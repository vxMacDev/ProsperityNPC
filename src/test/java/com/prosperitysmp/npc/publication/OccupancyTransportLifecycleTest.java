package com.prosperitysmp.npc.publication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyTransportLifecycleTest {
    @TempDir Path root;
    OccupancyPublicationHost host() {
        return new OccupancyPublicationHost(root,s->OccupancyPublicationSessionTest.evidence(OccupancyPublicationSessionTest.NOW,true),
                Clock.fixed(OccupancyPublicationSessionTest.NOW,ZoneOffset.UTC),System::nanoTime,
                Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofHours(1),Duration.ofDays(1));
    }
    @Test void startsOffOwnerAuthenticatesAndShutdownReleasesListener() throws Exception {
        byte[] transferred=OccupancyHttpServerTest.token();String auth=OccupancyHttpServerTest.bearer(transferred);
        Thread owner=Thread.currentThread();var readThread=new AtomicReference<Thread>();var h=host();
        try(var client=HttpClient.newHttpClient()) {
            h.startTransport(OccupancyHttpServerTest.bind(),()->{readThread.set(Thread.currentThread());return transferred;});
            awaitStatus(h,"READY");assertNotSame(owner,readThread.get());assertArrayEquals(new byte[32],transferred);
            var address=h.transportAddress().orElseThrow();
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+address.getPort()+"/v1/occupancy"))
                    .timeout(Duration.ofSeconds(3)).header("Authorization",auth).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(503,response.statusCode()); // No approved scope has produced an accepted read.
            h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals("STOPPED",h.transportStatus());assertTrue(h.transportAddress().isEmpty());
            try(var replacement=new OccupancyHttpServer(address,OccupancyHttpServerTest.token(),Optional::empty)) {
                assertEquals(address.getPort(),replacement.address().getPort());
            }
        } finally {h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    @Test void shutdownDuringTokenReadWaitsAndClearsTokenBeforeCompletion() throws Exception {
        var h=host();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        byte[] bytes=OccupancyHttpServerTest.token();
        try {
            h.startTransport(OccupancyHttpServerTest.bind(),()->{
                entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");return bytes;
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS));var closed=h.shutdown();
            assertFalse(closed.toCompletableFuture().isDone());assertTrue(h.transportAddress().isEmpty());
            release.countDown();closed.toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertArrayEquals(new byte[32],bytes);assertEquals("STOPPED",h.transportStatus());
            assertThrows(IllegalStateException.class,()->h.startTransport(OccupancyHttpServerTest.bind(),OccupancyHttpServerTest::token));
        } finally {release.countDown();h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    @Test void tokenFailureHasBoundedStatusAndDoesNotPreventCleanup() throws Exception {
        var h=host();
        try {
            h.startTransport(OccupancyHttpServerTest.bind(),()->{throw new java.io.IOException("PRIVATE TOKEN PATH");});
            awaitStatus(h,"FAILED");assertTrue(h.transportAddress().isEmpty());
        } finally {h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    @Test void occupiedPortFailsWithoutStoppingOtherListener() throws Exception {
        var h=host();byte[] bytes=OccupancyHttpServerTest.token();
        try(var existing=new OccupancyHttpServer(OccupancyHttpServerTest.bind(),OccupancyHttpServerTest.token(),Optional::empty);
            var client=HttpClient.newHttpClient()) {
            h.startTransport(existing.address(),()->bytes);awaitStatus(h,"FAILED");
            assertArrayEquals(new byte[32],bytes);
            h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(401,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+existing.address().getPort()+"/v1/occupancy"))
                    .timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    @Test void shutdownSkipsQueuedTokenReadCompletely() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var tokenReads=new AtomicInteger();
        var h=new OccupancyPublicationHost(root,s->{
            entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");
            return OccupancyPublicationSessionTest.evidence(OccupancyPublicationSessionTest.NOW,true);
        },Clock.fixed(OccupancyPublicationSessionTest.NOW,ZoneOffset.UTC),System::nanoTime,
                Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofHours(1),Duration.ofDays(1));
        try {
            h.configure(OccupancyPublicationSessionTest.SCOPE);h.tick();assertTrue(entered.await(3,TimeUnit.SECONDS));
            h.startTransport(OccupancyHttpServerTest.bind(),()->{tokenReads.incrementAndGet();return OccupancyHttpServerTest.token();});
            var stopped=h.shutdown();release.countDown();stopped.toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(0,tokenReads.get());assertEquals("STOPPED",h.transportStatus());
        } finally {release.countDown();h.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    static void awaitStatus(OccupancyPublicationHost h,String expected) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!h.transportStatus().equals(expected) && System.nanoTime()<end)Thread.sleep(1);
        assertEquals(expected,h.transportStatus());
    }
}
