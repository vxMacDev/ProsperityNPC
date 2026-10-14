package com.prosperitysmp.npc.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed-clock cold-read admission checks; no server, database, or timing sleeps. */
class RenderColdReadAdmissionTest {
    @Test
    void expeditePreservesUnrelatedCachedValueAndOriginalReadStartExpiry() throws Exception {
        try (var fixture = new ReadFixture<Map<String, String>>()) {
            var cache = fixture.cache;
            Map<String, String> original = Map.of("unaffected", "keep-route");
            seed(fixture, original, 1000, 5000);
            for (long now : new long[]{60_000, 100_000, 120_999}) {
                for (int i = 0; i < 100; i++) cache.expedite();
                assertSame(original, cache.value(now), "cold demand must not evict another district");
                assertNull(cache.failure());
            }
            assertNull(cache.value(121_000), "expiry is read start + 120s, not completion or demand time");
            assertEquals(1, fixture.calls.get(), "expedite only marks demand; refresh admits the read");
        }
    }

    @Test
    void demandAcceptedBeforeReadIsSatisfiedWithoutPerpetualRetrigger() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            for (int i = 0; i < 100; i++) cache.expedite();
            var initial = fixture.read("first-request");
            var forbidden = fixture.read("extra-request");
            cache.refresh(initial, 1000);
            initial.awaitEntered();
            initial.release.countDown();
            await(() -> "first-request".equals(cache.value(5000)),
                    () -> cache.refresh(forbidden, 5000));
            assertRegularRefreshOnly(fixture, forbidden, "first-request", 5000, 1);
        }
    }

    @Test
    void manyDemandsBeforeGateCoalesceAndUseLatestReadInsteadOfQueue() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            assertEquals(2000L, RenderManager.ReadCache.EXPEDITED_MS);
            seed(fixture, "cached", 1000, 1000);
            cache.expedite();
            var first = fixture.read("first-cold-read");
            var forbidden = fixture.read("superseded-request");
            cache.refresh(first, 3000);
            first.awaitEntered();
            first.release.countDown();
            await(() -> "first-cold-read".equals(cache.value(3000)),
                    () -> cache.refresh(forbidden, 3000));

            long gate = 3000 + RenderManager.ReadCache.EXPEDITED_MS;
            for (long now : new long[]{3000, 4000, gate - 1}) {
                for (int i = 0; i < 100; i++) {
                    cache.expedite();
                    cache.refresh(forbidden, now);
                    assertEquals("first-cold-read", cache.value(now));
                }
            }
            assertEquals(2, fixture.calls.get());
            var latest = fixture.read("latest-request");
            cache.refresh(latest, gate);
            latest.awaitEntered();
            assertEquals(0, forbidden.calls.get(), "throttled callables must not be queued");
            latest.release.countDown();
            await(() -> "latest-request".equals(cache.value(gate)),
                    () -> cache.refresh(forbidden, gate));
            assertRegularRefreshOnly(fixture, forbidden, "latest-request", gate, 3);
        }
    }

    @Test
    void lateKeysDuringInflightReadSurviveCompletionUntilStartBasedGate() throws Exception {
        try (var fixture = new ReadFixture<Map<String, String>>()) {
            var cache = fixture.cache;
            Map<String, String> original = Map.of("unaffected", "keep-route");
            Map<String, String> early = Map.of("unaffected", "keep-route", "early", "early-route");
            Map<String, String> complete = Map.of(
                    "unaffected", "keep-route", "early", "early-route", "late", "late-route");
            seed(fixture, original, 1000, 1000);
            cache.expedite();
            var pending = fixture.read(early);
            var forbidden = fixture.read(Map.of("wrong", "queued-request"));
            cache.refresh(pending, 3000);
            pending.awaitEntered();
            for (int i = 0; i < 100; i++) {
                cache.expedite();
                cache.refresh(forbidden, 3500);
                assertSame(original, cache.value(3500));
            }
            assertEquals(1, fixture.active.get());
            pending.release.countDown();
            await(() -> early.equals(cache.value(4500)), () -> cache.refresh(forbidden, 4500));
            assertSame(early, cache.value(4500), "new demand must not invalidate the pending generation");
            long gate = 3000 + RenderManager.ReadCache.EXPEDITED_MS;
            for (int i = 0; i < 100; i++) cache.refresh(forbidden, gate - 1);
            assertEquals(2, fixture.calls.get());

            var followup = fixture.read(complete);
            cache.refresh(followup, gate);
            followup.awaitEntered();
            assertSame(early, cache.value(gate));
            assertEquals(0, forbidden.calls.get());
            followup.release.countDown();
            await(() -> complete.equals(cache.value(5500)), () -> cache.refresh(forbidden, 5500));
            assertRegularRefreshOnly(fixture, forbidden, complete, 5500, 3);
        }
    }

    @Test
    void slowReadStaysSingleFlightAndLateDemandStartsFollowupAtSameCompletionClock() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            cache.expedite();
            var pending = fixture.read("early-result");
            var forbidden = fixture.read("must-not-be-queued");
            cache.refresh(pending, 1000);
            pending.awaitEntered();
            for (int i = 0; i < 100; i++) {
                cache.expedite();
                cache.refresh(forbidden, 40_000);
                assertNull(cache.value(40_000));
            }
            assertEquals(1, fixture.calls.get());
            assertEquals(1, fixture.active.get(), "expired timers cannot overlap a pending read");
            pending.release.countDown();
            var followup = fixture.read("late-result");
            await(followup::entered, () -> cache.refresh(followup, 40_000));
            assertEquals("early-result", cache.value(40_000));
            assertEquals(0, forbidden.calls.get());
            followup.release.countDown();
            await(() -> "late-result".equals(cache.value(40_000)),
                    () -> cache.refresh(forbidden, 40_000));
            assertRegularRefreshOnly(fixture, forbidden, "late-result", 40_000, 2);
        }
    }

    @Test
    void expeditedReadFailureKeepsThirtySecondBackoffDespiteDemandSpam() throws Exception {
        assertFailureBackoff(true);
    }

    @Test
    void regularReadFailureKeepsThirtySecondBackoffDespiteDemandSpam() throws Exception {
        assertFailureBackoff(false);
    }

    private static void assertFailureBackoff(boolean expedited) throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            seed(fixture, "cached", 1000, 1000);
            long startedAt = expedited ? 3000 : 31_000;
            long observedAt = startedAt + 4000;
            long retryAt = observedAt + RenderManager.ReadCache.REFRESH_MS;
            if (expedited) cache.expedite();
            var failed = fixture.failingRead();
            var forbidden = fixture.read("premature-retry");
            cache.refresh(failed, startedAt);
            failed.awaitEntered();
            cache.expedite();
            cache.refresh(forbidden, startedAt + 1000);
            assertEquals("cached", cache.value(startedAt + 1000));
            failed.release.countDown();
            await(() -> cache.failure() != null, () -> cache.refresh(forbidden, observedAt));
            String failure = cache.failure();
            assertNull(cache.value(observedAt));
            for (long now : new long[]{observedAt, observedAt + RenderManager.ReadCache.EXPEDITED_MS,
                    startedAt + RenderManager.ReadCache.REFRESH_MS, retryAt - 1}) {
                for (int i = 0; i < 100; i++) {
                    cache.expedite();
                    cache.refresh(forbidden, now);
                    assertEquals(failure, cache.failure(), "demand must not erase a current failure");
                    assertNull(cache.value(now));
                }
            }
            assertEquals(2, fixture.calls.get());
            var recovery = fixture.read("recovered");
            cache.refresh(recovery, retryAt);
            recovery.awaitEntered();
            assertEquals(0, forbidden.calls.get(), "backoff starts at observed failure, not read start");
            recovery.release.countDown();
            await(() -> "recovered".equals(cache.value(retryAt)), () -> cache.refresh(forbidden, retryAt));
            assertNull(cache.failure());
            assertRegularRefreshOnly(fixture, forbidden, "recovered", retryAt, 3);
        }
    }

    @Test
    void closedEmptyAndSeededCachesRejectNewDemand() throws Exception {
        for (boolean seeded : new boolean[]{false, true}) {
            try (var fixture = new ReadFixture<String>()) {
                if (seeded) seed(fixture, "cached", 1000, 1000);
                fixture.cache.expedite();
                fixture.cache.close();
                assertClosed(fixture);
            }
        }
    }

    @Test
    void successfulPendingReadAfterCloseCannotPublishOrRestartFromDemand() throws Exception {
        assertPendingCompletionAfterClose(false);
    }

    @Test
    void failedPendingReadAfterCloseCannotPublishFailureOrRestartFromDemand() throws Exception {
        assertPendingCompletionAfterClose(true);
    }

    private static void assertPendingCompletionAfterClose(boolean failRead) throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            seed(fixture, "cached", 1000, 1000);
            cache.expedite();
            var pending = failRead ? fixture.failingRead() : fixture.read("late-result");
            cache.refresh(pending, 3000);
            pending.awaitEntered();
            cache.expedite();
            cache.close();
            pending.release.countDown();
            pending.awaitStopped();
            assertClosed(fixture);
        }
    }

    private static void assertClosed(ReadFixture<String> fixture) {
        var cache = fixture.cache;
        int callsBefore = fixture.calls.get();
        var forbidden = fixture.read("revived");
        for (long now : new long[]{3000, 33_000, 200_000}) {
            for (int i = 0; i < 100; i++) {
                cache.expedite();
                cache.refresh(forbidden, now);
                cache.invalidate(value -> value);
                cache.expedite();
                cache.refresh(forbidden, now);
                assertNull(cache.value(now));
                assertNull(cache.failure());
                assertFalse(cache.shouldRefresh(true));
                assertFalse(cache.shouldRefresh(false));
            }
        }
        assertEquals(callsBefore, fixture.calls.get());
        assertEquals(0, forbidden.calls.get());
    }

    @Test
    void publicationBypassesColdGateAndDiscardsObsoleteSuccess() throws Exception {
        assertPublicationReplacesObsoleteRead(false);
    }

    @Test
    void publicationBypassesColdGateAndDiscardsObsoleteFailure() throws Exception {
        assertPublicationReplacesObsoleteRead(true);
    }

    private static void assertPublicationReplacesObsoleteRead(boolean failOldRead) throws Exception {
        try (var fixture = new ReadFixture<Map<String, String>>()) {
            var cache = fixture.cache;
            Map<String, String> original = Map.of("changed", "old-route", "unaffected", "keep-route");
            Map<String, String> retained = Map.of("unaffected", "keep-route");
            Map<String, String> published = Map.of("changed", "new-route", "unaffected", "keep-route");
            seed(fixture, original, 1000, 1000);
            cache.expedite();
            var obsolete = failOldRead ? fixture.failingRead() : fixture.read(original);
            var forbidden = fixture.read(original);
            var fresh = fixture.read(published);
            cache.refresh(obsolete, 3000);
            obsolete.awaitEntered();
            cache.expedite();
            cache.invalidate(snapshot -> Map.of("unaffected", snapshot.get("unaffected")));
            for (int i = 0; i < 100; i++) {
                cache.expedite();
                cache.refresh(forbidden, 3001);
                assertEquals(retained, cache.value(3001));
            }
            assertEquals(2, fixture.calls.get());
            obsolete.release.countDown();
            await(fresh::entered, () -> {
                cache.refresh(fresh, 3001);
                assertEquals(retained, cache.value(3001), "obsolete data must not restore the changed route");
                assertNull(cache.failure(), "obsolete failure must not throttle the replacement");
            });
            assertEquals(0, forbidden.calls.get());
            assertEquals(1, fixture.maxActive.get());
            fresh.release.countDown();
            await(() -> published.equals(cache.value(3001)), () -> cache.refresh(forbidden, 3001));
            assertEquals("old-route", original.get("changed"));
            assertRegularRefreshOnly(fixture, forbidden, published, 3001, 3);
        }
    }

    @Test
    void publicationAfterCurrentFailureBypassesBackoffAndColdBudget() throws Exception {
        try (var fixture = new ReadFixture<String>()) {
            var cache = fixture.cache;
            seed(fixture, "cached", 1000, 1000);
            cache.expedite();
            var failed = fixture.failingRead();
            var forbidden = fixture.read("premature-retry");
            cache.refresh(failed, 3000);
            failed.awaitEntered();
            failed.release.countDown();
            await(() -> cache.failure() != null, () -> cache.refresh(forbidden, 3001));
            cache.expedite();
            cache.refresh(forbidden, 3001);
            assertNotNull(cache.failure());
            cache.invalidate();
            assertNull(cache.failure());
            var fresh = fixture.read("published");
            cache.refresh(fresh, 3001);
            fresh.awaitEntered();
            assertEquals(0, forbidden.calls.get());
            fresh.release.countDown();
            await(() -> "published".equals(cache.value(3001)), () -> cache.refresh(forbidden, 3001));
            assertRegularRefreshOnly(fixture, forbidden, "published", 3001, 3);
        }
    }

    private static <T> void assertRegularRefreshOnly(ReadFixture<T> fixture, GatedRead<T> forbidden,
                                                    T expected, long observedAt, int completedCalls)
            throws Exception {
        var cache = fixture.cache;
        assertEquals(30_000L, RenderManager.ReadCache.REFRESH_MS);
        long due = observedAt + RenderManager.ReadCache.REFRESH_MS;
        for (long now : new long[]{observedAt, observedAt + RenderManager.ReadCache.EXPEDITED_MS, due - 1}) {
            for (int i = 0; i < 100; i++) cache.refresh(forbidden, now);
            assertEquals(expected, cache.value(now));
        }
        assertEquals(completedCalls, fixture.calls.get());
        // A queued forbidden read blocks this barrier, exposing even submissions not yet executed.
        var regular = fixture.read(expected);
        cache.refresh(regular, due);
        regular.awaitEntered();
        assertEquals(0, forbidden.calls.get());
        assertEquals(completedCalls + 1, fixture.calls.get());
        assertEquals(1, fixture.maxActive.get(), "all read paths must remain single-flight");
    }

    private static <T> void seed(ReadFixture<T> fixture, T value, long startedAt, long observedAt)
            throws Exception {
        var initial = fixture.read(value);
        fixture.cache.refresh(initial, startedAt);
        initial.awaitEntered();
        initial.release.countDown();
        await(() -> value.equals(fixture.cache.value(observedAt)),
                () -> fixture.cache.refresh(initial, observedAt));
    }

    private static void await(BooleanSupplier condition, Runnable poll) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            poll.run();
            // Future completion follows Callable return; yield without advancing the supplied clock.
            Thread.yield();
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
            return add(null, new IllegalStateException("fixture cold-read failure"));
        }

        private GatedRead<T> add(T value, RuntimeException failure) {
            var read = new GatedRead<>(this, value, failure);
            reads.add(read);
            return read;
        }

        @Override
        public void close() {
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
