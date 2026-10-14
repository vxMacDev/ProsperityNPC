package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** Owner-ticked, round-robin HELD recovery. Exclusively owns its supplied worker; no external step/cancel calls. */
public final class ChronicleRecoveryHost {
    public enum Disposition { NOT_SCANNED, CHECKPOINTED, UNRESOLVED, FAILED, CANCELLED }
    public record Health(Approval scope,Disposition disposition) { }
    private final Thread owner=Thread.currentThread();
    private final ChronicleRecoveryWorker recovery;
    private final List<Approval> scopes;
    private final UUID generation;
    private final LongSupplier monotonic;
    private final long intervalNanos;
    private final int pageSize;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicReferenceArray<Disposition> outcomes;
    private volatile boolean fatal;
    private int next;
    private long lastDispatch;
    private boolean dispatched;
    private CompletableFuture<Void> shutdown;

    public ChronicleRecoveryHost(ChronicleRecoveryWorker recovery,List<Approval> scopes,UUID generation,
            Duration interval,int pageSize,LongSupplier monotonic) {
        this.recovery=Objects.requireNonNull(recovery);this.scopes=List.copyOf(scopes);
        this.generation=Objects.requireNonNull(generation);this.monotonic=Objects.requireNonNull(monotonic);
        if(scopes.isEmpty() || scopes.size()>512 || pageSize<1 || pageSize>16
                || interval.compareTo(Duration.ofSeconds(1))<0 || interval.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("Invalid Chronicle recovery bounds");
        var ids=new HashSet<UUID>();var regions=new HashSet<List<String>>();
        for(Approval scope:this.scopes)if(!ids.add(scope.propertyId())
                || !regions.add(List.of(scope.worldId().toString(),scope.regionId())))
            throw new IllegalArgumentException("Duplicate Chronicle recovery scope");
        this.pageSize=pageSize;intervalNanos=interval.toNanos();
        outcomes=new AtomicReferenceArray<>(scopes.size());
        for(int i=0;i<scopes.size();i++)outcomes.set(i,Disposition.NOT_SCANNED);
        // One recovery page may queue behind the previous task's finishing tail; reserve one shutdown slot.
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),task->{
            Thread thread=new Thread(task,"prosperity-npc-chronicle-recovery");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    /** Busy ticks are discarded, not queued. A failed property still yields to the next property. */
    public void tick() {
        main();if(shutdown!=null || fatal || busy.get())return;
        long now=monotonic.getAsLong();
        if(dispatched && now-lastDispatch<intervalNanos)return;
        if(!busy.compareAndSet(false,true))return;
        int selected=next;
        try {
            executor.execute(()->run(selected));
            next=(selected+1)%scopes.size();lastDispatch=now;dispatched=true;
        }catch(RuntimeException rejected){busy.set(false);fatal=true;outcomes.set(selected,Disposition.FAILED);}
    }

    private void run(int index) {
        try {
            var result=recovery.step(scopes.get(index),generation,pageSize);
            outcomes.set(index,!result.checkpointed() ? Disposition.CANCELLED
                    : result.unresolved()>0 ? Disposition.UNRESOLVED : Disposition.CHECKPOINTED);
        }catch(SQLException failure){outcomes.set(index,Disposition.FAILED);}
        catch(RuntimeException failure){outcomes.set(index,Disposition.FAILED);fatal=true;}
        catch(Error failure){outcomes.set(index,Disposition.FAILED);fatal=true;throw failure;}
        finally{busy.set(false);}
    }

    public List<Health> health() {
        main();var result=new ArrayList<Health>(scopes.size());
        for(int i=0;i<scopes.size();i++)result.add(new Health(scopes.get(i),outcomes.get(i)));
        return List.copyOf(result);
    }
    public boolean running(){main();return busy.get();}
    public boolean held(){main();return fatal;}

    /** No interrupt/rollback assumption. Await acknowledgment before closing the shared database. */
    public CompletionStage<Void> shutdown() {
        main();if(shutdown!=null)return shutdown.minimalCompletionStage();
        shutdown=new CompletableFuture<>();recovery.cancel();
        try {executor.execute(()->shutdown.complete(null));}
        catch(RuntimeException rejected){shutdown.completeExceptionally(rejected);}
        finally{executor.shutdown();}
        return shutdown.minimalCompletionStage();
    }
    private void main(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Chronicle host owner required");}
}
