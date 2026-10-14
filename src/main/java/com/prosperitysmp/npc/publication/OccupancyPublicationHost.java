package com.prosperitysmp.npc.publication;

import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;

/** Owns the serial publication worker. The plugin owns ticks and awaits shutdown before DB close. */
public final class OccupancyPublicationHost {
    private final Thread owner=Thread.currentThread();
    private final ThreadPoolExecutor worker;
    private final OccupancyPublicationController controller;
    private CompletableFuture<Void> shutdown;
    @FunctionalInterface public interface TokenSource { byte[] read() throws Exception; }
    private volatile boolean stopping;
    private volatile String transportStatus="DISABLED";
    private OccupancyHttpServer transport; // publication-worker owned

    public OccupancyPublicationHost(Path directory, OccupancyRefreshPump.Reader source, Clock clock,
            LongSupplier monotonic, Duration interval, Duration maxAge, Duration recoveryAge, Duration retentionAge) {
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4), task->{
            Thread thread=new Thread(task,"prosperity-npc-publication");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        try {
            controller=new OccupancyPublicationController(directory,worker,source,clock,monotonic,
                    interval,maxAge,recoveryAge,retentionAge);
        } catch(RuntimeException | Error failure) {worker.shutdownNow();throw failure;}
    }

    public void configure(List<Approval> approvals) {main();controller.configure(approvals);}
    public void tick() {main();if(shutdown==null)controller.tick();}
    public Optional<PublicOccupancySnapshot> current() {main();return controller.current();}
    public Optional<PublicOccupancySnapshot> readPublic() {return controller.readPublic();}
    /** TokenSource transfers ownership of its returned bytes, which are wiped after startup. */
    public void startTransport(InetSocketAddress bind,TokenSource tokens) {
        main();
        if(shutdown!=null || !transportStatus.equals("DISABLED"))throw new IllegalStateException("Transport already started or stopped");
        java.util.Objects.requireNonNull(bind);java.util.Objects.requireNonNull(tokens);
        if(bind.isUnresolved() || !bind.getAddress().isLoopbackAddress())throw new IllegalArgumentException("Loopback bind required");
        transportStatus="STARTING";
        try {worker.execute(()->{
            byte[] token=null;boolean ready=false;
            try {
                if(stopping)return;
                token=tokens.read();
                if(stopping)return;
                transport=new OccupancyHttpServer(bind,token,this::readPublic);
                ready=true;
            } catch(Exception failure) {transportStatus="FAILED";}
            catch(Error fatal) {transportStatus="FAILED";throw fatal;}
            finally {if(token!=null)Arrays.fill(token,(byte)0);if(ready)transportStatus="READY";}
        });} catch(RuntimeException rejected) {transportStatus="FAILED";}
    }
    public String transportStatus() {main();return transportStatus;}
    public Optional<InetSocketAddress> transportAddress() {
        main();return !stopping && transportStatus.equals("READY") ? Optional.of(transport.address()) : Optional.empty();
    }
    public String status() {
        main();
        if(shutdown!=null)return shutdown.isCompletedExceptionally() ? "CLEANUP_FAILED"
                : shutdown.isDone() ? "STOPPED" : "DRAINING";
        return controller.state().name()+" / "+controller.refreshDisposition().map(Enum::name).orElse("NO_READ");
    }

    /** Completion is read-only to callers; shutdown never interrupts an active source transaction. */
    public CompletionStage<Void> shutdown() {
        main();
        if(shutdown!=null)return shutdown.minimalCompletionStage();
        shutdown=new CompletableFuture<>();
        stopping=true;
        var cleanup=controller.shutdownTask();
        try {
            worker.execute(()->{
                try {
                    try {if(transport!=null)transport.close();transportStatus="STOPPED";}
                    finally {cleanup.call();}
                    shutdown.complete(null);
                }
                catch(Throwable failure) {
                    shutdown.completeExceptionally(failure);
                    if(failure instanceof Error fatal)throw fatal;
                }
            });
        } catch(RuntimeException rejected) {shutdown.completeExceptionally(rejected);}
        finally {worker.shutdown();}
        return shutdown.minimalCompletionStage();
    }

    private void main() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Publication host owner thread required");
    }
}
