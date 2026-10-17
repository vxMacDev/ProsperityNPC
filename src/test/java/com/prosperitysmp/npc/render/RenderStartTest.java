package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.render.RenderPolicy.Vec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Residents never spawn on, start from, or switch destination on a road. */
class RenderStartTest {
    /** Road node 1 is the closest to the points below; sidewalk 2 and 3 are farther. */
    private static NavGraph street() {
        return NavGraph.builder().addNode(1, 0, 64, 1, NavSurface.ROAD).addNode(2, 0, 64, 0, NavSurface.SIDEWALK)
                .addNode(3, 0, 64, 3, NavSurface.SIDEWALK).addNode(4, 0, 64, 2, NavSurface.CROSSING)
                .addBiEdge(2, 1, 10).addBiEdge(1, 4, 10).addBiEdge(4, 3, 10).build();
    }

    @Test
    void startSkipsRoadNodesEvenWhenTheyAreCloser() {
        NavGraph g = street();
        Vec onTheRoad = new Vec(0.5, 64, 1.5);
        assertEquals(1, RenderGeometry.nearest(g, onTheRoad), "the plain search would start on the road");
        assertEquals(2, RenderGeometry.nearestWalkStart(g, onTheRoad));
        Vec nearTheSecondCrossing = new Vec(0.5, 64, 2.4);
        assertEquals(4, RenderGeometry.nearest(g, nearTheSecondCrossing));
        assertEquals(3, RenderGeometry.nearestWalkStart(g, nearTheSecondCrossing));
    }

    @Test
    void onAnUnclassifiedGraphTheStartIsTheSameAsBefore() {
        NavGraph legacy = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 5, 64, 0).addNode(3, 9, 64, 4).build();
        for (Vec at : List.of(new Vec(0.5, 64, 0.5), new Vec(5.2, 64, 0.5), new Vec(8, 64, 3), new Vec(100, 64, 100))) {
            assertEquals(RenderGeometry.nearest(legacy, at), RenderGeometry.nearestWalkStart(legacy, at));
        }
    }

    @Test
    void aGraphOfOnlyRoadHasNoWalkStart() {
        NavGraph roads = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.ROAD).addNode(2, 1, 64, 0, NavSurface.CROSSING).build();
        assertEquals(-1, RenderGeometry.nearestWalkStart(roads, new Vec(0, 64, 0)));
    }

    @Test
    void equalDistancesBreakByLowestId() {
        NavGraph g = NavGraph.builder().addNode(9, 1, 64, 0, NavSurface.SIDEWALK).addNode(4, -1, 64, 0, NavSurface.SIDEWALK).build();
        assertEquals(4, RenderGeometry.nearestWalkStart(g, new Vec(0.5, 64, 0.5)));
    }

    @Test
    void aNewDestinationNeverReplansWhileOnARoad() {
        NavGraph g = street();
        assertFalse(RenderManager.replanAllowed(g, 1), "mid-crossing: finish the crossing first");
        assertFalse(RenderManager.replanAllowed(g, 4));
        assertTrue(RenderManager.replanAllowed(g, 2));
        assertTrue(RenderManager.replanAllowed(g, 3));
        assertTrue(RenderManager.replanAllowed(g, 999), "an unknown node is not a road");
    }

    @Test
    void spawnCandidatesExcludeStreetNodesAndStayInIdOrder() {
        List<NavGraph.Node> candidates = RenderManager.spawnCandidates(street());
        assertEquals(List.of(2L, 3L), candidates.stream().map(NavGraph.Node::id).toList());
        NavGraph legacy = NavGraph.builder().addNode(7, 0, 64, 0).addNode(3, 1, 64, 0).build();
        assertEquals(List.of(3L, 7L), RenderManager.spawnCandidates(legacy).stream().map(NavGraph.Node::id).toList());
    }

    /** Compiled placement check: the call sites use the road-aware helpers, not the plain nearest search. */
    @Test
    void renderManagerStartsAndSpawnsThroughTheRoadAwareHelpers() throws java.io.IOException {
        try (var bytes = RenderManager.class.getResourceAsStream("RenderManager.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var calls = model.methods().stream().flatMap(m -> m.code().stream()).flatMap(c -> c.elementStream())
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).toList();
            assertEquals(0, calls.stream().filter(c -> c.owner().asInternalName().endsWith("RenderGeometry")
                    && c.name().equalsString("nearest")).count(), "no route may start from the plain nearest node");
            assertEquals(3, calls.stream().filter(c -> c.owner().asInternalName().endsWith("RenderGeometry")
                    && c.name().equalsString("nearestWalkStart")).count(), "debug spawn, premium walk and route planning");
            assertEquals(1, calls.stream().filter(c -> c.name().equalsString("spawnCandidates")).count());
            assertEquals(1, calls.stream().filter(c -> c.name().equalsString("replanAllowed")).count());
        }
    }
}
