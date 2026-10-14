package com.prosperitysmp.npc.db;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/** Observes real executor termination off the calling thread; never equates interruption with termination. */
public final class ExecutorDrain {
    private ExecutorDrain(){}
    public static CompletionStage<Void> afterShutdown(ExecutorService executor,Duration deadline) {
        Objects.requireNonNull(executor);Objects.requireNonNull(deadline);
        if(deadline.compareTo(Duration.ofMillis(1))<0 || deadline.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Invalid executor drain deadline");
        var result=new CompletableFuture<Void>();
        if(!executor.isShutdown()) {
            result.completeExceptionally(new IllegalStateException("Executor shutdown was not requested"));
            return result.minimalCompletionStage();
        }
        try {Thread.ofPlatform().daemon(true).name("prosperity-npc-worker-drain").start(()->{
            try {
                if(executor.awaitTermination(deadline.toNanos(),TimeUnit.NANOSECONDS))result.complete(null);
                else result.completeExceptionally(new TimeoutException("NPC worker termination unconfirmed"));
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.completeExceptionally(new IllegalStateException("NPC worker drain interrupted"));}
            catch(Throwable failure){result.completeExceptionally(new IllegalStateException("NPC worker drain failed"));if(failure instanceof Error fatal)throw fatal;}
        });}catch(Throwable failure){result.completeExceptionally(new IllegalStateException("NPC worker drain could not start"));if(failure instanceof Error fatal)throw fatal;}
        return result.minimalCompletionStage();
    }
}
