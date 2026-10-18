package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The street numbers' limits with their messages, the entry penalty, and the painted-crosswalk share at its thresholds. */
class StreetRulesBoundsTest {

    private static String refusal(int reach, int length, double share) {
        return assertThrows(IllegalArgumentException.class, () -> new StreetRules(reach, length, share)).getMessage();
    }

    @Test
    void theDocumentedLimitsAreUnchanged() {
        assertEquals(64, StreetRules.MAX_REACH);
        assertEquals(32, StreetRules.MAX_CROSSING);
        assertEquals(2, StreetRules.MIN_PAINT);
        assertEquals(new StreetRules(12, 16, 0.4), StreetRules.LEGACY);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 65, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void aReachOutsideZeroTo64IsRefusedWithTheValue(int reach) {
        assertEquals("nav.street.crosswalk-reach must be 0..64 (was " + reach + ")", refusal(reach, 16, 0.4));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 12, 64})
    void aReachInsideTheRangeIsAccepted(int reach) {
        assertDoesNotThrow(() -> new StreetRules(reach, 16, 0.4));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 33, 1000, Integer.MIN_VALUE})
    void aCrossingLengthOutsideOneTo32IsRefusedWithTheValue(int length) {
        assertEquals("nav.street.max-crossing-length must be 1..32 (was " + length + ")", refusal(12, length, 0.4));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 16, 32})
    void aCrossingLengthInsideTheRangeIsAccepted(int length) {
        assertDoesNotThrow(() -> new StreetRules(12, length, 0.4));
    }

    @ParameterizedTest
    @CsvSource({"0.0,0.0", "-0.1,-0.1", "1.0000001,1.0000001", "1.5,1.5", "Infinity,Infinity", "-Infinity,-Infinity", "NaN,NaN"})
    void aShareNotAboveZeroAndAtMostOneIsRefusedWithTheValue(double share, String shown) {
        assertEquals("nav.street.crosswalk-min-share must be above 0 and at most 1 (was " + shown + ")", refusal(12, 16, share));
    }

    @Test
    void negativeZeroIsNotAboveZero() {
        assertEquals("nav.street.crosswalk-min-share must be above 0 and at most 1 (was -0.0)", refusal(12, 16, -0.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.MIN_VALUE, 0.0001, 0.4, 0.999999, 1.0})
    void aShareAboveZeroUpToOneIsAccepted(double share) {
        assertDoesNotThrow(() -> new StreetRules(12, 16, share));
    }

    @Test
    void reachIsCheckedBeforeLengthAndLengthBeforeShare() {
        assertTrue(refusal(-1, 0, 0).contains("crosswalk-reach"));
        assertTrue(refusal(12, 0, 0).contains("max-crossing-length"));
        assertTrue(refusal(12, 16, 0).contains("crosswalk-min-share"));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,20", "12,240", "64,1280"})
    void theEntryPenaltyIsTwentyPerBlockOfReach(int reach, int penalty) {
        assertEquals(penalty, new StreetRules(reach, 16, 0.4).entryPenalty());
    }

    // ---- the share rule at its thresholds ----

    /** A crossing with {@code inner} inner cells of which the first {@code paint} are paint, between two plain road ends. */
    private static List<NavSurface> crossing(int inner, int paint) {
        List<NavSurface> cells = new ArrayList<>();
        cells.add(NavSurface.ROAD);
        for (int i = 0; i < inner; i++) cells.add(i < paint ? NavSurface.CROSSING : NavSurface.ROAD);
        cells.add(NavSurface.ROAD);
        return cells;
    }

    @ParameterizedTest
    @CsvSource({
            "0.4,5,1,false", "0.4,5,2,true", "0.4,6,2,false", "0.4,6,3,true", "0.4,10,3,false", "0.4,10,4,true",
            "1.0,4,3,false", "1.0,4,4,true", "0.5,4,1,false", "0.5,4,2,true", "0.5,5,2,false", "0.5,5,3,true",
            "0.01,100,1,false", "0.01,100,2,true", "0.3,10,2,false", "0.3,10,3,true", "0.7,10,6,false", "0.7,10,7,true",
            "0.1,30,2,false", "0.1,30,3,true"
    })
    void thePaintNeededIsTheShareOfInnerCellsRoundedUpAndNeverBelowTwo(double share, int inner, int paint, boolean expected) {
        assertEquals(expected, new StreetRules(12, 16, share).isCrosswalk(crossing(inner, paint)));
    }

    @Test
    void paintOnTheTwoEndCellsNeverCounts() {
        List<NavSurface> cells = List.of(NavSurface.CROSSING, NavSurface.ROAD, NavSurface.ROAD, NavSurface.ROAD, NavSurface.CROSSING);
        assertFalse(new StreetRules(12, 16, 0.01).isCrosswalk(cells));
    }

    @Test
    void onlyCrossingCellsCountAsPaintNotSidewalkAccessOrUnknown() {
        List<NavSurface> cells = List.of(NavSurface.ROAD, NavSurface.SIDEWALK, NavSurface.ACCESS, NavSurface.UNKNOWN, NavSurface.ROAD);
        assertFalse(new StreetRules(12, 16, 0.01).isCrosswalk(cells));
    }

    @Test
    void anEmptyAndAOneCellListAreNeverCrosswalks() {
        assertFalse(StreetRules.LEGACY.isCrosswalk(List.of()));
        assertFalse(StreetRules.LEGACY.isCrosswalk(List.of(NavSurface.CROSSING)));
    }

    @Test
    void paintIsCountedWhereverItSitsAmongTheInnerCells() {
        List<NavSurface> middle = List.of(NavSurface.ROAD, NavSurface.ROAD, NavSurface.CROSSING, NavSurface.ROAD, NavSurface.CROSSING,
                NavSurface.ROAD, NavSurface.ROAD);
        assertTrue(new StreetRules(12, 16, 0.4).isCrosswalk(middle), "2 of 5 inner cells");
    }
}
