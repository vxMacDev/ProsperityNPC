package com.prosperitysmp.npc.nav.contract;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.waypoint.WaypointDensifier;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;

import java.util.Set;
import java.util.TreeSet;

/** The graph-source contract run against staff waypoints: one point per curb block, linked along and across. */
class DensifierGraphSourceContractTest extends GraphSourceContract {

    /** The street as staff would place it. */
    static WaypointSet waypoints(Street street) {
        WaypointSet set = WaypointSet.EMPTY;
        LinkKind across = street.painted() ? LinkKind.CROSSWALK : LinkKind.CROSS;
        int last = street.length() - 1;
        for (int x = 0; x <= last; x++) {
            set = set.addPoint(new Point("n" + x, x, GROUND + 1, 2, PointKind.SIDEWALK,
                            x == 0 ? new TreeSet<>(Set.of("home")) : null), Limits.DEFAULT)
                    .addPoint(new Point("s" + x, x, GROUND + 1, street.southCurb(), PointKind.SIDEWALK,
                            x == last ? new TreeSet<>(Set.of("work")) : null), Limits.DEFAULT)
                    .link("n" + x, "s" + x, across, Limits.DEFAULT);
            if (x > 0) {
                set = set.link("n" + (x - 1), "n" + x, LinkKind.WALK, Limits.DEFAULT)
                        .link("s" + (x - 1), "s" + x, LinkKind.WALK, Limits.DEFAULT);
            }
        }
        return set;
    }

    @Override
    protected GraphSource subject() {
        return new GraphSource() {
            @Override
            public NavGraph build(Street street, Caps caps) {
                return WaypointDensifier.densify(waypoints(street), RULES, 1,
                        new WaypointDensifier.Limits(caps.maxNodes(), caps.maxEdges())).graph();
            }

            @Override
            public boolean capped() {
                return true;
            }
        };
    }
}
