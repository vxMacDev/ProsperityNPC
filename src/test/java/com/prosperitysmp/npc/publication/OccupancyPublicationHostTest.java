package com.prosperitysmp.npc.publication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyPublicationHostTest {
    @TempDir Path root;
    OccupancyPublicationHost host(OccupancyRefreshPump.Reader source) {
        return new OccupancyPublicationHost(root,source,Clock.fixed(OccupancyPublicationSessionTest.NOW,ZoneOffset.UTC),
                System::nanoTime,Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofHours(1),Duration.ofDays(1));
    }
    @Test void activeReadDrainsOffOwnerWithoutTicksAndCompletionCannotBeForged() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Thread owner=Thread.currentThread();var dbClosed=new AtomicBoolean();
        var h=host(s->{
            assertNotSame(owner,Thread.currentThread());entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("test source timeout");
            assertFalse(dbClosed.get());return OccupancyPublicationSessionTest.evidence(OccupancyPublicationSessionTest.NOW,true);
        });
        try {
            h.configure(OccupancyPublicationSessionTest.SCOPE);h.tick();assertTrue(entered.await(5,TimeUnit.SECONDS));
            var webRead=new FutureTask<>(h::readPublic);var webThread=new Thread(webRead);webThread.start();
            assertTrue(webRead.get(1,TimeUnit.SECONDS).isEmpty());
            webThread.join(1000);assertFalse(webThread.isAlive());
            var drained=h.shutdown();drained.thenRun(()->dbClosed.set(true));
            assertTrue(h.current().isEmpty());
            assertTrue(h.readPublic().isEmpty());
            assertEquals("DRAINING",h.status());
            var forged=drained.toCompletableFuture();forged.complete(null);
            assertFalse(h.shutdown().toCompletableFuture().isDone());assertFalse(dbClosed.get());
            release.countDown();drained.toCompletableFuture().get(5,TimeUnit.SECONDS);
            // A dependent callback may run after the independent waiting observer.
            drained.thenRun(()->dbClosed.set(true)).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertTrue(dbClosed.get());
            assertEquals("STOPPED",h.status());
            try(var reopened=new AtomicOccupancyArtifacts(root)) {
                assertTrue(reopened.beginAuthorization(UUID.randomUUID()).entries().isEmpty());
            }
            assertThrows(IllegalStateException.class,()->h.configure(OccupancyPublicationSessionTest.SCOPE));
        } finally {release.countDown();h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    @Test void disabledHostNeverReadsOrCreatesFilesAndRejectsOffOwnerCalls() throws Exception {
        var reads=new AtomicInteger();var h=host(s->{reads.incrementAndGet();throw new AssertionError("no read expected");});
        try {
            for(int i=0;i<100;i++)h.tick();assertEquals(0,reads.get());
            assertEquals("DISABLED / NO_READ",h.status());
            var result=new FutureTask<Void>(()->{h.tick();return null;});var thread=new Thread(result);thread.start();
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS)).getCause());
            thread.join(5000);assertFalse(thread.isAlive());
        } finally {h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
        try(var entries=Files.list(root)){assertEquals(0,entries.count());}
    }
    @Test void workerPublishesThenOwnerAcceptsAndInvalidReplacementRevokes() throws Exception {
        var h=host(s->OccupancyPublicationSessionTest.evidence(OccupancyPublicationSessionTest.NOW,true));
        try {
            h.configure(OccupancyPublicationSessionTest.SCOPE);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(h.current().isEmpty() && System.nanoTime()<deadline){h.tick();Thread.sleep(1);}
            assertTrue(h.current().isPresent());assertTrue(Files.exists(root.resolve(".selection.json")));
            assertThrows(IllegalArgumentException.class,()->h.configure(List.of(
                    OccupancyPublicationSessionTest.SCOPE.getFirst(),OccupancyPublicationSessionTest.SCOPE.getFirst())));
            assertTrue(h.current().isEmpty());
        } finally {h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
}
