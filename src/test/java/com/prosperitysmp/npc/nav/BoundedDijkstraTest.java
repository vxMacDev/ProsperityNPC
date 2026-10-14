package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.prosperitysmp.npc.nav.Dijkstra.SearchStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedDijkstraTest {
    @Test
    void findsCheapestReachableTargetNotFirstDiscovered() {
        NavGraph graph = NavGraph.builder()
                .addNode(1, 0, 0, 0).addNode(2, 0, 0, 0).addNode(3, 0, 0, 0)
                .addNode(4, 0, 0, 0).addNode(7, 0, 0, 0).addNode(8, 0, 0, 0)
                .addNode(9, 0, 0, 0).addNode(10, 0, 0, 0)
                .addEdge(1, 8, 20).addEdge(1, 9, 4).addEdge(1, 2, 1)
                .addEdge(2, 3, 1).addEdge(3, 4, 1).addEdge(4, 7, 1).build();
        var result = Dijkstra.findNearest(graph, 1, Set.of(7L, 8L, 9L, 10L)::contains, edge -> true);
        assertEquals(FOUND, result.status());
        assertEquals(List.of(1L, 2L, 3L, 4L, 7L), result.nodes());
        assertEquals(4L, result.cost());
    }

    @Test
    void targetAndPredecessorTiesIgnoreNodeAndEdgeInsertionOrder() {
        List<Long> ids = new ArrayList<>(List.of(1L, 2L, 3L, 8L, 9L));
        List<NavGraph.Edge> edges = new ArrayList<>(List.of(new NavGraph.Edge(1, 3, 1),
                new NavGraph.Edge(1, 2, 2), new NavGraph.Edge(3, 9, 3),
                new NavGraph.Edge(3, 8, 3), new NavGraph.Edge(2, 8, 2), new NavGraph.Edge(2, 9, 2)));
        Dijkstra.BoundedPath previous = null;
        for (int order = 0; order < 2; order++) {
            NavGraph.Builder builder = NavGraph.builder();
            ids.forEach(id -> builder.addNode(id, 0, 0, 0));
            edges.forEach(edge -> builder.addEdge(edge.from(), edge.to(), edge.cost()));
            var result = Dijkstra.findNearest(builder.build(), 1, id -> id == 8 || id == 9, edge -> true);
            assertEquals(FOUND, result.status());
            assertEquals(List.of(1L, 2L, 8L), result.nodes());
            assertEquals(4L, result.cost());
            if (previous != null) assertEquals(previous, result);
            previous = result;
            Collections.reverse(ids);
            Collections.reverse(edges);
        }
    }

    @Test
    void countsRejectedEdgesAndOnlyPassesValidUnsettledEdgesToFilter() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 0, 0).addNode(2, 0, 0, 0)
                .addNode(3, 0, 0, 0).addEdge(1, 1, 1).addEdge(1, 99, 1)
                .addEdge(1, 2, 0).addEdge(1, 2, -1).addEdge(1, 3, 1).addEdge(1, 2, 7).build();
        AtomicInteger filtered = new AtomicInteger();
        var result = Dijkstra.findNearest(graph, 1, id -> id == 2, edge -> {
            filtered.incrementAndGet();
            return edge.to() != 3;
        });
        assertEquals(FOUND, result.status());
        assertEquals(List.of(1L, 2L), result.nodes());
        assertEquals(7L, result.cost());
        assertEquals(2, result.visited());
        assertEquals(6, result.edgesExamined());
        assertEquals(2, filtered.get());
    }

    @Test
    void missingStartDoesNotEvaluatePredicates() {
        var result = Dijkstra.findNearest(NavGraph.builder().addNode(1, 0, 0, 0).build(), 99,
                id -> fail("missing start must not be settled"), edge -> fail("no edge can be inspected"));
        assertUnfinished(result, NO_ROUTE, 0, 0);
    }

    @Test
    void currentTargetSettlesWithoutInspectingItsOutgoingEdges() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 0, 0).addNode(2, 0, 0, 0)
                .addEdge(1, 2, 1).build();
        var result = Dijkstra.findNearest(graph, 1, id -> id == 1,
                edge -> fail("current target must not move"));
        assertEquals(FOUND, result.status());
        assertEquals(List.of(1L), result.nodes());
        assertEquals(0L, result.cost());
        assertEquals(1, result.visited());
        assertEquals(0, result.edgesExamined());
    }

    @Test
    void costsAccumulateAsLong() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 0, 0).addNode(2, 0, 0, 0)
                .addNode(3, 0, 0, 0).addEdge(1, 2, Integer.MAX_VALUE)
                .addEdge(2, 3, Integer.MAX_VALUE).build();
        var result = Dijkstra.findNearest(graph, 1, id -> id == 3, edge -> true);
        assertEquals(FOUND, result.status());
        assertEquals(2L * Integer.MAX_VALUE, result.cost());
    }

    @Test
    void targetAtInclusiveSettledNodeLimitIsFound() {
        var result = Dijkstra.findNearest(chain(1024).build(), 1, id -> id == 1024, edge -> true);
        assertEquals(FOUND, result.status());
        assertEquals(1024, result.visited());
        assertEquals(1023, result.edgesExamined());
        assertEquals(1024, result.nodes().size());
        assertEquals(1023L, result.cost());
    }

    @Test
    void nextUnsettledNodeExhaustsBudgetWithoutReturningDiscoveredTarget() {
        var result = Dijkstra.findNearest(chain(1025).build(), 1, id -> id == 1025, edge -> true);
        assertUnfinished(result, BUDGET_EXHAUSTED, 1024, 1024);
    }

    @Test
    void exhaustiveSearchAtNodeLimitIsNoRouteAndStaleEntriesDoNotSpendBudget() {
        NavGraph graph = chain(1024).addEdge(1, 3, 20).build();
        var result = Dijkstra.findNearest(graph, 1, id -> false, edge -> true);
        assertUnfinished(result, NO_ROUTE, 1024, 1024);
    }

    @Test
    void highDegreeStopsBeforeExaminingOrFilteringEdge8193() {
        NavGraph.Builder builder = NavGraph.builder().addNode(1, 0, 0, 0).addNode(2, 0, 0, 0);
        for (int i = 0; i < 8193; i++) builder.addEdge(1, 2, 1);
        AtomicInteger filtered = new AtomicInteger();
        var result = Dijkstra.findNearest(builder.build(), 1, id -> id == 2, edge -> {
            filtered.incrementAndGet();
            return true;
        });
        assertUnfinished(result, BUDGET_EXHAUSTED, 1, 8192);
        assertEquals(8192, filtered.get());
    }

    @Test
    void rejectedEdgesAlsoExhaustBudget() {
        NavGraph.Builder builder = NavGraph.builder().addNode(1, 0, 0, 0);
        for (int i = 0; i < 8193; i++) builder.addEdge(1, 99, -1);
        var result = Dijkstra.findNearest(builder.build(), 1, id -> false,
                edge -> fail("invalid edges must not reach the filter"));
        assertUnfinished(result, BUDGET_EXHAUSTED, 1, 8192);
    }

    @Test
    void exactEdgeLimitCanStillProveDestinationOrNoRoute() {
        NavGraph.Builder builder = NavGraph.builder().addNode(1, 0, 0, 0).addNode(2, 0, 0, 0);
        for (int i = 0; i < 8192; i++) builder.addEdge(1, 2, 1);
        NavGraph graph = builder.build();
        var found = Dijkstra.findNearest(graph, 1, id -> id == 2, edge -> true);
        assertEquals(FOUND, found.status());
        assertEquals(List.of(1L, 2L), found.nodes());
        assertEquals(2, found.visited());
        assertEquals(8192, found.edgesExamined());
        assertUnfinished(Dijkstra.findNearest(graph, 1, id -> false, edge -> true), NO_ROUTE, 2, 8192);
    }

    @Test
    void graphInputCapsFailBeforePredicatesEvenAtCurrentDestination() {
        NavGraph.Builder tooManyNodes = NavGraph.builder();
        for (int i = 0; i < 20_001; i++) tooManyNodes.addNode(i, i, 0, 0);
        NavGraph.Builder tooManyEdges = NavGraph.builder().addNode(0, 0, 0, 0).addNode(1, 1, 0, 0);
        for (int i = 0; i < 160_001; i++) tooManyEdges.addEdge(0, 1, 1);
        for (NavGraph graph : List.of(tooManyNodes.build(), tooManyEdges.build())) {
            var result = Dijkstra.findNearest(graph, 0,
                    id -> fail("oversized graph must not evaluate even the current goal"),
                    edge -> fail("oversized graph must not inspect edges"));
            assertUnfinished(result, BUDGET_EXHAUSTED, 0, 0);
        }
    }

    @Test
    void exactGraphInputCapsAreAllowed() {
        NavGraph.Builder builder = NavGraph.builder();
        for (int i = 0; i < 20_000; i++) builder.addNode(i, i, 0, 0);
        for (int i = 0; i < 160_000; i++) builder.addEdge(0, 1, 1);
        var result = Dijkstra.findNearest(builder.build(), 0, id -> id == 0, edge -> true);
        assertEquals(FOUND, result.status());
        assertEquals(List.of(0L), result.nodes());
        assertEquals(1, result.visited());
        assertEquals(0, result.edgesExamined());
    }

    @Test
    void pathsAreImmutableDefensiveCopiesIncludingLegacyFind() {
        List<Long> nodes = new ArrayList<>(List.of(1L, 2L));
        var path = new Dijkstra.Path(nodes, 1);
        var bounded = new Dijkstra.BoundedPath(FOUND, nodes, 1, 2, 1);
        nodes.clear();
        assertEquals(List.of(1L, 2L), path.nodes());
        assertEquals(List.of(1L, 2L), bounded.nodes());
        assertThrows(UnsupportedOperationException.class, () -> path.nodes().add(3L));
        assertThrows(UnsupportedOperationException.class, () -> bounded.nodes().clear());
        NavGraph graph = chain(2).build();
        assertThrows(UnsupportedOperationException.class, () -> Dijkstra.find(graph, 1, 2).nodes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> Dijkstra.findNearest(graph, 1, id -> id == 2, edge -> true).nodes().clear());
    }

    private static NavGraph.Builder chain(int nodes) {
        NavGraph.Builder builder = NavGraph.builder();
        for (int id = 1; id <= nodes; id++) {
            builder.addNode(id, id, 64, 0);
            if (id > 1) builder.addEdge(id - 1, id, 1);
        }
        return builder;
    }

    private static void assertUnfinished(Dijkstra.BoundedPath result, Dijkstra.SearchStatus status,
                                         int visited, int edges) {
        assertEquals(status, result.status());
        assertTrue(result.nodes().isEmpty());
        assertEquals(-1L, result.cost());
        assertEquals(visited, result.visited());
        assertEquals(edges, result.edgesExamined());
    }
}
