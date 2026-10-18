package com.prosperitysmp.npc.charge;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChargeLimitsTest {
    private static ChargeLimits.Candidate line(String id, long raw, long max, boolean external) {
        return new ChargeLimits.Candidate(id, raw, max, 1, external);
    }

    @Test void capsKeepWholeLinesInPolicyOrderWithoutSpendingSkippedHeadroom() {
        var input = List.of(line("z", 150, 100, false), line("b", 50, 100, false),
                line("a", 60, 100, false), line("c", 40, 100, false));
        var result = ChargeLimits.apply(input, 120, 100);
        assertEquals(List.of("a", "c"), result.lines().stream().map(ChargeLimits.Line::policyId).toList());
        assertEquals(100, result.total());
        assertEquals(List.of("b"), result.serverTrimmed());
        assertEquals(List.of("z"), result.ownerTrimmed());
        assertEquals("z", input.getFirst().policyId());
    }

    @Test void aHugeOrdinaryTaxIsCappedWhileASuspectMeterIsOmitted() {
        var result = ChargeLimits.apply(List.of(line("tax", Long.MAX_VALUE, 100, false),
                line("meter", 401, 100, true), line("boundary", 400, 100, true)), 1000, 1000);
        assertEquals(List.of("meter"), result.suspect());
        assertEquals(200, result.total());
        assertTrue(result.lines().stream().allMatch(ChargeLimits.Line::capped));
    }

    @Test void belowMinimumAndExhaustedBudgetsNeverBecomePartialCharges() {
        var candidates = List.of(line("zero", 0, 100, false),
                new ChargeLimits.Candidate("tiny", 10, 100, 20, false));
        assertEquals(List.of("tiny", "zero"), ChargeLimits.apply(candidates, 100, 100).belowMinimum());
        var over = List.of(line("bill", 100, 100, false));
        assertEquals(List.of("bill"), ChargeLimits.apply(over, 99, 100).ownerTrimmed());
        assertEquals(List.of("bill"), ChargeLimits.apply(over, 100, 99).serverTrimmed());
        assertEquals(0, ChargeLimits.apply(over, -1, Long.MAX_VALUE).total());
        assertEquals(0, ChargeLimits.apply(over, Long.MAX_VALUE, -1).total());
    }

    @Test void oneWholeBillNeverPassesTwoHundredDollarsWhateverTheDailyLimitsAllow() {
        long max = ChargeLimits.PER_BILL_CEILING_CENTS;
        var lines = List.of(line("a-tax", 15_000, max, false), line("b-tax", 15_000, max, false),
                line("c-tax", 5_000, max, false), line("d-tax", 1, max, false));
        var result = ChargeLimits.apply(lines, 100_000, 2_500_000);
        assertEquals(List.of("a-tax", "c-tax"), result.lines().stream().map(ChargeLimits.Line::policyId).toList());
        assertEquals(20_000, result.total());
        assertEquals(List.of("b-tax", "d-tax"), result.billTrimmed());
        assertEquals(List.of(), result.ownerTrimmed());
        assertEquals(List.of(), result.serverTrimmed());
        // Five lines at their $200 maximum still make one $200 bill.
        var five = new java.util.ArrayList<ChargeLimits.Candidate>();
        for (int i = 0; i < 5; i++) five.add(line("p" + i, max, max, false));
        assertEquals(20_000, ChargeLimits.apply(five, 100_000, 2_500_000).total());
        assertEquals(List.of("p1", "p2", "p3", "p4"), ChargeLimits.apply(five, 100_000, 2_500_000).billTrimmed());
    }
}
