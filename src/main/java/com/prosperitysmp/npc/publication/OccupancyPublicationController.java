package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/**
 * Owner-thread tick/lifecycle coordinator. Uses a supplied bounded off-thread executor; creates no
 * threads or Bukkit tasks. Tick must continue during draining, including after close, until drained().
 * The executor and database must remain alive until drained; current() is never served during draining.
 */
public final class OccupancyPublicationController implements AutoCloseable {
    public enum State { DISABLED, ACTIVE, DRAINING, CLOSE_FAILED, CLOSED }
    private final Thread owner = Thread.currentThread();
    private final Path directory;
    private final Executor worker;
    private final OccupancyRefreshPump.Reader source;
    private final Clock clock;
    private final LongSupplier monotonic;
    private final Duration maxAge, recoveryAge, retentionAge;
    private final long interval;
    private List<Approval> desired = List.of();
    private OccupancyPublicationSession session, closing;
    private OccupancyRefreshPump pump;
    private volatile OccupancyRefreshPump readable;
    private volatile Boolean closeResult;
    private boolean closeQueued, closed, attempted, closeAttempted;
    private long lastAttempt, lastCloseAttempt;
    private State state = State.DISABLED;

    public OccupancyPublicationController(Path directory, Executor worker, OccupancyRefreshPump.Reader source,
            Clock clock, LongSupplier monotonic, Duration refreshInterval, Duration maxAge,
            Duration recoveryAge, Duration retentionAge) {
        this.directory = Objects.requireNonNull(directory); this.worker = Objects.requireNonNull(worker);
        this.source = Objects.requireNonNull(source); this.clock = Objects.requireNonNull(clock);
        this.monotonic = Objects.requireNonNull(monotonic);
        for(Duration value : Arrays.asList(refreshInterval,maxAge,recoveryAge,retentionAge))
            if(value==null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("Invalid publication policy");
        try { interval = refreshInterval.toNanos(); maxAge.toNanos(); }
        catch(ArithmeticException overflow) { throw new IllegalArgumentException("Publication policy overflow"); }
        if(refreshInterval.compareTo(maxAge)>=0) throw new IllegalArgumentException("Refresh interval must be below max age");
        this.maxAge=maxAge; this.recoveryAge=recoveryAge; this.retentionAge=retentionAge;
    }

    /** Revoke first, even when replacement validation fails. Only the most recent desired scope is kept. */
    public void configure(List<Approval> replacement) {
        main(); if(closed) throw new IllegalStateException("Publication controller closed");
        desired=List.of(); retire();
        desired=OccupancyRefreshPump.checkedScope(replacement);
    }

    public void tick() {
        main();
        Boolean result=closeResult;
        if(result!=null) {
            closeResult=null; closeQueued=false;
            if(result) { closing=null; closeAttempted=false; }
            else state=State.CLOSE_FAILED;
        }
        long now=monotonic.getAsLong();
        if(closing!=null) {
            if(!closeQueued && due(now,lastCloseAttempt,closeAttempted)) queueClose(now);
            return;
        }
        if(closed) {state=State.CLOSED;return;}
        if(desired.isEmpty()) {state=State.DISABLED;return;}
        if(session==null) {
            session=new OccupancyPublicationSession(directory,desired,source,clock,monotonic,maxAge,recoveryAge,retentionAge);
            pump=new OccupancyRefreshPump(worker,session,clock,monotonic,maxAge);
            pump.configure(desired); attempted=false;
            readable=pump;
        }
        state=State.ACTIVE; pump.tick();
        if(!pump.busy() && due(now,lastAttempt,attempted)) {
            attempted=true;lastAttempt=now;pump.request();
        }
    }

    public Optional<PublicOccupancySnapshot> current() {
        main(); return closed || closing!=null || pump==null ? Optional.empty() : pump.current();
    }
    /** Public schema only, with freshness/revocation checked on every off-thread read. */
    public Optional<PublicOccupancySnapshot> readPublic() {
        OccupancyRefreshPump selected=readable;
        return selected==null ? Optional.empty() : selected.readPublic();
    }
    public State state() {main();return state;}
    public Optional<OccupancyRefreshPump.Disposition> refreshDisposition() {
        main();return pump==null ? Optional.empty() : Optional.of(pump.disposition());
    }
    public boolean drained() {main();return closed && closing==null && session==null;}

    @Override public void close() {
        main(); if(closed)return;
        closed=true;desired=List.of();retire();
        if(closing==null)state=State.CLOSED;
    }
    /**
     * Terminal host shutdown: revoke on the owner, then submit the returned work off-thread.
     * Successful task completion (not merely submission) permits releasing the source database.
     * No further owner ticks are required. Submission or cleanup failure must not be treated as
     * drained. Submission can be retried; a session cleanup failure remains latched for operator
     * review. This does not shut down the supplied executor. drained()/state() remain tick-driven;
     * the task's successful completion is the terminal host signal instead.
     */
    public Callable<Void> shutdownTask() {
        main(); closed=true; desired=List.of(); retire(false);
        OccupancyPublicationSession target=closing;
        return () -> {
            if(Thread.currentThread()==owner)
                throw new IllegalStateException("Publication shutdown requires worker thread");
            if(target!=null)target.close();
            return null;
        };
    }
    private void retire() {
        retire(true);
    }
    private void retire(boolean submitClose) {
        readable=null;
        if(pump!=null) {pump.close();pump=null;}
        if(session!=null) {
            session.cancel();closing=session;session=null;closeAttempted=false;
        }
        if(closing!=null) {
            state=State.DRAINING;long now=monotonic.getAsLong();
            if(submitClose && !closeQueued && due(now,lastCloseAttempt,closeAttempted))queueClose(now);
        }
        else state=closed ? State.CLOSED : State.DISABLED;
    }
    private void queueClose(long now) {
        closeAttempted=true;lastCloseAttempt=now;closeQueued=true;
        OccupancyPublicationSession target=closing;
        try {
            worker.execute(()->{
                boolean success=false;
                try {target.close();success=true;} catch(Exception ignored) { /* bounded lifecycle status only */ }
                finally {closeResult=success;}
            });
        } catch(RuntimeException rejected) {closeQueued=false;state=State.CLOSE_FAILED;}
    }
    private boolean due(long now,long previous,boolean hasPrevious) {
        return !hasPrevious || now-previous<0 || now-previous>=interval;
    }
    private void main() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Publication controller owner thread required");
    }
}
