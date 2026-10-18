package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemRepairPreviewTest {
    @Test void onlyTheDisplayedIncidentTypeAndCashPriceMayBeTaken() {
        var shown = new ItemRepairRuntime.Preview(7, IncidentType.PESTS, 2375, List.of(), "v1", null);
        var quote = new RepairQuote(7, IncidentType.PESTS, "a".repeat(64), 2375L);
        assertTrue(ItemRepairRuntime.matchesPreview(quote, shown));
        for (var changed : List.of(new RepairQuote(7, IncidentType.PESTS, "a".repeat(64), 2374L),
                new RepairQuote(7, IncidentType.PESTS, "a".repeat(64), 2376L),
                new RepairQuote(8, IncidentType.PESTS, "a".repeat(64), 2375L),
                new RepairQuote(7, IncidentType.PLUMBING, "a".repeat(64), 2375L)))
            assertFalse(ItemRepairRuntime.matchesPreview(changed, shown));
        assertFalse(ItemRepairRuntime.matchesPreview(null, shown));
        assertFalse(ItemRepairRuntime.matchesPreview(quote, null));
    }
}
