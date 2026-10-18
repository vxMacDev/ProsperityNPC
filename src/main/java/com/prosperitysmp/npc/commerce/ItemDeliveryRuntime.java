package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared merchant/worker delivery and item repair returns. Funding/finite stock must already be durably bound to custody.
 * A marked delivery (every return, and commerce while town.commerce.delivery-save-marker is on) is proven against a
 * marked save made just before it; unmarked commerce is checked by the inventory count alone.
 */
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
    private final Path playerDataWorld;
    private final boolean storageAvailable;
    private final UUID runId = UUID.randomUUID();
    private final Map<UUID, Pending> pending = new HashMap<>();
    /** Item repair returns only. Orders keep their own counts, so order refusals never clear these. */
    private final DeliverySaveCheckWatch saveChecks = new DeliverySaveCheckWatch();
    private final DeliverySaveCheckWatch orderSaveChecks = new DeliverySaveCheckWatch();
    /** Test hook: pauses after a marked order's save is read back, so a test can change things in that window. Absent means no pause. */
    private final long orderDelayMillis = Math.clamp(Long.getLong("prosperitynpc.dev.orderDeliveryDelayMs", 0L), 0L, 30_000L);
    private boolean saveMarkerWarned;
    private final ArrayBlockingQueue<Runnable> completions = new ArrayBlockingQueue<>(64);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor worker;
    private final BukkitTask ticker;
    /** Most held paid orders reported at one start. */
    private static final int HELD_REPORT_LIMIT = 64;
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
        requireMain();
        this.plugin = Objects.requireNonNull(plugin);
        this.repository = new ItemCustodyRepository(database);
        this.gates = Objects.requireNonNull(gates);
        this.inventoryGate = Objects.requireNonNull(inventoryGate);
        saveProof = new ItemSaveProof(plugin.getServer().getClass().getClassLoader());
        playerDataWorld = saveProof.mainWorldFolder(plugin.getServer()).orElse(null);
        storageAvailable = saveProof.storageAvailable(playerDataWorld);
        if (!storageAvailable)
            plugin.getLogger().warning("Item delivery held: player-data storage cannot support saved-item proofs.");
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread thread = new Thread(r, "npc-item-custody-sql");
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
                post(() -> ready = true);
                // The report comes after readiness, so a slow or failed read never holds deliveries back, and after
                // recovery, so a delivery cut off by a stop or crash is listed. A failed read only skips the report.
                java.util.List<ItemCustodyRepository.HeldOrder> held;
                int total;
                try {
                    held = repository.heldOrders(HELD_REPORT_LIMIT);
                    total = repository.heldOrderCount();
                } catch (java.sql.SQLException | RuntimeException unreadable) {
                    held = java.util.List.of();
                    total = 0;
                }
                var report = held;
                int all = total;
                post(() -> reportHeldOrders(report, all));
            }, () -> ready = false);
        } catch (RuntimeException | Error failure) {
            closed = true;
            if (scheduled != null) scheduled.cancel();
            worker.shutdown();
            throw failure;
        }
    }

    /**
     * Main only. A held paid-order delivery blocks that player's other deliveries until staff look at it, and one cut
     * off by a stop or crash was held by startup recovery with no report, so every start reports each one. Only for an
     * order whose commerce gate is open and with the save marker on, so with either off nothing is said. total is how
     * many are held in all; past the listed ones a single line gives the rest.
     */
    private void reportHeldOrders(java.util.List<ItemCustodyRepository.HeldOrder> held, int total) {
        int reported = 0;
        for (var order : held) {
            if (!gates.test(order.source()) || !saveMarkerOn()) continue;
            reported++;
            try {
                plugin.getLogger().warning(DeliverySaveCheckWatch.orderHeldAtStartLine(order.requestNonce(), order.playerId(),
                        order.outcome()));
                plugin.alerts().raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.COMMERCE_DELIVERY_HELD,
                        "order-held-" + order.requestNonce(), DeliverySaveCheckWatch.orderHeldAlert(order.requestNonce()));
            } catch (RuntimeException reporting) { /* A report that cannot be written changes nothing else. */ }
        }
        if (reported > 0 && total > held.size())
            plugin.getLogger().warning(DeliverySaveCheckWatch.orderHeldMoreLine(total - held.size(), total));
    }

    public boolean ready() {
        requireMain();
        return ready && !closed && storageAvailable;
    }

    private static java.util.function.Predicate<ItemCustody.Source> sameGate(BooleanSupplier gates) {
        Objects.requireNonNull(gates);
        return source -> gates.getAsBoolean();
    }

    /**
     * The per-source delivery gate. Commerce goods need system && economy && town.commerce-enabled. Item-repair
     * returns need only the system switch and a connected database, so items an item repair
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
        requireMain();
        Objects.requireNonNull(player);
        Objects.requireNonNull(custody);
        Objects.requireNonNull(authorization);
        Objects.requireNonNull(reply);
        if (!ready()) {
            reply.accept(Result.NOT_READY);
            return;
        }
        Pending operation = new Pending(player, UUID.randomUUID(), custody, authorization, reply);
        if (!authorized(operation)) { reply.accept(Result.UNAUTHORIZED); return; }
        if (pending.size() >= 16 || pending.putIfAbsent(player.getUniqueId(), operation) != null) {
            reply.accept(Result.BUSY);
            return;
        }
        if (!inventoryGate.tryHold(player.getUniqueId(), operation)) {
            pending.remove(player.getUniqueId(), operation);
            reply.accept(Result.BUSY);
            return;
        }
        boolean returned = custody.source() == ItemCustody.Source.REPAIR_RETURN;
        if (!returned) {
            // Commerce checks for space here; stored items that cannot be read answer HELD.
            try {
                if (!ItemInventoryCustody.canReceive(player, ItemInventoryCustody.decode(custody))) {
                    finish(operation, Result.NO_SPACE); return;
                }
            } catch (RuntimeException | LinkageError invalid) { finish(operation, Result.HELD); return; }
        } else {
            ItemStack item;
            try { item = ItemInventoryCustody.decode(custody); }
            catch (RuntimeException | LinkageError unreadable) {
                holdUnreadable(operation);
                return;
            }
            try {
                if (!ItemInventoryCustody.canReceive(player, item)) {
                    finish(operation, Result.NO_SPACE);
                    return;
                }
            } catch (RuntimeException | LinkageError unclear) {
                finish(operation, Result.NOT_READY);
                return;
            }
        }
        // The save marker is read once, here, after the gate above let this delivery through (so never while commerce is
        // off), and kept: a reload cannot mix the two proofs inside one delivery. A return is always marked.
        boolean marked = marked(custody.source(), this::saveMarkerOn);
        boolean orderProof = marked && !returned;
        if (orderProof) {
            preflight(operation);
            return;
        }
        // From here a marked delivery is a return. A return proves its delivery against a save made just before it, as a
        // hand-in proves its take. Unmarked commerce deliveries are checked by the inventory count alone.
        if (marked && !savedBefore(operation)) return;
        // A return whose worker step fails before beginDelivery has given and held nothing: NOT_READY. Once beginDelivery
        // ran it may have committed, so unclearStart reads the row back. Any failure of unmarked commerce replies HELD.
        var startTried = new AtomicBoolean();
        submit(() -> {
            ItemSaveProof.Baseline baseline = null;
            if (marked) {
                // Nothing started yet: a save that cannot be read back gives nothing and holds nothing. Say so.
                var read = saveProof.deliveryBaseline(playerDataWorld, custody);
                if (read.isEmpty()) {
                    post(() -> {
                        saveCheckRefused(custody);
                        finish(operation, Result.NOT_READY);
                    });
                    return;
                }
                baseline = read.get();
            }
            startTried.set(true);
            var start = repository.beginDelivery(custody, runId, Instant.now());
            var before = baseline;
            post(() -> dispatch(operation, start, before));
        }, () -> {
            if (!marked) finish(operation, Result.HELD);
            else if (startTried.get()) unclearStart(operation);
            else finish(operation, Result.NOT_READY);
        });
    }

    /** A return is always marked and never reads the commerce key; commerce follows town.commerce.delivery-save-marker. */
    static boolean marked(ItemCustody.Source source, BooleanSupplier saveMarkerOn) {
        return source == ItemCustody.Source.REPAIR_RETURN || saveMarkerOn.getAsBoolean();
    }

    /** Main only. The live key, failing toward on: a value that is not true or false is on, with one warning per run. */
    private boolean saveMarkerOn() {
        try {
            Object raw = plugin.getConfig().get(CommerceWiring.SAVE_MARKER);
            if (CommerceWiring.saveMarkerMalformed(raw) && !saveMarkerWarned) {
                saveMarkerWarned = true;
                plugin.getLogger().warning("town.commerce.delivery-save-marker is not true or false; the check stays on.");
            }
            return CommerceWiring.saveMarkerOn(raw);
        } catch (RuntimeException unreadable) { return true; }
    }

    /**
     * Main only, marked deliveries only: the save just before the delivery, carrying its before marker. False when the
     * save was refused: the refusal is counted and the reply is NOT_READY, with nothing started.
     */
    private boolean savedBefore(Pending operation) {
        try {
            ItemInventoryCustody.saveBeforeDelivery(operation.login(), operation.custody());
            return true;
        }
        catch (RuntimeException | LinkageError unsaved) {
            saveCheckRefused(operation.custody());
            finish(operation, Result.NOT_READY);
            return false;
        }
    }

    /**
     * A marked order delivery, step 1 on the worker: would beginDelivery refuse it now? Asked before the save, because the
     * marker key holds one value per player: a retry of a held order, or a new order while another is held, must not
     * overwrite the held delivery's marker. Nothing is written; a failure here is NOT_READY.
     */
    private void preflight(Pending operation) {
        ItemCustody custody = operation.custody();
        submit(() -> {
            var refusal = repository.wouldRefuse(custody, Instant.now());
            post(() -> afterPreflight(operation, refusal));
        }, () -> finish(operation, Result.NOT_READY));
    }

    /**
     * Step 2 on main. A refusal replies as dispatch does for a start that was not new, with no save. Then authority, life
     * and space again (nothing started in any of these), then the save with this order's before marker and, in the same
     * tick, the full inventory and ender chest state that save holds.
     */
    private void afterPreflight(Pending operation, Optional<ItemCustodyRepository.Receipt> refusal) {
        requireMain();
        if (pending.get(operation.login().getUniqueId()) != operation) return;
        Result refused = preflightReply(refusal, operation.custody().requestNonce());
        if (refused != null) {
            finish(operation, refused);
            return;
        }
        Player player = operation.login();
        if (!authorized(operation) || player.isDead()) {
            finish(operation, Result.UNAUTHORIZED);
            return;
        }
        try {
            if (!ItemInventoryCustody.canReceive(player, ItemInventoryCustody.decode(operation.custody()))) {
                finish(operation, Result.NO_SPACE);
                return;
            }
        } catch (RuntimeException | LinkageError unclear) {
            finish(operation, Result.NOT_READY);
            return;
        }
        if (!savedBefore(operation)) return;
        String preState;
        try { preState = ItemInventoryCustody.inventoryState(player); }
        catch (RuntimeException | LinkageError unclear) {
            finish(operation, Result.NOT_READY);
            return;
        }
        orderBaseline(operation, preState);
    }

    /** Null when nothing refused the start. Otherwise the reply dispatch gives a start that was not new. */
    static Result preflightReply(Optional<ItemCustodyRepository.Receipt> refusal, UUID requestNonce) {
        if (refusal.isEmpty()) return null;
        var row = refusal.get();
        return row.request().requestNonce().equals(requestNonce) && row.state() == ItemCustodyRepository.State.DELIVERED
                ? Result.DELIVERED : Result.HELD;
    }

    /**
     * Step 3 on the worker: reads back and forces the save just made. A save that cannot be read back, or that lacks this
     * order's before marker, gives nothing and holds nothing: NOT_READY, and staff are told. beginDelivery is not called
     * here, so every check after this read runs on main before anything starts.
     */
    private void orderBaseline(Pending operation, String preState) {
        ItemCustody custody = operation.custody();
        submit(() -> {
            var read = saveProof.deliveryBaseline(playerDataWorld, custody);
            if (read.isEmpty()) {
                post(() -> {
                    saveCheckRefused(custody);
                    finish(operation, Result.NOT_READY);
                });
                return;
            }
            if (orderDelayMillis > 0) Thread.sleep(orderDelayMillis);
            var baseline = read.get();
            post(() -> beforeStart(operation, preState, baseline));
        }, () -> finish(operation, Result.NOT_READY));
    }

    /**
     * Step 4 on main, the last check before the start. Authority and life, then the inventory must still be exactly as
     * saved (otherwise the baseline no longer describes it), then space. Any refusal starts nothing. After this only one
     * SQL hop remains before dispatch.
     */
    private void beforeStart(Pending operation, String preState, ItemSaveProof.Baseline baseline) {
        requireMain();
        if (pending.get(operation.login().getUniqueId()) != operation) return;
        Player player = operation.login();
        boolean alive = authorized(operation) && !player.isDead();
        boolean same;
        try { same = alive && ItemInventoryCustody.inventoryState(player).equals(preState); }
        catch (RuntimeException | LinkageError unclear) { same = false; }
        Boolean receivable;
        try { receivable = alive && same && ItemInventoryCustody.canReceive(player, ItemInventoryCustody.decode(operation.custody())); }
        catch (RuntimeException | LinkageError unclear) { receivable = null; }
        Result refused = startRefusal(alive, same, receivable);
        if (refused != null) {
            finish(operation, refused);
            return;
        }
        orderStart(operation, baseline);
    }

    /**
     * Null when the start may go ahead. In order: lost authority or a dead player is UNAUTHORIZED, a changed inventory is
     * NOT_READY, no space is NO_SPACE and a space check that threw (receivable null) is NOT_READY. Never HELD or
     * DELIVERED: nothing has started.
     */
    static Result startRefusal(boolean authorizedAndAlive, boolean sameState, Boolean receivable) {
        if (!authorizedAndAlive) return Result.UNAUTHORIZED;
        if (!sameState) return Result.NOT_READY;
        if (receivable == null) return Result.NOT_READY;
        return receivable ? null : Result.NO_SPACE;
    }

    /**
     * Step 5 on the worker: beginDelivery alone. Nothing starts once the runtime closed, so a stop leaves the row
     * RESERVED with only a before marker. A failure before the start is NOT_READY; after it, unclearStart reads the row.
     */
    private void orderStart(Pending operation, ItemSaveProof.Baseline baseline) {
        ItemCustody custody = operation.custody();
        var startTried = new AtomicBoolean();
        submit(() -> {
            if (closed) return;
            startTried.set(true);
            var start = repository.beginDelivery(custody, runId, Instant.now());
            post(() -> dispatch(operation, start, baseline));
        }, () -> {
            if (startTried.get()) unclearStart(operation, baseline);
            else finish(operation, Result.NOT_READY);
        });
    }

    /** A return's unclear start. It has no baseline here, so nothing is reported beyond the reply. */
    private void unclearStart(Pending operation) { unclearStart(operation, null); }

    /**
     * A marked delivery whose beginDelivery failed after it may have committed. A row still RESERVED gave nothing and holds
     * nothing, so the player can try again: NOT_READY. Otherwise this run's started intent is held as DELIVERY_UNCERTAIN,
     * which the desk and staff list as held, and the reply is HELD. Anything that cannot be read is HELD. A marked order
     * held here is reported like any other held order, since its baseline count exists only in memory.
     */
    private void unclearStart(Pending operation, ItemSaveProof.Baseline baseline) {
        ItemCustody custody = operation.custody();
        submit(() -> {
            boolean waiting = nothingStarted(repository.find(custody.playerId(), custody.requestNonce(), Instant.now()));
            if (!waiting) repository.markAmbiguous(custody, runId, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN);
            post(() -> {
                if (!waiting) heldReport(operation, baseline);
                finish(operation, waiting ? Result.NOT_READY : Result.HELD);
            });
        }, () -> {
            heldReport(operation, baseline);
            finish(operation, Result.HELD);
        });
    }

    /** Only a row read back as RESERVED proves a failed start began nothing. */
    static boolean nothingStarted(Optional<ItemCustodyRepository.Receipt> row) {
        return row.isPresent() && row.get().state() == ItemCustodyRepository.State.RESERVED;
    }

    /**
     * baseline: a marked delivery's before-delivery save, read back (every return, and an order with the save marker on);
     * null for unmarked commerce deliveries.
     */
    private void dispatch(Pending operation, ItemCustodyRepository.Start start, ItemSaveProof.Baseline baseline) {
        requireMain();
        if (pending.get(operation.login().getUniqueId()) != operation) return;
        if (!start.newlyStarted()) {
            finish(operation, start.receipt().state() == ItemCustodyRepository.State.DELIVERED ? Result.DELIVERED : Result.HELD);
            return;
        }
        if (!authorized(operation)) {
            ambiguous(operation, baseline);
            return;
        }
        // A marked order never gives items to a dead player. Returns and unmarked commerce skip this check.
        if (orderProof(baseline, operation) && operation.login().isDead()) {
            ambiguous(operation, baseline);
            return;
        }
        ItemDeliveryEvidence evidence;
        try {
            // No callbacks/SQL/waits between this capacity check and the mutation. If space changed
            // during SQL, retain an uncertain intent rather than risk a partial grant or a retry.
            if (!ItemInventoryCustody.canReceive(operation.login(), ItemInventoryCustody.decode(operation.custody()))) {
                ambiguous(operation, baseline);
                return;
            }
            if (baseline != null) ItemInventoryCustody.markDelivered(operation.login(), operation.custody());
            evidence = ItemInventoryCustody.deliver(operation.login(), operation.custody());
        } catch (RuntimeException | LinkageError uncertain) {
            ambiguous(operation, baseline);
            return;
        }
        var persisted = baseline == null ? saveProof.capture(playerDataWorld, operation.custody(), evidence)
                : saveProof.captureDelivery(playerDataWorld, operation.custody(), evidence, baseline);
        if (persisted.isEmpty()) {
            ambiguous(operation, baseline);
            return;
        }
        submit(() -> {
            // A void saveData() return is not durable evidence: Paper can swallow a save failure.
            boolean durable = saveProof.verify(persisted.orElseThrow());
            var verified = new ItemDeliveryEvidence(evidence.playerId(), evidence.payloadSha256(),
                    evidence.beforeInventorySha256(), evidence.afterInventorySha256(), evidence.beforeUnits(),
                    evidence.afterUnits(), evidence.leftoverUnits(), evidence.returnedNormally() && durable);
            var receipt = repository.recordDelivery(operation.custody(), runId, verified,
                    confirmedOutcome(operation.custody().source(), baseline != null));
            post(() -> {
                boolean delivered = receipt.state() == ItemCustodyRepository.State.DELIVERED;
                if (!delivered) heldReport(operation, baseline);
                finish(operation, delivered ? Result.DELIVERED : Result.HELD);
            });
        }, () -> {
            heldReport(operation, baseline);
            finish(operation, Result.HELD);
        });
    }

    /** A delivery proven against its own marked save: a commerce source with a baseline. Returns are never one. */
    private static boolean orderProof(ItemSaveProof.Baseline baseline, Pending operation) {
        return baseline != null && operation.custody().source() != ItemCustody.Source.REPAIR_RETURN;
    }

    /**
     * The outcome a confirmed delivery records. MARKER_CONFIRMED only for a commerce delivery proven against its marked
     * save; returns and unmarked commerce keep INVENTORY_CONFIRMED.
     */
    static String confirmedOutcome(ItemCustody.Source source, boolean baselinePresent) {
        return baselinePresent && source != ItemCustody.Source.REPAIR_RETURN
                ? ItemCustodyRepository.MARKER_CONFIRMED : ItemCustodyRepository.INVENTORY_CONFIRMED;
    }

    /** After the start: hold the intent as DELIVERY_UNCERTAIN and reply HELD; a marked order is also reported to staff. */
    private void ambiguous(Pending operation, ItemSaveProof.Baseline baseline) {
        submit(() -> {
            repository.markAmbiguous(operation.custody(), runId, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN);
            post(() -> {
                heldReport(operation, baseline);
                finish(operation, Result.HELD);
            });
        }, () -> {
            heldReport(operation, baseline);
            finish(operation, Result.HELD);
        });
    }

    /**
     * Main only. A marked order held after it started: one log line with the whole-file count from the save just before
     * it (otherwise kept only in memory, and staff need it to settle the order), and a staff alert. Does nothing for a
     * return or an unmarked order.
     */
    private void heldReport(Pending operation, ItemSaveProof.Baseline baseline) {
        if (!orderProof(baseline, operation)) return;
        ItemCustody custody = operation.custody();
        try {
            plugin.getLogger().warning(DeliverySaveCheckWatch.orderHeldLine(custody.requestNonce(), custody.playerId(),
                    baseline.units().getFirst()));
            plugin.alerts().raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.COMMERCE_DELIVERY_HELD,
                    "order-held-" + custody.requestNonce(), DeliverySaveCheckWatch.orderHeldAlert(custody.requestNonce()));
        } catch (RuntimeException reporting) { /* The HELD reply stands. */ }
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

    /**
     * Main only: the save before a marked delivery was refused. Logged once per delivery per run, a staff alert after
     * repeats. Returns and orders each have their own log lines, alert kind and counts.
     */
    private void saveCheckRefused(ItemCustody custody) {
        try {
            if (custody.source() == ItemCustody.Source.REPAIR_RETURN) {
                switch (saveChecks.refused(custody.requestNonce())) {
                    case LOG -> plugin.getLogger().warning(DeliverySaveCheckWatch.logLine(custody.requestNonce(), custody.playerId()));
                    case ALERT -> plugin.alerts().raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.ITEM_REPAIR_HELD,
                            "return-save-" + custody.requestNonce(), DeliverySaveCheckWatch.staffAlert(custody.requestNonce()));
                    case NONE -> { }
                }
                return;
            }
            switch (orderSaveChecks.refused(custody.requestNonce())) {
                case LOG -> plugin.getLogger().warning(DeliverySaveCheckWatch.orderLogLine(custody.requestNonce(), custody.playerId()));
                case ALERT -> plugin.alerts().raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.COMMERCE_SAVE_CHECK,
                        "order-save-" + custody.requestNonce(), DeliverySaveCheckWatch.orderStaffAlert(custody.requestNonce()));
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
     * never replies once the player left.
     */
    static boolean tellsCaller(boolean left, ItemCustody.Source source) {
        return !left || source == ItemCustody.Source.REPAIR_RETURN;
    }

    private void submit(Work work, Runnable fail) {
        try {
            worker.execute(() -> {
                try {
                    work.run();
                } catch (Exception failure) {
                    post(fail);
                }
            });
        } catch (RuntimeException busy) { fail.run(); }
    }

    private void post(Runnable action) {
        if (!closed && !completions.offer(action)) {
            closed = true;
            worker.shutdown();
        }
    }

    public CompletionStage<Void> closeAsync() {
        requireMain();
        closed = true;
        ready = false;
        ticker.cancel();
        pending.forEach((player, operation) -> inventoryGate.release(player, operation));
        pending.clear();
        completions.clear();
        worker.shutdown();
        return drained.minimalCompletionStage();
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Item delivery requires main thread");
    }
}
