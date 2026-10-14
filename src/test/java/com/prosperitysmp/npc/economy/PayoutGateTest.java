package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayoutGateTest {

    private static final double RATIO = 0.90;
    // Generous defaults so a single constraint can be isolated per test.
    private static final PayoutGate.CapRemaining BIG_CAPS =
            new PayoutGate.CapRemaining(1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000);
    private static final PayoutGate.FundingState RICH =
            new PayoutGate.FundingState(1_000_000, 1_000_000, 0);
    private static final long CB = 10_000_000;   // $100k
    private static final long FLOOR = 3_500_000; // $35k

    @Test
    void nonPositiveRequest_paysZero() {
        assertEquals(0, PayoutGate.allowedPayoutCents(0, false, BIG_CAPS, RICH, CB, FLOOR, RATIO));
        assertEquals(0, PayoutGate.allowedPayoutCents(-5, false, BIG_CAPS, RICH, CB, FLOOR, RATIO));
    }

    @Test
    void allGenerous_paysFullRequest() {
        assertEquals(600, PayoutGate.allowedPayoutCents(600, false, BIG_CAPS, RICH, CB, FLOOR, RATIO));
    }

    @Test
    void serverCapBinds() {
        var caps = new PayoutGate.CapRemaining(100, 1_000_000, 1_000_000, 1_000_000, 1_000_000);
        assertEquals(100, PayoutGate.allowedPayoutCents(600, false, caps, RICH, CB, FLOOR, RATIO));
    }

    @Test
    void benefitUnitCapBinds() {
        var caps = new PayoutGate.CapRemaining(1_000_000, 250, 1_000_000, 1_000_000, 1_000_000);
        assertEquals(250, PayoutGate.allowedPayoutCents(600, false, caps, RICH, CB, FLOOR, RATIO));
    }

    @Test
    void propertyCapBinds() {
        var caps = new PayoutGate.CapRemaining(1_000_000, 1_000_000, 150, 1_000_000, 1_000_000);
        assertEquals(150, PayoutGate.allowedPayoutCents(600, false, caps, RICH, CB, FLOOR, RATIO));
    }

    @Test
    void tenantCapBindsForRent() {
        var caps = new PayoutGate.CapRemaining(1_000_000, 1_000_000, 1_000_000, 90, 1_000_000);
        // rent (appreciation=false) uses the tenant cap
        assertEquals(90, PayoutGate.allowedPayoutCents(600, false, caps, RICH, CB, FLOOR, RATIO));
    }

    @Test
    void appreciationUsesAppreciationCapNotTenant() {
        // tenant cap is 0 but this is appreciation, so the tenant cap must be ignored.
        var caps = new PayoutGate.CapRemaining(1_000_000, 1_000_000, 1_000_000, 0, 300);
        assertEquals(300, PayoutGate.allowedPayoutCents(600, true, caps, RICH, CB, FLOOR, RATIO));
    }

    @Test
    void fundingInvariantBinds() {
        // seed 50, no sinks -> funded 50; only $0.50 can ever be paid until sinks accrue.
        var funding = new PayoutGate.FundingState(50, 0, 0);
        assertEquals(50, PayoutGate.allowedPayoutCents(600, false, BIG_CAPS, funding, CB, FLOOR, RATIO));
    }

    @Test
    void fundingRespectsPriorPayouts() {
        // seed 5000, sinks 1000 -> funded 5900; already paid 5850 -> only 50 left.
        var funding = new PayoutGate.FundingState(5000, 1000, 5850);
        assertEquals(50, PayoutGate.allowedPayoutCents(600, false, BIG_CAPS, funding, CB, FLOOR, RATIO));
    }

    @Test
    void reserveFloorBinds() {
        // CentralBank only $200 above the floor.
        assertEquals(200, PayoutGate.allowedPayoutCents(600, false, BIG_CAPS, RICH, FLOOR + 200, FLOOR, RATIO));
    }

    @Test
    void belowReserveFloor_paysZero() {
        assertEquals(0, PayoutGate.allowedPayoutCents(600, false, BIG_CAPS, RICH, FLOOR - 1, FLOOR, RATIO));
    }
}
