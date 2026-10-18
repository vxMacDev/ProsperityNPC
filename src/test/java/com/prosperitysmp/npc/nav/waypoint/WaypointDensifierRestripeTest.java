package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Re-reading a built waypoint graph's crossings against the paint the world shows. */
class WaypointDensifierRestripeTest {
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    private static final WaypointDensifier.Limits CAPS = WaypointDensifier.Limits.DEFAULT;

    /** Two sidewalk points five blocks apart with a four-block crossing between them (node ids 1 to 6 along x). */
    private static WaypointDensifier.Result built(LinkKind kind) {
        WaypointSet set = WaypointSet.EMPTY
                .addPoint(new Point("w", 0, 64, 0, PointKind.SIDEWALK, new TreeSet<>(Set.of("home"))), Limits.DEFAULT)
                .addPoint(new Point("e", 5, 64, 0, PointKind.SIDEWALK, new TreeSet<>(Set.of("work"))), Limits.DEFAULT)
                .link("w", "e", kind, Limits.DEFAULT);
        return WaypointDensifier.densify(set, RULES, 1, CAPS);
    }

    private static int cost(NavGraph graph, long from, long to) {
        return graph.neighbors(from).stream().filter(e -> e.to() == to).findFirst().orElseThrow().cost();
    }

    @Test
    void aPlainCrossingWhoseMiddleTwoCellsAreReallyPaintedBecomesACrosswalkAndStopsCostingTheEntryPenalty() {
        WaypointDensifier.Result before = built(LinkKind.CROSS);
        assertEquals(250, cost(before.graph(), 1, 2));
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, Map.of(3L, NavSurface.CROSSING, 4L, NavSurface.CROSSING), RULES);
        assertEquals(NavSurface.ROAD, after.graph().node(2).surface());
        assertEquals(NavSurface.CROSSING, after.graph().node(3).surface());
        assertEquals(NavSurface.CROSSING, after.graph().node(4).surface());
        assertEquals(NavSurface.ROAD, after.graph().node(5).surface());
        assertEquals(10, cost(after.graph(), 1, 2));
        assertEquals(10, cost(after.graph(), 6, 5));
        assertEquals(1, after.crosswalkLinks());
        assertEquals(0, after.plainLinks());
    }

    @Test
    void aPaintedCrossingWhoseWorldShowsOnlyRoadBecomesPlainAndPaysTheEntryPenaltyAgain() {
        WaypointDensifier.Result before = built(LinkKind.CROSSWALK);
        assertEquals(10, cost(before.graph(), 1, 2));
        Map<Long, NavSurface> road = Map.of(2L, NavSurface.ROAD, 3L, NavSurface.ROAD, 4L, NavSurface.ROAD, 5L, NavSurface.ROAD);
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, road, RULES);
        assertEquals(NavSurface.ROAD, after.graph().node(3).surface());
        assertEquals(250, cost(after.graph(), 1, 2));
        assertEquals(250, cost(after.graph(), 6, 5));
        assertEquals(0, after.crosswalkLinks());
        assertEquals(1, after.plainLinks());
    }

    @Test
    void aCellTheWorldReadAsNotStreetKeepsItsBuiltClass() {
        WaypointDensifier.Result before = built(LinkKind.CROSSWALK);
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, Map.of(3L, NavSurface.SIDEWALK, 4L, NavSurface.UNKNOWN), RULES);
        assertEquals(NavSurface.CROSSING, after.graph().node(3).surface());
        assertEquals(NavSurface.CROSSING, after.graph().node(4).surface());
        assertEquals(1, after.crosswalkLinks());
    }

    @Test
    void aCellMissingFromTheObservedMapKeepsItsBuiltClass() {
        WaypointDensifier.Result before = built(LinkKind.CROSSWALK);
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, Map.of(2L, NavSurface.ROAD), RULES);
        assertEquals(NavSurface.ROAD, after.graph().node(2).surface());
        assertEquals(NavSurface.CROSSING, after.graph().node(3).surface());
        assertEquals(NavSurface.CROSSING, after.graph().node(4).surface());
        assertEquals(NavSurface.CROSSING, after.graph().node(5).surface());
        assertEquals(1, after.crosswalkLinks(), "two painted cells inside still make a crosswalk");
    }

    @Test
    void theCurbNodesAreNeverReclassifiedWhateverTheWorldSays() {
        WaypointDensifier.Result before = built(LinkKind.CROSS);
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, Map.of(1L, NavSurface.CROSSING, 6L, NavSurface.ROAD), RULES);
        assertEquals(NavSurface.SIDEWALK, after.graph().node(1).surface());
        assertEquals(NavSurface.SIDEWALK, after.graph().node(6).surface());
    }

    @Test
    void nothingObservedGivesTheSameGraphAndTheSameCounts() {
        WaypointDensifier.Result before = built(LinkKind.CROSS);
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, Map.of(), RULES);
        assertEquals(before.graph().edges(), after.graph().edges());
        for (NavGraph.Node node : before.graph().nodes()) assertEquals(node, after.graph().node(node.id()));
        assertEquals(before.crosswalkLinks(), after.crosswalkLinks());
        assertEquals(before.plainLinks(), after.plainLinks());
    }

    @Test
    void idsTagsWidthsProvenanceAndEdgeOrderSurviveARestripe() {
        WaypointDensifier.Result before = built(LinkKind.CROSS);
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, Map.of(3L, NavSurface.CROSSING, 4L, NavSurface.CROSSING), RULES);
        assertEquals(before.graph().nodeCount(), after.graph().nodeCount());
        assertEquals(Set.of("home"), after.graph().node(1).tags());
        assertEquals(Set.of("work"), after.graph().node(6).tags());
        assertEquals(4, after.graph().node(3).roadWidth());
        assertEquals(before.provenance(), after.provenance());
        assertEquals(before.graph().edges().stream().map(e -> e.from() + ">" + e.to()).toList(),
                after.graph().edges().stream().map(e -> e.from() + ">" + e.to()).toList());
        assertEquals(1, after.crossings().size());
        assertArrayEquals(before.crossings().get(0), after.crossings().get(0));
    }

    @Test
    void onlyTheTwoCurbStepsOntoACrossingChangeCostAndEveryOtherEdgeKeepsItsCost() {
        WaypointDensifier.Result before = built(LinkKind.CROSS);
        WaypointDensifier.Result after = WaypointDensifier.restripe(before, Map.of(3L, NavSurface.CROSSING, 4L, NavSurface.CROSSING), RULES);
        List<String> changed = before.graph().edges().stream()
                .filter(e -> cost(after.graph(), e.from(), e.to()) != e.cost()).map(e -> e.from() + ">" + e.to()).toList();
        assertEquals(List.of("1>2", "6>5"), changed.stream().sorted().toList());
    }

    @Test
    void theOriginalResultIsNotChangedByARestripe() {
        WaypointDensifier.Result before = built(LinkKind.CROSS);
        WaypointDensifier.restripe(before, Map.of(3L, NavSurface.CROSSING, 4L, NavSurface.CROSSING), RULES);
        assertEquals(NavSurface.ROAD, before.graph().node(3).surface());
        assertEquals(250, cost(before.graph(), 1, 2));
    }

    @Test
    void theStreetRulesPassedInDecideThePenaltyAndTheShareOfTheRestripe() {
        WaypointDensifier.Result before = built(LinkKind.CROSS);
        StreetRules fewer = new StreetRules(5, 16, 0.4);
        WaypointDensifier.Result plain = WaypointDensifier.restripe(before, Map.of(), fewer);
        assertEquals(10 + 20 * 5, cost(plain.graph(), 1, 2));
        StreetRules strict = new StreetRules(12, 16, 1.0);
        WaypointDensifier.Result allPainted = WaypointDensifier.restripe(before, Map.of(3L, NavSurface.CROSSING, 4L, NavSurface.CROSSING), strict);
        assertEquals(1, allPainted.crosswalkLinks(), "both inner cells are painted, which is the whole share");
        WaypointDensifier.Result half = WaypointDensifier.restripe(before, Map.of(3L, NavSurface.CROSSING), strict);
        assertEquals(0, half.crosswalkLinks());
        assertEquals(250, cost(half.graph(), 1, 2));
    }
}
