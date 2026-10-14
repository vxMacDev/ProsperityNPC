package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.*;
import com.prosperitysmp.npc.publication.ChronicleRecoveryHost.Disposition;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleRecoveryHostTest {
    private static final Approval A=scope(1),B=scope(2);
    private static final UUID GEN=new UUID(0,20),REQUEST=new UUID(0,30);
    private static Approval scope(int id){return new Approval(new UUID(0,id),new UUID(0,10),"c00"+id,"main");}
    private static final class Fixture implements Journal {
        final List<Approval> seen=new CopyOnWriteArrayList<>();
        final AtomicInteger commits=new AtomicInteger();
        volatile Approval fail;
        volatile boolean runtime;
        volatile boolean fatal;
        volatile CountDownLatch entered,release;
        @Override public Checkpoint load(Approval scope,UUID generation) throws SQLException {
            seen.add(scope);
            if(fatal)throw new AssertionError("fixture fatal");
            if(runtime)throw new IllegalStateException("PRIVATE fixture");
            if(scope.equals(fail))throw new SQLException("PRIVATE fixture");
            if(entered!=null){entered.countDown();await(release);}
            return new Checkpoint(scope,generation,0,null);
        }
        @Override public void commit(Checkpoint expected,ChronicleDiscoveryRepository.Page page,List<Attempt> attempts){commits.incrementAndGet();}
        ChronicleRecoveryWorker worker(){return new ChronicleRecoveryWorker((s,g,c,l)->
                new ChronicleDiscoveryRepository.Page(Instant.parse("2026-09-08T12:00:00Z"),List.of(REQUEST),null),
                (id,s,g)->new ChronicleOutboxRepository.Held("a".repeat(64),"b".repeat(64)),this);}
    }
    private static void await(CountDownLatch latch) throws SQLException {
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new SQLException("fixture timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}
    }
    private static void idle(ChronicleRecoveryHost host) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(host.running() && System.nanoTime()-deadline<0)Thread.yield();
        assertFalse(host.running(),"worker did not finish");
    }
    private static ChronicleRecoveryHost host(Fixture f,AtomicLong time){
        return new ChronicleRecoveryHost(f.worker(),List.of(A,B),GEN,Duration.ofSeconds(1),2,time::get);
    }
    private static void close(ChronicleRecoveryHost host) throws Exception {host.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}

    @Test void roundRobinIncludesFailedPropertyAndRespectsCadenceWithoutBacklog() throws Exception {
        var f=new Fixture();f.fail=A;var time=new AtomicLong();var host=host(f,time);
        try {
            host.tick();idle(host);assertEquals(List.of(A),f.seen);
            for(int i=0;i<100;i++)host.tick();assertEquals(List.of(A),f.seen);
            time.addAndGet(TimeUnit.SECONDS.toNanos(1));host.tick();idle(host);
            assertEquals(List.of(A,B),f.seen);assertEquals(1,f.commits.get());
            assertEquals(List.of(Disposition.FAILED,Disposition.CHECKPOINTED),host.health().stream().map(ChronicleRecoveryHost.Health::disposition).toList());
            assertFalse(host.held());assertFalse(host.health().toString().contains("PRIVATE"));
            f.fail=null;time.addAndGet(TimeUnit.SECONDS.toNanos(1));host.tick();idle(host);
            assertEquals(List.of(A,B,A),f.seen);assertEquals(Disposition.CHECKPOINTED,host.health().getFirst().disposition());
            assertThrows(UnsupportedOperationException.class,()->host.health().clear());
        }finally{close(host);}
    }

    @Test void busyTicksCoalesceAndShutdownWaitsForActualInflightRead() throws Exception {
        var f=new Fixture();f.entered=new CountDownLatch(1);f.release=new CountDownLatch(1);
        var time=new AtomicLong();var host=host(f,time);
        try {
            host.tick();await(f.entered);
            for(int i=0;i<100;i++){time.addAndGet(TimeUnit.SECONDS.toNanos(1));host.tick();}
            assertEquals(List.of(A),f.seen);
            var stopped=host.shutdown().toCompletableFuture();assertFalse(stopped.isDone());
            host.tick();f.release.countDown();stopped.get(5,TimeUnit.SECONDS);
            assertEquals(0,f.commits.get());assertEquals(Disposition.CANCELLED,host.health().getFirst().disposition());
            host.tick();assertEquals(List.of(A),f.seen);assertTrue(host.shutdown().toCompletableFuture().isDone());
        }finally{f.release.countDown();close(host);}
    }

    @Test void runtimeFailureHoldsAllFurtherDispatchUntilExplicitReplacement() throws Exception {
        var f=new Fixture();f.runtime=true;var time=new AtomicLong();var host=host(f,time);
        try {
            host.tick();idle(host);assertTrue(host.held());f.runtime=false;
            time.addAndGet(TimeUnit.HOURS.toNanos(1));host.tick();assertEquals(List.of(A),f.seen);
        }finally{close(host);}
    }

    @Test void ownerBoundariesAndShutdownBeforeFirstTick() throws Exception {
        var f=new Fixture();var host=host(f,new AtomicLong());
        try(var other=Executors.newSingleThreadExecutor()) {
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->other.submit(host::tick).get()).getCause());
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->other.submit(host::shutdown).get()).getCause());
            close(host);host.tick();assertTrue(f.seen.isEmpty());
        }finally{close(host);}
    }

    @Test void shutdownHasReservedCapacityBehindFinishingTailAndQueuedPage() throws Exception {
        var f=new Fixture();var host=host(f,new AtomicLong());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var field=ChronicleRecoveryHost.class.getDeclaredField("executor");field.setAccessible(true);
        var executor=(ThreadPoolExecutor)field.get(host);
        // Models the executor still finishing a task after the host busy flag was cleared.
        executor.execute(()->{entered.countDown();try{await(release);}catch(SQLException e){throw new AssertionError(e);}});
        try {
            await(entered);host.tick();assertEquals(1,executor.getQueue().size());
            var publicStage=host.shutdown();var forged=publicStage.toCompletableFuture();
            assertFalse(forged.isDone());forged.complete(null);
            assertFalse(host.shutdown().toCompletableFuture().isDone(),"caller must not forge drain acknowledgment");
            assertEquals(2,executor.getQueue().size());release.countDown();close(host);
            assertTrue(f.seen.isEmpty(),"queued cancelled page must perform no SQL");
        }finally{release.countDown();close(host);}
    }

    @Test void fatalErrorHoldsDispatchButTerminalDrainStillCompletes() throws Exception {
        var f=new Fixture();f.fatal=true;var time=new AtomicLong();var host=host(f,time);
        try{
            host.tick();idle(host);assertTrue(host.held());f.fatal=false;
            time.addAndGet(TimeUnit.SECONDS.toNanos(2));host.tick();idle(host);
            assertEquals(List.of(A),f.seen);close(host);
        }
        finally{close(host);}
    }

    @Test void rejectsUnboundedOrAmbiguousConfigurationBeforeStartingThread() {
        var f=new Fixture();var worker=f.worker();
        assertThrows(IllegalArgumentException.class,()->new ChronicleRecoveryHost(worker,List.of(),GEN,Duration.ofSeconds(1),1,System::nanoTime));
        assertThrows(IllegalArgumentException.class,()->new ChronicleRecoveryHost(worker,List.of(A,A),GEN,Duration.ofSeconds(1),1,System::nanoTime));
        var alias=new Approval(new UUID(0,99),A.worldId(),A.regionId(),"other");
        assertThrows(IllegalArgumentException.class,()->new ChronicleRecoveryHost(worker,List.of(A,alias),GEN,Duration.ofSeconds(1),1,System::nanoTime));
        assertThrows(IllegalArgumentException.class,()->new ChronicleRecoveryHost(worker,List.of(A),GEN,Duration.ZERO,1,System::nanoTime));
        assertThrows(IllegalArgumentException.class,()->new ChronicleRecoveryHost(worker,List.of(A),GEN,Duration.ofSeconds(1),17,System::nanoTime));
        assertThrows(IllegalArgumentException.class,()->new ChronicleRecoveryHost(worker,java.util.stream.IntStream.rangeClosed(1,513).mapToObj(ChronicleRecoveryHostTest::scope).toList(),GEN,Duration.ofSeconds(1),1,System::nanoTime));
    }
}
