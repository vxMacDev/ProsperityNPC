package com.prosperitysmp.npc.db;

import com.prosperitysmp.npc.publication.PublicationShutdown;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ExecutorDrainTest {
    @Test void liveExecutorIsNotSilentlyShutdownOrCertified() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            var failure=assertThrows(ExecutionException.class,()->ExecutorDrain.afterShutdown(executor,Duration.ofSeconds(1)).toCompletableFuture().get());
            assertEquals("Executor shutdown was not requested",failure.getCause().getMessage());assertFalse(executor.isShutdown());
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void interruptIgnoringWorkPreventsDatabaseReleaseUntilItActuallyEnds() throws Exception {
        var executor=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var closed=new AtomicInteger();
        executor.submit(()->{entered.countDown();awaitIgnoringInterrupt(release);});
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));executor.shutdownNow();
            var drain=ExecutorDrain.afterShutdown(executor,Duration.ofSeconds(3));
            var released=PublicationShutdown.releaseAfter(drain,closed::incrementAndGet);
            drain.toCompletableFuture().complete(null);assertFalse(released.toCompletableFuture().isDone());assertEquals(0,closed.get());
            release.countDown();released.toCompletableFuture().get(4,TimeUnit.SECONDS);
            assertTrue(executor.isTerminated());assertEquals(1,closed.get());
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void expiredDrainCannotLaterAuthorizeDatabaseClose() throws Exception {
        var executor=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new AtomicInteger();
        executor.submit(()->{entered.countDown();awaitIgnoringInterrupt(release);});
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));executor.shutdownNow();
            var drain=ExecutorDrain.afterShutdown(executor,Duration.ofMillis(50));
            var released=PublicationShutdown.releaseAfter(drain,closed::incrementAndGet);
            var failure=assertThrows(ExecutionException.class,()->released.toCompletableFuture().get(2,TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class,failure.getCause());assertEquals(0,closed.get());
            release.countDown();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));
            assertTrue(released.toCompletableFuture().isCompletedExceptionally());assertEquals(0,closed.get());
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void alreadyTerminatedAndDeadlineBounds() throws Exception {
        var executor=Executors.newSingleThreadExecutor();executor.shutdown();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));
        ExecutorDrain.afterShutdown(executor,Duration.ofSeconds(1)).toCompletableFuture().get(2,TimeUnit.SECONDS);
        for(var duration:new Duration[]{Duration.ZERO,Duration.ofNanos(1),Duration.ofSeconds(31)})
            assertThrows(IllegalArgumentException.class,()->ExecutorDrain.afterShutdown(executor,duration));
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch){
        boolean done=false;while(!done)try{latch.await();done=true;}catch(InterruptedException ignored){}
    }
}
