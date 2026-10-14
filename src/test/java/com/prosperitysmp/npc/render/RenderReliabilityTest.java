package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.render.RenderPolicy.Vec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure selection/geometry regressions, not Citizens physics or Java/Bedrock visual acceptance. */
class RenderReliabilityTest {
    @Test
    void selectionOrderIsStableBelowCapAndAcrossInputOrder() {
        Map<String, Vec> candidates = new LinkedHashMap<>();
        candidates.put("z", new Vec(1, 0, 0));
        candidates.put("a", new Vec(-1, 0, 0));
        candidates.put("b", new Vec(0, 0, 0));
        var viewers = Map.of("v", new Vec(0, 0, 0));
        assertEquals(List.of("b", "a", "z"), new ArrayList<>(RenderPolicy.select(viewers, candidates, 5, 3, 20)));
        var reversed = new LinkedHashMap<String, Vec>();
        List.of("b", "a", "z").forEach(id -> reversed.put(id, candidates.get(id)));
        assertEquals(new ArrayList<>(RenderPolicy.select(viewers, candidates, 5, 3, 20)),
                new ArrayList<>(RenderPolicy.select(viewers, reversed, 5, 3, 20)));
    }

    @Test
    void globalCapRanksWithinWorldDistancesInsteadOfWorldIterationOrder() {
        var viewers = Map.of("w1", Map.of("v1", new Vec(0, 0, 0)), "w2", Map.of("v2", new Vec(100, 0, 0)));
        var candidates = Map.of("w1", Map.of("a", new Vec(10, 0, 0)), "w2", Map.of("b", new Vec(101, 0, 0)));
        assertEquals(List.of("b"), new ArrayList<>(RenderPolicy.selectWorlds(viewers, candidates, 48, 3, 1)));
    }

    @Test
    void coordinatesNeverGrantSlotsAcrossWorlds() {
        assertTrue(RenderPolicy.selectWorlds(Map.of("w1", Map.of("v", new Vec(0, 0, 0))),
                Map.of("w2", Map.of("c", new Vec(0, 0, 0))), 48, 3, 20).isEmpty());
    }

    @Test
    void invalidRadiusFailsClosed() {
        var points = Map.of("p", new Vec(0, 0, 0));
        for (double radius : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertTrue(RenderPolicy.select(points, points, radius, 3, 20).isEmpty());
        }
    }

    @Test
    void loweredCapOverridesGraceAndKeepsDesiredFirst() {
        var active = Map.of("a", 990L, "b", 980L, "c", 970L);
        assertEquals(List.of("c", "a"), new ArrayList<>(RenderPolicy.retain(active, Set.of("c"), 1000, 100, 2)));
        assertTrue(RenderPolicy.retain(active, Set.of("a"), 1000, 100, 0).isEmpty());
        assertTrue(RenderPolicy.retain(active, Set.of(), 1000, 100, -1).isEmpty());
    }

    @Test
    void graceExpiresAtBoundaryAndDoesNotRetainUnknownIds() {
        assertTrue(RenderPolicy.retain(Map.of("a", 900L), Set.of("new"), 1000, 100, 20).isEmpty());
        assertEquals(Set.of("a"), RenderPolicy.retain(Map.of("a", 901L), Set.of(), 1000, 100, 20));
    }

    @Test
    void desiredRetentionUsesStablePriorityWhenCapFalls() {
        var desired = new LinkedHashSet<>(List.of("c", "a", "b"));
        assertEquals(List.of("c", "a"), new ArrayList<>(RenderPolicy.retain(
                Map.of("a", 0L, "b", 0L, "c", 0L), desired, 1000, 0, 2)));
    }

    @Test
    void arrivalCannotSkipAdjacentNodeOrAcceptAnotherFloor() {
        Vec target = new Vec(0.5, 64, 0.5);
        assertTrue(RenderGeometry.arrived(new Vec(0.6, 64.1, 0.5), target));
        assertFalse(RenderGeometry.arrived(new Vec(0.5, 65, 0.5), target));
        assertFalse(RenderGeometry.arrived(new Vec(1.5, 64, 0.5), target));
        assertFalse(RenderGeometry.arrived(new Vec(Double.NaN, 64, 0.5), target));
    }

    @Test
    void nearestNodeUsesHeightAndDeterministicIdTieBreak() {
        NavGraph graph = NavGraph.builder().addNode(9, 0, 64, 0).addNode(8, 0, 74, 0)
                .addNode(2, 0, 74, 0).build();
        assertEquals(2, RenderGeometry.nearest(graph, new Vec(0.5, 74, 0.5)));
        assertEquals(9, RenderGeometry.nearest(graph, new Vec(0.5, 64, 0.5)));
        assertEquals(-1, RenderGeometry.nearest(NavGraph.builder().build(), new Vec(0, 0, 0)));
    }

    @Test
    void corridorIsFiniteAndThreeDimensional() {
        Vec from = new Vec(0.5, 64, 0.5);
        Vec to = new Vec(1.5, 64, 0.5);
        assertTrue(RenderGeometry.confined(new Vec(1, 64, 0.6), from, to));
        assertFalse(RenderGeometry.confined(new Vec(1, 65, 0.5), from, to));
        assertFalse(RenderGeometry.confined(new Vec(1, 64, 1.1), from, to));
        assertFalse(RenderGeometry.confined(new Vec(2.5, 64, 0.5), from, to));
        assertFalse(RenderGeometry.confined(new Vec(-0.5, 64, 0.5), from, to));
    }

    @Test
    void onlyDirectedAdjacentPublishedEdgesAreAccepted() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 65, 0)
                .addNode(3, 10, 64, 0).addNode(4, 0, 74, 0)
                .addEdge(1, 2, 10).addEdge(1, 3, 10).addEdge(1, 4, 10).build();
        assertTrue(RenderGeometry.edge(graph, 1, 2));
        assertFalse(RenderGeometry.edge(graph, 2, 1));
        assertFalse(RenderGeometry.edge(graph, 1, 3));
        assertFalse(RenderGeometry.edge(graph, 1, 4));
        assertFalse(RenderGeometry.edge(graph, 1, 99));
    }
}
