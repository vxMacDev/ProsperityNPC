package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository.Cursor;
import com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository.Page;
import com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One off-owner recovery step. No scheduler, publication, or default/no-op journal. */
public final class ChronicleRecoveryWorker {
    @FunctionalInterface public interface Discovery {
        Page page(Approval scope, UUID generation, Cursor cursor, int limit) throws SQLException;
    }
    @FunctionalInterface public interface Stager {
        Held stage(UUID requestId, Approval scope, UUID generation) throws SQLException;
    }
    /** Must atomically persist ALL attempts plus cursor using expected revision/generation CAS. */
    public interface Journal {
        Checkpoint load(Approval scope, UUID generation) throws SQLException;
        void commit(Checkpoint expected, Page page, List<Attempt> attempts) throws SQLException;
    }
    public record Checkpoint(Approval scope, UUID generation, long revision, Cursor cursor) {
        public Checkpoint {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(generation);
            if (revision < 0 || revision == Long.MAX_VALUE || cursor != null
                    && (!scope.equals(cursor.scope()) || !generation.equals(cursor.generation())))
                throw new IllegalArgumentException("Invalid Chronicle checkpoint");
        }
    }
    public enum Outcome { STAGED, UNRESOLVED }
    /** UNRESOLVED is an observed staging failure, not a diagnosis or proof of non-commit. */
    public record Attempt(UUID requestId, Outcome outcome, Held held) {
        public Attempt {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.STAGED ? held == null : held != null) throw new IllegalArgumentException("Invalid Chronicle attempt");
            if (held != null && (!hash(held.eventKey()) || !hash(held.payloadSha256())))
                throw new IllegalArgumentException("Invalid Chronicle staging acknowledgment");
        }
    }
    /** Counts describe this invocation; only checkpointed=true acknowledges durable progress. */
    public record Result(boolean checkpointed, int staged, int unresolved) { }
    private final Discovery discovery;
    private final Stager stager;
    private final Journal journal;
    private final Thread ownerThread;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public ChronicleRecoveryWorker(Discovery discovery, Stager stager, Journal journal) {
        this.discovery = Objects.requireNonNull(discovery);
        this.stager = Objects.requireNonNull(stager);
        this.journal = Objects.requireNonNull(journal);
        ownerThread = Thread.currentThread();
    }

    /** Cooperative only: a call already in progress may commit; no source or journal write is undone. */
    public void cancel() { cancelled.set(true); }

    public Result step(Approval scope, UUID generation, int limit) throws SQLException {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(generation);
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Recovery step limit must be 1..16");
        if (Thread.currentThread() == ownerThread) throw new IllegalStateException("Chronicle recovery must run off owner");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Chronicle recovery already running");
        try {
            if (cancelled.get()) return new Result(false, 0, 0);
            Checkpoint checkpoint = Objects.requireNonNull(journal.load(scope, generation));
            if (!scope.equals(checkpoint.scope()) || !generation.equals(checkpoint.generation()))
                throw new SQLException("Chronicle checkpoint scope changed");
            if (cancelled.get()) return new Result(false, 0, 0);
            Page page = Objects.requireNonNull(discovery.page(scope, generation, checkpoint.cursor(), limit));
            validatePage(checkpoint, page, limit);
            var attempts = new ArrayList<Attempt>();
            int staged = 0, unresolved = 0;
            for (UUID requestId : page.requestIds()) {
                if (cancelled.get()) return new Result(false, staged, unresolved);
                try {
                    attempts.add(new Attempt(requestId, Outcome.STAGED, stager.stage(requestId, scope, generation)));
                    staged++;
                } catch (SQLException failure) {
                    // Includes lost replies: do not assert no commit or log raw SQL/private exception text.
                    attempts.add(new Attempt(requestId, Outcome.UNRESOLVED, null));
                    unresolved++;
                }
            }
            if (cancelled.get()) return new Result(false, staged, unresolved);
            journal.commit(checkpoint, page, List.copyOf(attempts));
            return new Result(true, staged, unresolved);
        } finally { running.set(false); }
    }

    private static void validatePage(Checkpoint checkpoint, Page page, int limit) throws SQLException {
        if (page.requestIds().size() > limit) throw new SQLException("Invalid Chronicle recovery page");
        Cursor previous = checkpoint.cursor(), next = page.continuation();
        UUID prior = previous == null ? null : previous.after();
        for (UUID id : page.requestIds()) {
            if (prior != null && compare(prior, id) >= 0 || previous != null && compare(id, previous.through()) > 0)
                throw new SQLException("Invalid Chronicle recovery page order");
            prior = id;
        }
        if (next != null && (!checkpoint.scope().equals(next.scope()) || !checkpoint.generation().equals(next.generation())
                || page.requestIds().size() != limit || !next.after().equals(prior)
                || previous != null && !previous.through().equals(next.through())))
            throw new SQLException("Invalid Chronicle recovery continuation");
    }
    private static int compare(UUID left, UUID right) {
        return Arrays.compareUnsigned(Uuids.toBytes(left), Uuids.toBytes(right));
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
