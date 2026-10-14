package com.prosperitysmp.npc.interaction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResidentSpeechTest {
    @Test void wrapsWithoutDroppingReplyWords() {
        String reply = "I'm a restorer. I like finding what is worth keeping in an old build. I'm part of the Vale household.";
        var lines = ResidentSpeech.wrap(reply);
        assertTrue(lines.size() > 1);
        assertEquals(reply, String.join(" ", lines));
        assertTrue(lines.stream().allMatch(line -> line.length() <= 48));
    }

    @Test void oversizedWordUsesFallbackRatherThanClipping() {
        assertTrue(ResidentSpeech.wrap("x".repeat(49)).isEmpty());
    }

    @Test void emptyAndWhitespaceSafe() {
        assertTrue(ResidentSpeech.wrap(null).isEmpty());
        assertTrue(ResidentSpeech.wrap("  ").isEmpty());
        assertEquals("Hello there", String.join(" ", ResidentSpeech.wrap("Hello\nthere")));
    }
}
