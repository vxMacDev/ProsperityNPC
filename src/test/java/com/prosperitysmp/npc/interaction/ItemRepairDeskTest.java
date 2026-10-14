package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.ItemRepairRecipes;
import com.prosperitysmp.npc.economy.ItemRepairRuntime;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Off: the desk and the Bedrock form show the same text as without item repairs. On: one note line and a three-button choice. */
class ItemRepairDeskTest {
    private static final Instant AT = Instant.parse("2026-09-08T18:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    private static final OwnerDeskData.Issue PLUMBING = new OwnerDeskData.Issue(5, "PLUMBING", 3_500, AT);

    private static OwnerDeskData data(int residents, boolean issue) {
        var id = new UUID(0, 1);
        var property = new Property(id, id, "fixture", "test", "room", 4, 1000, 1200, id, 3, 65, true);
        var people = IntStream.range(0, residents).mapToObj(slot -> new OwnerDeskData.Resident(new UUID(0, slot + 2),
                "Resident " + slot, slot, "STANDARD", "NEUTRAL", 50)).toList();
        return new OwnerDeskData(property, 2, AT, people, issue ? List.of(PLUMBING) : List.of(), List.of(),
                new OwnerDeskData.Statement(DAY.minusDays(6), DAY, 0, 0, 0, 0));
    }
    private static ItemRepairRuntime.Preview available() {
        return new ItemRepairRuntime.Preview(5, IncidentType.PLUMBING, 3_500, List.of(new ItemRepairRecipes.Priced("COPPER_INGOT", 16, 200),
                new ItemRepairRecipes.Priced("IRON_INGOT", 4, 500)), "v1", null);
    }
    private static ItemRepairRuntime.Preview unavailable() {
        return new ItemRepairRuntime.Preview(5, IncidentType.PLUMBING, 3_500, List.of(), null, ItemRepairRecipes.Unavailable.PRICE_UNKNOWN);
    }

    @Test void offTheDeskTextIsUnchanged() {
        for (boolean issue : List.of(false, true)) for (boolean pause : List.of(false, true)) {
            var data = data(4, issue);
            assertEquals(OwnerDeskMenu.itemRepairLines(false, false), List.of());
            assertEquals(OwnerDeskMenu.issueLines(data), OwnerDeskMenu.issueLines(data, OwnerDeskMenu.itemRepairLines(false, false)));
            assertEquals(BedrockNpcUi.ownerBody(data, pause), BedrockNpcUi.ownerBody(data, pause, OwnerDeskMenu.itemRepairLines(false, false)));
        }
    }

    @Test void collectLineFollowsTheRunningRuntimeNotTheFlag() {
        UUID owner = new UUID(9, 9);
        java.util.function.Predicate<UUID> waiting = owner::equals;
        assertEquals(List.of(ItemRepairCopy.ITEMS_TO_COLLECT), OwnerDeskMenu.itemRepairLines(false, true, waiting, owner),
                "item-repairs off, runtime running for earlier hand-ins, items waiting: the line shows");
        assertEquals(List.of(), OwnerDeskMenu.itemRepairLines(false, true, waiting, new UUID(9, 8)), "nothing waiting for this owner");
        assertEquals(List.of(), OwnerDeskMenu.itemRepairLines(false, false, id -> { throw new AssertionError("asked while not running"); }, owner),
                "flag off with no runtime: no lines");
        assertEquals(List.of(ItemRepairCopy.DESK_NOTE), OwnerDeskMenu.itemRepairLines(true, false, id -> { throw new AssertionError(); }, owner));
        assertEquals(List.of(ItemRepairCopy.DESK_NOTE, ItemRepairCopy.ITEMS_TO_COLLECT), OwnerDeskMenu.itemRepairLines(true, true, waiting, owner));
    }

    @Test void onAddsTheNoteAndPendingReturnsAddTheCollectLine() {
        var data = data(1, true);
        assertEquals(List.of(ItemRepairCopy.DESK_NOTE), OwnerDeskMenu.itemRepairLines(true, false));
        assertEquals(List.of(ItemRepairCopy.ITEMS_TO_COLLECT), OwnerDeskMenu.itemRepairLines(false, true));
        assertEquals(List.of(ItemRepairCopy.DESK_NOTE, ItemRepairCopy.ITEMS_TO_COLLECT), OwnerDeskMenu.itemRepairLines(true, true));
        var lines = OwnerDeskMenu.issueLines(data, OwnerDeskMenu.itemRepairLines(true, true));
        assertEquals(OwnerDeskMenu.issueLines(data).size() + 2, lines.size());
        assertTrue(BedrockNpcUi.ownerBody(data, false, OwnerDeskMenu.itemRepairLines(true, true)).contains("Items to collect: /property repair returns"));
    }

    @Test void bedrockMainFormKeepsItsButtonCountInEveryOccupancy() {
        for (int residents = 0; residents <= 4; residents++) for (boolean issue : List.of(false, true)) for (boolean pause : List.of(false, true)) {
            var buttons = BedrockNpcUi.ownerButtons(data(residents, issue), pause);
            assertTrue(buttons.labels().size() <= 12);
            if (issue) assertTrue(buttons.slots().contains(22), "the repair button stays, at slot 22");
            var body = BedrockNpcUi.ownerBody(data(residents, issue), pause, OwnerDeskMenu.itemRepairLines(true, true));
            assertTrue(body.length() < 4096);
        }
    }

    /** Every optional desk line at once, at its longest: the Bedrock form still fits 4096 characters and 12 buttons. */
    @Test void worstCaseOwnerFormWithEveryFeatureStillFits() {
        var base = OwnerDeskMenuTest.housingData(false);
        var residents = base.residents().stream().map(r -> new OwnerDeskData.Resident(r.id(), "N".repeat(64), r.slot(), r.tier(),
                r.mood(), r.satisfaction())).toList();
        var issues = IntStream.range(0, 16).mapToObj(index -> new OwnerDeskData.Issue(Long.MAX_VALUE - index, "I".repeat(24),
                Long.MAX_VALUE, base.capturedAt())).toList();
        var view = base.housing().orElseThrow();
        var premium = new com.prosperitysmp.npc.market.PremiumHousingEligibility.Result(false,
                com.prosperitysmp.npc.market.PremiumHousingEligibility.Reason.PREMIUM_THRESHOLDS_NOT_MET,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                view.premium().operatorScore(), view.premium().propertyScore());
        var housing = new com.prosperitysmp.npc.market.OwnerHousingView(view.propertyId(), view.ownerId(), view.capturedAt(),
                view.windowFrom(), view.rating(), premium);
        var statement = new OwnerDeskData.Statement(base.statement().fromDay(), base.statement().throughDay(), Long.MAX_VALUE, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        var insurance = new OwnerDeskData.Insurance(2000, statement.throughDay().minusDays(6), statement.throughDay().plusDays(1),
                false, true, true);
        var data = new OwnerDeskData(base.property(), base.revision(), base.capturedAt(), residents, issues, base.upgrades(),
                statement, insurance, java.util.Optional.of(housing),
                java.util.Optional.of(new OwnerDeskData.PendingRent(Long.MAX_VALUE, LocalDate.MAX, 4)),
                java.util.Optional.of(new OwnerDeskData.Linked(5, Long.MAX_VALUE)),
                // Contracts and partnerships: the longest partner line (long name, top fee cut, rank perk, advertising at the top).
                java.util.Optional.of(new OwnerDeskData.Partner("P".repeat(64), LocalDate.MAX, 60, true, true)));
        String without = BedrockNpcUi.ownerBody(data, true);
        String body = BedrockNpcUi.ownerBody(data, true, OwnerDeskMenu.itemRepairLines(true, true));
        assertTrue(body.contains(data.pendingRent().orElseThrow().line()) && body.contains(data.linked().orElseThrow().line()),
                "the rent change and linked lines are in the body");
        assertTrue(body.contains(data.partner().orElseThrow().line()), "the partner line is in the body");
        assertEquals(without.length() + ("\n" + ItemRepairCopy.DESK_NOTE + "\n" + ItemRepairCopy.ITEMS_TO_COLLECT).length(), body.length(),
                "item repairs add exactly their two lines");
        assertTrue(body.length() <= 4096, () -> "Owner body length: " + body.length());
        assertTrue(BedrockNpcUi.ownerButtons(data, true).labels().size() <= 12);
        // The choice form at its longest: the largest cash price and four long item names at full quantity.
        var longest = new ItemRepairRuntime.Preview(Long.MAX_VALUE, IncidentType.BREAK_IN, 100_000_000, List.of(
                new ItemRepairRecipes.Priced("WAXED_WEATHERED_CUT_COPPER_STAIRS", 256, 100_000_000),
                new ItemRepairRecipes.Priced("WAXED_OXIDIZED_CUT_COPPER_STAIRS", 256, 100_000_000),
                new ItemRepairRecipes.Priced("POLISHED_BLACKSTONE_BRICK_STAIRS", 256, 100_000_000),
                new ItemRepairRecipes.Priced("INFESTED_CHISELED_STONE_BRICKS", 256, 100_000_000)), "v1", null);
        var form = BedrockNpcUi.repairChoiceForm(new OwnerDeskData.Issue(Long.MAX_VALUE, "BREAK_IN", 100_000_000, AT), longest);
        assertTrue(form.body().length() <= 4096 && form.title().length() <= 160 && form.labels().size() <= 12);
    }

    @Test void choiceFormMatchesTheCopyInJavaOrder() {
        var form = BedrockNpcUi.repairChoiceForm(PLUMBING, available());
        assertEquals("Repair #5", form.title());
        assertEquals(List.of("Pay $35.00 in cash", "Hand in items", "Back"), form.labels());
        assertEquals(List.of(30, 32, 34), BedrockNpcUi.REPAIR_CHOICE_SLOTS);
        assertEquals(String.join("\n", List.of(
                "Plumbing at this home. Pay $35.00 in cash, or hand in these items: 16 copper ingot, 4 iron ingot.",
                ItemRepairCopy.FROM_INVENTORY, ItemRepairCopy.PLAIN_ONLY, ItemRepairCopy.NO_FUNDING)), form.body());
        var closed = BedrockNpcUi.repairChoiceForm(PLUMBING, unavailable());
        assertEquals(List.of("Pay $35.00 in cash", "Hand in items (not available now)", "Back"), closed.labels());
        assertTrue(closed.body().startsWith("Plumbing at this home. Pay $35.00 in cash. Item repair is not available right now."));
        assertTrue(closed.body().contains(ItemRepairCopy.NO_FUNDING));
        assertEquals(OwnerDeskMenu.repairChoiceLines(PLUMBING, available()),
                List.of(form.body().split("\n")), "Java slot 13 and the Bedrock body read the same");
    }
}
