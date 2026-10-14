package com.prosperitysmp.npc.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class RenderRefreshLifecycleTest {
    @Test void publicationRemovingLastPreviewStillDrainsObsoleteReadAndReplacementThenIdles() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var cache = new RenderManager.ReadCache<String>()) {
            assertFalse(cache.shouldRefresh(false));
            cache.refresh(() -> {
                calls.incrementAndGet();
                started.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("read not released");
                return "old-route";
            }, 1000);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            cache.invalidate();
            Callable<String> replacement = () -> { calls.incrementAndGet(); return "new-route"; };
            // routesChanged requests again, but must not submit over the first read.
            cache.refresh(replacement, 1000);
            assertTrue(cache.shouldRefresh(false));
            release.countDown();
            await(() -> "new-route".equals(cache.value(1000)), () -> {
                if (cache.shouldRefresh(false)) cache.refresh(replacement, 1000);
                assertNotEquals("old-route", cache.value(1000));
            });
            assertEquals(2, calls.get());
            assertFalse(cache.shouldRefresh(false), "no unused periodic SQL after completion");
            assertFalse(cache.shouldRefresh(false));
            assertTrue(cache.shouldRefresh(true));
        } finally {
            release.countDown();
        }
    }

    @Test void currentFailureWithoutConsumersIsConsumedThenIdles() throws Exception {
        try (var cache = new RenderManager.ReadCache<String>()) {
            var calls = new AtomicInteger();
            Callable<String> read = () -> { calls.incrementAndGet(); throw new IllegalStateException("read failed"); };
            cache.refresh(read, 1000);
            await(() -> cache.failure() != null, () -> {
                if (cache.shouldRefresh(false)) cache.refresh(read, 1000);
            });
            assertFalse(cache.shouldRefresh(false));
            assertNull(cache.value(1000));
            assertEquals(1, calls.get());
        }
    }

    @SuppressWarnings("try") // Explicit early close is the behavior under test.
    @Test void closedCacheDoesNotRequestMaintenanceEvenWithConsumers() {
        try (var cache = new RenderManager.ReadCache<String>()) {
            cache.close();
            assertFalse(cache.shouldRefresh(false));
            assertFalse(cache.shouldRefresh(true));
        }
    }

    @Test void actualMaintenanceUsesTheLifecycleGate() throws IOException {
        try (var bytes = RenderManager.class.getResourceAsStream("RenderManager.class")) {
            assertNotNull(bytes);
            var maintain = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().equalsString("maintain")).findFirst().orElseThrow();
            var calls = maintain.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assertEquals(1, calls.stream().filter("shouldRefresh"::equals).count());
            assertTrue(calls.indexOf("shouldRefresh") < calls.indexOf("requestSnapshot"));
        }
    }

    private static void await(BooleanSupplier finished, Runnable tick) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!finished.getAsBoolean() && System.nanoTime() < until) {
            tick.run();
            Thread.sleep(5);
        }
        assertTrue(finished.getAsBoolean(), "maintenance stopped before consuming the route refresh");
    }
}
