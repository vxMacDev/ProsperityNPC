package com.prosperitysmp.npc.nav;

import java.util.List;

/**
 * The street numbers a section-form palette is scanned (and audited) with. Costs are baked into
 * the graph at scan time from these, and the audit reads the same values, so the router and the
 * audit cannot disagree about how far a crosswalk is worth walking to.
 */
public record StreetRules(int crosswalkReach, int maxCrossingLength, double minShare) {

    public static final int MAX_REACH = 64;
    public static final int MAX_CROSSING = 32;
    /** Inner paint cells every crossing needs before its share is even looked at, however short the crossing is. */
    public static final int MIN_PAINT = 2;

    /** Legacy palettes have no street cells, so only the shape of these values matters. */
    public static final StreetRules LEGACY = new StreetRules(12, 16, 0.4);

    public StreetRules {
        if (crosswalkReach < 0 || crosswalkReach > MAX_REACH) {
            throw new IllegalArgumentException("nav.street.crosswalk-reach must be 0.." + MAX_REACH
                    + " (was " + crosswalkReach + ")");
        }
        if (maxCrossingLength < 1 || maxCrossingLength > MAX_CROSSING) {
            throw new IllegalArgumentException("nav.street.max-crossing-length must be 1.." + MAX_CROSSING
                    + " (was " + maxCrossingLength + ")");
        }
        if (!(minShare > 0) || minShare > 1) {
            throw new IllegalArgumentException("nav.street.crosswalk-min-share must be above 0 and at most 1 (was "
                    + minShare + ")");
        }
    }

    /**
     * Extra cost for stepping from a curb onto a plain (unpainted) crossing. A crosswalk route and a
     * straight route differ only by this against the sidewalk detour, so one number works on any road width.
     */
    public int entryPenalty() {
        return 20 * crosswalkReach;
    }

    /**
     * A crossing is a painted crosswalk when enough of its inner cells are crossing paint. The first and
     * last cell are ignored: a solid edge line along the road lands on both ends of every crossing and
     * would make a short plain crossing look painted. Paint on at least {@link #MIN_PAINT} inner cells is
     * also needed on every crossing, so a single lane dash never counts, and a crossing with fewer than that
     * many inner cells is never a crosswalk.
     *
     * @param cells the crossing's surfaces in order from one curb to the other
     */
    public boolean isCrosswalk(List<NavSurface> cells) {
        int inner = cells.size() - 2;
        if (inner < MIN_PAINT) {
            return false;
        }
        int paint = 0;
        for (int i = 1; i <= inner; i++) {
            if (cells.get(i) == NavSurface.CROSSING) {
                paint++;
            }
        }
        return paint >= MIN_PAINT && paint >= minShare * inner - 1e-9;
    }
}
