package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.arm.OwnershipFence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The hooks a guard calls go straight to the ownership fence; guarded turns a throw into a reported failure. */
class FenceHooksTest {
    private static final UUID WORLD = new UUID(0, 71);
    private static final UUID OWNER = new UUID(0, 72);
    private final List<Runnable> hops = new ArrayList<>();
    private final OwnershipFence fence = new OwnershipFence(() -> true, hops::add);
    private final List<String> failures = new ArrayList<>();
    private final FenceHooks hooks = new FenceHooks(fence, (what, error) -> failures.add(what + ": " + error));

    private record Token(UUID worldId, String regionId, UUID ownerId) implements OwnershipToken {
        @Override public boolean sameIncarnation(OwnershipToken other) { return other instanceof Token; }
    }

    private final Token token = new Token(WORLD, "home-1", OWNER);

    @Test void bothArgumentsAreRequired() {
        assertThrows(NullPointerException.class, () -> new FenceHooks(null, (w, e) -> { }));
        assertThrows(NullPointerException.class, () -> new FenceHooks(fence, null));
    }

    @Test void aWriteIsRefusedWhileAPaymentHoldsTheHome() {
        var lease = fence.acquire(token);
        assertTrue(hooks.isHeld(WORLD, "home-1"));
        var refused = assertThrows(IllegalStateException.class, () -> hooks.beginWrite(WORLD, "home-1"));
        assertEquals("Property transaction or recovery in progress", refused.getMessage());
        lease.releaseReconciled();
        assertFalse(hooks.isHeld(WORLD, "home-1"));
        hooks.beginWrite(WORLD, "home-1").run();
    }

    @Test void aWriteInProgressKeepsPaymentsOut() {
        Runnable end = hooks.beginWrite(WORLD, "home-1");
        assertThrows(OwnershipFence.Busy.class, () -> fence.acquire(token));
        end.run();
        assertNotNull(fence.acquire(token));
    }

    @Test void anExternalChangeInvalidatesTheHeldLease() {
        var lease = fence.acquire(token);
        lease.validate(token);
        hooks.externalChange(WORLD, "home-1");
        var failure = assertThrows(IllegalStateException.class, () -> lease.validate(token));
        assertEquals("Property authority changed", failure.getMessage());
    }

    @Test void markDirtyInvalidatesTheHeldLeaseEvenAfterTheMainThreadHopRuns() {
        var lease = fence.acquire(token);
        hooks.markDirty(WORLD, "home-1");
        assertEquals(1, hops.size());
        assertThrows(IllegalStateException.class, () -> lease.validate(token));
        hops.getFirst().run();
        assertThrows(IllegalStateException.class, () -> lease.validate(token), "the hop moved the generation on");
        lease.releaseReconciled();
        var fresh = fence.acquire(token);
        fresh.validate(token);
    }

    @Test void guardedRunsTheBodyAndReportsNothingOnSuccess() {
        int[] ran = {0};
        hooks.guarded("veto", () -> ran[0]++);
        assertEquals(1, ran[0]);
        assertTrue(failures.isEmpty());
    }

    @Test void guardedReportsARuntimeFailureByName() {
        hooks.guarded("owner event", () -> { throw new IllegalArgumentException("bad region"); });
        assertEquals(List.of("owner event: java.lang.IllegalArgumentException: bad region"), failures);
    }

    @Test void guardedReportsLinkageAndAssertionErrors() {
        hooks.guarded("one", () -> { throw new NoClassDefFoundError("com/vendor/Event"); });
        hooks.guarded("two", () -> { throw new AssertionError("broken"); });
        assertEquals(List.of("one: java.lang.NoClassDefFoundError: com/vendor/Event", "two: java.lang.AssertionError: broken"),
                failures);
    }

    @Test void guardedRethrowsVirtualMachineErrors() {
        assertThrows(OutOfMemoryError.class, () -> hooks.guarded("x", () -> { throw new OutOfMemoryError("heap"); }));
        assertThrows(StackOverflowError.class, () -> hooks.guarded("x", () -> { throw new StackOverflowError(); }));
        assertTrue(failures.isEmpty());
    }
}
