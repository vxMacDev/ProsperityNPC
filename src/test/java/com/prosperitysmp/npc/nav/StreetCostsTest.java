package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The entry cost moved out of the scanner unchanged; NavScanLogicTest's legacy golden and StreetScanTest guard the scanner side. */
class StreetCostsTest {

    private static final NavSurface R = NavSurface.ROAD;
    private static final NavSurface W = NavSurface.CROSSING;

    /** The scanner's own expression before the move. */
    private static int old(StreetRules rules, List<NavSurface> cells) {
        return NavScanLogic.CARDINAL_COST + (rules.isCrosswalk(cells) ? 0 : rules.entryPenalty());
    }

    @Test
    void theExtractedEntryEqualsTheOldInlineValue() {
        List<List<NavSurface>> crossings = List.of(List.of(R), List.of(W), List.of(R, R), List.of(W, W, W),
                List.of(R, W, W, R), List.of(R, W, R, R), List.of(W, W, W, W), List.of(R, W, W, W, W, R),
                List.of(R, R, R, R, R, R, R, R));
        for (int reach : new int[]{0, 1, 12, 64}) {
            for (double share : new double[]{0.1, 0.4, 1.0}) {
                StreetRules rules = new StreetRules(reach, 16, share);
                for (List<NavSurface> cells : crossings) {
                    assertEquals(old(rules, cells), StreetCosts.entry(rules, cells), reach + " " + share + " " + cells);
                }
            }
        }
        assertEquals(10, StreetCosts.entry(StreetWorld.RULES, List.of(R, W, W, R)));
        assertEquals(250, StreetCosts.entry(StreetWorld.RULES, List.of(R, R, R, R)));
        assertEquals(1290, StreetCosts.entry(new StreetRules(64, 16, 0.4), List.of(R, R)));
    }
}
