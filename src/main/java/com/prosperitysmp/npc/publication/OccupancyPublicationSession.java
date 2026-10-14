package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.PublicOccupancyRepository.Evidence;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * One fixed-scope private publication session, implementing the refresh pump's worker Reader.
 * Construct on the controller thread; read/close must run off it on a bounded worker. No scheduler,
 * thread creation, HTTP or source writes. Create a NEW session on every configuration generation.
 * cancel is immediate but not a disk revocation barrier; close the serving pump before cancellation.
 */
public final class OccupancyPublicationSession implements OccupancyRefreshPump.Reader, AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Path directory;
    private final List<Approval> scope;
    private final OccupancyRefreshPump.Reader source;
    private final Clock clock;
    private final LongSupplier monotonic;
    private final Duration maxAge, recoveryAge, retentionAge;
    private final long maxAgeNanos;
    private final UUID generation = UUID.randomUUID();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    @FunctionalInterface interface StoreFactory { AtomicOccupancyArtifacts open(Path directory) throws IOException; }
    private final StoreFactory storeFactory;
    @FunctionalInterface interface StoreCloser { void close(AtomicOccupancyArtifacts store) throws IOException; }
    private final StoreCloser storeCloser;
    private AtomicOccupancyArtifacts store;
    private boolean initialized, closed;
    private IOException cleanupFailure;

    public OccupancyPublicationSession(Path directory, List<Approval> scope, OccupancyRefreshPump.Reader source,
            Clock clock, LongSupplier monotonic, Duration maxAge, Duration recoveryAge, Duration retentionAge) {
        this(directory, scope, source, clock, monotonic, maxAge, recoveryAge, retentionAge, AtomicOccupancyArtifacts::new);
    }

    // Test-only factory for actual filesystem fault injection, never a production alternate backend.
    OccupancyPublicationSession(Path directory, List<Approval> scope, OccupancyRefreshPump.Reader source,
            Clock clock, LongSupplier monotonic, Duration maxAge, Duration recoveryAge, Duration retentionAge, StoreFactory storeFactory) {
        this(directory,scope,source,clock,monotonic,maxAge,recoveryAge,retentionAge,storeFactory,AtomicOccupancyArtifacts::close);
    }

    // Test-only close failure injection; the public path always closes the real acquired store.
    OccupancyPublicationSession(Path directory, List<Approval> scope, OccupancyRefreshPump.Reader source,
            Clock clock, LongSupplier monotonic, Duration maxAge, Duration recoveryAge, Duration retentionAge,
            StoreFactory storeFactory, StoreCloser storeCloser) {
        this.directory = Objects.requireNonNull(directory);
        this.storeFactory = Objects.requireNonNull(storeFactory);
        this.storeCloser = Objects.requireNonNull(storeCloser);
        if (scope == null || scope.isEmpty() || scope.size() > 512) throw new IllegalArgumentException("Invalid publication scope");
        this.scope = List.copyOf(scope); this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock); this.monotonic = Objects.requireNonNull(monotonic);
        for (Duration policy : Arrays.asList(maxAge, recoveryAge, retentionAge))
            if (policy == null || policy.isZero() || policy.isNegative()) throw new IllegalArgumentException("Invalid publication duration");
        this.maxAge = maxAge; this.recoveryAge = recoveryAge; this.retentionAge = retentionAge;
        try { maxAgeNanos = maxAge.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Publication max age overflow"); }
    }

    @Override public Evidence read(List<Approval> requested) throws Exception {
        worker();
        synchronized (this) {
            active();
            if (!scope.equals(requested)) throw new IOException("Publication session scope changed");
            try {
                if (store == null) store = storeFactory.open(directory);
                if (!initialized) { store.beginAuthorization(generation); initialized = true; }
                store.recoverTemporaryFiles(recoveryAge, clock.instant());
                var previous = store.readSelection(generation).orElseThrow(() -> new IOException("Missing publication selection"));
                if (!previous.entries().isEmpty()) store.prune(previous.protectedNames(generation), retentionAge, clock.instant());
                else store.pruneUnselected(retentionAge, clock.instant());
                active();
                Instant requestedAt = clock.instant(); long started = monotonic.getAsLong();
                Evidence evidence = source.read(scope);
                fresh(evidence, requestedAt, started); active();
                var snapshot = evidence.snapshot();
                if (snapshot.isEmpty()) return evidence;
                var receipt = store.write(snapshot);
                fresh(evidence, requestedAt, started); active();
                var next = previous.advance(generation, snapshot, receipt, maxAge, clock.instant());
                store.commitSelection(previous, next, generation);
                // A lost acknowledgment can leave committed metadata; next read reloads it before advancing.
                store.prune(next.protectedNames(generation), retentionAge, clock.instant());
                fresh(evidence, requestedAt, started); active();
                return evidence;
            } finally {
                if (cancelled.get()) closeLocked();
            }
        }
    }

    /** Call only after revoking the owner-thread serving view; never waits on worker I/O. */
    public void cancel() { cancelled.set(true); }

    @Override public void close() throws IOException {
        worker(); cancel();
        synchronized (this) { closeLocked(); }
    }
    private void closeLocked() throws IOException {
        closed = true;
        if(cleanupFailure!=null)throw cleanupFailure;
        if (store != null) {
            var acquired = store; store = null;
            try {storeCloser.close(acquired);}
            catch(IOException | RuntimeException failure) {
                cleanupFailure=new IOException("Publication cleanup failed; operator review required",failure);
                throw cleanupFailure;
            }
            catch(Error fatal) {
                cleanupFailure=new IOException("Publication cleanup failed; operator review required");
                throw fatal;
            }
        }
    }
    private void fresh(Evidence evidence, Instant requestedAt, long started) throws IOException {
        Instant now = clock.instant(); long elapsed = monotonic.getAsLong() - started;
        if (evidence == null || !evidence.capturedAt().equals(evidence.snapshot().capturedAt())
                || evidence.capturedAt().isBefore(requestedAt.truncatedTo(ChronoUnit.MILLIS))
                || evidence.capturedAt().isAfter(now) || now.isBefore(requestedAt)
                || elapsed < 0 || elapsed >= maxAgeNanos) throw new IOException("Publication evidence stale or invalid");
        try {
            if (Duration.between(evidence.capturedAt(), now).toNanos() >= maxAgeNanos)
                throw new IOException("Publication evidence expired");
        } catch (ArithmeticException overflow) { throw new IOException("Publication evidence age overflow"); }
    }
    private void active() throws IOException {
        if (closed || cancelled.get()) throw new IOException("Publication session closed");
    }
    private void worker() {
        if (Thread.currentThread() == owner) throw new IllegalStateException("Publication filesystem requires worker thread");
    }
}
