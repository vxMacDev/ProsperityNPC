package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.Dijkstra.Path;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DijkstraTest {

    @Test
    void linearPath() {
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 64, 0).addNode(2, 1, 64, 0).addNode(3, 2, 64, 0)
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10)
                .build();
        Path p = Dijkstra.find(g, 1, 3);
        assertEquals(List.of(1L, 2L, 3L), p.nodes());
        assertEquals(20L, p.cost());
        assertTrue(p.found());
    }

    @Test
    void choosesCheaperRoute() {
        // Direct 1->2 costs 100; detour 1->3->2 costs 2.
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 64, 0).addNode(2, 0, 64, 0).addNode(3, 0, 64, 0)
                .addEdge(1, 2, 100).addEdge(1, 3, 1).addEdge(3, 2, 1)
                .build();
        Path p = Dijkstra.find(g, 1, 2);
        assertEquals(List.of(1L, 3L, 2L), p.nodes());
        assertEquals(2L, p.cost());
    }

    @Test
    void unreachableReturnsNone() {
        NavGraph g = NavGraph.builder().addNode(1, 0, 0, 0).addNode(2, 0, 0, 0).build();
        assertFalse(Dijkstra.find(g, 1, 2).found());
        assertEquals(-1L, Dijkstra.find(g, 1, 2).cost());
    }

    @Test
    void startEqualsGoal() {
        NavGraph g = NavGraph.builder().addNode(1, 0, 0, 0).build();
        Path p = Dijkstra.find(g, 1, 1);
        assertEquals(List.of(1L), p.nodes());
        assertEquals(0L, p.cost());
    }

    @Test
    void missingEndpointsReturnNone() {
        NavGraph g = NavGraph.builder().addNode(1, 0, 0, 0).build();
        assertFalse(Dijkstra.find(g, 1, 999).found());
        assertFalse(Dijkstra.find(g, 999, 1).found());
    }

    @Test
    void equalCostPathsBreakTiesDeterministically() {
        // Two equal 20-cost routes to 4 (via 2 or via 3); the smaller predecessor (2) wins.
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 0, 0).addNode(2, 0, 0, 0).addNode(3, 0, 0, 0).addNode(4, 0, 0, 0)
                .addEdge(1, 2, 10).addEdge(1, 3, 10).addEdge(2, 4, 10).addEdge(3, 4, 10)
                .build();
        assertEquals(List.of(1L, 2L, 4L), Dijkstra.find(g, 1, 4).nodes());
    }

    @Test
    void nonPositiveCostEdgesAreIgnored() {
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 0, 0).addNode(2, 0, 0, 0)
                .addEdge(1, 2, 0) // zero cost -> skipped
                .build();
        assertFalse(Dijkstra.find(g, 1, 2).found());
    }
}
