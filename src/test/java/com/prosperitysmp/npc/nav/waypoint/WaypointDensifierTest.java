package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.FileGraphCodec;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphValidator;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.StreetAudit;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointDensifierTest {

    static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    private static final WaypointDensifier.Limits CAPS = WaypointDensifier.Limits.DEFAULT;

    static Point p(String name, int x, int y, int z, String... tags) {
        return new Point(name, x, y, z, PointKind.SIDEWALK, new java.util.TreeSet<>(Set.of(tags)));
    }

    static WaypointSet set(List<Point> points, Object... links) {
        WaypointSet set = WaypointSet.EMPTY;
        for (Point point : points) set = set.addPoint(point, Limits.DEFAULT);
        for (int i = 0; i < links.length; i += 3) {
            set = set.link((String) links[i], (String) links[i + 1], (LinkKind) links[i + 2], Limits.DEFAULT);
        }
        return set;
    }

    /** Two sidewalks four blocks apart across a road: a plain crossing at z 0 and a painted one at z 4. */
    static WaypointSet street() {
        return set(List.of(p("w0", 0, 64, 0, "home"), p("w4", 0, 64, 4), p("e0", 5, 64, 0, "work"), p("e4", 5, 64, 4)),
                "w0", "w4", LinkKind.WALK, "e0", "e4", LinkKind.WALK,
                "w0", "e0", LinkKind.CROSS, "w4", "e4", LinkKind.CROSSWALK);
    }

    static NavGraph.Node at(NavGraph graph, int x, int y, int z) {
        return graph.nodes().stream().filter(n -> n.x() == x && n.y() == y && n.z() == z).findFirst().orElseThrow();
    }

    static int cost(NavGraph graph, NavGraph.Node from, NavGraph.Node to) {
        return graph.neighbors(from.id()).stream().filter(e -> e.to() == to.id()).findFirst().orElseThrow().cost();
    }

    // D1
    @Test
    void everyEdgeJoinsNeighboursWithAPositiveCostBothWays() {
        Random random = new Random(66);
        for (int round = 0; round < 40; round++) {
            List<Point> points = new ArrayList<>();
            List<Object> links = new ArrayList<>();
            int x = 0;
            int y = 64;
            int z = 0;
            for (int i = 0; i < 8; i++) {
                points.add(p("p" + i, x, y, z));
                if (i > 0) {
                    links.add("p" + (i - 1));
                    links.add("p" + i);
                    links.add(LinkKind.WALK);
                }
                int length = 1 + random.nextInt(6);
                int climb = random.nextInt(3) - 1;
                if (random.nextBoolean()) x += length; else z += length;
                y += climb;
            }
            NavGraph graph = WaypointDensifier.densify(set(points, links.toArray()), RULES, 1, CAPS).graph();
            for (NavGraph.Edge edge : graph.edges()) {
                NavGraph.Node from = graph.node(edge.from());
                NavGraph.Node to = graph.node(edge.to());
                assertTrue(edge.cost() > 0);
                assertEquals(1, Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()), "round " + round);
                assertTrue(Math.abs(from.y() - to.y()) <= 1);
                assertTrue(graph.hasPositiveEdge(edge.to(), edge.from()), "reverse of " + edge);
            }
            assertTrue(NavGraphValidator.validate(graph).valid());
        }
    }

    // D2
    @Test
    void aLinkGivesTheSameCellsWhicheverWayRoundItIsWritten() {
        Point a = p("a", 3, 70, 10);
        Point b = p("b", 3, 64, 2);
        List<int[]> forward = WaypointDensifier.cells(a, b);
        List<int[]> backward = WaypointDensifier.cells(b, a);
        assertEquals(forward.size(), backward.size());
        for (int i = 0; i < forward.size(); i++) assertTrue(java.util.Arrays.equals(forward.get(i), backward.get(i)));
        assertTrue(java.util.Arrays.equals(new int[]{3, 64, 2}, forward.getFirst()));
        assertTrue(java.util.Arrays.equals(new int[]{3, 70, 10}, forward.getLast()));
        for (int i = 1; i < forward.size(); i++) assertTrue(Math.abs(forward.get(i)[1] - forward.get(i - 1)[1]) <= 1);
        NavGraph one = WaypointDensifier.densify(set(List.of(a, b), "a", "b", LinkKind.WALK), RULES, 1, CAPS).graph();
        NavGraph two = WaypointDensifier.densify(set(List.of(b, a), "b", "a", LinkKind.WALK), RULES, 1, CAPS).graph();
        assertEquals(FileGraphCodec.write(one, RULES), FileGraphCodec.write(two, RULES));
    }

    // D3
    @Test
    void bentAndSteepLinksAreRefusedByName() {
        EditRefused bent = assertThrows(EditRefused.class, () -> WaypointDensifier.densify(
                set(List.of(p("a", 0, 64, 0), p("b", 3, 64, 4)), "a", "b", LinkKind.WALK), RULES, 1, CAPS));
        assertEquals("WP_NOT_AXIS", bent.code());
        assertTrue(bent.getMessage().contains("a-b"));
        EditRefused steep = assertThrows(EditRefused.class, () -> WaypointDensifier.densify(
                set(List.of(p("a", 0, 64, 0), p("b", 0, 68, 3)), "a", "b", LinkKind.WALK), RULES, 1, CAPS));
        assertEquals("WP_TOO_STEEP", steep.code());
        assertTrue(steep.getMessage().contains("a-b"));
        // With max-step 0 any climb is too steep; a level link is fine.
        assertThrows(EditRefused.class, () -> WaypointDensifier.densify(
                set(List.of(p("a", 0, 64, 0), p("b", 0, 65, 3)), "a", "b", LinkKind.WALK), RULES, 0, CAPS));
        WaypointDensifier.densify(set(List.of(p("a", 0, 64, 0), p("b", 0, 64, 3)), "a", "b", LinkKind.WALK), RULES, 0, CAPS);
        assertEquals("WP_NOT_AXIS", WaypointSetCheck.check(set(List.of(p("a", 0, 64, 0), p("b", 3, 64, 4)), "a", "b", LinkKind.WALK),
                RULES, 1, CAPS).issues().getFirst().code());
    }

    // D4
    @Test
    void junctionsMergeButDifferentHeightsDoNot() {
        // A plus shape: two links crossing at (5,64,5) share one node.
        WaypointSet plus = set(List.of(p("n", 5, 64, 0), p("s", 5, 64, 10), p("w", 0, 64, 5), p("e", 10, 64, 5)),
                "n", "s", LinkKind.WALK, "w", "e", LinkKind.WALK);
        NavGraph graph = WaypointDensifier.densify(plus, RULES, 1, CAPS).graph();
        assertEquals(21, graph.nodeCount());
        assertEquals(4, graph.neighbors(at(graph, 5, 64, 5).id()).size());
        // A bridge: the same column at another height stays a separate node.
        WaypointSet bridge = set(List.of(p("n", 5, 70, 0), p("s", 5, 70, 10), p("w", 0, 64, 5), p("e", 10, 64, 5)),
                "n", "s", LinkKind.WALK, "w", "e", LinkKind.WALK);
        NavGraph two = WaypointDensifier.densify(bridge, RULES, 1, CAPS).graph();
        assertEquals(22, two.nodeCount());
        assertNotNull(at(two, 5, 70, 5));
        assertNotNull(at(two, 5, 64, 5));
    }

    // D5
    @Test
    void crossingCostsMatchTheScannerForPlainAndPaintedCrossings() {
        NavGraph graph = WaypointDensifier.densify(street(), RULES, 1, CAPS).graph();
        NavGraph.Node w0 = at(graph, 0, 64, 0);
        NavGraph.Node r1 = at(graph, 1, 64, 0);
        NavGraph.Node r4 = at(graph, 4, 64, 0);
        NavGraph.Node e0 = at(graph, 5, 64, 0);
        assertEquals(NavSurface.ROAD, r1.surface());
        assertEquals(4, r1.roadWidth());
        assertEquals(10 + 20 * 12, cost(graph, w0, r1));
        assertEquals(10, cost(graph, r1, w0));
        assertEquals(10 + 20 * 12, cost(graph, e0, r4));
        assertEquals(10, cost(graph, r4, e0));
        NavGraph.Node c1 = at(graph, 1, 64, 4);
        assertEquals(NavSurface.CROSSING, c1.surface());
        assertEquals(10, cost(graph, at(graph, 0, 64, 4), c1));
        for (int reach : new int[]{0, 64}) {
            StreetRules rules = new StreetRules(reach, 16, 0.4);
            NavGraph g = WaypointDensifier.densify(street(), rules, 1, CAPS).graph();
            int entry = cost(g, at(g, 0, 64, 0), at(g, 1, 64, 0));
            assertEquals(10 + 20 * reach, entry);
            if (reach == 64) {
                assertEquals(1290, entry);
                // The router sums costs in a long; the largest edge in a full graph stays far from overflow.
                assertTrue((long) entry * Dijkstra.MAX_EDGES_EXAMINED < Long.MAX_VALUE / 4);
            }
        }
    }

    // D6
    @Test
    void eachCrossingRuleFiresOnItsFixtureAndItsCleanTwinPasses() {
        record Case(String code, WaypointSet bad, WaypointSet clean) { }
        Point a = p("a", 0, 64, 0);
        Point b = p("b", 5, 64, 0);
        List<Case> cases = List.of(
                new Case("WP_CROSS_ENDS",
                        set(List.of(a, new Point("b", 5, 64, 0, PointKind.ACCESS, null)), "a", "b", LinkKind.CROSS),
                        set(List.of(a, b), "a", "b", LinkKind.CROSS)),
                new Case("WP_CROSS_LEN", set(List.of(a, p("b", 1, 64, 0)), "a", "b", LinkKind.CROSS),
                        set(List.of(a, p("b", 2, 64, 0)), "a", "b", LinkKind.CROSS)),
                new Case("WP_CROSS_LEN", set(List.of(a, p("b", 18, 64, 0)), "a", "b", LinkKind.CROSS),
                        set(List.of(a, p("b", 17, 64, 0)), "a", "b", LinkKind.CROSS)),
                new Case("WP_CROSS_LEVEL", set(List.of(a, p("b", 5, 65, 0)), "a", "b", LinkKind.CROSSWALK),
                        set(List.of(a, b), "a", "b", LinkKind.CROSSWALK)),
                new Case("WP_CROSS_JUNCTION",
                        set(List.of(a, b, p("n", 2, 64, -3), p("s", 2, 64, 3)), "a", "b", LinkKind.CROSS, "n", "s", LinkKind.CROSS),
                        set(List.of(a, b, p("n", 7, 64, -3), p("s", 7, 64, 3)), "a", "b", LinkKind.CROSS, "n", "s", LinkKind.CROSS)),
                new Case("WP_CROSS_ON_POINT", set(List.of(a, b, p("mid", 2, 64, 0)), "a", "b", LinkKind.CROSS),
                        set(List.of(a, b, p("mid", 2, 64, 1)), "a", "b", LinkKind.CROSS)),
                new Case("WP_WALK_ON_STREET",
                        set(List.of(a, b, p("n", 2, 64, -3), p("s", 2, 64, 3)), "a", "b", LinkKind.CROSS, "n", "s", LinkKind.WALK),
                        set(List.of(a, b, p("n", 7, 64, -3), p("s", 7, 64, 3)), "a", "b", LinkKind.CROSS, "n", "s", LinkKind.WALK)));
        for (Case c : cases) {
            WaypointSetCheck.Report bad = WaypointSetCheck.check(c.bad(), RULES, 1, CAPS);
            assertTrue(bad.issues().stream().anyMatch(i -> i.code().equals(c.code())), c.code() + " " + bad.issues());
            assertFalse(bad.ok());
            EditRefused refused = assertThrows(EditRefused.class, () -> WaypointDensifier.densify(c.bad(), RULES, 1, CAPS));
            assertEquals(c.code(), refused.code());
            WaypointSetCheck.Report clean = WaypointSetCheck.check(c.clean(), RULES, 1, CAPS);
            assertTrue(clean.ok(), c.code() + " twin: " + clean.issues());
            WaypointDensifier.densify(c.clean(), RULES, 1, CAPS);
        }
    }

    // D7
    @Test
    void aRebuildIsByteIdentical() {
        String first = FileGraphCodec.write(WaypointDensifier.densify(street(), RULES, 1, CAPS).graph(), RULES);
        WaypointSet reparsed = WaypointCodec.parse(WaypointCodec.canonical(street()));
        String second = FileGraphCodec.write(WaypointDensifier.densify(reparsed, RULES, 1, CAPS).graph(), RULES);
        assertEquals(first, second);
        NavGraph a = WaypointDensifier.densify(street(), RULES, 1, CAPS).graph();
        NavGraph b = WaypointDensifier.densify(reparsed, RULES, 1, CAPS).graph();
        assertEquals(a.edges(), b.edges());
        for (NavGraph.Node node : a.nodes()) assertEquals(node, b.node(node.id()));
    }

    // D8
    @Test
    void theNodeCapRefuses() {
        WaypointSet huge = set(List.of(p("a", 0, 64, 0), p("b", 1100, 64, 0)), "a", "b", LinkKind.WALK);
        EditRefused refused = assertThrows(EditRefused.class, () -> WaypointDensifier.densify(huge, RULES, 1, CAPS));
        assertEquals("WP_CAP_NODES", refused.code());
        assertTrue(refused.getMessage().contains("split this district into two regions".replace("split", "Split")));
        assertTrue(WaypointSetCheck.check(huge, RULES, 1, CAPS).issues().stream().anyMatch(i -> i.code().equals("WP_CAP_NODES")));
        WaypointSet fits = set(List.of(p("a", 0, 64, 0), p("b", 1023, 64, 0)), "a", "b", LinkKind.WALK);
        assertEquals(1024, WaypointDensifier.densify(fits, RULES, 1, CAPS).graph().nodeCount());
    }

    // D10
    @Test
    void aGoodSetPassesTheStreetAuditAndAnUnreachableStopFails() {
        WaypointSet good = set(List.of(p("w0", 0, 64, 0, "home"), p("w4", 0, 64, 4), p("e0", 5, 64, 0, "work"),
                        p("e4", 5, 64, 4)),
                "w0", "w4", LinkKind.WALK, "e0", "e4", LinkKind.WALK, "w0", "e0", LinkKind.CROSS);
        StreetAudit.Options options = new StreetAudit.Options(RULES, 5_000_000_000L, 1024, System::nanoTime);
        NavGraph graph = WaypointDensifier.densify(good, RULES, 1, CAPS).graph();
        StreetAudit.Result pass = StreetAudit.audit(graph, null, options);
        assertEquals(StreetAudit.Verdict.PASS, pass.verdict(), pass.examples().toString());
        WaypointSet island = good.addPoint(p("far", 40, 64, 40, "market"), Limits.DEFAULT)
                .addPoint(p("far2", 40, 64, 44), Limits.DEFAULT).link("far", "far2", LinkKind.WALK, Limits.DEFAULT);
        StreetAudit.Result fail = StreetAudit.audit(WaypointDensifier.densify(island, RULES, 1, CAPS).graph(), null, options);
        assertEquals(StreetAudit.Verdict.FAIL, fail.verdict());
        assertTrue(fail.count(StreetAudit.Code.NO_ROUTE) > 0);
    }

    @Test
    void aLinkCannotAllocateMoreCellsThanAnyGraphCanHold() {
        var a = p("a", 0, 64, 0);
        var b = p("b", Dijkstra.MAX_GRAPH_NODES, 64, 0);
        var refused = assertThrows(EditRefused.class, () -> WaypointDensifier.cells(a, b));
        assertEquals("WP_CAP_NODES", refused.code());
        assertThrows(EditRefused.class, () -> WaypointDensifier.cells(
                p("min", Integer.MIN_VALUE, 64, 0), p("max", Integer.MAX_VALUE, 64, 0)));
    }

    @Test
    void aHeightDifferenceCannotWrapPastTheStepLimit() {
        var set = set(List.of(p("a", 0, Integer.MIN_VALUE, 0), p("b", 1, Integer.MAX_VALUE, 0)),
                "a", "b", LinkKind.WALK);
        var refused = assertThrows(EditRefused.class, () -> WaypointDensifier.densify(set, RULES, 1, CAPS));
        assertEquals("WP_TOO_STEEP", refused.code());
        assertTrue(WaypointSetCheck.check(set, RULES, 1, CAPS).issues().stream()
                .anyMatch(issue -> issue.code().equals("WP_TOO_STEEP")));
    }

    @Test
    void provenanceNamesThePointOrTheLinkCell() {
        WaypointDensifier.Result built = WaypointDensifier.densify(street(), RULES, 1, CAPS);
        Map<String, String> byCell = new HashMap<>();
        for (NavGraph.Node node : built.graph().nodes()) {
            byCell.put(node.x() + "," + node.y() + "," + node.z(), built.provenance().get(node.id()));
        }
        assertEquals("point w0", byCell.get("0,64,0"));
        assertEquals("link e0-w0, cell 3", byCell.get("3,64,0"));
        assertEquals(1, built.crosswalkLinks());
        assertEquals(1, built.plainLinks());
    }

    @Test
    void checkWarnsAboutIsolatedPointsSplitGroupsAndNoStops() {
        WaypointSet set = set(List.of(p("a", 0, 64, 0), p("b", 0, 64, 3), p("c", 9, 64, 0), p("d", 9, 64, 3), p("lone", 20, 64, 20)),
                "a", "b", LinkKind.WALK, "c", "d", LinkKind.WALK);
        WaypointSetCheck.Report report = WaypointSetCheck.check(set, RULES, 1, CAPS);
        assertTrue(report.ok());
        assertEquals(Set.of("WP_ISOLATED", "WP_DISCONNECTED", "WP_NO_STOPS"),
                Set.copyOf(report.issues().stream().map(WaypointSetCheck.Issue::code).toList()));
    }
}
