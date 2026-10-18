package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cells along a link, the ordering helpers the graph build depends on, and the pieces of a built result. */
class WaypointDensifierCellsTest {
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    private static final WaypointDensifier.Limits CAPS = WaypointDensifier.Limits.DEFAULT;

    private static Point at(String name, int x, int y, int z) {
        return new Point(name, x, y, z, PointKind.SIDEWALK, null);
    }

    private static List<String> keys(List<int[]> cells) {
        return cells.stream().map(c -> WaypointDensifier.key(c[0], c[1], c[2])).toList();
    }

    // ---- cells ----

    @Test
    void aLinkAlongXListsEveryBlockFromTheLowerXEndToTheOther() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 2, 64, 5), at("b", 5, 64, 5));
        assertEquals(List.of("2,64,5", "3,64,5", "4,64,5", "5,64,5"), keys(cells));
    }

    @Test
    void aLinkAlongZListsEveryBlockFromTheLowerZEndToTheOther() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 7, 64, -2), at("b", 7, 64, 1));
        assertEquals(List.of("7,64,-2", "7,64,-1", "7,64,0", "7,64,1"), keys(cells));
    }

    @Test
    void theArgumentOrderDoesNotChangeTheCells() {
        Point a = at("a", 0, 64, 0);
        Point b = at("b", 0, 66, 4);
        assertEquals(keys(WaypointDensifier.cells(a, b)), keys(WaypointDensifier.cells(b, a)));
    }

    @Test
    void aClimbIsSpreadEvenlyAndRoundsHalfUp() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 0, 64, 0), at("b", 4, 66, 0));
        assertEquals(List.of(64, 65, 65, 66, 66), cells.stream().map(c -> c[1]).toList());
    }

    @Test
    void aDescentFromTheLowerXEndRoundsHalfUpOnTheWayDown() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 0, 66, 0), at("b", 4, 64, 0));
        assertEquals(List.of(66, 66, 65, 65, 64), cells.stream().map(c -> c[1]).toList());
    }

    @Test
    void aClimbOfOnePerBlockStepsUpEveryBlock() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 0, 64, 0), at("b", 0, 68, 4));
        assertEquals(List.of(64, 65, 66, 67, 68), cells.stream().map(c -> c[1]).toList());
    }

    @Test
    void aLevelLinkKeepsOneHeight() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 0, 70, 0), at("b", 6, 70, 0));
        assertEquals(Set.of(70), Set.copyOf(cells.stream().map(c -> c[1]).toList()));
        assertEquals(7, cells.size());
    }

    @Test
    void theEndsOfALinkAreItsFirstAndLastCellsWithTheirOwnHeights() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 0, 61, 0), at("b", 0, 64, 9));
        assertArrayEquals(new int[]{0, 61, 0}, cells.get(0));
        assertArrayEquals(new int[]{0, 64, 9}, cells.get(9));
    }

    @Test
    void aDiagonalLinkHasNoCells() {
        assertNull(WaypointDensifier.cells(at("a", 0, 64, 0), at("b", 3, 64, 4)));
    }

    @Test
    void twoPointsOnTheSameColumnHaveNoCells() {
        assertNull(WaypointDensifier.cells(at("a", 3, 64, 4), at("b", 3, 70, 4)));
    }

    @Test
    void aLinkOfOneBlockHasTwoCells() {
        assertEquals(List.of("0,64,0", "0,64,1"), keys(WaypointDensifier.cells(at("a", 0, 64, 0), at("b", 0, 64, 1))));
    }

    @Test
    void aLinkAtExactlyTheCellLimitIsListedAndOneMoreIsRefusedWithTheCounts() {
        Point a = at("a", 0, 64, 0);
        assertEquals(5, WaypointDensifier.cells(a, at("b", 4, 64, 0), 5).size());
        EditRefused refused = assertThrows(EditRefused.class, () -> WaypointDensifier.cells(a, at("b", 5, 64, 0), 5));
        assertEquals("WP_CAP_NODES", refused.code());
        assertEquals("Link a-b makes 6 nodes; the limit is 5. Split this district into two regions.", refused.getMessage());
    }

    @Test
    void theLimitNeverExceedsWhatAnyGraphCanHold() {
        Point a = at("a", 0, 64, 0);
        EditRefused refused = assertThrows(EditRefused.class,
                () -> WaypointDensifier.cells(a, at("b", Dijkstra.MAX_GRAPH_NODES, 64, 0), Integer.MAX_VALUE));
        assertEquals("Link a-b makes 20001 nodes; the limit is 20000. Split this district into two regions.", refused.getMessage());
        assertEquals(Dijkstra.MAX_GRAPH_NODES, WaypointDensifier.cells(a, at("b", Dijkstra.MAX_GRAPH_NODES - 1, 64, 0), Integer.MAX_VALUE).size());
    }

    @Test
    void aLinkSpanningTheWholeIntRangeIsRefusedNotWrapped() {
        EditRefused refused = assertThrows(EditRefused.class, () -> WaypointDensifier.cells(
                at("lo", Integer.MIN_VALUE, 64, 0), at("hi", Integer.MAX_VALUE, 64, 0), 1000));
        assertEquals("WP_CAP_NODES", refused.code());
        assertTrue(refused.getMessage().contains("makes 4294967296 nodes"), refused.getMessage());
    }

    @Test
    void startsAtComparesAllThreeCoordinates() {
        List<int[]> cells = WaypointDensifier.cells(at("a", 0, 64, 0), at("b", 0, 64, 3));
        assertTrue(WaypointDensifier.startsAt(cells, at("p", 0, 64, 0)));
        assertFalse(WaypointDensifier.startsAt(cells, at("p", 0, 64, 3)));
        assertFalse(WaypointDensifier.startsAt(cells, at("p", 0, 65, 0)));
        assertFalse(WaypointDensifier.startsAt(cells, at("p", 1, 64, 0)));
    }

    // ---- ordering and keys ----

    @ParameterizedTest
    @CsvSource({"0,0,0,0,0,0,0", "1,0,0,0,9,9,1", "0,0,0,1,0,0,-1", "0,1,0,0,0,9,1", "0,0,9,0,1,0,-1", "0,0,1,0,0,2,-1",
            "5,5,5,5,5,4,1", "-3,0,0,2,0,0,-1"})
    void compareCellOrdersByXThenYThenZ(int ax, int ay, int az, int bx, int by, int bz, int sign) {
        assertEquals(sign, Integer.signum(WaypointDensifier.compareCell(new int[]{ax, ay, az}, new int[]{bx, by, bz})));
    }

    @Test
    void compareCellDoesNotOverflowOnTheExtremes() {
        assertEquals(-1, Integer.signum(WaypointDensifier.compareCell(new int[]{Integer.MIN_VALUE, 0, 0}, new int[]{Integer.MAX_VALUE, 0, 0})));
        assertEquals(1, Integer.signum(WaypointDensifier.compareCell(new int[]{0, Integer.MAX_VALUE, 0}, new int[]{0, Integer.MIN_VALUE, 0})));
    }

    @Test
    void keyIsTheThreeCoordinatesWithCommasAndMinusSigns() {
        assertEquals("-1,2,-3", WaypointDensifier.key(-1, 2, -3));
        assertEquals("0,0,0", WaypointDensifier.key(0, 0, 0));
        assertEquals("-2147483648,2147483647,0", WaypointDensifier.key(Integer.MIN_VALUE, Integer.MAX_VALUE, 0));
    }

    // ---- the built result ----

    private static NavGraph.Node nodeAt(NavGraph graph, int x, int y, int z) {
        return graph.nodes().stream().filter(n -> n.x() == x && n.y() == y && n.z() == z).findFirst().orElseThrow();
    }

    private static WaypointSet street() {
        WaypointSet set = WaypointSet.EMPTY;
        for (Point p : List.of(new Point("w", 0, 64, 0, PointKind.SIDEWALK, new TreeSet<>(Set.of("home"))),
                new Point("e", 5, 64, 0, PointKind.SIDEWALK, new TreeSet<>(Set.of("work"))))) {
            set = set.addPoint(p, WaypointSet.Limits.DEFAULT);
        }
        return set.link("w", "e", LinkKind.CROSS, WaypointSet.Limits.DEFAULT);
    }

    @Test
    void nodeIdsFollowPositionOrderFromOne() {
        NavGraph graph = WaypointDensifier.densify(street(), RULES, 1, CAPS).graph();
        for (int x = 0; x <= 5; x++) {
            final int cell = x;
            NavGraph.Node node = graph.nodes().stream().filter(n -> n.x() == cell).findFirst().orElseThrow();
            assertEquals(x + 1L, node.id());
        }
    }

    @Test
    void crossingCellsAreTheInteriorBlocksOnlyNotTheCurbs() {
        WaypointDensifier.Result built = WaypointDensifier.densify(street(), RULES, 1, CAPS);
        assertEquals(Set.of(2L, 3L, 4L, 5L), built.crossingCells());
        assertEquals(1, built.crossings().size());
        assertArrayEquals(new long[]{1, 2, 3, 4, 5, 6}, built.crossings().get(0));
    }

    @Test
    void theCrossingNodesAreRoadAndCarryTheRoadWidthAndTheCurbsDoNot() {
        NavGraph graph = WaypointDensifier.densify(street(), RULES, 1, CAPS).graph();
        assertEquals(NavSurface.SIDEWALK, graph.node(1).surface());
        assertEquals(0, graph.node(1).roadWidth());
        for (long id = 2; id <= 5; id++) {
            assertEquals(NavSurface.ROAD, graph.node(id).surface());
            assertEquals(4, graph.node(id).roadWidth());
        }
        assertEquals(NavSurface.SIDEWALK, graph.node(6).surface());
        assertEquals(Set.of("home"), graph.node(1).tags());
        assertEquals(Set.of("work"), graph.node(6).tags());
    }

    @Test
    void aResultKeepsItsOwnCopyOfTheCrossingChains() {
        long[] chain = {1, 2, 3};
        List<long[]> chains = new ArrayList<>(List.of(chain));
        WaypointDensifier.Result result = new WaypointDensifier.Result(NavGraph.builder().build(), Map.of(), 0, 1, chains);
        chain[0] = 99;
        chains.clear();
        assertEquals(1, result.crossings().size());
        assertArrayEquals(new long[]{1, 2, 3}, result.crossings().get(0));
        assertThrows(UnsupportedOperationException.class, () -> result.crossings().clear());
    }

    @Test
    void twoWalkLinksThatLayTheSameStepKeepOneEdgeEachWay() {
        WaypointSet set = WaypointSet.EMPTY
                .addPoint(at("a", 0, 64, 0), WaypointSet.Limits.DEFAULT).addPoint(at("b", 0, 64, 4), WaypointSet.Limits.DEFAULT)
                .addPoint(at("mid", 0, 64, 2), WaypointSet.Limits.DEFAULT)
                .link("a", "b", LinkKind.WALK, WaypointSet.Limits.DEFAULT)
                .link("a", "mid", LinkKind.WALK, WaypointSet.Limits.DEFAULT);
        NavGraph graph = WaypointDensifier.densify(set, RULES, 1, CAPS).graph();
        assertEquals(5, graph.nodeCount());
        assertEquals(8, graph.edgeCount());
    }

    @Test
    void anAccessWalkBetweenAccessPointsIsAccessGroundAndOtherwiseSidewalk() {
        Point a = new Point("a", 0, 64, 0, PointKind.ACCESS, null);
        Point b = new Point("b", 0, 64, 4, PointKind.ACCESS, null);
        Point c = new Point("c", 4, 64, 0, PointKind.SIDEWALK, null);
        WaypointSet set = WaypointSet.of(List.of(a, b, c), List.of(new WaypointSet.Link("a", "b", LinkKind.WALK),
                new WaypointSet.Link("a", "c", LinkKind.WALK)));
        NavGraph graph = WaypointDensifier.densify(set, RULES, 1, CAPS).graph();
        assertEquals(NavSurface.ACCESS, nodeAt(graph, 0, 64, 2).surface());
        assertEquals(NavSurface.SIDEWALK, nodeAt(graph, 2, 64, 0).surface());
        assertEquals(NavSurface.ACCESS, nodeAt(graph, 0, 64, 0).surface());
    }
}
