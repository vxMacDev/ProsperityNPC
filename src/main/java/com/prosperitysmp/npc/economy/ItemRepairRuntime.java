package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.arm.ArmAdapter;
import com.prosperitysmp.npc.arm.OwnershipFence;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.InventoryCustodyGate;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.commerce.ItemDeliveryRuntime;
import com.prosperitysmp.npc.commerce.ItemInventoryCustody;
import com.prosperitysmp.npc.commerce.ItemSaveProof;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.Property;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Main-thread coordinator for item repairs. Single flight server-wide, one SQL worker, a bounded completion queue
 * pumped every tick. It never calls Vault, the transfer journal, the cap allocator or the accounting projection.
 * Order: freeze the quote, hold the home's lease and the player's inventory gate, save the player, read that save back,
 * write INTENT, re-check on main (the inventory must be exactly as saved), take the items in one tick and save, prove
 * that save is the take's own and the items left the whole file, then settle (apply, return or hold). A hand-in left in
 * INTENT by a crash or shutdown is held by startup recovery for the offline tool, never finished or retried.
 */
public final class ItemRepairRuntime {
    /** What the desk shows for one incident: the cash price and the live item list, or why items cannot be used. */
    public record Preview(long incidentId, IncidentType type, long cashCents, List<ItemRepairRecipes.Priced> lines,
                          String priceVersion, ItemRepairRecipes.Unavailable reason) {
        public Preview { lines = List.copyOf(lines); }
        public boolean available() { return reason == null && !lines.isEmpty() && priceVersion != null; }
        public String list() { return ItemRepairRecipes.describe(lines); }
        public String typeLabel() {
            String s = type.name().replace('_', ' ').toLowerCase(Locale.ROOT);
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    private static final class Flight {
        final Player player;
        final Property property;
        final OwnershipFence.Lease lease;
        final Preview shown;
        /** The price publication the frozen unit prices came from (the request-time preview, not the desk render). */
        final String priceVersion;
        final List<ItemInventoryCustody.Take> takes;
        final List<ItemRepairQuote.Line> lines;
        final List<ItemRepairQuote.ReturnStack> plan;
        final BooleanSupplier authorized;
        final Consumer<String> reply;
        final LocalDate quoteDay;
        final UUID collectionId = UUID.randomUUID();
        ItemRepairQuote quote;
        /** The inventory state saveBeforeTake saved; the take must find exactly this. */
        String preState;
        /** That save, read back on the worker before the INTENT. */
        ItemSaveProof.Baseline baseline;
        boolean taken;
        Flight(Player player, Property property, OwnershipFence.Lease lease, Preview shown, String priceVersion,
               List<ItemInventoryCustody.Take> takes, List<ItemRepairQuote.Line> lines, List<ItemRepairQuote.ReturnStack> plan,
               BooleanSupplier authorized, Consumer<String> reply, LocalDate quoteDay) {
            this.player = player;
            this.property = property;
            this.lease = lease;
            this.shown = shown;
            this.priceVersion = Objects.requireNonNull(priceVersion);
            this.takes = takes;
            this.lines = lines;
            this.plan = plan;
            this.authorized = authorized;
            this.reply = reply;
            this.quoteDay = quoteDay;
        }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }

    private final ProsperityNpcPlugin plugin;
    private final ItemRepairRepository repository;
    private final ItemSaveProof saveProof;
    private final Path playerDataWorld;
    private final InventoryCustodyGate gate;
    private final Supplier<ItemDeliveryRuntime> delivery;
    private final EconomyClock days;
    private final long settleDelayMillis;
    private final UUID runId = UUID.randomUUID();
    private final ArrayBlockingQueue<Runnable> completions = new ArrayBlockingQueue<>(64);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor worker;
    private final BukkitTask ticker;
    private final Set<UUID> returnsPending = new HashSet<>();
    private final Set<UUID> claiming = new HashSet<>();
    private volatile boolean closed;
    private boolean ready;
    private Flight flight;

    public ItemRepairRuntime(ProsperityNpcPlugin plugin, Database database, InventoryCustodyGate gate,
                             Supplier<ItemDeliveryRuntime> delivery) {
        requireMain();
        this.plugin = Objects.requireNonNull(plugin);
        this.repository = new ItemRepairRepository(database);
        this.gate = Objects.requireNonNull(gate);
        this.delivery = Objects.requireNonNull(delivery);
        saveProof = new ItemSaveProof(plugin.getServer().getClass().getClassLoader());
        playerDataWorld = saveProof.mainWorldFolder(plugin.getServer()).orElse(null);
        days = new EconomyClock(Clock.systemUTC(), ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago")),
                plugin.getConfig().getInt("clock.daily-reset-hour", 4));
        // Test hook: delays settlement so a test can stop the server mid hand-in. Absent means no delay.
        settleDelayMillis = Math.clamp(Long.getLong("prosperitynpc.dev.itemRepairSettleDelayMs", 0L), 0L, 30_000L);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread thread = new Thread(r, "npc-item-repair-sql");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { drained.complete(null); }
        };
        BukkitTask scheduled = null;
        try {
            plugin.trackDatabaseWorker(worker);
            scheduled = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (int i = 0; i < 32; i++) {
                    Runnable next = completions.poll();
                    if (next == null) break;
                    next.run();
                }
            }, 1, 1);
            ticker = scheduled;
            submit(() -> {
                while (repository.recoverOtherRuns(runId, 100) != 0) { if (closed) return; }
                var held = repository.listOpen(ItemRepairRepository.MAX_SWEEP);
                var waiting = repository.playersWithPendingReturns(1024);
                post(() -> {
                    ready = true;
                    returnsPending.addAll(waiting);
                    for (UUID reference : heldReferences(held)) alertHeld(reference);
                });
            }, () -> plugin.getLogger().warning("Item repair startup recovery did not finish; item repairs stay held."));
        } catch (RuntimeException | Error failure) {
            closed = true;
            if (scheduled != null) scheduled.cancel();
            worker.shutdown();
            throw failure;
        }
    }

    /** Recovery drained, the save proof available, a player-data folder found and the delivery runtime present. */
    public boolean ready() {
        requireMain();
        return ready && !closed && saveProof.available() && playerDataWorld != null && delivery.get() != null;
    }

    /** Main only: true while this player has items to collect (shown on the desk). */
    public boolean hasReturnsPending(UUID player) {
        requireMain();
        return returnsPending.contains(player);
    }

    /** Main only: prices the incident's item list from the live price book. Never charges or takes anything. */
    public Preview preview(long incidentId, String incidentType, long cashCents) {
        requireMain();
        IncidentType type;
        try {
            type = IncidentType.valueOf(incidentType);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
        var settings = plugin.itemRepairSettings();
        String version = plugin.commercePrices().version();
        if (!settings.active() || cashCents != type.cashRepairCents())
            return new Preview(incidentId, type, type.cashRepairCents(), List.of(), version, ItemRepairRecipes.Unavailable.NO_RECIPE);
        var recipe = settings.recipe(type);
        if (recipe.isEmpty())
            return new Preview(incidentId, type, cashCents, List.of(), version, ItemRepairRecipes.Unavailable.NO_RECIPE);
        var result = ItemRepairRecipes.quote(recipe.get(), cashCents, settings.ratioBps(), settings.maxUnits(), settings.maxStacks(),
                plugin.commercePrices()::unitCents, ItemRepairRuntime::maxStack);
        if (result instanceof ItemRepairRecipes.Quoted quoted && version != null)
            return new Preview(incidentId, type, cashCents, quoted.lines(), version, null);
        var reason = result instanceof ItemRepairRecipes.Refused refused ? refused.reason() : ItemRepairRecipes.Unavailable.PRICE_UNKNOWN;
        return new Preview(incidentId, type, cashCents, List.of(), version, reason);
    }

    /**
     * The first admission gate, read live on every hand-in: the feature flag (off says so), then a valid config,
     * the system and economy switches and a ready runtime. Null means the hand-in may go on to the player checks.
     */
    static String admissionRefusal(ItemRepairSettings settings, boolean system, boolean economy, boolean ready) {
        if (!settings.enabled()) return ItemRepairCopy.OFF;
        if (!settings.active() || !system || !economy || !ready) return ItemRepairCopy.UNAVAILABLE;
        return null;
    }

    /** Stack size of an item material, 0 when the name is not a current item. */
    public static int maxStack(String name) {
        Material material = Material.getMaterial(name);
        if (material == null || material.isLegacy() || !material.isItem() || material.isAir()) return 0;
        return material.getMaxStackSize();
    }

    /**
     * Main only. Hands in the shown items for the named incident. False means nothing started; the reason was
     * already sent. True means the hand-in started and every later outcome is sent through reply.
     */
    public boolean request(Player player, long incidentId, Preview shown, BooleanSupplier authorized, Consumer<String> reply) {
        requireMain();
        Objects.requireNonNull(player);
        Objects.requireNonNull(authorized);
        Objects.requireNonNull(reply);
        var settings = plugin.itemRepairSettings();
        String closedReason = admissionRefusal(settings, plugin.killSwitches().isSystemEnabled(), plugin.killSwitches().isEconomyEnabled(),
                ready() && plugin.armAdapter() != null && plugin.propertyService() != null && plugin.ownershipFence() != null);
        if (closedReason != null) {
            reply.accept(closedReason);
            return false;
        }
        if (flight != null) {
            reply.accept(ItemRepairCopy.HOME_BUSY);
            return false;
        }
        if (returnsPending.contains(player.getUniqueId())) {
            reply.accept(ItemRepairCopy.RETURNS_FIRST);
            return false;
        }
        if (settings.requireSurvival() && player.getGameMode() != GameMode.SURVIVAL) {
            reply.accept(ItemRepairCopy.SURVIVAL_ONLY);
            return false;
        }
        if (!inventoryClear(player)) {
            reply.accept(ItemRepairCopy.CLOSE_INVENTORY);
            return false;
        }
        if (!present(player) || !authorized.getAsBoolean()) {
            reply.accept(ItemRepairCopy.UNAVAILABLE);
            return false;
        }
        var owner = plugin.armAdapter().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        Property property = owner == null ? null
                : plugin.propertyService().findByWorldRegion(owner.worldId(), owner.regionId()).orElse(null);
        if (property == null || !property.enabled() || !player.getUniqueId().equals(property.ownerUuid())) {
            reply.accept(ItemRepairCopy.NOT_HERE);
            return false;
        }
        if (plugin.propertyService().reconcileWriting(property.propertyId())) {
            reply.accept(ItemRepairCopy.HOME_BUSY);
            return false;
        }
        if (shown == null || shown.incidentId() != incidentId || !shown.available()) {
            reply.accept(ItemRepairCopy.UNAVAILABLE);
            return false;
        }
        Preview current = preview(incidentId, shown.type().name(), shown.cashCents());
        if (current == null || !current.available()) {
            reply.accept(ItemRepairCopy.UNAVAILABLE);
            return false;
        }
        if (needsMore(current, shown)) {
            reply.accept(ItemRepairCopy.PRICES_CHANGED);
            return false;
        }
        // Freeze the shown quantities at today's prices: never fewer items than shown, never below the required value.
        var takes = new ArrayList<ItemInventoryCustody.Take>();
        var lines = new ArrayList<ItemRepairQuote.Line>();
        var plan = new ArrayList<ItemRepairQuote.ReturnStack>();
        var missing = new ArrayList<ItemRepairRecipes.Line>();
        try {
            for (int i = 0; i < shown.lines().size(); i++) {
                var line = shown.lines().get(i);
                Material material = Material.getMaterial(line.material());
                int stack = maxStack(line.material());
                if (material == null || stack < 2) {
                    reply.accept(ItemRepairCopy.UNAVAILABLE);
                    return false;
                }
                int inStorage = ItemInventoryCustody.storageUnits(player, material);
                if (inStorage < line.quantity()) {
                    missing.add(new ItemRepairRecipes.Line(line.material(), line.quantity() - inStorage));
                    continue;
                }
                long unit = current.lines().get(i).unitCents();
                takes.add(new ItemInventoryCustody.Take(material, line.quantity()));
                lines.add(new ItemRepairQuote.Line(line.material(), line.quantity(), unit,
                        new CustodyBytes(ItemInventoryCustody.prototype(material).serializeAsBytes()),
                        ItemInventoryCustody.totalUnits(player, material)));
                for (int amount : ItemRepairRecipes.chunks(line.quantity(), stack))
                    plan.add(new ItemRepairQuote.ReturnStack(i, amount,
                            new CustodyBytes(new ItemStack(material, amount).serializeAsBytes())));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            reply.accept(ItemRepairCopy.UNAVAILABLE);
            return false;
        }
        if (!missing.isEmpty()) {
            reply.accept(ItemRepairCopy.missing(ItemRepairRecipes.describe(missing)));
            return false;
        }
        final OwnershipFence.Lease lease;
        try {
            var fresh = plugin.armAdapter().ownerReadRegion(property.worldUuid(), property.regionId(), player.getUniqueId()).orElseThrow();
            lease = plugin.ownershipFence().acquire(fresh);
        } catch (RuntimeException busy) {
            reply.accept(ItemRepairCopy.HOME_BUSY);
            return false;
        }
        Flight next = new Flight(player, property, lease, shown, current.priceVersion(), List.copyOf(takes), List.copyOf(lines),
                List.copyOf(plan), authorized, reply, days.today());
        if (!gate.tryHold(player.getUniqueId(), next)) {
            lease.releaseReconciled();
            reply.accept(ItemRepairCopy.HOME_BUSY);
            return false;
        }
        flight = next;
        // Save before the take so the removal proof has a file to compare the take's own save against.
        try { next.preState = ItemInventoryCustody.saveBeforeTake(player, next.collectionId); }
        catch (RuntimeException | LinkageError unsaved) {
            abandonBeforeTake(next, ItemRepairCopy.UNAVAILABLE);
            return false;
        }
        reply.accept(ItemRepairCopy.CHECKING);
        submit(() -> begin(next), () -> abandonBeforeTake(next, ItemRepairCopy.UNAVAILABLE));
        return true;
    }

    /**
     * Main only, for /property repair <id> items: looks up the open incident at the home the player stands in, then
     * runs the same hand-in with today's item list. The reply says what happened.
     */
    public void requestTyped(Player player, long incidentId, BooleanSupplier authorized, Consumer<String> reply) {
        requireMain();
        Objects.requireNonNull(player);
        Objects.requireNonNull(authorized);
        Objects.requireNonNull(reply);
        if (!plugin.itemRepairSettings().enabled()) {
            reply.accept(ItemRepairCopy.OFF);
            return;
        }
        if (!ready() || plugin.armAdapter() == null || plugin.propertyService() == null) {
            reply.accept(ItemRepairCopy.UNAVAILABLE);
            return;
        }
        var owner = plugin.armAdapter().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        Property property = owner == null ? null
                : plugin.propertyService().findByWorldRegion(owner.worldId(), owner.regionId()).orElse(null);
        if (property == null || !property.enabled() || !player.getUniqueId().equals(property.ownerUuid())) {
            reply.accept(ItemRepairCopy.NOT_HERE);
            return;
        }
        UUID propertyId = property.propertyId();
        submit(() -> {
            var type = repository.openIncidentType(propertyId, incidentId);
            post(() -> {
                if (!present(player)) return;
                if (type.isEmpty()) {
                    reply.accept(ItemRepairCopy.NO_SUCH_PROBLEM);
                    return;
                }
                Preview shown = preview(incidentId, type.get().name(), type.get().cashRepairCents());
                request(player, incidentId, shown, authorized, reply);
            });
        }, () -> {
            if (present(player)) reply.accept(ItemRepairCopy.UNAVAILABLE);
        });
    }

    /** Worker: read back the before-take save, read the incident snapshot, freeze the quote, write INTENT. */
    private void begin(Flight f) throws Exception {
        var baseline = saveProof.baseline(playerDataWorld, f.player.getUniqueId(), f.collectionId,
                f.lines.stream().map(ItemRepairQuote.Line::prototype).toList());
        if (baseline.isEmpty()) {
            post(() -> abandonBeforeTake(f, ItemRepairCopy.UNAVAILABLE));
            return;
        }
        Instant at = Instant.now();
        var repair = repository.currentRepair(f.property.propertyId(), f.player.getUniqueId(), f.property.ownershipEpoch(),
                f.shown.incidentId(), at, days.dayAt(at), f.quoteDay);
        if (repair.isEmpty() || repair.get().type() != f.shown.type()) {
            post(() -> abandonBeforeTake(f, ItemRepairCopy.NO_SUCH_PROBLEM));
            return;
        }
        var settings = plugin.itemRepairSettings();
        long cash = repair.get().cents();
        long value = 0;
        for (var line : f.lines) value = Math.addExact(value, Math.multiplyExact((long) line.quantity(), line.unitFullCents()));
        ItemRepairQuote quote;
        try {
            quote = new ItemRepairQuote(f.collectionId, f.property.propertyId(), f.player.getUniqueId(),
                    f.property.ownershipEpoch(), f.quoteDay, repair.get(), cash, settings.ratioBps(),
                    ItemRepairRecipes.requiredCents(cash, settings.ratioBps()), value, f.priceVersion, f.lines, f.plan);
        } catch (IllegalArgumentException cheaperThanCash) {
            post(() -> abandonBeforeTake(f, ItemRepairCopy.PRICES_CHANGED));
            return;
        }
        ItemRepairRepository.Begin begun;
        try { begun = repository.begin(quote, runId, at, days.dayAt(at)); }
        catch (Exception uncertain) {
            // Nothing was taken. If the INTENT row did commit, cancel it now; otherwise there is nothing to undo.
            try { repository.cancelBeforeTake(quote.collectionId(), quote.playerId(), runId, "BEGIN_UNCONFIRMED"); }
            catch (Exception none) { /* No row, or startup recovery holds it. */ }
            post(() -> abandonBeforeTake(f, ItemRepairCopy.UNAVAILABLE));
            return;
        }
        post(() -> {
            if (!begun.started()) {
                if (begun.refusal() == ItemRepairRepository.Refusal.RETURNS_PENDING) returnsPending.add(f.player.getUniqueId());
                abandonBeforeTake(f, switch (begun.refusal()) {
                    case RETURNS_PENDING -> ItemRepairCopy.RETURNS_FIRST;
                    case COLLECTION_OPEN -> ItemRepairCopy.ALREADY_WAITING;
                    case CASH_PENDING -> ItemRepairCopy.CASH_WAITING;
                    case TARGET_CHANGED -> ItemRepairCopy.NO_SUCH_PROBLEM;
                    case OWNERSHIP_CHANGED -> ItemRepairCopy.NOT_HERE;
                    case PLAYER_ITEMS_HELD -> ItemRepairCopy.UNAVAILABLE;
                });
                return;
            }
            f.quote = quote;
            f.baseline = baseline.get();
            take(f);
        });
    }

    /** Main, one tick: every check again, then the take and the save. Any failed check cancels with nothing taken. */
    private void take(Flight f) {
        if (closed || flight != f) return;
        var settings = plugin.itemRepairSettings();
        String refusal = null;
        try {
            if (!present(f.player) || !f.authorized.getAsBoolean()) refusal = ItemRepairCopy.UNAVAILABLE;
            else if (!settings.active() || !plugin.killSwitches().isSystemEnabled() || !plugin.killSwitches().isEconomyEnabled())
                refusal = settings.enabled() ? ItemRepairCopy.UNAVAILABLE : ItemRepairCopy.OFF;
            else if (!stillOwned(f)) refusal = ItemRepairCopy.NOT_HERE;
            else if (settings.requireSurvival() && f.player.getGameMode() != GameMode.SURVIVAL) refusal = ItemRepairCopy.SURVIVAL_ONLY;
            else if (!inventoryClear(f.player)) refusal = ItemRepairCopy.CLOSE_INVENTORY;
            else {
                Preview now = preview(f.shown.incidentId(), f.shown.type().name(), f.shown.cashCents());
                if (now == null || !now.available() || needsMore(now, f.shown)) refusal = ItemRepairCopy.PRICES_CHANGED;
                else if (!countsUnchanged(f) || !ItemInventoryCustody.inventoryState(f.player).equals(f.preState))
                    refusal = ItemRepairCopy.ITEMS_CHANGED;
            }
        } catch (RuntimeException | LinkageError unclear) { refusal = ItemRepairCopy.UNAVAILABLE; }
        if (refusal != null) {
            cancel(f, refusal);
            return;
        }
        f.taken = true;
        ItemCollectionEvidence evidence;
        try { evidence = ItemInventoryCustody.collect(f.player, f.collectionId, f.takes); }
        catch (RuntimeException | LinkageError unclear) {
            held(f, ItemRepairRepository.Uncertainty.TAKE_FAILED);
            return;
        }
        // Logged only with the test delay set, so a test can stop the server right after the take.
        if (settleDelayMillis > 0) plugin.getLogger().info("Item repair " + f.collectionId + ": items taken; proving the save.");
        boolean ownershipStillValid = stillOwned(f);
        var expectations = new ArrayList<ItemSaveProof.Expectation>();
        for (var line : f.quote.lines())
            expectations.add(new ItemSaveProof.Expectation(line.prototype(), line.beforeUnits() - line.quantity(), line.quantity()));
        var snapshot = saveProof.captureCollection(playerDataWorld, f.player.getUniqueId(), expectations, f.baseline);
        ItemCollectionEvidence taken = evidence;
        submit(() -> {
            if (settleDelayMillis > 0) Thread.sleep(settleDelayMillis);
            // A void saveData() is not proof: Paper can swallow a failed save. Read and force the file first.
            boolean durable = snapshot.isPresent() && saveProof.verify(snapshot.get());
            Instant at = Instant.now();
            var settled = repository.settle(f.collectionId, f.player.getUniqueId(), runId, taken.withDurable(durable),
                    ownershipStillValid, at, days.dayAt(at));
            Optional<List<Property>> reread = Optional.empty();
            if (settled.state() == ItemRepairRepository.State.APPLIED) {
                // The repair changed the home's condition row; install it before the lease is released.
                try { reread = Optional.of(plugin.propertyService().readRow(f.property.propertyId()).map(List::of).orElse(List.of())); }
                catch (Exception unreadable) { reread = Optional.empty(); }
            }
            var rows = reread;
            post(() -> settled(f, settled, rows));
        }, () -> held(f, ItemRepairRepository.Uncertainty.SETTLE_FAILED));
    }

    private void settled(Flight f, ItemRepairRepository.Settlement settlement, Optional<List<Property>> reread) {
        switch (settlement.state()) {
            case APPLIED -> {
                try { plugin.propertyService().installLeased(List.of(f.property), reread.orElse(null), "Item repair " + f.collectionId); }
                catch (RuntimeException cacheFailure) {
                    plugin.getLogger().warning("Item repair " + f.collectionId
                            + " could not refresh the cached home; the next ownership check does.");
                }
                finish(f, ItemRepairCopy.done(ItemRepairRecipes.describe(f.quote.lines()), f.collectionId));
            }
            case RETURN_ISSUED -> {
                returnsPending.add(f.player.getUniqueId());
                finish(f, ItemRepairCopy.HOME_CHANGED);
                if (present(f.player)) claimReturns(f.player, f.reply);
            }
            default -> {
                alertHeld(f.collectionId);
                finish(f, ItemRepairCopy.held(f.collectionId));
            }
        }
    }

    /** After the take, any doubt holds the hand-in for the offline tool. Never retried, never returned automatically. */
    private void held(Flight f, ItemRepairRepository.Uncertainty reason) {
        if (closed) return;
        submit(() -> {
            repository.markAmbiguous(f.collectionId, f.player.getUniqueId(), runId, reason);
            post(() -> {
                alertHeld(f.collectionId);
                finish(f, ItemRepairCopy.held(f.collectionId));
            });
        }, () -> {
            alertHeld(f.collectionId);
            finish(f, ItemRepairCopy.held(f.collectionId));
        });
    }

    /** Before the take: INTENT becomes CANCELLED for this run. Nothing was taken. */
    private void cancel(Flight f, String message) {
        submit(() -> {
            repository.cancelBeforeTake(f.collectionId, f.player.getUniqueId(), runId, "CANCELLED_BEFORE_TAKE");
            post(() -> finish(f, message));
        }, () -> {
            // The INTENT could not be cancelled: hold it like any unclear hand-in. Still nothing was taken.
            alertHeld(f.collectionId);
            finish(f, ItemRepairCopy.held(f.collectionId));
        });
    }

    private void abandonBeforeTake(Flight f, String message) { finish(f, message); }

    private void finish(Flight f, String message) {
        if (flight != f) return;
        flight = null;
        gate.release(f.player.getUniqueId(), f);
        try { f.lease.releaseReconciled(); }
        catch (RuntimeException mismatch) {
            plugin.getLogger().warning("Item repair " + f.collectionId + ": the home's lease needs staff review.");
            plugin.alerts().raise(StaffAlerts.Kind.ITEM_REPAIR_HELD, "lease-" + f.collectionId, ItemRepairCopy.staffAlert(f.collectionId));
        }
        if (!closed && present(f.player)) {
            try { f.reply.accept(message); } catch (RuntimeException ignored) { }
        }
    }

    private void alertHeld(UUID collectionId) {
        plugin.alerts().raise(StaffAlerts.Kind.ITEM_REPAIR_HELD, collectionId.toString(), ItemRepairCopy.staffAlert(collectionId));
        plugin.getLogger().warning("Item repair held for staff review: " + collectionId);
    }

    /**
     * Main only. Gives back items an item repair took, one stack at a time through the normal delivery. Works with
     * item-repairs off: it needs only the system switch and the database. A full inventory leaves the rest waiting.
     */
    public boolean claimReturns(Player player, Consumer<String> reply) {
        requireMain();
        Objects.requireNonNull(player);
        Objects.requireNonNull(reply);
        var deliveries = delivery.get();
        if (closed || !plugin.killSwitches().isSystemEnabled() || deliveries == null || !deliveries.ready() || !ready) {
            reply.accept(ItemRepairCopy.UNAVAILABLE);
            return false;
        }
        if (!claiming.add(player.getUniqueId())) {
            reply.accept(ItemRepairCopy.HOME_BUSY);
            return false;
        }
        boolean queued = submit(() -> {
            // A held delivery stops every delivery for this player, so say that instead of pointing at the command again.
            var held = repository.heldDelivery(player.getUniqueId());
            var waiting = held.isPresent() ? List.<ItemCustody>of() : repository.pendingReturns(player.getUniqueId());
            post(() -> {
                if (held.isPresent()) {
                    returnsPending.remove(player.getUniqueId());
                    claiming.remove(player.getUniqueId());
                    if (present(player)) reply.accept(ItemRepairCopy.held(held.get()));
                    return;
                }
                if (waiting.isEmpty()) {
                    returnsPending.remove(player.getUniqueId());
                    claiming.remove(player.getUniqueId());
                    if (present(player)) reply.accept(ItemRepairCopy.NO_RETURNS);
                    return;
                }
                deliverNext(player, waiting, 0, reply);
            });
        }, () -> {
            claiming.remove(player.getUniqueId());
            if (present(player)) reply.accept(ItemRepairCopy.UNAVAILABLE);
        });
        return queued;
    }

    /** Main only, read-only staff list: open, held and return-pending hand-ins. */
    public boolean staffList(Consumer<List<String>> reply) {
        requireMain();
        Objects.requireNonNull(reply);
        return submit(() -> {
            var rows = repository.listOpen(ItemRepairRepository.MAX_SWEEP);
            post(() -> reply.accept(staffLines(rows)));
        }, () -> reply.accept(List.of("Item repair list unavailable.")));
    }

    static List<String> staffLines(List<ItemRepairRepository.Summary> rows) {
        if (rows.isEmpty()) return List.of("No item hand-ins are open, held or waiting to give items back.");
        var out = new ArrayList<String>();
        for (var row : rows)
            out.add(row.collectionId() + " | player " + row.playerId() + " | problem #" + row.incidentId() + " | " + row.state()
                    + (row.outcome() == null ? "" : " " + row.outcome())
                    + (row.undeliveredReturns() > 0 ? " | " + row.undeliveredReturns() + " stacks to give back" : "")
                    + (row.heldReturn() == null ? ""
                            : "AMBIGUOUS".equals(row.heldReturnState()) ? " | held return " + row.heldReturn()
                            : " | return " + row.heldReturn() + " mid-delivery"));
        return List.copyOf(out);
    }

    /**
     * Startup alerts: every held hand-in, and every return stack that is held or was mid-delivery. Nothing gives a return
     * back before this runtime is ready, so a stack still mid-delivery here was cut off by a stop or crash. It is held as
     * INTERRUPTED_RUN and gets the same alert as a stack held during delivery.
     */
    static List<UUID> heldReferences(List<ItemRepairRepository.Summary> rows) {
        var out = new ArrayList<UUID>();
        for (var row : rows) {
            if (row.state() == ItemRepairRepository.State.AMBIGUOUS) out.add(row.collectionId());
            if (row.heldReturn() != null) out.add(row.heldReturn());
        }
        return List.copyOf(out);
    }

    private void deliverNext(Player player, List<ItemCustody> waiting, int index, Consumer<String> reply) {
        if (index >= waiting.size()) {
            claiming.remove(player.getUniqueId());
            returnsPending.remove(player.getUniqueId());
            return;
        }
        var deliveries = delivery.get();
        if (closed || deliveries == null || !present(player)) {
            claiming.remove(player.getUniqueId());
            return;
        }
        ItemCustody custody = waiting.get(index);
        String item = itemName(custody);
        // The delivery runtime calls back even after the player left, so the claim always clears and a held return always
        // reaches staff. Only the chat lines need the player here.
        deliveries.deliver(player, custody, () -> present(player), result -> {
            switch (result) {
                case DELIVERED -> {
                    if (present(player)) reply.accept(ItemRepairCopy.returned(custody.quantity(), item));
                    deliverNext(player, waiting, index + 1, reply);
                }
                case NO_SPACE -> {
                    claiming.remove(player.getUniqueId());
                    if (present(player)) reply.accept(ItemRepairCopy.MAKE_ROOM);
                }
                case HELD -> {
                    // Nothing more can be delivered to this player until staff settle it, so the desk stops offering it.
                    claiming.remove(player.getUniqueId());
                    returnsPending.remove(player.getUniqueId());
                    alertHeld(custody.requestNonce());
                    if (present(player)) reply.accept(ItemRepairCopy.held(custody.requestNonce()));
                }
                case BUSY -> {
                    claiming.remove(player.getUniqueId());
                    if (present(player)) reply.accept(ItemRepairCopy.HOME_BUSY);
                }
                default -> {
                    claiming.remove(player.getUniqueId());
                    if (present(player)) reply.accept(ItemRepairCopy.UNAVAILABLE);
                }
            }
        });
    }

    private static String itemName(ItemCustody custody) {
        try { return ItemRepairRecipes.itemName(ItemStack.deserializeBytes(custody.payload().bytes()).getType().name()); }
        catch (RuntimeException | LinkageError unreadable) { return "items"; }
    }

    /** The leased owner still holds the same home record. Where the player stands is the caller's authorized() check. */
    private boolean stillOwned(Flight f) {
        try {
            var current = plugin.propertyService().findByWorldRegion(f.property.worldUuid(), f.property.regionId()).orElse(null);
            if (current == null || !current.equals(f.property) || !current.enabled()
                    || !f.player.getUniqueId().equals(current.ownerUuid())) return false;
            ArmAdapter.OwnerRead fresh = plugin.armAdapter().ownerReadRegion(f.property.worldUuid(), f.property.regionId(),
                    f.player.getUniqueId()).orElseThrow();
            f.lease.validate(fresh);
            return true;
        } catch (RuntimeException changed) { return false; }
    }

    private static boolean countsUnchanged(Flight f) {
        for (int i = 0; i < f.takes.size(); i++) {
            var take = f.takes.get(i);
            var line = f.quote.lines().get(i);
            if (ItemInventoryCustody.totalUnits(f.player, take.material()) != line.beforeUnits()
                    || ItemInventoryCustody.storageUnits(f.player, take.material()) < line.quantity()) return false;
        }
        return true;
    }

    /** True when today's prices need more of any item than the shown list (or a different list). */
    static boolean needsMore(Preview current, Preview shown) {
        if (current.lines().size() != shown.lines().size()) return true;
        for (int i = 0; i < shown.lines().size(); i++) {
            var now = current.lines().get(i);
            var then = shown.lines().get(i);
            if (!now.material().equals(then.material()) || now.quantity() > then.quantity()) return true;
        }
        return false;
    }

    private static boolean inventoryClear(Player player) {
        var view = player.getOpenInventory();
        return view.getTopInventory().getType() == InventoryType.CRAFTING
                && (player.getItemOnCursor() == null || player.getItemOnCursor().isEmpty());
    }

    private boolean present(Player player) {
        try {
            return !closed && plugin.isEnabled() && player.isOnline() && !player.isDead()
                    && Bukkit.getPlayer(player.getUniqueId()) == player && player.hasPermission("prosperitynpc.use");
        } catch (RuntimeException failure) { return false; }
    }

    private boolean submit(Work work, Runnable fail) {
        try {
            worker.execute(() -> {
                try {
                    work.run();
                } catch (Exception failure) {
                    post(fail);
                }
            });
            return true;
        } catch (RuntimeException busy) {
            fail.run();
            return false;
        }
    }

    private void post(Runnable action) {
        if (!closed && !completions.offer(action)) {
            closed = true;
            worker.shutdown();
        }
    }

    /** An in-flight INTENT is left for startup recovery, never finished on shutdown. The home's lease is kept. */
    public CompletionStage<Void> closeAsync() {
        requireMain();
        closed = true;
        ready = false;
        ticker.cancel();
        if (flight != null) gate.release(flight.player.getUniqueId(), flight);
        flight = null;
        claiming.clear();
        completions.clear();
        worker.shutdown();
        return drained.minimalCompletionStage();
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Item repairs require main thread");
    }
}
