package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.InventoryCustodyGate;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.commerce.ItemInventoryCustody;
import com.prosperitysmp.npc.commerce.ItemSaveProof;
import com.prosperitysmp.npc.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.scheduler.BukkitTask;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Main-thread coordinator for contract and partnership hand-overs. It never calls Vault, the transfer journal, the
 * cap allocator or the accounting projection: items go one way, player to destroyed. Order: check on main (session,
 * survival, world, plain items in inventory storage), hold the player's shared inventory gate, save the player and read
 * that save back, write HANDOVER_INTENT, check everything again on main (the inventory must be exactly as saved), take
 * the items in one tick and save, prove that save is the take's own and the items left the whole file, then settle (COMPLETED
 * or AMBIGUOUS). The take uses the shared item-repairs primitive (ItemInventoryCustody.collect and
 * ItemSaveProof.captureCollection) with one line. A hand-over left in HANDOVER_INTENT by a stop or crash is held at
 * the next start, never finished or retried.
 */
public final class ContractRuntime {
    /** What one hand-over is: a contract's or a partnership's material and quantity. */
    private static final class Flight {
        final Player player;
        final Material material;
        final int quantity;
        final int expectedBefore;
        final BooleanSupplier authority;
        final Consumer<Outcome> reply;
        final UUID runId = UUID.randomUUID();
        final PartnerOffer offer;
        UUID contractId;
        /** The one-item prototype, the inventory state saveBeforeTake saved, and that save read back. */
        CustodyBytes prototype;
        String preState;
        ItemSaveProof.Baseline baseline;
        Flight(Player player, Material material, int quantity, int expectedBefore, BooleanSupplier authority,
               Consumer<Outcome> reply, PartnerOffer offer) {
            this.player = player; this.material = material; this.quantity = quantity; this.expectedBefore = expectedBefore;
            this.authority = authority; this.reply = reply; this.offer = offer;
        }
    }
    /** A finished request: the repository result, or only a line for a refusal before any SQL. */
    public record Outcome(TownContractRepository.Result result, String line) {
        static Outcome of(TownContractRepository.Result result) { return new Outcome(result, null); }
        static Outcome refused(String line) { return new Outcome(null, line); }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }

    private final ProsperityNpcPlugin plugin;
    private final TownContractRepository contracts;
    private final PartnershipRepository partnerships;
    private final InventoryCustodyGate gate;
    private final ItemSaveProof saveProof;
    private final Path playerDataWorld;
    private final EconomyClock days;
    private final long settleDelayMillis;
    private final ArrayBlockingQueue<Runnable> completions = new ArrayBlockingQueue<>(64);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor worker;
    private final BukkitTask ticker;
    private final java.util.Set<UUID> inFlight = new java.util.HashSet<>();
    private volatile boolean closed;
    private volatile int heldCount = -1;
    private volatile int activePartnerships = -1;

    /** Main only. recovered: the hand-overs held by startup recovery, alerted here once. */
    public ContractRuntime(ProsperityNpcPlugin plugin, Database database, InventoryCustodyGate gate, EconomyClock days,
                           List<TownContractRepository.Held> recovered) {
        requireMain();
        this.plugin = Objects.requireNonNull(plugin);
        this.contracts = new TownContractRepository(database);
        this.partnerships = new PartnershipRepository(database);
        this.gate = Objects.requireNonNull(gate);
        this.days = Objects.requireNonNull(days);
        saveProof = new ItemSaveProof(plugin.getServer().getClass().getClassLoader());
        playerDataWorld = saveProof.mainWorldFolder(plugin.getServer()).orElse(null);
        // DEV bot proof only: absent means no delay.
        settleDelayMillis = Math.clamp(Long.getLong("prosperitynpc.dev.contractSettleDelayMs", 0L), 0L, 30_000L);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread thread = new Thread(r, "npc-contract-sql"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { drained.complete(null); }
        };
        BukkitTask scheduled = null;
        try {
            plugin.trackDatabaseWorker(worker);
            scheduled = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (int i = 0; i < 32; i++) { Runnable next = completions.poll(); if (next == null) break; next.run(); }
            }, 1, 1);
            ticker = scheduled;
        } catch (RuntimeException | Error failure) {
            closed = true; if (scheduled != null) scheduled.cancel(); worker.shutdown(); throw failure;
        }
        for (var held : recovered) alertHeld(held.contractId());
        if (!recovered.isEmpty()) heldCount = -1;
    }

    public TownContractRepository contracts() { return contracts; }
    public PartnershipRepository partnerships() { return partnerships; }

    /**
     * Database worker (any thread but main): everything the contracts view needs for one player at one resident.
     * Reads only; a partnership past its last day is lapsed by the read, as everywhere else.
     */
    public com.prosperitysmp.npc.interaction.ContractPresentation.Facts facts(ContractCatalogue catalogue, boolean contractsOn,
            boolean partnershipsOn, boolean rankLive, UUID player, UUID resident, java.util.Set<UUID> reachable, Instant at)
            throws java.sql.SQLException {
        LocalDate day = days.dayAt(at);
        var current = contracts.current(player, at);
        int slots = contractsOn ? contracts.slotsUsed(player, day) : 0;
        int kept = contracts.keptFor(resident, player, TownContractRepository.keptSince(day));
        Optional<Partnership> partnership = Optional.empty();
        boolean dayUsed = false, home = false, top = false;
        int seats = 0;
        if (partnershipsOn) {
            partnership = partnerships.current(player, day, at);
            dayUsed = contracts.partnerDayUsed(player, day);
            home = contracts.ownsEnrolledHome(player);
            seats = contracts.seatsUsed(resident, day);
            top = rankLive && contracts.advertisingTop(player);
        }
        return new com.prosperitysmp.npc.interaction.ContractPresentation.Facts(catalogue, contractsOn, partnershipsOn, rankLive,
                current, slots, kept, partnership, dayUsed, home, seats, top, reachable, at, day);
    }

    /** The save proof and the player-data folder are available. Without them no hand-over starts (fail closed). */
    public boolean ready() { return !closed && saveProof.available() && playerDataWorld != null; }

    /**
     * Main only. Hands over the items for an ACTIVE contract to actualResident (the authoritative avatar the player is
     * talking to). Every later outcome goes to reply on the main thread.
     */
    public void handover(Player player, UUID contractId, UUID actualResident, BooleanSupplier authority, Consumer<Outcome> reply) {
        requireMain(); Objects.requireNonNull(player); Objects.requireNonNull(contractId); Objects.requireNonNull(actualResident);
        String refusal = refusal(player, authority);
        if (refusal != null) { reply.accept(Outcome.refused(refusal)); return; }
        UUID playerId = player.getUniqueId();
        if (!inFlight.add(playerId)) { reply.accept(Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.BUSY)); return; }
        boolean queued = submit(() -> {
            Optional<TownContract> found = contracts.find(contractId, playerId);
            post(() -> {
                if (found.isEmpty() || found.get().status() != TownContract.Status.ACTIVE) {
                    inFlight.remove(playerId);
                    var code = found.isEmpty() ? TownContractRepository.Code.NOT_FOUND : switch (found.get().status()) {
                        case COMPLETED, RESOLVED_CONSUMED -> TownContractRepository.Code.ALREADY_COMPLETED;
                        case EXPIRED -> TownContractRepository.Code.EXPIRED;
                        case AMBIGUOUS -> TownContractRepository.Code.HANDOVER_HELD;
                        case HANDOVER_INTENT -> TownContractRepository.Code.BUSY;
                        default -> TownContractRepository.Code.NOT_ACTIVE;
                    };
                    reply.accept(Outcome.of(new TownContractRepository.Result(code, found, Optional.empty(), 0)));
                    return;
                }
                ContractDefinition d = found.get().definition();
                start(player, d.material(), d.quantity(), authority, reply, null,
                        f -> contracts.beginHandover(contractId, playerId, actualResident, f.runId, now()));
            });
        }, () -> { inFlight.remove(playerId); reply.accept(Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)); });
        if (!queued) inFlight.remove(playerId);
    }

    /** Main only. Hands over the materials that start or renew a partnership with the offer's resident. */
    public void partner(Player player, PartnerOffer offer, boolean start, ContractCatalogue catalogue,
                        BooleanSupplier authority, Consumer<Outcome> reply) {
        requireMain(); Objects.requireNonNull(player); Objects.requireNonNull(offer); Objects.requireNonNull(catalogue);
        String refusal = refusal(player, authority);
        if (refusal != null) { reply.accept(Outcome.refused(refusal)); return; }
        UUID playerId = player.getUniqueId();
        if (!inFlight.add(playerId)) { reply.accept(Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.BUSY)); return; }
        var terms = catalogue.terms(offer);
        start(player, offer.material(), offer.quantity(), authority, reply, offer,
                f -> contracts.beginPartnership(start, playerId, offer, terms, f.runId, now(), days.dayAt(now())));
    }

    @FunctionalInterface private interface Begin { TownContractRepository.Result run(Flight f) throws Exception; }

    /** Main: inventory check and gate, then the intent on the worker. Owns inFlight from here. */
    private void start(Player player, String materialName, int quantity, BooleanSupplier authority, Consumer<Outcome> reply,
                       PartnerOffer offer, Begin begin) {
        UUID playerId = player.getUniqueId();
        Material material = Material.getMaterial(materialName);
        final int have, before;
        try {
            if (material == null || !material.isItem() || material.isAir()) {
                inFlight.remove(playerId); reply.accept(Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)); return;
            }
            have = ItemInventoryCustody.storageUnits(player, material);
            before = ItemInventoryCustody.totalUnits(player, material);
        } catch (RuntimeException | LinkageError unavailable) {
            inFlight.remove(playerId); reply.accept(Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)); return;
        }
        if (have < quantity) {
            inFlight.remove(playerId);
            reply.accept(Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.notEnough(quantity, materialName, have)));
            return;
        }
        Flight f = new Flight(player, material, quantity, before, authority, reply, offer);
        if (!gate.tryHold(playerId, f)) {
            inFlight.remove(playerId); reply.accept(Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.ITEMS_BUSY)); return;
        }
        // Save before the take so the removal proof has a file to compare the take's own save against.
        try {
            f.prototype = new CustodyBytes(ItemInventoryCustody.prototype(material).serializeAsBytes());
            f.preState = ItemInventoryCustody.saveBeforeTake(player, f.runId);
        } catch (RuntimeException | LinkageError unsaved) {
            finish(f, Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)); return;
        }
        boolean queued = submit(() -> {
            var baseline = saveProof.baseline(playerDataWorld, playerId, f.runId, List.of(f.prototype));
            if (baseline.isEmpty()) {
                post(() -> finish(f, Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)));
                return;
            }
            TownContractRepository.Result result;
            try { result = begin.run(f); }
            catch (Exception uncertain) {
                // Nothing was taken. If the intent did commit, undo it now; if that fails too, startup recovery holds it.
                try { contracts.cancelUnconfirmedBegin(playerId, f.runId); }
                catch (Exception none) { /* No row, or the next start holds it for staff. */ }
                post(() -> finish(f, Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)));
                return;
            }
            post(() -> { f.baseline = baseline.get(); began(f, result); });
        }, () -> finish(f, Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)));
        if (!queued) { gate.release(playerId, f); inFlight.remove(playerId); }
    }

    /** Main, one tick: every check again, then the take and the save. A failed check cancels with nothing taken. */
    private void began(Flight f, TownContractRepository.Result result) {
        if (closed) return;
        if (result.code() != TownContractRepository.Code.HANDOVER_STARTED || result.contract().isEmpty()) {
            finish(f, Outcome.of(result)); return;
        }
        f.contractId = result.contract().get().contractId();
        String refusal;
        try {
            refusal = refusal(f.player, f.authority);
            if (refusal == null && (ItemInventoryCustody.storageUnits(f.player, f.material) < f.quantity
                    || ItemInventoryCustody.totalUnits(f.player, f.material) != f.expectedBefore))
                refusal = com.prosperitysmp.npc.interaction.ContractPresentation.notEnough(f.quantity, f.material.name(),
                        ItemInventoryCustody.storageUnits(f.player, f.material));
            if (refusal == null && !ItemInventoryCustody.inventoryState(f.player).equals(f.preState))
                refusal = com.prosperitysmp.npc.interaction.ContractPresentation.ITEMS_CHANGED;
        } catch (RuntimeException | LinkageError unclear) { refusal = com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE; }
        if (refusal != null) {
            String message = refusal;
            submit(() -> {
                contracts.cancelBeforeTake(f.contractId, f.player.getUniqueId(), f.runId);
                post(() -> finish(f, Outcome.refused(message)));
            }, () -> held(f));
            return;
        }
        ItemCollectionEvidence evidence;
        try { evidence = ItemInventoryCustody.collect(f.player, f.runId, List.of(new ItemInventoryCustody.Take(f.material, f.quantity))); }
        catch (RuntimeException | LinkageError unclear) { held(f); return; }
        // INFO only with the DEV settle delay, so a bot proof can stop the server right after this line.
        if (settleDelayMillis > 0) plugin.getLogger().info("Contract handover " + f.contractId + ": items taken; proving the save.");
        var snapshot = saveProof.captureCollection(playerDataWorld, f.player.getUniqueId(), List.of(new ItemSaveProof.Expectation(
                f.prototype, Math.max(0, f.expectedBefore - f.quantity), f.quantity)), f.baseline);
        ItemCollectionEvidence taken = evidence;
        submit(() -> {
            if (settleDelayMillis > 0) Thread.sleep(settleDelayMillis);
            // A void saveData() is not proof: read and force the saved file first.
            boolean durable = snapshot.isPresent() && saveProof.verify(snapshot.get());
            Instant at = now();
            TownContractRepository.PartnerTerms terms = f.offer == null ? null : currentTerms(f.offer);
            var settled = contracts.finishHandover(f.contractId, f.player.getUniqueId(), f.runId, f.expectedBefore,
                    taken.withDurable(durable), at, days.dayAt(at), terms);
            post(() -> {
                if (settled.code() == TownContractRepository.Code.HANDOVER_HELD) { alertHeld(f.contractId); heldCount = -1; }
                if (settled.partnership().isPresent()) activePartnerships = -1;
                finish(f, Outcome.of(settled));
            });
        }, () -> held(f));
    }

    /**
     * The partnership terms from the config at settle time, with the offer's perks, material and quantity as of now.
     * The repository settles at the lower of these and the terms kept with the hand-in at begin, and holds when the
     * material changed or the quantity went up.
     */
    private TownContractRepository.PartnerTerms currentTerms(PartnerOffer offer) {
        ContractCatalogue catalogue = plugin.contractCatalogue();
        var now = catalogue.partnerOffer(offer.residentId());
        return now.map(catalogue::terms).orElse(null);
    }

    /** After the take, any doubt holds the hand-over for staff. Never retried. */
    private void held(Flight f) {
        if (closed) return;
        boolean queued = submit(() -> {
            contracts.markAmbiguous(f.contractId, f.player.getUniqueId(), f.runId);
            post(() -> { alertHeld(f.contractId); heldCount = -1; finish(f, Outcome.refused(
                    com.prosperitysmp.npc.interaction.ContractPresentation.held(f.contractId))); });
        }, () -> { alertHeld(f.contractId); finish(f, Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.held(f.contractId))); });
        if (!queued) finish(f, Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.held(f.contractId)));
    }

    private void finish(Flight f, Outcome outcome) {
        gate.release(f.player.getUniqueId(), f);
        inFlight.remove(f.player.getUniqueId());
        if (closed) return;
        try { f.reply.accept(outcome); } catch (RuntimeException ignored) { }
    }

    /** Main: the checks that need no SQL. Null means the hand-over may go on. */
    private String refusal(Player player, BooleanSupplier authority) {
        ContractCatalogue catalogue = plugin.contractCatalogue();
        try {
            if (closed || !ready() || !present(player) || !authority.getAsBoolean())
                return com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE;
            if (catalogue.requireSurvival() && player.getGameMode() != GameMode.SURVIVAL)
                return com.prosperitysmp.npc.interaction.ContractPresentation.SURVIVAL_ONLY;
            if (!catalogue.worldAllowed(player.getWorld().getName()))
                return com.prosperitysmp.npc.interaction.ContractPresentation.WRONG_WORLD;
            var view = player.getOpenInventory();
            if (view.getTopInventory().getType() != InventoryType.CRAFTING
                    || player.getItemOnCursor() != null && !player.getItemOnCursor().isEmpty())
                return com.prosperitysmp.npc.interaction.ContractPresentation.CLOSE_INVENTORY;
            return null;
        } catch (RuntimeException | LinkageError unclear) {
            return com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE;
        }
    }

    private boolean present(Player player) {
        try {
            return !closed && plugin.isEnabled() && player.isOnline() && !player.isDead()
                    && Bukkit.getPlayer(player.getUniqueId()) == player && player.hasPermission("prosperitynpc.use");
        } catch (RuntimeException failure) { return false; }
    }

    private void alertHeld(UUID contractId) {
        if (contractId == null) return;
        plugin.alerts().raise(StaffAlerts.Kind.HANDOVER_HELD, contractId.toString(),
                "Contract handover held for review. Settle it with /npctown contract-resolve " + contractId + ".");
        plugin.getLogger().warning("Contract handover held for staff review: " + contractId);
    }

    /**
     * Main only, for /npcadmin health: the last known held count and active partnerships. A value not read yet is
     * refreshed in the background and shows as "?" until then.
     */
    public String heldText() { refreshCounts(); return heldCount < 0 ? "?" : Integer.toString(heldCount); }
    public String activeText() { refreshCounts(); return activePartnerships < 0 ? "?" : Integer.toString(activePartnerships); }

    private boolean refreshing;
    private void refreshCounts() {
        requireMain();
        if (refreshing || closed) return;
        refreshing = true;
        boolean queued = submit(() -> {
            int held = contracts.heldCount();
            int active = partnerships.countActive(days.today());
            post(() -> { heldCount = held; activePartnerships = active; refreshing = false; });
        }, () -> refreshing = false);
        if (!queued) refreshing = false;
    }

    private Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }

    private boolean submit(Work work, Runnable fail) {
        try {
            worker.execute(() -> {
                try { work.run(); } catch (Exception failure) { post(fail); }
            });
            return true;
        } catch (RuntimeException busy) { fail.run(); return false; }
    }

    private void post(Runnable action) {
        if (!closed && !completions.offer(action)) { closed = true; worker.shutdown(); }
    }

    /** An in-flight HANDOVER_INTENT is left for startup recovery, never finished on shutdown. */
    public CompletionStage<Void> closeAsync() {
        requireMain();
        closed = true; ticker.cancel(); completions.clear(); inFlight.clear(); worker.shutdown();
        return drained.minimalCompletionStage();
    }

    /** Day used by today's reads. */
    public LocalDate today() { return days.today(); }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Contract hand-overs require main thread");
    }
}
