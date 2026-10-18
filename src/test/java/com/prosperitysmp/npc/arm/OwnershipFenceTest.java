package com.prosperitysmp.npc.arm;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipFenceTest {
    private ArmAdapter.OwnerRead owner() {
        Object identity = new Object();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (p, m, a) -> { throw new AssertionError("No Bukkit access expected"); });
        return new ArmAdapter.OwnerRead(new UUID(0, 1), "c001", new UUID(0, 2),
                identity, identity, identity, identity, identity, world);
    }
    @Test void heldTransactionExcludesWritersUntilExplicitReconciledRelease() {
        var f = new OwnershipFence(() -> true); var o = owner(); var lease = f.acquire(o);
        lease.validate(o);
        assertThrows(IllegalStateException.class, () -> f.beginWrite(o.worldId(), o.regionId()));
        lease.releaseReconciled();
        f.beginWrite(o.worldId(), o.regionId()).run();
        assertThrows(IllegalStateException.class, () -> lease.validate(o));
    }
    @Test void nestedWriterCannotReleaseOuterExclusion() {
        var f = new OwnershipFence(() -> true); var o = owner();
        var outer = f.beginWrite(o.worldId(), o.regionId());
        var inner = f.beginWrite(o.worldId(), o.regionId()); inner.run(); inner.run();
        assertThrows(IllegalStateException.class, () -> f.acquire(o));
        outer.run(); f.acquire(o).releaseReconciled();
    }
    @Test void exclusiveLeaseHoldsTheRegionButAuthorizesNothing() {
        var f = new OwnershipFence(() -> true); var o = owner();
        var held = f.acquire(o);
        assertThrows(IllegalStateException.class, () -> f.acquireExclusive(o.worldId(), o.regionId()), "busy while a transfer holds it");
        held.releaseReconciled();
        var exclusive = f.acquireExclusive(o.worldId(), o.regionId());
        assertThrows(IllegalStateException.class, () -> f.acquire(o), "a payment cannot start meanwhile");
        assertThrows(IllegalStateException.class, () -> f.beginWrite(o.worldId(), o.regionId()), "nor an ARM write");
        assertThrows(IllegalStateException.class, () -> f.acquireExclusive(o.worldId(), o.regionId()));
        assertThrows(IllegalStateException.class, () -> exclusive.validate(o), "no owner authority to validate");
        exclusive.releaseReconciled();
        f.acquire(o).releaseReconciled();
        var writer = f.beginWrite(o.worldId(), o.regionId());
        assertThrows(IllegalStateException.class, () -> f.acquireExclusive(o.worldId(), o.regionId()), "busy during a write");
        writer.run();
        f.acquireExclusive(o.worldId(), o.regionId()).releaseReconciled();
    }
    @Test void shutdownRejectsNewWorkButAllowsReconciledCleanup() {
        var f = new OwnershipFence(() -> true); var o = owner(); var lease = f.acquire(o);
        f.stop();
        assertThrows(IllegalStateException.class, () -> lease.validate(o));
        assertThrows(IllegalStateException.class, () -> f.beginWrite(o.worldId(), o.regionId()));
        lease.releaseReconciled();
        assertThrows(IllegalStateException.class, () -> f.acquire(o));
    }

    @Test void aTokenFromAnotherBackendIsNeverTheSameIncarnation() {
        var o = owner();
        // Same world, region and owner, but not an ARM read: it must not validate an ARM lease.
        var foreign = new com.prosperitysmp.npc.api.OwnershipToken() {
            @Override public UUID worldId() { return o.worldId(); }
            @Override public String regionId() { return o.regionId(); }
            @Override public UUID ownerId() { return o.ownerId(); }
            @Override public boolean sameIncarnation(com.prosperitysmp.npc.api.OwnershipToken other) { return true; }
        };
        assertFalse(o.sameIncarnation(foreign));
        assertFalse(o.sameIncarnation(null));
        assertTrue(o.sameIncarnation(o), "positive control");
        var f = new OwnershipFence(() -> true);
        var lease = f.acquire(o);
        assertThrows(IllegalStateException.class, () -> lease.validate(foreign));
        lease.validate(o);
    }

    @Test void externalChangeDuringAHeldLeaseFailsItsNextValidateButNeverThrows() {
        var f = new OwnershipFence(() -> true); var o = owner(); var lease = f.acquire(o);
        lease.validate(o);
        f.externalChange(o.worldId(), o.regionId());
        assertThrows(IllegalStateException.class, () -> lease.validate(o), "authority changed under the lease");
        lease.releaseReconciled();
        // Nobody holds it now: the change has nothing to invalidate and leaves no state behind.
        f.externalChange(o.worldId(), o.regionId());
        var next = f.acquire(o);
        next.validate(o);
        next.releaseReconciled();
    }
    @Test void externalChangeIsMainThreadOnlyAndRefusedAfterStop() {
        boolean[] main = {false};
        var f = new OwnershipFence(() -> main[0]); var o = owner();
        assertThrows(IllegalStateException.class, () -> f.externalChange(o.worldId(), o.regionId()));
        main[0] = true;
        f.stop();
        assertThrows(IllegalStateException.class, () -> f.externalChange(o.worldId(), o.regionId()));
    }
    @Test void isHeldMirrorsLeasesFromAnyThread() throws Exception {
        var f = new OwnershipFence(() -> true); var o = owner();
        assertFalse(f.isHeld(o.worldId(), o.regionId()));
        var lease = f.acquire(o);
        boolean[] seen = new boolean[1];
        Thread reader = new Thread(() -> seen[0] = f.isHeld(o.worldId(), o.regionId()));
        reader.start(); reader.join();
        assertTrue(seen[0], "an off-main reader sees the lease");
        lease.releaseReconciled();
        assertFalse(f.isHeld(o.worldId(), o.regionId()));
        var exclusive = f.acquireExclusive(o.worldId(), o.regionId());
        assertTrue(f.isHeld(o.worldId(), o.regionId()));
        exclusive.releaseReconciled();
        assertFalse(f.isHeld(o.worldId(), o.regionId()));
        var writer = f.beginWrite(o.worldId(), o.regionId());
        assertFalse(f.isHeld(o.worldId(), o.regionId()), "an ARM write is not a lease");
        writer.run();
    }
    @Test void markDirtyFromAnotherThreadHoldsValidateUntilTheMainHopApplies() throws Exception {
        var hops = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        Thread mainThread = Thread.currentThread();
        var f = new OwnershipFence(() -> Thread.currentThread() == mainThread, hops::add); var o = owner();
        var lease = f.acquire(o);
        Thread event = new Thread(() -> f.markDirty(o.worldId(), o.regionId()));
        event.start(); event.join();
        assertEquals(1, hops.size(), "one hop posted to main");
        assertThrows(IllegalStateException.class, () -> lease.validate(o), "refused while marked");
        hops.poll().run(); // the next main tick
        assertThrows(IllegalStateException.class, () -> lease.validate(o), "the hop moved the generation on");
        lease.releaseReconciled();
        var next = f.acquire(o);
        next.validate(o);
        next.releaseReconciled();
    }
    @Test void aMarkThatCannotBePostedStaysAndKeepsRefusing() {
        var f = new OwnershipFence(() -> true); var o = owner();
        var lease = f.acquire(o);
        assertThrows(IllegalStateException.class, () -> f.markDirty(o.worldId(), o.regionId()));
        assertThrows(IllegalStateException.class, () -> lease.validate(o));
        lease.releaseReconciled();
        var next = f.acquire(o);
        assertThrows(IllegalStateException.class, () -> next.validate(o), "fails closed until a restart");
    }
}
