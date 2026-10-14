package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremiumWalkTest {
    private static final UUID WORLD = new UUID(0, 1);
    private static final ResidentProfile PERSON = ResidentCatalogue.betaCast().get(0);

    private static final UUID NEW_PROP = new UUID(0, 77);

    /** Resident's own home anchor sits at node 0; the new property's anchor (when present) at the last node. */
    private static NavGraph line(int nodes, boolean connected, boolean homeAnchor) {
        var b = NavGraph.builder();
        for (int i = 0; i < nodes; i++) {
            if (i == 0) b.addNode(i, i, 64, 0, "home:" + PERSON.id());
            else if (homeAnchor && i == nodes - 1) b.addNode(i, i, 64, 0, PremiumWalk.homeTag(NEW_PROP));
            else b.addNode(i, i, 64, 0);
        }
        for (int i = 0; i + 1 < nodes; i++) if (connected || i + 2 != nodes) b.addBiEdge(i, i + 1, 1);
        return b.build();
    }

    @Test void sameGraphWithARouteWalksToTheHomeAnchor() {
        var graph = line(5, true, true);
        var d = PremiumWalk.decide(WORLD, "district_a", graph, WORLD, "DISTRICT_A", line(5, true, true), PERSON, NEW_PROP, 0);
        assertTrue(d.walk());
        assertEquals(java.util.List.of(0L, 1L, 2L, 3L, 4L), d.nodes());
    }

    @Test void alreadyAtTheAnchorIsAWalkOfOneNode() {
        var d = PremiumWalk.decide(WORLD, "a", line(3, true, true), WORLD, "a", line(3, true, true), PERSON, NEW_PROP, 2);
        assertTrue(d.walk());
        assertEquals(1, d.nodes().size());
    }

    @Test void differentDistrictOrWorldFallsBack() {
        var graph = line(4, true, true);
        assertFalse(PremiumWalk.decide(WORLD, "a", graph, WORLD, "b", graph, PERSON, NEW_PROP, 0).walk());
        assertFalse(PremiumWalk.decide(WORLD, "a", graph, new UUID(0, 2), "a", graph, PERSON, NEW_PROP, 0).walk());
    }

    @Test void changedGraphMissingGraphOrProfileFallsBack() {
        assertFalse(PremiumWalk.decide(WORLD, "a", line(4, true, true), WORLD, "a", line(5, true, true), PERSON, NEW_PROP, 0).walk());
        assertFalse(PremiumWalk.decide(WORLD, "a", null, WORLD, "a", line(4, true, true), PERSON, NEW_PROP, 0).walk());
        assertFalse(PremiumWalk.decide(WORLD, "a", line(4, true, true), WORLD, "a", null, PERSON, NEW_PROP, 0).walk());
        assertFalse(PremiumWalk.decide(WORLD, "a", line(4, true, true), WORLD, "a", line(4, true, true), null, NEW_PROP, 0).walk());
    }

    @Test void noStartNodeNoAnchorOrNoRouteFallsBack() {
        var graph = line(4, true, true);
        assertFalse(PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, NEW_PROP, -1).walk());
        assertFalse(PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, NEW_PROP, 99).walk());
        var noAnchor = line(4, true, false);
        assertFalse(PremiumWalk.decide(WORLD, "a", noAnchor, WORLD, "a", noAnchor, PERSON, NEW_PROP, 0).walk());
        var split = line(4, false, true);
        var d = PremiumWalk.decide(WORLD, "a", split, WORLD, "a", split, PERSON, NEW_PROP, 0);
        assertFalse(d.walk());
        assertTrue(d.nodes().isEmpty());
    }

    @Test void homeIntentIgnoresTheClock() {
        var intent = PremiumWalk.homeIntent(PERSON);
        assertEquals("home", intent.destinationTag());
        assertEquals(com.prosperitysmp.npc.resident.ResidentRoutine.Activity.HOME, intent.activity());
    }

    @Test void destinationIsTheNewPropertyNotTheResidentsOldHome() {
        var graph = line(5, true, true);
        var d = PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, NEW_PROP, 2);
        assertTrue(d.walk());
        assertEquals(4L, d.nodes().get(d.nodes().size() - 1));
        // a different property has no anchor on this graph: fall back, never walk to the resident's own home
        assertFalse(PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, new UUID(0, 78), 2).walk());
        assertFalse(PremiumWalk.decide(WORLD, "a", graph, WORLD, "a", graph, PERSON, null, 2).walk());
    }

    @Test void arrivalEndsTheWalkOnlyForTheHomeRoute() {
        // adopted mid-path: the old routine path finishes first and must not count as arrival
        assertFalse(RenderManager.walkEnds(true, false, 3, 3));
        assertTrue(RenderManager.walkEnds(true, true, 3, 3));
        assertFalse(RenderManager.walkEnds(true, true, 2, 3));
        assertFalse(RenderManager.walkEnds(false, true, 3, 3));
    }
}
