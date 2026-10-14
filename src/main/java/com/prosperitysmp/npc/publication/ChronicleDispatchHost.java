package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held;
import com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exclusively owns dispatcher and its transport. Runtime discovery and authority remain separate. */
public final class ChronicleDispatchHost {
    public enum Failure { NONE, DATABASE, INTERNAL }
    public record Outcome(Held request,UUID generation,Destination destination,ChronicleDispatcher.Result result,Failure failure) {
        public Outcome {
            Objects.requireNonNull(request);Objects.requireNonNull(generation);Objects.requireNonNull(destination);Objects.requireNonNull(failure);
            if((failure==Failure.NONE)!=(result!=null))throw new IllegalArgumentException("Invalid dispatch outcome");
        }
    }
    private final Thread owner=Thread.currentThread();
    private final ChronicleDispatcher dispatcher;
    private final AutoCloseable transport;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicReference<Outcome> completed=new AtomicReference<>();
    private volatile boolean failed;
    private CompletableFuture<Void> shutdown;

    public ChronicleDispatchHost(ChronicleDispatcher dispatcher,AutoCloseable transport) {
        this.dispatcher=Objects.requireNonNull(dispatcher);this.transport=Objects.requireNonNull(transport);
        if(!dispatcher.ownsTransport(transport))throw new IllegalArgumentException("Dispatcher transport ownership mismatch");
        // One task can wait behind the preceding finishing tail; reserve the second slot for terminal close.
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),task->{
            var thread=new Thread(task,"prosperity-npc-chronicle-dispatch");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    /**
     * False means not accepted. An unread outcome blocks admission, so neither work
     * nor notifications accumulate. No caller completion callback runs on the worker.
     */
    public boolean trySend(Held held,UUID generation,Destination destination) {
        main();Objects.requireNonNull(held);Objects.requireNonNull(generation);Objects.requireNonNull(destination);
        if(shutdown!=null || failed || !busy.compareAndSet(false,true))return false;
        // Acquire after the previous worker publishes its outcome, then inspect the slot.
        // Checking the slot before CAS allows a finishing worker's unread outcome to be overwritten.
        if(failed || completed.get()!=null){busy.set(false);return false;}
        try {executor.execute(()->{
            try {completed.set(new Outcome(held,generation,destination,dispatcher.send(held,generation,destination),Failure.NONE));}
            catch(Throwable failure){failed=true;completed.set(new Outcome(held,generation,destination,null,
                    failure instanceof java.sql.SQLException ? Failure.DATABASE : Failure.INTERNAL));if(failure instanceof Error fatal)throw fatal;}
            finally{busy.set(false);}
        });}catch(RuntimeException rejected){failed=true;busy.set(false);return false;}
        return true;
    }
    /** Consume once on owner thread, including after shutdown; private exception text is never exposed. */
    public Optional<Outcome> poll(){main();return Optional.ofNullable(completed.getAndSet(null));}
    public boolean running(){main();return busy.get();}
    public boolean held(){main();return failed;}
    /** Cooperative drain: a stuck database operation keeps this pending, never falsely safe to close DB. */
    public CompletionStage<Void> shutdown() {
        main();if(shutdown!=null)return shutdown.minimalCompletionStage();
        shutdown=new CompletableFuture<>();dispatcher.cancel();
        try{executor.execute(()->{
            try{transport.close();shutdown.complete(null);}
            catch(Throwable failure){failed=true;shutdown.completeExceptionally(failure);if(failure instanceof Error fatal)throw fatal;}
        });}catch(RuntimeException rejected){failed=true;shutdown.completeExceptionally(rejected);}
        finally{executor.shutdown();}
        return shutdown.minimalCompletionStage();
    }
    private void main(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Chronicle dispatch host owner required");}
}
