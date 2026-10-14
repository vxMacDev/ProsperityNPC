package com.prosperitysmp.npc.publication;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationShutdownTest {
    @Test void alreadyCompletedDrainStillReleasesOffCallingThread() throws Exception {
        Thread owner=Thread.currentThread();var released=new AtomicReference<Thread>();
        PublicationShutdown.releaseAfter(CompletableFuture.completedFuture(null),()->released.set(Thread.currentThread()))
                .toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertNotSame(owner,released.get());assertEquals("prosperity-npc-db-close",released.get().getName());
    }
    @Test void pendingDrainCannotReleaseEarlyAndExceptionalDrainNeverReleases() throws Exception {
        var released=new AtomicInteger();var pending=new CompletableFuture<Void>();
        var result=PublicationShutdown.releaseAfter(pending,released::incrementAndGet);
        assertFalse(result.toCompletableFuture().isDone());assertEquals(0,released.get());
        pending.completeExceptionally(new java.io.IOException("cleanup failed"));
        assertThrows(ExecutionException.class,()->result.toCompletableFuture().get(5,TimeUnit.SECONDS));
        assertEquals(0,released.get());
        var other=new CompletableFuture<Void>();var success=PublicationShutdown.releaseAfter(other,released::incrementAndGet);
        other.complete(null);success.toCompletableFuture().get(5,TimeUnit.SECONDS);assertEquals(1,released.get());
    }
    @Test void releaseFailureIsNotAcknowledgedAndCallerCannotForgeCompletion() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var result=PublicationShutdown.releaseAfter(CompletableFuture.completedFuture(null),()->{
            entered.countDown();
            try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            throw new IllegalStateException("close failed");
        });
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));result.toCompletableFuture().complete(null);
            assertFalse(result.toCompletableFuture().isDone());release.countDown();
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,()->result.toCompletableFuture().get(5,TimeUnit.SECONDS)).getCause());
        } finally {release.countDown();}
    }
}
