package com.prosperitysmp.npc.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class DatabaseWorkersLifecycleTest {
    @Test
    void aNullWorkerIsRefused() {
        assertThrows(NullPointerException.class, () -> new DatabaseWorkers().register(null));
    }

    @Test
    void aNullDeadlineIsRefused() {
        assertThrows(NullPointerException.class, () -> new DatabaseWorkers(null));
    }

    @Test
    void theRegistryIsOpenUntilShutdownIsRequested() throws Exception {
        DatabaseWorkers workers = new DatabaseWorkers();
        assertFalse(workers.sealed());
        workers.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(workers.sealed());
    }

    @Test
    void shuttingDownAnEmptyRegistryCompletesAtOnce() throws Exception {
        var drain = new DatabaseWorkers().shutdown().toCompletableFuture();
        assertTrue(drain.isDone());
        assertFalse(drain.isCompletedExceptionally());
    }

    @Test
    void everyCallToShutdownReturnsTheSameCachedStage() {
        DatabaseWorkers workers = new DatabaseWorkers();
        CompletionStage<Void> first = workers.shutdown();
        assertSame(first, workers.shutdown());
    }

    @Test
    void shutdownStopsEveryRegisteredWorkerAndWaitsForThemToTerminate() throws Exception {
        ExecutorService a = Executors.newSingleThreadExecutor();
        ExecutorService b = Executors.newFixedThreadPool(2);
        DatabaseWorkers workers = new DatabaseWorkers(Duration.ofSeconds(5));
        workers.register(a);
        workers.register(b);
        workers.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(a.isTerminated());
        assertTrue(b.isTerminated());
    }

    @Test
    void registeringTheSameWorkerTwiceCountsOnce() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        DatabaseWorkers workers = new DatabaseWorkers(Duration.ofSeconds(5));
        workers.register(worker);
        workers.register(worker);
        workers.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(worker.isTerminated());
    }

    @Test
    void aWorkerRegisteredAfterShutdownIsStoppedAtOnceAndRefused() throws Exception {
        DatabaseWorkers workers = new DatabaseWorkers();
        workers.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);
        ExecutorService late = Executors.newSingleThreadExecutor();
        var failure = assertThrows(IllegalStateException.class, () -> workers.register(late));
        assertEquals("Database worker registration unavailable", failure.getMessage());
        assertTrue(late.isShutdown());
        assertTrue(late.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void aWorkerThatNeverStopsFailsTheDrainWithATimeoutAndTheRegistryStaysSealed() throws Exception {
        ExecutorService stuck = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stuck.submit(() -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                } catch (InterruptedException ignored) {
                    // Deliberately keeps waiting so the executor cannot terminate.
                }
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            DatabaseWorkers workers = new DatabaseWorkers(Duration.ofMillis(50));
            workers.register(stuck);
            var failure = assertThrows(ExecutionException.class, () -> workers.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals("NPC worker termination unconfirmed", failure.getCause().getMessage());
            assertTrue(workers.sealed());
            release.countDown();
            assertTrue(stuck.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.shutdown().toCompletableFuture().isCompletedExceptionally(), "an expired drain stays failed");
        } finally {
            release.countDown();
            stuck.shutdownNow();
        }
    }

    @Test
    void aDeadlineOutsideTheDrainBoundsFailsTheShutdownWithoutNamingTheWorker() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        DatabaseWorkers workers = new DatabaseWorkers(Duration.ofSeconds(31));
        workers.register(worker);
        var failure = assertThrows(ExecutionException.class, () -> workers.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("Database worker shutdown failed", failure.getCause().getMessage());
        assertTrue(worker.isShutdown());
    }

    @Test
    void theDrainBoundsAreOneMillisecondToThirtySecondsInclusive() throws Exception {
        for (Duration ok : new Duration[]{Duration.ofMillis(1), Duration.ofSeconds(30)}) {
            ExecutorService done = Executors.newSingleThreadExecutor();
            done.shutdown();
            assertTrue(done.awaitTermination(5, TimeUnit.SECONDS));
            ExecutorDrain.afterShutdown(done, ok).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        ExecutorService done = Executors.newSingleThreadExecutor();
        done.shutdown();
        for (Duration bad : new Duration[]{Duration.ofNanos(999_999), Duration.ofSeconds(30).plusNanos(1), Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> ExecutorDrain.afterShutdown(done, bad), bad.toString());
        }
    }

    @Test
    void theDrainRefusesNullArguments() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThrows(NullPointerException.class, () -> ExecutorDrain.afterShutdown(null, Duration.ofSeconds(1)));
            assertThrows(NullPointerException.class, () -> ExecutorDrain.afterShutdown(executor, null));
        } finally {
            executor.shutdownNow();
        }
    }
}
