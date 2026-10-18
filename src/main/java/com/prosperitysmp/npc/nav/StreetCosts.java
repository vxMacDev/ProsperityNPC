package com.prosperitysmp.npc.nav;

import java.util.List;

/**
 * The cost of stepping from a curb onto a crossing. One rule shared by the scanner, the waypoint
 * densifier and the route file reader, so a crossing costs the same however its graph was made.
 */
public final class StreetCosts {

    private StreetCosts() {
    }

    /**
     * Only stepping onto a plain crossing costs extra; walking over it and leaving it do not.
     *
     * @param cells the crossing's street cells in order from one curb to the other (curbs not included)
     */
    public static int entry(StreetRules rules, List<NavSurface> cells) {
        return NavScanLogic.CARDINAL_COST + (rules.isCrosswalk(cells) ? 0 : rules.entryPenalty());
    }
}
