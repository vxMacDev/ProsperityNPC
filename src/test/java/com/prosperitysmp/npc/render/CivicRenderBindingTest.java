package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.town.CivicPlacement;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CivicRenderBindingTest {
    private static final UUID RESIDENT = new UUID(0, 1), WORLD = new UUID(0, 2);
    private static final NavGraph GRAPH = NavGraph.builder().addNode(1, 10, 64, 20, "market").build();

    @Test void civicBindingNeedsTheSameRevisionAndNoPropertyBinding() {
        var candidate = civic(3);
        assertTrue(RenderManager.matchesBinding(candidate, null, 0, 3));
        assertFalse(RenderManager.matchesBinding(candidate, null, 0, 2));
        assertFalse(RenderManager.matchesBinding(candidate, UUID.randomUUID(), 0, 3));
        assertEquals(WORLD, candidate.worldId());
        assertEquals("downtown", candidate.district());
    }

    @Test void publishingANewRouteInvalidatesCivicCandidatesAsWellAsPropertyCandidates() {
        var key = new RenderManager.GraphKey(WORLD, "downtown");
        var snapshot = new RenderManager.Snapshot(Map.of(RESIDENT, civic(1)), Map.of(key, GRAPH), Set.of(key));
        var invalidated = snapshot.without(key);
        assertTrue(invalidated.candidates().isEmpty());
        assertTrue(invalidated.graphs().isEmpty());
        assertTrue(invalidated.checked().isEmpty());
    }

    @Test void aResidentCannotHaveAnUnspecifiedBinding() {
        assertThrows(IllegalArgumentException.class, () -> new RenderManager.Candidate(null, "Mara", GRAPH, null));
    }

    private static RenderManager.Candidate civic(long revision) {
        return new RenderManager.Candidate(null, "Mara", GRAPH, null,
                new CivicPlacement(RESIDENT, WORLD, "downtown", true, revision));
    }
}
