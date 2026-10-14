package com.prosperitysmp.npc.render;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production frame/edge drivers, plus checks their compiled Bukkit wiring. */
class RenderCadenceTest {
    private static final Runnable NOOP = () -> { };

    @Test void productionFrameAdvancesTwentyAvatarsOncePerTickAndMaintainsOnlyOnTickTwenty() {
        var cadence = new RenderCadence(20);
        int[] steps = new int[20];
        var order = new ArrayList<String>();
        for (int tick = 1; tick <= 40; tick++) {
            order.clear();
            RenderManager.runFrame(cadence, () -> order.add("gates"), () -> order.add("maintenance"), () -> {
                order.add("traversal");
                for (int avatar = 0; avatar < steps.length; avatar++) steps[avatar]++;
            });
            assertEquals(tick % 20 == 0 ? List.of("gates", "maintenance", "traversal")
                    : List.of("gates", "traversal"), order, "frame " + tick);
            for (int count : steps) assertEquals(tick, count);
        }
    }

    @Test void originalMaintenanceClampAndInitialDelayArePreserved() {
        for (long configured : new long[]{Long.MIN_VALUE, 0, 1, 7, 20, 21, Long.MAX_VALUE}) {
            int interval = Math.clamp(configured, 1, 20);
            var cadence = new RenderCadence(configured);
            var maintenance = new AtomicInteger();
            var traversal = new AtomicInteger();
            for (int tick = 1; tick <= 100; tick++) {
                RenderManager.runFrame(cadence, NOOP, maintenance::incrementAndGet, traversal::incrementAndGet);
                assertEquals(tick / interval, maintenance.get(), "configured " + configured + ", tick " + tick);
                assertEquals(tick, traversal.get());
            }
        }
    }

    @Test void delayedFrameDoesNotCatchUpMaintenanceTraversalOrPlanning() {
        var cadence = new RenderCadence(20);
        var budget = new RenderManager.PlanBudget();
        var ids = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4), new UUID(0, 5));
        var admitted = new java.util.HashSet<UUID>();
        var maintenance = new AtomicInteger();
        var traversal = new AtomicInteger();
        var plans = new AtomicInteger();
        for (int tick = 1; tick <= 20; tick++) {
            long time = tick < 20 ? tick * 50L : 60_000L;
            RenderManager.runFrame(cadence, NOOP, maintenance::incrementAndGet, () -> {
                traversal.incrementAndGet();
                Set<UUID> ready = new java.util.HashSet<>(ids);
                ready.removeAll(admitted);
                budget.begin(ready, time);
                ids.forEach(id -> { if (budget.acquire(id)) { plans.incrementAndGet(); admitted.add(id); } });
            });
        }
        assertEquals(1, maintenance.get());
        assertEquals(20, traversal.get());
        assertEquals(4, plans.get(), "two initial plans and two after lag, never saved credit");
    }

    @Test void gatesAndMaintenanceFailuresCannotFallThroughToTraversal() {
        var cadence = new RenderCadence(1);
        var calls = new AtomicInteger();
        Runnable fail = () -> { throw new IllegalStateException("fixture"); };
        assertThrows(IllegalStateException.class,
                () -> RenderManager.runFrame(cadence, fail, calls::incrementAndGet, calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertThrows(IllegalStateException.class,
                () -> RenderManager.runFrame(cadence, NOOP, fail, calls::incrementAndGet));
        assertEquals(0, calls.get());
    }

    @Test void failedStartsUseProductionContinuationWithoutRecountingEveryFastTick() {
        var edge = new EdgeFixture();
        assertTrue(edge.issue());
        var cadence = new RenderCadence(20);
        var maintenance = new AtomicInteger();
        for (int tick = 1; tick <= 40; tick++) {
            edge.now = tick * 50L;
            RenderManager.runFrame(cadence, NOOP, maintenance::incrementAndGet, edge::advance);
            assertEquals(1 + tick / 20, edge.attempts, "one failed start per second");
            assertEquals(tick < 40, edge.alive, "only the third failure removes the avatar");
        }
        assertEquals(List.of(0L, 1000L, 2000L), edge.starts);
        assertEquals(3, edge.warnings);
        assertEquals(3, edge.cancels);
        assertEquals(2, maintenance.get());
    }

    @Test void earlyIdleSchedulesOneFailureThenRetriesAtItsDeadline() {
        var edge = new EdgeFixture();
        edge.startSucceeds = true;
        assertTrue(edge.issue());
        edge.navigating = false;
        for (long now = 100; now <= 1050; now += 50) {
            edge.now = now;
            edge.advance();
            assertTrue(edge.alive);
            assertEquals(1, edge.attempts);
            assertEquals(1, edge.warnings, "pending retry must not be classified as another early idle");
        }
        edge.now = 1100;
        edge.advance();
        assertEquals(List.of(0L, 1100L), edge.starts);
        assertFalse(edge.retry.pending());
        edge.now = 1150;
        edge.advance();
        assertEquals(1, edge.warnings);
        assertEquals(2, edge.attempts);
    }

    @Test void timeoutWaitsBeforeRestartAndLongLagDoesNotReplayRetries() {
        var edge = new EdgeFixture();
        edge.startSucceeds = true;
        assertTrue(edge.issue());
        edge.now = 10_000;
        edge.advance();
        assertEquals(1, edge.warnings);
        assertEquals(1, edge.attempts);
        edge.now = 10_999;
        edge.advance();
        assertEquals(1, edge.attempts);
        edge.now = 60_000;
        edge.advance();
        assertEquals(List.of(0L, 60_000L), edge.starts);
        assertTrue(edge.alive);
    }

    @Test void successfulNextEdgeAndPauseResumeDoNotWaitForMaintenanceOrRetrySpacing() {
        var edge = new EdgeFixture();
        edge.startSucceeds = true;
        assertTrue(edge.issue());
        edge.now = 300;
        edge.retry.arrived();
        assertTrue(edge.issue(), "next edge can be issued in the arrival frame");
        edge.now = 10_300;
        assertTrue(edge.issue(), "pause completion can reissue immediately");
        assertEquals(List.of(0L, 300L, 10_300L), edge.starts);
        assertEquals(0, edge.warnings);
    }

    @Test void arrivalResetsFailureBudgetButSuccessfulReissueAloneDoesNot() {
        var edge = new EdgeFixture();
        assertTrue(edge.issue());
        edge.now = 1000;
        edge.startSucceeds = true;
        assertTrue(edge.issue());
        edge.now = 1100;
        edge.navigating = false;
        edge.advance();
        edge.now = 2100;
        edge.startSucceeds = false;
        edge.advance();
        assertFalse(edge.alive, "starting again does not erase previous failures on this edge");

        var arrived = new EdgeFixture();
        assertTrue(arrived.issue());
        arrived.retry.arrived();
        arrived.now = 50;
        assertTrue(arrived.issue(), "a genuinely reached next edge gets a fresh failure budget");
        arrived.now = 1050;
        assertTrue(arrived.issue());
        arrived.now = 2050;
        assertFalse(arrived.issue());
    }

    @Test void cancellationFailureImmediatelyFailsClosed() {
        var edge = new EdgeFixture();
        edge.cancelSucceeds = false;
        assertFalse(edge.issue());
        assertEquals(1, edge.attempts);
        assertEquals(1, edge.cancels);
        assertEquals(0, edge.warnings);
    }

    @Test void compiledManagerWiresFastTimerAndSingleMaintenanceRefresh() throws IOException {
        assertEquals(1, calls("tick", "runFrame"));
        assertEquals(0, calls("tick", "maintain"));
        assertEquals(0, calls("tick", "advance"));
        assertEquals(1, calls("start", "runTaskTimer"));
        var start = method("start").code().orElseThrow().elementStream().toList();
        int timer = -1;
        for (int i = 0; i < start.size(); i++) {
            if (start.get(i) instanceof InvokeInstruction call && call.name().equalsString("runTaskTimer")) timer = i;
        }
        assertTrue(timer >= 2);
        assertEquals(Opcode.LCONST_1, ((java.lang.classfile.Instruction) start.get(timer - 1)).opcode());
        assertEquals(Opcode.LCONST_1, ((java.lang.classfile.Instruction) start.get(timer - 2)).opcode());
        assertEquals(1, calls("maintain", "requestSnapshot"));
        assertEquals(0, calls("advance", "requestSnapshot"));
        assertEquals(0, calls("updateDebugs", "requestSnapshot"));
        assertEquals(0, calls("maintain", "walk"));
        assertEquals(1, calls("advance", "walk"));
        assertEquals(1, calls("advance", "updateDebugs"));
        assertEquals(1, calls("walk", "continueEdge"));
        assertEquals(1, calls("walk", "arrived", "RenderCadence$EdgeRetry"));
        assertEquals(1, calls("issueEdge", "attempt"));
        assertEquals(1, calls("failed", "failed"));
    }

    private static long calls(String method, String target) throws IOException {
        return calls(method, target, "");
    }

    private static long calls(String method, String target, String ownerSuffix) throws IOException {
        return method(method).code().orElseThrow().elementStream()
                .filter(e -> e instanceof InvokeInstruction call && call.name().equalsString(target)
                        && call.owner().asInternalName().endsWith(ownerSuffix)).count();
    }

    private static MethodModel method(String name) throws IOException {
        try (var bytes = RenderManager.class.getResourceAsStream("RenderManager.class")) {
            assertNotNull(bytes);
            return ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow();
        }
    }

    private static final class EdgeFixture {
        final RenderCadence.EdgeRetry retry = new RenderCadence.EdgeRetry();
        final List<Long> starts = new ArrayList<>();
        long now;
        long started;
        int attempts;
        int cancels;
        int warnings;
        boolean startSucceeds;
        boolean cancelSucceeds = true;
        boolean navigating;
        boolean alive = true;

        boolean issue() {
            return retry.attempt(now, 3, () -> {
                attempts++;
                started = now;
                starts.add(now);
                navigating = startSucceeds;
                return startSucceeds;
            }, this::cancel, () -> warnings++);
        }

        boolean cancel() {
            cancels++;
            navigating = false;
            return cancelSucceeds;
        }

        void advance() {
            alive = RenderManager.continueEdge(retry, now, started, 10_000,
                    () -> navigating, this::issue, () -> retry.failed(now, 3, this::cancel, () -> warnings++));
        }
    }
}
