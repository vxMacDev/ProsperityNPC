package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chain is one straight crossing: the road cells joined to one another and the curbs they step onto. */
class StreetChainsTest {

    private static List<StreetChains.Chain> find(NavGraph graph) {
        return StreetChains.find(graph, id -> graph.node(id).surface());
    }

    private static List<Long> ids(StreetChains.Chain chain) {
        return chain.cells().stream().map(NavGraph.Node::id).toList();
    }

    /** Road cells 1..n along x at z 0 starting at x 1, with a sidewalk curb at x 0 (id 100) and one past the end (id 101). */
    private static NavGraph.Builder lineAlongX(int n) {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(100, 0, 64, 0, NavSurface.SIDEWALK);
        for (int i = 1; i <= n; i++) {
            builder.addNode(i, i, 64, 0, NavSurface.ROAD);
        }
        builder.addNode(101, n + 1, 64, 0, NavSurface.SIDEWALK);
        builder.addBiEdge(100, 1, 10);
        for (int i = 1; i < n; i++) {
            builder.addBiEdge(i, i + 1, 10);
        }
        builder.addBiEdge(n, 101, 10);
        return builder;
    }

    @Test
    void aStraightLineOfFiveRoadCellsIsOneChainRunningAlongXWithTheThirdCellInTheMiddle() {
        List<StreetChains.Chain> chains = find(lineAlongX(5).build());
        assertEquals(1, chains.size());
        StreetChains.Chain chain = chains.get(0);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(chain));
        assertEquals(5, chain.length());
        assertEquals(3L, chain.mid());
        assertEquals(List.of(100L, 101L), chain.curbs());
        assertTrue(chain.alongX());
    }

    @Test
    void anEvenNumberOfCellsPutsTheMiddleOnTheLaterOfTheTwoCentralCells() {
        StreetChains.Chain chain = find(lineAlongX(4).build()).get(0);
        assertEquals(4, chain.length());
        assertEquals(3L, chain.mid());
    }

    @Test
    void twoCellsHaveTheSecondAsTheirMiddle() {
        StreetChains.Chain chain = find(lineAlongX(2).build()).get(0);
        assertEquals(2L, chain.mid());
    }

    @Test
    void aLineAlongZIsNotAlongX() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(100, 7, 64, 0, NavSurface.SIDEWALK);
        builder.addNode(1, 7, 64, 1, NavSurface.ROAD);
        builder.addNode(2, 7, 64, 2, NavSurface.CROSSING);
        builder.addNode(3, 7, 64, 3, NavSurface.ROAD);
        builder.addNode(101, 7, 64, 4, NavSurface.SIDEWALK);
        builder.addBiEdge(100, 1, 10).addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).addBiEdge(3, 101, 10);
        StreetChains.Chain chain = find(builder.build()).get(0);
        assertEquals(List.of(1L, 2L, 3L), ids(chain));
        assertFalse(chain.alongX());
        assertEquals(2L, chain.mid());
    }

    @Test
    void crossingPaintCellsAreStreetCellsToo() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 64, 0, NavSurface.CROSSING);
        builder.addNode(2, 1, 64, 0, NavSurface.CROSSING);
        builder.addBiEdge(1, 2, 10);
        List<StreetChains.Chain> chains = find(builder.build());
        assertEquals(1, chains.size());
        assertEquals(List.of(1L, 2L), ids(chains.get(0)));
    }

    @Test
    void twoSeparateRoadsGiveTwoChainsOrderedByTheirLowestNodeId() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(7, 50, 64, 50, NavSurface.ROAD).addNode(8, 51, 64, 50, NavSurface.ROAD);
        builder.addNode(1, 0, 64, 0, NavSurface.ROAD).addNode(2, 1, 64, 0, NavSurface.ROAD);
        builder.addBiEdge(7, 8, 10).addBiEdge(1, 2, 10);
        List<StreetChains.Chain> chains = find(builder.build());
        assertEquals(2, chains.size());
        assertEquals(List.of(1L, 2L), ids(chains.get(0)));
        assertEquals(List.of(7L, 8L), ids(chains.get(1)));
    }

    @Test
    void roadCellsWithNoStepBetweenThemAreSeparateChains() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 64, 0, NavSurface.ROAD).addNode(2, 1, 64, 0, NavSurface.ROAD);
        List<StreetChains.Chain> chains = find(builder.build());
        assertEquals(2, chains.size());
        assertEquals(1, chains.get(0).length());
        assertEquals(1, chains.get(1).length());
    }

    @Test
    void aStepInOneDirectionOnlyStillJoinsTheTwoCells() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 64, 0, NavSurface.ROAD).addNode(2, 1, 64, 0, NavSurface.ROAD);
        builder.addEdge(2, 1, 10);
        List<StreetChains.Chain> chains = find(builder.build());
        assertEquals(1, chains.size());
        assertEquals(List.of(1L, 2L), ids(chains.get(0)));
    }

    @Test
    void aSingleCellWithCurbsAlongXRunsAlongX() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 5, 64, 5, NavSurface.ROAD);
        builder.addNode(2, 4, 64, 5, NavSurface.SIDEWALK).addNode(3, 6, 64, 5, NavSurface.SIDEWALK);
        builder.addBiEdge(2, 1, 10).addBiEdge(1, 3, 10);
        StreetChains.Chain chain = find(builder.build()).get(0);
        assertEquals(1, chain.length());
        assertEquals(1L, chain.mid());
        assertEquals(List.of(2L, 3L), chain.curbs());
        assertTrue(chain.alongX());
    }

    @Test
    void aSingleCellWithCurbsOnTheSameXDoesNotRunAlongX() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 5, 64, 5, NavSurface.ROAD);
        builder.addNode(2, 5, 64, 4, NavSurface.SIDEWALK).addNode(3, 5, 64, 6, NavSurface.SIDEWALK);
        builder.addBiEdge(2, 1, 10).addBiEdge(1, 3, 10);
        assertFalse(find(builder.build()).get(0).alongX());
    }

    @Test
    void aSingleCellWithOneCurbOnEachAxisCountsAsAlongXBecauseAnyCurbOffItsXDecides() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 5, 64, 5, NavSurface.ROAD);
        builder.addNode(2, 5, 64, 4, NavSurface.SIDEWALK).addNode(3, 6, 64, 5, NavSurface.SIDEWALK);
        builder.addBiEdge(2, 1, 10).addBiEdge(1, 3, 10);
        assertTrue(find(builder.build()).get(0).alongX());
    }

    @Test
    void aSingleCellWithNoCurbAtAllIsNotAlongX() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 5, 64, 5, NavSurface.ROAD);
        StreetChains.Chain chain = find(builder.build()).get(0);
        assertEquals(List.of(), chain.curbs());
        assertFalse(chain.alongX());
        assertEquals(1L, chain.mid());
    }

    @Test
    void anEdgeToAMissingNodeIsSkippedAndNeverBecomesACurb() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 5, 64, 5, NavSurface.ROAD);
        builder.addNode(2, 6, 64, 5, NavSurface.SIDEWALK);
        builder.addEdge(1, 99, 10).addEdge(98, 1, 10).addBiEdge(1, 2, 10);
        StreetChains.Chain chain = find(builder.build()).get(0);
        assertEquals(List.of(2L), chain.curbs());
    }

    @Test
    void anEdgeBetweenTwoNonStreetNodesAddsNothing() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addNode(2, 1, 64, 0, NavSurface.ACCESS);
        builder.addNode(3, 2, 64, 0, NavSurface.UNKNOWN);
        builder.addBiEdge(1, 2, 10).addBiEdge(2, 3, 10);
        assertEquals(List.of(), find(builder.build()));
    }

    @Test
    void aCurbReachedByAnEdgeFromTheCurbSideIsStillACurb() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 5, 64, 5, NavSurface.ROAD);
        builder.addNode(2, 4, 64, 5, NavSurface.SIDEWALK);
        builder.addEdge(2, 1, 10);
        assertEquals(List.of(2L), find(builder.build()).get(0).curbs());
    }

    @Test
    void aCurbListedByBothDirectionsAppearsOnce() {
        StreetChains.Chain chain = find(lineAlongX(3).build()).get(0);
        assertEquals(List.of(100L, 101L), chain.curbs());
    }

    @Test
    void curbsAreSortedByIdWhateverOrderTheEdgesCameIn() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 5, 64, 5, NavSurface.ROAD);
        builder.addNode(30, 6, 64, 5, NavSurface.SIDEWALK).addNode(20, 4, 64, 5, NavSurface.SIDEWALK);
        builder.addNode(10, 5, 64, 6, NavSurface.SIDEWALK);
        builder.addBiEdge(1, 30, 10).addBiEdge(1, 10, 10).addBiEdge(1, 20, 10);
        assertEquals(List.of(10L, 20L, 30L), find(builder.build()).get(0).curbs());
    }

    @Test
    void aCurbSharedByTwoChainsIsListedOnBoth() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(50, 5, 64, 0, NavSurface.SIDEWALK);
        builder.addNode(1, 4, 64, 0, NavSurface.ROAD).addNode(2, 6, 64, 0, NavSurface.ROAD);
        builder.addBiEdge(50, 1, 10).addBiEdge(50, 2, 10);
        List<StreetChains.Chain> chains = find(builder.build());
        assertEquals(2, chains.size());
        assertEquals(List.of(50L), chains.get(0).curbs());
        assertEquals(List.of(50L), chains.get(1).curbs());
    }

    @Test
    void aTJunctionIsOneChainOfSevenCellsRunningAlongXFromItsFirstTwoCells() {
        NavGraph.Builder builder = NavGraph.builder();
        // The bar along x at z 0, ids in an order that is not the position order.
        builder.addNode(5, 0, 64, 0, NavSurface.ROAD).addNode(4, 1, 64, 0, NavSurface.ROAD);
        builder.addNode(3, 2, 64, 0, NavSurface.ROAD).addNode(2, 3, 64, 0, NavSurface.ROAD);
        builder.addNode(1, 4, 64, 0, NavSurface.ROAD);
        // The stem down z at x 2.
        builder.addNode(6, 2, 64, 1, NavSurface.ROAD).addNode(7, 2, 64, 2, NavSurface.ROAD);
        builder.addBiEdge(5, 4, 10).addBiEdge(4, 3, 10).addBiEdge(3, 2, 10).addBiEdge(2, 1, 10);
        builder.addBiEdge(3, 6, 10).addBiEdge(6, 7, 10);
        List<StreetChains.Chain> chains = find(builder.build());
        assertEquals(1, chains.size());
        StreetChains.Chain chain = chains.get(0);
        assertEquals(List.of(5L, 4L, 3L, 6L, 7L, 2L, 1L), ids(chain));
        assertEquals(6L, chain.mid(), "the fourth of seven cells by position");
        assertTrue(chain.alongX());
    }

    @Test
    void cellsAreOrderedByXThenZThenIdAndStackedCellsBreakTheTieById() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(9, 0, 70, 0, NavSurface.ROAD).addNode(4, 0, 64, 0, NavSurface.ROAD);
        builder.addBiEdge(9, 4, 10);
        StreetChains.Chain chain = find(builder.build()).get(0);
        assertEquals(List.of(4L, 9L), ids(chain));
        assertTrue(chain.alongX(), "two cells at one x and z share a z, which reads as along x");
    }

    @Test
    void theSurfaceFunctionDecidesWhatIsStreetNotTheStoredSurface() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addNode(2, 1, 64, 0, NavSurface.SIDEWALK);
        builder.addBiEdge(1, 2, 10);
        NavGraph graph = builder.build();
        List<StreetChains.Chain> chains = StreetChains.find(graph, id -> NavSurface.ROAD);
        assertEquals(1, chains.size());
        assertEquals(List.of(1L, 2L), ids(chains.get(0)));
        assertEquals(List.of(), StreetChains.find(graph, id -> NavSurface.SIDEWALK));
    }

    @Test
    void theCellListOfAChainCannotBeChanged() {
        StreetChains.Chain chain = find(lineAlongX(3).build()).get(0);
        assertThrows(UnsupportedOperationException.class, () -> chain.cells().add(chain.cells().get(0)));
    }

    @Test
    void anEmptyGraphHasNoChains() {
        assertEquals(List.of(), find(NavGraph.builder().build()));
    }

    @Test
    void aLoopOfRoadCellsIsOneChainWithEachCellOnce() {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 64, 0, NavSurface.ROAD).addNode(2, 1, 64, 0, NavSurface.ROAD);
        builder.addNode(3, 1, 64, 1, NavSurface.ROAD).addNode(4, 0, 64, 1, NavSurface.ROAD);
        builder.addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).addBiEdge(3, 4, 10).addBiEdge(4, 1, 10);
        List<StreetChains.Chain> chains = find(builder.build());
        assertEquals(1, chains.size());
        assertEquals(List.of(1L, 4L, 2L, 3L), ids(chains.get(0)));
    }
}
