package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.command.PieceDiagnosis.Setting;
import com.prosperitysmp.npc.pieces.PieceSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PieceDiagnosisLineTest {
    @Test
    void aSettingInUseShowsItsIdAndDetail() {
        assertEquals("money.source: vault [in use] backed by the server economy",
                new Setting("money.source", "vault", true, "backed by the server economy").line());
    }

    @Test
    void aSettingNotAvailableSaysSo() {
        assertEquals("accounting.sink: ledgerx [not available] unknown",
                new Setting("accounting.sink", "ledgerx", false, "unknown").line());
    }

    @Test
    void aLabelIsAppendedAfterASemicolonOnlyWhenThereIsOne() {
        assertEquals("k: a [in use] d; label: Fancy", new Setting("k", "a", true, "d", "Fancy", "").line());
        assertEquals("k: a [in use] d", new Setting("k", "a", true, "d", "", "").line());
        assertEquals("k: a [in use] d", new Setting("k", "a", true, "d", null, "").line());
        assertEquals("k: a [in use] d", new Setting("k", "a", true, "d", "§\u0007", "").line());
    }

    @Test
    void aLabelIsCleanedAndCappedAtEightyCharacters() {
        String line = new Setting("k", "a", true, "d", "A§cB\u0007" + "x".repeat(100), "").line();
        assertEquals("k: a [in use] d; label: AcB" + "x".repeat(77), line);
    }

    @Test
    void noRestartLineWhenTheConfigIsNotReadOrIsEmptyOrSame() {
        assertNull(new Setting("k", "a", true, "d", "", null).restartLine());
        assertNull(new Setting("k", "a", true, "d", "", "").restartLine());
        assertNull(new Setting("k", "a", true, "d", "", "a").restartLine());
    }

    @Test
    void aDifferentConfiguredPieceAsksForARestart() {
        assertEquals("money.source: configured other; restart required",
                new Setting("money.source", "vault", true, "d", "", "other").restartLine());
    }

    @Test
    void theConfiguredIdIsMatchedExactlyIncludingCase() {
        assertEquals("k: configured A; restart required", new Setting("k", "a", true, "d", "", "A").restartLine());
    }

    @Test
    void theConfiguredIdInTheRestartLineIsCleaned() {
        assertEquals("k: configured bc; restart required", new Setting("k", "a", true, "d", "", "b§c").restartLine());
    }

    @Test
    void aNullTextCleansToEmpty() {
        assertEquals("", PieceDiagnosis.clean(null));
        assertEquals("", PieceDiagnosis.clean(null, 5));
    }

    @Test
    void controlAndSectionCharactersAreRemoved() {
        assertEquals("abc", PieceDiagnosis.clean("a§b\u0000c"));
        assertEquals("abc", PieceDiagnosis.clean("a\nb\tc"));
        assertEquals("ab", PieceDiagnosis.clean("§a§b"));
    }

    @Test
    void textIsCutToTheFieldLimit() {
        assertEquals(80, PieceDiagnosis.MAX_FIELD);
        assertEquals("y".repeat(80), PieceDiagnosis.clean("y".repeat(80)));
        assertEquals("y".repeat(80), PieceDiagnosis.clean("y".repeat(81)));
        assertEquals("yy", PieceDiagnosis.clean("yyyy", 2));
        assertEquals("", PieceDiagnosis.clean("yyyy", 0));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "\u0007§"})
    void nothingPrintableCleansToEmpty(String text) {
        assertEquals("", PieceDiagnosis.clean(text));
    }

    @Test
    void theCleanedLengthIsCountedAfterRemovingTheBadCharacters() {
        assertEquals("z".repeat(80), PieceDiagnosis.clean("§".repeat(50) + "z".repeat(80)));
    }

    @Test
    void theHeaderNamesTheApiVersionAndSettingsComeInOrder() {
        List<String> lines = PieceDiagnosis.lines(List.of(new Setting("a", "1", true, "x"), new Setting("b", "2", false, "y")), List.of());
        assertEquals(List.of("ProsperityNPC pieces (API 1.2)", "a: 1 [in use] x", "b: 2 [not available] y"), lines);
    }

    @Test
    void aRestartLineFollowsItsSettingImmediately() {
        List<String> lines = PieceDiagnosis.lines(List.of(new Setting("a", "1", true, "x", "", "9"), new Setting("b", "2", true, "y")), List.of());
        assertEquals(List.of("ProsperityNPC pieces (API 1.2)", "a: 1 [in use] x", "a: configured 9; restart required",
                "b: 2 [in use] y"), lines);
    }

    @Test
    void aMissingListingIsSkipped() {
        List<PieceSelector.Selection<?>> listings = new ArrayList<>();
        listings.add(null);
        assertEquals(1, PieceDiagnosis.lines(List.of(), listings).size());
    }

    @Test
    void exactlyThirtyLinesAreKeptWithoutANote() {
        List<Setting> settings = new ArrayList<>();
        for (int i = 0; i < 29; i++) settings.add(new Setting("k" + i, "v", true, "d"));
        List<String> lines = PieceDiagnosis.lines(settings, List.of());
        assertEquals(30, lines.size());
        assertEquals("k28: v [in use] d", lines.get(29));
    }

    @Test
    void moreThanThirtyLinesAreCutToTwentyNineAndANoteCountingTheRest() {
        List<Setting> settings = new ArrayList<>();
        for (int i = 0; i < 40; i++) settings.add(new Setting("k" + i, "v", true, "d"));
        List<String> lines = PieceDiagnosis.lines(settings, List.of());
        assertEquals(30, lines.size());
        assertEquals("k27: v [in use] d", lines.get(28));
        assertEquals("... 12 more line(s) not shown", lines.get(29));
    }
}
