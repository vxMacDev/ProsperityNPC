package com.prosperitysmp.npc.interaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** How a reply becomes private speech lines: 48 characters wide, never cutting a word, refusing what cannot fit. */
class ResidentSpeechWrapTest {
    private static String word(int length) { return "x".repeat(length); }

    @Test void aWordOfExactlyFortyEightCharactersFitsOnItsOwnLine() {
        assertEquals(List.of(word(48)), ResidentSpeech.wrap(word(48)));
    }

    @Test void aWordOfFortyNineCharactersMakesTheWholeReplyUnfit() {
        assertEquals(List.of(), ResidentSpeech.wrap(word(49)));
        assertEquals(List.of(), ResidentSpeech.wrap("Short words then " + word(49) + " and more"));
    }

    @Test void twoWordsFillingFortyEightCharactersShareALine() {
        String text = word(24) + " " + word(23);
        assertEquals(List.of(text), ResidentSpeech.wrap(text));
        assertEquals(48, ResidentSpeech.wrap(text).get(0).length());
    }

    @Test void twoWordsNeedingFortyNineCharactersSplitIntoTwoLines() {
        assertEquals(List.of(word(24), word(24)), ResidentSpeech.wrap(word(24) + " " + word(24)));
    }

    @Test void aLongSentenceBreaksAtWordBoundariesAndKeepsEveryWordInOrder() {
        String text = "I'm a restorer. I like finding what is worth keeping in an old build. I'm part of the Vale household.";
        List<String> lines = ResidentSpeech.wrap(text);
        assertEquals(List.of("I'm a restorer. I like finding what is worth", "keeping in an old build. I'm part of the Vale", "household."), lines);
        assertEquals(text, String.join(" ", lines));
    }

    @Test void everyLineIsAtMostFortyEightCharacters() {
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega";
        for (String line : ResidentSpeech.wrap(text)) assertTrue(line.length() <= 48, line);
    }

    @ParameterizedTest @ValueSource(strings = {"one two", "one  two", "one\ttwo", "one\ntwo", "one\r\ntwo", "  one two  ", "\none two\n"})
    void anyWhitespaceBetweenWordsBecomesOneSpace(String text) {
        assertEquals(List.of("one two"), ResidentSpeech.wrap(text));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\n "})
    void blankTextHasNoLines(String text) {
        assertEquals(List.of(), ResidentSpeech.wrap(text));
    }

    @Test void nullTextHasNoLines() {
        assertEquals(List.of(), ResidentSpeech.wrap(null));
    }

    @Test void theResultIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> ResidentSpeech.wrap("one two").add("three"));
    }

    @Test void aSingleShortWordIsOneLine() {
        assertEquals(List.of("Hello"), ResidentSpeech.wrap("Hello"));
    }

    @Test void sixFullWidthWordsAreSixLinesAndSevenAreSeven() {
        assertEquals(6, ResidentSpeech.wrap(String.join(" ", java.util.Collections.nCopies(6, word(48)))).size());
        assertEquals(7, ResidentSpeech.wrap(String.join(" ", java.util.Collections.nCopies(7, word(48)))).size());
    }

    @Test void thePunctuationInsideAWordIsKept() {
        assertEquals(List.of("Don't panic - it's \"fine\" (really)!"), ResidentSpeech.wrap("Don't panic - it's \"fine\" (really)!"));
    }

    @Test void aNonBreakingSpaceIsNotASeparatorSoItCountsTowardTheWord() {
        String joined = "a" + Character.toString(0x00a0) + "b";
        assertEquals(List.of(joined), ResidentSpeech.wrap(joined));
    }
}
