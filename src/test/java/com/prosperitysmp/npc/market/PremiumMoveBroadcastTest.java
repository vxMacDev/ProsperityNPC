package com.prosperitysmp.npc.market;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PremiumMoveBroadcastTest {
    @Test void wordingIsPlainAndHasNoDashes() {
        String l = PremiumMoveBroadcast.line("Mara Voss", "old_harbor");
        assertEquals("Mara Voss moved to a better-run home in old harbor.", l);
        assertFalse(l.contains("—"));
    }
    @Test void unusableInputsAreSkipped() {
        assertNull(PremiumMoveBroadcast.line("  ", "market"));
        assertNull(PremiumMoveBroadcast.line("Mara", null));
        assertTrue(new PremiumMoveBroadcast().offer(null, "m", 0).isEmpty());
        assertEquals("Mara moved to a better-run home in m.", PremiumMoveBroadcast.line("Ma§ra", "m"));
    }
    @Test void oneBroadcastPerThirtyMinutes() {
        var b = new PremiumMoveBroadcast();
        long t = 1_000_000L;
        assertTrue(b.offer("A", "m", t).isPresent());
        assertTrue(b.offer("B", "m", t + 29 * 60_000L).isEmpty());
        assertTrue(b.offer("C", "m", t + 30 * 60_000L).isPresent());
        assertTrue(b.offer("D", "m", t + 31 * 60_000L).isEmpty());
    }
    @Test void rejectedMoveDoesNotExtendWindow() {
        var b = new PremiumMoveBroadcast();
        assertTrue(b.offer("A", "m", 0).isPresent());
        assertTrue(b.offer("B", "m", 20 * 60_000L).isEmpty());
        assertTrue(b.offer("C", "m", 30 * 60_000L).isPresent());
    }
}
