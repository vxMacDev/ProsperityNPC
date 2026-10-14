package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.PublicOccupancyRepository.Evidence;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Owner-thread controller; executor must be bounded and run the SQL reader off-thread. No scheduler or I/O. */
public final class OccupancyRefreshPump implements AutoCloseable {
    public enum Acceptance { ACCEPTED, BUSY, DISABLED, CLOSED, REJECTED }
    public enum Disposition { DISABLED, INVALIDATED, READING, READY, FAILED, EMPTY_HELD,
        EXPIRED, FUTURE, OLDER, CLOCK_ROLLBACK, CLOSED }
    @FunctionalInterface public interface Reader { Evidence read(List<Approval> approvals) throws Exception; }
    private record Completion(Object generation, Evidence evidence, long started, Instant requestedAt) { }
    private record Time(Instant wall, long mono) { }
    private final Thread owner = Thread.currentThread();
    private final Executor executor;
    private final Reader reader;
    private final Clock clock;
    private final LongSupplier monotonic;
    private final long maxAgeNanos;
    private final Object completionLock = new Object();
    private Completion completion;
    private List<Approval> approvals = List.of();
    private Object generation = new Object();
    private Evidence current;
    private Instant latestCapture, lastWall;
    private long acceptedMono, initialAge, lastMono;
    private boolean sampled, inFlight, closed;
    private Disposition disposition = Disposition.DISABLED;

    /** maxAge is explicit policy. The monotonic supplier uses nanoseconds, like System.nanoTime. */
    public OccupancyRefreshPump(Executor executor, Reader reader, Clock clock,
                                LongSupplier monotonic, Duration maxAge) {
        this.executor = Objects.requireNonNull(executor); this.reader = Objects.requireNonNull(reader);
        this.clock = Objects.requireNonNull(clock); this.monotonic = Objects.requireNonNull(monotonic);
        Objects.requireNonNull(maxAge);
        try { maxAgeNanos = maxAge.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Invalid occupancy max age"); }
        if (maxAgeNanos <= 0) throw new IllegalArgumentException("Invalid occupancy max age");
    }

    /** Even rejected configuration clears the old privacy scope; in-flight work must still drain. */
    public synchronized void configure(List<Approval> replacement) {
        main();
        if (closed) throw new IllegalStateException("Occupancy pump closed");
        generation = new Object(); current = null; latestCapture = null; approvals = List.of();
        disposition = Disposition.DISABLED;
        approvals = checkedScope(replacement);
        disposition = approvals.isEmpty() ? Disposition.DISABLED : Disposition.INVALIDATED;
    }

    static List<Approval> checkedScope(List<Approval> replacement) {
        if (replacement == null || replacement.size() > 512) throw new IllegalArgumentException("Invalid occupancy scope");
        var copy = List.copyOf(replacement);
        var ids = new HashSet<UUID>();
        var regions = new HashMap<UUID, HashSet<String>>();
        var aliases = new HashMap<UUID, String>();
        var worlds = new HashMap<String, UUID>();
        for (Approval a : copy) {
            String alias = aliases.putIfAbsent(a.worldId(), a.publicWorldKey());
            UUID world = worlds.putIfAbsent(a.publicWorldKey(), a.worldId());
            if (!ids.add(a.propertyId()) || !regions.computeIfAbsent(a.worldId(), ignored -> new HashSet<>()).add(a.regionId())
                    || (alias != null && !alias.equals(a.publicWorldKey())) || (world != null && !world.equals(a.worldId())))
                throw new IllegalArgumentException("Invalid occupancy scope");
        }
        return copy;
    }

    public synchronized Acceptance request() {
        main();
        if (closed) return Acceptance.CLOSED;
        Time now = time(); expire(now);
        if (inFlight) return Acceptance.BUSY;
        if (approvals.isEmpty()) return Acceptance.DISABLED;
        Object token = generation; List<Approval> scope = approvals;
        inFlight = true; disposition = Disposition.READING;
        try {
            executor.execute(() -> {
                Evidence evidence = null;
                try {
                    if (Thread.currentThread() == owner) throw new IllegalStateException("Occupancy reader requires worker thread");
                    evidence = reader.read(scope);
                } catch (Exception ignored) { /* Failure is delivered as a bounded disposition, not private SQL text. */ }
                finally {
                    synchronized (completionLock) {
                        if (!closed) completion = new Completion(token, evidence, now.mono(),
                                now.wall().truncatedTo(ChronoUnit.MILLIS));
                    }
                }
            });
            return Acceptance.ACCEPTED;
        } catch (RuntimeException rejected) {
            inFlight = false; disposition = Disposition.FAILED;
            return Acceptance.REJECTED;
        }
    }

    public synchronized void tick() {
        main();
        Completion done;
        synchronized (completionLock) { done = completion; completion = null; }
        if (closed) return;
        Time now = time(); expire(now);
        if (done == null) return;
        inFlight = false;
        if (done.generation() != generation) return;
        Evidence evidence = done.evidence();
        if (evidence == null || !evidence.capturedAt().equals(evidence.snapshot().capturedAt())) {
            disposition = Disposition.FAILED; return;
        }
        PublicOccupancySnapshot candidate = evidence.snapshot();
        if (candidate.isEmpty()) { disposition = Disposition.EMPTY_HELD; return; }
        Instant captured = candidate.capturedAt();
        if (captured.isAfter(now.wall())) { disposition = Disposition.FUTURE; return; }
        if (latestCapture != null && captured.isBefore(latestCapture)) { disposition = Disposition.OLDER; return; }
        if (captured.isBefore(done.requestedAt())) { disposition = Disposition.EXPIRED; return; }
        long wallAge = age(captured, now.wall());
        long elapsed = now.mono() - done.started();
        if (elapsed < 0 || elapsed >= maxAgeNanos || wallAge >= maxAgeNanos) {
            disposition = Disposition.EXPIRED; return;
        }
        current = evidence; latestCapture = captured; acceptedMono = now.mono();
        initialAge = Math.max(wallAge, elapsed); disposition = Disposition.READY;
    }

    /**
     * Owner-thread public-schema view, valid only at this read. A caller retaining it must enforce
     * freshness and privacy-generation invalidation independently; this is not a revocable handle.
     * Private evidence IDs have no public getter.
     */
    public synchronized Optional<PublicOccupancySnapshot> current() {
        main();
        return readPublic();
    }

    /**
     * Thread-safe read of already accepted public data. No query, task submission or file access.
     * Shares the owner's expiry/clock/revocation state; a web reader cannot resurrect an old lease.
     * Valid at this read only: previously returned bytes cannot be recalled after revocation.
     */
    public synchronized Optional<PublicOccupancySnapshot> readPublic() {
        if (closed) return Optional.empty();
        expire(time());
        return current == null ? Optional.empty() : Optional.of(current.snapshot());
    }

    public synchronized Disposition disposition() { main(); if (!closed) expire(time()); return disposition; }
    public synchronized boolean busy() { main(); return inFlight; }

    @Override public synchronized void close() {
        main();
        synchronized (completionLock) { closed = true; completion = null; }
        generation = new Object(); current = null; approvals = List.of();
        inFlight = false; disposition = Disposition.CLOSED;
    }

    private Time time() {
        Instant wall = clock.instant(); long mono = monotonic.getAsLong();
        if (sampled && (wall.isBefore(lastWall) || mono - lastMono < 0)) {
            current = null; generation = new Object(); disposition = Disposition.CLOCK_ROLLBACK;
        }
        sampled = true; lastWall = wall; lastMono = mono;
        return new Time(wall, mono);
    }

    private void expire(Time now) {
        if (current == null) return;
        long elapsed = now.mono() - acceptedMono;
        if (current.capturedAt().isAfter(now.wall()) || age(current.capturedAt(), now.wall()) >= maxAgeNanos
                || elapsed < 0 || elapsed >= maxAgeNanos - initialAge) {
            current = null; disposition = Disposition.EXPIRED;
        }
    }

    private static long age(Instant from, Instant to) {
        try { return Duration.between(from, to).toNanos(); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private void main() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Occupancy owner thread required");
    }
}
