package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.arm.OwnershipFence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The rent change step's exclusion leases over a real ownership fence. */
class DueRentLeasesTest {
    private static final UUID WORLD = new UUID(0, 51);
    private boolean main = true;
    private final OwnershipFence fence = new OwnershipFence(() -> main);

    private static Property home(int n) {
        return new Property(new UUID(1, n), WORLD, "home-" + n, "town", "small", 2, 10_000, 10_000,
                new UUID(2, n), 0, 100, true);
    }

    @ParameterizedTest
    @CsvSource({
            "0,1,false", "1,1,true", "1,29,true", "1,30,false", "1,31,false", "-1,1,false",
            "512,0,true", "1,-5,true", "0,0,false", "2147483647,29,true", "-2147483648,0,false"})
    void retryOnlyWhileSomethingIsDeferredAndAttemptsRemain(int deferred, int attempt, boolean expected) {
        assertEquals(expected, DueRentLeases.retry(deferred, attempt));
    }

    @Test void retryLimitsAreThirtyAttemptsOneSecondApart() {
        assertEquals(30, DueRentLeases.MAX_ATTEMPTS);
        assertEquals(20L, DueRentLeases.RETRY_TICKS);
    }

    @Test void onlyARealBooleanTrueEnablesTheGuard() {
        assertTrue(DueRentLeases.enabled(Boolean.TRUE));
        assertTrue(DueRentLeases.enabled(true));
        assertFalse(DueRentLeases.enabled(Boolean.FALSE));
        assertFalse(DueRentLeases.enabled(null));
        assertFalse(DueRentLeases.enabled("true"));
        assertFalse(DueRentLeases.enabled("TRUE"));
        assertFalse(DueRentLeases.enabled(1));
        assertFalse(DueRentLeases.enabled(List.of(true)));
    }

    @Test void nullArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> DueRentLeases.acquire(null, List.of()));
        assertThrows(NullPointerException.class, () -> DueRentLeases.acquire(fence, null));
    }

    @Test void anEmptyCacheHoldsNothing() {
        var leases = DueRentLeases.acquire(fence, List.of());
        assertEquals(0, leases.heldCount());
        assertEquals(List.of(), leases.heldIds());
        assertEquals(Set.of(), leases.excluded());
        leases.releaseAll();
    }

    @Test void everyFreeHomeIsHeldInCacheOrder() {
        var a = home(3);
        var b = home(1);
        var c = home(2);
        var leases = DueRentLeases.acquire(fence, List.of(a, b, c));
        assertEquals(3, leases.heldCount());
        assertEquals(List.of(a.propertyId(), b.propertyId(), c.propertyId()), leases.heldIds());
        assertTrue(leases.holds(b.propertyId()));
        assertFalse(leases.holds(new UUID(1, 99)));
        assertTrue(fence.isHeld(WORLD, "home-3"));
        assertTrue(leases.excluded().isEmpty());
    }

    @Test void aBusyHomeIsExcludedNotWaitedOn() {
        var busy = home(1);
        var free = home(2);
        OwnershipFence.Lease payment = fence.acquireExclusive(WORLD, "home-1");
        var leases = DueRentLeases.acquire(fence, List.of(busy, free));
        assertEquals(Set.of(busy.propertyId()), leases.excluded());
        assertFalse(leases.holds(busy.propertyId()));
        assertTrue(leases.holds(free.propertyId()));
        assertEquals(1, leases.heldCount());
        leases.releaseAll();
        assertTrue(fence.isHeld(WORLD, "home-1"), "the payment's lease is untouched");
        payment.releaseReconciled();
    }

    @Test void aRegionDuringAnArmWriteIsExcluded() {
        Runnable end = fence.beginWrite(WORLD, "home-1");
        var leases = DueRentLeases.acquire(fence, List.of(home(1)));
        assertEquals(Set.of(home(1).propertyId()), leases.excluded());
        assertEquals(0, leases.heldCount());
        end.run();
    }

    @Test void aDuplicateEntryForTheSameHomeIsTakenOnce() {
        var a = home(1);
        var leases = DueRentLeases.acquire(fence, List.of(a, a));
        assertEquals(1, leases.heldCount());
        assertTrue(leases.excluded().isEmpty(), "the second copy is skipped, not counted as busy");
    }

    @Test void twoHomesSharingARegionExcludeTheSecond() {
        var first = home(1);
        var twin = new Property(new UUID(1, 77), WORLD, "home-1", "town", "small", 2, 10_000, 10_000, new UUID(2, 77), 0, 100, true);
        var leases = DueRentLeases.acquire(fence, List.of(first, twin));
        assertEquals(List.of(first.propertyId()), leases.heldIds());
        assertEquals(Set.of(twin.propertyId()), leases.excluded());
    }

    @Test void releasingFreesEveryLeaseAndIsIdempotent() {
        var leases = DueRentLeases.acquire(fence, List.of(home(1), home(2)));
        leases.releaseAll();
        assertEquals(0, leases.heldCount());
        assertEquals(List.of(), leases.heldIds());
        assertFalse(leases.holds(home(1).propertyId()));
        assertFalse(fence.isHeld(WORLD, "home-1"));
        assertFalse(fence.isHeld(WORLD, "home-2"));
        leases.releaseAll();
        assertNotNull(fence.acquireExclusive(WORLD, "home-1"), "a released home can be held again");
    }

    @Test void theExcludedSetIsACopy() {
        fence.acquireExclusive(WORLD, "home-1");
        var leases = DueRentLeases.acquire(fence, List.of(home(1)));
        assertThrows(UnsupportedOperationException.class, () -> leases.excluded().clear());
        assertThrows(UnsupportedOperationException.class, () -> leases.heldIds().clear());
        assertEquals(1, leases.excluded().size());
    }

    @Test void aNonBusyFailureReleasesEverythingTakenAndIsRethrown() {
        var good = home(1);
        var bad = new Property(new UUID(1, 2), WORLD, " ", "town", "small", 2, 10_000, 10_000, new UUID(2, 2), 0, 100, true);
        var after = home(3);
        var failure = assertThrows(IllegalArgumentException.class, () -> DueRentLeases.acquire(fence, List.of(good, bad, after)));
        assertEquals("Missing region", failure.getMessage());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(fence.isHeld(WORLD, "home-1"), "the lease taken before the failure is released");
        assertFalse(fence.isHeld(WORLD, "home-3"), "nothing after the failure is taken");
    }

    @Test void aStoppedFenceAbortsTheAttempt() {
        fence.stop();
        var failure = assertThrows(IllegalStateException.class, () -> DueRentLeases.acquire(fence, List.of(home(1))));
        assertFalse(failure instanceof OwnershipFence.Busy);
        assertEquals("Ownership coordinator stopped", failure.getMessage());
    }

    @Test void theWrongThreadAbortsTheAttempt() {
        main = false;
        var failure = assertThrows(IllegalStateException.class, () -> DueRentLeases.acquire(fence, List.of(home(1))));
        assertEquals("Ownership fence requires main thread", failure.getMessage());
    }

    @Test void releaseFailuresAreAllAttemptedAndTheFirstIsThrownWithTheRestSuppressed() {
        var leases = DueRentLeases.acquire(fence, List.of(home(1), home(2), home(3)));
        main = false;
        var failure = assertThrows(IllegalStateException.class, leases::releaseAll);
        assertEquals("Ownership fence requires main thread", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(0, leases.heldCount(), "the step counts as released even though the fence refused");
        main = true;
        leases.releaseAll();
        assertTrue(fence.isHeld(WORLD, "home-2"), "a second release is a no-op, so the refused lease stays on the fence");
    }
}
