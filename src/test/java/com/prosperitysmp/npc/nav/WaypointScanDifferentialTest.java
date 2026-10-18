package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.waypoint.WaypointDensifier;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D9: draw a street, scan it, describe the same street as waypoints, and compare what each makes of the road:
 * the crossing nodes, their surfaces and every step onto, over and off the road.
 */
class WaypointScanDifferentialTest {

    private static final int LENGTH = 12;

    /** Every street node and every edge touching one, by coordinates. */
    static Map<String, String> streetPart(NavGraph graph) {
        Map<String, String> out = new TreeMap<>();
        for (NavGraph.Node n : graph.nodes()) {
            if (n.surface().isStreet()) out.put("node " + n.x() + "," + n.y() + "," + n.z(), n.surface().name());
        }
        for (NavGraph.Edge e : graph.edges()) {
            NavGraph.Node a = graph.node(e.from());
            NavGraph.Node b = graph.node(e.to());
            if (a.surface().isStreet() || b.surface().isStreet()) {
                out.put("edge " + a.x() + "," + a.z() + ">" + b.x() + "," + b.z(), String.valueOf(e.cost()));
            }
        }
        return out;
    }

    /** The same street as staff would place it: one point per curb block, linked along each sidewalk and across. */
    static WaypointSet asWaypoints(int width, int length, LinkKind kind) {
        int far = width + 3;
        WaypointSet set = WaypointSet.EMPTY;
        for (int x = 0; x < length; x++) {
            set = set.addPoint(new Point("n" + x, x, 64, 2, PointKind.SIDEWALK, null), Limits.DEFAULT)
                    .addPoint(new Point("s" + x, x, 64, far, PointKind.SIDEWALK, null), Limits.DEFAULT)
                    .link("n" + x, "s" + x, kind, Limits.DEFAULT);
            if (x > 0) {
                set = set.link("n" + (x - 1), "n" + x, LinkKind.WALK, Limits.DEFAULT)
                        .link("s" + (x - 1), "s" + x, LinkKind.WALK, Limits.DEFAULT);
            }
        }
        return set;
    }

    @Test
    void plainAndPaintedCrossingsMatchTheScanner() {
        for (int width : new int[]{2, 4, 7}) {
            NavGraph scanned = StreetWorld.strip(width, LENGTH).build().graph();
            NavGraph built = WaypointDensifier.densify(asWaypoints(width, LENGTH, LinkKind.CROSS), StreetWorld.RULES, 1,
                    WaypointDensifier.Limits.DEFAULT).graph();
            Map<String, String> expected = streetPart(scanned);
            assertFalse(expected.isEmpty(), "plain, width " + width);
            assertEquals(expected, streetPart(built), "plain, width " + width);

            NavGraph paintedScan = StreetWorld.strip(width, LENGTH).fill(0, 3, LENGTH - 1, 2 + width, 'W').build().graph();
            NavGraph paintedBuilt = WaypointDensifier.densify(asWaypoints(width, LENGTH, LinkKind.CROSSWALK), StreetWorld.RULES, 1,
                    WaypointDensifier.Limits.DEFAULT).graph();
            assertEquals(streetPart(paintedScan), streetPart(paintedBuilt), "painted, width " + width);
        }
    }

    /** Reads a drawn street the way the world reader does: solid below the support, the support with its class, air above. */
    static NavSweep.Reader reader(StreetWorld world) {
        return (x, y, z) -> {
            boolean ground = "SARWU".indexOf(world.at(x, z)) >= 0;
            int top = world.ground(x, z);
            if (ground && y < top) return NavSweep.SOLID;
            if (ground && y == top) {
                return (byte) (NavSweep.SOLID | NavTerrainCapture.SUPPORT
                        | world.surface(x, y, z).code() << NavTerrainCapture.SURFACE_SHIFT);
            }
            return NavTerrainCapture.CLEAR;
        };
    }

    static NavSweep.Revalidation read(NavSweep sweep, StreetWorld world) {
        int rounds = 0;
        while (!sweep.step(reader(world), 512, Long.MAX_VALUE, () -> 0L)) {
            assertTrue(++rounds < 100_000, "the sweep must finish");
        }
        return sweep.revalidation("hash");
    }

    /** A street whose crossings carry the given paint, one character per road block from the north curb: R or W. */
    static StreetWorld painted(String paint, int length) {
        StreetWorld.Builder street = StreetWorld.strip(paint.length(), length);
        for (int i = 0; i < paint.length(); i++) street.fill(0, 3 + i, length - 1, 3 + i, paint.charAt(i));
        return street.build();
    }

    /**
     * Real crosswalks have stripes and unpainted ends. A waypoint build reads each crossing cell's paint from the
     * world, so the graph it saves matches what the scanner makes of the same street, whichever link kind staff used.
     */
    @Test
    void stripedAndPartlyPaintedCrossingsBuildToTheScannersGraph() {
        for (String paint : List.of("RWRWRWR", "RWWWWWR", "RRRWRRR", "WRWRW", "RWWR")) {
            StreetWorld world = painted(paint, LENGTH);
            Map<String, String> scanned = streetPart(world.graph());
            assertFalse(scanned.isEmpty(), paint);
            for (LinkKind kind : List.of(LinkKind.CROSS, LinkKind.CROSSWALK)) {
                WaypointDensifier.Result built = WaypointDensifier.densify(asWaypoints(paint.length(), LENGTH, kind),
                        StreetWorld.RULES, 1, WaypointDensifier.Limits.DEFAULT);
                NavSweep sweep = NavSweep.forWaypointBuild(built.graph(), "hash", built.crossingCells());
                NavSweep.Revalidation r = read(sweep, world);
                assertEquals(NavSweep.Verdict.CLEAN, r.verdict(), paint + " " + kind + " " + r.lines());
                WaypointDensifier.Result saved = WaypointDensifier.restripe(built, sweep.streetClasses(), StreetWorld.RULES);
                assertEquals(scanned, streetPart(saved.graph()), paint + " " + kind);
                assertEquals(LENGTH, saved.crosswalkLinks() + saved.plainLinks());
                // What is saved reads back clean even under the exact compare that publish and revalidate use.
                assertEquals(NavSweep.Verdict.CLEAN, read(NavSweep.forRevalidation(saved.graph(), "hash"), world).verdict(),
                        paint + " " + kind);
            }
        }
    }

    @Test
    void onlyCrossingCellsMayShowEitherStreetClass() {
        StreetWorld striped = painted("RWRWRWR", LENGTH);
        WaypointDensifier.Result built = WaypointDensifier.densify(asWaypoints(7, LENGTH, LinkKind.CROSSWALK),
                StreetWorld.RULES, 1, WaypointDensifier.Limits.DEFAULT);
        // The exact compare that scans and route files keep still refuses the stripes.
        NavSweep.Revalidation exact = read(NavSweep.forRevalidation(built.graph(), "hash"), striped);
        assertEquals(NavSweep.Verdict.DRIFTED, exact.verdict());
        assertTrue(exact.changedClass() > 0);
        // A curb point standing on road is still a changed class in a waypoint build.
        StreetWorld curbOnRoad = StreetWorld.strip(7, LENGTH).fill(0, 3, LENGTH - 1, 9, 'W').set(4, 2, 'R').build();
        NavSweep.Revalidation curb = read(NavSweep.forWaypointBuild(built.graph(), "hash", built.crossingCells()), curbOnRoad);
        assertEquals(NavSweep.Verdict.DRIFTED, curb.verdict());
        assertEquals(1, curb.changedClass());
        // A crossing cell with no class at all is not street paint either way.
        StreetWorld gap = StreetWorld.strip(7, LENGTH).fill(0, 3, LENGTH - 1, 9, 'W').set(5, 6, 'U').build();
        NavSweep.Revalidation hole = read(NavSweep.forWaypointBuild(built.graph(), "hash", built.crossingCells()), gap);
        assertEquals(NavSweep.Verdict.DRIFTED, hole.verdict());
        // A world painted exactly as the links say changes nothing.
        StreetWorld full = StreetWorld.strip(7, LENGTH).fill(0, 3, LENGTH - 1, 9, 'W').build();
        NavSweep sweep = NavSweep.forWaypointBuild(built.graph(), "hash", built.crossingCells());
        assertEquals(NavSweep.Verdict.CLEAN, read(sweep, full).verdict());
        WaypointDensifier.Result same = WaypointDensifier.restripe(built, sweep.streetClasses(), StreetWorld.RULES);
        assertEquals(streetPart(built.graph()), streetPart(same.graph()));
        assertEquals(built.graph().edges(), same.graph().edges());
    }

    @Test
    void aClippedStreetCannotVouchForTheCrossingWidth() {
        var clipped = StreetWorld.strip(7, 6).build().scan(StreetWorld.RULES);
        assertTrue(streetPart(clipped.graph()).isEmpty());
        assertTrue(clipped.stats().dropped().getOrDefault(NavScanLogic.Drop.BOX_EDGE, 0) > 0);
    }
}
