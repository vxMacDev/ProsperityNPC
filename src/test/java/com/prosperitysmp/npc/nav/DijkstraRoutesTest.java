package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Route choice on small hand-built graphs: costs, ties, filters, and what each result reports. */
class DijkstraRoutesTest {

    private static NavGraph.Builder nodes(long... ids) {
        NavGraph.Builder builder = NavGraph.builder();
        for (long id : ids) builder.addNode(id, (int) id, 64, 0);
        return builder;
    }

    // ---- find ----

    @Test
    void theCheaperLongerRouteBeatsTheShorterExpensiveOne() {
        NavGraph graph = nodes(1, 2, 3, 4).addEdge(1, 4, 50).addEdge(1, 2, 10).addEdge(2, 3, 10).addEdge(3, 4, 10).build();
        Dijkstra.Path path = Dijkstra.find(graph, 1, 4);
        assertEquals(List.of(1L, 2L, 3L, 4L), path.nodes());
        assertEquals(30L, path.cost());
        assertTrue(path.found());
    }

    @Test
    void anEdgeIsOneWayAndTheReturnTripNeedsItsOwnEdge() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 10).build();
        assertEquals(10L, Dijkstra.find(graph, 1, 2).cost());
        assertFalse(Dijkstra.find(graph, 2, 1).found());
    }

    @Test
    void theReturnTripCanCostDifferentlyFromTheOutwardOne() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 10).addEdge(2, 1, 14).build();
        assertEquals(10L, Dijkstra.find(graph, 1, 2).cost());
        assertEquals(14L, Dijkstra.find(graph, 2, 1).cost());
    }

    @Test
    void parallelEdgesUseTheCheaperOne() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 30).addEdge(1, 2, 10).addEdge(1, 2, 20).build();
        assertEquals(10L, Dijkstra.find(graph, 1, 2).cost());
    }

    @Test
    void anUnreachableGoalIsNoneWithACostOfMinusOne() {
        NavGraph graph = nodes(1, 2, 3).addEdge(1, 2, 10).build();
        Dijkstra.Path path = Dijkstra.find(graph, 1, 3);
        assertFalse(path.found());
        assertEquals(List.of(), path.nodes());
        assertEquals(-1L, path.cost());
        assertEquals(path, Dijkstra.Path.none());
    }

    @Test
    void aRouteToTheStartIsTheStartAloneAtCostZero() {
        NavGraph graph = nodes(1).build();
        Dijkstra.Path path = Dijkstra.find(graph, 1, 1);
        assertEquals(List.of(1L), path.nodes());
        assertEquals(0L, path.cost());
    }

    @Test
    void aMissingStartOrGoalIsNone() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 10).build();
        assertFalse(Dijkstra.find(graph, 9, 2).found());
        assertFalse(Dijkstra.find(graph, 1, 9).found());
        assertFalse(Dijkstra.find(graph, 9, 9).found(), "even a route from a missing node to itself");
    }

    @Test
    void anEdgeIntoAMissingNodeIsNeverUsed() {
        NavGraph graph = nodes(1, 3).addEdge(1, 2, 1).addEdge(2, 3, 1).build();
        assertFalse(Dijkstra.find(graph, 1, 3).found());
    }

    @Test
    void zeroAndNegativeCostEdgesAreSkippedInFavourOfAPositiveOne() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 0).addEdge(1, 2, -5).addEdge(1, 2, 40).build();
        assertEquals(40L, Dijkstra.find(graph, 1, 2).cost());
    }

    @Test
    void costsAddUpInALongWithoutOverflowingAnInt() {
        NavGraph graph = nodes(1, 2, 3).addEdge(1, 2, Integer.MAX_VALUE).addEdge(2, 3, Integer.MAX_VALUE).build();
        assertEquals(2L * Integer.MAX_VALUE, Dijkstra.find(graph, 1, 3).cost());
    }

    @Test
    void equalCostRoutesTakeTheSmallerPredecessorAtTheMeetingNode() {
        NavGraph graph = nodes(1, 2, 3, 4).addEdge(1, 3, 5).addEdge(1, 2, 5).addEdge(3, 4, 5).addEdge(2, 4, 5).build();
        assertEquals(List.of(1L, 2L, 4L), Dijkstra.find(graph, 1, 4).nodes());
    }

    @Test
    void thePathObjectsListCannotBeChanged() {
        Dijkstra.Path path = Dijkstra.find(nodes(1, 2).addEdge(1, 2, 10).build(), 1, 2);
        assertThrows(UnsupportedOperationException.class, () -> path.nodes().add(5L));
    }

    // ---- findNearest ----

    @Test
    void theStartIsTheNearestWhenItMatchesAndNothingElseIsLookedAt() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 10).build();
        Dijkstra.BoundedPath result = Dijkstra.findNearest(graph, 1, id -> true, edge -> true);
        assertEquals(Dijkstra.SearchStatus.FOUND, result.status());
        assertEquals(List.of(1L), result.nodes());
        assertEquals(0L, result.cost());
        assertEquals(1, result.visited());
        assertEquals(0, result.edgesExamined());
    }

    @Test
    void aTraversalFilterThatRefusesTheOnlyEdgeLeavesNoRoute() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 10).build();
        Dijkstra.BoundedPath result = Dijkstra.findNearest(graph, 1, id -> id == 2, edge -> false);
        assertEquals(Dijkstra.SearchStatus.NO_ROUTE, result.status());
        assertEquals(List.of(), result.nodes());
        assertEquals(-1L, result.cost());
        assertEquals(1, result.edgesExamined(), "the refused edge was still looked at");
    }

    @Test
    void aTraversalFilterSteersTheRouteAroundARefusedEdge() {
        NavGraph graph = nodes(1, 2, 3, 4).addEdge(1, 2, 1).addEdge(2, 4, 1).addEdge(1, 3, 5).addEdge(3, 4, 5).build();
        Dijkstra.BoundedPath open = Dijkstra.findNearest(graph, 1, id -> id == 4, edge -> true);
        assertEquals(List.of(1L, 2L, 4L), open.nodes());
        Dijkstra.BoundedPath detour = Dijkstra.findNearest(graph, 1, id -> id == 4, edge -> edge.to() != 2);
        assertEquals(List.of(1L, 3L, 4L), detour.nodes());
        assertEquals(10L, detour.cost());
    }

    @Test
    void equalCostTargetsResolveToTheSmallerId() {
        NavGraph graph = nodes(1, 5, 7).addEdge(1, 7, 10).addEdge(1, 5, 10).build();
        Dijkstra.BoundedPath result = Dijkstra.findNearest(graph, 1, id -> id == 5 || id == 7, edge -> true);
        assertEquals(List.of(1L, 5L), result.nodes());
    }

    @Test
    void aMissingStartIsNoRouteWithNothingVisited() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 10).build();
        Dijkstra.BoundedPath result = Dijkstra.findNearest(graph, 9, id -> true, edge -> true);
        assertEquals(Dijkstra.SearchStatus.NO_ROUTE, result.status());
        assertEquals(0, result.visited());
        assertEquals(0, result.edgesExamined());
    }

    @Test
    void theFilterIsNotAskedAboutEdgesTheSearchWouldSkipAnyway() {
        NavGraph graph = nodes(1, 2).addEdge(1, 2, 0).addEdge(1, 3, 10).addEdge(1, 2, -1).build();
        AtomicInteger asked = new AtomicInteger();
        Dijkstra.BoundedPath result = Dijkstra.findNearest(graph, 1, id -> false, edge -> {
            asked.incrementAndGet();
            return true;
        });
        assertEquals(Dijkstra.SearchStatus.NO_ROUTE, result.status());
        assertEquals(0, asked.get());
        assertEquals(3, result.edgesExamined(), "skipped edges still count against the edge budget");
    }

    @Test
    void theTargetPredicateIsAskedOncePerSettledNodeInSettlingOrder() {
        NavGraph graph = nodes(1, 2, 3).addEdge(1, 2, 1).addEdge(2, 3, 1).build();
        List<Long> asked = new ArrayList<>();
        Dijkstra.findNearest(graph, 1, id -> {
            asked.add(id);
            return id == 3;
        }, edge -> true);
        assertEquals(List.of(1L, 2L, 3L), asked);
    }

    @Test
    void aNullArgumentIsRefusedBeforeAnySearch() {
        NavGraph graph = nodes(1).build();
        assertThrows(NullPointerException.class, () -> Dijkstra.findNearest(null, 1, id -> true, edge -> true));
        assertThrows(NullPointerException.class, () -> Dijkstra.findNearest(graph, 1, null, edge -> true));
        assertThrows(NullPointerException.class, () -> Dijkstra.findNearest(graph, 1, id -> true, null));
    }

    @Test
    void aBoundedPathKeepsACopyOfItsNodeList() {
        List<Long> source = new ArrayList<>(List.of(1L, 2L));
        Dijkstra.BoundedPath path = new Dijkstra.BoundedPath(Dijkstra.SearchStatus.FOUND, source, 5L, 2, 1);
        source.clear();
        assertEquals(List.of(1L, 2L), path.nodes());
        assertThrows(UnsupportedOperationException.class, () -> path.nodes().clear());
    }

    @Test
    void theDocumentedLimitsAreUnchanged() {
        assertEquals(1024, Dijkstra.MAX_VISITED);
        assertEquals(8192, Dijkstra.MAX_EDGES_EXAMINED);
        assertEquals(20_000, Dijkstra.MAX_GRAPH_NODES);
        assertEquals(160_000, Dijkstra.MAX_GRAPH_EDGES);
        assertEquals(Set.of("FOUND", "NO_ROUTE", "BUDGET_EXHAUSTED"),
                Arrays.stream(Dijkstra.SearchStatus.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void aSearchThatCannotRunReturnsTheSameUnfinishedShapeForEveryBudgetFailure() {
        NavGraph.Builder builder = NavGraph.builder();
        for (int i = 0; i <= Dijkstra.MAX_GRAPH_NODES; i++) builder.addNode(i, i, 64, 0);
        Dijkstra.BoundedPath result = Dijkstra.findNearest(builder.build(), 0, id -> true, edge -> true);
        assertEquals(Dijkstra.SearchStatus.BUDGET_EXHAUSTED, result.status());
        assertEquals(List.of(), result.nodes());
        assertEquals(-1L, result.cost());
        assertEquals(0, result.visited());
    }
}
