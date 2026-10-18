package com.prosperitysmp.npc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What an adapter can state about itself: names and values in order, frozen when built, and read safely. */
class CapabilitiesTest {

    @Test void aBuilderWithNothingSetBuildsAnEmptyCapabilitySet() {
        var caps = Capabilities.builder().build();
        assertTrue(caps.all().isEmpty());
        assertEquals("{}", caps.toString());
        assertFalse(caps.flag("guard"));
        assertEquals("", caps.value("guard"));
    }

    @Test void aFlagIsStoredAsTheTextTrueOrFalse() {
        var caps = Capabilities.builder().flag("on", true).flag("off", false).build();
        assertEquals("true", caps.value("on"));
        assertEquals("false", caps.value("off"));
        assertTrue(caps.flag("on"));
        assertFalse(caps.flag("off"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "True", " true", "true ", "1", "yes", "on", "", "false", "null"})
    void onlyTheExactLowercaseTextTrueCountsAsASetFlag(String text) {
        assertFalse(Capabilities.builder().value("x", text).build().flag("x"), "'" + text + "'");
    }

    @Test void theExactLowercaseTextTrueSetThroughValueCountsAsASetFlag() {
        assertTrue(Capabilities.builder().value("x", "true").build().flag("x"));
    }

    @Test void anEmptyValueIsAllowedAndIsKeptAsAName() {
        var caps = Capabilities.builder().value("owner-model", "").build();
        assertEquals("", caps.value("owner-model"));
        assertEquals(List.of("owner-model"), new ArrayList<>(caps.all().keySet()));
        assertFalse(caps.flag("owner-model"));
    }

    @Test void settingTheSameNameAgainKeepsTheLastValue() {
        var builder = Capabilities.builder().value("scale", "3").flag("scale", true);
        assertEquals("true", builder.build().value("scale"));
        assertEquals("2", builder.value("scale", "2").build().value("scale"));
        assertEquals(1, builder.build().all().size());
    }

    @Test void namesAreOrderedByPlainStringOrder() {
        var builder = Capabilities.builder();
        for (String name : List.of("b", "a", "_x", "B", "9", "10")) builder.value(name, "v");
        assertEquals(List.of("10", "9", "B", "_x", "a", "b"), new ArrayList<>(builder.build().all().keySet()));
    }

    @Test void namesAreCaseSensitive() {
        var caps = Capabilities.builder().value("Guard", "none").build();
        assertEquals("none", caps.value("Guard"));
        assertEquals("", caps.value("guard"));
    }

    @Test void aNameKeepsItsSurroundingSpacesAndIsADifferentName() {
        var caps = Capabilities.builder().value("a", "1").value(" a", "2").value("a ", "3").build();
        assertEquals("1", caps.value("a"));
        assertEquals("2", caps.value(" a"));
        assertEquals("3", caps.value("a "));
        assertEquals(3, caps.all().size());
    }

    @Test void toStringListsTheSortedPairs() {
        var caps = Capabilities.builder().value("scale", "2").flag("artifact-hashable", true).build();
        assertEquals("{artifact-hashable=true, scale=2}", caps.toString());
    }

    @Test void theMapIsReadOnlyAndStaysTheSameAfterARefusedWrite() {
        var caps = Capabilities.builder().value("a", "1").build();
        assertThrows(UnsupportedOperationException.class, () -> caps.all().put("b", "2"));
        assertThrows(UnsupportedOperationException.class, () -> caps.all().remove("a"));
        assertThrows(UnsupportedOperationException.class, () -> caps.all().clear());
        assertEquals("{a=1}", caps.all().toString());
    }

    @Test void theSameMapIsReturnedEachTime() {
        var caps = Capabilities.builder().value("a", "1").build();
        assertSame(caps.all(), caps.all());
    }

    @Test void aLaterBuildSeesLaterAdditionsButAnEarlierOneDoesNot() {
        var builder = Capabilities.builder().value("a", "1");
        var first = builder.build();
        var second = builder.value("b", "2").build();
        assertEquals("{a=1}", first.toString());
        assertEquals("{a=1, b=2}", second.toString());
    }

    @Test void everyBuilderCallReturnsTheBuilderItself() {
        var builder = Capabilities.builder();
        assertSame(builder, builder.flag("a", true));
        assertSame(builder, builder.value("b", "x"));
    }

    @Test void eachBuilderStartsEmpty() {
        Capabilities.builder().value("leak", "yes");
        assertTrue(Capabilities.builder().build().all().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\n"})
    void aBlankNameIsRefusedByBothBuilderMethods(String name) {
        assertThrows(IllegalArgumentException.class, () -> Capabilities.builder().value(name, "x"));
        assertThrows(IllegalArgumentException.class, () -> Capabilities.builder().flag(name, true));
    }

    @Test void theBlankNameMessageSaysWhatWasWrong() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> Capabilities.builder().value("", "x"));
        assertEquals("Capability name is blank", thrown.getMessage());
    }

    @Test void aRefusedValueLeavesTheBuilderUsable() {
        var builder = Capabilities.builder().value("a", "1");
        assertThrows(IllegalArgumentException.class, () -> builder.value(" ", "x"));
        assertThrows(NullPointerException.class, () -> builder.value("b", null));
        assertEquals("{a=1}", builder.build().toString());
    }

    @Test void aFlagWithANullNameIsRefused() {
        assertThrows(NullPointerException.class, () -> Capabilities.builder().flag(null, true));
    }

    @Test void readingANullNameIsRefused() {
        var caps = Capabilities.builder().value("a", "1").build();
        assertThrows(NullPointerException.class, () -> caps.flag(null));
        assertThrows(NullPointerException.class, () -> caps.value(null));
    }

    @Test void aFlagSetFalseThenTrueReadsTrue() {
        assertTrue(Capabilities.builder().flag("x", false).flag("x", true).build().flag("x"));
    }

    @Test void aValueIsKeptExactlyIncludingLineBreaks() {
        var caps = Capabilities.builder().value("owner-model", "line one\nline two ").build();
        assertEquals("line one\nline two ", caps.value("owner-model"));
    }

    @Test void aThousandNamesStayInOrderAndCountOnce() {
        var builder = Capabilities.builder();
        for (int i = 999; i >= 0; i--) builder.value(String.format("cap-%04d", i), Integer.toString(i));
        for (int i = 0; i < 1000; i += 2) builder.value(String.format("cap-%04d", i), "again");
        var caps = builder.build();
        assertEquals(1000, caps.all().size());
        assertEquals("cap-0000", caps.all().firstKey());
        assertEquals("cap-0999", caps.all().lastKey());
        assertEquals("again", caps.value("cap-0998"));
        assertEquals("999", caps.value("cap-0999"));
    }
}
