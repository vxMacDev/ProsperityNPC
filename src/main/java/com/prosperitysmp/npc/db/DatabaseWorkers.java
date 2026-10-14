package com.prosperitysmp.npc.db;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Plugin-lifetime ownership, including workers from failed or retired controllers. */
public final class DatabaseWorkers {
    private final Set<ExecutorService> workers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Duration deadline;
    private CompletionStage<Void> drain;

    public DatabaseWorkers() {
        this(Duration.ofSeconds(15));
    }

    DatabaseWorkers(Duration deadline) {
        this.deadline = Objects.requireNonNull(deadline);
    }

    public synchronized boolean sealed() {
        return drain != null;
    }

    public synchronized void register(ExecutorService worker) {
        Objects.requireNonNull(worker);
        if (drain != null || (!workers.contains(worker) && workers.size() >= 32)) {
            worker.shutdownNow();
            throw new IllegalStateException("Database worker registration unavailable");
        }
        workers.add(worker);
    }

    /** Cached result: an expired observation can never be retried into a successful release. */
    public synchronized CompletionStage<Void> shutdown() {
        if (drain != null) return drain;
        var result = new CompletableFuture<Void>();
        drain = result.minimalCompletionStage();
        var stages = new ArrayList<CompletableFuture<?>>();
        for (var worker : workers) {
            try {
                worker.shutdownNow();
                stages.add(ExecutorDrain.afterShutdown(worker, deadline).toCompletableFuture());
            } catch (RuntimeException | LinkageError failure) {
                stages.add(CompletableFuture.failedFuture(new IllegalStateException("Database worker shutdown failed")));
            }
        }
        CompletableFuture.allOf(stages.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure == null) result.complete(null);
            else result.completeExceptionally(failure);
        });
        return drain;
    }
}
