package com.prosperitysmp.npc.appearance;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.prosperitysmp.npc.appearance.OutfitChangePolicy.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

class OutfitChangePolicyTest {
    private static final String EVERYDAY = "resident/v1/otto-marsh/everyday";
    private static final String LEISURE = "resident/v1/otto-marsh/leisure";

    private static ResidentAppearanceCatalogue.Selection wants(String key, ResidentAppearanceCatalogue.Status status) {
        var applied = new ResidentAppearanceCatalogue.Applied(key, ResidentAppearanceCatalogue.citizensKey(key), TestSkins.synthetic('b'));
        return new ResidentAppearanceCatalogue.Selection(1, LEISURE, status, Optional.of(applied));
    }

    @Test void noneWhenTheAvatarAlreadyWearsTheSelectedSkin() {
        assertEquals(NONE, OutfitChangePolicy.decide(LEISURE, wants(LEISURE, ResidentAppearanceCatalogue.Status.REQUESTED), 100, false, 16));
        assertEquals(NONE, OutfitChangePolicy.decide(EVERYDAY, wants(EVERYDAY, ResidentAppearanceCatalogue.Status.BASE_FALLBACK), 100, false, 16));
    }

    @Test void waitWhileAViewerIsInsideTheRadius() {
        var selection = wants(LEISURE, ResidentAppearanceCatalogue.Status.REQUESTED);
        assertEquals(WAIT, OutfitChangePolicy.decide(EVERYDAY, selection, 5, false, 16));
        assertEquals(WAIT, OutfitChangePolicy.decide(EVERYDAY, selection, 16, false, 16), "exactly on the edge is still close");
        assertEquals(WAIT, OutfitChangePolicy.decide(EVERYDAY, selection, Double.NaN, false, 16), "unknown distance waits");
        assertEquals(CHANGE, OutfitChangePolicy.decide(EVERYDAY, selection, 16.01, false, 16));
    }

    @Test void waitWhileAConversationIsOpen() {
        assertEquals(WAIT, OutfitChangePolicy.decide(EVERYDAY, wants(LEISURE, ResidentAppearanceCatalogue.Status.REQUESTED),
                Double.POSITIVE_INFINITY, true, 16));
    }

    @Test void changeWhenHiddenAndIdle() {
        assertEquals(CHANGE, OutfitChangePolicy.decide(EVERYDAY, wants(LEISURE, ResidentAppearanceCatalogue.Status.REQUESTED),
                Double.POSITIVE_INFINITY, false, 16));
        assertEquals(CHANGE, OutfitChangePolicy.decide(LEISURE, wants(EVERYDAY, ResidentAppearanceCatalogue.Status.BASE_FALLBACK),
                30, false, 16), "a variant removed at reload falls back to everyday");
    }

    @Test void missingBaseOrUnknownStateNeverChanges() {
        var missing = new ResidentAppearanceCatalogue.Selection(1, LEISURE, ResidentAppearanceCatalogue.Status.MISSING_BASE, Optional.empty());
        assertEquals(NONE, OutfitChangePolicy.decide(EVERYDAY, missing, 100, false, 16));
        assertEquals(NONE, OutfitChangePolicy.decide(null, wants(LEISURE, ResidentAppearanceCatalogue.Status.REQUESTED), 100, false, 16));
        assertEquals(NONE, OutfitChangePolicy.decide(EVERYDAY, null, 100, false, 16));
    }

    @Test void radiusAndRateAreClamped() {
        assertEquals(16, OutfitChangePolicy.hiddenRadius(16, 48));
        assertEquals(8, OutfitChangePolicy.hiddenRadius(2, 48));
        assertEquals(48, OutfitChangePolicy.hiddenRadius(500, 48));
        assertEquals(30, OutfitChangePolicy.hiddenRadius(40, 30), "never above rendering.radius");
        assertEquals(8, OutfitChangePolicy.hiddenRadius(40, 4), "never below 8, so always above talking range");
        assertEquals(16, OutfitChangePolicy.hiddenRadius(Double.NaN, 48));
        assertEquals(1, OutfitChangePolicy.changesPerTick(0));
        assertEquals(5, OutfitChangePolicy.changesPerTick(99));
        assertEquals(2, OutfitChangePolicy.changesPerTick(2));
    }
}
