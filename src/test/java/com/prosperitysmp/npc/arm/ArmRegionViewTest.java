package com.prosperitysmp.npc.arm;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmRegionViewTest {
    @Test void legacyMixedCaseMatchesWithoutChangingCanonicalIdentity() {
        UUID world = UUID.randomUUID();
        var view = new ArmRegionView("c001", "world", world, null, "SELL", false);
        assertTrue(view.matches(world, "C001"));
        assertEquals("c001", view.regionId());
    }

    @Test void missingOrDifferentScopeFailsClosed() {
        UUID world = UUID.randomUUID();
        var view = new ArmRegionView("c001", "world", world, null, "SELL", false);
        assertFalse(view.matches(UUID.randomUUID(), "c001"));
        assertFalse(view.matches(null, "c001"));
        assertFalse(view.matches(world, null));
        assertFalse(view.matches(world, "c002"));
        assertFalse(new ArmRegionView(null, "world", world, null, "SELL", false).matches(world, "c001"));
    }
}
