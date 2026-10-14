package com.prosperitysmp.npc.interaction;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EncounterViewTest {
    @Test void wrapsWithoutLosingWordsOrExceedingLoreWidth() {
        String sentence = "A good home is also a neighborhood where you get to know people.";
        var lines = EncounterView.wrap(sentence, 20);
        assertEquals(sentence, String.join(" ", lines));
        assertTrue(lines.stream().allMatch(s -> s.length() <= 20));
    }
    @Test void longWordsAreSplitRatherThanOverflowing() {
        String text = "abcdefghijklmnopqrstuvwx";
        var lines = EncounterView.wrap(text, 8);
        assertEquals(List.of("abcdefgh", "ijklmnop", "qrstuvwx"), lines);
        assertEquals(text, String.join("", lines));
    }
    @Test void whitespaceAndExactBoundaryDoNotCreateBlankRows() {
        assertEquals(List.of("12345678", "abcdefgh"), EncounterView.wrap(" 12345678   abcdefgh ", 8));
        assertEquals(List.of(), EncounterView.wrap(" ", 8));
    }
    @Test void tooNarrowOrUnboundedLoreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EncounterView.wrap("text", 7));
        assertThrows(IllegalArgumentException.class, () -> EncounterView.wrap("text", 81));
    }

    private static EncounterView sample() {
        return new EncounterView("Mara", "Cook", "Stranger", "Hello there.",
                List.of(com.prosperitysmp.npc.resident.ResidentDialogue.Topic.ROUTINE,
                        com.prosperitysmp.npc.resident.ResidentDialogue.Topic.HOME),
                List.of(new ResidentAction(ResidentAction.Kind.VISIT, "market", null, new java.util.UUID(0, 9), "Show me the market")),
                "I feel settled.");
    }

    @Test void withNoticeAppendsAndKeepsFields() {
        var base = sample();
        var shown = base.withNotice("I can only stay a little longer.");
        assertEquals("Hello there. I can only stay a little longer.", shown.line());
        assertEquals(base.name(), shown.name());
        assertEquals(base.occupation(), shown.occupation());
        assertEquals(base.familiarity(), shown.familiarity());
        assertEquals(base.topics(), shown.topics());
        assertEquals(base.actions(), shown.actions());
        assertEquals("I feel settled.", shown.moodSuffix());
    }

    @Test void blankNoticeIsIdentity() {
        var base = sample();
        assertSame(base, base.withNotice(null));
        assertSame(base, base.withNotice(""));
        assertSame(base, base.withNotice("   "));
    }

    @Test void noticeSurvivesRoutineActivityOverride() {
        var activity = new com.prosperitysmp.npc.resident.ResidentActivity(
                com.prosperitysmp.npc.resident.ResidentActivity.State.PAUSED,
                com.prosperitysmp.npc.resident.ResidentRoutine.Activity.WORK);
        var shown = sample().withActivity(com.prosperitysmp.npc.resident.ResidentDialogue.Topic.ROUTINE, activity)
                .withNotice(ResidentEncounterController.PAUSE_ENDING_LINE);
        assertEquals("I've paused my walk to talk with you. I feel settled. I can only stay a little longer.", shown.line());
    }
}
