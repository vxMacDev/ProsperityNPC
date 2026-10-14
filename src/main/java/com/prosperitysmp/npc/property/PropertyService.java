package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.arm.ArmAdapter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded async SQL reconciliation, NOT atomic ARM admission authorization. */
public final class PropertyService {
    public record ReconcileResult(int checked, int changed, int skipped, int missing) {}
    private final ArmAdapter arm;
    private final PropertyRepository repo;
    private final Map<UUID, Property> cache = new ConcurrentHashMap<>();
    private final Set<UUID> missingRegions = ConcurrentHashMap.newKeySet();
    private volatile long lastReconcileMillis;
    private final ExecutorService worker;
    private final OwnershipReconcilePump pump;
    private final BukkitTask ticker;
    private final org.bukkit.event.Listener worldLifecycle;
    private final OwnershipVisibility visibility;
    private final ProsperityNpcPlugin plugin;
    private boolean refreshRequested;
    private boolean admissionPending;
    // Main thread only. Pending: some property holds a delayed rent change. Suspect: a rent step was not confirmed.
    private boolean pendingRentChanges, rentCacheSuspect;
    // Main thread only. A guarded rent step waiting to try again (housing.rent-change-lease-guard-enabled).
    private Runnable rentRetry;
    private long rentRetryTicks;
    // Main thread only. The guard is on but ARM coordination is off; logged once per service.
    private boolean unguardedFallbackLogged;
    private final ConcurrentLinkedQueue<Runnable> admissionReplies = new ConcurrentLinkedQueue<>();
    public PropertyService(ProsperityNpcPlugin plugin, ArmAdapter arm, PropertyRepository repo) {
        this(plugin, arm, repo, System::nanoTime);
    }
    public PropertyService(ProsperityNpcPlugin plugin, ArmAdapter arm, PropertyRepository repo,
                           java.util.function.LongSupplier monotonicClock) {
        this.plugin = plugin; this.arm = arm; this.repo = repo;
        visibility = new OwnershipVisibility(monotonicClock);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
            Thread t = new Thread(r, "npc-ownership-reconcile"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.trackDatabaseWorker(worker);
        pump = new OwnershipReconcilePump(new OwnershipReconcilePump.Host() {
            public Property current(UUID id) { return cache.get(id); }
            public OwnershipReconcilePump.Observation observe(Property p) { return capture(p); }
            public void refreshing(UUID id) { if (cache.containsKey(id)) missingRegions.add(id); }
            public void uncertain(UUID id) { refreshing(id); visibility.invalidate(id); }
            // Read live: a held home (payment between DEBIT and CREDIT) is never rebased under its lease.
            public com.prosperitysmp.npc.arm.OwnershipFence fence() { return plugin.ownershipFence(); }
            public void stuck(UUID id, int retries) {
                plugin.getLogger().warning("Ownership check for property " + id + " has found it busy " + retries
                        + " times in a row (about a minute); it keeps retrying quietly until the home is free.");
            }
            public void rebase(Property expected, Property authoritative, boolean verified) {
                if (!expected.propertyId().equals(authoritative.propertyId())) return;
                if (!authoritative.enabled()) {
                    cache.remove(expected.propertyId(), expected); missingRegions.remove(expected.propertyId());
                    visibility.invalidate(expected.propertyId());
                } else if (cache.replace(expected.propertyId(), expected, authoritative)) {
                    if (verified) {
                        missingRegions.remove(expected.propertyId()); visibility.verified(expected.propertyId());
                    } else { missingRegions.add(expected.propertyId()); visibility.invalidate(expected.propertyId()); }
                }
            }
        }, (expected, owner) -> {
            try { return new OwnershipReconcilePump.Write(repo.reconcileOwner(expected, owner), true); }
            catch (SQLException | RuntimeException conflict) {
                // Repair cache only: the first commit may have succeeded without acknowledgement.
                return new OwnershipReconcilePump.Write(repo.findById(expected.propertyId()).orElse(null), false);
            }
        }, worker);
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Runnable reply = admissionReplies.poll();
            if (reply != null) reply.run();
            if (rentRetry != null && --rentRetryTicks <= 0) { Runnable retry = rentRetry; rentRetry = null; retry.run(); }
            pump.tick();
            if (refreshRequested) {
                var accepted = requestReconcile(r -> {});
                if (accepted != OwnershipReconcilePump.Acceptance.BUSY) refreshRequested = false;
            }
        }, 1L, 1L);
        worldLifecycle = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
            public void unload(org.bukkit.event.world.WorldUnloadEvent event) {
                worldChanged(event.getWorld().getUID(), event.isCancelled());
            }
            @org.bukkit.event.EventHandler
            public void load(org.bukkit.event.world.WorldLoadEvent event) { worldChanged(event.getWorld().getUID(), false); }
        };
        plugin.getServer().getPluginManager().registerEvents(worldLifecycle, plugin);
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required"); }
    private void worldChanged(UUID world, boolean cancelled) {
        main(); Set<UUID> affected = OwnershipVisibility.affected(cache.values(), world, cancelled);
        if (affected.isEmpty()) return;
        affected.forEach(visibility::invalidate);
        invalidateReconciliation(); refreshRequested = true;
    }
    private OwnershipReconcilePump.Observation capture(Property p) {
        main(); World world = Bukkit.getWorld(p.worldUuid());
        if (world == null || !arm.isAvailable())
            return new OwnershipReconcilePump.Observation(OwnershipReconcilePump.Presence.UNAVAILABLE, null);
        return arm.findRegion(world.getName(), p.regionId())
                .filter(v -> v.matches(p.worldUuid(), p.regionId()))
                .map(v -> new OwnershipReconcilePump.Observation(OwnershipReconcilePump.Presence.PRESENT, v.ownerUuid()))
                // ARM currently conflates absent regions with read failures.
                .orElseGet(() -> new OwnershipReconcilePump.Observation(OwnershipReconcilePump.Presence.UNAVAILABLE, null));
    }
    /** Startup/legacy explicit load still performs synchronous SQL, not used by the pump. */
    public void loadCache() throws SQLException {
        main(); invalidateReconciliation(); visibility.clear();
        List<Property> rows = repo.listEnrolled();
        if (rows.size() > 512) throw new SQLException("Enrolled property limit exceeds 512");
        cache.clear(); missingRegions.clear();
        for (Property p : rows) { cache.put(p.propertyId(), p); missingRegions.add(p.propertyId()); }
        refreshRequested = true;
    }
    public Collection<Property> getEnrolled() { return List.copyOf(cache.values()); }
    public int enrolledCount() { return cache.size(); }
    /** Includes pending, unavailable and unverified ownership. */
    public int missingCount() { return missingRegions.size(); }
    public boolean isRegionMissing(UUID id) { return !cache.containsKey(id) || missingRegions.contains(id); }
    /** Renderer-only grace period. Neither cache API authorizes finances or admission;
     * isRegionMissing is only a legacy fail-closed hold. */
    public boolean isDisplayUnavailable(UUID id) {
        main(); return !cache.containsKey(id) || visibility.unavailable(id);
    }
    public long lastReconcileMillis() { return lastReconcileMillis; }
    /** Homes the ownership check is waiting on because a payment or other step holds them. Main thread. */
    public int waitingOnBusyCount() { main(); return pump.deferredIds().size(); }
    /** The /npcadmin health suffix for waitingOnBusyCount: empty at 0. */
    public static String waitingOnBusy(int homes) { return homes > 0 ? "; " + homes + " waiting on a busy home" : ""; }
    /**
     * True while the ownership check's SQL write for this home is in flight. A single-home admission that takes
     * the home's lease and whose completion checks SQL ownership refuses meanwhile ("busy, try again"). Main thread.
     */
    public boolean reconcileWriting(UUID propertyId) { main(); return pump.writing(propertyId); }
    public Optional<Property> findByWorldRegion(UUID world, String region) {
        return cache.values().stream().filter(p -> p.worldUuid().equals(world) && p.regionId().equals(region)).findFirst();
    }
    public void enroll(Property property, long seedCents) throws SQLException {
        main(); if (cache.size() >= 512) throw new SQLException("Enrolled property limit reached");
        invalidateReconciliation(); repo.insertEnrollment(property, seedCents);
        cache.put(property.propertyId(), property); missingRegions.add(property.propertyId());
        refreshRequested = true;
    }
    /** Bounded enrollment with a lifetime seed allowance, never an immediate cash grant. */
    public boolean enrollCoordinated(Property property, ArmAdapter.OwnerRead owner,
            java.util.function.Supplier<ArmAdapter.OwnerRead> refreshOwner, Consumer<Boolean> done) {
        main();
        long seed = plugin.getConfig().getLong("economy.property-seed-cents", 5000);
        long seedPool = plugin.getConfig().getLong("economy.rollout-seed-pool-cents", 50000);
        if (seed < 0 || seed > 5000 || seedPool < 0 || seedPool > 50000) return false;
        var fence = plugin.ownershipFence();
        if (fence == null || admissionPending || cache.size() >= 512
                || findByWorldRegion(property.worldUuid(), property.regionId()).isPresent()) return false;
        if (!property.worldUuid().equals(owner.worldId()) || !property.regionId().equals(owner.regionId())
                || !Objects.equals(property.ownerUuid(), owner.ownerId())) return false;
        var lease = fence.acquire(owner);
        try { lease.validate(refreshOwner.get()); }
        catch (RuntimeException rejected) { lease.releaseReconciled(); throw rejected; }
        admissionPending = true;
        try {
            worker.execute(() -> {
                boolean acknowledged = false;
                try { repo.insertEnrollmentWithSeedPool(property, seed, seedPool); acknowledged = true; }
                catch (SQLException | RuntimeException failure) {
                    // Retain the lease on uncertain commit; never blindly retry with a new UUID.
                }
                boolean committed = acknowledged;
                admissionReplies.add(() -> {
                    if (!committed) {
                        plugin.getLogger().severe("Enrollment requires recovery: property " + property.propertyId());
                        done.accept(false);
                        return;
                    }
                    try {
                        lease.validate(refreshOwner.get());
                        invalidateReconciliation();
                        cache.put(property.propertyId(), property);
                        missingRegions.add(property.propertyId());
                        refreshRequested = true;
                        lease.releaseReconciled();
                        admissionPending = false;
                    } catch (RuntimeException failure) {
                        plugin.getLogger().severe("Committed enrollment authority requires recovery: property " + property.propertyId());
                        done.accept(false);
                        return;
                    }
                    done.accept(true);
                });
            });
            return true;
        } catch (RejectedExecutionException busy) {
            admissionPending = false;
            lease.releaseReconciled(); // Executor rejected before any database work.
            return false;
        }
    }
    /** Staff resume reads SQL off-thread, then captures fresh ARM authority before the write. */
    public boolean resumeCoordinated(UUID world, String region, java.util.function.BooleanSupplier authorized,
                                     Consumer<String> done) {
        main();
        if (admissionPending || cache.size() >= 512 || plugin.ownershipFence() == null || !authorized.getAsBoolean()) return false;
        admissionPending = true;
        try {
            worker.execute(() -> {
                Property paused;
                try { paused = repo.findByWorldRegion(world, region).orElse(null); }
                catch (SQLException | RuntimeException failure) {
                    admissionReplies.add(() -> { admissionPending=false; done.accept("Property lookup unavailable; nothing resumed."); });
                    return;
                }
                admissionReplies.add(() -> {
                    if (paused == null || paused.enabled() || !authorized.getAsBoolean()) {
                        admissionPending=false; done.accept("No paused property authorized for resume."); return;
                    }
                    var liveWorld = Bukkit.getWorld(world);
                    var view = liveWorld == null ? null : arm.findRegion(liveWorld.getName(), region).orElse(null);
                    var owner = view == null || view.ownerUuid() == null ? null
                            : arm.ownerReadRegion(world, region, view.ownerUuid()).orElse(null);
                    if (owner == null) { admissionPending=false; done.accept("Current sole ARM owner unavailable; resume refused."); return; }
                    final com.prosperitysmp.npc.arm.OwnershipFence.Lease lease;
                    try { lease = plugin.ownershipFence().acquire(owner); }
                    catch (RuntimeException busy) { admissionPending=false; done.accept("Ownership busy; nothing resumed."); return; }
                    // economy.payout-retirement-enabled AND its parent (payments in use: the same
                    // UtilityPaymentRuntime.payoutRetirementActive() gate the daily-cycle and in-batch retirement use),
                    // read on main right before the SQL hop; nothing moves money. Flag off, parent off or no payment
                    // runtime: the two-argument resume, exactly as before.
                    final var payments = plugin.utilityPayments();
                    final boolean retirementParent = payments != null && payments.payoutRetirementActive();
                    final PropertyRepository.ResumePreparation retire = resumeRetirement(
                            plugin.getConfig().get(com.prosperitysmp.npc.economy.UtilityPaymentRuntime.PAYOUT_RETIREMENT),
                            retirementParent, paused.propertyId(), economyToday(), plugin.getLogger()::info);
                    try {
                        worker.execute(() -> {
                            Property resumed;
                            try { resumed = retire == null ? repo.resumeExpected(paused, owner.ownerId())
                                    : repo.resumeExpected(paused, owner.ownerId(), retire); }
                            catch (PropertyRepository.ResumeRefused refused) {
                                admissionReplies.add(() -> {
                                    lease.releaseReconciled(); admissionPending=false;
                                    done.accept("Resume refused: paused state changed or an unsettled payment needs review. No resume written.");
                                });
                                return;
                            }
                            catch (SQLException | RuntimeException failure) {
                                admissionReplies.add(() -> {
                                    plugin.getLogger().severe("Resume requires review: property " + paused.propertyId());
                                    done.accept("Resume not confirmed; property held for staff review. Do not retry.");
                                });
                                return;
                            }
                            admissionReplies.add(() -> {
                                try {
                                    lease.validate(arm.ownerReadRegion(world, region, owner.ownerId()).orElse(null));
                                    invalidateReconciliation(); cache.put(resumed.propertyId(), resumed);
                                    missingRegions.add(resumed.propertyId()); refreshRequested=true;
                                    lease.releaseReconciled(); admissionPending=false;
                                    done.accept("Resumed " + region + "; existing tenants, incidents and funding history retained.");
                                } catch (RuntimeException failure) {
                                    done.accept("Committed resume authority changed; held for staff review.");
                                }
                            });
                        });
                    } catch (RejectedExecutionException busy) {
                        lease.releaseReconciled(); admissionPending=false; done.accept("Database worker busy; nothing resumed.");
                    }
                });
            });
            return true;
        } catch (RejectedExecutionException busy) { admissionPending=false; return false; }
    }

    public boolean setRentCoordinated(UUID world, String region, UUID actor, long cents,
                                     java.util.function.BooleanSupplier authorized, Consumer<String> done) {
        main();
        var property = findByWorldRegion(world, region).orElse(null);
        if (admissionPending || property == null || !property.enabled() || !actor.equals(property.ownerUuid())
                || plugin.ownershipFence() == null || pump.writing(property.propertyId())
                || !authorized.getAsBoolean() || cents <= 0
                || cents < (long) property.referenceRentCents()*50/100
                || cents > (long) property.referenceRentCents()*150/100) return false;
        int delay = rentChangeDelayDays(plugin.getConfig().get(RENT_CHANGE_DELAY));
        if (delay < 0) return false;
        var owner = plugin.armAdapter().ownerReadRegion(world, region, actor).orElse(null);
        if (owner == null) return false;
        final java.time.LocalDate today = economyToday();
        final com.prosperitysmp.npc.arm.OwnershipFence.Lease lease;
        try { lease = plugin.ownershipFence().acquire(owner); } catch (RuntimeException held) { return false; }
        admissionPending = true;
        try {
            worker.execute(() -> {
                try {
                    // Pending state is read from SQL under the row lock; no in-memory flag decides it.
                    var change = repo.changeAskingRent(property, cents, today, delay);
                    var updated = change.property();
                    admissionReplies.add(() -> {
                        invalidateReconciliation(); cache.put(updated.propertyId(), updated); refreshRequested=true;
                        if (change.outcome() == PropertyRepository.RentChange.Outcome.SCHEDULED) pendingRentChanges = true;
                        lease.releaseReconciled(); admissionPending=false;
                        done.accept(rentChangeReply(change));
                    });
                } catch (PropertyRepository.ResumeRefused refused) {
                    admissionReplies.add(() -> {
                        if (refused.getSuppressed().length != 0) {
                            plugin.getLogger().severe("Rent refusal cleanup requires recovery: property " + property.propertyId());
                            done.accept("Rent outcome needs staff review; do not retry blindly.");
                            return;
                        }
                        lease.releaseReconciled(); admissionPending=false;
                        done.accept("Property changed or rent outside its allowed range; no rent change applied."); });
                } catch (SQLException | RuntimeException uncertain) {
                    admissionReplies.add(() -> {
                        plugin.getLogger().severe("Rent change requires recovery: property " + property.propertyId());
                        done.accept("Rent outcome needs staff review; do not retry blindly.");
                    });
                }
            });
            return true;
        } catch (RejectedExecutionException refused) { admissionPending=false; lease.releaseReconciled(); return false; }
    }

    public static final String RENT_CHANGE_DELAY = "housing.rent-change-delay-days";

    /** Missing or 0 = immediate (as before). 1..30 economy days. Anything else is invalid (-1): rent changes refused. */
    public static int rentChangeDelayDays(Object configured) {
        if (configured == null) return 0;
        if (!(configured instanceof Integer days)) return -1;
        return days >= 0 && days <= PropertyRepository.MAX_RENT_DELAY_DAYS ? days : -1;
    }

    /** Plain owner reply for one rent command; amounts are what SQL recorded. */
    public static String rentChangeReply(PropertyRepository.RentChange change) {
        String rent = com.prosperitysmp.npc.util.Money.format(change.cents()) + "/day";
        return switch (change.outcome()) {
            case APPLIED_NOW -> "Asking rent set to " + rent + ".";
            case SCHEDULED -> "Rent changes to " + rent + " on " + change.effectiveDay() + ". "
                    + (change.notices() == 1 ? "1 resident was told." : change.notices() + " residents were told.");
            case ALREADY_SCHEDULED -> "Rent already changes to " + rent + " on " + change.effectiveDay() + ". Nothing changed.";
            case CANCELLED -> "Pending rent change cancelled. Rent stays " + rent + ".";
            case UNCHANGED -> "Rent is already " + rent + ". Nothing changed.";
        };
    }

    /** True when the daily cycle must run the rent-change step first. Main thread. */
    public boolean rentChangesDue() {
        main();
        int delay = rentChangeDelayDays(plugin.getConfig().get(RENT_CHANGE_DELAY));
        return delay > 0 || pendingRentChanges || rentCacheSuspect;
    }

    /** Startup only: changes left pending while the delay was above 0 still apply on their day. */
    public void notePendingRentChanges(boolean pending) { main(); pendingRentChanges = pending; }

    /** Reads the SQL count of properties with a pending rent change. */
    @FunctionalInterface public interface PendingRentCount { int read() throws SQLException; }

    /**
     * Startup pending state. Unreadable fails closed: it counts as pending, so the daily cycle runs the rent
     * step, which re-reads the real state from SQL (stillPending) instead of a failed read meaning "none".
     */
    public static boolean pendingRentChangesAtStartup(PendingRentCount count, Consumer<String> warn) {
        Objects.requireNonNull(count); Objects.requireNonNull(warn);
        try {
            int pending = count.read();
            if (pending < 0) throw new SQLException("Negative pending rent count");
            return pending > 0;
        } catch (SQLException | RuntimeException unreadable) {
            warn.accept("Pending rent changes unreadable at startup; treating as pending until the rent step re-reads them: "
                    + unreadable.getMessage());
            return true;
        }
    }

    /**
     * Start of the daily cycle: applies every due pending asking rent in one SQL transaction, then rebases
     * the cache, so matching and rent read the new value from its effective day and never before.
     * False: busy, nothing started. done receives true only when SQL and cache agree.
     */
    public boolean applyDueRentChanges(java.time.LocalDate today, java.util.function.BiConsumer<Boolean, String> done) {
        main(); Objects.requireNonNull(today); Objects.requireNonNull(done);
        if (admissionPending) return false;
        // housing.rent-change-lease-guard-enabled, read live on main before any hop. Off: exactly the step below.
        if (DueRentLeases.enabled(plugin.getConfig().get(RENT_CHANGE_LEASE_GUARD))) {
            var fence = plugin.ownershipFence();
            if (fence != null) return applyDueRentChangesLeased(fence, today, done);
            // No ARM coordination: no payment, payout or admission can hold a home, so none can be in flight.
            if (!unguardedFallbackLogged) {
                unguardedFallbackLogged = true;
                plugin.getLogger().info("housing.rent-change-lease-guard-enabled is on, but ARM ownership coordination is off,"
                        + " so no payment can be in progress; the rent change step runs without holding homes.");
            }
        }
        final boolean rebase = rentCacheSuspect;
        final List<UUID> cachedIds = List.copyOf(cache.keySet());
        admissionPending = true;
        try {
            worker.execute(() -> {
                PropertyRepository.DueRentResult result;
                List<Property> authoritative = new ArrayList<>();
                try {
                    result = repo.applyDueRentChanges(today, PropertyRepository.MAX_DUE_RENT_CHANGES);
                    if (rebase) for (UUID id : cachedIds) repo.findById(id).ifPresent(authoritative::add);
                } catch (SQLException | RuntimeException uncertain) {
                    admissionReplies.add(() -> {
                        rentCacheSuspect = true; admissionPending = false;
                        plugin.getLogger().severe("Rent change step not confirmed; matching and rent wait for the next cycle.");
                        done.accept(false, "Rent change step not confirmed; later phases not started.");
                    });
                    return;
                }
                admissionReplies.add(() -> {
                    var outcome = rebaseDueRent(cache, result, authoritative, id -> true);
                    int applied = outcome.applied(), cancelled = outcome.cancelled(), replaced = outcome.replaced();
                    boolean drift = outcome.drift();
                    if (!result.changes().isEmpty() || drift) { invalidateReconciliation(); refreshRequested = true; }
                    rentCacheSuspect = drift;
                    pendingRentChanges = result.stillPending() > 0;
                    admissionPending = false;
                    if (drift) {
                        plugin.getLogger().warning("Rent change cache differs from SQL; matching and rent wait for reconciliation.");
                        done.accept(false, "Rent changes applied but the property cache needs reconciliation; later phases not started.");
                        return;
                    }
                    done.accept(true, dueRentSummary(applied, cancelled, replaced));
                });
            });
            return true;
        } catch (RejectedExecutionException busy) { admissionPending = false; return false; }
    }

    public static final String RENT_CHANGE_LEASE_GUARD = "housing.rent-change-lease-guard-enabled";

    /** Cache side of one rent step: counts by resolution, and whether the cache and SQL disagree afterwards. */
    public record DueRentCacheOutcome(int applied, int cancelled, int replaced, boolean drift) { }

    /**
     * Applies one rent step's SQL result to the cache. mayReplace false: that entry is left exactly as it is
     * (a payment holds it), and any difference from SQL counts as drift. Always true: the original step.
     */
    public static DueRentCacheOutcome rebaseDueRent(Map<UUID, Property> cache, PropertyRepository.DueRentResult result,
                                                    List<Property> authoritative, java.util.function.Predicate<UUID> mayReplace) {
        Objects.requireNonNull(cache); Objects.requireNonNull(result);
        Objects.requireNonNull(authoritative); Objects.requireNonNull(mayReplace);
        int applied = 0, cancelled = 0, replaced = 0;
        boolean drift = false;
        for (var change : result.changes()) {
            switch (change.resolution()) {
                case APPLIED -> applied++;
                case CANCELLED -> cancelled++;
                case REPLACED -> replaced++;
            }
            Property cached = cache.get(change.before().propertyId());
            if (cached == null) continue;
            if (!cached.equals(change.before())) drift = true;
            else if (mayReplace.test(cached.propertyId())) cache.replace(cached.propertyId(), cached, change.after());
            else if (!cached.equals(change.after())) drift = true;
        }
        for (Property fresh : authoritative) {
            Property cached = cache.get(fresh.propertyId());
            if (cached == null || cached.askingRentCents() == fresh.askingRentCents()) continue;
            if (cached.withAskingRent(fresh.askingRentCents()).equals(fresh) && mayReplace.test(cached.propertyId()))
                cache.replace(cached.propertyId(), cached, fresh);
            else drift = true;
        }
        return new DueRentCacheOutcome(applied, cancelled, replaced, drift);
    }

    /**
     * housing.rent-change-lease-guard-enabled. The same step, fenced (GuardedRentStep): nothing due takes no lease
     * and writes nothing; otherwise every cached property's ownership slot is taken on main before the SQL hop and
     * held until the cache matches SQL, so no payment can be between its DEBIT and CREDIT on a property whose
     * record this step changes. A held property is excluded and its change stays pending; deferred changes retry
     * every DueRentLeases.RETRY_TICKS up to MAX_ATTEMPTS. An unconfirmed step whose re-read also fails keeps its
     * leases and re-reads on a bounded backoff from the ticker until SQL answers, then releases.
     */
    private boolean applyDueRentChangesLeased(com.prosperitysmp.npc.arm.OwnershipFence fence, java.time.LocalDate today,
                                              java.util.function.BiConsumer<Boolean, String> done) {
        main();
        admissionPending = true;
        final boolean started;
        try {
            started = GuardedRentStep.start(rentStepHost, new GuardedRentStep.Sql() {
                public PropertyRepository.DueRentStatus status(java.time.LocalDate day) throws SQLException {
                    return repo.dueRentStatus(day);
                }
                public PropertyRepository.DueRentResult apply(java.time.LocalDate day, Set<UUID> excluded) throws SQLException {
                    return repo.applyDueRentChanges(day, PropertyRepository.MAX_DUE_RENT_CHANGES, excluded);
                }
                public Optional<Property> find(UUID id) throws SQLException { return repo.findById(id); }
            }, fence, worker::execute, admissionReplies::add, today, done);
        } catch (RuntimeException failure) { admissionPending = false; throw failure; }
        if (!started) admissionPending = false;
        return started;
    }

    /** PropertyService state for GuardedRentStep. Main thread only. */
    private final GuardedRentStep.Host rentStepHost = new GuardedRentStep.Host() {
        public Map<UUID, Property> cache() { return cache; }
        public boolean cacheSuspect() { return rentCacheSuspect; }
        public void cacheSuspect(boolean suspect) { rentCacheSuspect = suspect; }
        public void pendingRentChanges(boolean pending) { pendingRentChanges = pending; }
        public void cacheChanged() { invalidateReconciliation(); refreshRequested = true; }
        public void finished() { admissionPending = false; }
        public void schedule(long ticks, Runnable next) { rentRetryTicks = ticks; rentRetry = next; }
        public void held(int properties, String text) {
            var alerts = plugin.alerts();
            if (alerts != null) alerts.raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.DAILY_PHASE_FAILED,
                    "rent-change-held", text);
        }
        public void log(java.util.logging.Level level, String message) { plugin.getLogger().log(level, message); }
    };

    /** Staff log line for one daily rent step. Unchanged wording when nothing was replaced. */
    static String dueRentSummary(int applied, int cancelled, int replaced) {
        if (applied == 0 && cancelled == 0 && replaced == 0) return "No rent changes due.";
        return "Rent changes applied: " + applied
                + (cancelled == 0 ? "" : "; cancelled after an owner or terms change: " + cancelled)
                + (replaced == 0 ? "" : "; replaced by a newer asking rent: " + replaced) + ".";
    }

    /**
     * Resume-time payout retirement. Only a real Boolean true flag with its parent active (parentActive: the
     * caller's UtilityPaymentRuntime.payoutRetirementActive(), i.e. switches on and payments opted in) enables it;
     * anything else returns null and resume runs exactly as before, so the default-on flag is inert with payments
     * off. On: the paused home's own never-attempted reservations that can no longer be paid
     * (an earlier day, or a changed owner or epoch) are retired in the resume transaction, so a server where no
     * rent batch can run (every home paused, every owner away) can still resume it. Bounded; moves no money.
     * The rent mutex is taken before the property lock; retirement runs after resume writes the fresh ARM owner,
     * so a paused home sold today (never reconciled while paused) retires the old owner's never-attempted rows
     * as OWNER_CHANGED instead of refusing until they expire.
     * The day is taken on main before the hop; a stale day only retires less.
     */
    public static PropertyRepository.ResumePreparation resumeRetirement(Object flag, boolean parentActive, UUID propertyId,
                                                                 java.time.LocalDate today, Consumer<String> log) {
        if (!Boolean.TRUE.equals(flag) || !parentActive) return null;
        Objects.requireNonNull(propertyId); Objects.requireNonNull(today); Objects.requireNonNull(log);
        return new PropertyRepository.ResumePreparation() {
            @Override public void lockFirst(java.sql.Connection c) throws SQLException {
                com.prosperitysmp.npc.economy.RentReservationRepository.lockMutex(c);
            }
            @Override public void prepare(java.sql.Connection c) throws SQLException {
                int retired = com.prosperitysmp.npc.economy.RentReservationRepository.retireHeldFor(c, propertyId, today, 500).size();
                if (retired > 0) log.accept("Resume of property " + propertyId + " retires " + retired
                        + " never-attempted payout reservations if it commits. No money moved.");
            }
        };
    }

    private java.time.LocalDate economyToday() {
        return new com.prosperitysmp.npc.clock.EconomyClock(java.time.Clock.systemUTC(),
                java.time.ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago")),
                plugin.getConfig().getInt("clock.daily-reset-hour", 4)).today();
    }

    public boolean unenroll(UUID world, String region) throws SQLException {
        throw new IllegalStateException("Use coordinated unenrollment; synchronous ownership mutation retired");
    }
    public boolean unenrollCoordinated(UUID world,String region,java.util.function.BooleanSupplier authorized,Consumer<String> done) {
        main();
        var p=findByWorldRegion(world,region);
        if(admissionPending || p.isEmpty() || plugin.ownershipFence()==null || !authorized.getAsBoolean())return false;
        var property=p.get();
        if(pump.writing(property.propertyId()))return false; // The ownership check is writing this home: busy.
        var owner=plugin.armAdapter().ownerReadRegion(world,region,property.ownerUuid()).orElse(null);
        if(owner==null)return false;
        final com.prosperitysmp.npc.arm.OwnershipFence.Lease lease;
        try{lease=plugin.ownershipFence().acquire(owner);}catch(RuntimeException held){return false;}
        admissionPending=true;
        try {
            worker.execute(()->{
                try {
                    boolean changed=repo.disableExpected(property);
                    admissionReplies.add(()->{
                        invalidateReconciliation();
                        if(changed) {
                            cache.remove(property.propertyId());missingRegions.remove(property.propertyId());visibility.invalidate(property.propertyId());
                        }
                        refreshRequested=true;lease.releaseReconciled();admissionPending=false;
                        done.accept(changed?"Property disabled; tenants, receipts and history retained."
                                :"Property changed before disable; no disable was applied.");
                    });
                }catch(SQLException|RuntimeException uncertain) {
                    admissionReplies.add(()->{
                        plugin.getLogger().severe("Unenrollment requires recovery: property "+property.propertyId());
                        done.accept("Disable outcome needs recovery; history retained, do not retry blindly.");
                    });
                }
            });
            return true;
        }catch(RejectedExecutionException refused){admissionPending=false;lease.releaseReconciled();return false;}
    }
    public OwnershipReconcilePump.Acceptance requestReconcile(Consumer<OwnershipReconcilePump.Result> done) {
        main(); return pump.request(cache.values(), r -> {
            if (reconciled(r)) lastReconcileMillis = System.currentTimeMillis();
            try { done.accept(r); }
            catch (RuntimeException callbackFailure) {
                plugin.getLogger().warning("Ownership completion callback failed: " + callbackFailure.getClass().getSimpleName());
            }
        });
    }
    /** The scheduled pass warns when any home was not verified, including homes it found busy (deferred). */
    public static boolean reconcileIncomplete(OwnershipReconcilePump.Result r) {
        return r.failed() > 0 || r.missing() > 0 || r.skipped() > 0 || r.deferred() > 0;
    }
    public static String reconcileIncompleteText(OwnershipReconcilePump.Result r) {
        return "ARM reconcile incomplete: verified=" + r.checked() + " skipped=" + r.skipped() + " missing=" + r.missing()
                + " unverified=" + r.failed() + (r.deferred() > 0 ? " waiting-on-busy-home=" + r.deferred() : "");
    }
    /** A finished pass counts as a reconcile unless every home it reached was busy (it only deferred). */
    static boolean reconciled(OwnershipReconcilePump.Result r) {
        return !r.cancelled() && (r.checked() > 0 || r.deferred() == 0);
    }
    public void invalidateReconciliation() { main(); missingRegions.addAll(cache.keySet()); pump.invalidate(); }

    /** Worker side: one property's current SQL row, for a step that must install it before releasing its lease. */
    public Optional<Property> readRow(UUID propertyId) throws SQLException { return repo.findById(propertyId); }

    /** What installLeasedRows did: rows put into the cache, and homes left for the ownership check to settle. */
    public record LeasedInstall(Set<UUID> installed, Set<UUID> unsettled) {
        public LeasedInstall { installed = Set.copyOf(installed); unsettled = Set.copyOf(unsettled); }
    }

    /**
     * A step that changed SQL rows under its leases, before it releases them: each re-read row replaces the cached
     * record only if that record is still the very one the step leased (identity) and the row has the same home,
     * owner, epoch and enabled state. A row that differs in any of those, a missing row, or a cached record some
     * other writer replaced is not installed; that home is unsettled and left for the ownership check.
     */
    public static LeasedInstall installLeasedRows(Map<UUID, Property> cache, Collection<Property> leased,
                                                  Collection<Property> rows) {
        Objects.requireNonNull(cache); Objects.requireNonNull(leased); Objects.requireNonNull(rows);
        Map<UUID, Property> fresh = new HashMap<>();
        for (Property row : rows) fresh.put(row.propertyId(), row);
        Set<UUID> installed = new LinkedHashSet<>(), unsettled = new LinkedHashSet<>();
        for (Property held : leased) {
            UUID id = held.propertyId();
            Property cached = cache.get(id), row = fresh.get(id);
            if (cached == null) continue;
            if (cached != held || row == null) { unsettled.add(id); continue; }
            if (row.equals(held)) continue;
            if (row.worldUuid().equals(held.worldUuid()) && row.regionId().equals(held.regionId())
                    && Objects.equals(row.ownerUuid(), held.ownerUuid()) && row.ownershipEpoch() == held.ownershipEpoch()
                    && row.enabled() == held.enabled()) {
                cache.put(id, row); installed.add(id);
            } else unsettled.add(id);
        }
        return new LeasedInstall(installed, unsettled);
    }

    /**
     * Main thread, while the caller still holds the leases. rows null: the re-read failed, so every leased home
     * is marked refreshing, single-home admissions wait for it (reconcileWriting) and the ownership check retries it
     * until it installs SQL's row; the step's own SQL work is already committed and stays as it is. Otherwise
     * installLeasedRows, and any unsettled home is marked uncertain for the ownership check. Returns how many rows
     * were installed.
     */
    public int installLeased(Collection<Property> leased, List<Property> rows, String step) {
        main(); Objects.requireNonNull(leased); Objects.requireNonNull(step);
        if (rows == null) {
            for (Property p : leased) if (cache.containsKey(p.propertyId())) missingRegions.add(p.propertyId());
            pump.markAhead(leased.stream().map(Property::propertyId).toList());
            if (!leased.isEmpty()) refreshRequested = true;
            plugin.getLogger().warning(step + " could not re-read " + leased.size()
                    + " homes from the database; their cached records are refreshed by the next ownership check.");
            return 0;
        }
        var outcome = installLeasedRows(cache, leased, rows);
        for (UUID id : outcome.unsettled()) if (cache.containsKey(id)) { missingRegions.add(id); visibility.invalidate(id); }
        if (!outcome.installed().isEmpty() || !outcome.unsettled().isEmpty()) refreshRequested = true;
        if (!outcome.unsettled().isEmpty())
            plugin.getLogger().warning(step + " left " + outcome.unsettled().size()
                    + " homes for the ownership check: their database row no longer matches the record the step held.");
        return outcome.installed().size();
    }
    public void close() {
        main(); ticker.cancel(); org.bukkit.event.HandlerList.unregisterAll(worldLifecycle);
        visibility.clear(); refreshRequested = false; rentRetry = null;
        try { pump.close(); } finally { worker.shutdownNow(); }
    }
}
