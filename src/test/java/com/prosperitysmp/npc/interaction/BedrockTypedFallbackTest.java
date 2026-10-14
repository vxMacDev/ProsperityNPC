package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.ResidentDialogue;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedrockTypedFallbackTest {
    @Test void everyChoiceHasATypedCommandAndNoClickIsNeeded() {
        var action = new ResidentAction(ResidentAction.Kind.TRAIN, "builder", null, new UUID(0, 1), "Train builder");
        var lines = BedrockTypedFallback.lines(List.of(action),
                List.of(ResidentDialogue.Topic.ROUTINE, ResidentDialogue.Topic.BUSINESS));
        assertEquals("Type /resident act 1 for: Train builder", lines.getFirst());
        assertTrue(lines.get(1).contains("/resident choose <topic>"));
        assertTrue(lines.get(1).contains("routine, business"));
        assertEquals("Type /resident leave to end the chat.", lines.getLast());
        assertThrows(UnsupportedOperationException.class, () -> lines.add("x"));
    }

    @Test void noActionsOrTopicsStillShowsHowToLeave() {
        assertEquals(List.of("Type /resident leave to end the chat."), BedrockTypedFallback.lines(List.of(), List.of()));
    }

    @Test void typedActionNumbersAreOneBasedAndBounded() {
        assertEquals(0, BedrockTypedFallback.index("1"));
        assertEquals(2, BedrockTypedFallback.index(" 3 "));
        for (String bad : List.of("0", "-1", "x", "", "100", "1.5")) assertEquals(-1, BedrockTypedFallback.index(bad), bad);
    }
}
