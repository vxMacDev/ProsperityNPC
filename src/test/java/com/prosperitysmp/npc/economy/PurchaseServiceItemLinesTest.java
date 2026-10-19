package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.formulas.IncidentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The item-repair receipt a player reads: the problem, the items, the time and the state, never a price. */
class PurchaseServiceItemLinesTest {
    private static final UUID RUN = new UUID(9, 9);

    /** PESTS: 16 paper at $1 and 8 baked potato at $2, above the $30 floor. */
    private static ItemRepairQuote quote() {
        return new ItemRepairQuote(new UUID(1, 1), new UUID(1, 2), new UUID(1, 3), 0, LocalDate.of(2026, 10, 13),
                new RepairQuote(41, IncidentType.PESTS, "a".repeat(64)), 2_000, 15_000, 3_000, 3_200, "v1",
                List.of(new ItemRepairQuote.Line("PAPER", 16, 100, new CustodyBytes(new byte[]{1}), 20),
                        new ItemRepairQuote.Line("BAKED_POTATO", 8, 200, new CustodyBytes(new byte[]{2}), 8)),
                List.of(new ItemRepairQuote.ReturnStack(0, 16, new CustodyBytes(new byte[]{0, 16})),
                        new ItemRepairQuote.ReturnStack(1, 8, new CustodyBytes(new byte[]{1, 8}))));
    }

    private static List<String> lines(ItemRepairRepository.State state, Instant updated) {
        return PurchaseService.itemLines(new ItemRepairRepository.Stored(quote(), state, RUN, null,
                Instant.parse("2026-10-14T00:00:00Z"), updated));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "APPLIED|Repair done.",
            "RETURN_ISSUED|The home changed, so the items were given back.",
            "CANCELLED|Cancelled. Nothing was taken.",
            "RECONCILED_NOT_COLLECTED|Checked by staff. Nothing was taken.",
            "AMBIGUOUS|Held for a staff check. Do not try again.",
            "INTENT|Still being checked."})
    void everyStateHasItsOwnLastLine(ItemRepairRepository.State state, String line) {
        List<String> receipt = lines(state, null);
        assertEquals(3, receipt.size());
        assertEquals(line, receipt.get(2));
    }

    @Test
    void theReceiptNamesTheProblemAndTheItemsAndSaysNoMoneyMoved() {
        assertEquals(List.of("Item repair for problem #41.", "Items: 16 paper, 8 baked potato. No money moved.", "Repair done."),
                lines(ItemRepairRepository.State.APPLIED, null));
    }

    @Test
    void theUpdateTimeIsShownInUtc() {
        assertEquals("Item repair for problem #41. Oct 14 03:05 UTC.",
                lines(ItemRepairRepository.State.APPLIED, Instant.parse("2026-10-14T03:05:59Z")).getFirst());
        assertEquals("Item repair for problem #41. Jan 2 23:00 UTC.",
                lines(ItemRepairRepository.State.INTENT, Instant.parse("2027-01-02T23:00:00Z")).getFirst());
    }

    @Test
    void noLineMentionsADollarAmount() {
        for (ItemRepairRepository.State state : ItemRepairRepository.State.values())
            for (String line : lines(state, Instant.parse("2026-10-14T03:05:00Z")))
                assertFalse(line.contains("$"), line);
    }
}
