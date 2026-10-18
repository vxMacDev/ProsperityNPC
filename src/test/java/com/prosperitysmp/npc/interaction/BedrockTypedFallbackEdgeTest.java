package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.interaction.ResidentAction.Kind;
import com.prosperitysmp.npc.resident.ResidentDialogue.Topic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The typed-command lines for players who cannot click: numbering, topic words and the number parser's edges. */
class BedrockTypedFallbackEdgeTest {
    private static ResidentAction action(String label) {
        return new ResidentAction(Kind.CANCEL, null, UUID.randomUUID(), new UUID(0, 1), label);
    }

    @Test void actionsAreNumberedFromOneInTheOrderGiven() {
        List<String> lines = BedrockTypedFallback.lines(List.of(action("Cancel one"), action("Find two"), action("Take three")), List.of());
        assertEquals(List.of("Type /resident act 1 for: Cancel one", "Type /resident act 2 for: Find two",
                "Type /resident act 3 for: Take three", "Type /resident leave to end the chat."), lines);
    }

    @Test void everyTopicIsListedLowerCaseInOrderOnOneLine() {
        List<String> lines = BedrockTypedFallback.lines(List.of(), Arrays.asList(Topic.values()));
        assertEquals("Type /resident choose <topic>: greeting, routine, home, business, commerce, history, about, tips, contracts",
                lines.get(0));
        assertEquals(2, lines.size());
    }

    @Test void noTopicsMeansNoChooseLineAndNoActionsMeansNoActLine() {
        assertEquals(List.of("Type /resident leave to end the chat."), BedrockTypedFallback.lines(List.of(), List.of()));
        assertEquals(List.of("Type /resident choose <topic>: home", "Type /resident leave to end the chat."),
                BedrockTypedFallback.lines(List.of(), List.of(Topic.HOME)));
    }

    @Test void actionsComeBeforeTheTopicsAndTheLeaveLineIsAlwaysLast() {
        List<String> lines = BedrockTypedFallback.lines(List.of(action("Only")), List.of(Topic.TIPS));
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("Type /resident act 1"));
        assertTrue(lines.get(1).startsWith("Type /resident choose"));
        assertEquals("Type /resident leave to end the chat.", lines.get(2));
    }

    @Test void theLinesAreAnImmutableCopy() {
        List<ResidentAction> actions = new ArrayList<>(List.of(action("A")));
        List<String> lines = BedrockTypedFallback.lines(actions, List.of());
        actions.add(action("B"));
        assertEquals(2, lines.size());
        assertThrows(UnsupportedOperationException.class, () -> lines.add("x"));
    }

    @ParameterizedTest
    @CsvSource({"1,0", "2,1", "9,8", "10,9", "98,97", "99,98", "'  7  ',6", "007,6", "099,98", "+5,4"})
    void aTypedNumberFromOneToNinetyNineIsAZeroBasedIndex(String typed, int index) {
        assertEquals(index, BedrockTypedFallback.index(typed));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "-0", "100", "101", "1000", "00", "", " ", "x", "one", "1.0", "1,5", "1 2", "0x10",
            "1e1", "9999999999", "-9999999999", "2147483648", "++1", "1-"})
    void anythingElseIsNotAnIndex(String typed) {
        assertEquals(-1, BedrockTypedFallback.index(typed));
    }

    @Test void aTrailingNewlineOrTabIsIgnored() {
        assertEquals(2, BedrockTypedFallback.index("3\n"));
        assertEquals(2, BedrockTypedFallback.index("\t3\t"));
    }
}
