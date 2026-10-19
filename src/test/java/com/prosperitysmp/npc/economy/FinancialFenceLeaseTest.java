package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The single reserve lease: who holds it, double release and contention. */
class FinancialFenceLeaseTest {

    @Test
    void aLeaseNamesItsOwnerAndHasItsOwnId() {
        var fence = new FinancialFence();
        var first = fence.acquire("rent");
        assertEquals("rent", first.owner());
        assertNotNull(first.id());
        first.releaseVerified();
        var second = fence.acquire("rent");
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void theRefusalSaysAnotherPaymentHoldsTheReserve() {
        var fence = new FinancialFence();
        fence.acquire("rent");
        assertEquals("Another NPC payment holds the reserve",
                assertThrows(IllegalStateException.class, () -> fence.acquire("rent")).getMessage());
    }

    @Test
    void aLeaseCannotBeReleasedTwice() {
        var fence = new FinancialFence();
        var lease = fence.acquire("bill");
        lease.releaseVerified();
        assertEquals("NPC payment lease no longer active",
                assertThrows(IllegalStateException.class, lease::releaseVerified).getMessage());
        assertEquals("NPC payment lease no longer active",
                assertThrows(IllegalStateException.class, lease::validate).getMessage());
    }

    @Test
    void aStaleLeaseCannotReleaseTheNewHolder() {
        var fence = new FinancialFence();
        var stale = fence.acquire("old");
        stale.releaseVerified();
        var current = fence.acquire("new");
        assertThrows(IllegalStateException.class, stale::releaseVerified);
        current.validate();
        assertThrows(IllegalStateException.class, () -> fence.acquire("third"));
    }

    @Test
    void twoFencesLeaseIndependentlyAndReleasingOneLeavesTheOtherValid() {
        var one = new FinancialFence();
        var two = new FinancialFence();
        var lease = one.acquire("one");
        var other = two.acquire("two");
        lease.validate();
        other.validate();
        lease.releaseVerified();
        other.validate();
    }

    @Test
    void aMissingOwnerIsRefusedAndLeavesTheFenceFree() {
        var fence = new FinancialFence();
        assertThrows(NullPointerException.class, () -> fence.acquire(null));
        assertEquals("ok", fence.acquire("ok").owner());
    }

    @Test
    void eightThreadsRacingForTheFenceLetExactlyOneIn() throws Exception {
        var fence = new FinancialFence();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String owner = "worker-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        fence.acquire(owner);
                        return true;
                    } catch (IllegalStateException held) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) if (result.get(30, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }
}
