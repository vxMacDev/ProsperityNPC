package com.prosperitysmp.npc.db;

import com.prosperitysmp.npc.publication.PublicationShutdown;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DatabaseWorkersTest {
    @Test void retiredWorkerAndOtherDrainBothGateRelease() throws Exception {
        var registry=new DatabaseWorkers(Duration.ofSeconds(3));var retired=Executors.newSingleThreadExecutor();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var other=new CompletableFuture<Void>();var closed=new AtomicInteger();
        registry.register(retired);registry.register(retired);
        retired.submit(()->{entered.countDown();awaitIgnoringInterrupt(release);});
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));retired.shutdownNow(); // Simulate a discarded controller.
            var drain=registry.shutdown();assertTrue(registry.sealed());
            var combined=CompletableFuture.allOf(drain.toCompletableFuture(),other);
            var released=PublicationShutdown.releaseAfter(combined,closed::incrementAndGet);
            drain.toCompletableFuture().complete(null);assertFalse(registry.shutdown().toCompletableFuture().isDone());
            release.countDown();drain.toCompletableFuture().get(4,TimeUnit.SECONDS);
            assertFalse(released.toCompletableFuture().isDone());assertEquals(0,closed.get());
            other.complete(null);released.toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,closed.get());
        }finally{release.countDown();retired.shutdownNow();assertTrue(retired.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void timeoutRemainsFailedOnRepeatedShutdownAfterWorkerEventuallyStops() throws Exception {
        var registry=new DatabaseWorkers(Duration.ofMillis(50));var executor=Executors.newSingleThreadExecutor();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);registry.register(executor);
        executor.submit(()->{entered.countDown();awaitIgnoringInterrupt(release);});
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));var drain=registry.shutdown();
            assertThrows(ExecutionException.class,()->drain.toCompletableFuture().get(2,TimeUnit.SECONDS));
            release.countDown();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));assertSame(drain,registry.shutdown());
            assertThrows(ExecutionException.class,()->registry.shutdown().toCompletableFuture().get());
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void emptyStartupAndLateRegistrationAreFailClosed() throws Exception {
        var registry=new DatabaseWorkers();registry.shutdown().toCompletableFuture().get(2,TimeUnit.SECONDS);
        var late=Executors.newSingleThreadExecutor();
        try{assertThrows(IllegalStateException.class,()->registry.register(late));assertTrue(late.isShutdown());}
        finally{late.shutdownNow();assertTrue(late.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void brokenWorkerShutdownDoesNotSkipOtherWorkers(boolean linkage) throws Exception {
        var registry=new DatabaseWorkers();
        var broken=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>()) {
            @Override public java.util.List<Runnable> shutdownNow(){if(linkage)throw new NoClassDefFoundError("fixture-private-details");throw new IllegalStateException("fixture-private-details");}
        };
        var normal=Executors.newSingleThreadExecutor();registry.register(broken);registry.register(normal);
        try {
            var error=assertThrows(ExecutionException.class,()->registry.shutdown().toCompletableFuture().get(2,TimeUnit.SECONDS));
            assertTrue(normal.isTerminated());assertFalse(error.toString().contains("fixture-private-details"));
        }finally{broken.shutdown();normal.shutdownNow();assertTrue(broken.awaitTermination(2,TimeUnit.SECONDS));assertTrue(normal.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void capacityIsBoundedWithoutLosingPreviouslyRegisteredWorkers() throws Exception {
        var registry=new DatabaseWorkers();var workers=new java.util.ArrayList<ExecutorService>();
        try {
            for(int i=0;i<33;i++)workers.add(Executors.newSingleThreadExecutor());
            for(int i=0;i<32;i++)registry.register(workers.get(i));
            assertThrows(IllegalStateException.class,()->registry.register(workers.get(32)));assertTrue(workers.get(32).isShutdown());
            registry.shutdown().toCompletableFuture().get(3,TimeUnit.SECONDS);
            for(var worker:workers)assertTrue(worker.isTerminated());
        }finally{for(var worker:workers){worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}}
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch){
        boolean done=false;while(!done)try{latch.await();done=true;}catch(InterruptedException ignored){}
    }
}
