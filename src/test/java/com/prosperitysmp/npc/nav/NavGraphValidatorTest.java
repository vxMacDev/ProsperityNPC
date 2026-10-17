package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.NavGraphValidator.Report;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavGraphValidatorTest {

    private static NavGraph validGraph() {
        return NavGraph.builder()
                .addNode(1, 0, 64, 0, "spawn")
                .addNode(2, 1, 64, 0)
                .addNode(3, 2, 64, 0, "shop_entrance")
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10)
                .build();
    }

    private static boolean hasCode(Report r, String code) {
        return r.issues().stream().anyMatch(i -> i.code().equals(code));
    }

    @Test
    void validGraphPasses() {
        Report r = NavGraphValidator.validate(validGraph());
        assertTrue(r.valid());
        assertEquals(0, r.errors());
    }

    @Test
    void emptyGraphIsError() {
        Report r = NavGraphValidator.validate(NavGraph.builder().build());
        assertFalse(r.valid());
        assertTrue(hasCode(r, "EMPTY"));
    }

    @Test
    void danglingEdgeIsError() {
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 0, 0, "spawn")
                .addEdge(1, 99, 10) // 99 does not exist
                .build();
        Report r = NavGraphValidator.validate(g);
        assertFalse(r.valid());
        assertTrue(hasCode(r, "DANGLING_TO"));
    }

    @Test
    void selfLoopIsError() {
        NavGraph g = NavGraph.builder().addNode(1, 0, 0, 0, "spawn").addEdge(1, 1, 10).build();
        Report r = NavGraphValidator.validate(g);
        assertFalse(r.valid());
        assertTrue(hasCode(r, "SELF_LOOP"));
    }

    @Test
    void nonPositiveCostIsError() {
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 0, 0, "spawn").addNode(2, 0, 0, 0)
                .addEdge(1, 2, 0)
                .build();
        Report r = NavGraphValidator.validate(g);
        assertTrue(hasCode(r, "BAD_COST"));
        assertFalse(r.valid());
    }

    @Test
    void orphanNodeIsWarnNotError() {
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 0, 0, "spawn").addNode(2, 0, 0, 0).addNode(3, 9, 9, 9)
                .addBiEdge(1, 2, 10) // node 3 has no edges
                .build();
        Report r = NavGraphValidator.validate(g);
        assertTrue(hasCode(r, "ORPHAN"));
        assertTrue(r.valid()); // warnings do not block publish
    }

    @Test
    void disconnectedComponentsWarn() {
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 0, 0, "spawn").addNode(2, 0, 0, 0).addNode(3, 0, 0, 0).addNode(4, 0, 0, 0)
                .addBiEdge(1, 2, 10).addBiEdge(3, 4, 10) // two separate components
                .build();
        Report r = NavGraphValidator.validate(g);
        assertTrue(hasCode(r, "DISCONNECTED"));
        assertTrue(r.valid());
    }

    @Test
    void missingSpawnTagWarns() {
        NavGraph g = NavGraph.builder()
                .addNode(1, 0, 0, 0).addNode(2, 0, 0, 0).addBiEdge(1, 2, 10)
                .build();
        Report r = NavGraphValidator.validate(g);
        assertTrue(hasCode(r, "NO_SPAWN"));
        assertTrue(r.valid());
    }

    private static NavGraph crossing() {
        return NavGraph.builder()
                .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 0, 64, 1, NavSurface.ROAD)
                .addNode(3, 0, 64, 2, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10)
                .build();
    }

    @Test
    void aStraightCrossingValidatesWithNoStreetIssues() {
        Report r = NavGraphValidator.validate(crossing());
        assertTrue(r.valid());
        assertFalse(hasCode(r, "STREET_TAG") || hasCode(r, "STREET_SHAPE") || hasCode(r, "UNKNOWN_SURFACE"));
    }

    @Test
    void aTagOnARoadOrCrosswalkIsAnError() {
        for (NavSurface street : new NavSurface[]{NavSurface.ROAD, NavSurface.CROSSING}) {
            NavGraph g = NavGraph.builder()
                    .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 0, 64, 1, street, "home")
                    .addNode(3, 0, 64, 2, NavSurface.SIDEWALK).addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).build();
            Report r = NavGraphValidator.validate(g);
            assertFalse(r.valid());
            assertTrue(hasCode(r, "STREET_TAG"), street.name());
        }
    }

    @Test
    void aTurnOnTheRoadIsAnError() {
        // An L over two road cells: each step is one block, but both road nodes join neighbours on both axes.
        NavGraph turn = NavGraph.builder()
                .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 1, 64, 0, NavSurface.ROAD)
                .addNode(3, 1, 64, 1, NavSurface.ROAD).addNode(4, 2, 64, 1, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).addBiEdge(3, 4, 10).build();
        Report r = NavGraphValidator.validate(turn);
        assertFalse(r.valid());
        assertTrue(hasCode(r, "STREET_TURN"));
        assertFalse(hasCode(r, "STREET_SHAPE"), "every single step is straight; only the joint is wrong");
        // The straight crossing and a long straight lane are fine.
        assertFalse(hasCode(NavGraphValidator.validate(crossing()), "STREET_TURN"));
        NavGraph lane = NavGraph.builder()
                .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 1, 64, 0, NavSurface.ROAD)
                .addNode(3, 2, 64, 0, NavSurface.CROSSING).addNode(4, 3, 64, 0, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).addBiEdge(3, 4, 10).build();
        assertFalse(hasCode(NavGraphValidator.validate(lane), "STREET_TURN"));
    }

    @Test
    void anEdgeThatTouchesARoadMustBeAStraightStep() {
        NavGraph diagonal = NavGraph.builder()
                .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 1, 64, 1, NavSurface.ROAD)
                .addBiEdge(1, 2, 14).build();
        Report r = NavGraphValidator.validate(diagonal);
        assertFalse(r.valid());
        assertTrue(hasCode(r, "STREET_SHAPE"));
        NavGraph jump = NavGraph.builder()
                .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 0, 64, 2, NavSurface.CROSSING)
                .addEdge(1, 2, 20).build();
        assertTrue(hasCode(NavGraphValidator.validate(jump), "STREET_SHAPE"));
        // Diagonals between sidewalk nodes stay fine.
        NavGraph sidewalk = NavGraph.builder()
                .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 1, 64, 1, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 14).build();
        assertTrue(NavGraphValidator.validate(sidewalk).valid());
    }

    @Test
    void unclassifiedNodesWarnWithACountButDoNotBlock() {
        Report r = NavGraphValidator.validate(validGraph());
        assertTrue(r.valid());
        var warning = r.issues().stream().filter(i -> i.code().equals("UNKNOWN_SURFACE")).findFirst().orElseThrow();
        assertTrue(warning.message().startsWith("3 nodes"));
        assertEquals(NavGraphValidator.Severity.WARN, warning.severity());
    }
}
