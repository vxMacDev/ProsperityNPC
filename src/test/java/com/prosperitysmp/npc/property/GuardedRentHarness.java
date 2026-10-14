package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.arm.OwnershipFence;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/**
 * Test host for the production GuardedRentStep: PropertyService's state as plain fields, main and worker as one
 * FIFO queue drained in order, and the real PropertyRepository behind a Sql that can simulate an outage or an
 * acknowledgement lost after commit. Nothing here re-implements the step's decisions.
 */
public final class GuardedRentHarness implements GuardedRentStep.Host {
    public final Map<UUID, Property> cache;
    public boolean suspect, pending, finished = true, rejectWorker;
    public int cacheChanges;
    public final List<String> alerts = new ArrayList<>(), logs = new ArrayList<>(), trace = new ArrayList<>();
    public final List<Map.Entry<Boolean, String>> done = new ArrayList<>();
    public Runnable scheduled;
    public long scheduledTicks;
    /** Runs after every queued task (main or worker), for invariants such as "no lease is held". */
    public Runnable probe = () -> { };
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    public final Executor main = r -> queue.add(() -> { trace.add("main"); r.run(); });
    public final Executor worker = r -> {
        if (rejectWorker) throw new RejectedExecutionException("busy");
        queue.add(() -> { trace.add("worker"); r.run(); });
    };

    public GuardedRentHarness() { this(new ConcurrentHashMap<>()); }
    /** Shares the caller's cache map, for example the one a payment's CREDIT authorization reads. */
    public GuardedRentHarness(Map<UUID, Property> cache) { this.cache = cache; }

    @Override public Map<UUID, Property> cache() { return cache; }
    @Override public boolean cacheSuspect() { return suspect; }
    @Override public void cacheSuspect(boolean s) { suspect = s; }
    @Override public void pendingRentChanges(boolean p) { pending = p; }
    @Override public void cacheChanged() { cacheChanges++; }
    @Override public void finished() { finished = true; }
    @Override public void schedule(long ticks, Runnable next) {
        if (scheduled != null) throw new AssertionError("one retry slot only");
        scheduledTicks = ticks; scheduled = next;
    }
    @Override public void held(int properties, String text) { alerts.add(properties + ":" + text); }
    @Override public void log(Level level, String message) { logs.add(level + ":" + message); }

    /** As PropertyService: admissionPending is set before start and cleared on a refused start. */
    public boolean start(GuardedRentStep.Sql sql, OwnershipFence fence, LocalDate day) {
        finished = false;
        boolean started = GuardedRentStep.start(this, sql, fence, worker, main, day,
                (ok, message) -> done.add(Map.entry(ok, message)));
        if (!started) finished = true;
        return started;
    }

    public void drain() {
        while (!queue.isEmpty()) { queue.remove().run(); probe.run(); }
    }

    /** The ticker firing the pending retry or re-read, then everything it queued. */
    public void fireScheduled() {
        Runnable next = scheduled;
        if (next == null) throw new AssertionError("nothing scheduled");
        scheduled = null;
        next.run();
        probe.run();
        drain();
    }

    /** The real repository, with switchable failure. */
    public static final class RepoSql implements GuardedRentStep.Sql {
        private final PropertyRepository repo;
        public boolean outage, loseApplyAcknowledgement;
        public int statusCalls, applyCalls, findCalls;
        public final List<PropertyRepository.DueRentResult> applied = new ArrayList<>();
        /** Runs on worker at the start of every apply, before anything else (for example to start an outage). */
        public Runnable onApply = () -> { };

        public RepoSql(PropertyRepository repo) { this.repo = repo; }

        @Override public PropertyRepository.DueRentStatus status(LocalDate today) throws SQLException {
            statusCalls++;
            if (outage) throw new SQLException("database down");
            return repo.dueRentStatus(today);
        }
        @Override public PropertyRepository.DueRentResult apply(LocalDate today, Set<UUID> excluded) throws SQLException {
            applyCalls++;
            onApply.run();
            if (outage && !loseApplyAcknowledgement) throw new SQLException("database down");
            var result = repo.applyDueRentChanges(today, PropertyRepository.MAX_DUE_RENT_CHANGES, excluded);
            applied.add(result);
            // Committed, then the connection dropped before the acknowledgement arrived.
            if (loseApplyAcknowledgement) throw new SQLException("connection lost after commit");
            return result;
        }
        @Override public Optional<Property> find(UUID propertyId) throws SQLException {
            findCalls++;
            if (outage) throw new SQLException("database down");
            return repo.findById(propertyId);
        }
    }
}
