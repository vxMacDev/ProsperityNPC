package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.formulas.IncidentType;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** The quote record re-checks the SQL rules, so a hand-in cheaper than cash cannot even be built. */
class ItemRepairQuoteTest {
    static final UUID ID = new UUID(1, 1), PROPERTY = new UUID(1, 2), PLAYER = new UUID(1, 3);
    static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    static final RepairQuote PESTS = new RepairQuote(7, IncidentType.PESTS, "a".repeat(64));

    static ItemRepairQuote.Line line(String material, int quantity, long unit, int before) {
        return new ItemRepairQuote.Line(material, quantity, unit, new CustodyBytes(material.getBytes()), before);
    }
    static ItemRepairQuote.ReturnStack stack(int line, int quantity) {
        return new ItemRepairQuote.ReturnStack(line, quantity, new CustodyBytes(new byte[]{(byte) line, (byte) quantity}));
    }
    /** PESTS ($20): 16 paper at $1 + 8 potato at $2 = $32, above the $30 floor at 150 percent. */
    static ItemRepairQuote valid() {
        return new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1",
                List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(1, 8)));
    }

    @Test void validQuoteBuilds() {
        var q = valid();
        assertEquals(List.of(16, 8), q.quantities());
        assertEquals(List.of(20, 8), q.beforeUnits());
        assertTrue(q.quoteSha256().matches("[a-f0-9]{64}"));
    }

    @Test void cheaperThanTheFloorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 2_992, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 174, 8)), List.of(stack(0, 16), stack(1, 8))));
        // The stated value must equal the lines' value.
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 3_300, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(1, 8))));
    }

    @Test void requiredMustBeTheExactCeiling() {
        for (long required : List.of(2_999L, 3_001L, 2_000L))
            assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                    required, 3_200, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(1, 8))));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 9_999,
                1_999, 3_200, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(1, 8))));
        // Cash must be the incident's cash price.
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_001, 15_000,
                3_002, 3_200, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(1, 8))));
    }

    @Test void duplicateMaterialsTooManyLinesAndBadQuantitiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 3_200, "v1", List.of(line("PAPER", 16, 100, 20), line("PAPER", 8, 200, 8)), List.of(stack(0, 16), stack(1, 8))));
        var five = new ArrayList<ItemRepairQuote.Line>();
        for (String m : List.of("A", "B", "C", "D", "E")) five.add(line(m, 1, 1_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 5_000, "v1", five, List.of(stack(0, 1))));
        assertThrows(IllegalArgumentException.class, () -> line("PAPER", 0, 100, 20));
        assertThrows(IllegalArgumentException.class, () -> line("PAPER", 257, 100, 300));
        assertThrows(IllegalArgumentException.class, () -> line("PAPER", 16, 100, 15));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 3_200, "v1", List.of(), List.of(stack(0, 1))));
    }

    @Test void returnPlanMustGiveBackExactlyWhatIsTaken() {
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 3_200, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(1, 7))));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 3_200, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(1, 8), stack(1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000,
                3_000, 3_200, "v1", List.of(line("PAPER", 16, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 16), stack(2, 8))));
    }

    @Test void hashChangesWhenAnyFieldChanges() {
        var base = valid();
        var variants = List.of(base,
                new ItemRepairQuote(new UUID(9, 9), PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, new UUID(9, 9), PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, new UUID(9, 9), 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 1, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY.plusDays(1), PESTS, 2_000, 15_000, 3_000, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, new RepairQuote(8, IncidentType.PESTS, "a".repeat(64)), 2_000, 15_000, 3_000, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, new RepairQuote(7, IncidentType.PESTS, "b".repeat(64)), 2_000, 15_000, 3_000, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 16_000, 3_200, 3_200, "v1", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v2", base.lines(), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1",
                        List.of(line("PAPER", 16, 100, 21), line("BAKED_POTATO", 8, 200, 8)), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_300, "v1",
                        List.of(line("PAPER", 17, 100, 20), line("BAKED_POTATO", 8, 200, 8)), List.of(stack(0, 17), stack(1, 8))),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_216, "v1",
                        List.of(line("PAPER", 16, 101, 20), line("BAKED_POTATO", 8, 200, 8)), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1",
                        List.of(new ItemRepairQuote.Line("PAPER", 16, 100, new CustodyBytes(new byte[]{9}), 20), line("BAKED_POTATO", 8, 200, 8)), base.returnPlan()),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1", base.lines(),
                        List.of(stack(0, 8), stack(0, 8), stack(1, 8))),
                new ItemRepairQuote(ID, PROPERTY, PLAYER, 0, DAY, PESTS, 2_000, 15_000, 3_000, 3_200, "v1", base.lines(),
                        List.of(new ItemRepairQuote.ReturnStack(0, 16, new CustodyBytes(new byte[]{7})), stack(1, 8))));
        var hashes = new HashSet<String>();
        for (var q : variants) hashes.add(q.quoteSha256());
        assertEquals(variants.size(), hashes.size(), "every field is bound by the hash");
        assertEquals(base.quoteSha256(), valid().quoteSha256(), "and it is stable");
    }
}
