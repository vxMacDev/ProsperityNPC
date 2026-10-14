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
}
