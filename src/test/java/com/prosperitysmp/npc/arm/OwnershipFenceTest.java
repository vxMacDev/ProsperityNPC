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
}
