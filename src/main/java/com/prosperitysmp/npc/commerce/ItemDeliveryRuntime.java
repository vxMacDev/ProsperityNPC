package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared merchant/worker delivery. Funding/finite stock must already be durably bound to custody. */
public final class ItemDeliveryRuntime {
    public enum Result { DELIVERED, NO_SPACE, HELD, BUSY, UNAUTHORIZED, NOT_READY }
    private record Pending(Player login, UUID operation, ItemCustody custody, BooleanSupplier authorization,
                           Consumer<Result> reply) { }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private final ProsperityNpcPlugin plugin;
    private final ItemCustodyRepository repository;
    private final java.util.function.Predicate<ItemCustody.Source> gates;
    private final InventoryCustodyGate inventoryGate;
    private final ItemSaveProof saveProof;
    private final java.nio.file.Path playerDataWorld;
    private final UUID runId = UUID.randomUUID();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final ReturnSaveCheckWatch saveChecks = new ReturnSaveCheckWatch();
    private final ArrayBlockingQueue<Runnable> completions = new ArrayBlockingQueue<>(64);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor worker;
    private final BukkitTask ticker;
    private volatile boolean closed;
    private boolean ready;

    public ItemDeliveryRuntime(ProsperityNpcPlugin plugin, Database database, BooleanSupplier gates) {
        this(plugin, database, sameGate(gates), new InventoryCustodyGate());
    }

    /**
     * gates decides per custody source whether a delivery may run now. inventoryGate is shared with every other
     * runtime that changes a player's inventory, so no two mutations for one player overlap.
     */
    public ItemDeliveryRuntime(ProsperityNpcPlugin plugin, Database database,
                               java.util.function.Predicate<ItemCustody.Source> gates, InventoryCustodyGate inventoryGate) {
        requireMain(); this.plugin = Objects.requireNonNull(plugin);
        this.repository = new ItemCustodyRepository(database); this.gates = Objects.requireNonNull(gates);
        this.inventoryGate = Objects.requireNonNull(inventoryGate);
        saveProof = new ItemSaveProof(plugin.getServer().getClass().getClassLoader());
        playerDataWorld = saveProof.mainWorldFolder(plugin.getServer()).orElse(null);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread thread = new Thread(r, "npc-item-custody-sql"); thread.setDaemon(true); return thread;
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
            submit(() -> {
                while (repository.recoverOtherRuns(runId, 100) != 0) { if (closed) return; }
                post(() -> ready = true);
            }, () -> ready = false);
        } catch (RuntimeException | Error failure) {
            closed = true; if (scheduled != null) scheduled.cancel(); worker.shutdown(); throw failure;
        }
    }

    public boolean ready() { requireMain(); return ready && !closed && saveProof.available() && playerDataWorld != null; }

    private static java.util.function.Predicate<ItemCustody.Source> sameGate(BooleanSupplier gates) {
        Objects.requireNonNull(gates);
        return source -> gates.getAsBoolean();
    }

    /**
     * The per-source delivery gate. Commerce goods keep system && economy && town.commerce-enabled, exactly as
     * before. Item-repair returns need only the system switch and a connected database, so items an item repair
     * took stay deliverable after item-repairs or the economy switch is turned off.
     */
    public static boolean sourceAllowed(ItemCustody.Source source, boolean system, boolean economy,
                                        boolean commerceEnabled, boolean databaseConnected) {
        return CommerceWiring.deliveryGate(() -> system, () -> economy, () -> commerceEnabled, () -> databaseConnected).test(source);
    }

    /** The shared player-inventory gate, for other runtimes that change inventories. */
    public InventoryCustodyGate inventoryGate() { return inventoryGate; }

    /** Only persisted issuer custody, not a client material/amount. Caller provides current authority. */
    public void deliver(Player player, ItemCustody custody, BooleanSupplier authorization, Consumer<Result> reply) {
        requireMain(); Objects.requireNonNull(player); Objects.requireNonNull(custody);
        Objects.requireNonNull(authorization); Objects.requireNonNull(reply);
        if (!ready()) { reply.accept(Result.NOT_READY); return; }
        Pending operation = new Pending(player, UUID.randomUUID(), custody, authorization, reply);
        if (!authorized(operation)) { reply.accept(Result.UNAUTHORIZED); return; }
        if (pending.size() >= 16 || pending.putIfAbsent(player.getUniqueId(), operation) != null) { reply.accept(Result.BUSY); return; }
        if (!inventoryGate.tryHold(player.getUniqueId(), operation)) {
            pending.remove(player.getUniqueId(), operation); reply.accept(Result.BUSY); return;
        }
        boolean returned = custody.source() == ItemCustody.Source.REPAIR_RETURN;
        if (!returned) {
            // Commerce keeps the dev.58 check and reply.
            try {
                if (!ItemInventoryCustody.canReceive(player, ItemInventoryCustody.decode(custody))) {
                    finish(operation, Result.NO_SPACE); return;
                }
            } catch (RuntimeException | LinkageError invalid) { finish(operation, Result.HELD); return; }
        } else {
            org.bukkit.inventory.ItemStack item;
            try { item = ItemInventoryCustody.decode(custody); }
            catch (RuntimeException | LinkageError unreadable) { holdUnreadable(operation); return; }
            try {
                if (!ItemInventoryCustody.canReceive(player, item)) { finish(operation, Result.NO_SPACE); return; }
            } catch (RuntimeException | LinkageError unclear) { finish(operation, Result.NOT_READY); return; }
        }
        // A return proves its delivery against a save made just before it, as a hand-in proves its take. Commerce
        // deliveries keep the dev.58 proof.
        if (returned) {
            try { ItemInventoryCustody.saveBeforeReturn(player, custody.requestNonce()); }
            catch (RuntimeException | LinkageError unsaved) { saveCheckRefused(custody); finish(operation, Result.NOT_READY); return; }
        }
        // A return whose worker step fails before beginDelivery has given and held nothing: NOT_READY. Once beginDelivery
        // ran it may have committed, so unclearReturnStart reads the row back. Commerce keeps the dev.58 reply.
        var startTried = new java.util.concurrent.atomic.AtomicBoolean();
        submit(() -> {
            ItemSaveProof.Baseline baseline = null;
            if (returned) {
                // Nothing started yet: a save that cannot be read back gives nothing and holds nothing. Say so.
                var read = saveProof.returnBaseline(playerDataWorld, custody);
                if (read.isEmpty()) { post(() -> { saveCheckRefused(custody); finish(operation, Result.NOT_READY); }); return; }
                baseline = read.get();
            }
            startTried.set(true);
            var start = repository.beginDelivery(custody, runId, Instant.now());
            var before = baseline;
            post(() -> dispatch(operation, start, before));
        }, () -> {
            if (!returned) finish(operation, Result.HELD);
            else if (startTried.get()) unclearReturnStart(operation);
            else finish(operation, Result.NOT_READY);
        });
    }

    /**
     * A return whose beginDelivery failed after it may have committed. A row still RESERVED gave nothing and holds
     * nothing, so the player can try again: NOT_READY. Otherwise this run's started intent is held as DELIVERY_UNCERTAIN,
     * which the desk and staff list as held, and the reply is HELD. Anything that cannot be read is HELD.
     */
    private void unclearReturnStart(Pending operation) {
        ItemCustody custody = operation.custody();
        submit(() -> {
            boolean waiting = nothingStarted(repository.find(custody.playerId(), custody.requestNonce(), Instant.now()));
            if (!waiting) repository.markAmbiguous(custody, runId, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN);
            post(() -> finish(operation, waiting ? Result.NOT_READY : Result.HELD));
        }, () -> finish(operation, Result.HELD));
    }

    /** Only a row read back as RESERVED proves a failed start began nothing. */
    static boolean nothingStarted(java.util.Optional<ItemCustodyRepository.Receipt> row) {
        return row.isPresent() && row.get().state() == ItemCustodyRepository.State.RESERVED;
    }

    /** baseline: a return's before-delivery save, read back; null for commerce deliveries. */
    private void dispatch(Pending operation, ItemCustodyRepository.Start start, ItemSaveProof.Baseline baseline) {
        requireMain();
        if (pending.get(operation.login().getUniqueId()) != operation) return;
        if (!start.newlyStarted()) {
            finish(operation, start.receipt().state() == ItemCustodyRepository.State.DELIVERED ? Result.DELIVERED : Result.HELD); return;
        }
        if (!authorized(operation)) { ambiguous(operation); return; }
        ItemDeliveryEvidence evidence;
        try {
            // No callbacks/SQL/waits between this capacity check and the mutation. If space changed
            // during SQL, retain an uncertain intent rather than risk a partial grant or a retry.
            if (!ItemInventoryCustody.canReceive(operation.login(), ItemInventoryCustody.decode(operation.custody()))) {
                ambiguous(operation); return;
            }
            if (baseline != null) ItemInventoryCustody.markReturnDelivered(operation.login(), operation.custody().requestNonce());
            evidence = ItemInventoryCustody.deliver(operation.login(), operation.custody());
        } catch (RuntimeException | LinkageError uncertain) { ambiguous(operation); return; }
        var persisted = baseline == null ? saveProof.capture(playerDataWorld, operation.custody(), evidence)
                : saveProof.captureReturn(playerDataWorld, operation.custody(), evidence, baseline);
        if (persisted.isEmpty()) { ambiguous(operation); return; }
        submit(() -> {
            // A void saveData() return is not durable evidence: Paper can swallow a save failure.
            boolean durable = saveProof.verify(persisted.orElseThrow());
            var verified = new ItemDeliveryEvidence(evidence.playerId(), evidence.payloadSha256(),
                    evidence.beforeInventorySha256(), evidence.afterInventorySha256(), evidence.beforeUnits(),
                    evidence.afterUnits(), evidence.leftoverUnits(), evidence.returnedNormally() && durable);
            var receipt = repository.recordDelivery(operation.custody(), runId, verified);
            post(() -> finish(operation, receipt.state() == ItemCustodyRepository.State.DELIVERED ? Result.DELIVERED : Result.HELD));
        }, () -> finish(operation, Result.HELD));
    }

    private void ambiguous(Pending operation) {
        submit(() -> {
            repository.markAmbiguous(operation.custody(), runId, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN);
            post(() -> finish(operation, Result.HELD));
        }, () -> finish(operation, Result.HELD));
    }

    /**
     * A return whose stored items cannot be read back into an item. Nothing started and nothing was given. It is held in
     * the database first (AMBIGUOUS PAYLOAD_UNREADABLE), so the HELD reply is true and the offline tool can settle it.
     * When the hold cannot be written the reply is NOT_READY: nothing was held, and the next try holds it again.
     */
    private void holdUnreadable(Pending operation) {
        ItemCustody custody = operation.custody();
        submit(() -> {
            var receipt = repository.holdUnreadableReturn(custody, runId);
            post(() -> finish(operation, unreadableReply(receipt.state())));
        }, () -> {
            plugin.getLogger().warning("Item return " + custody.requestNonce() + " has items that cannot be read, and it could"
                    + " not be held. Nothing was given.");
            finish(operation, Result.NOT_READY);
        });
    }

    /** HELD only when the database holds the return. Any other state means this call held nothing. */
    static Result unreadableReply(ItemCustodyRepository.State state) {
        return state == ItemCustodyRepository.State.AMBIGUOUS ? Result.HELD : Result.NOT_READY;
    }

    /** Main only: the save before a return was refused. Logged once per return per run, a staff alert after repeats. */
    private void saveCheckRefused(ItemCustody custody) {
        try {
            switch (saveChecks.refused(custody.requestNonce())) {
                case LOG -> plugin.getLogger().warning(ReturnSaveCheckWatch.logLine(custody.requestNonce(), custody.playerId()));
                case ALERT -> plugin.alerts().raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.ITEM_REPAIR_HELD,
                        "return-save-" + custody.requestNonce(), ReturnSaveCheckWatch.staffAlert(custody.requestNonce()));
                case NONE -> { }
            }
        } catch (RuntimeException reporting) { /* The NOT_READY reply stands. */ }
    }

    private boolean authorized(Pending operation) {
        try {
            Player player = operation.login();
            return !closed && plugin.isEnabled() && gates.test(operation.custody().source()) && player.isOnline()
                    && Bukkit.getPlayer(player.getUniqueId()) == player && player.hasPermission("prosperitynpc.use")
                    && player.getUniqueId().equals(operation.custody().playerId()) && operation.authorization().getAsBoolean();
        } catch (RuntimeException failure) { return false; }
    }

    private void finish(Pending operation, Result result) {
        if (!pending.remove(operation.login().getUniqueId(), operation)) return;
        inventoryGate.release(operation.login().getUniqueId(), operation);
        boolean left = closed || !operation.login().isOnline() || Bukkit.getPlayer(operation.login().getUniqueId()) != operation.login();
        if (!tellsCaller(left, operation.custody().source())) return;
        try { operation.reply().accept(result); } catch (RuntimeException ignored) { }
    }

    /**
     * Whether finish tells the caller. Always while the player is here. After they left only for a return, so the item
     * repair runtime can clear its claim and raise a held alert; it checks presence before it says anything. Commerce
     * keeps the dev.58 rule: no reply once the player left.
     */
    static boolean tellsCaller(boolean left, ItemCustody.Source source) {
        return !left || source == ItemCustody.Source.REPAIR_RETURN;
    }

    private void submit(Work work, Runnable fail) {
        try { worker.execute(() -> {
            try { work.run(); } catch (Exception failure) { post(fail); }
        }); } catch (RuntimeException busy) { fail.run(); }
    }

    private void post(Runnable action) {
        if (!closed && !completions.offer(action)) { closed = true; worker.shutdown(); }
    }

    public CompletionStage<Void> closeAsync() {
        requireMain(); closed = true; ready = false; ticker.cancel();
        pending.forEach((player, operation) -> inventoryGate.release(player, operation));
        pending.clear(); completions.clear(); worker.shutdown();
        return drained.minimalCompletionStage();
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Item delivery requires main thread");
    }
}
