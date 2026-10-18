package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory graph and the node and edge records every other nav class is built on. */
class NavGraphTest {

    // ---- Node ----

    @Test
    void aNodeWithNullTagsAndNullSurfaceHasNoTagsAndAnUnknownSurface() {
        NavGraph.Node node = new NavGraph.Node(1, 0, 64, 0, null, null, 0);
        assertEquals(Set.of(), node.tags());
        assertEquals(NavSurface.UNKNOWN, node.surface());
    }

    @Test
    void aNodeCopiesItsTagsSoLaterChangesToTheSourceDoNotReachIt() {
        Set<String> source = new HashSet<>(Set.of("home"));
        NavGraph.Node node = new NavGraph.Node(1, 0, 64, 0, source, NavSurface.SIDEWALK);
        source.add("work");
        assertEquals(Set.of("home"), node.tags());
    }

    @Test
    void theTagsOfANodeCannotBeChanged() {
        NavGraph.Node node = new NavGraph.Node(1, 0, 64, 0, Set.of("home"));
        assertThrows(UnsupportedOperationException.class, () -> node.tags().add("work"));
        assertThrows(UnsupportedOperationException.class, () -> node.tags().remove("home"));
    }

    @Test
    void aNegativeRoadWidthIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new NavGraph.Node(1, 0, 64, 0, Set.of(), NavSurface.ROAD, -1));
        assertEquals("road width cannot be negative", refused.getMessage());
    }

    @Test
    void aRoadWidthOfZeroMeansNoneWasRecordedAndIsAllowed() {
        assertEquals(0, new NavGraph.Node(1, 0, 64, 0, Set.of(), NavSurface.ROAD, 0).roadWidth());
        assertEquals(Integer.MAX_VALUE, new NavGraph.Node(1, 0, 64, 0, Set.of(), NavSurface.ROAD, Integer.MAX_VALUE).roadWidth());
    }

    @Test
    void theShorterNodeConstructorsRecordNoWidthAndTheRightSurface() {
        NavGraph.Node classified = new NavGraph.Node(2, 1, 65, 2, Set.of("spawn"), NavSurface.ACCESS);
        assertEquals(NavSurface.ACCESS, classified.surface());
        assertEquals(0, classified.roadWidth());
        NavGraph.Node legacy = new NavGraph.Node(3, 1, 65, 2, Set.of("spawn"));
        assertEquals(NavSurface.UNKNOWN, legacy.surface());
        assertEquals(0, legacy.roadWidth());
    }

    @Test
    void hasTagIsAnExactMatch() {
        NavGraph.Node node = new NavGraph.Node(1, 0, 64, 0, Set.of("home", "work:cook"));
        assertTrue(node.hasTag("home"));
        assertTrue(node.hasTag("work:cook"));
        assertFalse(node.hasTag("work"));
        assertFalse(node.hasTag("HOME"));
        assertFalse(node.hasTag(""));
    }

    @Test
    void withTagsKeepsEverythingButTheTags() {
        NavGraph.Node road = new NavGraph.Node(9, -4, 70, 12, Set.of("a"), NavSurface.CROSSING, 7);
        NavGraph.Node changed = road.withTags(Set.of("b", "c"));
        assertEquals(Set.of("b", "c"), changed.tags());
        assertEquals(9L, changed.id());
        assertEquals(-4, changed.x());
        assertEquals(70, changed.y());
        assertEquals(12, changed.z());
        assertEquals(NavSurface.CROSSING, changed.surface());
        assertEquals(7, changed.roadWidth());
        assertEquals(Set.of("a"), road.tags(), "the original is untouched");
    }

    @Test
    void twoNodesWithTheSameFieldsAreEqual() {
        assertEquals(new NavGraph.Node(1, 2, 3, 4, Set.of("home"), NavSurface.SIDEWALK, 0),
                new NavGraph.Node(1, 2, 3, 4, new HashSet<>(Set.of("home")), NavSurface.SIDEWALK));
    }

    // ---- Builder and graph ----

    @Test
    void anEmptyGraphHasNothingInIt() {
        NavGraph graph = NavGraph.builder().build();
        assertEquals(0, graph.nodeCount());
        assertEquals(0, graph.edgeCount());
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
        assertNull(graph.node(1));
        assertFalse(graph.hasNode(1));
    }

    @Test
    void addNodeWithTagsAndNoSurfaceMakesAnUnclassifiedNode() {
        NavGraph graph = NavGraph.builder().addNode(5, 1, 64, 2, "home", "work").build();
        NavGraph.Node node = graph.node(5);
        assertEquals(NavSurface.UNKNOWN, node.surface());
        assertEquals(Set.of("home", "work"), node.tags());
        assertEquals(1, node.x());
        assertEquals(64, node.y());
        assertEquals(2, node.z());
    }

    @Test
    void addNodeWithASurfaceKeepsItAndItsTags() {
        NavGraph graph = NavGraph.builder().addNode(5, 1, 64, 2, NavSurface.ACCESS, "market").build();
        assertEquals(NavSurface.ACCESS, graph.node(5).surface());
        assertEquals(Set.of("market"), graph.node(5).tags());
    }

    @Test
    void addingTheSameIdAgainReplacesTheEarlierNode() {
        NavGraph graph = NavGraph.builder().addNode(5, 1, 64, 2).addNode(5, 9, 70, 9, NavSurface.ROAD).build();
        assertEquals(1, graph.nodeCount());
        assertEquals(9, graph.node(5).x());
        assertEquals(NavSurface.ROAD, graph.node(5).surface());
    }

    @Test
    void addNodeAcceptsAReadyMadeNodeAndKeepsItsRoadWidth() {
        NavGraph graph = NavGraph.builder()
                .addNode(new NavGraph.Node(4, 0, 64, 0, Set.of(), NavSurface.ROAD, 6)).build();
        assertEquals(6, graph.node(4).roadWidth());
    }

    @Test
    void addBiEdgeAddsBothDirectionsAtTheSameCostInOrder() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0).addBiEdge(1, 2, 10).build();
        assertEquals(List.of(new NavGraph.Edge(1, 2, 10), new NavGraph.Edge(2, 1, 10)), graph.edges());
        assertEquals(2, graph.edgeCount());
    }

    @Test
    void edgesKeepTheirInsertionOrderAndDuplicatesAreKept() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0)
                .addEdge(2, 1, 14).addEdge(1, 2, 10).addEdge(1, 2, 10).build();
        assertEquals(List.of(new NavGraph.Edge(2, 1, 14), new NavGraph.Edge(1, 2, 10), new NavGraph.Edge(1, 2, 10)),
                graph.edges());
        assertEquals(2, graph.neighbors(1).size());
    }

    @Test
    void neighborsAreTheOutEdgesOfANodeInInsertionOrder() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0).addNode(3, 2, 64, 0)
                .addEdge(1, 3, 20).addEdge(1, 2, 10).addEdge(2, 1, 10).build();
        assertEquals(List.of(new NavGraph.Edge(1, 3, 20), new NavGraph.Edge(1, 2, 10)), graph.neighbors(1));
        assertEquals(List.of(new NavGraph.Edge(2, 1, 10)), graph.neighbors(2));
    }

    @Test
    void aNodeWithNoOutEdgesAndAnUnknownNodeBothHaveEmptyNeighbors() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0).addEdge(1, 2, 10).build();
        assertEquals(List.of(), graph.neighbors(2));
        assertEquals(List.of(), graph.neighbors(404));
    }

    @Test
    void theOutEdgesOfADanglingEdgeAreStillListedAsNeighbors() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addEdge(1, 77, 10).build();
        assertEquals(List.of(new NavGraph.Edge(1, 77, 10)), graph.neighbors(1));
        assertEquals(1, graph.edgeCount());
    }

    @Test
    void aPositiveEdgeNeedsPositiveCostAndBothNodesAndTheRightDirection() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0).addNode(3, 2, 64, 0)
                .addEdge(1, 2, 10)
                .addEdge(2, 3, 0)
                .addEdge(3, 1, -5)
                .addEdge(1, 99, 10)
                .addEdge(98, 1, 10)
                .build();
        assertTrue(graph.hasPositiveEdge(1, 2));
        assertFalse(graph.hasPositiveEdge(2, 1), "an edge is one way");
        assertFalse(graph.hasPositiveEdge(2, 3), "a zero cost is not walkable");
        assertFalse(graph.hasPositiveEdge(3, 1), "a negative cost is not walkable");
        assertFalse(graph.hasPositiveEdge(1, 99), "the target node is missing");
        assertFalse(graph.hasPositiveEdge(98, 1), "the source node is missing");
        assertFalse(graph.hasPositiveEdge(1, 3), "no such edge");
    }

    @Test
    void aSecondEdgeWithPositiveCostMakesAnEarlierZeroCostPairWalkable() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0)
                .addEdge(1, 2, 0).addEdge(1, 2, 10).build();
        assertTrue(graph.hasPositiveEdge(1, 2));
    }

    @Test
    void theBuiltGraphIsNotChangedByLaterBuilderCalls() {
        NavGraph.Builder builder = NavGraph.builder().addNode(1, 0, 64, 0);
        NavGraph graph = builder.build();
        builder.addNode(2, 1, 64, 0).addEdge(1, 2, 10);
        assertEquals(1, graph.nodeCount());
        assertEquals(0, graph.edgeCount());
        assertEquals(2, builder.build().nodeCount());
    }

    @Test
    void theNodeAndEdgeViewsOfAGraphCannotBeChanged() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0).addEdge(1, 2, 10).build();
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.edges().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.neighbors(1).clear());
    }

    @Test
    void nodeLookupReturnsTheSameNodeObjectTheBuilderWasGiven() {
        NavGraph.Node node = new NavGraph.Node(3, 0, 64, 0, Set.of("spawn"));
        NavGraph graph = NavGraph.builder().addNode(node).build();
        assertSame(node, graph.node(3));
        assertTrue(graph.nodes().contains(node));
    }
}
