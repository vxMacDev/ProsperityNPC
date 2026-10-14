package com.prosperitysmp.npc.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises only the nested pure Java read gate; no server or database is started. */
class RenderReadCacheTest {
    @Test
    void oneInFlightReadEvenWhenInvalidatedOrStale() throws Exception {
        try (var cache = new RenderManager.ReadCache<String>()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Callable<String> read = () -> {
                calls.incrementAndGet();
                entered.countDown();
                release.await();
                return "old";
            };
            cache.refresh(read, 1000);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cache.invalidate();
            for (int i = 0; i < 100; i++) {
                cache.refresh(read, 200_000);
            }
            assertEquals(1, calls.get());
            assertNull(cache.value(200_000));
            release.countDown();
            AtomicLong now = new AtomicLong(200_000);
            await(() -> "new".equals(cache.value(now.get())), () -> {
                cache.refresh(() -> "new", now.addAndGet(31_000));
                assertNotEquals("old", cache.value(now.get()));
            });
        }
    }

    @Test
    void refreshIsThrottledAndValueExpiresFromReadStart() throws Exception {
        try (var cache = new RenderManager.ReadCache<String>()) {
            AtomicInteger calls = new AtomicInteger();
            Callable<String> read = () -> "v" + calls.incrementAndGet();
            cache.refresh(read, 1000);
            await(() -> "v1".equals(cache.value(1000)), () -> cache.refresh(read, 1000));
            for (long now = 2000; now < 31_000; now += 1000) {
                cache.refresh(read, now);
            }
            assertEquals(1, calls.get());
            assertEquals("v1", cache.value(120_999));
            assertNull(cache.value(121_000));
            cache.refresh(read, 121_000);
            await(() -> "v2".equals(cache.value(121_000)), () -> cache.refresh(read, 121_000));
        }
    }

    @Test
    void failedReadClearsDataAndCannotRetryEveryTick() throws Exception {
        try (var cache = new RenderManager.ReadCache<String>()) {
            AtomicInteger calls = new AtomicInteger();
            Callable<String> fail = () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("fixture");
            };
            cache.refresh(fail, 1000);
            await(() -> cache.failure() != null, () -> cache.refresh(fail, 1000));
            for (long now = 2000; now < 31_000; now += 1000) {
                cache.refresh(fail, now);
            }
            assertEquals(1, calls.get());
            assertNull(cache.value(30_000));
        }
    }

    @Test
    void completedReadAfterCloseCannotPublishOrRestart() throws Exception {
        var cache = new RenderManager.ReadCache<String>();
        cache.refresh(() -> "cached", 1000);
        await(() -> cache.value(1000) != null, () -> cache.refresh(() -> "cached", 1000));
        cache.close();
        cache.refresh(() -> { fail("closed cache started another read"); return "bad"; }, 200_000);
        assertNull(cache.value(200_000));
    }

    private static void await(BooleanSupplier condition, Runnable poll) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            poll.run();
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "read did not complete within test deadline");
    }
}
