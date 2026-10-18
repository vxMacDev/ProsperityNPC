package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Which committed relocations get a walk home, and the exact reason a walk falls back to a plain respawn. */
class PremiumWalkMovesTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final UUID WORLD = new UUID(0, 1);
    private static final UUID NEW_PROPERTY = new UUID(0, 77);
    private static final ResidentProfile PERSON = ResidentCatalogue.betaCast().get(0);

    private static HousingMoveRepository.Result result(UUID resident, UUID property, HousingPolicy.Tier tier, UUID originProperty,
                                                       boolean newlyApplied) {
        HousingMoveRequest.Origin origin = originProperty == null ? null
                : new HousingMoveRequest.Origin(originProperty, UUID.randomUUID(), 0, 0, 0, UUID.randomUUID());
        var request = new HousingMoveRequest(UUID.randomUUID(), resident, property, UUID.randomUUID(), WORLD, "old_harbor", "room",
                0, 0, 0, origin == null ? 0 : 1, 0, DAY, tier, origin);
        var receipt = new HousingMoveRepository.Receipt(request, new HousingMoveRequest.Policy(3, 1, 1, 7), 3, DAY.plusDays(7),
                Instant.parse("2026-10-14T17:00:00Z"));
        return new HousingMoveRepository.Result(newlyApplied, receipt);
    }

    @Test void aCommittedPremiumRelocationBecomesOneMoveWithBothProperties() {
        UUID resident = UUID.randomUUID(), oldHome = UUID.randomUUID(), newHome = UUID.randomUUID();
        var moves = PremiumWalk.moves(List.of(result(resident, newHome, HousingPolicy.Tier.PREMIUM, oldHome, true)));
        assertEquals(List.of(new PremiumWalk.Move(resident, oldHome, newHome)), moves);
    }

    @Test void aReplayedRelocationIsNotMovedAgain() {
        assertEquals(List.of(), PremiumWalk.moves(List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), HousingPolicy.Tier.PREMIUM, UUID.randomUUID(), false))));
    }

    @Test void anAdmissionWithoutAnOriginHasNothingToWalkFrom() {
        assertEquals(List.of(), PremiumWalk.moves(List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), HousingPolicy.Tier.PREMIUM, null, true))));
    }

    @Test void aStandardTierAdmissionIsNeverAWalk() {
        assertEquals(List.of(), PremiumWalk.moves(List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), HousingPolicy.Tier.STANDARD, null, true))));
    }

    @Test void onlyTheQualifyingResultsOfAMixedBatchSurviveInOrder() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        UUID oldA = UUID.randomUUID(), oldC = UUID.randomUUID();
        UUID newA = UUID.randomUUID(), newB = UUID.randomUUID(), newC = UUID.randomUUID();
        var moves = PremiumWalk.moves(List.of(
                result(a, newA, HousingPolicy.Tier.PREMIUM, oldA, true),
                result(b, newB, HousingPolicy.Tier.PREMIUM, UUID.randomUUID(), false),
                result(UUID.randomUUID(), UUID.randomUUID(), HousingPolicy.Tier.STANDARD, null, true),
                result(c, newC, HousingPolicy.Tier.PREMIUM, oldC, true)));
        assertEquals(List.of(new PremiumWalk.Move(a, oldA, newA), new PremiumWalk.Move(c, oldC, newC)), moves);
    }

    @Test void noResultsMeansNoMoves() {
        assertEquals(List.of(), PremiumWalk.moves(List.of()));
    }

    @Test void theWindowAndPendingLimitAreTenMinutesAndSixtyFour() {
        assertEquals(600_000L, PremiumWalk.WINDOW_MS);
        assertEquals(64, PremiumWalk.MAX_PENDING);
    }

    @Test void theHomeTagNamesTheProperty() {
        assertEquals("home:00000000-0000-0000-0000-00000000004d", PremiumWalk.homeTag(NEW_PROPERTY));
    }

    @Test void aDecisionKeepsAnImmutableCopyOfItsPath() {
        List<Long> path = new java.util.ArrayList<>(List.of(1L, 2L));
        var decision = new PremiumWalk.Decision(PremiumWalk.Verdict.WALK, "route", path);
        path.add(3L);
        assertEquals(List.of(1L, 2L), decision.nodes());
        assertThrows(UnsupportedOperationException.class, () -> decision.nodes().add(9L));
        assertTrue(decision.walk());
        assertFalse(new PremiumWalk.Decision(PremiumWalk.Verdict.FALLBACK, "x", List.of()).walk());
    }

    private static NavGraph line(boolean homeAnchor, boolean connected) {
        var b = NavGraph.builder();
        for (int i = 0; i < 4; i++) {
            if (homeAnchor && i == 3) b.addNode(i, i, 64, 0, PremiumWalk.homeTag(NEW_PROPERTY));
            else b.addNode(i, i, 64, 0);
        }
        for (int i = 0; i < 3; i++) if (connected || i != 1) b.addBiEdge(i, i + 1, 1);
        return b.build();
    }

    private static PremiumWalk.Decision decide(NavGraph graph) {
        return PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, NEW_PROPERTY, 0);
    }

    @Test void aWalkNamesItsRouteAndAnArrivedResidentSaysSo() {
        var walking = decide(line(true, true));
        assertEquals("route", walking.reason());
        assertEquals(List.of(0L, 1L, 2L, 3L), walking.nodes());
        var there = PremiumWalk.decide(WORLD, "a", line(true, true), WORLD, "a", line(true, true), PERSON, NEW_PROPERTY, 3);
        assertEquals("already there", there.reason());
        assertEquals(List.of(3L), there.nodes());
    }

    @Test void everyFallbackNamesItsOwnReason() {
        NavGraph graph = line(true, true);
        assertEquals("graph unavailable", PremiumWalk.decide(WORLD, "a", null, WORLD, "a", graph, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("graph unavailable", PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", null, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("graph unavailable", PremiumWalk.decide(null, "a", graph, WORLD, "a", graph, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("graph unavailable", PremiumWalk.decide(WORLD, "a", graph, null, "a", graph, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("graph unavailable", PremiumWalk.decide(WORLD, null, graph, WORLD, "a", graph, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("graph unavailable", PremiumWalk.decide(WORLD, "a", graph, WORLD, null, graph, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("different graph", PremiumWalk.decide(WORLD, "a", graph, new UUID(0, 2), "a", graph, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("different graph", PremiumWalk.decide(WORLD, "a", graph, WORLD, "b", graph, PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("graph changed", PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", line(false, true), PERSON, NEW_PROPERTY, 0).reason());
        assertEquals("no profile", PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, null, NEW_PROPERTY, 0).reason());
        assertEquals("no profile", PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, null, 0).reason());
        assertEquals("no start node", PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, NEW_PROPERTY, -1).reason());
        assertEquals("no start node", PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, NEW_PROPERTY, 99).reason());
    }

    @Test void aMissingAnchorAndABrokenRouteFallBackWithTheirStatus() {
        assertEquals("no route: NO_ANCHOR", decide(line(false, true)).reason());
        assertEquals("no route: NO_ROUTE", decide(line(true, false)).reason());
        assertEquals(PremiumWalk.Verdict.FALLBACK, decide(line(true, false)).verdict());
        assertEquals(List.of(), decide(line(true, false)).nodes());
    }

    @Test void theDistrictNameIsComparedWithoutRegardToCase() {
        NavGraph graph = line(true, true);
        assertTrue(PremiumWalk.decide(WORLD, "Old_Harbor", graph, WORLD, "old_harbor", graph, PERSON, NEW_PROPERTY, 0).walk());
        assertFalse(PremiumWalk.decide(WORLD, "Old_Harbor", graph, WORLD, "old-harbor", graph, PERSON, NEW_PROPERTY, 0).walk());
    }

    @Test void twoSeparateButIdenticalGraphsCountAsTheSameGraph() {
        assertTrue(PremiumWalk.sameGraph(line(true, true), line(true, true)));
        NavGraph graph = line(true, true);
        assertTrue(PremiumWalk.sameGraph(graph, graph));
    }

    @Test void graphsThatDifferInAnEdgeANodeTagOrPositionAreNotTheSame() {
        assertFalse(PremiumWalk.sameGraph(line(true, true), line(true, false)));
        assertFalse(PremiumWalk.sameGraph(line(true, true), line(false, true)));
        NavGraph moved = NavGraph.builder().addNode(0, 0, 64, 0).addNode(1, 1, 65, 0).addNode(2, 2, 64, 0)
                .addNode(3, 3, 64, 0, PremiumWalk.homeTag(NEW_PROPERTY)).addBiEdge(0, 1, 1).addBiEdge(1, 2, 1).addBiEdge(2, 3, 1).build();
        assertFalse(PremiumWalk.sameGraph(line(true, true), moved));
        NavGraph fewer = NavGraph.builder().addNode(0, 0, 64, 0).build();
        assertFalse(PremiumWalk.sameGraph(line(true, true), fewer));
    }
}
