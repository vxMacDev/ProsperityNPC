package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository.Page;
import com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held;
import com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.*;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleRecoveryWorkerTest {
    private static final Approval SCOPE=new Approval(new UUID(0,1),new UUID(0,2),"c001","main");
    private static final UUID GENERATION=new UUID(0,3),FIRST=new UUID(0,4),SECOND=new UUID(0,5);
    private static final Held HELD=new Held("a".repeat(64),"b".repeat(64));
    private static final Page PAGE=new Page(Instant.parse("2026-09-08T12:00:00Z"),List.of(FIRST,SECOND),null);

    /** In-memory boundary fixture only; does not certify a durable journal implementation. */
    private static class JournalFixture implements Journal {
        int loads,commits;List<Attempt> attempts;
        @Override public Checkpoint load(Approval scope,UUID generation) {loads++;return new Checkpoint(scope,generation,0,null);}
        @Override public void commit(Checkpoint expected,Page page,List<Attempt> attempts) throws SQLException {
            commits++;this.attempts=attempts;
        }
    }
    private static Result run(ChronicleRecoveryWorker worker) throws Exception {
        try(var executor=Executors.newSingleThreadExecutor()) {return executor.submit(()->worker.step(SCOPE,GENERATION,2)).get(5,TimeUnit.SECONDS);}
    }

    @Test void stagesAllIdsThenCheckpointsExactImmutableOutcomesOffOwner() throws Exception {
        var order=new ArrayList<String>();var journal=new JournalFixture() {
            @Override public void commit(Checkpoint expected,Page page,List<Attempt> attempts) throws SQLException {
                assertEquals(List.of("discover","stage:"+FIRST,"stage:"+SECOND),order);
                assertEquals(PAGE,page);assertEquals(SCOPE,expected.scope());assertEquals(GENERATION,expected.generation());
                super.commit(expected,page,attempts);
            }
        };
        var worker=new ChronicleRecoveryWorker((scope,generation,cursor,limit)->{order.add("discover");return PAGE;},
                (id,scope,generation)->{order.add("stage:"+id);return HELD;},journal);
        assertEquals(new Result(true,2,0),run(worker));assertEquals(1,journal.commits);
        assertEquals(List.of(new Attempt(FIRST,Outcome.STAGED,HELD),new Attempt(SECOND,Outcome.STAGED,HELD)),journal.attempts);
        assertThrows(UnsupportedOperationException.class,()->journal.attempts.clear());
    }

    @Test void sqlFailureIncludingLostReplyIsUnresolvedNotDroppedOrNonCommitProof() throws Exception {
        var journal=new JournalFixture();var seen=new ArrayList<UUID>();
        var worker=new ChronicleRecoveryWorker((s,g,c,l)->PAGE,(id,s,g)->{
            seen.add(id);if(id.equals(FIRST))throw new SQLException("PRIVATE SQL and resident data");return HELD;
        },journal);
        assertEquals(new Result(true,1,1),run(worker));assertEquals(List.of(FIRST,SECOND),seen);
        assertEquals(new Attempt(FIRST,Outcome.UNRESOLVED,null),journal.attempts.getFirst());
        assertFalse(journal.attempts.toString().contains("PRIVATE"));
    }

    @Test void failedCheckpointCannotAcknowledgeProgressAndReplayStagesAgain() throws Exception {
        var stages=new AtomicInteger();var journal=new JournalFixture() {
            @Override public void commit(Checkpoint expected,Page page,List<Attempt> attempts) throws SQLException {
                super.commit(expected,page,attempts);if(commits==1)throw new SQLException("fixture journal failure");
            }
        };
        var worker=new ChronicleRecoveryWorker((s,g,c,l)->PAGE,(id,s,g)->{stages.incrementAndGet();return HELD;},journal);
        assertInstanceOf(SQLException.class,assertThrows(ExecutionException.class,()->run(worker)).getCause());
        assertEquals(new Result(true,2,0),run(worker));assertEquals(4,stages.get());
    }

    @Test void cancellationAfterAnInFlightStagePreventsFurtherWorkAndCheckpoint() throws Exception {
        var journal=new JournalFixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var worker=new ChronicleRecoveryWorker((s,g,c,l)->PAGE,(id,s,g)->{
            entered.countDown();try {if(!release.await(3,TimeUnit.SECONDS))throw new SQLException("fixture timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}return HELD;
        },journal);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var result=executor.submit(()->worker.step(SCOPE,GENERATION,2));
            try {assertTrue(entered.await(2,TimeUnit.SECONDS));worker.cancel();}finally {release.countDown();}
            assertEquals(new Result(false,1,0),result.get(3,TimeUnit.SECONDS));
        }
        assertEquals(0,journal.commits);int loads=journal.loads;
        assertEquals(new Result(false,0,0),run(worker));assertEquals(loads,journal.loads);
    }

    @Test void ownerAndOverlappingCallsRejectWithoutAnotherDiscovery() throws Exception {
        var journal=new JournalFixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new AtomicInteger();
        var worker=new ChronicleRecoveryWorker((s,g,c,l)->{
            calls.incrementAndGet();entered.countDown();try {if(!release.await(3,TimeUnit.SECONDS))throw new SQLException("fixture timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}return PAGE;
        },(id,s,g)->HELD,journal);
        assertThrows(IllegalStateException.class,()->worker.step(SCOPE,GENERATION,2));assertEquals(0,journal.loads);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->worker.step(SCOPE,GENERATION,2));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                var second=executor.submit(()->worker.step(SCOPE,GENERATION,2));
                assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->second.get(2,TimeUnit.SECONDS)).getCause());
            }finally {release.countDown();}
            assertEquals(new Result(true,2,0),first.get(3,TimeUnit.SECONDS));
        }
        assertEquals(1,calls.get());
    }

    @Test void invalidBatchAndJournalScopeFailBeforeStaging() throws Exception {
        var journal=new JournalFixture();var calls=new AtomicInteger();
        var duplicate=new Page(PAGE.capturedAt(),List.of(FIRST,FIRST),null);
        var worker=new ChronicleRecoveryWorker((s,g,c,l)->duplicate,(id,s,g)->{calls.incrementAndGet();return HELD;},journal);
        assertInstanceOf(SQLException.class,assertThrows(ExecutionException.class,()->run(worker)).getCause());
        assertEquals(0,calls.get());assertEquals(0,journal.commits);
        var wrong=new JournalFixture(){@Override public Checkpoint load(Approval s,UUID g){return new Checkpoint(s,new UUID(0,9),0,null);}};
        var wrongWorker=new ChronicleRecoveryWorker((s,g,c,l)->{fail("discovery must not run");return PAGE;},(id,s,g)->HELD,wrong);
        assertInstanceOf(SQLException.class,assertThrows(ExecutionException.class,()->run(wrongWorker)).getCause());
        assertThrows(IllegalArgumentException.class,()->worker.step(SCOPE,GENERATION,17));
    }

    @Test void cursorSkippingAndChangedFenceRejectBeforeStaging() throws Exception {
        var cursor=new ChronicleDiscoveryRepository.Cursor(GENERATION,SCOPE,new UUID(0,1),new UUID(0,9));
        var journal=new JournalFixture(){@Override public Checkpoint load(Approval s,UUID g){return new Checkpoint(s,g,2,cursor);}};
        for(Page bad:List.of(
                new Page(PAGE.capturedAt(),List.of(SECOND,FIRST),null),
                new Page(PAGE.capturedAt(),List.of(new UUID(0,10)),null),
                new Page(PAGE.capturedAt(),List.of(FIRST,SECOND),new ChronicleDiscoveryRepository.Cursor(GENERATION,SCOPE,new UUID(0,6),new UUID(0,9))),
                new Page(PAGE.capturedAt(),List.of(FIRST,SECOND),new ChronicleDiscoveryRepository.Cursor(GENERATION,SCOPE,SECOND,new UUID(0,10))))) {
            var worker=new ChronicleRecoveryWorker((s,g,c,l)->bad,(id,s,g)->{fail("invalid page must not stage");return HELD;},journal);
            assertInstanceOf(SQLException.class,assertThrows(ExecutionException.class,()->run(worker)).getCause());
        }
        assertEquals(0,journal.commits);
    }

    @Test void cancellationDuringSuccessfulJournalCommitStillAcknowledgesItsCompletion() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var journal=new JournalFixture(){@Override public void commit(Checkpoint expected,Page page,List<Attempt> attempts) throws SQLException {
            entered.countDown();try {if(!release.await(3,TimeUnit.SECONDS))throw new SQLException("fixture timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}
            super.commit(expected,page,attempts);
        }};
        var worker=new ChronicleRecoveryWorker((s,g,c,l)->PAGE,(id,s,g)->HELD,journal);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var result=executor.submit(()->worker.step(SCOPE,GENERATION,2));
            try {assertTrue(entered.await(2,TimeUnit.SECONDS));worker.cancel();}finally {release.countDown();}
            assertEquals(new Result(true,2,0),result.get(3,TimeUnit.SECONDS));
        }
        assertEquals(1,journal.commits);assertEquals(new Result(false,0,0),run(worker));
    }

    @Test void runtimeAndFatalErrorsAbortCheckpointButReleaseSingleFlight() throws Exception {
        for(Throwable failure:List.of(new IllegalStateException("fixture"),new AssertionError("fixture"))) {
            var first=new AtomicBoolean(true);var journal=new JournalFixture();
            var worker=new ChronicleRecoveryWorker((s,g,c,l)->PAGE,(id,s,g)->{
                if(first.compareAndSet(true,false)) {
                    if(failure instanceof Error error)throw error;
                    throw (RuntimeException)failure;
                }return HELD;
            },journal);
            assertSame(failure,assertThrows(ExecutionException.class,()->run(worker)).getCause());
            assertEquals(0,journal.commits);assertEquals(new Result(true,2,0),run(worker));
        }
    }
}
