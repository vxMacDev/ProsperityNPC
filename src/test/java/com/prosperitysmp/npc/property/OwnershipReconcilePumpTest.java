package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipReconcilePumpTest {
    private static Property property(int n) {
        return new Property(new UUID(0,n), new UUID(1,1), "c"+n, "central", "standard",
                1,100,100,new UUID(2,1),0,90,true);
    }
    private static final class Harness implements OwnershipReconcilePump.Host, Executor {
        final Map<UUID,Property> cache = new HashMap<>();
        final Set<UUID> uncertain = new HashSet<>();
        final List<OwnershipReconcilePump.Result> results = new ArrayList<>();
        long nanos;
        final OwnershipVisibility visibility = new OwnershipVisibility(() -> nanos);
        Runnable work;
        int observations, writes;
        UUID owner = new UUID(2,2);
        com.prosperitysmp.npc.arm.OwnershipFence fence;
        OwnershipReconcilePump.Presence presence = OwnershipReconcilePump.Presence.PRESENT;
        boolean fail, fallback, reject, same;
        final OwnershipReconcilePump pump = new OwnershipReconcilePump(this, (p,o) -> {
            writes++;
            if(fail) throw new java.sql.SQLException("fixture");
            if(same) return new OwnershipReconcilePump.Write(p, true); // SQL already held exactly this record
            return new OwnershipReconcilePump.Write(new Property(p.propertyId(), p.worldUuid(), p.regionId(),
                    p.marketId(), p.classKey(), p.capacity(), p.referenceRentCents(), 123, o,
                    Objects.equals(o,p.ownerUuid()) ? p.ownershipEpoch() : p.ownershipEpoch()+1,
                    p.conditionScore(), p.enabled()), !fallback);
        }, this);
        Harness(int count) { for(int i=1;i<=count;i++) cache.put(property(i).propertyId(),property(i)); }
        public Property current(UUID id) { return cache.get(id); }
        public com.prosperitysmp.npc.arm.OwnershipFence fence() { return fence; }
        final List<String> stuck = new ArrayList<>();
        final Map<UUID,Integer> refreshes = new HashMap<>();
        Set<UUID> writingAtSubmit;
        public void stuck(UUID id, int retries) { stuck.add(id + "@" + refreshes.get(id) + "/" + retries); }
        public OwnershipReconcilePump.Observation observe(Property p) {
            observations++; return new OwnershipReconcilePump.Observation(presence,owner);
        }
        public void refreshing(UUID id) { refreshes.merge(id,1,Integer::sum); if(cache.containsKey(id)) uncertain.add(id); }
        public void uncertain(UUID id) { refreshing(id); visibility.invalidate(id); }
        public void rebase(Property old, Property fresh, boolean verified) {
            if(cache.get(old.propertyId()) == old) {
                cache.put(old.propertyId(),fresh);
                if(verified) { uncertain.remove(old.propertyId()); visibility.verified(old.propertyId()); }
                else { uncertain.add(old.propertyId()); visibility.invalidate(old.propertyId()); }
            }
        }
        public void execute(Runnable task) {
            writingAtSubmit = new HashSet<>();
            for(UUID id : cache.keySet()) if(pump.writing(id)) writingAtSubmit.add(id);
            if(reject) throw new java.util.concurrent.RejectedExecutionException();
            assertNull(work,"No queued SQL backlog permitted"); work=task;
        }
        void complete() { Runnable r=work;work=null;assertNotNull(r);r.run(); }
        OwnershipReconcilePump.Acceptance request() { return pump.request(cache.values(),results::add); }
    }
    @Test void acceptedThenBusyAndTruthfulChangedCount() {
        Harness h=new Harness(1);
        assertEquals(OwnershipReconcilePump.Acceptance.ACCEPTED,h.request());
        assertEquals(1,h.uncertain.size());
        assertEquals(OwnershipReconcilePump.Acceptance.BUSY,h.request());
        h.pump.tick(); assertEquals(0,h.writes); h.complete();
        assertTrue(h.results.isEmpty()); h.pump.tick();
        assertEquals(new OwnershipReconcilePump.Result(1,1,0,0,0,false),h.results.getFirst());
        assertTrue(h.uncertain.isEmpty());
    }
    @Test void evenUnchangedObservationWritesAndRebases() {
        Harness h=new Harness(1);h.owner=property(1).ownerUuid();h.request();h.pump.tick();h.complete();h.pump.tick();
        assertEquals(1,h.writes);assertEquals(0,h.results.getFirst().changed());
        assertEquals(0,h.cache.get(property(1).propertyId()).ownershipEpoch());
        assertEquals(123,h.cache.get(property(1).propertyId()).askingRentCents());
    }
    @Test void rejectsOverflowWithoutPartialWork() {
        Harness h=new Harness(513);assertEquals(OwnershipReconcilePump.Acceptance.OVERFLOW,h.request());
        h.pump.tick();assertNull(h.work);assertTrue(h.results.isEmpty());
    }
    @Test void acceptsExactLimitAndCapsCapturesEachTick() {
        Harness h=new Harness(512);assertEquals(OwnershipReconcilePump.Acceptance.ACCEPTED,h.request());
        for(int i=0;i<512;i++) {
            int before=h.observations;h.pump.tick();assertTrue(h.observations-before<=2);
            h.complete();
        }
        h.pump.tick();assertEquals(512,h.results.getFirst().checked());
    }
    @Test void neverQueuesWhileWorkerPending() {
        Harness h=new Harness(8);h.request();h.pump.tick();
        for(int i=0;i<100;i++)h.pump.tick();
        assertEquals(1,h.observations);assertEquals(0,h.writes);
    }
    @Test void cancelledGenerationCannotRebaseAfterAba() {
        Harness h=new Harness(1);h.request();h.pump.tick();Property original=h.cache.get(property(1).propertyId());
        h.pump.invalidate();h.cache.put(original.propertyId(),original.withOwner(new UUID(2,3),2));
        h.cache.put(original.propertyId(),original);
        assertEquals(OwnershipReconcilePump.Acceptance.BUSY,h.request());
        h.complete();h.pump.tick();
        assertSame(original,h.cache.get(original.propertyId()));assertTrue(h.results.getFirst().cancelled());
        assertTrue(h.uncertain.contains(original.propertyId()));
        assertEquals(OwnershipReconcilePump.Acceptance.ACCEPTED,h.request());
    }
    @Test void removalCannotBeResurrected() {
        Harness h=new Harness(1);h.request();h.pump.tick();h.cache.clear();h.complete();h.pump.tick();
        assertTrue(h.cache.isEmpty());assertEquals(1,h.results.getFirst().skipped());
    }
    @Test void equalButReplacedCacheObjectRejectsCompletion() {
        Harness h=new Harness(1);h.request();h.pump.tick();h.cache.put(property(1).propertyId(),property(1));
        h.complete();h.pump.tick();assertEquals(1,h.results.getFirst().skipped());
        assertEquals(0,h.cache.get(property(1).propertyId()).ownershipEpoch());
    }
    @Test void failedWorkerReportsFailureNotChanged() {
        Harness h=new Harness(1);h.fail=true;h.request();h.pump.tick();h.complete();h.pump.tick();
        assertEquals(new OwnershipReconcilePump.Result(0,0,0,0,1,false),h.results.getFirst());
        assertEquals(1,h.uncertain.size());
    }
    @Test void fallbackRepairsCacheButCannotClaimSuccess() {
        Harness h=new Harness(1);h.fallback=true;h.request();h.pump.tick();h.complete();h.pump.tick();
        assertEquals(h.owner,h.cache.get(property(1).propertyId()).ownerUuid());
        assertEquals(1,h.results.getFirst().failed());assertEquals(0,h.results.getFirst().changed());
        assertEquals(1,h.uncertain.size());
    }
    @Test void shutdownRejectsLateCompletionAndNewRequest() {
        Harness h=new Harness(1);h.request();h.pump.tick();h.pump.close();h.complete();h.pump.tick();
        assertEquals(0,h.cache.get(property(1).propertyId()).ownershipEpoch());
        assertEquals(OwnershipReconcilePump.Acceptance.CLOSED,h.request());
        assertEquals(1,h.results.size());assertTrue(h.results.getFirst().cancelled());
    }
    @Test void unavailableWorldIsUnverifiedWithoutSql() {
        Harness h=new Harness(1);h.presence=OwnershipReconcilePump.Presence.UNAVAILABLE;
        h.request();h.pump.tick();h.pump.tick();assertNull(h.work);
        assertEquals(1,h.results.getFirst().skipped());assertEquals(1,h.uncertain.size());
    }
    @Test void missingRegionIsCountedSeparately() {
        Harness h=new Harness(1);h.presence=OwnershipReconcilePump.Presence.MISSING;
        h.request();h.pump.tick();h.pump.tick();assertEquals(1,h.results.getFirst().missing());
    }
    @Test void changedArmAfterSqlFailsVerification() {
        Harness h=new Harness(1);h.request();h.pump.tick();h.complete();h.owner=new UUID(2,5);h.pump.tick();
        assertEquals(1,h.results.getFirst().failed());assertEquals(1,h.uncertain.size());
    }
    @Test void unavailableArmAfterSqlFailsVerification() {
        Harness h=new Harness(1);h.request();h.pump.tick();h.complete();
        h.presence=OwnershipReconcilePump.Presence.UNAVAILABLE;h.pump.tick();
        assertEquals(1,h.results.getFirst().failed());assertEquals(1,h.uncertain.size());
    }
    @Test void executorRejectionCompletesHonestly() {
        Harness h=new Harness(1);h.reject=true;h.request();h.pump.tick();h.pump.tick();
        assertEquals(1,h.results.getFirst().failed());assertEquals(0,h.writes);
    }
    @Test void emptyPassCompletesOnTickNotInsideRequest() {
        Harness h=new Harness(0);h.request();assertTrue(h.results.isEmpty());h.pump.tick();
        assertEquals(new OwnershipReconcilePump.Result(0,0,0,0,0,false),h.results.getFirst());
    }
    @Test void routineDelayedRefreshPreservesDisplayButNotAuthorityUntilExactTtl() {
        Harness h=new Harness(1);h.owner=property(1).ownerUuid();
        h.request();h.pump.tick();h.complete();h.pump.tick();
        UUID id=property(1).propertyId();assertFalse(h.visibility.unavailable(id));
        h.request();h.pump.tick();assertTrue(h.uncertain.contains(id));
        h.nanos=OwnershipVisibility.LEASE_NANOS-1;assertFalse(h.visibility.unavailable(id));
        h.nanos++;assertTrue(h.visibility.unavailable(id));assertNotNull(h.work);
    }
    @Test void changedOwnerHidesBeforeWorkerRuns() {
        Harness h=new Harness(1);h.visibility.verified(property(1).propertyId());
        h.request();assertFalse(h.visibility.unavailable(property(1).propertyId()));
        h.pump.tick();assertTrue(h.visibility.unavailable(property(1).propertyId()));assertEquals(0,h.writes);
    }
    @Test void failedRoutineWorkerInvalidatesRecentDisplay() {
        Harness h=new Harness(1);h.owner=property(1).ownerUuid();h.fail=true;
        h.visibility.verified(property(1).propertyId());h.request();h.pump.tick();
        assertFalse(h.visibility.unavailable(property(1).propertyId()));
        h.complete();h.pump.tick();assertTrue(h.visibility.unavailable(property(1).propertyId()));
    }
    @Test void cancelledRoutineRefreshPreservesDisplayLease() {
        Harness h=new Harness(1);h.owner=property(1).ownerUuid();
        h.visibility.verified(property(1).propertyId());h.request();h.pump.tick();h.pump.invalidate();
        assertFalse(h.visibility.unavailable(property(1).propertyId()));
        h.complete();h.pump.tick();assertFalse(h.visibility.unavailable(property(1).propertyId()));
        assertTrue(h.uncertain.contains(property(1).propertyId()));
    }
    @Test void unrelatedAndCancelledWorldEventsLeaveGenerationIntact() {
        Harness h=new Harness(1);h.owner=property(1).ownerUuid();h.request();h.pump.tick();
        for(var ids : List.of(OwnershipVisibility.affected(h.cache.values(),new UUID(9,9),false),
                OwnershipVisibility.affected(h.cache.values(),property(1).worldUuid(),true))) {
            if(!ids.isEmpty())h.pump.invalidate();
        }
        h.complete();h.pump.tick();assertFalse(h.results.getFirst().cancelled());
        assertEquals(1,h.results.getFirst().checked());
    }

    // ---- ownership fence: never replace the cached record of a home whose lease is held

    private static com.prosperitysmp.npc.arm.OwnershipFence.Lease hold(Harness h, Property p) {
        return h.fence.acquireExclusive(p.worldUuid(), p.regionId());
    }
    private static boolean free(Harness h, Property p) {
        try { hold(h, p).releaseReconciled(); return true; }
        catch (com.prosperitysmp.npc.arm.OwnershipFence.Busy busy) { return false; }
    }
    /** Ticks until a SQL hop is queued (or the limit), so a deferred retry can be reached. -1: none queued. */
    private static int ticksUntilWork(Harness h, int limit) {
        for (int i = 1; i <= limit; i++) { h.pump.tick(); if (h.work != null) return i; }
        return -1;
    }
    private static final int RETRY = (int) OwnershipReconcilePump.DEFERRED_RETRY_TICKS + 2;

    @Test void heldAtDispatchIsDeferredWithoutSqlThenRebasedAfterRelease() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId());
        var payment = hold(h, admitted); // a payment between its DEBIT and its CREDIT
        h.request(); h.pump.tick();
        assertNull(h.work, "no SQL hop while held"); assertEquals(0, h.observations); assertEquals(0, h.writes);
        h.pump.tick();
        assertEquals(new OwnershipReconcilePump.Result(0, 0, 0, 0, 0, false, 1), h.results.getFirst());
        assertSame(admitted, h.cache.get(admitted.propertyId()), "the payment's admitted record is untouched");
        assertEquals(Set.of(admitted.propertyId()), h.pump.deferredIds());
        assertTrue(h.uncertain.contains(admitted.propertyId()), "held fail-closed until reconciled");

        // Still held: each retry costs one fence check and touches nothing.
        for (int round = 0; round < 3; round++) {
            assertEquals(-1, ticksUntilWork(h, RETRY));
            assertSame(admitted, h.cache.get(admitted.propertyId()));
        }
        assertEquals(0, h.writes); assertEquals(0, h.observations);
        assertEquals(1, h.results.size(), "retries never call an external callback");

        payment.releaseReconciled();
        int waited = ticksUntilWork(h, RETRY);
        assertTrue(waited > 0, "retried after the release");
        h.complete(); h.pump.tick();
        Property rebased = h.cache.get(admitted.propertyId());
        assertNotSame(admitted, rebased); assertEquals(123, rebased.askingRentCents(), "the SQL record is installed");
        assertTrue(h.pump.deferredIds().isEmpty());
        assertTrue(free(h, admitted), "the pump holds no lease afterwards");
        h.pump.tick();
        assertEquals(-1, ticksUntilWork(h, 3 * RETRY), "nothing left to retry");
        assertEquals(1, h.writes);
    }

    @Test void leaseTakenDuringTheSqlHopBlocksTheRebase() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId());
        h.request(); h.pump.tick();
        assertNotNull(h.work, "dispatched while free");
        assertTrue(free(h, admitted), "the pump never holds a lease across the SQL hop, so a batch can take every home");
        var payment = hold(h, admitted); // the payment starts from the cached record while the hop is in flight
        h.complete(); h.pump.tick();
        assertSame(admitted, h.cache.get(admitted.propertyId()), "no swap between DEBIT and CREDIT");
        h.pump.tick();
        var r = h.results.getFirst();
        assertEquals(1, r.deferred()); assertEquals(0, r.checked()); assertEquals(0, r.failed());
        assertEquals(1, h.writes);
        assertEquals(-1, ticksUntilWork(h, RETRY), "still held: the retry writes nothing");
        assertSame(admitted, h.cache.get(admitted.propertyId()));

        payment.releaseReconciled(); // CREDIT done, lease released
        assertTrue(ticksUntilWork(h, RETRY) > 0);
        h.complete(); h.pump.tick();
        assertEquals(123, h.cache.get(admitted.propertyId()).askingRentCents());
        assertTrue(free(h, admitted));
    }

    /** With nothing held, the same pass rebases on the first pass and defers nothing. */
    @Test void unheldHomeIsRebasedOnTheFirstPass() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property cached = h.cache.get(property(1).propertyId());
        h.request(); h.pump.tick(); h.complete(); h.pump.tick(); h.pump.tick();
        assertEquals(new OwnershipReconcilePump.Result(1, 1, 0, 0, 0, false, 0), h.results.getFirst());
        assertNotSame(cached, h.cache.get(cached.propertyId()));
        assertEquals(123, h.cache.get(cached.propertyId()).askingRentCents());
        assertTrue(h.pump.deferredIds().isEmpty());
        assertTrue(free(h, cached));
    }

    /** Without a fence the same interleaving does swap the record under the lease, so the fenced tests above are real. */
    @Test void unfencedPumpSwapsTheRecordUnderAPaymentLease() {
        Harness h = new Harness(1);
        var fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId());
        var payment = fence.acquireExclusive(admitted.worldUuid(), admitted.regionId());
        h.request(); h.pump.tick(); h.complete(); h.pump.tick();
        assertNotSame(admitted, h.cache.get(admitted.propertyId()), "without a fence the record is swapped under the lease");
        assertNotEquals(admitted, h.cache.get(admitted.propertyId()));
        payment.releaseReconciled();
    }

    @Test void stoppedCoordinatorWritesNothingAndReplacesNothing() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property cached = h.cache.get(property(1).propertyId());
        h.fence.stop();
        h.request(); h.pump.tick(); assertNull(h.work); h.pump.tick();
        assertEquals(new OwnershipReconcilePump.Result(0, 0, 0, 0, 1, false, 0), h.results.getFirst());
        assertSame(cached, h.cache.get(cached.propertyId())); assertEquals(0, h.writes);
        assertTrue(h.pump.deferredIds().isEmpty(), "a coordinator failure is not retried as busy");
    }

    @Test void stoppedCoordinatorAtCompletionKeepsTheRecord() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property cached = h.cache.get(property(1).propertyId());
        h.request(); h.pump.tick(); h.fence.stop(); h.complete(); h.pump.tick(); h.pump.tick();
        assertSame(cached, h.cache.get(cached.propertyId()));
        assertEquals(1, h.results.getFirst().failed()); assertTrue(h.uncertain.contains(cached.propertyId()));
    }

    @Test void cancelledPassKeepsDeferredHomesForTheRetry() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId());
        var payment = hold(h, admitted);
        h.request(); h.pump.tick();
        assertEquals(Set.of(admitted.propertyId()), h.pump.deferredIds());
        h.pump.invalidate();
        assertTrue(h.results.getFirst().cancelled());
        assertEquals(Set.of(admitted.propertyId()), h.pump.deferredIds(), "cancelling the pass does not forget the home");
        payment.releaseReconciled();
        assertTrue(ticksUntilWork(h, RETRY) > 0);
        h.complete(); h.pump.tick();
        assertEquals(123, h.cache.get(admitted.propertyId()).askingRentCents());
        assertTrue(free(h, admitted));
    }

    @Test void externalRequestCoversDeferredHomesAndRetryPassYieldsToNone() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId());
        var payment = hold(h, admitted);
        h.request(); h.pump.tick(); h.pump.tick();
        payment.releaseReconciled();
        // A full pass before the retry is due reaches the home itself and clears it from the retry set.
        assertEquals(OwnershipReconcilePump.Acceptance.ACCEPTED, h.request());
        assertEquals(Set.of(admitted.propertyId()), h.pump.deferredIds(), "still waiting until the pass reaches it");
        h.pump.tick();
        assertTrue(h.pump.deferredIds().isEmpty());
        h.complete(); h.pump.tick(); h.pump.tick();
        assertEquals(1, h.results.get(1).checked());
        assertEquals(-1, ticksUntilWork(h, 3 * RETRY), "no stale retry afterwards");
    }

    // ---- a deferred home leaves the retry set only when a pass reaches it

    @Test void cancelledPassBeforeReachingADeferredHomeKeepsItForTheRetry() {
        Harness h = new Harness(2); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property first = h.cache.get(property(1).propertyId()), second = h.cache.get(property(2).propertyId());
        var payment = hold(h, second);
        h.pump.request(List.of(second), h.results::add); h.pump.tick(); h.pump.tick();
        assertEquals(Set.of(second.propertyId()), h.pump.deferredIds());
        payment.releaseReconciled();
        // A new pass lists the deferred home after another one and is cancelled before it gets there.
        assertEquals(OwnershipReconcilePump.Acceptance.ACCEPTED, h.pump.request(List.of(first, second), h.results::add));
        h.pump.tick(); assertNotNull(h.work, "the pass reached the first home only");
        h.pump.invalidate();
        assertEquals(Set.of(second.propertyId()), h.pump.deferredIds(), "an unreached deferred home is not forgotten");
        h.complete(); h.pump.tick();
        assertTrue(ticksUntilWork(h, RETRY) > 0, "the retry still reaches it");
        h.complete(); h.pump.tick();
        assertEquals(123, h.cache.get(second.propertyId()).askingRentCents());
        assertTrue(h.pump.deferredIds().isEmpty());
    }

    @Test void skippedDeferredHomeLeavesTheRetrySetWhenReached() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId());
        var payment = hold(h, admitted);
        h.request(); h.pump.tick(); h.pump.tick();
        payment.releaseReconciled();
        h.request(); h.cache.put(admitted.propertyId(), property(1)); // replaced before the pass reaches it
        assertEquals(Set.of(admitted.propertyId()), h.pump.deferredIds());
        h.pump.tick();
        assertTrue(h.pump.deferredIds().isEmpty(), "reached (skipped), so not retried");
    }

    // ---- writing(id) covers the in-flight SQL write exactly

    @Test void writingCoversTheSqlHopUntilItsCompletionIsConsumed() {
        Harness h = new Harness(2); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property p = h.cache.get(property(1).propertyId()), other = h.cache.get(property(2).propertyId());
        h.pump.request(List.of(p), h.results::add);
        assertFalse(h.pump.writing(p.propertyId()), "nothing submitted yet");
        h.pump.tick();
        assertEquals(Set.of(p.propertyId()), h.writingAtSubmit, "already writing when the task is submitted");
        assertTrue(h.pump.writing(p.propertyId())); assertFalse(h.pump.writing(other.propertyId()));
        assertFalse(h.pump.writing(null));
        h.complete();
        assertTrue(h.pump.writing(p.propertyId()), "written but not yet consumed on main");
        h.pump.tick();
        assertFalse(h.pump.writing(p.propertyId()));
    }

    @Test void writingEndsWhenTheDispatchIsRejected() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true); h.reject = true;
        h.request(); h.pump.tick();
        assertEquals(Set.of(property(1).propertyId()), h.writingAtSubmit);
        assertFalse(h.pump.writing(property(1).propertyId()));
    }

    @Test void invalidatedWriteStaysWritingUntilItsCompletionDrains() {
        Harness h = new Harness(1); UUID id = property(1).propertyId();
        h.request(); h.pump.tick(); h.pump.invalidate();
        assertTrue(h.pump.writing(id), "the cancelled write is still in flight (no fence: reported only)");
        h.pump.tick(); assertTrue(h.pump.writing(id));
        h.complete(); assertTrue(h.pump.writing(id));
        h.pump.tick();
        assertTrue(h.pump.writing(id), "consumed without a rebase: SQL may be ahead of the cache");
        assertTrue(h.pump.deferredIds().contains(id), "and the home is queued for a retry");
    }

    @Test void cancelledWriteIsRetriedAndStopsWritingOnceSqlsRowIsInstalled() {
        Harness h = new Harness(1); UUID id = property(1).propertyId(); Property original = h.cache.get(id);
        h.request(); h.pump.tick(); h.pump.invalidate(); h.complete(); h.pump.tick();
        assertSame(original, h.cache.get(id), "the cancelled completion installed nothing");
        assertTrue(h.pump.writing(id), "single-home admissions wait while SQL may be ahead");
        int waited = ticksUntilWork(h, RETRY);
        assertTrue(waited > 0, "the retry pass dispatched the home again");
        assertTrue(h.pump.writing(id));
        h.complete(); h.pump.tick();
        assertNotSame(original, h.cache.get(id), "the retry installed SQL's row");
        assertEquals(123, h.cache.get(id).askingRentCents());
        assertFalse(h.pump.writing(id)); assertTrue(h.pump.deferredIds().isEmpty());
        assertEquals(-1, ticksUntilWork(h, 3 * RETRY), "nothing left to retry");
    }

    @Test void cancelledWriteThatChangedNothingIsRetriedWithoutHoldingAdmissions() {
        Harness h = new Harness(1); UUID id = property(1).propertyId(); h.same = true;
        h.request(); h.pump.tick(); h.pump.invalidate(); h.complete(); h.pump.tick();
        assertFalse(h.pump.writing(id), "SQL returned the very record the pass held: not ahead");
        assertTrue(h.pump.deferredIds().contains(id), "the owed check is still retried");
        assertTrue(ticksUntilWork(h, RETRY) > 0);
    }

    @Test void deferredHomeDispatchedThenCancelledIsNotForgotten() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId()); UUID id = admitted.propertyId();
        var payment = hold(h, admitted);
        h.request(); h.pump.tick(); h.pump.tick();
        assertEquals(Set.of(id), h.pump.deferredIds());
        payment.releaseReconciled();
        assertTrue(ticksUntilWork(h, RETRY) > 0, "the retry pass dispatched it");
        assertFalse(h.pump.deferredIds().contains(id), "reached");
        h.pump.invalidate(); h.complete(); h.pump.tick();
        assertSame(admitted, h.cache.get(id));
        assertTrue(h.pump.deferredIds().contains(id), "a cancelled dispatch keeps the home queued");
        assertTrue(ticksUntilWork(h, RETRY) > 0);
        h.complete(); h.pump.tick();
        assertNotSame(admitted, h.cache.get(id)); assertFalse(h.pump.writing(id));
        assertTrue(free(h, admitted));
    }

    @Test void heldAtCompletionStaysWritingUntilTheRetryInstallsTheRow() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        Property admitted = h.cache.get(property(1).propertyId()); UUID id = admitted.propertyId();
        h.request(); h.pump.tick();
        var batch = hold(h, admitted); // a batch takes the home during the SQL hop
        h.complete(); h.pump.tick();
        assertSame(admitted, h.cache.get(id), "no rebase under the batch's lease");
        assertTrue(h.pump.writing(id), "SQL is ahead of the cache while the batch holds the home");
        batch.releaseReconciled();
        assertTrue(ticksUntilWork(h, RETRY) > 0);
        h.complete(); h.pump.tick();
        assertNotSame(admitted, h.cache.get(id)); assertFalse(h.pump.writing(id));
    }

    @Test void aheadHomeStaysQueuedWhenItsRetryCannotReadArm() {
        Harness h = new Harness(1); UUID id = property(1).propertyId(); Property original = h.cache.get(id);
        h.pump.markAhead(List.of(id));
        h.presence = OwnershipReconcilePump.Presence.UNAVAILABLE;
        int before = h.observations;
        assertEquals(-1, ticksUntilWork(h, RETRY), "ARM unavailable: reached, nothing dispatched");
        assertTrue(h.observations > before, "the retry pass did reach the home");
        assertTrue(h.pump.writing(id), "still ahead, so admissions still wait");
        assertTrue(h.pump.deferredIds().contains(id), "and it is queued again, not left for the 5 minute pass");
        h.presence = OwnershipReconcilePump.Presence.PRESENT;
        assertTrue(ticksUntilWork(h, RETRY) > 0);
        h.complete(); h.pump.tick();
        assertNotSame(original, h.cache.get(id)); assertFalse(h.pump.writing(id));
        assertTrue(h.pump.deferredIds().isEmpty());
    }

    @Test void aheadHomeThatNeverReadsWarnsOnceAfterTwelveRetries() {
        Harness h = new Harness(1); UUID id = property(1).propertyId();
        h.pump.markAhead(List.of(id));
        h.presence = OwnershipReconcilePump.Presence.MISSING; // a region removed in a map change
        for (int i = 0; i < OwnershipReconcilePump.STUCK_RETRIES + 5; i++) assertEquals(-1, ticksUntilWork(h, RETRY));
        assertEquals(1, h.stuck.size(), "one warning, not one per retry");
        assertTrue(h.stuck.getFirst().startsWith(id + "@"));
        assertTrue(h.pump.writing(id));
    }

    @Test void aheadHomeStaysQueuedWhenTheWorkerRefusesItsWrite() {
        Harness h = new Harness(1); UUID id = property(1).propertyId(); Property original = h.cache.get(id);
        h.pump.markAhead(List.of(id));
        h.reject = true; // the shared worker queue is full when the retry dispatches
        int before = h.observations;
        assertEquals(-1, ticksUntilWork(h, RETRY));
        assertTrue(h.observations > before, "the retry pass reached the home and tried to dispatch");
        assertTrue(h.pump.writing(id));
        assertTrue(h.pump.deferredIds().contains(id), "queued again, so health counts it and the retry continues");
        h.reject = false;
        assertTrue(ticksUntilWork(h, RETRY) > 0);
        h.complete(); h.pump.tick();
        assertNotSame(original, h.cache.get(id)); assertFalse(h.pump.writing(id));
    }

    @Test void homeNotAheadIsNotRequeuedWhenArmIsUnavailable() {
        Harness h = new Harness(1); UUID id = property(1).propertyId();
        h.presence = OwnershipReconcilePump.Presence.UNAVAILABLE;
        h.request(); h.pump.tick(); h.pump.tick();
        assertFalse(h.pump.writing(id));
        assertTrue(h.pump.deferredIds().isEmpty(), "an ordinary unavailable read waits for the next scheduled pass");
    }

    @Test void markAheadHoldsAdmissionsUntilAPassInstallsTheRow() {
        Harness h = new Harness(2); UUID id = property(1).propertyId(); Property original = h.cache.get(id);
        UUID uncached = new UUID(0, 99);
        h.pump.markAhead(List.of(id, uncached));
        assertTrue(h.pump.writing(id)); assertFalse(h.pump.writing(uncached), "a home not cached is ignored");
        assertFalse(h.pump.writing(property(2).propertyId()));
        assertEquals(Set.of(id), h.pump.deferredIds());
        assertTrue(ticksUntilWork(h, RETRY) > 0);
        h.complete(); h.pump.tick();
        assertNotSame(original, h.cache.get(id)); assertFalse(h.pump.writing(id));
    }

    @Test void closedPumpReportsWritingUntilTheWorkerFinishes() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        UUID id = property(1).propertyId();
        h.request(); h.pump.tick(); h.pump.close();
        assertTrue(h.pump.writing(id));
        h.pump.tick(); assertTrue(h.pump.writing(id), "a closed pump never consumes, so the worker decides");
        h.complete();
        assertFalse(h.pump.writing(id), "drained");
    }

    // ---- a home automatic retries keep finding busy is named once

    /** Ticks until stuck is told or the limit. */
    private static void ticksUntilStuck(Harness h, int limit) {
        for (int i = 0; i < limit && h.stuck.isEmpty(); i++) h.pump.tick();
    }

    @Test void twelveAutomaticRetriesInARowNameTheHomeOnce() {
        Harness h = new Harness(1); h.fence = new com.prosperitysmp.npc.arm.OwnershipFence(() -> true);
        UUID id = property(1).propertyId();
        var payment = hold(h, h.cache.get(id));
        h.request(); h.pump.tick(); h.pump.tick(); // the external pass defers it: not an automatic retry
        ticksUntilStuck(h, 20 * RETRY);
        // Each pass refreshes the home twice (start and deferral): 1 external pass + 12 automatic retries.
        assertEquals(List.of(id + "@" + 2 * 13 + "/" + OwnershipReconcilePump.STUCK_RETRIES), h.stuck);
        for (int i = 0; i < 30 * RETRY; i++) h.pump.tick();
        assertEquals(1, h.stuck.size(), "then quiet for that home");
        assertEquals(Set.of(id), h.pump.deferredIds(), "still retried");
        payment.releaseReconciled();
        assertTrue(ticksUntilWork(h, RETRY) > 0); h.complete(); h.pump.tick(); h.pump.tick();
        // Reached unheld: a later run of busy retries counts from zero and is named again.
        var again = hold(h, h.cache.get(id));
        h.refreshes.clear();
        h.request(); h.pump.tick(); h.pump.tick();
        h.stuck.clear(); ticksUntilStuck(h, 20 * RETRY);
        assertEquals(List.of(id + "@" + 2 * 13 + "/" + OwnershipReconcilePump.STUCK_RETRIES), h.stuck);
        again.releaseReconciled();
    }
}
