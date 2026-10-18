package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.ItemRepairRecipes.Priced;
import com.prosperitysmp.npc.economy.ItemRepairRuntime.Preview;
import com.prosperitysmp.npc.formulas.IncidentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What the item-repair desk shows and when a fresh price list asks for more than the shown one. */
class ItemRepairPreviewRulesTest {
    private static final Priced PAPER = new Priced("PAPER", 16, 10);
    private static final Priced POTATO = new Priced("BAKED_POTATO", 8, 25);

    private static Preview preview(List<Priced> lines) {
        return new Preview(7, IncidentType.PESTS, 2_000, lines, "v1", null);
    }

    @ParameterizedTest
    @CsvSource({"PESTS,Pests", "PLUMBING,Plumbing", "BREAK_IN,Break in"})
    void theTypeLabelIsTheIncidentNameInSentenceCase(IncidentType type, String label) {
        assertEquals(label, new Preview(1, type, 1, List.of(), null, null).typeLabel());
    }

    @Test
    void aPreviewIsAvailableOnlyWithLinesAPriceVersionAndNoReason() {
        assertTrue(preview(List.of(PAPER)).available());
        assertFalse(preview(List.of()).available(), "no lines");
        assertFalse(new Preview(7, IncidentType.PESTS, 2_000, List.of(PAPER), null, null).available(), "no price version");
        assertFalse(new Preview(7, IncidentType.PESTS, 2_000, List.of(PAPER), "v1",
                ItemRepairRecipes.Unavailable.PRICE_UNKNOWN).available(), "a reason");
    }

    @Test
    void aPreviewCopiesItsLines() {
        List<Priced> lines = new ArrayList<>(List.of(PAPER));
        Preview shown = preview(lines);
        lines.add(POTATO);
        assertEquals(List.of(PAPER), shown.lines());
        assertThrows(UnsupportedOperationException.class, () -> shown.lines().add(POTATO));
    }

    @Test
    void theSameListOrFewerOfEachItemNeedsNoMore() {
        assertFalse(ItemRepairRuntime.needsMore(preview(List.of(PAPER, POTATO)), preview(List.of(PAPER, POTATO))));
        assertFalse(ItemRepairRuntime.needsMore(preview(List.of(new Priced("PAPER", 15, 10), POTATO)),
                preview(List.of(PAPER, POTATO))));
    }

    @Test
    void aPriceChangeAloneNeedsNoMoreItems() {
        assertFalse(ItemRepairRuntime.needsMore(preview(List.of(new Priced("PAPER", 16, 99))), preview(List.of(PAPER))));
    }

    @Test
    void oneMoreOfAnyItemNeedsMore() {
        assertTrue(ItemRepairRuntime.needsMore(preview(List.of(PAPER, new Priced("BAKED_POTATO", 9, 25))),
                preview(List.of(PAPER, POTATO))));
    }

    @Test
    void aDifferentItemOrOrderNeedsMore() {
        assertTrue(ItemRepairRuntime.needsMore(preview(List.of(new Priced("GLASS", 16, 10))), preview(List.of(PAPER))));
        assertTrue(ItemRepairRuntime.needsMore(preview(List.of(POTATO, PAPER)), preview(List.of(PAPER, POTATO))));
    }

    @Test
    void aLongerOrShorterListNeedsMore() {
        assertTrue(ItemRepairRuntime.needsMore(preview(List.of(PAPER, POTATO)), preview(List.of(PAPER))));
        assertTrue(ItemRepairRuntime.needsMore(preview(List.of(PAPER)), preview(List.of(PAPER, POTATO))));
        assertFalse(ItemRepairRuntime.needsMore(preview(List.of()), preview(List.of())));
    }
}
