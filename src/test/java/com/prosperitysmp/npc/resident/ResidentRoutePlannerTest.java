package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.nav.NavGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.prosperitysmp.npc.resident.ResidentRoutePlanner.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory graph fixtures only; no resident simulation, world or database is needed. */
class ResidentRoutePlannerTest {
    private final ResidentProfile person = ResidentCatalogue.betaCast().getFirst();
    private final ResidentRoutine.Intent work = ResidentRoutine.at(person, 5000);

    @Test
    void actualRouteCostWinsOverEuclideanProximity() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0, "work")
                .addNode(3, 0, 64, 1).addNode(4, 1, 64, 1).addNode(5, 2, 64, 1, "work")
                .addEdge(1, 2, 50).addEdge(1, 3, 1).addEdge(3, 4, 1).addEdge(4, 5, 1).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertEquals(WALK, decision.status());
        assertEquals(List.of(1L, 3L, 4L, 5L), decision.nodes());
        assertEquals(3L, decision.cost());
        assertEquals("work", decision.targetTag());
    }

    @Test
    void personalTierWinsForEveryActivityEvenWhenAlreadyAtGenericAnchor() {
        for (long tick : new long[]{0, 5000, 10000, 13000}) {
            ResidentRoutine.Intent intent = ResidentRoutine.at(person, tick);
            String personal = intent.destinationTag() + ':' + person.id();
            NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, intent.destinationTag())
                    .addNode(2, 1, 64, 0, "work:restorer").addNode(3, 2, 64, 0, personal)
                    .addEdge(1, 2, 1).addEdge(2, 3, 1).build();
            var decision = ResidentRoutePlanner.plan(person, intent, graph, 1);
            assertEquals(WALK, decision.status());
            assertEquals(personal, decision.targetTag());
            assertEquals(List.of(1L, 2L, 3L), decision.nodes());
        }
    }

    @Test
    void occupationTierWinsOverGenericButIsOnlyUsedForWork() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "work")
                .addNode(2, 1, 64, 0, "work:restorer").addEdge(1, 2, 7).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertEquals(WALK, decision.status());
        assertEquals("work:restorer", decision.targetTag());
        assertEquals(List.of(1L, 2L), decision.nodes());
        for (long tick : new long[]{0, 10000, 13000}) {
            var intent = ResidentRoutine.at(person, tick);
            assertFailure(ResidentRoutePlanner.plan(person, intent, graph, 1), NO_ANCHOR, intent.destinationTag());
        }
    }

    @Test
    void workFallsBackToGenericOnlyWhenPreferredTiersHaveNoNodes() {
        String otherResident = "work:" + ResidentCatalogue.betaCast().get(1).id();
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0)
                .addNode(2, 1, 64, 0, "work:shopkeeper", otherResident)
                .addNode(3, 2, 64, 0, "work").addEdge(1, 2, 1).addEdge(2, 3, 1).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertEquals(WALK, decision.status());
        assertEquals("work", decision.targetTag());
        assertEquals(List.of(1L, 2L, 3L), decision.nodes());
    }

    @Test
    void unreachablePersonalTierNeverFallsBackToReachableOccupationOrGeneric() {
        String personal = "work:" + person.id();
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "work", "work:restorer")
                .addNode(2, 1, 64, 0, personal).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertFailure(decision, NO_ROUTE, personal);
        assertEquals(1, decision.visited());
        assertEquals(0, decision.edgesExamined());
    }

    @Test
    void unreachableOccupationTierNeverFallsBackToGeneric() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "work")
                .addNode(2, 1, 64, 0, "work:restorer").build();
        assertFailure(ResidentRoutePlanner.plan(person, work, graph, 1), NO_ROUTE, "work:restorer");
    }

    @Test
    void unreachableTargetDoesNotHideReachableTargetWithinSamePersonalTier() {
        String personal = "work:" + person.id();
        NavGraph graph = NavGraph.builder().addNode(0, 5, 64, 0, personal).addNode(1, 0, 64, 0)
                .addNode(2, 1, 64, 0, personal).addEdge(1, 2, 3).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertEquals(WALK, decision.status());
        assertEquals(personal, decision.targetTag());
        assertEquals(List.of(1L, 2L), decision.nodes());
    }

    @Test
    void missingAnchorsDifferFromMissingStartAndNoSpawnFallbackExists() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "spawn").build();
        var missing = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertFailure(missing, NO_ANCHOR, "work");
        assertEquals(0, missing.visited());
        assertEquals(0, missing.edgesExamined());
        assertFailure(ResidentRoutePlanner.plan(person, work, NavGraph.builder().build(), 1), NO_ANCHOR, "work");
        NavGraph anchored = NavGraph.builder().addNode(1, 0, 64, 0, "work").build();
        var invalidStart = ResidentRoutePlanner.plan(person, work, anchored, 99);
        assertFailure(invalidStart, NO_ROUTE, "work");
        assertEquals(0, invalidStart.visited());
        assertEquals(0, invalidStart.edgesExamined());
    }

    @Test
    void oneWayEdgeCannotBeWalkedBackwards() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "work")
                .addNode(2, 1, 64, 0).addEdge(1, 2, 1).build();
        assertFailure(ResidentRoutePlanner.plan(person, work, graph, 2), NO_ROUTE, "work");
    }

    @Test
    void currentDestinationIdlesWithZeroCostAndNoExaminedEdges() {
        String personal = "work:" + person.id();
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, personal)
                .addNode(2, 1, 64, 0, personal).addEdge(1, 2, 1).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertEquals(AT_DESTINATION, decision.status());
        assertEquals(List.of(1L), decision.nodes());
        assertEquals(0L, decision.cost());
        assertEquals(personal, decision.targetTag());
        assertEquals(1, decision.visited());
        assertEquals(0, decision.edgesExamined());
        assertEquals(decision, ResidentRoutePlanner.plan(person, work, graph, 1));
    }

    @Test
    void usesExistingNegativeClockIntentWithoutReadingAClockOrChangingResident() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0)
                .addNode(2, 1, 64, 0, "home").addNode(3, 0, 64, 1, "work")
                .addNode(4, -1, 64, 0, "market").addNode(5, 0, 64, -1, "leisure")
                .addEdge(1, 2, 1).addEdge(1, 3, 1).addEdge(1, 4, 1).addEdge(1, 5, 1).build();
        for (long tick : new long[]{-1, -19000, Long.MIN_VALUE}) {
            var intent = ResidentRoutine.at(person, tick);
            var decision = ResidentRoutePlanner.plan(person, intent, graph, 1);
            assertEquals(WALK, decision.status());
            assertEquals(intent.destinationTag(), decision.targetTag());
            assertEquals(decision, ResidentRoutePlanner.plan(person,
                    ResidentRoutine.at(person, Math.floorMod(tick, 24_000L)), graph, 1));
        }
        assertEquals("home", ResidentRoutePlanner.plan(person, ResidentRoutine.at(person, -1), graph, 1).targetTag());
        assertEquals(person, ResidentCatalogue.betaCast().getFirst());
    }

    @Test
    void targetAndRouteTiesIgnoreInsertionOrder() {
        List<NavGraph.Node> nodes = new ArrayList<>(List.of(
                new NavGraph.Node(1, 0, 64, 0, Set.of()), new NavGraph.Node(2, 1, 64, 0, Set.of()),
                new NavGraph.Node(3, 0, 64, 1, Set.of()), new NavGraph.Node(8, 1, 64, 1, Set.of("work")),
                new NavGraph.Node(9, 1, 65, 1, Set.of("work"))));
        List<NavGraph.Edge> edges = new ArrayList<>(List.of(new NavGraph.Edge(1, 3, 1),
                new NavGraph.Edge(1, 2, 2), new NavGraph.Edge(3, 9, 3), new NavGraph.Edge(3, 8, 3),
                new NavGraph.Edge(2, 8, 2), new NavGraph.Edge(2, 9, 2)));
        ResidentRoutePlanner.Decision previous = null;
        for (int order = 0; order < 2; order++) {
            NavGraph.Builder builder = NavGraph.builder();
            nodes.forEach(builder::addNode);
            edges.forEach(edge -> builder.addEdge(edge.from(), edge.to(), edge.cost()));
            var decision = ResidentRoutePlanner.plan(person, work, builder.build(), 1);
            assertEquals(WALK, decision.status());
            assertEquals(List.of(1L, 2L, 8L), decision.nodes());
            assertEquals(4L, decision.cost());
            if (previous != null) assertEquals(previous, decision);
            previous = decision;
            Collections.reverse(nodes);
            Collections.reverse(edges);
        }
    }

    @Test
    void rejectsNonAdjacentColocatedAndVerticalOnlyGeometry() {
        for (int[] xyz : new int[][]{{0, 64, 0}, {0, 65, 0}, {2, 64, 0}, {1, 66, 0}, {0, 64, 2}}) {
            NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0)
                    .addNode(2, xyz[0], xyz[1], xyz[2], "work").addEdge(1, 2, 1).build();
            var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
            assertFailure(decision, NO_ROUTE, "work");
            assertEquals(1, decision.edgesExamined());
        }
        NavGraph extreme = NavGraph.builder().addNode(1, Integer.MAX_VALUE, 64, 0)
                .addNode(2, Integer.MIN_VALUE, 64, 0, "work").addEdge(1, 2, 1).build();
        assertFailure(ResidentRoutePlanner.plan(person, work, extreme, 1), NO_ROUTE, "work");
    }

    @Test
    void rejectsDanglingNonPositiveAndUnpublishedForwardLinks() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0, "work")
                .addEdge(1, 2, 0).addEdge(1, 2, -4).addEdge(1, 99, 1).addEdge(2, 1, 1).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertFailure(decision, NO_ROUTE, "work");
        assertEquals(1, decision.visited());
        assertEquals(3, decision.edgesExamined());
    }

    @Test
    void acceptsDirectedDiagonalStepsWithAtMostOneBlockHeightChange() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 65, 1)
                .addNode(3, 2, 64, 2, "work").addEdge(1, 2, 4).addEdge(2, 3, 5).build();
        var decision = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertEquals(WALK, decision.status());
        assertEquals(List.of(1L, 2L, 3L), decision.nodes());
        assertEquals(9L, decision.cost());
    }

    @Test
    void settledNodeBudgetIsInclusiveAndExhaustionIsNotNoRoute() {
        for (int length : new int[]{1024, 1025}) {
            NavGraph.Builder builder = NavGraph.builder();
            for (int id = 0; id < length; id++) {
                if (id == length - 1) builder.addNode(id, id, 64, 0, "work");
                else builder.addNode(id, id, 64, 0);
                if (id > 0) builder.addEdge(id - 1, id, 1);
            }
            var decision = ResidentRoutePlanner.plan(person, work, builder.build(), 0);
            assertEquals(1024, decision.visited());
            if (length == 1024) {
                assertEquals(WALK, decision.status());
                assertEquals(1024, decision.nodes().size());
                assertEquals(1023L, decision.cost());
                assertEquals(1023, decision.edgesExamined());
            } else {
                assertFailure(decision, BUDGET_EXHAUSTED, "work");
                assertEquals(1024, decision.edgesExamined());
            }
        }
    }

    @Test
    void highDegreeExhaustionDoesNotReturnTentativePathOrFallbackToGeneric() {
        String personal = "work:" + person.id();
        NavGraph.Builder builder = NavGraph.builder().addNode(0, 0, 64, 0, "work")
                .addNode(1, 1, 64, 0, personal);
        for (int i = 0; i < 8193; i++) builder.addEdge(0, 1, 1);
        var decision = ResidentRoutePlanner.plan(person, work, builder.build(), 0);
        assertFailure(decision, BUDGET_EXHAUSTED, personal);
        assertEquals(1, decision.visited());
        assertEquals(8192, decision.edgesExamined());
    }

    @Test
    void oversizedGraphIsBudgetExhaustedEvenIfAlreadyAtAnchor() {
        NavGraph.Builder nodes = NavGraph.builder().addNode(0, 0, 64, 0, "work");
        for (int id = 1; id < 20_001; id++) nodes.addNode(id, id, 64, 0);
        NavGraph.Builder edges = NavGraph.builder().addNode(0, 0, 64, 0, "work").addNode(1, 1, 64, 0);
        for (int i = 0; i < 160_001; i++) edges.addEdge(0, 1, 1);
        for (NavGraph graph : List.of(nodes.build(), edges.build())) {
            var decision = ResidentRoutePlanner.plan(person, work, graph, 0);
            assertFailure(decision, BUDGET_EXHAUSTED, "work");
            assertEquals(0, decision.visited());
            assertEquals(0, decision.edgesExamined());
        }
    }

    @Test
    void exactGraphInputLimitsAllowPlanning() {
        NavGraph.Builder builder = NavGraph.builder().addNode(0, 0, 64, 0, "work");
        for (int id = 1; id < 20_000; id++) builder.addNode(id, id, 64, 0);
        for (int i = 0; i < 160_000; i++) builder.addEdge(0, 1, 1);
        var decision = ResidentRoutePlanner.plan(person, work, builder.build(), 0);
        assertEquals(AT_DESTINATION, decision.status());
        assertEquals(List.of(0L), decision.nodes());
        assertEquals(1, decision.visited());
        assertEquals(0, decision.edgesExamined());
    }

    @Test
    void decisionsDefensivelyCopyPathsAndCannotChangeCostThroughInputMutation() {
        List<Long> input = new ArrayList<>(List.of(1L, 2L));
        var decision = new ResidentRoutePlanner.Decision(WALK, input, 7L, "work", 2, 1);
        input.clear();
        assertEquals(List.of(1L, 2L), decision.nodes());
        assertEquals(7L, decision.cost());
        assertThrows(UnsupportedOperationException.class, () -> decision.nodes().clear());
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0, "work")
                .addEdge(1, 2, 7).build();
        var planned = ResidentRoutePlanner.plan(person, work, graph, 1);
        assertThrows(UnsupportedOperationException.class, () -> planned.nodes().add(3L));
        assertEquals(7L, planned.cost());
    }

    private static void assertFailure(ResidentRoutePlanner.Decision decision,
                                       ResidentRoutePlanner.Status status, String tag) {
        assertEquals(status, decision.status());
        assertEquals(tag, decision.targetTag());
        assertTrue(decision.nodes().isEmpty());
        assertEquals(-1L, decision.cost());
    }
}
