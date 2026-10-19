package com.prosperitysmp.npc.resident;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Owner-authored lines: what a line may hold, the exact-two-or-none list rule and how a variant picks its line. */
class ResidentLinesTest {
    private static final List<String> TWO = List.of("First line.", "Second line.");
    private static final String GRINNING_FACE = Character.toString(0x1F600);
    private static final String HEAVY_DOLLAR = Character.toString(0x1F4B2);

    @Test void theLimitsAreTheDocumentedOnes() {
        assertEquals(160, ResidentLines.MAX_LINE);
        assertEquals("{name}", ResidentLines.NAME_TOKEN);
        assertEquals(2, ResidentDialogue.VARIANT_COUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hello.", "a", "Welcome to the neighborhood, {name}.", "{name}", "{name} here", "Hi {name}!",
            "Caf\u00e9 au lait", "Quotes \"inside\" and 'single'", "Percent 50% and #hash and @at and &amp",
            "Inner  double space", "Dash - and \u2013 en dash", "Brackets [ok] (ok) <ok>", "Stray \u00a0 nbsp inside"})
    void aPlainLineIsValid(String line) {
        assertTrue(ResidentLines.validLine(line), line);
    }

    @Test void anEmojiLineIsValid() {
        assertTrue(ResidentLines.validLine("Emoji " + GRINNING_FACE + " fine"));
    }

    @Test void aLineOfExactlyOneHundredSixtyCharactersIsValidAndOneMoreIsNot() {
        assertTrue(ResidentLines.validLine("x".repeat(160)));
        assertFalse(ResidentLines.validLine("x".repeat(161)));
    }

    @Test void theLengthLimitCountsUtf16UnitsSoSurrogatePairsCountTwice() {
        assertTrue(ResidentLines.validLine(GRINNING_FACE.repeat(80)));
        assertFalse(ResidentLines.validLine(GRINNING_FACE.repeat(81)));
    }

    @Test void aNullOrEmptyLineIsInvalid() {
        assertFalse(ResidentLines.validLine(null));
        assertFalse(ResidentLines.validLine(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " leading", "trailing ", "\u2003em space lead", "trail em\u2003", "  both  "})
    void paddingMakesALineInvalid(String line) {
        assertFalse(ResidentLines.validLine(line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Costs $5", "$", "5$", "Fullwidth \uff04 sign", "Small \ufe69 sign", "Euro \u20ac", "Pound \u00a3",
            "Yen \u00a5", "Cent \u00a2", "Rupee \u20b9", "Bitcoin \u20bf", "Won \u20a9", "Currency \u00a4"})
    void everyCurrencySymbolIsRefused(String line) {
        assertFalse(ResidentLines.validLine(line), line);
    }

    @Test void theHeavyDollarEmojiIsNotACurrencySymbolSoItIsAllowed() {
        assertTrue(ResidentLines.validLine("Dollar " + HEAVY_DOLLAR + " ok?"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00a7c red", "plain \u00a7", "\u00a7"})
    void theSectionSignIsRefused(String line) {
        assertFalse(ResidentLines.validLine(line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tab\there", "new\nline", "carriage\rreturn", "nul\u0000char", "bell\u0007", "escape\u001b[0m",
            "del\u007f", "next\u0085line", "c1\u009bcontrol"})
    void controlCharactersAreRefused(String line) {
        assertFalse(ResidentLines.validLine(line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"line\u2028sep", "para\u2029sep", "zero\u200bwidth", "joiner\u200dhere", "bom\ufeffhere",
            "soft\u00adhyphen", "rtl\u202emark", "isolate\u2066x", "word\u2060joiner"})
    void invisibleFormattingCharactersAreRefused(String line) {
        assertFalse(ResidentLines.validLine(line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{x}", "{name", "name}", "{", "}", "{{name}}", "{Name}", "{ name}", "{name }", "{NAME}",
            "{name}{name}", "{name} and {name}", "{name}{", "}{name}", "{name}}", "{{name}"})
    void bracesAreOnlyAllowedInsideOneNameToken(String line) {
        assertFalse(ResidentLines.validLine(line), line);
    }

    @Test void oneNameTokenAnywhereInTheLineIsValid() {
        assertTrue(ResidentLines.validLine("{name} says hello"));
        assertTrue(ResidentLines.validLine("hello {name} again"));
        assertTrue(ResidentLines.validLine("hello {name}"));
    }

    @Test void moneySignCoversDollarAndEveryCurrencyCategoryCodePoint() {
        assertTrue(ResidentLines.moneySign('$'));
        assertTrue(ResidentLines.moneySign(0xff04));
        assertTrue(ResidentLines.moneySign(0xfe69));
        assertTrue(ResidentLines.moneySign(0x20ac));
        assertTrue(ResidentLines.moneySign(0x00a2));
        assertFalse(ResidentLines.moneySign('S'));
        assertFalse(ResidentLines.moneySign('%'));
        assertFalse(ResidentLines.moneySign('#'));
        assertFalse(ResidentLines.moneySign('0'));
        assertFalse(ResidentLines.moneySign(0x00a7));
    }

    @Test void noneIsEmptyAndHasNoLines() {
        assertTrue(ResidentLines.NONE.isEmpty());
        assertEquals(List.of(), ResidentLines.NONE.greeting());
        assertEquals(List.of(), ResidentLines.NONE.about());
        assertEquals(List.of(), ResidentLines.NONE.home());
    }

    @Test void anyOneNonEmptyListMakesTheLinesNonEmpty() {
        assertFalse(new ResidentLines(TWO, List.of(), List.of()).isEmpty());
        assertFalse(new ResidentLines(List.of(), TWO, List.of()).isEmpty());
        assertFalse(new ResidentLines(List.of(), List.of(), TWO).isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {1, 3, 4, 10})
    void aListMustBeEmptyOrExactlyTwoLines(int size) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < size; i++) lines.add("Line " + i);
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(lines, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(List.of(), lines, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(List.of(), List.of(), lines));
    }

    @Test void aListWithAnInvalidLineIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(List.of("Fine.", "Costs $5"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(List.of(), List.of("", "Fine."), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(List.of(), List.of(), List.of("Fine.", " padded")));
    }

    @Test void aNullListOrNullLineIsRefused() {
        assertThrows(NullPointerException.class, () -> new ResidentLines(null, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new ResidentLines(List.of(), null, List.of()));
        assertThrows(NullPointerException.class, () -> new ResidentLines(List.of(), List.of(), null));
        List<String> withNull = new ArrayList<>();
        withNull.add("Fine.");
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new ResidentLines(withNull, List.of(), List.of()));
    }

    @Test void theRecordKeepsAnImmutableCopyOfTheCallersList() {
        List<String> source = new ArrayList<>(TWO);
        ResidentLines lines = new ResidentLines(source, List.of(), List.of());
        source.set(0, "Changed after the fact.");
        assertEquals(TWO, lines.greeting());
        assertThrows(UnsupportedOperationException.class, () -> lines.greeting().add("x"));
    }

    @Test void renderReplacesEveryNameToken() {
        assertEquals("Mara hello Mara", ResidentLines.render("{name} hello {name}", "Mara"));
        assertEquals("No token here", ResidentLines.render("No token here", "Mara"));
        assertEquals("", ResidentLines.render("", "Mara"));
    }

    @Test void renderDoesNotReplaceAgainInsideTheInsertedName() {
        assertEquals("Hi {name}!", ResidentLines.render("Hi {name}!", "{name}"));
        assertEquals("{name}{name}", ResidentLines.render("{name}{name}", "{name}"));
    }

    @Test void renderRefusesANullName() {
        assertThrows(NullPointerException.class, () -> ResidentLines.render("Hi {name}", null));
    }

    @Test void renderIsCaseSensitiveAboutTheToken() {
        assertEquals("Hi {Name}", ResidentLines.render("Hi {Name}", "Mara"));
    }

    private static final ResidentLines LINES = new ResidentLines(
            List.of("Greeting even {name}.", "Greeting odd {name}."),
            List.of("About even.", "About odd."),
            List.of("Home even.", "Home odd."));

    @ParameterizedTest
    @CsvSource({"0,even", "1,odd", "2,even", "3,odd", "100,even", "101,odd", "-1,odd", "-2,even", "-3,odd",
            "2147483647,odd", "-2147483648,even"})
    void theVariantPicksByFloorModulo(int variant, String parity) {
        assertEquals(Optional.of("Greeting " + parity + " Mara."), LINES.greeting(variant, "Mara"));
        assertEquals(Optional.of("About " + parity + "."), LINES.about(variant, "Mara"));
        assertEquals(Optional.of("Home " + parity + "."), LINES.home(variant, "Mara"));
    }

    @Test void anEmptyListPicksNothingForAnyVariant() {
        for (int variant : new int[] {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertEquals(Optional.empty(), ResidentLines.NONE.greeting(variant, "Mara"));
            assertEquals(Optional.empty(), ResidentLines.NONE.about(variant, "Mara"));
            assertEquals(Optional.empty(), ResidentLines.NONE.home(variant, "Mara"));
        }
    }

    @Test void thePickedLineIsRenderedWithTheResidentsName() {
        assertEquals(Optional.of("Greeting even Ellis Ward."), LINES.greeting(0, "Ellis Ward"));
    }

    @Test void pickingFromOneListDoesNotTouchTheOthers() {
        ResidentLines onlyHome = new ResidentLines(List.of(), List.of(), List.of("Home A.", "Home B."));
        assertEquals(Optional.empty(), onlyHome.greeting(0, "Mara"));
        assertEquals(Optional.empty(), onlyHome.about(0, "Mara"));
        assertEquals(Optional.of("Home B."), onlyHome.home(1, "Mara"));
    }
}
