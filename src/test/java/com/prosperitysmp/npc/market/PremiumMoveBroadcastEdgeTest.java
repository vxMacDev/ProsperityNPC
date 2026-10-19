package com.prosperitysmp.npc.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** The broadcast line keeps only printable text, caps each part at 40 characters and never names anything else. */
class PremiumMoveBroadcastEdgeTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Mara|old_harbor|Mara moved to a better-run home in old harbor.",
            "'  Mara Voss  '|town|Mara Voss moved to a better-run home in town.",
            "Mara|old-harbor|Mara moved to a better-run home in old harbor.",
            "Mara|OLD_HARBOR|Mara moved to a better-run home in old harbor.",
            "Mara|North-East_Gate|Mara moved to a better-run home in north east gate.",
            "Zoë|town|Zoë moved to a better-run home in town.",
            "MARA|town|MARA moved to a better-run home in town.",
            "§aMara|town|aMara moved to a better-run home in town.",
            "Ma§§ra|t§own|Mara moved to a better-run home in town."})
    void lineWording(String name, String market, String expected) {
        assertEquals(expected, PremiumMoveBroadcast.line(name, market));
    }

    @Test void controlCharactersAndDeleteAreDroppedFromBothParts() {
        assertEquals("Mara moved to a better-run home in old harbor.",
                PremiumMoveBroadcast.line("Ma\u0007r\ta\n", "old\r_har\u007fbor\u0000"));
        assertEquals("Mara moved to a better-run home in town.", PremiumMoveBroadcast.line("\u001bMara\u001f", "town"));
    }

    @Test void eachPartIsCutToFortyCharactersAndTrimmedAgain() {
        String long45 = "A".repeat(45);
        assertEquals("A".repeat(40) + " moved to a better-run home in town.", PremiumMoveBroadcast.line(long45, "town"));
        String spaceAtForty = "x".repeat(39) + " yyyy";
        assertEquals("x".repeat(39) + " moved to a better-run home in town.", PremiumMoveBroadcast.line(spaceAtForty, "town"));
        String market = "b".repeat(39) + "_c";
        assertEquals("Mara moved to a better-run home in " + "b".repeat(39) + ".", PremiumMoveBroadcast.line("Mara", market));
        assertEquals("M moved to a better-run home in " + "b".repeat(40) + ".", PremiumMoveBroadcast.line("M", "B".repeat(50)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "NULL|town", "''|town", "'   '|town", "§§|town", "'\u0007\u0008'|town",
            "Mara|NULL", "Mara|''", "Mara|___", "Mara|'- _ -'", "Mara|§"})
    void unusablePartsGiveNoLine(String name, String market) {
        assertNull(PremiumMoveBroadcast.line(name, market));
        assertTrue(new PremiumMoveBroadcast().offer(name, market, 0L).isEmpty());
    }

    @Test void anUnusableOfferNeitherOpensNorRestartsTheWindow() {
        var broadcast = new PremiumMoveBroadcast();
        assertTrue(broadcast.offer(null, "town", 0L).isEmpty());
        assertEquals("A moved to a better-run home in town.", broadcast.offer("A", "town", 1L).orElseThrow(),
                "the first usable move is broadcast at once");
        assertTrue(broadcast.offer("", "town", 2L).isEmpty());
        assertTrue(broadcast.offer("B", "town", 1L + PremiumMoveBroadcast.WINDOW_MILLIS - 1).isEmpty());
        assertTrue(broadcast.offer("C", "town", 1L + PremiumMoveBroadcast.WINDOW_MILLIS).isPresent());
    }

    @Test void theWindowIsThirtyMinutesFromTheLastBroadcast() {
        assertEquals(1_800_000L, PremiumMoveBroadcast.WINDOW_MILLIS);
        var broadcast = new PremiumMoveBroadcast();
        assertTrue(broadcast.offer("A", "town", 5_000L).isPresent());
        assertTrue(broadcast.offer("B", "town", 5_000L).isEmpty(), "same millisecond");
        assertTrue(broadcast.offer("C", "town", 1_804_999L).isEmpty());
        assertTrue(broadcast.offer("D", "town", 1_805_000L).isPresent());
        assertTrue(broadcast.offer("E", "town", 1_805_001L).isEmpty(), "the window restarted at the second broadcast");
    }

    @Test void aClockThatMovesBackwardsOpensTheWindowAndRestartsItThere() {
        var broadcast = new PremiumMoveBroadcast();
        assertTrue(broadcast.offer("A", "town", 10_000_000L).isPresent());
        assertTrue(broadcast.offer("B", "town", 9_999_999L).isPresent());
        assertTrue(broadcast.offer("C", "town", 10_000_000L).isEmpty(), "measured from the earlier time now");
        assertTrue(broadcast.offer("D", "town", 9_999_999L + PremiumMoveBroadcast.WINDOW_MILLIS).isPresent());
    }
}
