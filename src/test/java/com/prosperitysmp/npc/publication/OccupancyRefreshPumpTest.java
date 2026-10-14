package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.PublicOccupancyRepository.Evidence;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static com.prosperitysmp.npc.publication.OccupancyRefreshPump.Acceptance.*;
import static com.prosperitysmp.npc.publication.OccupancyRefreshPump.Disposition.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OccupancyRefreshPumpTest {
    private static final Instant START = Instant.parse("2026-09-08T12:00:00Z");
    private static final UUID PROPERTY = new UUID(0, 1), WORLD = new UUID(0, 2);
    private static final Approval APPROVAL = new Approval(PROPERTY, WORLD, "c1", "main");

    private static Evidence evidence(Instant at, boolean enabled) {
        var property = new Property(PROPERTY, WORLD, "c1", "market", "TEST_ONLY", 2, 100, 100,
                null, 0, 80, enabled);
        var snapshot = PublicOccupancySnapshot.build(at, List.of(new Row(property, 1)), List.of(APPROVAL));
        return new Evidence(snapshot, at, Map.of(PROPERTY, 3L), Map.of());
    }

    @Test void singleFlightPublishesOnlyOnTickWithTypedMetadata() throws Exception {
        var h = new Harness();
        assertEquals(ACCEPTED, h.pump.request());
        for (int i = 0; i < 100; i++) assertEquals(BUSY, h.pump.request());
        assertEquals(1, h.queue.size());
        h.run();
        assertTrue(h.pump.current().isEmpty());
        assertEquals(BUSY, h.pump.request());
        h.pump.tick();
        var snapshot = h.pump.current().orElseThrow();
        assertEquals(START, snapshot.capturedAt());
        assertFalse(snapshot.isEmpty());
        assertEquals(READY, h.pump.disposition());
        assertEquals(1, h.reads);
    }
    private static <T> T offThread(java.util.concurrent.Callable<T> work) throws Exception {
        var task=new java.util.concurrent.FutureTask<>(work);Thread thread=new Thread(task);thread.start();
        try {return task.get(3,TimeUnit.SECONDS);}
        finally {thread.join(3000);assertFalse(thread.isAlive());}
    }
    @Test void publicReadsDoNotAcceptCompletionsOrIssueQueries() throws Exception {
        var h=new Harness();h.pump.request();h.run();
        assertTrue(offThread(h.pump::readPublic).isEmpty());
        h.pump.tick();
        offThread(()->{
            for(int i=0;i<1000;i++)assertEquals(START,h.pump.readPublic().orElseThrow().capturedAt());
            return null;
        });
        assertEquals(1,h.reads);assertTrue(h.queue.isEmpty());
        h.pump.configure(List.of());assertTrue(offThread(h.pump::readPublic).isEmpty());
        h.pump.close();assertTrue(offThread(h.pump::readPublic).isEmpty());
    }
    @Test void publicReadExpiresWithoutOwnerTickAndCannotResurrect() throws Exception {
        var h=new Harness();h.publish();
        h.wall=START.plusSeconds(5);h.nano=5_000_000_000L;
        assertTrue(offThread(h.pump::readPublic).isEmpty());
        h.wall=START.plusSeconds(1);h.nano++;
        assertTrue(offThread(h.pump::readPublic).isEmpty());
        assertTrue(h.pump.current().isEmpty());assertEquals(CLOCK_ROLLBACK,h.pump.disposition());
    }
    @Test void rollbackObservedByWebReaderInvalidatesInFlightGeneration() throws Exception {
        var h=new Harness();h.publish();
        h.wall=START.plusSeconds(1);h.nano=1_000_000_000L;h.next=evidence(h.wall,true);h.pump.request();
        h.wall=START.plusSeconds(2);h.nano=2_000_000_000L;
        assertTrue(offThread(h.pump::readPublic).isPresent());
        h.wall=START.plusSeconds(1);h.nano++;
        assertTrue(offThread(h.pump::readPublic).isEmpty());
        h.run();h.pump.tick();assertTrue(h.pump.current().isEmpty());
        assertFalse(h.pump.busy());assertEquals(2,h.reads);
    }

    @Test void configurationImmediatelyRevokesAndOldWorkMustDrain() throws Exception {
        var h = new Harness(); h.publish();
        assertEquals(ACCEPTED, h.pump.request());
        var copy = new ArrayList<>(List.of(APPROVAL));
        h.pump.configure(copy); copy.clear();
        assertTrue(h.pump.current().isEmpty());
        assertEquals(BUSY, h.pump.request());
        h.run(); h.pump.tick();
        assertTrue(h.pump.current().isEmpty());
        assertEquals(ACCEPTED, h.pump.request()); h.run(); h.pump.tick();
        assertTrue(h.pump.current().isPresent());
    }

    @Test void emptyAndInvalidConfigurationDisableAndClear() throws Exception {
        var h = new Harness(); h.publish();
        h.pump.configure(List.of());
        assertEquals(OccupancyRefreshPump.Acceptance.DISABLED, h.pump.request());
        assertTrue(h.pump.current().isEmpty());
        h.pump.configure(List.of(APPROVAL)); h.publish();
        assertThrows(IllegalArgumentException.class, () -> h.pump.configure(List.of(APPROVAL, APPROVAL)));
        assertTrue(h.pump.current().isEmpty());
        assertEquals(OccupancyRefreshPump.Acceptance.DISABLED, h.pump.request());
        assertThrows(IllegalArgumentException.class, () -> h.pump.configure(java.util.Collections.nCopies(513, APPROVAL)));
    }

    @Test void readFailureRetainsHealthyOnlyUntilExactTtl() throws Exception {
        var h = new Harness(); h.publish(); var old = h.pump.current().orElseThrow();
        h.wall = START.plusSeconds(1); h.nano = 1_000_000_000L; h.fail = true;
        h.publish();
        assertEquals(FAILED, h.pump.disposition()); assertSame(old, h.pump.current().orElseThrow());
        h.wall = START.plusSeconds(5); h.nano = 5_000_000_000L;
        assertTrue(h.pump.current().isEmpty());
        h.wall = START.plusSeconds(1); h.nano++;
        assertTrue(h.pump.current().isEmpty());
        h.wall = START.plusSeconds(2); h.nano++;
        assertTrue(h.pump.current().isEmpty());
    }

    @Test void emptyCompletionIsExplicitlyHeldNotVacancyReplacement() throws Exception {
        var h = new Harness(); h.publish(); var old = h.pump.current().orElseThrow();
        h.next = evidence(START, false); h.publish();
        assertEquals(EMPTY_HELD, h.pump.disposition()); assertSame(old, h.pump.current().orElseThrow());
        h.pump.configure(List.of(APPROVAL)); h.publish();
        assertEquals(EMPTY_HELD, h.pump.disposition()); assertTrue(h.pump.current().isEmpty());
    }

    @Test void fullRequestDurationCountsEvenIfWallClockStalls() throws Exception {
        var h = new Harness(); assertEquals(ACCEPTED, h.pump.request());
        h.nano = 4_000_000_000L; h.run(); h.pump.tick();
        assertTrue(h.pump.current().isPresent());
        h.nano = 5_000_000_000L;
        assertTrue(h.pump.current().isEmpty());
        var late = new Harness(); assertEquals(ACCEPTED, late.pump.request());
        late.nano = 5_000_000_000L; late.run(); late.pump.tick();
        assertEquals(EXPIRED, late.pump.disposition()); assertTrue(late.pump.current().isEmpty());
    }

    @Test void rollbackClearsCurrentAndInvalidatesPendingCompletion() throws Exception {
        var h = new Harness(); h.publish();
        h.wall = START.plusSeconds(1); h.nano = 1_000_000_000L;
        assertEquals(ACCEPTED, h.pump.request());
        h.wall = START; h.nano++;
        assertTrue(h.pump.current().isEmpty());
        assertEquals(CLOCK_ROLLBACK, h.pump.disposition());
        h.run(); h.pump.tick(); assertTrue(h.pump.current().isEmpty());
        h.wall = START.plusSeconds(2); h.nano++;
        assertTrue(h.pump.current().isEmpty());
    }

    @Test void monotonicRegressionAlsoClearsPermanently() throws Exception {
        var h = new Harness(); h.nano = 100; h.publish(); h.nano = 99;
        assertTrue(h.pump.current().isEmpty()); h.nano = 101;
        assertTrue(h.pump.current().isEmpty());
    }

    @Test void futurePreRequestAndMismatchedCaptureCompletionsReject() throws Exception {
        for (Evidence bad : List.of(evidence(START.plusMillis(1), true), evidence(START.minusMillis(1), true),
                new Evidence(evidence(START, true).snapshot(), START.plusMillis(1), Map.of(), Map.of()))) {
            var h = new Harness(); h.next = bad; h.publish();
            assertTrue(h.pump.current().isEmpty());
        }
        var floor = new Harness(); floor.wall = START.plusNanos(999_999); floor.publish();
        assertTrue(floor.pump.current().isPresent());
    }

    @Test void olderCaptureCannotReplacePriorHealthy() throws Exception {
        var h = new Harness(); h.publish(); var old = h.pump.current().orElseThrow();
        h.next = evidence(START.minusMillis(1), true); h.publish();
        assertEquals(OLDER, h.pump.disposition()); assertSame(old, h.pump.current().orElseThrow());
    }

    @Test void rejectedExecutorDoesNotWedgeOrDropHealthyView() throws Exception {
        var h = new Harness(); h.publish(); h.reject = true;
        assertEquals(REJECTED, h.pump.request()); assertFalse(h.pump.busy());
        assertTrue(h.pump.current().isPresent()); h.reject = false;
        assertEquals(ACCEPTED, h.pump.request()); h.run(); h.pump.tick();
    }

    @Test void closeDuringReadDiscardsLatePrivateCompletion() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var pump = new OccupancyRefreshPump(task -> {
            var thread = new Thread(task); worker.set(thread); thread.start();
        }, approvals -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return evidence(START, true);
        }, Clock.fixed(START, ZoneOffset.UTC), () -> 0, Duration.ofSeconds(5));
        pump.configure(List.of(APPROVAL));
        try {
            assertEquals(ACCEPTED, pump.request()); assertTrue(entered.await(3, TimeUnit.SECONDS));
            pump.close();
        } finally { release.countDown(); if (worker.get() != null) worker.get().join(3000); }
        assertFalse(worker.get().isAlive()); pump.tick();
        assertEquals(OccupancyRefreshPump.Acceptance.CLOSED, pump.request()); assertTrue(pump.current().isEmpty());
        var field = OccupancyRefreshPump.class.getDeclaredField("completion"); field.setAccessible(true);
        assertNull(field.get(pump), "Late private evidence must not be retained");
    }

    @Test void ownerThreadAndExplicitPositiveBoundedTtlRequired() throws Exception {
        var h = new Harness(); var thrown = new AtomicReference<Throwable>();
        var other = new Thread(() -> { try { h.pump.request(); } catch (Throwable t) { thrown.set(t); } });
        other.start(); other.join(3000); assertFalse(other.isAlive());
        assertInstanceOf(IllegalStateException.class, thrown.get());
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE)))
            assertThrows(IllegalArgumentException.class, () -> new OccupancyRefreshPump(Runnable::run,
                    ignored -> evidence(START, true), Clock.fixed(START, ZoneOffset.UTC), () -> 0, invalid));
    }

    @Test void fatalWorkerErrorStillDrainsThroughFinally() throws Exception {
        var h = new Harness(); h.fatal = true;
        assertEquals(ACCEPTED, h.pump.request());
        assertThrows(AssertionError.class, h::run);
        h.pump.tick();
        assertFalse(h.pump.busy()); assertTrue(h.pump.current().isEmpty());
        assertEquals(FAILED, h.pump.disposition());
        h.fatal = false; h.publish(); assertTrue(h.pump.current().isPresent());
    }

    @Test void inlineExecutorNeverCallsSqlOnOwnerThread() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var pump = new OccupancyRefreshPump(Runnable::run, scope -> {
            reads.incrementAndGet(); return evidence(START, true);
        }, Clock.fixed(START, ZoneOffset.UTC), () -> 0, Duration.ofSeconds(5));
        pump.configure(List.of(APPROVAL));
        assertEquals(ACCEPTED, pump.request());
        assertEquals(0, reads.get());
        pump.tick();
        assertEquals(FAILED, pump.disposition());
        assertFalse(pump.busy());
        assertTrue(pump.current().isEmpty());
    }

    private static final class Harness extends Clock {
        Instant wall = START;
        long nano;
        int reads;
        boolean fail, reject, fatal;
        Evidence next = evidence(START, true);
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final OccupancyRefreshPump pump = new OccupancyRefreshPump(task -> {
            if (reject) throw new java.util.concurrent.RejectedExecutionException();
            queue.add(task);
        }, scope -> {
            assertEquals(List.of(APPROVAL), scope);
            assertThrows(UnsupportedOperationException.class, scope::clear);
            reads++;
            if (fatal) throw new AssertionError("test fatal reader error");
            if (fail) throw new java.sql.SQLException("private failure");
            return next;
        }, this, () -> nano, Duration.ofSeconds(5));

        Harness() { pump.configure(List.of(APPROVAL)); }
        void publish() throws Exception { assertEquals(ACCEPTED, pump.request()); run(); pump.tick(); }
        void run() throws Exception {
            var error = new AtomicReference<Throwable>(); var task = queue.remove();
            var thread = new Thread(() -> { try { task.run(); } catch (Throwable t) { error.set(t); } });
            thread.start(); thread.join(3000); assertFalse(thread.isAlive());
            if (error.get() != null) throw new AssertionError(error.get());
        }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return wall; }
    }
}
