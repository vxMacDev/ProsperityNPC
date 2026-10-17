package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.arm.OwnershipFence;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.AutoCareRepository;
import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.Property;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;
import net.milkbowl.vault.economy.Economy;

/**
 * Explicit beta utility/repair/rent integration. New operations require acknowledged
 * absolute accounting projection. No BridgeApi, refund, automatic transfer retry or recovery
 * release. The host serializes its own transfers, not other plugins' wallet writes.
 * Close revokes provider calls; registered SQL work drains through plugin shutdown.
 */
public final class UtilityPaymentRuntime implements AutoCloseable {
    public static final String SMOKE_OPT_IN = "economy.beta-utility-smoke-enabled";
    public static final String PAYMENTS_OPT_IN = "economy.payments-enabled";
    /** Off: one unavailable owner refuses the whole rent batch. */
    public static final String RENT_ISOLATION = "economy.rent-isolation-enabled";
    /** Off: no appreciation is reserved or paid; rent batches pay rent only. */
    public static final String APPRECIATION = "economy.appreciation-enabled";
    /** Off: no payout reservation is ever retired. */
    public static final String PAYOUT_RETIREMENT = "economy.payout-retirement-enabled";
    /**
     * Off: appreciation is held only by this batch's own rent (a floor deferral, or a home it skipped for a due rent
     * change). On: it is also held whenever the ledger has any of today's rent not yet final on any property,
     * including rent this batch never listed (isolation-held, paused or owner-away after reserving), or while any
     * home's rent is held for a due rent change not yet applied, so it cannot spend reserve headroom that rent needs.
     */
    public static final String APPRECIATION_LEDGER_RENT_HOLD = "economy.appreciation-ledger-rent-hold-enabled";
    /**
     * Off: owner-away (housing.vacation-days) is checked only at admission. On: it is reread on
     * main before every DEBIT leg (and immediately before the provider withdraw), so a home that became held during
     * an async hop (for example /npcadmin reload turning vacation-days on) is not charged or paid. Never before CREDIT.
     */
    public static final String AWAY_DEBIT_RECHECK = "economy.away-debit-recheck-enabled";
    /** One batch retires at most RETIREMENT_ROUNDS x RETIREMENT_LIMIT rows, above the 10000-row ledger scan bound. */
    static final int RETIREMENT_ROUNDS = 40, RETIREMENT_LIMIT = 500;
    private final ProsperityNpcPlugin plugin;
    private final UUID reserve, runId = UUID.randomUUID();
    private final UtilityRepository utilities;
    private final PurchaseRepository purchases;
    private final AutoCareRepository autoCare;
    private final RentReservationRepository rents;
    private final TransferJournal journal;
    /** Main thread only: payments refused before any money moved. */
    private final RefusalMonitor refusals;
    private final EconomyClock days;
    private final ThreadPoolExecutor worker;
    private final ArrayBlockingQueue<Runnable> deliveries = new ArrayBlockingQueue<>(4);
    private final Executor mainExecutor = this::post;
    private final BukkitTask ticker;
    private volatile boolean closed;
    private boolean busy, recovery, startupReady;
    private enum Result { PAID, REPAIRED, EVICTED, INSURED, PURCHASED, PURCHASE_ALREADY_APPLIED, DECLINED, ALREADY_PAID, OVER_CAP,
            REPAIR_UNAVAILABLE, REPAIR_ITEMS_PENDING, EVICTION_UNAVAILABLE, TARGET_UNAVAILABLE, INSUFFICIENT_FUNDS }
    /** The reply when a cash repair waits on an item hand-in (item-repairs.block-cash-while-items-held). */
    public static final String ITEMS_PENDING_REPLY = "This repair has an item hand-in waiting for staff. No money was taken.";
    private record PurchaseTarget(PurchaseRequest.Kind kind, UpgradeType upgrade, Integer level, UUID tenantId) { }
    private record Prepared(long eventId, long cents, UtilityBill bill, PurchaseRequest repair, Result terminal) {
        static Prepared stopped(Result result) { return new Prepared(0, 0, null, null, result); }
    }
    private OwnershipFence.Lease retainedLease;
    private FinancialFence.Lease financialLease;
    private final LinkedHashMap<UUID, OwnershipFence.Lease> rentLeases = new LinkedHashMap<>();
    // Main thread only. Held = skipped by the latest isolated batch; logged = once per property per economy day.
    private final LinkedHashMap<UUID, LocalDate> rentHeldLogged = new LinkedHashMap<>();
    /** Worker-written: the economy day a failed server-cap read was last logged, so it logs once per day. */
    private final AtomicReference<LocalDate> serverCapReadWarned = new AtomicReference<>();
    private List<Property> rentHeld = List.of();
    private LocalDate rentHeldDay;

    /** Owner admission for one rent batch. Eligible properties and their owner reads share an index. */
    public record RentPlan<T>(List<Property> eligible, List<T> owners, List<Property> held) { }

    /**
     * Isolation off: any missing, reserve-owned or unreadable owner refuses the whole batch.
     * Isolation on: that property is held (no reservation, no payment) and every other property continues.
     */
    public static <T> RentPlan<T> planRent(List<Property> properties, UUID reserve, boolean isolate,
                                           Function<Property, Optional<T>> read) {
        Objects.requireNonNull(reserve);
        Objects.requireNonNull(read);
        var eligible = new ArrayList<Property>();
        var owners = new ArrayList<T>();
        var held = new ArrayList<Property>();
        for (Property property : properties) {
            T owner = null;
            if (property.ownerUuid() != null && !reserve.equals(property.ownerUuid())) {
                try { owner = read.apply(property).orElse(null); }
                catch (RuntimeException unreadable) { if (!isolate) throw unreadable; }
            }
            if (owner == null) {
                if (!isolate) throw new IllegalStateException("Rent owner unavailable");
                held.add(property);
                continue;
            }
            eligible.add(property);
            owners.add(owner);
        }
        return new RentPlan<>(List.copyOf(eligible), List.copyOf(owners), List.copyOf(held));
    }

    public UtilityPaymentRuntime(ProsperityNpcPlugin plugin) {
        main();
        this.plugin = Objects.requireNonNull(plugin);
        reserve = ReserveAccount.fromConfig(plugin.getConfig());
        Clock clock = Clock.systemUTC();
        ZoneId zone = ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago"));
        int reset = plugin.getConfig().getInt("clock.daily-reset-hour", 4);
        days = new EconomyClock(clock, zone, reset);
        utilities = new UtilityRepository(plugin.database(), reserve, clock, zone, reset);
        // item-repairs.block-cash-while-items-held, read per request; it acts only on item-repair rows.
        purchases = new PurchaseRepository(plugin.database(), reserve, clock, zone, reset,
                () -> plugin.itemRepairSettings().blockCashWhileItemsHeld());
        autoCare = new AutoCareRepository(plugin.database(), reserve, clock, zone, reset);
        // linked-accounts: the saved day pin and links group owners for the benefit-unit cap. The flag is read per batch.
        rents = new RentReservationRepository(plugin.database(), reserve, clock, zone, reset,
                Boolean.TRUE.equals(plugin.getConfig().get("housing.premium-enabled")),
                plugin.accountLinks(), plugin::linkedAccountsEnabled);
        journal = new TransferJournal(plugin.database(), reserve);
        refusals = new RefusalMonitor((kind, subject, text) -> plugin.alerts().raise(kind, subject, text),
                plugin.getLogger()::warning, Bukkit::isPrimaryThread);
        String build = plugin.getPluginMeta().getVersion();
        Path ownJar;
        try { ownJar = Path.of(UtilityPaymentRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception unavailable) { ownJar = null; }
        final Path runJar = ownJar;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), r -> {
            Thread t = new Thread(r, "npc-utility-payment");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        try {
            plugin.trackDatabaseWorker(worker);
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                Runnable next = deliveries.poll();
                if (next != null) next.run();
            }, 1, 1);
        } catch (RuntimeException | Error failure) {
            worker.shutdown();
            throw failure;
        }
        var database = plugin.database();
        // One bounded pass, not an automatic recovery loop. Existing ambiguity also holds a new host.
        CompletableFuture.supplyAsync(() -> {
            // Ties every row this run writes (run_id) to the build and jar that wrote it.
            plugin.getLogger().info(runLine("NPC run", runId, build, runJar));
            try {
                int changed = journal.recoverOtherRuns(runId, 100);
                if (changed != 0) return false;
                try (var c = database.getConnection(); var ps = c.prepareStatement("""
                        SELECT event_id FROM npc_transfer_attempts
                        WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS')
                        UNION ALL SELECT 0 FROM npc_commerce_orders
                        WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS') LIMIT 1
                        """)) {
                    ps.setQueryTimeout(5);
                    try (var rs = ps.executeQuery()) {
                        boolean unresolved = rs.next();
                        if (c.getWarnings() != null || ps.getWarnings() != null || rs.getWarnings() != null)
                            throw new IllegalStateException("Startup transfer read warning");
                        return !unresolved;
                    }
                }
            } catch (Exception failure) { throw new IllegalStateException("Startup transfer recovery unconfirmed"); }
        }, worker).whenComplete((clear, failure) -> {
            try { post(() -> {
                startupReady = failure == null && Boolean.TRUE.equals(clear);
                recovery = !startupReady;
                if (recovery) {
                    plugin.getLogger().warning("Utility startup recovery requires review; payments held.");
                    plugin.alerts().raise(StaffAlerts.Kind.PAYMENT_HELD_FOR_REVIEW, "startup",
                            "Payments are held: an earlier transfer needs staff review.");
                }
            });
            } catch (RuntimeException stopped) { /* Closed hosts never become ready. */ }
        });
    }

    /** Main-thread admission. False means nothing was dispatched by this request. */
    public boolean request(UUID propertyId, UUID actor, long maximumCents,
                           BooleanSupplier authorized, Consumer<String> reply) {
        return requestCore(propertyId, actor, 0, null, maximumCents, authorized, reply);
    }

    public boolean requestRepair(UUID propertyId, UUID actor, long incidentId, long maximumCents,
                                 BooleanSupplier authorized, Consumer<String> reply) {
        main();
        if (incidentId <= 0) return false;
        return requestCore(propertyId, actor, incidentId, null, maximumCents, authorized, reply);
    }

    public boolean requestPurchase(UUID propertyId, UUID actor, PurchaseRequest.Kind kind,
                                   UpgradeType upgrade, Integer level,
                                   long maximumCents, BooleanSupplier authorized, Consumer<String> reply) {
        main();
        if (kind != PurchaseRequest.Kind.UPGRADE && kind != PurchaseRequest.Kind.MAINTAIN) return false;
        if (kind == PurchaseRequest.Kind.UPGRADE
                ? upgrade == null || level == null || level < 1 || level > UpgradeType.MAX_LEVEL
                : upgrade != null || level != null) return false;
        return requestCore(propertyId, actor, 0, new PurchaseTarget(kind, upgrade, level, null), maximumCents, authorized, reply);
    }

    public boolean requestEviction(UUID propertyId, UUID actor, UUID tenantId, long maximumCents,
                                   BooleanSupplier authorized, Consumer<String> reply) {
        main();
        Objects.requireNonNull(tenantId);
        return requestCore(propertyId, actor, 0, new PurchaseTarget(PurchaseRequest.Kind.EVICT, null, null, tenantId),
                maximumCents, authorized, reply);
    }

    /** Explicit consent to the displayed premium; SQL binds the current property capacity. */
    public boolean requestInsurance(UUID propertyId, UUID actor, long maximumCents,
                                    BooleanSupplier authorized, Consumer<String> reply) {
        main();
        return requestCore(propertyId, actor, 0, new PurchaseTarget(PurchaseRequest.Kind.INSURANCE, null, null, null),
                maximumCents, authorized, reply);
    }

    private boolean requestCore(UUID propertyId, UUID actor, long incidentId, PurchaseTarget target, long maximumCents,
                                BooleanSupplier authorized, Consumer<String> reply) {
        return requestCore(propertyId, actor, incidentId, target, maximumCents, authorized, reply, null, ignored -> { });
    }

    /** Only a newly reserved recurring-consent claim may enter the normal payment coordinator. */
    public boolean requestAutoCare(AutoCareRepository.Claim claim,
                                   BooleanSupplier authorized, Consumer<Boolean> finished) {
        main();
        Objects.requireNonNull(claim);
        Objects.requireNonNull(finished);
        if (claim.state() != AutoCareRepository.State.RESERVED || claim.eventId() != null
                || !claim.day().equals(days.today())) return false;
        var target = claim.action() == AutoCareRepository.Action.MAINTAIN
                ? new PurchaseTarget(PurchaseRequest.Kind.MAINTAIN, null, null, null) : null;
        return requestCore(claim.propertyId(), claim.ownerUuid(), claim.incidentId() == null ? 0 : claim.incidentId(),
                target, claim.quotedCents(), authorized,
                message -> plugin.getLogger().info("Auto-care " + claim.claimId() + ": " + message), claim, finished);
    }

    private boolean requestCore(UUID propertyId, UUID actor, long incidentId, PurchaseTarget target, long maximumCents,
                                BooleanSupplier authorized, Consumer<String> reply,
                                com.prosperitysmp.npc.property.AutoCareRepository.Claim recurring, Consumer<Boolean> finished) {
        main();
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(authorized);
        Objects.requireNonNull(reply);
        if (maximumCents <= 0 || busy || recovery || !ready(authorized) || !accountingReady() || reserve.equals(actor)) return false;
        Property property = plugin.propertyService().getEnrolled().stream()
                .filter(p -> p.propertyId().equals(propertyId)).findFirst().orElse(null);
        if (property == null || !property.enabled() || !actor.equals(property.ownerUuid())
                || (recurring != null && recurring.ownershipEpoch() != property.ownershipEpoch())) return false;
        // Busy, like a held lease: the ownership check's SQL write for this home could land after admission.
        if (plugin.propertyService().reconcileWriting(propertyId)) return false;
        // Saved decision only: an unsaved one (for example just after a restart) holds the bill until it is saved.
        if (awayBlocks(plugin.ownerAway().holds(property), recurring != null, incidentId, target != null)) return false;
        // The same admission predicate (owner-chosen repairs and purchases stay exempt), reread on main before the debit.
        BooleanSupplier awayHeld = () -> awayHeldNow(plugin.getConfig().get(AWAY_DEBIT_RECHECK),
                () -> awayBlocks(plugin.ownerAway().holds(property), recurring != null, incidentId, target != null));
        RegisteredServiceProvider<Economy> registration = registration();
        if (registration == null || !registration.getProvider().isEnabled()) return false;
        final long availableCents;
        try {
            availableCents = TransferProof.availableCents(registration.getProvider().getBalance(Bukkit.getOfflinePlayer(actor))).orElseThrow();
        } catch (RuntimeException unreadable) { return false; }
        final Path providerJar;
        try {
            providerJar = Path.of(registration.getProvider().getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception failure) { return false; }
        final OwnershipFence.Lease lease;
        try {
            financialLease = plugin.financialFence().acquire("property-payment");
            var owner = plugin.armAdapter().ownerReadRegion(property.worldUuid(), property.regionId(), actor).orElseThrow();
            lease = plugin.ownershipFence().acquire(owner);
            retainedLease = lease;
        } catch (RuntimeException failure) {
            releaseFinancial();
            return false;
        }
        busy = true;
        UUID requestId = recurring == null ? UUID.randomUUID() : recurring.claimId();
        LocalDate day = days.today();
        // A paid repair or purchase may change the home's SQL row (condition): re-read on the worker, installed on
        // main before the lease is released. Empty: no re-read needed. null: the re-read failed.
        final AtomicReference<List<Property>> reread =
                new AtomicReference<>(List.of());
        // Every continuation destined for Bukkit is pumped on main; worker closures perform only file/SQL work.
        final CompletableFuture<String> fingerprint;
        try { fingerprint = CompletableFuture.supplyAsync(() -> hash(providerJar), worker); }
        catch (RuntimeException rejected) {
            lease.releaseReconciled();
            retainedLease = null;
            busy = false;
            releaseFinancial();
            return false; // No file task or SQL was accepted.
        }
        fingerprint
                // Nothing is written until prepare below: a failure up to there started no payment.
                .handle((sha, error) -> {
                    if (error != null) throw NotStarted.of(error);
                    return sha;
                })
                .thenComposeAsync(sha -> {
                    try { validate(property, actor, authorized, registration, lease, day); }
                    catch (RuntimeException | LinkageError refused) { throw new NotStarted(refused); }
                    return CompletableFuture.supplyAsync(() -> {
                        var prepared = prepare(requestId, property, actor, incidentId, target, day, maximumCents, availableCents);
                        if (recurring != null) {
                            try {
                                if (prepared.terminal() != null) autoCare.hold(requestId);
                                else {
                                    if (prepared.cents() != recurring.quotedCents())
                                        throw new IllegalStateException("Auto-care frozen quote differs");
                                    var binding = autoCare.bind(requestId, prepared.eventId());
                                    if (!binding.newlyBound()) throw new IllegalStateException("Auto-care replay cannot dispatch money");
                                }
                            } catch (SQLException failure) { throw new IllegalStateException("Auto-care binding unconfirmed", failure); }
                        }
                        return prepared;
                    },
                            worker).thenComposeAsync(prepared -> {
                        // Proven no-new-payment paths do not need renewed presentation permission to release.
                        if (prepared.terminal() != null) return CompletableFuture.completedFuture(prepared.terminal());
                        // Authority and owner-away are rechecked in authorize, on main right before the debit. A refusal
                        // there is journaled as a decline and the prepared row is declined with it, so it never holds.
                        if (prepared.cents() <= 0 || prepared.cents() > maximumCents)
                            throw new IllegalStateException("Payment preparation binding failed");
                        var handle = new TransferJournal.Handle(UUID.randomUUID(), prepared.eventId(), runId, sha);
                        var transfer = new JournaledTransfer(journal, (receipt, leg) -> {
                            int[] providerChecks = {0};
                            var observer = new VaultLegObservation(registration.getProvider(), () -> {
                                requireLegAuthority(leg, () -> registration(), registration,
                                        () -> validate(property, actor, authorized, registration, lease, day));
                                // Second provider check: after the pre-balance read, immediately before the withdraw.
                                if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) requireAwayDebitGate(leg, awayHeld);
                                return registration.getProvider();
                            }, Bukkit::isPrimaryThread);
                            UUID account = leg == TransferProof.Leg.DEBIT ? actor : reserve;
                            UUID counterparty = leg == TransferProof.Leg.DEBIT ? reserve : actor;
                            return observer.execute(account, Bukkit.getOfflinePlayer(account),
                                    Bukkit.getOfflinePlayer(counterparty), receipt.cents(), leg);
                        }, worker, mainExecutor, (leg, refused) -> refusals.refused("utility", "event " + prepared.eventId(), leg, refused));
                        return transfer.submit(handle, new JournaledTransfer.Lease() {
                            public void authorize(TransferJournal.Receipt receipt, TransferProof.Leg leg) {
                                // A receipt bound to another payment is a defect: it holds for staff, never declines.
                                if (!receipt.handle().equals(handle) || !receipt.payer().equals(actor)
                                        || !receipt.payee().equals(reserve) || receipt.cents() != prepared.cents()
                                        || receipt.cents() > maximumCents)
                                    throw new JournaledTransfer.BindingFailed("Utility transfer binding failed");
                                requireLegAuthority(leg, () -> registration(), registration,
                                        () -> validate(property, actor, authorized, registration, lease, day));
                                requireAwayDebitGate(leg, awayHeld);
                            }
                            public void releaseVerified(TransferJournal.Receipt receipt) {
                                // Keep ownership excluded until the domain receipt/effect also acknowledges its commit.
                            }
                        }).thenApplyAsync(receipt -> {
                            if (receipt.state() != TransferJournal.State.PAID && receipt.state() != TransferJournal.State.DECLINED)
                                throw new IllegalStateException("Utility transfer requires recovery");
                            var result = complete(prepared, requestId, receipt);
                            if (prepared.repair() != null && receipt.state() == TransferJournal.State.PAID) {
                                // Money and effect are committed; a failed re-read (or a missing row) only leaves the row to
                                // the ownership check.
                                try { reread.set(plugin.propertyService().readRow(property.propertyId()).map(List::of).orElse(null)); }
                                catch (Exception unreadable) { reread.set(null); }
                            }
                            if (recurring != null) {
                                try {
                                    var settled = autoCare.reconcile(requestId);
                                    var expected = receipt.state() == TransferJournal.State.PAID
                                            ? AutoCareRepository.State.SPENT
                                            : AutoCareRepository.State.DECLINED;
                                    if (settled.state() != expected) throw new IllegalStateException("Auto-care settlement unconfirmed");
                                } catch (SQLException failure) {
                                    throw new IllegalStateException("Auto-care reconciliation unconfirmed", failure);
                                }
                            }
                            return result;
                        }, worker).toCompletableFuture();
                    }, mainExecutor);
                }, mainExecutor).whenComplete((state, failure) -> {
                    try { post(() -> {
                        plugin.accountingProjection().invalidate();
                        recovery = true;
                        NotStarted stopped = NotStarted.in(failure);
                        if (stopped != null && releaseUnstarted(lease, requestId)) {
                            refusals.notStarted("utility", "request " + requestId, stopped.getCause());
                            finished.accept(false);
                            respond(authorized, reply, NOT_STARTED_REPLY);
                            return;
                        }
                        if (failure != null) {
                            plugin.getLogger().warning("Payment recovery required; request " + requestId + "; lease retained.");
                            plugin.alerts().raise(StaffAlerts.Kind.PAYMENT_HELD_FOR_REVIEW, "request-" + requestId,
                                    "Payment held for review. Reference " + requestId + ".");
                            respond(authorized, reply, "Payment outcome requires staff recovery. Do not retry. Reference: " + requestId);
                            finished.accept(false);
                            return;
                        }
                        List<Property> rows = reread.get();
                        if (rows == null || !rows.isEmpty()) {
                            try { plugin.propertyService().installLeased(List.of(property), rows, "Property payment " + requestId); }
                            catch (RuntimeException cacheFailure) {
                                plugin.getLogger().warning("Property payment " + requestId + " could not refresh the cached home; "
                                        + "the next ownership check does.");
                            }
                        }
                        try { lease.releaseReconciled(); }
                        catch (RuntimeException failureToRelease) {
                            plugin.getLogger().warning("Utility lease release requires review; request " + requestId);
                            plugin.alerts().raise(StaffAlerts.Kind.PAYMENT_HELD_FOR_REVIEW, "request-" + requestId,
                                    "Payments held: a lease release needs staff review. Reference " + requestId + ".");
                            finished.accept(false);
                            return;
                        }
                        retainedLease = null;
                        busy = false;
                        recovery = false;
                        releaseFinancial();
                        if (state == Result.PAID || state == Result.REPAIRED || state == Result.EVICTED || state == Result.INSURED
                                || state == Result.PURCHASED) refusals.paid("utility");
                        finished.accept(true);
                        respond(authorized, reply, switch (state) {
                            case ALREADY_PAID -> "Utility bill already paid; no additional charge.";
                            case PAID -> "Utility payment verified. Accounting refresh queued.";
                            case REPAIRED -> "Paid repair applied and service history recorded. Accounting refresh queued.";
                            case EVICTED -> "Payment verified and tenant eviction completed. Accounting refresh queued.";
                            case INSURED -> "Premium verified. Seven-day insurance starts next economy day; one prevention, no payout or "
                                    + "refund. Accounting refresh queued.";
                            case PURCHASED -> "Payment verified and property benefit applied. Accounting refresh queued.";
                            case PURCHASE_ALREADY_APPLIED -> "This property benefit is already applied; no additional charge.";
                            case REPAIR_UNAVAILABLE -> "Repair target already resolved or has an applied receipt; no additional charge.";
                            case REPAIR_ITEMS_PENDING -> ITEMS_PENDING_REPLY;
                            case EVICTION_UNAVAILABLE -> "Tenant is no longer at this property; no additional charge and no new payment "
                                    + "started.";
                            case TARGET_UNAVAILABLE -> "No payment started: this benefit is unnecessary, changed, or already covered. "
                                    + "Refresh your owner desk.";
                            case INSUFFICIENT_FUNDS -> "Insufficient available funds. No payment was started; NPC purchases cannot borrow.";
                            case DECLINED -> "Payment declined with no movement. No automatic retry.";
                            case OVER_CAP -> "Cost exceeds your maximum; no money moved and no new payment was queued.";
                        });
                    });
                    } catch (RuntimeException stopped) { /* Shutdown retains the lease; no further money call. */ }
                });
        return true;
    }

    /**
     * The startup line that ties every row a run writes (run_id) to the build and jar that wrote it, for example
     * "NPC run <uuid> build 0.2.0-dev.63 jar <sha256>". An unreadable jar prints "unavailable". Worker thread.
     */
    static String runLine(String label, UUID runId, String build, Path jar) {
        String jarSha;
        try { jarSha = jar == null ? "unavailable" : hash(jar); }
        catch (RuntimeException unreadable) { jarSha = "unavailable"; }
        return label + " " + runId + " build " + build + " jar " + jarSha;
    }

    /** The reply when a request stopped before any payment row or transfer existed. */
    static final String NOT_STARTED_REPLY = "No payment was started. Nothing was charged.";
    /** The reply when a rent batch stopped before any reservation or transfer existed. */
    static final String RENT_NOT_STARTED_REPLY = "Rent batch not started; nothing was paid.";

    /**
     * Main thread. A request that stopped before any SQL row or transfer existed releases what it held. False
     * when the lease cannot be released, which leaves the request to the normal recovery hold.
     */
    private boolean releaseUnstarted(OwnershipFence.Lease lease, UUID requestId) {
        try { lease.releaseReconciled(); }
        catch (RuntimeException failureToRelease) {
            plugin.getLogger().warning("Utility lease release requires review; request " + requestId);
            return false;
        }
        retainedLease = null;
        busy = false;
        recovery = false;
        releaseFinancial();
        return true;
    }

    /**
     * Main thread. A rent batch that stopped before any reservation or transfer existed releases its leases. False
     * when one cannot be released, which leaves the batch to the normal recovery hold.
     */
    private boolean releaseUnstartedRent(UUID batchId) {
        return releasedQuietly(this::releaseUnusedRent, plugin.getLogger()::warning,
                "Rent lease release requires review; batch " + batchId);
    }

    /** Runs a release. False, with the warning logged, when it throws: the caller then keeps its recovery hold. */
    static boolean releasedQuietly(Runnable release, Consumer<String> warn, String warning) {
        try {
            release.run();
            return true;
        } catch (RuntimeException | LinkageError failed) {
            try { warn.accept(warning); }
            catch (RuntimeException | LinkageError unlogged) { /* The recovery hold still follows. */ }
            return false;
        }
    }

    /** Explicit current-day batch; utility, repairs and rent share the same admission gate. */
    public boolean requestRentBatch(BooleanSupplier authorized, Consumer<String> reply) {
        main();
        Objects.requireNonNull(authorized);
        Objects.requireNonNull(reply);
        if (busy || recovery || !ready(authorized) || !accountingReady()) return false;
        var cached = plugin.propertyService().getEnrolled();
        if (cached.size() > 512) return false;
        // Owner-away homes get no rent and (through the same list) no appreciation today. Decided on main.
        var awayHeld = plugin.ownerAway().held(cached);
        if (awayHeld.isEmpty()) {
            respond(authorized, reply, "Owner-away decisions for today are not saved yet; rent waits.");
            return false;
        }
        var away = awayHeld.get();
        List<Property> enrolled = rentCandidates(cached, away);
        if (enrolled.isEmpty() && !away.isEmpty()) {
            try { if (authorized.getAsBoolean()) reply.accept(ALL_AWAY_REPLY); }
            catch (RuntimeException failure) { plugin.getLogger().warning("Utility reply unavailable; receipt state unchanged."); }
            return true;
        }
        if (enrolled.isEmpty()) {
            respond(authorized, reply, "No enrolled properties for rent.");
            return false;
        }
        boolean isolate = Boolean.TRUE.equals(plugin.getConfig().get(RENT_ISOLATION));
        boolean appreciate = appreciationEnabled(plugin.getConfig().get(APPRECIATION));
        // Flag and parent together (payoutRetirementActive): the same gate every retirement entry uses.
        boolean retire = payoutRetirementActive();
        // housing.rent-change-delay-days in use: SQL holds any home whose rent change is due but not yet applied.
        boolean holdDueRent = rentChangesDue();
        int[] appreciated = {-1}; // Main thread only. -1: appreciation did not run this batch.
        boolean[] rentDeferred = {false}; // Main thread only. Set when the floor defers any of today's rent.
        // Main thread only. Homes this batch's reservation skipped for a due, unapplied rent change (0 with holdNow off).
        int[] dueChangeHeld = {0};
        EconomyLimits limits = plugin.economyLimits();
        LocalDate day = days.today();
        var pinned = registration();
        if (pinned == null || !pinned.getProvider().isEnabled()) return false;
        final Path providerJar;
        try {
            providerJar = Path.of(pinned.getProvider().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception unavailable) { return false; }
        try { financialLease = plugin.financialFence().acquire("property-rent"); }
        catch (RuntimeException occupied) { return false; }
        busy = true;
        // No SQL has been submitted. An acquisition failure may release every acquired lease.
        final List<Property> properties;
        final List<Property> held;
        try {
            requireLimits(limits);
            if (!ready(authorized)) throw new IllegalStateException("Rent owner unavailable");
            // A whole-ARM outage is not one bad property: it still refuses the batch.
            if (isolate && (plugin.armAdapter() == null || !plugin.armAdapter().isAvailable()))
                throw new IllegalStateException("Rent ARM unavailable");
            var plan = planRent(enrolled, reserve, isolate,
                    p -> plugin.armAdapter().ownerReadRegion(p.worldUuid(), p.regionId(), p.ownerUuid()));
            for (int i = 0; i < plan.eligible().size(); i++) {
                Property property = plan.eligible().get(i);
                var owner = plan.owners().get(i);
                if (!ready(authorized)) throw new IllegalStateException("Rent owner unavailable");
                var lease = plugin.ownershipFence().acquire(owner);
                if (rentLeases.putIfAbsent(property.propertyId(), lease) != null) {
                    lease.releaseReconciled();
                    throw new IllegalStateException("Duplicate rent property");
                }
                lease.validate(owner);
            }
            properties = plan.eligible();
            held = plan.held();
        } catch (RuntimeException refused) {
            releaseUnusedRent();
            busy = false;
            releaseFinancial();
            return false;
        }
        if (isolate) noteRentHeld(day, held);
        UUID batchId = UUID.randomUUID();
        final CompletableFuture<String> fingerprint;
        try { fingerprint = CompletableFuture.supplyAsync(() -> hash(providerJar), worker); }
        catch (RuntimeException refused) {
            releaseUnusedRent();
            busy = false;
            releaseFinancial();
            return false;
        }
        // Nothing is written until the reservation step below: a failure up to there started no payout.
        fingerprint.handle((sha, error) -> {
            if (error != null) throw NotStarted.of(error);
            return sha;
        }).thenComposeAsync(sha -> {
            try {
                requireLimits(limits);
                for (Property property : properties)
                    validate(property, property.ownerUuid(), authorized, pinned, rentLeases.get(property.propertyId()), day);
            } catch (RuntimeException | LinkageError refused) { throw new NotStarted(refused); }
            // Re-read on main after the fingerprint hop: switched off mid-batch means no retirement in this batch.
            boolean retireNow = retire && payoutRetirementActive();
            // Re-read on main too: either read in use means due rent changes are held (the safe side).
            boolean holdNow = holdDueRent || rentChangesDue();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return reserveRentBatchCounted(rents, retireNow, holdNow, day, properties, limits, plugin.getLogger()::warning);
                } catch (Exception failure) { throw new IllegalStateException("Rent reservation unconfirmed"); }
            }, worker).thenComposeAsync(step -> {
                var pending = step.pending();
                if (pending.size() > properties.size() || step.heldForDueChange() > properties.size())
                    throw new IllegalStateException("Rent pending bound");
                // Decided under the rent mutex in this batch's own SQL step: the change may be applied before the
                // appreciation phase, after which no later SQL check can see that this home's rent is still owed.
                dueChangeHeld[0] = step.heldForDueChange();
                var expected = new LinkedHashMap<UUID, Property>();
                for (Property property : properties) expected.put(property.propertyId(), property);
                var seen = new HashSet<UUID>();
                var events = new HashSet<Long>();
                for (var row : pending) {
                    if (row == null || !row.property().equals(expected.get(row.property().propertyId()))
                            || row.eventId() <= 0 || row.cents() <= 0 || !events.add(row.eventId())
                            || !seen.add(row.property().propertyId()))
                        throw new IllegalStateException("Rent pending binding failed");
                }
                // SQL acknowledged the reservation/read; properties without pending work can now reopen.
                for (Property property : properties) if (!seen.contains(property.propertyId())) {
                    rentLeases.get(property.propertyId()).releaseReconciled();
                    rentLeases.remove(property.propertyId());
                }
                CompletableFuture<Integer> sequence = CompletableFuture.completedFuture(0);
                for (var row : pending.stream().sorted(Comparator.comparing(r -> r.property().propertyId().toString())).toList()) {
                    sequence = sequence.thenComposeAsync(paid -> payRent(row, sha, limits, day, pinned, authorized,
                                    () -> rentDeferred[0] = true, RENT_DEBIT_GATE)
                            .thenApply(settled -> paid + (settled ? 1 : 0)), mainExecutor);
                }
                return thenAppreciation(sequence, appreciate, () -> appreciationHeld(rentDeferred[0], dueChangeHeld[0]),
                        () -> payAppreciation(properties, sha, limits, day, pinned, authorized),
                        count -> appreciated[0] = count, mainExecutor);
            }, mainExecutor);
        }, mainExecutor).whenComplete((paid, failure) -> {
            try { post(() -> {
                plugin.accountingProjection().invalidate();
                NotStarted stopped = NotStarted.in(failure);
                // No reservation or transfer exists for this batch, so every lease it took may reopen. A lease that
                // cannot be released leaves the batch to the normal recovery hold below.
                if (stopped != null && releaseUnstartedRent(batchId)) {
                    busy = false;
                    releaseFinancial();
                    refusals.notStarted("rent", "batch " + batchId, stopped.getCause());
                    respond(authorized, reply, RENT_NOT_STARTED_REPLY);
                    return;
                }
                if (failure != null) {
                    recovery = true;
                    plugin.getLogger().warning("Rent recovery required; batch " + batchId + "; unreleased leases retained.");
                    plugin.alerts().raise(StaffAlerts.Kind.PAYMENT_HELD_FOR_REVIEW, "batch-" + batchId,
                            "Rent batch held for review. Reference " + batchId + ".");
                    respond(authorized, reply, "Rent batch stopped; do not retry without staff review. Reference: " + batchId);
                } else {
                    busy = false;
                    releaseFinancial();
                    respond(authorized, reply, rentBatchReply(paid, held.size(), appreciate, appreciated[0]));
                    checkServerCap(limits, day);
                }
            });
            } catch (RuntimeException stopped) { /* Closed: no release of outstanding ownership. */ }
        });
        return true;
    }

    /**
     * Standalone bounded payout retirement. It runs even when requestRentBatch would stop before its SQL step (no
     * enabled homes, every owner away), so an expired reservation can never pin a paused home forever. Returns false
     * and touches nothing when payoutRetirementActive() is false. It moves no money, but holds the shared financial
     * fence and this runtime's busy flag, so it never runs while a rent, appreciation, utility or purchase batch has
     * pending rows bound. It uses the same never-attempted rule as the in-batch pass. Accepted: done is always called
     * exactly once on main (the daily cycle continues from it). Refused: done is not called.
     */
    public boolean requestPayoutRetirement(BooleanSupplier authorized, Consumer<String> done) {
        main();
        Objects.requireNonNull(authorized);
        Objects.requireNonNull(done);
        if (!payoutRetirementActive() || busy || recovery || !ready(authorized)) return false;
        try { financialLease = plugin.financialFence().acquire("payout-retirement"); }
        catch (RuntimeException occupied) { return false; }
        busy = true;
        LocalDate day = days.today();
        final CompletableFuture<String> pass;
        try { pass = CompletableFuture.supplyAsync(() -> retirementPass(rents, day, plugin.getLogger()::warning), worker); }
        catch (RuntimeException refused) { busy = false; releaseFinancial(); return false; }
        pass.whenComplete((message, failure) -> {
            try { post(() -> {
                busy = false; releaseFinancial();
                try { done.accept(failure == null ? message : RETIREMENT_UNCONFIRMED); }
                catch (RuntimeException ignored) { plugin.getLogger().warning("Payout retirement reply unavailable."); }
            });
            } catch (RuntimeException stopped) { /* Closed: nothing further runs. */ }
        });
        return true;
    }

    /**
     * The single gate for every payout-retirement entry point (the daily standalone pass, the in-batch pass and
     * resume): economy.payout-retirement-enabled AND payments in use. Payments in use means the system and economy
     * kill switches on, payments opted in (economy.payments-enabled, or the smoke flag while that is unset), and
     * this runtime neither closed nor in startup recovery. With payments off the default-on flag retires nothing
     * anywhere. Only config and switch reads, so resume does not depend on batch readiness. Main thread.
     */
    public boolean payoutRetirementActive() {
        main();
        var switches = plugin.killSwitches();
        return !closed && !recovery && switches != null
                && payoutRetirementActive(plugin.getConfig().get(PAYOUT_RETIREMENT),
                        switches.isSystemEnabled(), switches.isEconomyEnabled(),
                        plugin.getConfig().get(PAYMENTS_OPT_IN), plugin.getConfig().get(SMOKE_OPT_IN));
    }

    /** Pure half of payoutRetirementActive(): the flag (a real Boolean true only) and every parent gate. */
    public static boolean payoutRetirementActive(Object flag, boolean systemEnabled, boolean economyEnabled,
                                                 Object paymentsOptIn, Object smokeOptIn) {
        return payoutRetirementEnabled(flag) && systemEnabled && economyEnabled
                && paymentOptIn(paymentsOptIn, smokeOptIn);
    }

    static boolean payoutRetirementEnabled(Object configured) {
        return Boolean.TRUE.equals(configured);
    }

    static final String RETIREMENT_UNCONFIRMED = "Payout retirement unconfirmed; no money moved.";

    /**
     * Worker half of requestPayoutRetirement: bounded retireStale rounds for every property, enabled or not.
     * Never throws; a failure is logged by retireStale, and only rows retired before it are counted.
     */
    static String retirementPass(RentReservationRepository rents, LocalDate day, Consumer<String> warn) {
        Objects.requireNonNull(rents);
        Objects.requireNonNull(day);
        Objects.requireNonNull(warn);
        int retired = retireStale(rents, day, warn);
        return retired == 0 ? "Payout retirement: nothing to retire. No money moved."
                : "Payout retirement: " + retired + " never-attempted payout reservations retired. No money moved.";
    }

    /**
     * Worker half of a rent batch's SQL step. Retirement off: reserve then the queued read.
     * On: first retire never-attempted payout reservations that can no longer be paid (an earlier day, or an
     * owner or epoch that changed, for example a sale after a floor deferral), so a stale binding neither
     * holds lifetime funding nor makes the queued read refuse the batch. Appreciation's own queued read later
     * in the same batch relies on this pass too.
     */
    static List<RentReservationRepository.Reserved> reserveRentBatch(RentReservationRepository rents, boolean retire,
            LocalDate day, List<Property> properties, EconomyLimits limits, Consumer<String> warn) throws SQLException {
        return reserveRentBatch(rents, retire, false, day, properties, limits, warn);
    }

    /**
     * holdDueRent (housing.rent-change-delay-days in use): a home whose asking-rent change is due but not yet
     * applied gets no reservation (see RentReservationRepository.reserve). False: exactly the plain batch.
     */
    static List<RentReservationRepository.Reserved> reserveRentBatch(RentReservationRepository rents, boolean retire,
            boolean holdDueRent, LocalDate day, List<Property> properties, EconomyLimits limits, Consumer<String> warn)
            throws SQLException {
        return reserveRentBatchCounted(rents, retire, holdDueRent, day, properties, limits, warn).pending();
    }

    /** The batch's SQL step: today's pending rent rows, and how many homes its reservation skipped for a due change. */
    record RentBatchStep(List<RentReservationRepository.Reserved> pending, int heldForDueChange) {
        RentBatchStep {
            pending = List.copyOf(pending);
            if (heldForDueChange < 0) throw new IllegalArgumentException("Negative due-change count");
        }
    }

    /**
     * reserveRentBatch, also reporting RentReservationRepository.Reservation.heldForDueChange for this batch.
     * holdDueRent false: the count is always 0 and the step is exactly reserveRentBatch.
     */
    static RentBatchStep reserveRentBatchCounted(RentReservationRepository rents, boolean retire,
            boolean holdDueRent, LocalDate day, List<Property> properties, EconomyLimits limits, Consumer<String> warn)
            throws SQLException {
        Objects.requireNonNull(rents);
        Objects.requireNonNull(warn);
        if (retire) retireStale(rents, day, warn);
        int heldForDueChange = rents.reserveCounting(day, properties, limits, holdDueRent).heldForDueChange();
        return new RentBatchStep(rents.pending(day, properties), heldForDueChange);
    }

    /**
     * Rent first, per batch: hold this batch's whole appreciation phase if the floor deferred any of today's rent,
     * or if this batch's reservation skipped any home for a due, unapplied rent change (that rent is still owed
     * today even if the change is applied before the appreciation phase runs).
     */
    static boolean appreciationHeld(boolean rentDeferred, int heldForDueChange) {
        return rentDeferred || heldForDueChange > 0;
    }

    /** Main thread. PropertyService.rentChangesDue(): false with the delay at 0 and nothing pending or suspect. */
    private boolean rentChangesDue() {
        var service = plugin.propertyService();
        return service != null && service.rentChangesDue();
    }

    /**
     * Bounded rounds of RentReservationRepository.retireStale. It moves no money, so a failure only logs and the
     * batch continues as it would with the flag off, where a stale binding still refuses the batch.
     * Returns the number retired by this call.
     */
    static int retireStale(RentReservationRepository rents, LocalDate day, Consumer<String> warn) {
        int total = 0;
        try {
            for (int round = 0; round < RETIREMENT_ROUNDS; round++) {
                int retired = rents.retireStale(day, RETIREMENT_LIMIT).size();
                total += retired;
                if (retired < RETIREMENT_LIMIT) break;
            }
        } catch (SQLException | RuntimeException failure) {
            warn.accept("Payout retirement unconfirmed; this batch retired " + total + " and continues without more.");
        }
        if (total > 0)
            warn.accept("Retired " + total + " never-attempted payout reservations (day passed or owner changed). No money moved.");
        return total;
    }

    static final String ALL_AWAY_REPLY = "Rent batch skipped: every owner is away today. No money moved.";

    /** Rent (and so appreciation) candidates: enabled homes in id order, minus owner-away homes. */
    public static List<Property> rentCandidates(Collection<Property> cached, Set<UUID> away) {
        return OwnerAway.present(cached.stream().filter(Property::enabled)
                .sorted(Comparator.comparing(p -> p.propertyId().toString())).toList(), away);
    }

    /**
     * Owner-away homes: no recurring auto-care charge and no manual utility bill for the day, since that day pays
     * no rent. Owner-chosen repairs, upgrades, insurance and evictions stay available.
     */
    public static boolean awayBlocks(boolean away, boolean recurring, long incidentId, boolean purchase) {
        return away && (recurring || (incidentId == 0 && !purchase));
    }

    /** Like every other release gate, only a real Boolean true opens it. */
    static boolean appreciationEnabled(Object configured) {
        return Boolean.TRUE.equals(configured);
    }

    /**
     * Appreciation is paid for the economy day that has closed, never the open one. No rent batch can
     * reserve rent for a closed day, so that day's rent is final and holds its cap share before any
     * appreciation is allocated against it. It shares lifetime funding with future rent, and the batch holds it if
     * the floor deferred any rent.
     */
    static LocalDate appreciationDay(LocalDate batchDay) {
        return batchDay.minusDays(1);
    }

    /**
     * Rent first: the appreciation phase is requested only after the rent sequence completed normally.
     * Off: the rent sequence is returned unchanged. A failed rent sequence never starts appreciation.
     */
    static <T> CompletableFuture<T> thenAppreciation(CompletableFuture<T> rent, boolean appreciate,
            Supplier<CompletableFuture<Integer>> appreciation, IntConsumer counted, Executor executor) {
        return thenAppreciation(rent, appreciate, () -> false, appreciation, counted, executor);
    }

    /**
     * Same, but if the reserve floor deferred any of today's rent in this batch, the whole appreciation
     * phase is held (counted as -1) and never started: deferred rent is not retried after its day ends,
     * so a dividend paid now could take reserve headroom that rent needed.
     */
    static <T> CompletableFuture<T> thenAppreciation(CompletableFuture<T> rent, boolean appreciate,
            BooleanSupplier rentDeferred, Supplier<CompletableFuture<Integer>> appreciation, IntConsumer counted,
            Executor executor) {
        Objects.requireNonNull(rent);
        Objects.requireNonNull(rentDeferred);
        Objects.requireNonNull(appreciation);
        Objects.requireNonNull(counted);
        if (!appreciate) return rent;
        return rent.thenComposeAsync(paid -> {
            if (rentDeferred.getAsBoolean()) {
                counted.accept(-1);
                return CompletableFuture.completedFuture(paid);
            }
            return appreciation.get().thenApply(count -> {
                counted.accept(count);
                return paid;
            });
        }, executor);
    }

    /** Like every other release gate, only a real Boolean true turns the ledger rent hold on. */
    static boolean appreciationLedgerRentHold(Object configured) {
        return Boolean.TRUE.equals(configured);
    }

    /**
     * Worker half of the appreciation phase's SQL step for the batch day. Hold off: reserve then the queued read
     * (never empty). Hold on: one transaction under the rent mutex checks the whole ledger for today's rent that is
     * not final, and every home for a due rent change that left today's rent unreserved. If either exists it reserves
     * nothing and returns empty (held), so even appreciation queued by an earlier batch is not dispatched.
     */
    static Optional<List<RentReservationRepository.Reserved>> appreciationStep(RentReservationRepository rents,
            boolean ledgerHold, LocalDate day, List<Property> eligible, EconomyLimits limits,
            RentReservationRepository.AppreciationScore scorer) throws SQLException {
        Objects.requireNonNull(rents);
        Objects.requireNonNull(day);
        LocalDate closed = appreciationDay(day);
        if (!ledgerHold) {
            rents.reserveAppreciation(closed, eligible, limits, scorer);
            return Optional.of(rents.pendingAppreciation(closed, eligible));
        }
        return rents.reserveAppreciationUnlessRentOutstanding(day, closed, eligible, limits, scorer);
    }

    /**
     * Main thread, after the appreciation SQL hop. Held when the ledger reported outstanding rent (empty step),
     * or when the hold was switched on during the hop, so the ledger was never asked: fail closed. A hold switched
     * off during the hop keeps the decision already taken under the mutex.
     */
    static boolean appreciationLedgerHeld(boolean holdBeforeHop, boolean holdNow, Optional<?> step) {
        Objects.requireNonNull(step);
        return step.isEmpty() || (!holdBeforeHop && holdNow);
    }

    /**
     * Serial reserve payouts behind a live off-switch. stillAllowed is read on the caller's thread before the
     * first row and on executor before every later row, immediately before that row is dispatched. Once it reads
     * false, that row and every later row is skipped (never dispatched, so skip may release its lease), even if
     * the switch turns back on. A row already dispatched is never interrupted here: its credit belongs to the
     * transfer. Returns the settled count, or -1 when the switch stopped the phase before any dispatch.
     */
    static <R> CompletableFuture<Integer> gatedSequence(List<R> rows, BooleanSupplier stillAllowed,
            Function<R, CompletableFuture<Boolean>> pay, Consumer<R> skip, Executor executor) {
        Objects.requireNonNull(rows);
        Objects.requireNonNull(stillAllowed);
        Objects.requireNonNull(pay);
        Objects.requireNonNull(skip);
        Objects.requireNonNull(executor);
        if (rows.isEmpty()) return CompletableFuture.completedFuture(0);
        if (!stillAllowed.getAsBoolean()) {
            for (R row : rows) skip.accept(row);
            return CompletableFuture.completedFuture(-1);
        }
        boolean[] stopped = {false};
        int[] dispatched = {0};
        CompletableFuture<Integer> sequence = CompletableFuture.completedFuture(0);
        for (R row : rows) {
            sequence = sequence.thenComposeAsync(paid -> {
                if (stopped[0] || !stillAllowed.getAsBoolean()) {
                    stopped[0] = true;
                    skip.accept(row);
                    return CompletableFuture.completedFuture(paid);
                }
                dispatched[0]++;
                return pay.apply(row).thenApply(settled -> paid + (settled ? 1 : 0));
            }, executor);
        }
        return sequence.thenApply(paid -> stopped[0] && dispatched[0] == 0 ? -1 : paid);
    }

    /**
     * A payout's own off-switch binds the DEBIT leg only. CREDIT is reachable only after an acknowledged
     * debit, so turning the payout off can never strand money already taken from the reserve.
     */
    static boolean debitGateAllows(TransferProof.Leg leg, BooleanSupplier stillAllowed) {
        return leg == TransferProof.Leg.CREDIT || stillAllowed.getAsBoolean();
    }

    static void requireDebitGate(TransferProof.Leg leg, BooleanSupplier stillAllowed) {
        if (!debitGateAllows(leg, stillAllowed)) throw new IllegalStateException("Payout turned off before debit");
    }

    /** Off (anything but a real Boolean true): owner-away is checked only at admission. */
    static boolean awayDebitRecheckEnabled(Object configured) {
        return Boolean.TRUE.equals(configured);
    }

    /**
     * Whether owner-away holds this money movement now. Off: false without calling held, so no owner-away decision
     * is read or made. On: held's answer (the same predicate as admission). Main thread.
     */
    static boolean awayHeldNow(Object configured, BooleanSupplier held) {
        Objects.requireNonNull(held);
        return awayDebitRecheckEnabled(configured) && held.getAsBoolean();
    }

    /**
     * Owner-away binds the DEBIT leg only. CREDIT is reachable only after an acknowledged debit, so an owner who
     * became away mid-transfer can never strand money already taken; awayHeld is not even read for CREDIT.
     */
    static boolean awayDebitAllows(TransferProof.Leg leg, BooleanSupplier awayHeld) {
        return leg == TransferProof.Leg.CREDIT || !awayHeld.getAsBoolean();
    }

    static void requireAwayDebitGate(TransferProof.Leg leg, BooleanSupplier awayHeld) {
        if (!awayDebitAllows(leg, awayHeld)) throw new IllegalStateException("Owner away before debit");
    }

    /**
     * Rent and appreciation DEBIT checks, in order: both gates (fail fast), then debitOnlyChecks (the floor's
     * getBalance and validate's plugin calls, which are external callbacks that may re-enter and flip
     * either flag), then both gates again as the last reads. CREDIT runs neither debitOnlyChecks nor any
     * flag read, so an acknowledged debit always completes its credit.
     */
    static void finalDebitChecks(TransferProof.Leg leg, Runnable debitOnlyChecks, BooleanSupplier debitGate,
            BooleanSupplier awayHeld) {
        Objects.requireNonNull(debitOnlyChecks);
        requireDebitGate(leg, debitGate);
        requireAwayDebitGate(leg, awayHeld);
        if (leg != TransferProof.Leg.DEBIT) return;
        debitOnlyChecks.run();
        requireDebitGate(leg, debitGate);
        requireAwayDebitGate(leg, awayHeld);
    }

    /**
     * Main thread. Tells staff and the server log about a rent or appreciation payout that stopped before its debit
     * intent, once its lease is released. Nothing moved, so a report that fails must not stop the batch and hold the
     * homes after it: it is written to the server log instead, never thrown. A null alert or warning is not sent.
     */
    private void payoutNotStarted(StaffAlerts.Kind kind, String subject, String alert, String warning) {
        reportNotStarted((k, s, text) -> plugin.alerts().raise(k, s, text), text -> plugin.getLogger().warning(text),
                kind, subject, alert, warning);
    }

    static void reportNotStarted(RefusalMonitor.Alerts alerts, Consumer<String> log, StaffAlerts.Kind kind,
            String subject, String alert, String warning) {
        if (alert != null) RefusalMonitor.quietly(() -> alerts.raise(kind, subject, alert));
        if (warning != null) RefusalMonitor.quietly(() -> log.accept(warning));
    }

    /** Main thread. Rent and appreciation: this home is held by owner-away now (flag on only). */
    private boolean payoutAwayHeld(Property property) {
        return awayHeldNow(plugin.getConfig().get(AWAY_DEBIT_RECHECK), () -> plugin.ownerAway().holds(property));
    }

    /** Staff reply. With appreciation off it has no appreciation part. */
    static String rentBatchReply(int paid, int held, boolean appreciate, int appreciated) {
        return "Rent batch complete: " + paid + " verified payouts. Reserve-floor deferrals remain pending; accounting refresh queued."
                + (held == 0 ? "" : " Rent held: owner unavailable for " + held + " propert" + (held == 1 ? "y." : "ies."))
                + (!appreciate ? "" : appreciated < 0 ? " Yesterday's appreciation held this batch; none paid."
                        : " Yesterday's appreciation: " + appreciated + " verified payouts.");
    }

    /** The owner-away decision, or null when it cannot be read. Before any intent exists, unknown means hold. */
    static Boolean awayDecision(BooleanSupplier held) {
        try { return held.getAsBoolean(); }
        catch (RuntimeException | LinkageError unreadable) { return null; }
    }

    /** Whole cents certainly available, or empty when the balance cannot be read or verified. */
    static OptionalLong readableCents(DoubleSupplier balance) {
        try { return TransferProof.availableCents(balance.getAsDouble()); }
        catch (RuntimeException | LinkageError unreadable) { return OptionalLong.empty(); }
    }

    /** Rent has no payout flag of its own beyond ready(), so its DEBIT gate never refuses. */
    static final BooleanSupplier RENT_DEBIT_GATE = () -> true;

    /**
     * debitGate is the payout's own feature flag (appreciation), reread on main at the DEBIT leg only.
     * The caller has already checked it before dispatch; a refusal here can only follow a reload inside the
     * beginDebit worker hop, so it fails closed into recovery with the journaled intent kept, never erased.
     */
    private CompletableFuture<Boolean> payRent(RentReservationRepository.Reserved row, String sha,
            EconomyLimits limits, LocalDate day, RegisteredServiceProvider<Economy> pinned, BooleanSupplier authorized,
            Runnable floorDeferred, BooleanSupplier debitGate) {
        main();
        Objects.requireNonNull(debitGate);
        Property property = row.property();
        var lease = rentLeases.get(property.propertyId());
        try {
            requireLimits(limits);
            validate(property, property.ownerUuid(), authorized, pinned, lease, day);
        } catch (RuntimeException | LinkageError refused) {
            // No debit intent has been dispatched. Like the owner-away hold below, release the lease and leave the
            // SQL reservation PENDING. A lease that cannot be released stays the normal recovery hold.
            lease.releaseReconciled();
            rentLeases.remove(property.propertyId());
            payoutNotStarted(StaffAlerts.Kind.PAYOUT_REFUSED, "property-" + property.propertyId(),
                    "Payout not started: authority changed. Property " + property.regionId() + ".",
                    "Payout not started for property " + property.regionId() + ": authority changed ("
                            + TransferProof.causeName(refused) + "). Reservation left pending; no money moved.");
            return CompletableFuture.completedFuture(false);
        }
        BooleanSupplier awayHeld = () -> payoutAwayHeld(property);
        // An owner-away decision that cannot be read holds this payout like an away owner: no intent exists yet.
        Boolean away = awayDecision(awayHeld);
        if (away == null || away) {
            // Held by owner-away since the batch admitted it. No debit intent has been dispatched: release the lease and
            // leave the SQL reservation PENDING, as a floor deferral does, without counting as a floor deferral.
            lease.releaseReconciled(); rentLeases.remove(property.propertyId());
            if (away == null) {
                payoutNotStarted(StaffAlerts.Kind.PAYOUT_REFUSED, "property-" + property.propertyId(),
                        "Payout not started: the owner-away decision cannot be read. Property " + property.regionId() + ".",
                        "Payout held for property " + property.regionId()
                                + ": the owner-away decision cannot be read. Reservation left pending; no money moved.");
            } else {
                payoutNotStarted(null, null, null, "Payout held: owner away for property " + property.regionId()
                        + " (decided after the batch started). Reservation left pending; no money moved.");
            }
            return CompletableFuture.completedFuture(false);
        }
        // A reserve balance that cannot be read defers the payout, as an unverifiable one does.
        var available = readableCents(() -> pinned.getProvider().getBalance(Bukkit.getOfflinePlayer(reserve)));
        if(available.isEmpty() || !floorAllows(available.getAsLong(),row.cents(),limits.centralBankFloorCents())) {
            // No debit intent has been dispatched. Retain the SQL reservation, not a live lease.
            lease.releaseReconciled();
            rentLeases.remove(property.propertyId());
            payoutNotStarted(StaffAlerts.Kind.RESERVE_BELOW_FLOOR, "property-" + property.propertyId(),
                    available.isEmpty()
                            ? "Payout deferred: the reserve balance cannot be verified. Property " + property.regionId() + "."
                            : "Payout deferred: the reserve would fall below its floor. Property " + property.regionId() + ".",
                    null);
            floorDeferred.run();
            return CompletableFuture.completedFuture(false);
        }
        var handle = new TransferJournal.Handle(UUID.randomUUID(), row.eventId(), runId, sha);
        var transfer = new JournaledTransfer(journal, (receipt, leg) -> {
            int[] providerChecks = {0};
            var observer = new VaultLegObservation(pinned.getProvider(), () -> {
                requireLegAuthority(leg, () -> registration(), pinned, () -> {
                    requireLimits(limits);
                    validate(property, property.ownerUuid(), authorized, pinned, lease, day);
                });
                // VaultLegObservation's second provider check is after its pre-balance and immediately
                // before withdrawal. Do not apply this subtraction again to the post-debit balance.
                // The flags are reread after requireFloor's getBalance and validate's plugin calls, any of which may
                // re-enter: nothing external runs between that last read and VaultLegObservation's withdraw.
                if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) finalDebitChecks(leg, () -> {
                    requireFloor(pinned.getProvider(), receipt.cents(), limits.centralBankFloorCents());
                    requireLimits(limits);
                    validate(property, property.ownerUuid(), authorized, pinned, lease, day);
                }, debitGate, awayHeld);
                return pinned.getProvider();
            }, Bukkit::isPrimaryThread);
            UUID account = leg == TransferProof.Leg.DEBIT ? reserve : property.ownerUuid();
            UUID counterparty = leg == TransferProof.Leg.DEBIT ? property.ownerUuid() : reserve;
            return observer.execute(account, Bukkit.getOfflinePlayer(account), Bukkit.getOfflinePlayer(counterparty),
                    receipt.cents(), leg);
        }, worker, mainExecutor, (leg, refused) -> {
            // Refused before any money moved. A refused debit is declined and today's reservation stays used;
            // a refused credit holds payments until staff refund the debit.
            plugin.alerts().raise(StaffAlerts.Kind.PAYOUT_REFUSED, "property-" + property.propertyId(),
                    "Payout refused before any money moved (" + refused.refusedBeforeCall() + ", " + leg + "). Property "
                            + property.regionId() + ".");
            refusals.refused("rent", "event " + row.eventId(), leg, refused);
        });
        return transfer.submit(handle, new JournaledTransfer.Lease() {
            public void authorize(TransferJournal.Receipt receipt, TransferProof.Leg leg) {
                // A receipt bound to another payout is a defect: it holds for staff, never declines.
                if (!receipt.handle().equals(handle) || !receipt.payer().equals(reserve)
                        || !receipt.payee().equals(property.ownerUuid()) || receipt.cents() != row.cents())
                    throw new JournaledTransfer.BindingFailed("Rent transfer binding failed");
                requireLegAuthority(leg, () -> registration(), pinned, () -> {
                    requireLimits(limits);
                    validate(property, property.ownerUuid(), authorized, pinned, lease, day);
                });
                finalDebitChecks(leg, () -> {
                    requireFloor(pinned.getProvider(), receipt.cents(), limits.centralBankFloorCents());
                    requireLimits(limits);
                    validate(property, property.ownerUuid(), authorized, pinned, lease, day);
                }, debitGate, awayHeld);
            }
            public void releaseVerified(TransferJournal.Receipt receipt) {
                lease.releaseReconciled();
                rentLeases.remove(property.propertyId());
            }
        }).thenApply(receipt -> {
            if (receipt.state() != TransferJournal.State.PAID && receipt.state() != TransferJournal.State.DECLINED)
                throw new IllegalStateException("Rent transfer requires recovery");
            if (receipt.state() == TransferJournal.State.PAID) refusals.paid("rent");
            return receipt.state() == TransferJournal.State.PAID;
        }).toCompletableFuture();
    }

    /**
     * Main thread, after every rent row of this batch settled or deferred. Pays the closed economy day
     * (appreciationDay): its rent is final, so rent always took its cap share first. Same leases, floor
     * check and journaled reserve-to-owner transfer as rent; validation stays bound to the batch day.
     * A phase that cannot start moves no money and returns -1. SQL reserves only properties whose own
     * rent for the closed day is journal-proved PAID to the same owner, so held rent pays nothing.
     */
    private CompletableFuture<Integer> payAppreciation(List<Property> properties, String sha, EconomyLimits limits,
            LocalDate day, RegisteredServiceProvider<Economy> pinned, BooleanSupplier authorized) {
        main();
        if (!rentLeases.isEmpty()) throw new IllegalStateException("Rent leases outstanding before appreciation");
        // Limits reloaded since rent: nothing is reserved or leased yet, so appreciation simply does not run.
        try { requireLimits(limits); }
        catch (RuntimeException | LinkageError changed) {
            payoutNotStarted(null, null, null, "Appreciation not started: limits changed (" + TransferProof.causeName(changed)
                    + "). Nothing reserved; no money moved.");
            return CompletableFuture.completedFuture(-1);
        }
        // Reread on main before every dispatch: /npcadmin reload can turn appreciation off during any async hop.
        BooleanSupplier stillOn = () -> appreciationEnabled(plugin.getConfig().get(APPRECIATION));
        if (!stillOn.getAsBoolean()) return CompletableFuture.completedFuture(-1);
        // Read on main before the hop and reread after it (appreciationLedgerHeld); the check itself runs under the rent mutex.
        boolean ledgerHold = appreciationLedgerRentHold(plugin.getConfig().get(APPRECIATION_LEDGER_RENT_HOLD));
        LocalDate closed = appreciationDay(day);
        final List<Property> eligible;
        try {
            if (!ready(authorized) || !days.today().equals(day) || plugin.armAdapter() == null || !plugin.armAdapter().isAvailable())
                return CompletableFuture.completedFuture(-1);
            var unchanged = new ArrayList<Property>();
            for (Property property : properties) {
                // Paused, unenrolled, sold or edited since the rent snapshot: no appreciation this batch.
                var now = plugin.propertyService().findByWorldRegion(property.worldUuid(), property.regionId()).orElse(null);
                // Owner away since the batch's admission (flag on): no appreciation for that home this batch.
                if (property.equals(now) && now.enabled() && !payoutAwayHeld(property)) unchanged.add(property);
            }
            // Always isolated here: an unreadable, missing or reserve owner is skipped, never a batch abort.
            var plan = planRent(unchanged, reserve, true,
                    p -> plugin.armAdapter().ownerReadRegion(p.worldUuid(), p.regionId(), p.ownerUuid()));
            for (int i = 0; i < plan.eligible().size(); i++) {
                Property property = plan.eligible().get(i);
                var owner = plan.owners().get(i);
                var lease = plugin.ownershipFence().acquire(owner);
                if (rentLeases.putIfAbsent(property.propertyId(), lease) != null) {
                    lease.releaseReconciled();
                    throw new IllegalStateException("Duplicate appreciation property");
                }
                lease.validate(owner);
            }
            eligible = plan.eligible();
        } catch (RuntimeException refused) {
            releaseUnusedRent();
            return CompletableFuture.completedFuture(-1);
        }
        if (eligible.isEmpty()) return CompletableFuture.completedFuture(0);
        var scorer = rents.ratedScore();
        final CompletableFuture<Optional<List<RentReservationRepository.Reserved>>> reserved;
        try {
            reserved = CompletableFuture.supplyAsync(() -> {
                try {
                    return appreciationStep(rents, ledgerHold, day, eligible, limits, scorer);
                } catch (Exception failure) { throw new IllegalStateException("Appreciation reservation unconfirmed"); }
            }, worker);
        } catch (RuntimeException rejected) {
            releaseUnusedRent();
            return CompletableFuture.completedFuture(-1);
        }
        return reserved.thenComposeAsync(step -> {
            if (appreciationLedgerHeld(ledgerHold,
                    appreciationLedgerRentHold(plugin.getConfig().get(APPRECIATION_LEDGER_RENT_HOLD)), step)) {
                // Nothing dispatched, so no debit intent exists and every lease may reopen. Queued rows stay PENDING.
                for (Property property : eligible) {
                    var lease = rentLeases.remove(property.propertyId());
                    if (lease != null) lease.releaseReconciled();
                }
                plugin.getLogger().warning("Appreciation for " + closed + " held: " + (step.isEmpty()
                        ? "rent for " + day + " is still outstanding in the ledger, or held for a due rent change not yet applied."
                        : "the ledger rent hold was switched on during the batch.") + " None paid; rent first.");
                return CompletableFuture.completedFuture(-1);
            }
            var pending = step.orElseThrow();
            if (pending.size() > eligible.size()) throw new IllegalStateException("Appreciation pending bound");
            var expected = new LinkedHashMap<UUID, Property>();
            for (Property property : eligible) expected.put(property.propertyId(), property);
            var seen = new HashSet<UUID>();
            var events = new HashSet<Long>();
            for (var row : pending) {
                if (row == null || !row.property().equals(expected.get(row.property().propertyId()))
                        || row.eventId() <= 0 || row.cents() <= 0 || !events.add(row.eventId())
                        || !seen.add(row.property().propertyId()))
                    throw new IllegalStateException("Appreciation pending binding failed");
            }
            for (Property property : eligible) if (!seen.contains(property.propertyId())) {
                rentLeases.get(property.propertyId()).releaseReconciled();
                rentLeases.remove(property.propertyId());
            }
            int[] held = {0}; // Main thread only.
            var rows = pending.stream().sorted(Comparator.comparing(r -> r.property().propertyId().toString())).toList();
            return gatedSequence(rows, stillOn,
                    row -> payRent(row, sha, limits, day, pinned, authorized, () -> { }, stillOn),
                    row -> {
                        // Never dispatched, so no debit intent exists and the lease may reopen. The reservation stays
                        // PENDING (unresolved: it still counts against caps and funding) for the retirement path.
                        rentLeases.get(row.property().propertyId()).releaseReconciled();
                        rentLeases.remove(row.property().propertyId());
                        held[0]++;
                    }, mainExecutor).thenApply(count -> {
                        if (held[0] > 0) plugin.getLogger().warning("Appreciation turned off during the batch; "
                                + held[0] + " reserved appreciation payout(s) for " + closed + " left pending, not paid.");
                        return count;
                    });
        }, mainExecutor);
    }

    /** Pre-dispatch deferral rule for every reserve payout (rent and appreciation): the floor must survive it. */
    public static boolean floorAllows(long availableCents, long cents, long floorCents) {
        return availableCents >= cents && availableCents - cents >= floorCents;
    }

    private void requireFloor(Economy provider, long cents, long floor) {
        long balance = TransferProof.availableCents(provider.getBalance(Bukkit.getOfflinePlayer(reserve))).orElseThrow();
        if (cents <= 0 || floor < 0 || balance < cents || balance - cents < floor)
            throw new IllegalStateException("Rent reserve floor unavailable");
    }

    private void requireLimits(EconomyLimits pinned) {
        main();
        if (!limitValues(pinned).equals(limitValues(plugin.economyLimits()))
                || !limitValues(pinned).equals(limitValues(EconomyLimits.load(plugin))))
            throw new IllegalStateException("Rent limits changed");
    }

    private static List<Number> limitValues(EconomyLimits l) {
        return List.of(l.serverDailyCapCents(), l.benefitUnitDailyCapCents(), l.propertyDailyCapCents(),
                l.tenantDailyCapCents(), l.appreciationDailyCapCents(), l.centralBankFloorCents(),
                l.propertySeedCents(), l.rolloutSeedPoolCents(), l.lifetimeSinkFundingRatio());
    }

    /**
     * Main thread. Alerts-flag gated: reads the day's paid and pending total on the worker, then alerts at 90 percent of the
     * server cap.
     */
    private void checkServerCap(EconomyLimits limits, LocalDate day) {
        if (!plugin.getConfig().getBoolean("alerts.enabled", false) || limits.serverDailyCapCents() <= 0) return;
        long cap = limits.serverDailyCapCents();
        try {
            CompletableFuture.runAsync(() -> serverCapCheck(() -> rents.serverSpentCents(day), cap, day,
                    message -> plugin.alerts().raise(StaffAlerts.Kind.SERVER_CAP_NEAR, "server-cap", message),
                    plugin.getLogger()::warning, serverCapReadWarned), worker);
        } catch (RuntimeException busyOrClosed) { /* Skipped; the next batch checks again. */ }
    }

    @FunctionalInterface
    interface SpentRead { long read() throws Exception; }

    /**
     * Worker half of checkServerCap. Observability only: payments never depend on it. A failed read is
     * logged once per economy day instead of being swallowed, so a broken alert path is visible.
     * Returns true when SERVER_CAP_NEAR was raised.
     */
    static boolean serverCapCheck(SpentRead read, long cap, LocalDate day, Consumer<String> alert,
                                  Consumer<String> warn, AtomicReference<LocalDate> warnedDay) {
        final long spent;
        try { spent = read.read(); }
        catch (Exception unreadable) {
            if (!day.equals(warnedDay.getAndSet(day)))
                warn.accept("Server cap alert could not read today's payout total (" + unreadable
                        + "). SERVER_CAP_NEAR is unavailable until this is fixed; payments are unaffected.");
            return false;
        }
        if (!StaffAlerts.capNear(spent, cap)) return false;
        alert.accept("Server daily payout cap is " + (spent * 100 / cap) + " percent used.");
        return true;
    }

    /** Main thread. Replaces the held view; logs each held property once per economy day. */
    private void noteRentHeld(LocalDate day, List<Property> held) {
        main();
        rentHeldLogged.values().removeIf(logged -> !logged.equals(day));
        rentHeld = List.copyOf(held);
        rentHeldDay = day;
        for (Property property : held) {
            if (day.equals(rentHeldLogged.put(property.propertyId(), day))) continue;
            plugin.alerts().raise(StaffAlerts.Kind.RENT_PROPERTY_HELD, "property-" + property.propertyId(),
                    "Rent held: owner unavailable for property " + property.regionId() + ".");
            plugin.getLogger().warning("Rent held: owner unavailable for property " + property.propertyId()
                    + " (region " + property.regionId() + ", day " + day + "). Other properties continue; no payment for this one.");
        }
    }

    /** Main thread. Null when nothing is held today; otherwise one short staff line, bounded to five regions. */
    public String rentHeldStatus() {
        main();
        var current = rentHeld;
        if (current.isEmpty() || rentHeldDay == null || !rentHeldDay.equals(days.today())) return null;
        var names = current.stream().limit(5).map(Property::regionId).toList();
        return "Rent held: owner unavailable for " + current.size() + " propert" + (current.size() == 1 ? "y" : "ies")
                + " (" + String.join(", ", names) + (current.size() > 5 ? ", +" + (current.size() - 5) + " more" : "") + ")";
    }

    /** Only for failure before any SQL dispatch. */
    private void releaseUnusedRent() {
        for (var lease : new ArrayList<>(rentLeases.values())) lease.releaseReconciled();
        rentLeases.clear();
    }

    /** Worker only: one domain preparation, with no player/provider/config access. */
    private Prepared prepare(UUID requestId, Property property, UUID actor, long incidentId, PurchaseTarget target,
                             LocalDate day, long maximumCents, long availableCents) {
        try {
            if (target != null) {
                var quote = switch (target.kind()) {
                    case EVICT -> purchases.quoteEviction(requestId, property.propertyId(), actor, property.ownershipEpoch(),
                            target.tenantId(), day);
                    case INSURANCE -> purchases.quoteInsurance(requestId, property.propertyId(), actor, property.ownershipEpoch(), day);
                    default -> new PurchaseRequest(requestId, property.propertyId(), actor, property.ownershipEpoch(),
                            target.kind(), target.upgrade(), target.level(), null, day);
                };
                if (!quote.requestId().equals(requestId) || !quote.propertyId().equals(property.propertyId())
                        || !quote.payerId().equals(actor) || quote.ownershipEpoch() != property.ownershipEpoch()
                        || !quote.day().equals(day) || quote.kind() != target.kind()
                        || !Objects.equals(quote.tenantId(), target.tenantId())
                        || (target.kind() == PurchaseRequest.Kind.EVICT && quote.eviction() == null)
                        || (target.kind() == PurchaseRequest.Kind.INSURANCE
                            && (quote.insurance() == null || quote.insurance().capacity() != property.capacity())))
                    throw new IllegalStateException("Purchase quote binding failed");
                var receipt = purchases.enqueue(quote, maximumCents, availableCents);
                var existing = receipt.request();
                if (!existing.propertyId().equals(quote.propertyId()) || !existing.payerId().equals(actor)
                        || existing.ownershipEpoch() != quote.ownershipEpoch()
                        || !existing.actionKey().equals(quote.actionKey()) || existing.kind() != quote.kind())
                    throw new IllegalStateException("Property purchase identity mismatch");
                if (receipt.state() == PurchaseRepository.State.APPLIED)
                    return Prepared.stopped(Result.PURCHASE_ALREADY_APPLIED);
                if (!existing.equals(quote) || receipt.state() != PurchaseRepository.State.WAITING
                        || receipt.quotedCents() != quote.cents())
                    throw new IllegalStateException("Property purchase preparation requires recovery");
                return new Prepared(receipt.eventId(), quote.cents(), null, quote, null);
            }
            if (incidentId > 0) {
                var quote = purchases.quoteRepair(requestId, property.propertyId(), actor,
                        property.ownershipEpoch(), incidentId, day);
                if (!quote.requestId().equals(requestId) || !quote.propertyId().equals(property.propertyId())
                        || !quote.payerId().equals(actor) || quote.ownershipEpoch() != property.ownershipEpoch()
                        || !quote.day().equals(day) || quote.kind() != PurchaseRequest.Kind.REPAIR
                        || quote.repair() == null || quote.repair().incidentId() != incidentId)
                    throw new IllegalStateException("Repair quote binding failed");
                var receipt = purchases.enqueue(quote, maximumCents, availableCents);
                if (receipt.state() == PurchaseRepository.State.APPLIED
                        && !receipt.request().requestId().equals(requestId)
                        && receipt.request().propertyId().equals(property.propertyId())
                        && receipt.request().kind() == PurchaseRequest.Kind.REPAIR
                        && receipt.request().repair().incidentId() == incidentId)
                    return Prepared.stopped(Result.REPAIR_UNAVAILABLE);
                if (!receipt.request().equals(quote) || receipt.state() != PurchaseRepository.State.WAITING
                        || receipt.quotedCents() != quote.cents())
                    throw new IllegalStateException("Repair preparation requires recovery");
                return new Prepared(receipt.eventId(), quote.cents(), null, quote, null);
            }
            var history = utilities.history(property.propertyId(), day);
            UtilityRepository.Receipt paid = null;
            for (var prior : history) {
                var bill = prior.bill();
                if (!bill.propertyId().equals(property.propertyId()) || !bill.ownerId().equals(actor)
                        || bill.ownershipEpoch() != property.ownershipEpoch() || !bill.day().equals(day)
                        || prior.state() == UtilityRepository.State.WAITING
                        || prior.state() == UtilityRepository.State.REVIEW_REQUIRED)
                    throw new IllegalStateException("Utility history requires review");
                if (prior.state() == UtilityRepository.State.PAID) paid = prior;
            }
            if (paid != null) return Prepared.stopped(Result.ALREADY_PAID);
            if (availableCents <= 0) return Prepared.stopped(Result.INSUFFICIENT_FUNDS);
            var prepared = utilities.preparePayment(requestId, property.propertyId(), actor,
                    property.ownershipEpoch(), day, Math.min(maximumCents, availableCents));
            var bill = prepared.receipt().bill();
            if (!prepared.newlyQueued() || !prepared.receipt().requestId().equals(requestId)
                    || !bill.propertyId().equals(property.propertyId()) || !bill.ownerId().equals(actor)
                    || bill.ownershipEpoch() != property.ownershipEpoch() || !bill.day().equals(day)
                    || prepared.receipt().state() != UtilityRepository.State.WAITING)
                throw new IllegalStateException("Utility preparation binding failed");
            return new Prepared(prepared.receipt().eventId(), bill.cents(), bill, null, null);
        } catch (UtilityRepository.MaximumExceededException overCap) {
            if (overCap.getSuppressed().length != 0) throw new IllegalStateException("Maximum rollback unconfirmed");
            return Prepared.stopped(availableCents < maximumCents ? Result.INSUFFICIENT_FUNDS : Result.OVER_CAP);
        } catch (PurchaseRepository.MaximumExceededException overCap) {
            if (overCap.getSuppressed().length != 0) throw new IllegalStateException("Maximum rollback unconfirmed");
            return Prepared.stopped(Result.OVER_CAP);
        } catch (PurchaseRepository.InsufficientFundsException refused) {
            if (refused.getSuppressed().length != 0) throw new IllegalStateException("Funds refusal rollback unconfirmed");
            return Prepared.stopped(Result.INSUFFICIENT_FUNDS);
        } catch (PurchaseRepository.TargetUnavailableException refused) {
            if (refused.getSuppressed().length != 0) throw new IllegalStateException("Purchase refusal rollback unconfirmed");
            return Prepared.stopped(Result.TARGET_UNAVAILABLE);
        } catch (PurchaseRepository.RepairUnavailableException resolved) {
            if (resolved.getSuppressed().length != 0) throw new IllegalStateException("Repair refusal rollback unconfirmed");
            return Prepared.stopped(Result.REPAIR_UNAVAILABLE);
        } catch (PurchaseRepository.ItemsPendingException held) {
            if (held.getSuppressed().length != 0) throw new IllegalStateException("Repair refusal rollback unconfirmed");
            return Prepared.stopped(Result.REPAIR_ITEMS_PENDING);
        } catch (PurchaseRepository.EvictionUnavailableException refused) {
            if (refused.getSuppressed().length != 0) throw new IllegalStateException("Eviction refusal rollback unconfirmed");
            return Prepared.stopped(Result.EVICTION_UNAVAILABLE);
        } catch (Exception failure) { throw new IllegalStateException("Payment preparation unconfirmed"); }
    }

    /** Worker only: the lease is retained until the utility or repair commit is acknowledged. */
    private Result complete(Prepared prepared, UUID requestId, TransferJournal.Receipt transfer) {
        try {
            boolean paid = transfer.state() == TransferJournal.State.PAID;
            if (prepared.repair() != null) {
                var result = purchases.complete(prepared.eventId()).orElseThrow();
                if (result.eventId() != prepared.eventId() || !result.request().equals(prepared.repair())
                        || result.quotedCents() != prepared.cents()
                        || result.state() != (paid ? PurchaseRepository.State.APPLIED : PurchaseRepository.State.DECLINED))
                    throw new IllegalStateException("Repair completion binding failed");
                return paid ? switch (prepared.repair().kind()) {
                    case REPAIR -> Result.REPAIRED;
                    case EVICT -> Result.EVICTED;
                    case INSURANCE -> Result.INSURED;
                    default -> Result.PURCHASED;
                } : Result.DECLINED;
            }
            var result = utilities.reconcile(prepared.eventId()).orElseThrow();
            if (result.eventId() != prepared.eventId() || !result.requestId().equals(requestId)
                    || !result.bill().equals(prepared.bill())
                    || result.state() != (paid ? UtilityRepository.State.PAID : UtilityRepository.State.DECLINED))
                throw new IllegalStateException("Utility reconciliation binding failed");
            return paid ? Result.PAID : Result.DECLINED;
        } catch (Exception failure) { throw new IllegalStateException("Payment completion unconfirmed"); }
    }

    private boolean ready(BooleanSupplier authorized) {
        return !closed && startupReady && plugin.isEnabled() && plugin.ownershipFence() != null
                && plugin.killSwitches().isSystemEnabled() && plugin.killSwitches().isEconomyEnabled()
                && paymentOptIn(plugin.getConfig().get(PAYMENTS_OPT_IN), plugin.getConfig().get(SMOKE_OPT_IN))
                && authorized.getAsBoolean();
    }
    static boolean paymentOptIn(Object current, Object legacy) {
        return Boolean.TRUE.equals(current == null ? legacy : current);
    }
    private boolean accountingReady() {
        return plugin.accountingProjection() != null && plugin.accountingProjection().ready();
    }

    /**
     * What a leg rechecks on main before its money call. The debit runs every check. The credit is reachable only
     * after an acknowledged debit and completes it: a config reload, a kill switch, a limit, the player leaving or
     * losing permission, a home sale or a day rollover between the legs must not strand money already taken. So
     * the credit checks only that the economy provider the debit used is still the registered one and enabled.
     * The receipt's binding to its accounts is checked before this, for both legs.
     */
    static void requireLegAuthority(TransferProof.Leg leg, Supplier<RegisteredServiceProvider<Economy>> registered,
                                    RegisteredServiceProvider<Economy> pinned, Runnable debitChecks) {
        if (leg != TransferProof.Leg.CREDIT) {
            debitChecks.run();
            return;
        }
        if (registered.get() != pinned || !pinned.getPlugin().isEnabled() || !pinned.getProvider().isEnabled())
            throw new IllegalStateException("Economy provider changed before the credit");
    }

    private void validate(Property property, UUID actor, BooleanSupplier authorized,
                          RegisteredServiceProvider<Economy> pinned, OwnershipFence.Lease lease, LocalDate day) {
        main();
        if (financialLease == null) throw new IllegalStateException("NPC reserve lease unavailable");
        financialLease.validate();
        if (!ready(authorized) || !days.today().equals(day)
                || !reserve.toString().equals(plugin.getConfig().getString("centralbank.uuid"))
                || registration() != pinned || !pinned.getPlugin().isEnabled() || !pinned.getProvider().isEnabled())
            throw new IllegalStateException("Utility authority unavailable");
        var current = plugin.propertyService().findByWorldRegion(property.worldUuid(), property.regionId()).orElseThrow();
        if (!current.equals(property) || !actor.equals(current.ownerUuid()) || !current.enabled())
            throw new IllegalStateException("Utility property changed");
        lease.validate(plugin.armAdapter().ownerReadRegion(property.worldUuid(), property.regionId(), actor).orElseThrow());
    }

    private RegisteredServiceProvider<Economy> registration() {
        return plugin.getServer().getServicesManager().getRegistration(Economy.class);
    }

    private void respond(BooleanSupplier authorized, Consumer<String> reply, String message) {
        try { if (!closed && authorized.getAsBoolean()) reply.accept(message); }
        catch (RuntimeException failure) { plugin.getLogger().warning("Utility reply unavailable; receipt state unchanged."); }
    }

    static String hash(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Provider jar unavailable");
            long size = Files.size(path);
            if (size < 1 || size > 128L * 1024 * 1024) throw new IllegalStateException("Provider jar bounds");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > size) throw new IllegalStateException("Provider jar changed");
                    digest.update(buffer, 0, n);
                }
                if (total != size) throw new IllegalStateException("Provider jar changed");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw new IllegalStateException("Provider fingerprint unavailable"); }
    }

    private void releaseFinancial() {
        if (financialLease != null) {
            financialLease.releaseVerified();
            financialLease = null;
        }
    }

    private synchronized void post(Runnable task) {
        if (closed || !deliveries.offer(task)) throw new RejectedExecutionException("Utility delivery unavailable");
    }

    /** True while a payment of this runtime is held for staff, including the startup hold. Main thread. */
    public boolean recoveryHeld() {
        main();
        return recovery;
    }

    public String status() {
        main();
        return closed ? "closed" : recovery ? "recovery-held" : !startupReady ? "startup-recovery-pending"
                : busy ? "busy" : accountingReady() ? "beta-only; accounting acknowledged" : "beta-only; accounting pending";
    }

    @Override public synchronized void close() {
        main();
        if (closed) return;
        closed = true;
        ticker.cancel();
        deliveries.clear();
        worker.shutdown();
        // Never release retainedLease here: an accepted SQL commit may still finish.
    }

    private static void main() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
    }
}
