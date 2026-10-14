package com.prosperitysmp.npc.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed-clock refresh races against the pure Java cache; no server or database is started. */
class RenderSnapshotRefreshTest {
    @Test
    void obsoleteSuccessCannotResurrectChangedEntryAndRefreshesAtSameClock() throws Exception {
        assertObsoleteCompletionIsIgnored(false);
    }

    @Test
    void obsoleteFailureCannotClearRetainedEntryOrThrottleFreshRead() throws Exception {
        assertObsoleteCompletionIsIgnored(true);
    }

    private static void assertObsoleteCompletionIsIgnored(boolean failOldRead) throws Exception {
        try (var fixture = new ReadFixture<Map<String, String>>()) {
            var cache = fixture.cache;
            Map<String, String> original = Map.of("changed", "old-route", "unaffected", "keep-route");
            Map<String, String> retained = Map.of("unaffected", "keep-route");
            Map<String, String> published = Map.of("changed", "new-route", "unaffected", "keep-route");
            seed(fixture, original, 1000);
            var obsolete = failOldRead ? fixture.failingRead() : fixture.read(original);
            var fresh = fixture.read(published);
            cache.refresh(obsolete, 31_000);
            obsolete.awaitEntered();
            cache.invalidate(snapshot -> without(snapshot, "changed"));
            assertEquals(retained, cache.value(31_000));
            obsolete.release.countDown();

            // Poll only at the original clock: a stale completion must not install a new backoff.
            await(fresh::entered, () -> {
                cache.refresh(fresh, 31_000);
                assertEquals(retained, cache.value(31_000), "obsolete completion changed retained data");
                assertNull(cache.failure(), "obsolete failure must not become the current failure");
            });
            assertEquals(3, fixture.calls.get());
            assertEquals(1, fixture.maxActive.get());
            assertEquals(retained, cache.value(31_000));
            fresh.release.countDown();
            await(() -> published.equals(cache.value(31_000)), () -> {
                cache.refresh(fresh, 31_000);
                assertNull(cache.failure());
                assertNotEquals(original, cache.value(31_000));
            });
            assertEquals(1, fresh.calls.get());
            assertEquals("old-route", original.get("changed"), "retention must not mutate the source map");
        }
    }

    @Test
    void noArgumentInvalidationClearsCachedValueAndRefreshesAtSameClock() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            seed(fixture, "cached", 1000);
            cache.invalidate();
            assertNull(cache.value(1000));
            var fresh = fixture.read("fresh");
            cache.refresh(fresh, 1000);
            fresh.awaitEntered();
            fresh.release.countDown();
            await(() -> "fresh".equals(cache.value(1000)), () -> cache.refresh(fresh, 1000));
            assertEquals(2, fixture.calls.get());
        }
    }

    @Test
    void eachInvalidationDiscardsTheCurrentlyPendingGeneration() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            var first = fixture.read("first-generation");
            var second = fixture.read("second-generation");
            var third = fixture.read("third-generation");
            cache.refresh(first, 1000);
            first.awaitEntered();
            cache.invalidate();
            first.release.countDown();
            await(second::entered, () -> {
                cache.refresh(second, 1000);
                assertNull(cache.value(1000), "no-argument invalidation must advance the generation");
            });
            cache.invalidate(value -> value);
            second.release.countDown();
            await(third::entered, () -> {
                cache.refresh(third, 1000);
                assertNull(cache.value(1000), "every retention invalidation must advance the generation");
            });
            third.release.countDown();
            await(() -> "third-generation".equals(cache.value(1000)), () -> {
                cache.refresh(third, 1000);
                assertNotEquals("second-generation", cache.value(1000));
            });
            assertEquals(3, fixture.calls.get());
            assertEquals(1, fixture.maxActive.get());
        }
    }

    @Test
    void repeatedInvalidationsCoalesceWithoutOverlappingOrQueuingReads() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            var obsolete = fixture.read("obsolete");
            var supersededRequest = fixture.read("must-not-be-queued");
            var fresh = fixture.read("fresh");
            cache.refresh(obsolete, 1000);
            obsolete.awaitEntered();
            for (int i = 0; i < 100; i++) {
                cache.invalidate();
                cache.refresh(supersededRequest, 1000);
                assertNull(cache.value(1000));
                assertNull(cache.failure());
            }
            assertEquals(1, fixture.calls.get());
            assertEquals(1, fixture.active.get());
            assertFalse(supersededRequest.entered());
            obsolete.release.countDown();
            // A wrongly queued request blocks this fresh read, even with a single-thread executor.
            await(fresh::entered, () -> {
                cache.refresh(fresh, 1000);
                assertNull(cache.value(1000));
                assertEquals(0, supersededRequest.calls.get());
            });
            for (int i = 0; i < 100; i++) cache.refresh(supersededRequest, 1000);
            assertEquals(2, fixture.calls.get());
            assertEquals(1, fixture.active.get());
            fresh.release.countDown();
            await(() -> "fresh".equals(cache.value(1000)), () -> cache.refresh(supersededRequest, 1000));
            assertEquals(2, fixture.calls.get());
            // Starting another explicit generation also exposes requests queued behind the fresh read.
            cache.invalidate();
            var barrier = fixture.read("next-explicit-refresh");
            cache.refresh(barrier, 1000);
            barrier.awaitEntered();
            assertEquals(3, fixture.calls.get());
            assertEquals(1, fixture.maxActive.get(), "JDBC work must never overlap, including after invalidation");
            assertEquals(0, supersededRequest.calls.get());
        }
    }

    @Test
    void retentionRemovesOnlyChangedKeyWithoutExtendingOriginalReadStartExpiry() throws Exception {
        try (var fixture = new ReadFixture<Map<String, String>>()) {
            var cache = fixture.cache;
            Map<String, String> original = Map.of("changed", "old-route", "unaffected", "keep-route");
            Map<String, String> retained = Map.of("unaffected", "keep-route");
            var initial = fixture.read(original);
            cache.refresh(initial, 1000);
            initial.awaitEntered();
            initial.release.countDown();
            await(() -> original.equals(cache.value(5000)), () -> cache.refresh(initial, 5000));
            var pending = fixture.read(original);
            cache.refresh(pending, 61_000);
            pending.awaitEntered();
            var retainCalls = new AtomicInteger();
            cache.invalidate(snapshot -> {
                retainCalls.incrementAndGet();
                assertSame(original, snapshot);
                return without(snapshot, "changed");
            });
            assertEquals(1, retainCalls.get());
            assertEquals(retained, cache.value(61_000));
            assertSame(original.get("unaffected"), cache.value(61_000).get("unaffected"));
            assertEquals(retained, cache.value(120_999));
            assertNull(cache.value(121_000), "expiry stays at start + 120 seconds, not completion or invalidation");
            assertEquals(2, original.size());
        }
    }

    @Test
    void retentionIsNotInvokedForAnEmptyCacheOrPendingOnlyRead() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            var retainCalls = new AtomicInteger();
            UnaryOperator<String> retain = value -> {
                retainCalls.incrementAndGet();
                return "invented";
            };
            cache.invalidate(retain);
            assertEquals(0, retainCalls.get());
            assertNull(cache.value(1000));
            var pending = fixture.read("pending");
            cache.refresh(pending, 1000);
            pending.awaitEntered();
            cache.invalidate(retain);
            assertEquals(0, retainCalls.get());
            assertNull(cache.value(1000));
        }
    }

    @Test
    void retentionInvalidationClearsFailureAndBypassesExistingBackoff() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            var failed = fixture.failingRead();
            cache.refresh(failed, 1000);
            failed.awaitEntered();
            failed.release.countDown();
            await(() -> cache.failure() != null, () -> cache.refresh(failed, 1000));
            var retainCalls = new AtomicInteger();
            cache.invalidate(value -> {
                retainCalls.incrementAndGet();
                return value;
            });
            assertNull(cache.failure());
            assertNull(cache.value(1000));
            assertEquals(0, retainCalls.get());
            var fresh = fixture.read("fresh");
            cache.refresh(fresh, 1000);
            fresh.awaitEntered();
            fresh.release.countDown();
            await(() -> "fresh".equals(cache.value(1000)), () -> cache.refresh(fresh, 1000));
            assertNull(cache.failure());
            assertEquals(2, fixture.calls.get());
        }
    }

    @Test
    void currentGenerationFailureClearsRetainedDataAndThrottlesForThirtySeconds() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            seed(fixture, "retained", 1000);
            cache.invalidate(value -> value);
            var failed = fixture.failingRead();
            cache.refresh(failed, 1000);
            failed.awaitEntered();
            assertEquals("retained", cache.value(1000));
            failed.release.countDown();
            await(() -> cache.failure() != null, () -> cache.refresh(failed, 2000));
            assertNull(cache.value(2000));
            String failure = cache.failure();
            var premature = fixture.read("must-not-retry-early");
            for (long now : new long[]{2000, 2001, 30_999, 31_000, 31_999}) {
                for (int i = 0; i < 100; i++) cache.refresh(premature, now);
                assertEquals(failure, cache.failure());
                assertNull(cache.value(now));
            }
            assertEquals(2, fixture.calls.get());
            var recovery = fixture.read("recovered");
            cache.refresh(recovery, 32_000);
            recovery.awaitEntered();
            assertEquals(0, premature.calls.get(), "backoff is measured from observed failure, not read start");
            recovery.release.countDown();
            await(() -> "recovered".equals(cache.value(32_000)), () -> cache.refresh(recovery, 32_000));
            assertNull(cache.failure());
            assertEquals(3, fixture.calls.get());
        }
    }

    @Test
    void closedCachedSnapshotCannotBeRevivedByEitherInvalidation() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            seed(fixture, "cached", 1000);
            cache.close();
            assertClosedAndCannotRestart(fixture);
        }
    }

    @Test
    void successfulPendingReadAfterCloseCannotPublishOrRestart() throws Exception {
        assertPendingCompletionAfterCloseIsIgnored(false);
    }

    @Test
    void failedPendingReadAfterCloseCannotPublishFailureOrRestart() throws Exception {
        assertPendingCompletionAfterCloseIsIgnored(true);
    }

    private static void assertPendingCompletionAfterCloseIsIgnored(boolean failRead) throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var pending = failRead ? fixture.failingRead() : fixture.read("late-result");
            fixture.cache.refresh(pending, 1000);
            pending.awaitEntered();
            // This deliberate close-while-blocked models a JDBC driver that ignores interruption.
            fixture.cache.close();
            pending.release.countDown();
            pending.awaitStopped();
            assertClosedAndCannotRestart(fixture);
        }
    }

    private static void assertClosedAndCannotRestart(ReadFixture<String> fixture) {
        var cache = fixture.cache;
        int callsBefore = fixture.calls.get();
        var forbidden = fixture.read("revived");
        for (long now : new long[]{1000, 31_000, 200_000}) {
            cache.refresh(forbidden, now);
            cache.invalidate(value -> "revived");
            cache.refresh(forbidden, now);
            assertNull(cache.value(now));
            assertNull(cache.failure());
            cache.invalidate();
            cache.refresh(forbidden, now);
            assertNull(cache.value(now));
            assertNull(cache.failure());
        }
        assertEquals(callsBefore, fixture.calls.get());
        assertEquals(0, forbidden.calls.get());
    }

    private static Map<String, String> without(Map<String, String> snapshot, String key) {
        var retained = new HashMap<>(snapshot);
        retained.remove(key);
        return Map.copyOf(retained);
    }

    private static <T> void seed(ReadFixture<T> fixture, T value, long now) throws Exception {
        var initial = fixture.read(value);
        fixture.cache.refresh(initial, now);
        initial.awaitEntered();
        initial.release.countDown();
        await(() -> value.equals(fixture.cache.value(now)), () -> fixture.cache.refresh(initial, now));
    }

    private static void await(BooleanSupplier condition, Runnable poll) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            poll.run();
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "read did not complete at the supplied clock within test deadline");
    }

    private static final class ReadFixture<T> implements AutoCloseable {
        final RenderManager.ReadCache<T> cache = new RenderManager.ReadCache<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<GatedRead<T>> reads = new ArrayList<>();

        GatedRead<T> read(T value) {
            return add(value, null);
        }

        GatedRead<T> failingRead() {
            return add(null, new IllegalStateException("fixture read failure"));
        }

        private GatedRead<T> add(T value, RuntimeException failure) {
            var read = new GatedRead<>(this, value, failure);
            reads.add(read);
            return read;
        }

        @Override
        public void close() {
            // Release even never-started candidates before shutdown, including when an assertion fails.
            for (var read : reads) read.release.countDown();
            cache.close();
            try {
                for (var read : reads) {
                    if (read.entered()) read.awaitStopped();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while cleaning up cache workers", e);
            }
        }
    }

    private static final class GatedRead<T> implements Callable<T> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        private volatile Thread worker;
        private final ReadFixture<T> fixture;
        private final T value;
        private final RuntimeException failure;

        GatedRead(ReadFixture<T> fixture, T value, RuntimeException failure) {
            this.fixture = fixture;
            this.value = value;
            this.failure = failure;
        }

        boolean entered() {
            return started.getCount() == 0;
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(started.await(3, TimeUnit.SECONDS), "read did not start at the supplied clock");
        }

        void awaitStopped() throws InterruptedException {
            assertTrue(finished.await(3, TimeUnit.SECONDS), "closed worker did not finish its read");
            // Callable completion alone precedes Future completion; thread termination proves both.
            worker.join(3000);
            assertFalse(worker.isAlive(), "cache worker leaked after close");
        }

        @Override
        public T call() {
            worker = Thread.currentThread();
            calls.incrementAndGet();
            fixture.calls.incrementAndGet();
            fixture.maxActive.accumulateAndGet(fixture.active.incrementAndGet(), Math::max);
            started.countDown();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (failure != null) throw failure;
                return value;
            } finally {
                fixture.active.decrementAndGet();
                finished.countDown();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
