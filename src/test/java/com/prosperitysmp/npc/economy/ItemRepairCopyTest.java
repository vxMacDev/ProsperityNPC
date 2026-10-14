package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** The server's plain voice: no em or en dash, no emoji, no "Not X, not Y, but Z", short sentences. */
class ItemRepairCopyTest {
    private static final Pattern NOT_NOT_BUT = Pattern.compile("(?i)\\bnot\\b[^.]*,\\s*not\\b[^.]*\\bbut\\b");

    static List<String> everyLine() {
        var lines = new ArrayList<>(ItemRepairCopy.fixedLines());
        UUID id = new UUID(7, 7);
        lines.add(ItemRepairCopy.title(42));
        lines.add(ItemRepairCopy.body("Plumbing", 3_500, "16 copper ingot, 4 iron ingot"));
        lines.add(ItemRepairCopy.payCash(3_500));
        lines.add(ItemRepairCopy.missing("7 copper ingot"));
        lines.add(ItemRepairCopy.done("16 copper ingot, 4 iron ingot", id));
        lines.add(ItemRepairCopy.returned(16, "copper ingot"));
        lines.add(ItemRepairCopy.held(id));
        lines.add(ItemRepairCopy.staffAlert(id));
        lines.addAll(ItemRepairCopy.choiceLines("Plumbing", 3_500, "16 copper ingot, 4 iron ingot", true));
        lines.addAll(ItemRepairCopy.choiceLines("Plumbing", 3_500, "", false));
        return lines;
    }

    static List<String> problems(String line) {
        var out = new ArrayList<String>();
        if (line.indexOf('—') >= 0 || line.indexOf('–') >= 0) out.add("dash");
        if (!line.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f)) out.add("non-ASCII (emoji?)");
        if (NOT_NOT_BUT.matcher(line).find()) out.add("not-not-but");
        if (line.contains("!")) out.add("exclamation");
        return out;
    }

    @Test void everyItemRepairLineIsPlain() {
        for (String line : everyLine()) assertEquals(List.of(), problems(line), line);
    }

    @Test void theCheckCanFail() {
        assertEquals(List.of("dash", "non-ASCII (emoji?)"), problems("Repair done — nice"));
        assertEquals(List.of("not-not-but"), problems("Not cash, not credit, but items."));
        assertEquals(List.of("non-ASCII (emoji?)"), problems("Done 😀"));
    }

    @Test void choiceScreenTellsOwnersAboutRentFunding() {
        assertEquals("Item repairs do not add to this home's rent funding.", ItemRepairCopy.NO_FUNDING);
        assertTrue(ItemRepairCopy.choiceLines("Pests", 2_000, "16 paper", true).contains(ItemRepairCopy.NO_FUNDING));
        assertTrue(ItemRepairCopy.choiceLines("Pests", 2_000, "", false).contains(ItemRepairCopy.NO_FUNDING));
        assertEquals("Pests at this home. Pay $20.00 in cash, or hand in these items: 16 paper.",
                ItemRepairCopy.choiceLines("Pests", 2_000, "16 paper", true).getFirst());
        assertEquals("This repair has an item hand-in waiting for staff. No money was taken.", UtilityPaymentRuntime.ITEMS_PENDING_REPLY);
    }
}
