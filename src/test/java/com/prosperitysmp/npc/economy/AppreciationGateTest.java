package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The runtime's appreciation gate, ordering and staff reply, without a server. */
class AppreciationGateTest {
    @Test void onlyARealBooleanTrueOpensTheGate() {
        assertFalse(UtilityPaymentRuntime.appreciationEnabled(null));
        assertFalse(UtilityPaymentRuntime.appreciationEnabled(Boolean.FALSE));
        assertTrue(UtilityPaymentRuntime.appreciationEnabled(Boolean.TRUE));
        // Truthy values that are not a YAML boolean stay off.
        assertFalse(UtilityPaymentRuntime.appreciationEnabled("true"));
        assertFalse(UtilityPaymentRuntime.appreciationEnabled("yes"));
        assertFalse(UtilityPaymentRuntime.appreciationEnabled(1));
    }

    @Test void flagOffReplyIsTheExactReplyFromBeforeAppreciation() {
        // Exact reply texts, so any wording change fails here.
        assertEquals("Rent batch complete: 0 verified payouts. Reserve-floor deferrals remain pending; accounting refresh queued.",
                UtilityPaymentRuntime.rentBatchReply(0, 0, false, -1));
        assertEquals("Rent batch complete: 1 verified payouts. Reserve-floor deferrals remain pending; accounting refresh queued.",
                UtilityPaymentRuntime.rentBatchReply(1, 0, false, 7));
        assertEquals("Rent batch complete: 2 verified payouts. Reserve-floor deferrals remain pending; accounting refresh queued."
                        + " Rent held: owner unavailable for 1 property.",
                UtilityPaymentRuntime.rentBatchReply(2, 1, false, 3));
        assertEquals("Rent batch complete: 2 verified payouts. Reserve-floor deferrals remain pending; accounting refresh queued."
                        + " Rent held: owner unavailable for 3 properties.",
                UtilityPaymentRuntime.rentBatchReply(2, 3, false, -1));
    }

    @Test void flagOnReplyNamesTheClosedDayPlainly() {
        String held = UtilityPaymentRuntime.rentBatchReply(4, 0, true, -1);
        String paid = UtilityPaymentRuntime.rentBatchReply(4, 0, true, 3);
        assertTrue(held.endsWith(" Yesterday's appreciation held this batch; none paid."), held);
        assertTrue(paid.endsWith(" Yesterday's appreciation: 3 verified payouts."), paid);
        for (String text : new String[] {held, paid}) {
            assertTrue(text.startsWith(UtilityPaymentRuntime.rentBatchReply(4, 0, false, 0)));
            assertEquals(-1, text.indexOf('—'));
            assertEquals(-1, text.indexOf('–'));
        }
    }

    @Test void appreciationIsForTheEconomyDayThatJustClosed() {
        assertEquals(LocalDate.of(2026, 9, 7), UtilityPaymentRuntime.appreciationDay(LocalDate.of(2026, 9, 8)));
        assertEquals(LocalDate.of(2025, 12, 31), UtilityPaymentRuntime.appreciationDay(LocalDate.of(2026, 1, 1)));
    }

    @Test void flagOffReturnsTheRentSequenceAndNeverStartsAppreciation() {
        var rent = CompletableFuture.completedFuture(4);
        var counted = new AtomicInteger(-1);
        var result = UtilityPaymentRuntime.thenAppreciation(rent, false,
                () -> { throw new AssertionError("appreciation phase must not run with the flag off"); },
                counted::set, Runnable::run);
        assertSame(rent, result);
        assertEquals(4, result.join());
        assertEquals(-1, counted.get());
    }

    @Test void appreciationStartsOnlyAfterRentCompletes() {
        var rent = new CompletableFuture<Integer>();
        var calls = new AtomicInteger();
        var counted = new AtomicInteger(-1);
        var result = UtilityPaymentRuntime.thenAppreciation(rent, true,
                () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(2); }, counted::set, Runnable::run);
        assertEquals(0, calls.get(), "rent still in flight: appreciation has not been requested");
        assertFalse(result.isDone());
        rent.complete(5);
        assertEquals(1, calls.get());
        assertEquals(5, result.join(), "the batch still reports rent payouts");
        assertEquals(2, counted.get());
    }

    @Test void floorDeferredRentHoldsTheWholeAppreciationPhase() {
        var rent = new CompletableFuture<Integer>();
        var deferred = new java.util.concurrent.atomic.AtomicBoolean(false);
        var calls = new AtomicInteger();
        var counted = new AtomicInteger(99);
        var result = UtilityPaymentRuntime.thenAppreciation(rent, true, deferred::get,
                () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(3); }, counted::set, Runnable::run);
        deferred.set(true); // the floor deferred one of today's rent rows during the sequence
        rent.complete(2);
        assertEquals(2, result.join(), "the batch still reports the rent it paid");
        assertEquals(0, calls.get(), "appreciation must not start when any rent was floor-deferred");
        assertEquals(-1, counted.get(), "reported as held this batch");
    }

    @Test void noDeferralStillRunsAppreciationWithTheGatedOverload() {
        var calls = new AtomicInteger();
        var counted = new AtomicInteger(-1);
        var result = UtilityPaymentRuntime.thenAppreciation(CompletableFuture.completedFuture(1), true, () -> false,
                () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(3); }, counted::set, Runnable::run);
        assertEquals(1, result.join());
        assertEquals(1, calls.get());
        assertEquals(3, counted.get());
    }

    @Test void failedRentNeverStartsAppreciation() {
        var rent = new CompletableFuture<Integer>();
        var calls = new AtomicInteger();
        var result = UtilityPaymentRuntime.thenAppreciation(rent, true,
                () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(1); }, n -> { }, Runnable::run);
        rent.completeExceptionally(new IllegalStateException("Rent transfer requires recovery"));
        assertThrows(CompletionException.class, result::join);
        assertEquals(0, calls.get());
    }

    // ---- due-change skip count: the batch's own SQL step skipped a home for a due, unapplied rent change

    @Test void appreciationHeldTruthTable() {
        assertFalse(UtilityPaymentRuntime.appreciationHeld(false, 0), "nothing deferred, nothing skipped: runs");
        assertTrue(UtilityPaymentRuntime.appreciationHeld(false, 1), "one home skipped for a due change: held");
        assertTrue(UtilityPaymentRuntime.appreciationHeld(false, 512));
        assertTrue(UtilityPaymentRuntime.appreciationHeld(true, 0), "floor deferral alone still holds");
        assertTrue(UtilityPaymentRuntime.appreciationHeld(true, 3));
    }

    @Test void aDueChangeSkipHoldsTheWholeAppreciationPhaseExactlyLikeAFloorDeferral() {
        var rent = new CompletableFuture<Integer>();
        int[] skipped = {0};
        var calls = new AtomicInteger();
        var counted = new AtomicInteger(99);
        var result = UtilityPaymentRuntime.thenAppreciation(rent, true,
                () -> UtilityPaymentRuntime.appreciationHeld(false, skipped[0]),
                () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(3); }, counted::set, Runnable::run);
        skipped[0] = 1; // the batch's SQL step reported one home skipped for its due rent change
        rent.complete(2);
        assertEquals(2, result.join(), "the batch still reports the rent it paid");
        assertEquals(0, calls.get(), "the supplier is never invoked");
        assertEquals(-1, counted.get(), "reported as held this batch");
    }

    @Test void aZeroSkipCountLetsAppreciationRun() {
        var calls = new AtomicInteger();
        var counted = new AtomicInteger(-1);
        var result = UtilityPaymentRuntime.thenAppreciation(CompletableFuture.completedFuture(1), true,
                () -> UtilityPaymentRuntime.appreciationHeld(false, 0),
                () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(3); }, counted::set, Runnable::run);
        assertEquals(1, result.join());
        assertEquals(1, calls.get());
        assertEquals(3, counted.get());
    }

    @Test void appreciationOffNeverReadsTheSkipCount() {
        var rent = CompletableFuture.completedFuture(4);
        var counted = new AtomicInteger(99);
        var result = UtilityPaymentRuntime.thenAppreciation(rent, false,
                () -> { throw new AssertionError("skip count read with appreciation off"); },
                () -> { throw new AssertionError("appreciation phase started"); }, counted::set, Runnable::run);
        assertSame(rent, result, "the rent sequence is returned unchanged");
        assertEquals(99, counted.get());
    }

    @Test void theBatchStepRejectsANegativeSkipCount() {
        assertThrows(IllegalArgumentException.class, () -> new UtilityPaymentRuntime.RentBatchStep(java.util.List.of(), -1));
        assertEquals(0, new UtilityPaymentRuntime.RentBatchStep(java.util.List.of(), 0).heldForDueChange());
    }
}
