package com.prosperitysmp.npc.charge;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ChargeLimitsLinesTest {
    private static ChargeLimits.Candidate line(String id, long raw, long max) {
        return new ChargeLimits.Candidate(id, raw, max, 1, false);
    }

    @ParameterizedTest(name = "raw {0}, max {1}, min {2}")
    @CsvSource({"-1, 100, 1", "0, 0, 1", "0, 20001, 1", "0, 100, -1", "0, -5, 0"})
    void aCandidateOutsideTheCeilingsIsRefused(long raw, long max, long min) {
        var failure = assertThrows(IllegalArgumentException.class, () -> new ChargeLimits.Candidate("tax", raw, max, min, false));
        assertEquals("invalid bill candidate", failure.getMessage());
    }

    @Test void aCandidateAtTheCeilingsIsAccepted() {
        var c = new ChargeLimits.Candidate("tax", Long.MAX_VALUE, ChargeLimits.PER_BILL_CEILING_CENTS, 0, true);
        assertEquals(20_000, c.maxCents());
        assertEquals(0, new ChargeLimits.Candidate("tax", 0, 1, 0, false).rawCents());
    }

    @Test void aLineAtExactlyItsMaximumIsNotMarkedCapped() {
        var result = ChargeLimits.apply(List.of(line("tax", 100, 100)), 1000, 1000);
        assertEquals(List.of(new ChargeLimits.Line("tax", 100, 100, false)), result.lines());
    }

    @Test void aLineAboveItsMaximumIsCutToItAndMarked() {
        var result = ChargeLimits.apply(List.of(line("tax", 101, 100)), 1000, 1000);
        assertEquals(List.of(new ChargeLimits.Line("tax", 101, 100, true)), result.lines());
        assertEquals(100, result.total());
    }

    @Test void anOrdinaryLineFarAboveFourTimesItsMaximumIsCappedNotSuspect() {
        var result = ChargeLimits.apply(List.of(line("tax", 401, 100)), 1000, 1000);
        assertEquals(List.of(), result.suspect());
        assertEquals(List.of(new ChargeLimits.Line("tax", 401, 100, true)), result.lines());
    }

    @Test void aMinimumOfZeroStillLeavesOffAZeroLine() {
        var result = ChargeLimits.apply(List.of(new ChargeLimits.Candidate("tax", 0, 100, 0, false)), 1000, 1000);
        assertEquals(List.of("tax"), result.belowMinimum());
        assertEquals(List.of(), result.lines());
    }

    @Test void theMinimumIsComparedAfterTheMaximumCap() {
        var result = ChargeLimits.apply(List.of(new ChargeLimits.Candidate("tax", 500, 50, 60, false)), 1000, 1000);
        assertEquals(List.of("tax"), result.belowMinimum());
        var atMinimum = ChargeLimits.apply(List.of(new ChargeLimits.Candidate("tax", 60, 100, 60, false)), 1000, 1000);
        assertEquals(60, atMinimum.total());
    }

    @Test void aLineThatFillsTheHeadroomExactlyIsKept() {
        var result = ChargeLimits.apply(List.of(line("a", 60, 100), line("b", 40, 100)), 100, 100);
        assertEquals(100, result.total());
        assertEquals(List.of(), result.ownerTrimmed());
        assertEquals(List.of(), result.serverTrimmed());
    }

    @Test void theBillCeilingIsCheckedBeforeTheDailyLimits() {
        var lines = List.of(line("a", 15_000, 20_000), line("b", 15_000, 20_000));
        var result = ChargeLimits.apply(lines, 15_000, 15_000);
        assertEquals(List.of("b"), result.billTrimmed());
        assertEquals(List.of(), result.ownerTrimmed());
        assertEquals(List.of(), result.serverTrimmed());
        assertEquals(List.of("a"), result.lines().stream().map(ChargeLimits.Line::policyId).toList());
    }

    @Test void theOwnerLimitIsCheckedBeforeTheServerLimit() {
        var result = ChargeLimits.apply(List.of(line("a", 50, 100)), 10, 10);
        assertEquals(List.of("a"), result.ownerTrimmed());
        assertEquals(List.of(), result.serverTrimmed());
    }

    @Test void aSuspectLineSpendsNoHeadroom() {
        var result = ChargeLimits.apply(List.of(new ChargeLimits.Candidate("a-meter", 1_000, 100, 1, true), line("b-tax", 100, 100)), 100, 100);
        assertEquals(List.of("a-meter"), result.suspect());
        assertEquals(List.of(new ChargeLimits.Line("b-tax", 100, 100, false)), result.lines());
    }

    @Test void noCandidatesGiveAnEmptyOutcome() {
        var result = ChargeLimits.apply(List.of(), 0, 0);
        assertEquals(new ChargeLimits.Outcome(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), result);
        assertEquals(0, result.total());
    }

    @Test void theOutcomeListsCannotBeChanged() {
        var result = ChargeLimits.apply(List.of(line("a", 1, 100)), 100, 100);
        assertThrows(UnsupportedOperationException.class, () -> result.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.belowMinimum().add("x"));
    }

    @Test void anOutcomeTotalThatWouldOverflowThrows() {
        var lines = List.of(new ChargeLimits.Line("a", 0, Long.MAX_VALUE, false), new ChargeLimits.Line("b", 0, 1, false));
        var outcome = new ChargeLimits.Outcome(lines, List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(ArithmeticException.class, outcome::total);
    }

    @Test void theSuspectFactorIsFour() {
        assertEquals(4L, ChargeLimits.SUSPECT_FACTOR);
    }
}
