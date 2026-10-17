package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.resident.ResidentRoutePlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.prosperitysmp.npc.nav.StreetWorld.RULES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Routes through built street graphs, planned the way residents plan them. */
class StreetRoutingTest {
    private static final int REACH = RULES.crosswalkReach();

    private record Trip(NavGraph graph, ResidentRoutePlanner.Decision decision) {
        List<NavGraph.Node> nodes() {
            return decision.nodes().stream().map(graph::node).toList();
        }

        /** Maximal runs of consecutive street nodes on the path. */
        List<List<NavGraph.Node>> runs() {
            List<List<NavGraph.Node>> runs = new ArrayList<>();
            List<NavGraph.Node> current = null;
            for (NavGraph.Node n : nodes()) {
                if (n.surface().isStreet()) {
                    if (current == null) runs.add(current = new ArrayList<>());
                    current.add(n);
                } else {
                    current = null;
                }
            }
            return runs;
        }
    }

    private static Trip trip(StreetWorld world, int fromX, int fromZ, int toX, int toZ) {
        NavGraph base = world.graph();
        long from = StreetWorld.node(base, fromX, fromZ).id();
        long to = StreetWorld.node(base, toX, toZ).id();
        NavGraph g = StreetWorld.tag(base, Map.of(to, "work"));
        return new Trip(g, ResidentRoutePlanner.planToTag(g, from, "work"));
    }

    // R1
    @ParameterizedTest
    @ValueSource(ints = {3, 5, 9, 13})
    void aSameSideTripNeverTouchesTheRoad(int width) {
        Trip t = trip(StreetWorld.strip(width, 30).build(), 3, 1, 22, 2);
        assertEquals(ResidentRoutePlanner.Status.WALK, t.decision().status());
        assertTrue(t.nodes().stream().noneMatch(n -> n.surface().isStreet()));
        assertTrue(t.runs().isEmpty());
    }

    // R2
    @ParameterizedTest
    @ValueSource(ints = {3, 5, 9, 13})
    void withNoCrosswalkTheTripCrossesOnceStraightOverTheShortestStretch(int width) {
        Trip t = trip(StreetWorld.strip(width, 30).build(), 8, 2, 8, width + 3);
        assertEquals(ResidentRoutePlanner.Status.WALK, t.decision().status());
        assertEquals(1, t.runs().size());
        List<NavGraph.Node> run = t.runs().getFirst();
        assertEquals(width, run.size());
        assertTrue(run.stream().allMatch(n -> n.x() == 8), "no lateral movement on the road");
        assertEquals(10L * (width + 1) + RULES.entryPenalty(), t.decision().cost());
    }

    // R3
    @ParameterizedTest
    @ValueSource(ints = {5, 9, 13})
    void aCrosswalkIsUsedExactlyWhenTheDetourIsUnderTheReachWhateverTheRoadWidth(int width) {
        for (int side : new int[]{1, -1}) {
            int from = side > 0 ? 5 : 25;
            // One block inside the reach: the painted crossing wins.
            int near = from + side * (REACH - 1);
            Trip used = trip(StreetWorld.strip(width, 30).fill(near, 3, near, 2 + width, 'W').build(), from, 2, from, width + 3);
            assertEquals(1, used.runs().size());
            assertTrue(used.runs().getFirst().stream().allMatch(n -> n.x() == near && n.surface() == NavSurface.CROSSING),
                    "width " + width + " side " + side + " should use the crosswalk at x=" + near);
            assertEquals(2L * 10 * (REACH - 1) + 10L * (width + 1), used.decision().cost());
            // One block outside: the straight crossing wins.
            int far = from + side * (REACH + 1);
            Trip straight = trip(StreetWorld.strip(width, 30).fill(far, 3, far, 2 + width, 'W').build(), from, 2, from, width + 3);
            assertEquals(1, straight.runs().size());
            assertTrue(straight.runs().getFirst().stream().allMatch(n -> n.x() == from && n.surface() == NavSurface.ROAD),
                    "width " + width + " side " + side + " should cross straight at x=" + from);
        }
    }

    // R4
    @ParameterizedTest
    @ValueSource(ints = {3, 5, 9, 13})
    void theDeterministicOldVersusNewControlGivesDifferentAuditVerdicts(int width) {
        int length = width + 8;
        StreetWorld ground = StreetWorld.strip(width, length).build(false);
        StreetWorld truth = StreetWorld.strip(width, length).build(true);
        NavGraph base = ground.scan(RULES).graph();
        long a = StreetWorld.node(base, 0, 1).id();
        long b = StreetWorld.node(base, width + 3, width + 4).id();
        NavGraph legacy = StreetWorld.tag(base, Map.of(a, "home", b, "work"));

        // With the road counted as ordinary ground the single cheapest walk is the pure diagonal.
        var diagonal = ResidentRoutePlanner.planToTag(legacy, a, "work");
        int steps = width + 3;
        assertEquals(14L * steps, diagonal.cost());
        assertEquals(steps + 1, diagonal.nodes().size());
        for (int i = 1; i < diagonal.nodes().size(); i++) {
            var from = legacy.node(diagonal.nodes().get(i - 1));
            var to = legacy.node(diagonal.nodes().get(i));
            assertEquals(1, to.x() - from.x());
            assertEquals(1, to.z() - from.z());
        }

        Map<Long, NavSurface> classes = new HashMap<>();
        for (NavGraph.Node n : legacy.nodes()) classes.put(n.id(), truth.surface(n.x(), n.y() - 1, n.z()));
        var options = new StreetAudit.Options(RULES, 60_000_000_000L, 1024, System::nanoTime);
        var before = StreetAudit.audit(legacy, new StreetAudit.Live(classes, Set.of(), 0, Map.of(), Map.of()), options);
        assertEquals(StreetAudit.Verdict.FAIL, before.verdict(), "the legacy graph cuts across the road");
        assertTrue(before.count(StreetAudit.Code.LATERAL) > 0 || before.count(StreetAudit.Code.NO_SIDEWALK_EXIT) > 0);

        NavGraph baseCity = truth.scan(RULES).graph();
        NavGraph city = StreetWorld.tag(baseCity, Map.of(StreetWorld.node(baseCity, 0, 1).id(), "home",
                StreetWorld.node(baseCity, width + 3, width + 4).id(), "work"));
        var after = StreetAudit.audit(city, null, options);
        assertEquals(StreetAudit.Verdict.PASS, after.verdict(), () -> "city audit: " + after.examples());
        assertTrue(after.paths() > 0);
        assertTrue(before.verdict() != after.verdict());
    }

    // R5
    @Test
    void whenTheSidewalkIsBlockedTheTripCrossesAndRecrossesOrHasNoRoute() {
        StreetWorld open = StreetWorld.strip(5, 21).fill(10, 0, 10, 2, '.').build();
        Trip around = trip(open, 5, 2, 15, 2);
        assertEquals(ResidentRoutePlanner.Status.WALK, around.decision().status());
        List<List<NavGraph.Node>> runs = around.runs();
        assertEquals(2, runs.size(), "over the road and back again");
        for (List<NavGraph.Node> run : runs) {
            assertEquals(5, run.size());
            assertTrue(run.stream().allMatch(n -> n.x() == run.getFirst().x()), "each crossing is straight");
        }
        // Cutting the far sidewalk too leaves nothing: there is no walking along the road to fall back on.
        StreetWorld sealed = StreetWorld.strip(5, 21).fill(10, 0, 10, 2, '.').fill(10, 8, 10, 10, '.').build();
        assertEquals(ResidentRoutePlanner.Status.NO_ROUTE, trip(sealed, 5, 2, 15, 2).decision().status());
    }
}
