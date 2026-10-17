package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The painted-crosswalk rule on its own: shapes written as one letter per cell, curb to curb. */
class StreetRulesTest {
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);

    private static List<NavSurface> cells(String shape) {
        List<NavSurface> out = new ArrayList<>();
        for (char c : shape.toCharArray()) {
            out.add(c == 'W' ? NavSurface.CROSSING : NavSurface.ROAD);
        }
        return out;
    }

    private static boolean crosswalk(String shape) {
        return RULES.isCrosswalk(cells(shape));
    }

    @Test
    void theMeasuredDottedZebrasCount() {
        assertTrue(crosswalk("rWrWrWrWr"), "4 of 7 inner cells");
        assertTrue(crosswalk("WrWrWrWrWrW"), "4 of 9 inner cells is 0.44");
        assertTrue(crosswalk("rWrWrWrWrW"));
        assertTrue(crosswalk("WWWWWWWWW"), "a solid stripe");
    }

    @Test
    void edgeLinesAndDashesDoNot() {
        assertFalse(crosswalk("WrrrW"), "edge lines at both ends were 2/5 = 0.4 over the whole ray");
        assertFalse(crosswalk("WrrrrW"));
        assertFalse(crosswalk("WrrrWrrrW"));
        assertFalse(crosswalk("WrrrrrrrW"));
        assertFalse(crosswalk("rrrrWrrrr"), "one dash in the middle of a nine-wide road");
        assertFalse(crosswalk("WrrWrrrrrrW"), "edge lines plus one dash");
    }

    @Test
    void aOneOrTwoCellCrossingHasNoInnerCellToJudge() {
        assertFalse(crosswalk("W"));
        assertFalse(crosswalk("WW"));
        assertFalse(crosswalk("r"));
    }

    @Test
    void aThreeCellCrossingHasOnlyOneInnerCellSoItIsNeverAPaintedCrosswalk() {
        assertFalse(crosswalk("WWW"));
        assertFalse(crosswalk("rWr"));
        assertFalse(crosswalk("WrW"));
        // Two inner cells are the least that can qualify.
        assertTrue(crosswalk("rWWr"));
        assertFalse(crosswalk("rWrr"));
    }

    @Test
    void aLoneInnerPaintBlockNeverCountsOnAWiderRoadHoweverLowTheShare() {
        StreetRules lax = new StreetRules(12, 16, 0.01);
        assertFalse(lax.isCrosswalk(cells("rrrrWrrrr")));
        assertTrue(lax.isCrosswalk(cells("rrrWWrrrr")), "two inner paint blocks pass a low share");
    }

    @Test
    void theShareIsMeasuredOnTheInnerCells() {
        // Inner cells of rrWWWrr are rWWWr: 3 of 5 = 0.6.
        assertTrue(new StreetRules(12, 16, 0.6).isCrosswalk(cells("rrWWWrr")));
        assertFalse(new StreetRules(12, 16, 0.61).isCrosswalk(cells("rrWWWrr")));
    }
}
