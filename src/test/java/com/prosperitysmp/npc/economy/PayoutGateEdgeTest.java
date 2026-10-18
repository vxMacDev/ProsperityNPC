package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** The payout envelope with exhausted or negative headroom, ratio extremes and the reserve floor boundary. */
class PayoutGateEdgeTest {
    private static final long BIG = 1_000_000_000L;
    private static final PayoutGate.CapRemaining OPEN = new PayoutGate.CapRemaining(BIG, BIG, BIG, BIG, BIG);
    private static final PayoutGate.FundingState RICH = new PayoutGate.FundingState(BIG, 0, 0);

    private static long allowed(long requested, boolean appreciation, PayoutGate.CapRemaining caps,
                                PayoutGate.FundingState funding, long bank, long floor, double ratio) {
        return PayoutGate.allowedPayoutCents(requested, appreciation, caps, funding, bank, floor, ratio);
    }

    @ParameterizedTest
    @CsvSource({"-1,1000,1000,1000,1000", "1000,-1,1000,1000,1000", "1000,1000,-1,1000,1000", "1000,1000,1000,-1,1000",
            "-9223372036854775808,1000,1000,1000,1000"})
    void anyNegativeCapHeadroomPaysNothingForRent(long server, long unit, long property, long tenant, long appreciation) {
        var caps = new PayoutGate.CapRemaining(server, unit, property, tenant, appreciation);
        assertEquals(0, allowed(500, false, caps, RICH, BIG, 0, 0.9));
    }

    @Test
    void rentIgnoresANegativeAppreciationCapAndAppreciationIgnoresANegativeTenantCap() {
        assertEquals(500, allowed(500, false, new PayoutGate.CapRemaining(BIG, BIG, BIG, BIG, -1), RICH, BIG, 0, 0.9));
        assertEquals(500, allowed(500, true, new PayoutGate.CapRemaining(BIG, BIG, BIG, -1, BIG), RICH, BIG, 0, 0.9));
        assertEquals(0, allowed(500, true, new PayoutGate.CapRemaining(BIG, BIG, BIG, BIG, -1), RICH, BIG, 0, 0.9));
    }

    @Test
    void theSmallestOfSeveralBindingCapsWins() {
        var caps = new PayoutGate.CapRemaining(400, 300, 200, 100, 50);
        assertEquals(100, allowed(1_000, false, caps, RICH, BIG, 0, 0.9));
        assertEquals(50, allowed(1_000, true, caps, RICH, BIG, 0, 0.9));
    }

    @Test
    void aZeroRatioLeavesOnlyTheSeed() {
        var funding = new PayoutGate.FundingState(300, 1_000_000, 0);
        assertEquals(300, allowed(1_000, false, OPEN, funding, BIG, 0, 0.0));
    }

    @Test
    void aFullRatioFundsEverySinkCent() {
        var funding = new PayoutGate.FundingState(0, 777, 0);
        assertEquals(777, allowed(1_000, false, OPEN, funding, BIG, 0, 1.0));
    }

    @Test
    void sinkFundingIsFlooredToWholeCents() {
        // 0.9 of 1111 is 999.9, floored to 999.
        var funding = new PayoutGate.FundingState(0, 1_111, 0);
        assertEquals(999, allowed(5_000, false, OPEN, funding, BIG, 0, 0.9));
    }

    @Test
    void payoutsBeyondTheFundingPayNothingRatherThanANegativeAmount() {
        var funding = new PayoutGate.FundingState(100, 0, 5_000);
        assertEquals(0, allowed(1_000, false, OPEN, funding, BIG, 0, 0.9));
    }

    @Test
    void aNegativeSeedOrSinkTotalCountsAsZero() {
        assertEquals(0, allowed(1_000, false, OPEN, new PayoutGate.FundingState(-500, 0, 0), BIG, 0, 0.9));
        assertEquals(200, allowed(1_000, false, OPEN, new PayoutGate.FundingState(200, -900, 0), BIG, 0, 0.9));
    }

    @Test
    void theReserveMayFallExactlyToItsFloorButNotBelow() {
        assertEquals(1_000, allowed(1_000, false, OPEN, RICH, 3_500_000 + 1_000, 3_500_000, 0.9));
        assertEquals(999, allowed(1_000, false, OPEN, RICH, 3_500_000 + 999, 3_500_000, 0.9));
        assertEquals(0, allowed(1_000, false, OPEN, RICH, 3_500_000, 3_500_000, 0.9));
    }

    @Test
    void aNegativeFloorLetsTheReservePayMoreThanItsBalance() {
        assertEquals(150, allowed(1_000, false, OPEN, RICH, 100, -50, 0.9));
    }

    @Test
    void theLargestRequestIsBoundedByTheTightestLimit() {
        assertEquals(BIG, allowed(Long.MAX_VALUE, false, OPEN, RICH, Long.MAX_VALUE, 0, 0.9));
        assertEquals(1, allowed(1, true, OPEN, RICH, BIG, 0, 0.9));
    }
}
