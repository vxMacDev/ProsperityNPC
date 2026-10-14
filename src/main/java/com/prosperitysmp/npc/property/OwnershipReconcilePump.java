package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.arm.OwnershipFence;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Main-thread state machine. The worker only publishes a value; it never touches the host.
 *
 * <p>Ownership fence (Host.fence() non-null, i.e. ARM coordination on): a payment, rent payout, admission or rent
 * step holding a home's lease authorizes its CREDIT against the FULL cached record, so the pump never replaces the
 * cached record of a held home. It takes the home's slot (acquireExclusive) on main for the synchronous part of a
 * dispatch and of a completion only, never across the SQL hop, so a daily batch acquiring every home never sees the
 * pump as busy. A home held by someone else is DEFERRED: no SQL write at dispatch, no cache replacement at completion.
 * Deferred homes are retried by the pump itself every DEFERRED_RETRY_TICKS until a pass reaches them unheld.
 * writing(id) names the home whose SQL write is in flight, so a single-home admission can wait for it. It stays true
 * after that write's completion is consumed without installing its result (the pass was cancelled, the record was
 * replaced, or the home was held), because SQL may then be ahead of the cache: such a home is retried like a
 * deferred one until a pass installs SQL's row.
 * Host.fence() null (no coordination, so no lease can be held): exactly the original behavior.
 */
public final class OwnershipReconcilePump {
    /** Wait before re-trying deferred homes (5 seconds). A retry that finds a home still held costs one fence check. */
    public static final long DEFERRED_RETRY_TICKS = 100L;
    /** Automatic retries in a row that still find one home held (one minute) before Host.stuck is told, once. */
    public static final int STUCK_RETRIES = 12;
    public enum Acceptance { ACCEPTED, BUSY, OVERFLOW, CLOSED }
    public enum Presence { PRESENT, MISSING, UNAVAILABLE }
    public record Observation(Presence presence, UUID owner) {}
    public record Write(Property authoritative, boolean successful) {}
    /** deferred: homes another lease held (a payment between DEBIT and CREDIT); left untouched and retried. */
    public record Result(int checked, int changed, int skipped, int missing, int failed, boolean cancelled, int deferred) {
        public Result(int checked, int changed, int skipped, int missing, int failed, boolean cancelled) {
            this(checked, changed, skipped, missing, failed, cancelled, 0);
        }
    }
    public interface Host {
        Property current(UUID id);
        Observation observe(Property property);
        void uncertain(UUID id);
        default void refreshing(UUID id) { uncertain(id); }
        void rebase(Property expected, Property authoritative, boolean verified);
        /** The live ownership fence, or null when ARM coordination is off (then no lease can be held). */
        default OwnershipFence fence() { return null; }
        /** Automatic retries found this home held STUCK_RETRIES times in a row. Called once until it is reached unheld. */
        default void stuck(UUID id, int retries) { }
    }
    @FunctionalInterface public interface Writer { Write reconcile(Property expected, UUID owner) throws Exception; }
    private record Completion(long generation, Property expected, Observation observation, Write write) {}
    private final Host host;
    private final Writer writer;
    private final Executor executor;
    private final Thread thread = Thread.currentThread();
    private volatile Completion completion;
    private boolean inFlight, closed;
    private long generation;
    private List<Property> pending = List.of();
    private int cursor, checked, changed, skipped, missing, failed, deferred;
    // Homes some lease held when a pass reached them; retried after DEFERRED_RETRY_TICKS. Main thread only.
    private final Set<UUID> deferredIds = new LinkedHashSet<>();
    // Main thread only. Consecutive automatic retries that deferred a home; negative once Host.stuck was told.
    private final Map<UUID, Integer> stuckRetries = new HashMap<>();
    private boolean automatic;
    // Main thread only. The home whose SQL write is in flight, from submit until its completion is consumed.
    private UUID writingId;
    // Main thread only. Homes whose SQL may be ahead of the cache: a write was consumed without a rebase.
    private final Set<UUID> aheadIds = new LinkedHashSet<>();
    private long retryIn;
    private Consumer<Result> callback;

    public OwnershipReconcilePump(Host host, Writer writer, Executor executor) {
        this.host = Objects.requireNonNull(host); this.writer = Objects.requireNonNull(writer);
        this.executor = Objects.requireNonNull(executor);
    }
    private void main() { if (Thread.currentThread() != thread) throw new IllegalStateException("Main thread required"); }
    public Acceptance request(Collection<Property> properties, Consumer<Result> done) {
        main(); Objects.requireNonNull(done);
        if (closed) return Acceptance.CLOSED;
        if (callback != null || inFlight) return Acceptance.BUSY;
        if (properties.size() > 512) return Acceptance.OVERFLOW;
        begin(properties, done);
        return Acceptance.ACCEPTED;
    }
    private void begin(Collection<Property> properties, Consumer<Result> done) {
        pending = List.copyOf(properties); cursor = checked = changed = skipped = missing = failed = deferred = 0;
        generation++; callback = done; automatic = false;
        // A deferred home leaves deferredIds only when this pass reaches it, so a cancelled pass forgets none.
        pending.forEach(p -> host.refreshing(p.propertyId()));
    }
    /** Homes waiting for a retry because a lease held them. Main thread. */
    public Set<UUID> deferredIds() { main(); return Set.copyOf(deferredIds); }
    /**
     * True while this home's SQL write is in flight (from submit until its completion is consumed on main), and
     * after a write was consumed without installing its result, until a later pass installs SQL's row.
     */
    public boolean writing(UUID id) {
        main();
        if (id == null) return false;
        if (aheadIds.contains(id)) return true;
        // After close() tick no longer runs: a published completion means the worker is done with the write.
        return id.equals(writingId) && !(closed && completion != null);
    }
    /**
     * Homes whose SQL rows a step changed but could not install in the cache (its re-read failed): single-home
     * admissions wait for them (writing) and each is retried like a deferred home until a pass installs SQL's row.
     */
    public void markAhead(Collection<UUID> ids) {
        main(); if (closed) return;
        for (UUID id : ids) if (id != null && host.current(id) != null) { aheadIds.add(id); queueRetry(id); }
    }
    /** At most two ARM captures per tick: one completion validation and one new observation. */
    public void tick() {
        main(); if (closed) return;
        Completion c = completion;
        if (c != null) {
            completion = null; inFlight = false; writingId = null;
            boolean rebased = false;
            if (callback != null && c.generation == generation) {
                if (host.current(c.expected.propertyId()) != c.expected) { skipped++; }
                else {
                    // The slot is held only for this synchronous block: the rebase cannot land under a payment.
                    OwnershipFence.Lease slot = NONE;
                    boolean free = true;
                    try { slot = slot(c.expected); }
                    catch (OwnershipFence.Busy held) { free = false; defer(c.expected); }
                    catch (RuntimeException coordinator) { free = false; host.uncertain(c.expected.propertyId()); failed++; }
                    if (free) {
                        try {
                            Observation now = capture(c.expected);
                            Write w = c.write;
                            boolean verified = w != null && w.successful && w.authoritative != null
                                    && now.presence == Presence.PRESENT
                                    && Objects.equals(now.owner, c.observation.owner)
                                    && Objects.equals(now.owner, w.authoritative.ownerUuid());
                            if (w != null && w.authoritative != null) { host.rebase(c.expected, w.authoritative, verified); rebased = true; }
                            if (!verified) { host.uncertain(c.expected.propertyId()); failed++; }
                            else { checked++; if (!Objects.equals(c.expected.ownerUuid(), w.authoritative.ownerUuid())) changed++; }
                        } finally { release(slot, c.expected); }
                    }
                }
            }
            settle(c, rebased);
        }
        if (callback == null && !inFlight && !deferredIds.isEmpty() && --retryIn <= 0) retryDeferred();
        if (callback == null || inFlight) return;
        if (cursor == pending.size()) { finish(false); return; }
        Property p = pending.get(cursor++);
        deferredIds.remove(p.propertyId()); // Reached: dispatched, skipped, failed or deferred again below.
        if (host.current(p.propertyId()) != p) { skipped++; stillAhead(p.propertyId()); return; }
        // Held by a payment (or any lease): no observation and no SQL write; retried after it is released.
        try { release(slot(p), p); }
        catch (OwnershipFence.Busy held) { defer(p); return; }
        catch (RuntimeException coordinator) { host.uncertain(p.propertyId()); failed++; stillAhead(p.propertyId()); return; }
        Observation o = capture(p);
        if (o.presence != Presence.PRESENT) {
            host.uncertain(p.propertyId());
            if (o.presence == Presence.MISSING) missing++; else skipped++;
            stillAhead(p.propertyId());
            return;
        }
        if (!Objects.equals(o.owner, p.ownerUuid())) host.uncertain(p.propertyId());
        long g = generation; inFlight = true; writingId = p.propertyId();
        try {
            executor.execute(() -> {
                Write w = null;
                try { w = writer.reconcile(p, o.owner); } catch (Exception ignored) { /* uncertain receipt */ }
                finally { completion = new Completion(g, p, o, w); }
            });
        } catch (RuntimeException rejected) {
            inFlight = false; writingId = null; host.uncertain(p.propertyId()); failed++; stillAhead(p.propertyId());
        }
    }
    /** No fence: no slot to take (NONE). Otherwise the home's exclusive slot; throws Busy when another lease holds it. */
    private OwnershipFence.Lease slot(Property p) {
        OwnershipFence fence = host.fence();
        return fence == null ? NONE : fence.acquireExclusive(p.worldUuid(), p.regionId());
    }
    private static final OwnershipFence.Lease NONE = null;
    private void release(OwnershipFence.Lease slot, Property p) {
        if (slot == NONE) return;
        try { slot.releaseReconciled(); }
        catch (RuntimeException mismatch) { host.uncertain(p.propertyId()); }
    }
    /**
     * A consumed completion. Rebased: SQL's row is in the cache. Otherwise (cancelled pass, replaced record, held
     * home, failed write) a home still cached is retried, and if the write may have changed SQL it stays writing.
     */
    private void settle(Completion c, boolean rebased) {
        UUID id = c.expected.propertyId();
        if (rebased || host.current(id) == null) { aheadIds.remove(id); return; }
        Write w = c.write;
        if (w == null || w.authoritative == null || !w.authoritative.equals(c.expected)) aheadIds.add(id);
        queueRetry(id);
    }
    private void queueRetry(UUID id) {
        if (deferredIds.add(id) && deferredIds.size() == 1) retryIn = DEFERRED_RETRY_TICKS;
    }
    /**
     * A home ahead of the cache that this pass reached but did not dispatch (record replaced, coordinator failure,
     * ARM unavailable, region missing or the worker refused the write) stays writing and is queued again, so it is
     * never left refused without a retry; repeated automatic retries count toward the stuck warning like deferrals do.
     */
    private void stillAhead(UUID id) {
        if (host.current(id) == null) { aheadIds.remove(id); return; }
        if (!aheadIds.contains(id)) return;
        queueRetry(id);
        streak(id);
    }
    private void defer(Property p) {
        deferred++;
        queueRetry(p.propertyId());
        host.refreshing(p.propertyId());
        streak(p.propertyId());
    }
    private void streak(UUID id) {
        if (!automatic) return;
        int streak = stuckRetries.merge(id, 1, (a, b) -> a < 0 ? a : a + b);
        if (streak == STUCK_RETRIES) { stuckRetries.put(id, -1); host.stuck(id, STUCK_RETRIES); }
    }
    /** A pass over the deferred homes still cached (their current records), with no external callback. */
    private void retryDeferred() {
        // A home no longer cached can never be reached, so it stops waiting.
        deferredIds.removeIf(id -> host.current(id) == null);
        aheadIds.removeIf(id -> host.current(id) == null);
        stuckRetries.keySet().retainAll(deferredIds);
        List<Property> retry = new ArrayList<>();
        for (UUID id : deferredIds) retry.add(host.current(id));
        if (retry.isEmpty()) return;
        begin(retry, r -> { });
        automatic = true;
    }
    private Observation capture(Property p) {
        try { return Objects.requireNonNull(host.observe(p)); }
        catch (RuntimeException e) { return new Observation(Presence.UNAVAILABLE, null); }
    }
    private void finish(boolean cancelled) {
        Consumer<Result> done = callback;
        Result result = new Result(checked, changed, skipped, missing, failed, cancelled, deferred);
        // A home this pass reached and did not defer starts its retry streak (and stuck warning) over.
        for (Property p : pending) if (!deferredIds.contains(p.propertyId())) stuckRetries.remove(p.propertyId());
        callback = null; pending = List.of();
        if (!deferredIds.isEmpty()) retryIn = DEFERRED_RETRY_TICKS;
        if (done != null) done.accept(result);
    }
    public void invalidate() {
        main(); generation++;
        pending.forEach(p -> host.refreshing(p.propertyId()));
        finish(true); // The existing worker must drain before another request is accepted.
    }
    public void close() { main(); closed = true; invalidate(); }
}
