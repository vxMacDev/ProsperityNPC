package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.arm.OwnershipFence;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * housing.rent-change-lease-guard-enabled: the daily asking-rent change step, fenced. This is the production
 * decision path PropertyService runs; it owns no plugin state itself, so a test drives exactly this code.
 *
 * <ol>
 * <li>Unless the cache is already suspect, a read-only SQL check runs first. Nothing due: no lease is taken,
 *     nothing is written, and the reply is the unguarded step's "No rent changes due.".</li>
 * <li>Something due (or a suspect cache to rebase): every cached property's ownership slot is taken on main
 *     (DueRentLeases). A busy one is excluded; a coordinator failure aborts the attempt with every lease released.</li>
 * <li>The SQL step runs with those exclusions; on main every held entry SQL reported (a change's after-row, or a
 *     re-read row) is set to that full row whenever it differs in ANY field (installHeld), and only then are the
 *     leases released: a held home is never released over a record SQL contradicts. An excluded (busy) entry is
 *     never touched; the fenced reconcile pump rebases it after its holder releases it.
 *     Deferred changes retry every DueRentLeases.RETRY_TICKS, at most MAX_ATTEMPTS times.</li>
 * <li>SQL outcome unknown: the held ids are re-read. Readable: rebase, release, done. Unreadable: the leases stay
 *     held (no payment may start on a cache SQL may contradict), a staff alert is raised, and the re-read is tried
 *     again on a bounded backoff (REREAD_TICKS doubling up to MAX_REREAD_TICKS) until it succeeds or the service
 *     closes. The first successful re-read rebases, releases and ends the step exactly like the readable branch.</li>
 * </ol>
 * Main thread only, except Sql calls, which run on worker.
 */
public final class GuardedRentStep {
    /** First wait before re-reading SQL after an unconfirmed step that could not be re-read (5 seconds). */
    public static final long REREAD_TICKS = 100L;
    /** Longest wait between re-reads (one minute), so an outage of any length is retried at least once a minute. */
    public static final long MAX_REREAD_TICKS = 1200L;

    /** The worker side. Each call is one SQL round trip. */
    public interface Sql {
        PropertyRepository.DueRentStatus status(LocalDate today) throws SQLException;
        PropertyRepository.DueRentResult apply(LocalDate today, Set<UUID> excluded) throws SQLException;
        Optional<Property> find(UUID propertyId) throws SQLException;
    }

    /** PropertyService state the step reads and writes. Main thread only. */
    public interface Host {
        Map<UUID, Property> cache();
        boolean cacheSuspect();
        void cacheSuspect(boolean suspect);
        void pendingRentChanges(boolean pending);
        /** The cache changed or may differ from SQL: invalidate reconciliation and request a refresh. */
        void cacheChanged();
        /** The step is over and holds nothing: admissionPending false. */
        void finished();
        /** One pending ticker callback (PropertyService.rentRetry); close() drops it. */
        void schedule(long ticks, Runnable next);
        /** Leases held while the SQL outcome is unknown. Raise a staff alert (alerts on). */
        void held(int properties, String text);
        void log(Level level, String message);
    }

    static final String UNREADABLE = "Rent change step could not read due changes; nothing changed and later phases not started.";
    static final String NOT_STARTED = "Rent change step could not start; later phases not started.";
    static final String HELD = "Rent change step not confirmed; properties held until the database can be re-read. Do not retry blindly.";
    static final String NOT_CONFIRMED = "Rent change step not confirmed; later phases not started.";
    static final String DRIFT = "Rent changes applied but the property cache needs reconciliation; later phases not started.";
    static final String NOT_FINISHED = "Rent change step could not finish; later phases not started.";

    private final Host host;
    private final Sql sql;
    private final OwnershipFence fence;
    private final Executor worker;
    private final Executor main;
    private final LocalDate today;
    private final BiConsumer<Boolean, String> done;
    private final int[] totals = new int[3];

    private GuardedRentStep(Host host, Sql sql, OwnershipFence fence, Executor worker, Executor main, LocalDate today,
                            BiConsumer<Boolean, String> done) {
        this.host = Objects.requireNonNull(host); this.sql = Objects.requireNonNull(sql);
        this.fence = Objects.requireNonNull(fence); this.worker = Objects.requireNonNull(worker);
        this.main = Objects.requireNonNull(main); this.today = Objects.requireNonNull(today);
        this.done = Objects.requireNonNull(done);
    }

    /**
     * Main thread; the caller has already set admissionPending. True: accepted, done is called exactly once and
     * finished() is called when the step holds nothing any more. False: nothing was submitted and no lease is held;
     * done is not called and the caller clears admissionPending.
     */
    public static boolean start(Host host, Sql sql, OwnershipFence fence, Executor worker, Executor main, LocalDate today,
                                BiConsumer<Boolean, String> done) {
        return new GuardedRentStep(host, sql, fence, worker, main, today, done).begin();
    }

    /**
     * Cache side of one guarded step. heldMatch: after the install, every held entry SQL reported equals its SQL row
     * (or is no longer cached), so the leases may be released.
     */
    record Installed(PropertyService.DueRentCacheOutcome outcome, boolean heldMatch) { }

    /**
     * Installs SQL's rows for held entries. holds(id) true: this step holds that home's lease, so no payment can be
     * between its DEBIT and CREDIT on it, and the cached record is set to the full SQL row (the change's after-row,
     * then any re-read row) whenever it differs in any field: asking rent, condition, owner, anything. holds(id)
     * false: a payment or other holder has it; the entry is left exactly as it is, and a difference counts as drift
     * as in PropertyService.rebaseDueRent (a change whose after-row differs; a re-read row whose asking rent differs).
     */
    static Installed installHeld(Map<UUID, Property> cache, PropertyRepository.DueRentResult result,
                                 List<Property> authoritative, Predicate<UUID> holds) {
        Objects.requireNonNull(cache); Objects.requireNonNull(result);
        Objects.requireNonNull(authoritative); Objects.requireNonNull(holds);
        int applied = 0, cancelled = 0, replaced = 0;
        boolean drift = false;
        var rows = new java.util.LinkedHashMap<UUID, Property>();
        for (var change : result.changes()) {
            switch (change.resolution()) {
                case APPLIED -> applied++;
                case CANCELLED -> cancelled++;
                case REPLACED -> replaced++;
            }
            rows.put(change.after().propertyId(), change.after());
        }
        // A re-read is at least as new as the step's own after-row.
        for (Property fresh : authoritative) rows.put(fresh.propertyId(), fresh);
        boolean heldMatch = true;
        for (Property row : rows.values()) {
            UUID id = row.propertyId();
            Property cached = cache.get(id);
            if (cached == null || cached.equals(row)) continue;
            if (holds.test(id)) {
                if (!cache.replace(id, cached, row)) heldMatch = false;
            } else if (result.changes().stream().anyMatch(c -> c.after().propertyId().equals(id))
                    || cached.askingRentCents() != row.askingRentCents()) drift = true;
        }
        for (Property row : rows.values()) {
            Property cached = cache.get(row.propertyId());
            if (holds.test(row.propertyId()) && cached != null && !cached.equals(row)) heldMatch = false;
        }
        return new Installed(new PropertyService.DueRentCacheOutcome(applied, cancelled, replaced, drift), heldMatch);
    }

    /** Wait before the n-th re-read (n from 1): 100, 200, 400, 800, then 1200 ticks. */
    public static long rereadDelay(int failures) {
        int doublings = Math.max(0, Math.min(4, failures - 1));
        return Math.min(MAX_REREAD_TICKS, REREAD_TICKS << doublings);
    }

    private boolean begin() {
        // A suspect cache always needs the guarded rebase, so there is nothing to check first.
        if (host.cacheSuspect()) return attempt(1);
        try {
            worker.execute(() -> {
                final PropertyRepository.DueRentStatus status;
                try { status = sql.status(today); }
                catch (SQLException | RuntimeException unreadable) {
                    main.execute(() -> {
                        // Read only: nothing was written, so the cache is not suspect. The next cycle checks again.
                        host.finished();
                        host.log(Level.WARNING, "Rent change status unreadable; nothing changed: " + unreadable.getMessage());
                        done.accept(false, UNREADABLE);
                    });
                    return;
                }
                main.execute(() -> {
                    if (status.due() == 0 && !host.cacheSuspect()) {
                        // Nothing due: no lease, no write. The same state and reply as the unguarded step.
                        host.pendingRentChanges(status.pending() > 0);
                        host.finished();
                        done.accept(true, PropertyService.dueRentSummary(0, 0, 0));
                        return;
                    }
                    if (attempt(1)) return;
                    host.finished();
                    done.accept(false, NOT_STARTED);
                });
            });
            return true;
        } catch (RejectedExecutionException busy) { return false; }
    }

    /** One guarded attempt. Main thread. False: nothing was submitted and every lease taken here is released. */
    private boolean attempt(int attempt) {
        final boolean rebase = host.cacheSuspect();
        final List<UUID> cachedIds = List.copyOf(host.cache().keySet());
        final DueRentLeases leases;
        try { leases = DueRentLeases.acquire(fence, host.cache().values()); }
        catch (RuntimeException unavailable) {
            host.log(Level.WARNING, "Rent change step stopped: ownership coordinator unavailable ("
                    + unavailable.getMessage() + "); nothing changed.");
            return false;
        }
        final Set<UUID> excluded = leases.excluded();
        final List<UUID> heldIds = leases.heldIds();
        try {
            worker.execute(() -> {
                final PropertyRepository.DueRentResult result;
                final List<Property> authoritative = new ArrayList<>();
                try {
                    result = sql.apply(today, excluded);
                    if (rebase) for (UUID id : cachedIds) sql.find(id).ifPresent(authoritative::add);
                } catch (SQLException | RuntimeException uncertain) {
                    // Outcome unknown. The leases stay held until SQL is re-read and the cache matches it.
                    final List<Property> reread = new ArrayList<>();
                    boolean readable;
                    try { for (UUID id : heldIds) sql.find(id).ifPresent(reread::add); readable = true; }
                    catch (SQLException | RuntimeException unreadable) { readable = false; }
                    final boolean confirmed = readable;
                    main.execute(() -> {
                        host.cacheSuspect(true);
                        if (!confirmed) {
                            host.log(Level.SEVERE, "Rent change step not confirmed and SQL could not be re-read; "
                                    + leases.heldCount() + " properties stay held and the re-read is retried.");
                            hold(leases, heldIds, 1);
                            done.accept(false, HELD);
                            return;
                        }
                        if (!settle(leases, heldIds, reread)) { done.accept(false, HELD); return; }
                        host.log(Level.SEVERE, "Rent change step not confirmed; matching and rent wait for the next cycle.");
                        done.accept(false, NOT_CONFIRMED);
                    });
                    return;
                }
                main.execute(() -> {
                    var installed = installHeld(host.cache(), result, authoritative, leases::holds);
                    var outcome = installed.outcome();
                    totals[0] += outcome.applied(); totals[1] += outcome.cancelled(); totals[2] += outcome.replaced();
                    if (!result.changes().isEmpty() || outcome.drift()) host.cacheChanged();
                    host.pendingRentChanges(result.stillPending() > 0);
                    if (!installed.heldMatch()) {
                        // Never release a held home whose cached record still differs from SQL: re-read and retry.
                        host.cacheChanged();
                        host.log(Level.SEVERE, "Rent change step could not install SQL's record for a held property; "
                                + leases.heldCount() + " properties stay held and SQL is re-read.");
                        hold(leases, heldIds, 1);
                        done.accept(false, HELD);
                        return;
                    }
                    host.cacheSuspect(outcome.drift());
                    release(leases);
                    if (outcome.drift()) {
                        host.finished();
                        host.log(Level.WARNING, "Rent change cache differs from SQL; matching and rent wait for reconciliation.");
                        done.accept(false, DRIFT);
                        return;
                    }
                    int deferred = result.deferred().size();
                    if (DueRentLeases.retry(deferred, attempt)) {
                        // admissionPending stays set: no other rent step or admission starts meanwhile.
                        host.schedule(DueRentLeases.RETRY_TICKS, () -> {
                            if (attempt(attempt + 1)) return;
                            host.finished();
                            host.log(Level.WARNING, "Rent change step could not try again; " + deferred + " due changes stay pending.");
                            done.accept(false, NOT_FINISHED);
                        });
                        return;
                    }
                    host.finished();
                    String summary = PropertyService.dueRentSummary(totals[0], totals[1], totals[2]);
                    if (deferred > 0) {
                        host.log(Level.WARNING, "Rent changes held by a payment in progress, applying at the next cycle: "
                                + result.deferred());
                        summary += " Held by a payment in progress, applying at the next cycle: " + deferred
                                + " (today's rent for these homes is held, and so is appreciation while the ledger rent hold is on).";
                    }
                    done.accept(true, summary);
                });
            });
            return true;
        } catch (RejectedExecutionException busy) { release(leases); return false; }
    }

    /**
     * Outcome unknown and SQL unreadable: keep every lease, alert staff, and re-read the held ids after a bounded
     * backoff. Main thread. Nothing here calls done; the attempt already reported the hold.
     */
    private void hold(DueRentLeases leases, List<UUID> heldIds, int failures) {
        host.cacheSuspect(true);
        host.held(leases.heldCount(), "Rent change step not confirmed and the database could not be re-read; "
                + leases.heldCount() + " homes are held (no payments) until it can be. Retrying automatically.");
        host.schedule(rereadDelay(failures), () -> reread(leases, heldIds, failures));
    }

    private void reread(DueRentLeases leases, List<UUID> heldIds, int failures) {
        try {
            worker.execute(() -> {
                final List<Property> fresh = new ArrayList<>();
                try { for (UUID id : heldIds) sql.find(id).ifPresent(fresh::add); }
                catch (SQLException | RuntimeException unreadable) {
                    main.execute(() -> {
                        host.log(Level.WARNING, "Rent change re-read " + failures + " failed; " + leases.heldCount()
                                + " properties stay held: " + unreadable.getMessage());
                        hold(leases, heldIds, failures + 1);
                    });
                    return;
                }
                main.execute(() -> {
                    if (!settle(leases, heldIds, fresh)) return;
                    host.log(Level.WARNING, "Rent change step re-read SQL after " + failures + " failed attempt"
                            + (failures == 1 ? "" : "s") + "; held properties released. Matching and rent wait for the next cycle.");
                });
            });
        } catch (RejectedExecutionException busy) { hold(leases, heldIds, failures + 1); }
    }

    /**
     * Installs the re-read SQL rows for every held entry, then releases and ends the step. False: some held entry
     * still differs from SQL, so nothing is released and the re-read is retried (hold).
     */
    private boolean settle(DueRentLeases leases, List<UUID> heldIds, List<Property> reread) {
        var installed = installHeld(host.cache(), new PropertyRepository.DueRentResult(List.of(), 0), reread, leases::holds);
        host.cacheChanged();
        if (!installed.heldMatch()) { hold(leases, heldIds, 1); return false; }
        release(leases);
        host.finished();
        return true;
    }

    private void release(DueRentLeases leases) {
        try { leases.releaseAll(); }
        catch (RuntimeException failure) {
            host.log(Level.SEVERE, "Rent change lease release failed; affected properties stay held: " + failure.getMessage());
        }
    }
}
