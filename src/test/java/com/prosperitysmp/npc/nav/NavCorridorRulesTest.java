package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What corridor authoring refuses and exactly what it keeps, with the message staff see. */
class NavCorridorRulesTest {

    /** A straight line of nodes 1..n along x at y 72, joined both ways; the given ids carry the given tag. */
    private static NavGraph.Builder line(int n) {
        NavGraph.Builder builder = NavGraph.builder();
        for (int i = 1; i <= n; i++) {
            builder.addNode(i, i, 72, 0, NavSurface.SIDEWALK);
            if (i > 1) builder.addBiEdge(i - 1, i, 10);
        }
        return builder;
    }

    private static NavGraph tagged(NavGraph graph, long... ids) {
        NavGraph.Builder builder = NavGraph.builder();
        Set<Long> chosen = java.util.Arrays.stream(ids).boxed().collect(Collectors.toSet());
        for (NavGraph.Node node : graph.nodes()) {
            builder.addNode(chosen.contains(node.id()) ? node.withTags(Set.of("home")) : node);
        }
        graph.edges().forEach(e -> builder.addEdge(e.from(), e.to(), e.cost()));
        return builder.build();
    }

    private static String refusal(NavGraph graph) {
        return assertThrows(IllegalArgumentException.class, () -> NavCorridor.compact(graph)).getMessage();
    }

    @Test
    void aGraphWithMoreNodesThanAnyGraphCanHoldIsRefused() {
        NavGraph.Builder builder = NavGraph.builder();
        for (int i = 0; i <= Dijkstra.MAX_GRAPH_NODES; i++) builder.addNode(i, i, 72, 0);
        assertEquals("Source exceeds authoring limits; narrow the scan.", refusal(builder.build()));
    }

    @Test
    void aGraphWithMoreEdgesThanAnyGraphCanHoldIsRefused() {
        NavGraph.Builder builder = NavGraph.builder().addNode(1, 0, 72, 0).addNode(2, 1, 72, 0);
        for (int i = 0; i <= Dijkstra.MAX_GRAPH_EDGES; i++) builder.addEdge(1, 2, 10);
        assertEquals("Source exceeds authoring limits; narrow the scan.", refusal(builder.build()));
    }

    @Test
    void anInvalidGraphIsRefusedWhateverItsTags() {
        assertEquals("Source graph is invalid.", refusal(NavGraph.builder().build()));
        NavGraph selfLoop = tagged(line(3).addEdge(2, 2, 10).build(), 1, 3);
        assertEquals("Source graph is invalid.", refusal(selfLoop));
        NavGraph badCost = tagged(line(3).addEdge(1, 3, 0).build(), 1, 3);
        assertEquals("Source graph is invalid.", refusal(badCost));
    }

    @Test
    void anEdgeBetweenNodesThatAreNotNeighboursIsRefused() {
        NavGraph jump = tagged(NavGraph.builder().addNode(1, 0, 72, 0).addNode(2, 5, 72, 0).addBiEdge(1, 2, 10).build(), 1, 2);
        assertEquals("Source contains non-adjacent edges.", refusal(jump));
    }

    @Test
    void aStepUpOfTwoBlocksIsNotAdjacentEither() {
        NavGraph tall = tagged(NavGraph.builder().addNode(1, 0, 72, 0).addNode(2, 1, 74, 0).addBiEdge(1, 2, 10).build(), 1, 2);
        assertEquals("Source contains non-adjacent edges.", refusal(tall));
    }

    @Test
    void aStepUpOfOneBlockAndADiagonalAreAdjacent() {
        NavGraph graph = tagged(NavGraph.builder().addNode(1, 0, 72, 0).addNode(2, 1, 73, 1).addBiEdge(1, 2, 14).build(), 1, 2);
        assertEquals(2, NavCorridor.compact(graph).nodeCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9, 10})
    void fewerThanTwoOrMoreThanEightTaggedNodesIsRefused(int count) {
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) ids[i] = i + 1;
        assertEquals("Tag 2..8 destination nodes before corridor authoring.", refusal(tagged(line(12).build(), ids)));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 8})
    void twoToEightTaggedNodesAreAccepted(int count) {
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) ids[i] = i + 1;
        assertEquals(count, NavCorridor.compact(tagged(line(12).build(), ids)).nodeCount());
    }

    @Test
    void onlyTheNodesBetweenTheTaggedOnesAreKept() {
        NavGraph result = NavCorridor.compact(tagged(line(10).build(), 3, 6));
        assertEquals(Set.of(3L, 4L, 5L, 6L), result.nodes().stream().map(NavGraph.Node::id).collect(Collectors.toSet()));
        assertEquals(6, result.edgeCount(), "three steps each way");
    }

    @Test
    void theTagsAndSurfacesOfKeptNodesAreUnchanged() {
        NavGraph result = NavCorridor.compact(tagged(line(10).build(), 3, 6));
        assertEquals(Set.of("home"), result.node(3).tags());
        assertEquals(Set.of(), result.node(4).tags());
        assertEquals(NavSurface.SIDEWALK, result.node(4).surface());
    }

    @Test
    void aBranchOffTheRouteIsDropped() {
        NavGraph.Builder builder = line(5);
        builder.addNode(50, 3, 72, 1, NavSurface.SIDEWALK).addBiEdge(3, 50, 10);
        NavGraph result = NavCorridor.compact(tagged(builder.build(), 1, 5));
        assertFalse(result.hasNode(50));
        assertEquals(5, result.nodeCount());
    }

    @Test
    void everyPairOfTaggedNodesKeepsItsOwnRouteSoAYStaysWhole() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 72, 0, NavSurface.SIDEWALK).addNode(2, 1, 72, 0, NavSurface.SIDEWALK)
                .addNode(3, 2, 72, 0, NavSurface.SIDEWALK).addNode(4, 3, 72, 0, NavSurface.SIDEWALK)
                .addNode(5, 2, 72, 1, NavSurface.SIDEWALK).addNode(6, 2, 72, 2, NavSurface.SIDEWALK)
                .addNode(7, 9, 72, 9, NavSurface.SIDEWALK).addNode(8, 9, 72, 8, NavSurface.SIDEWALK);
        builder.addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).addBiEdge(3, 4, 10).addBiEdge(3, 5, 10).addBiEdge(5, 6, 10).addBiEdge(7, 8, 10);
        builder.addBiEdge(4, 5, 14);
        // 7 and 8 are a separate, untagged island: never reached from any tag, never kept.
        NavGraph result = NavCorridor.compact(tagged(builder.build(), 1, 4, 6));
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L), result.nodes().stream().map(NavGraph.Node::id).collect(Collectors.toSet()));
    }

    @Test
    void aOneWayRouteIsNotEnoughBecauseTheWayBackMustExistToo() {
        NavGraph.Builder builder = NavGraph.builder().addNode(1, 0, 72, 0).addNode(2, 1, 72, 0).addEdge(1, 2, 10);
        assertEquals("Tagged destinations are disconnected.", refusal(tagged(builder.build(), 1, 2)));
    }

    @Test
    void twoTaggedNodesInSeparateGroupsAreDisconnected() {
        NavGraph.Builder builder = NavGraph.builder().addNode(1, 0, 72, 0).addNode(2, 1, 72, 0).addNode(3, 8, 72, 0).addNode(4, 9, 72, 0)
                .addBiEdge(1, 2, 10).addBiEdge(3, 4, 10);
        assertEquals("Tagged destinations are disconnected.", refusal(tagged(builder.build(), 1, 4)));
    }

    @Test
    void aCorridorLongerThanTheRuntimeSearchCanVisitIsRefused() {
        NavGraph graph = tagged(line(Dijkstra.MAX_VISITED + 1).build(), 1, Dijkstra.MAX_VISITED + 1);
        assertEquals("Corridor still exceeds runtime limits; shorten or split the district.", refusal(graph));
    }

    @Test
    void aCorridorOfExactlyTheRuntimeNodeLimitIsKept() {
        NavGraph graph = tagged(line(Dijkstra.MAX_VISITED).build(), 1, Dijkstra.MAX_VISITED);
        assertEquals(Dijkstra.MAX_VISITED, NavCorridor.compact(graph).nodeCount());
    }

    @Test
    void anInterruptedThreadStopsAuthoringWithAnIllegalState() {
        NavGraph graph = tagged(line(4).build(), 1, 4);
        Thread.currentThread().interrupt();
        try {
            IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> NavCorridor.compact(graph));
            assertEquals("Authoring interrupted.", stopped.getMessage());
        } finally {
            Thread.interrupted();
        }
        assertEquals(4, NavCorridor.compact(graph).nodeCount());
    }

    @Test
    void theCompactedGraphIsDeterministicAcrossRuns() {
        NavGraph graph = tagged(line(10).build(), 2, 8, 5);
        NavGraph first = NavCorridor.compact(graph);
        NavGraph second = NavCorridor.compact(graph);
        assertEquals(first.edges(), second.edges());
        assertEquals(first.nodes().stream().map(NavGraph.Node::id).sorted().toList(), second.nodes().stream().map(NavGraph.Node::id).sorted().toList());
        assertEquals(7, first.nodeCount());
    }

    @Test
    void theSourceGraphIsNeverChanged() {
        NavGraph graph = tagged(line(10).build(), 3, 6);
        NavCorridor.compact(graph);
        assertEquals(10, graph.nodeCount());
        assertEquals(18, graph.edgeCount());
    }

    @Test
    void anAnchorIsAnyNodeWithAnyTagNotOnlyAHome() {
        NavGraph.Builder builder = line(6);
        NavGraph graph = builder.build();
        NavGraph.Builder tagged = NavGraph.builder();
        for (NavGraph.Node node : graph.nodes()) {
            tagged.addNode(node.id() == 2 ? node.withTags(Set.of("market")) : node.id() == 5 ? node.withTags(Set.of("work:cook", "leisure")) : node);
        }
        graph.edges().forEach(e -> tagged.addEdge(e.from(), e.to(), e.cost()));
        List<Long> kept = NavCorridor.compact(tagged.build()).nodes().stream().map(NavGraph.Node::id).sorted().toList();
        assertEquals(List.of(2L, 3L, 4L, 5L), kept);
    }
}
