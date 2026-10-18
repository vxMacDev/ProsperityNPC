package com.prosperitysmp.npc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** A guard install either holds a mode or carries the reason it did not. */
class GuardResultTest {

    @ParameterizedTest
    @EnumSource(GuardMode.class)
    void everyModeCountsAsInstalledEvenNone(GuardMode mode) {
        var result = GuardResult.installed(mode);
        assertTrue(result.isInstalled());
        assertEquals(Optional.of(mode), result.mode());
        assertEquals("", result.reason());
    }

    @Test void aFailedResultIsNotInstalledAndHasNoMode() {
        var result = GuardResult.failed("GriefPrevention is not available");
        assertFalse(result.isInstalled());
        assertTrue(result.mode().isEmpty());
        assertEquals("GriefPrevention is not available", result.reason());
    }

    @Test void anEmptyReasonIsAllowedAndStillMeansFailed() {
        var result = GuardResult.failed("");
        assertFalse(result.isInstalled());
        assertEquals("", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {" padded ", "two\nlines", "café — down"})
    void aReasonIsKeptVerbatim(String reason) {
        assertEquals(reason, GuardResult.failed(reason).reason());
    }

    @Test void aLongReasonIsNotTruncated() {
        assertEquals(5000, GuardResult.failed("a".repeat(5000)).reason().length());
    }

    @Test void aNullModeOrReasonIsRefused() {
        assertThrows(NullPointerException.class, () -> GuardResult.installed(null));
        assertThrows(NullPointerException.class, () -> GuardResult.failed(null));
    }
}
