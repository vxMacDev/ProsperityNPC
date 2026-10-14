package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.publication.PublicOccupancyRepository.Evidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyPublicationControllerTest {
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-09-08T12:00:00Z");
    static List<Approval> scope(int id) {return List.of(new Approval(new UUID(0,id),new UUID(1,1),"c"+id,"main"));}
    final class Harness extends Clock implements AutoCloseable {
        Instant wall=NOW;long nano;int submitted;boolean reject,inline;
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final AtomicInteger reads=new AtomicInteger();
        final List<List<Approval>> observed=new ArrayList<>();
        volatile CountDownLatch entered,release;
        final OccupancyPublicationController controller=new OccupancyPublicationController(root,work->{
            submitted++;if(reject)throw new RejectedExecutionException();if(inline)work.run();else queue.add(work);
        },scope->{
            reads.incrementAndGet();observed.add(scope);
            if(entered!=null){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");}
            var a=scope.getFirst();
            var p=new Property(a.propertyId(),a.worldId(),a.regionId(),"market","room",2,100,100,null,0,80,true);
            var snapshot=PublicOccupancySnapshot.build(wall,List.of(new PublicOccupancySnapshot.Row(p,1)),scope);
            return new Evidence(snapshot,wall,Map.of(a.propertyId(),1L),Map.of());
        },this,()->nano,Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofHours(1),Duration.ofDays(1));
        void advance(long millis){wall=wall.plusMillis(millis);nano+=millis*1_000_000L;}
        void runNext() throws Exception {
            var failure=new AtomicReference<Throwable>();Runnable job=queue.remove();
            var thread=new Thread(()->{try{job.run();}catch(Throwable t){failure.set(t);}});
            thread.start();thread.join(5000);assertFalse(thread.isAlive());
            if(failure.get()!=null)throw new AssertionError(failure.get());
        }
        @Override public Instant instant(){return wall;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId z){throw new UnsupportedOperationException();}
        @Override public void close() {
            if(release!=null)release.countDown();reject=inline=false;controller.close();
            try {
                for(int i=0;i<10&&!controller.drained();i++) {
                    advance(1000);controller.tick();while(!queue.isEmpty())runNext();controller.tick();
                }
                assertTrue(controller.drained());
            }catch(Exception failure){throw new AssertionError(failure);}
        }
    }
    @Test void refreshCadenceIsBoundedAndCloseDrainsWithoutServing() throws Exception {
        try(var h=new Harness()) {
            h.controller.configure(scope(1));h.controller.tick();assertEquals(1,h.queue.size());
            for(int i=0;i<100;i++)h.controller.tick();assertEquals(1,h.submitted);
            h.runNext();h.controller.tick();assertTrue(h.controller.current().isPresent());
            h.advance(999);h.controller.tick();assertTrue(h.queue.isEmpty());
            h.advance(1);h.controller.tick();assertEquals(1,h.queue.size());
            h.runNext();h.controller.tick();assertEquals(2,h.reads.get());
            h.controller.close();assertTrue(h.controller.current().isEmpty());assertFalse(h.controller.drained());
            h.runNext();h.controller.tick();assertTrue(h.controller.drained());
        }
    }
    @Test void scopeReplacementWaitsForOldReadAndCloseAndOnlyLatestScopeRuns() throws Exception {
        try(var h=new Harness()) {
            h.entered=new CountDownLatch(1);h.release=new CountDownLatch(1);
            h.controller.configure(scope(1));h.controller.tick();
            Runnable old=h.queue.remove();Thread thread=new Thread(old);thread.start();
            try {
                assertTrue(h.entered.await(5,TimeUnit.SECONDS));
                h.controller.configure(scope(2));h.controller.configure(scope(3));
                assertTrue(h.controller.current().isEmpty());assertEquals(1,h.queue.size());
                h.controller.tick();assertEquals(1,h.queue.size());
            } finally {h.release.countDown();thread.join(5000);assertFalse(thread.isAlive());}
            h.entered=null;h.runNext();h.controller.tick(); // close acknowledgement, then new read
            assertEquals(1,h.queue.size());h.runNext();h.controller.tick();
            assertEquals(List.of(scope(1),scope(3)),h.observed);
            assertTrue(h.controller.current().orElseThrow().json().contains("c3"));
            assertFalse(h.controller.current().orElseThrow().json().contains("c1"));
        }
    }
    @Test void rejectedWorkRetriesAtCadenceIncludingClose() throws Exception {
        try(var h=new Harness()) {
            h.reject=true;h.controller.configure(scope(1));h.controller.tick();
            for(int i=0;i<100;i++)h.controller.tick();assertEquals(1,h.submitted);
            h.controller.configure(scope(2));int configuredAttempts=h.submitted;
            for(int i=0;i<100;i++)h.controller.configure(scope(3));
            assertEquals(configuredAttempts,h.submitted);
            h.controller.close();int attempts=h.submitted;
            for(int i=0;i<100;i++)h.controller.tick();assertEquals(attempts,h.submitted);
            assertFalse(h.controller.drained());assertTrue(h.controller.current().isEmpty());
            h.reject=false;h.advance(1000);h.controller.tick();h.runNext();h.controller.tick();
            assertTrue(h.controller.drained());assertEquals(0,h.reads.get());
        }
    }
    @Test void invalidConfigurationRevokesBeforeValidationAndDoesNotRetainDesiredScope() throws Exception {
        try(var h=new Harness()) {
            h.controller.configure(scope(1));h.controller.tick();h.runNext();h.controller.tick();
            assertThrows(IllegalArgumentException.class,()->h.controller.configure(List.of(scope(2).getFirst(),scope(2).getFirst())));
            assertTrue(h.controller.current().isEmpty());h.runNext();h.controller.tick();
            assertEquals(OccupancyPublicationController.State.DISABLED,h.controller.state());
            assertTrue(h.queue.isEmpty());assertEquals(1,h.reads.get());
        }
    }
    @Test void inlineExecutorFailsClosedWithoutSourceOrFileOperations() {
        try(var h=new Harness()) {
            h.inline=true;h.controller.configure(scope(1));h.controller.tick();h.controller.tick();
            assertEquals(0,h.reads.get());assertTrue(h.controller.current().isEmpty());
            assertFalse(java.nio.file.Files.exists(root.resolve(".writer.lock")));
        }
    }
    @Test void closeCanOvertakeQueuedReadWithoutOldSourceIoOrPublication() throws Exception {
        try(var h=new Harness()) {
            h.controller.configure(scope(1));h.controller.tick();Runnable delayed=h.queue.remove();
            h.controller.configure(scope(2));h.runNext(); // close overtakes delayed old read
            h.controller.tick();h.runNext();h.controller.tick(); // new scope completes first
            assertEquals(List.of(scope(2)),h.observed);
            h.queue.add(delayed);h.runNext();h.controller.tick();
            assertEquals(1,h.reads.get());
            assertTrue(h.controller.current().orElseThrow().json().contains("c2"));
            assertFalse(h.controller.current().orElseThrow().json().contains("c1"));
        }
    }
    @Test void shutdownHandoffWaitsForActiveSourceWithoutOwnerTicks() throws Exception {
        try(var h=new Harness()) {
            h.entered=new CountDownLatch(1);h.release=new CountDownLatch(1);
            h.controller.configure(scope(1));h.controller.tick();
            Thread reader=new Thread(h.queue.remove());reader.start();
            FutureTask<Void> cleanup=null;Thread cleaner=null;
            try {
                assertTrue(h.entered.await(5,TimeUnit.SECONDS));
                var work=h.controller.shutdownTask();
                assertTrue(h.controller.current().isEmpty());assertTrue(h.queue.isEmpty());
                assertThrows(IllegalStateException.class,work::call);
                cleanup=new FutureTask<>(work);cleaner=new Thread(cleanup);cleaner.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(cleaner.getState()!=Thread.State.BLOCKED && !cleanup.isDone() && System.nanoTime()<deadline)
                    Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED,cleaner.getState());
                FutureTask<Void> pending=cleanup;
                assertThrows(TimeoutException.class,()->cleanupGet(pending));
                h.release.countDown();reader.join(5000);assertFalse(reader.isAlive());
                cleanup.get(5,TimeUnit.SECONDS);
                // No owner ticks between shutdown and completion. The OS lock must be released.
                try(var reopened=new AtomicOccupancyArtifacts(root)) {
                    assertTrue(reopened.beginAuthorization(UUID.randomUUID()).entries().isEmpty());
                }
                assertTrue(h.controller.current().isEmpty());
                assertThrows(IllegalStateException.class,()->h.controller.configure(scope(2)));
            } finally {
                h.release.countDown();reader.join(5000);
                if(cleaner!=null){cleaner.join(5000);assertFalse(cleaner.isAlive());}
            }
        }
    }
    private static Void cleanupGet(FutureTask<Void> task) throws Exception {
        return task.get(50,TimeUnit.MILLISECONDS);
    }
    @Test void shutdownBeforeQueuedReadPreventsSourceIoAndCanRetry() throws Exception {
        try(var h=new Harness()) {
            h.controller.configure(scope(1));h.controller.tick();Runnable old=h.queue.remove();
            var cleanup=h.controller.shutdownTask();
            var done=new FutureTask<>(cleanup);Thread thread=new Thread(done);thread.start();
            done.get(5,TimeUnit.SECONDS);thread.join(5000);assertFalse(thread.isAlive());
            h.queue.add(old);h.runNext();
            assertEquals(0,h.reads.get());assertTrue(h.controller.current().isEmpty());
            assertFalse(java.nio.file.Files.exists(root.resolve(".writer.lock")));
            var again=new FutureTask<>(cleanup);thread=new Thread(again);thread.start();
            again.get(5,TimeUnit.SECONDS);thread.join(5000);assertFalse(thread.isAlive());
        }
    }
    @Test void webReaderHoldingOldPumpCannotReadAfterReconfigurationReturns() throws Exception {
        try(var h=new Harness()) {
            h.controller.configure(scope(1));h.controller.tick();h.runNext();h.controller.tick();
            var field=OccupancyPublicationController.class.getDeclaredField("readable");field.setAccessible(true);
            var old=(OccupancyRefreshPump)field.get(h.controller);
            var captured=new CountDownLatch(1);var resume=new CountDownLatch(1);
            var read=new FutureTask<>(()->{captured.countDown();if(!resume.await(3,TimeUnit.SECONDS))throw new AssertionError("timeout");return old.readPublic();});
            Thread thread=new Thread(read);thread.start();
            try {
                assertTrue(captured.await(3,TimeUnit.SECONDS));h.controller.configure(scope(2));
                resume.countDown();assertTrue(read.get(3,TimeUnit.SECONDS).isEmpty());
                assertTrue(h.controller.readPublic().isEmpty());
            } finally {resume.countDown();thread.join(3000);assertFalse(thread.isAlive());}
        }
    }
}
