package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.PurchasePrices;
import com.prosperitysmp.npc.economy.UtilityPrices;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** The Bedrock owner form: which button sits at which desk slot, what is dropped at the limit, and the body text. */
class BedrockOwnerFormTest {
    private static final UUID OWNER = new UUID(0, 11);
    private static final LocalDate THROUGH = LocalDate.of(2026, 9, 7);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T05:00:00Z");
    private static final OwnerDeskData.Statement STATEMENT = new OwnerDeskData.Statement(THROUGH.minusDays(6), THROUGH, 500, 125, 2, 1);
    private static final OwnerDeskData.Insurance INSURANCE = new OwnerDeskData.Insurance(1400, null, null, false, true);

    private static OwnerDeskData data(int capacity, int residents, int issues, Optional<OwnerDeskData.PendingRent> pending) {
        Property property = new Property(new UUID(0, 1), new UUID(0, 12), "fixture-region", "test", "room", capacity, 1000, 1200, OWNER, 3, 65, true);
        List<OwnerDeskData.Resident> people = IntStream.range(0, residents).mapToObj(slot ->
                new OwnerDeskData.Resident(new UUID(0, 100 + slot), "Resident " + slot, slot, "STANDARD", "NEUTRAL", 50)).toList();
        List<OwnerDeskData.Issue> open = IntStream.range(0, issues).mapToObj(i ->
                new OwnerDeskData.Issue(i + 1, "PLUMBING", 450, CAPTURED.plusSeconds(3600))).toList();
        return new OwnerDeskData(property, 2, CAPTURED, people, open, List.of(), STATEMENT, INSURANCE, Optional.empty(), pending);
    }

    private static OwnerDeskData data(int residents, int issues) {
        return data(4, residents, issues, Optional.empty());
    }

    private static String utility(OwnerDeskData d) { return OwnerDeskMenu.utilityLabel(d, UtilityPrices.DEFAULT); }

    private static List<String> purchases(OwnerDeskData d) {
        return List.of(OwnerDeskMenu.purchaseLabel(d, 24, PurchasePrices.DEFAULT), OwnerDeskMenu.purchaseLabel(d, 26, PurchasePrices.DEFAULT),
                OwnerDeskMenu.purchaseLabel(d, 28, PurchasePrices.DEFAULT));
    }

    @Test void aHomeWithNoIssueAndNoTenantsHasTheNineBasicButtonsInDeskOrder() {
        OwnerDeskData d = data(0, 0);
        var buttons = BedrockNpcUi.ownerButtons(d);
        List<String> labels = new ArrayList<>(List.of("Refresh", "Read full details in chat", "Close", utility(d)));
        labels.addAll(purchases(d));
        labels.add("Buy seven-day insurance for $14.00");
        labels.add("Auto-care settings");
        assertEquals(labels, buttons.labels());
        assertEquals(List.of(36, 38, 40, 42, 24, 26, 28, 30, 34), buttons.slots());
    }

    @Test void theBillsSwitchReplacesTheUtilityButtonInPlace() {
        OwnerDeskData d = data(0, 0);
        var buttons = BedrockNpcUi.ownerButtons(d, false, true);
        assertEquals("Payments and bills", buttons.labels().get(3));
        assertEquals(35, buttons.slots().get(3));
        assertEquals(9, buttons.labels().size());
        assertFalse(buttons.labels().contains(utility(d)));
        assertEquals(List.of(36, 38, 40, 35, 24, 26, 28, 30, 34), buttons.slots());
    }

    @Test void anOpenIssueAddsTheRepairButtonBeforeTheUpgradesAtSlotTwentyTwo() {
        OwnerDeskData d = data(0, 1);
        var buttons = BedrockNpcUi.ownerButtons(d);
        assertEquals(10, buttons.labels().size());
        assertEquals(List.of(36, 38, 40, 42, 22, 24, 26, 28, 30, 34), buttons.slots());
        assertEquals("Repair #1 up to $4.50", buttons.labels().get(4));
    }

    @Test void moreThanOneIssueStillAddsOnlyTheFirstRepairButton() {
        OwnerDeskData d = data(0, 3);
        var buttons = BedrockNpcUi.ownerButtons(d);
        assertEquals(10, buttons.labels().size());
        assertEquals("Repair #1 up to $4.50", buttons.labels().get(4));
    }

    @Test void thePauseButtonFollowsAutoCareAtSlotThirtyTwo() {
        OwnerDeskData d = data(0, 0);
        var buttons = BedrockNpcUi.ownerButtons(d, true);
        assertEquals(10, buttons.labels().size());
        assertEquals("Pause this property", buttons.labels().get(9));
        assertEquals(List.of(36, 38, 40, 42, 24, 26, 28, 30, 34, 32), buttons.slots());
    }

    @Test void eachTenantGetsAManageButtonAtItsRoomSlotAfterTheOthers() {
        OwnerDeskData d = data(2, 0);
        var buttons = BedrockNpcUi.ownerButtons(d);
        assertEquals(11, buttons.labels().size());
        assertEquals(List.of("Manage tenancy: Resident 0", "Manage tenancy: Resident 1"), buttons.labels().subList(9, 11));
        assertEquals(List.of(10, 12), buttons.slots().subList(9, 11));
    }

    @Test void aTenantInTheThirdRoomIsAtTheThirdRoomSlot() {
        Property property = new Property(new UUID(0, 1), new UUID(0, 12), "fixture-region", "test", "room", 4, 1000, 1200, OWNER, 3, 65, true);
        OwnerDeskData d = new OwnerDeskData(property, 2, CAPTURED,
                List.of(new OwnerDeskData.Resident(new UUID(0, 5), "Third Room", 2, "STANDARD", "NEUTRAL", 50)), List.of(), List.of(),
                STATEMENT, INSURANCE);
        var buttons = BedrockNpcUi.ownerButtons(d);
        assertEquals("Manage tenancy: Third Room", buttons.labels().getLast());
        assertEquals(14, buttons.slots().getLast());
    }

    @Test void atFourteenButtonsTheReadOnlyButtonsGoFirstAndTheFormStaysAtTwelve() {
        OwnerDeskData d = data(4, 1);
        var buttons = BedrockNpcUi.ownerButtons(d);
        assertEquals(12, buttons.labels().size());
        assertFalse(buttons.slots().contains(38), "Read full details in chat goes first");
        assertFalse(buttons.slots().contains(36), "Refresh goes second");
        assertTrue(buttons.slots().containsAll(List.of(40, 42, 22, 24, 26, 28, 30, 34, 10, 12, 14, 16)));
    }

    @Test void withPauseControlsTheCloseButtonGoesThirdBecauseTheFormHasItsOwn() {
        OwnerDeskData d = data(4, 1);
        var buttons = BedrockNpcUi.ownerButtons(d, true);
        assertEquals(12, buttons.labels().size());
        assertFalse(buttons.slots().contains(38));
        assertFalse(buttons.slots().contains(36));
        assertFalse(buttons.slots().contains(40), "the form's own close replaces Close");
        assertTrue(buttons.slots().contains(32), "the pause button always stays");
        assertTrue(buttons.slots().containsAll(List.of(42, 22, 24, 26, 28, 30, 34, 10, 12, 14, 16)));
    }

    @Test void everyButtonHasExactlyOneSlotAndNoSlotRepeats() {
        for (int residents = 0; residents <= 4; residents++) {
            for (int issues = 0; issues <= 2; issues++) {
                for (boolean pause : new boolean[] {false, true}) {
                    for (boolean bills : new boolean[] {false, true}) {
                        var buttons = BedrockNpcUi.ownerButtons(data(residents, issues), pause, bills);
                        String where = residents + "/" + issues + "/" + pause + "/" + bills;
                        assertEquals(buttons.labels().size(), buttons.slots().size(), where);
                        assertEquals(buttons.slots().size(), buttons.slots().stream().distinct().count(), where);
                        assertTrue(buttons.labels().size() <= 12, where);
                    }
                }
            }
        }
    }

    @Test void theButtonListsAreImmutableCopies() {
        var buttons = BedrockNpcUi.ownerButtons(data(0, 0));
        assertThrows(UnsupportedOperationException.class, () -> buttons.labels().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> buttons.slots().add(1));
    }

    private static List<String> statementLines() {
        return List.of("2026-09-01 to 2026-09-07 | Economy days", "Income received: $5.00", "Expenses paid: $1.25", "Net settled: $3.75",
                "Pending: 2 | Needs review: 1", "Your current ownership only.");
    }

    @Test void theBodyListsRoomsIssuesCoverStatementAndTheRentHint() {
        OwnerDeskData d = data(4, 1, 0, Optional.empty());
        List<String> expected = new ArrayList<>();
        expected.add("Residents: 1 / 4");
        expected.add("Condition: 65 / 100");
        expected.add("Room 1: Resident 0 | Neutral");
        expected.add("Open service issues: 0");
        expected.add("No unresolved service issue is recorded.");
        expected.add("Buy seven-day insurance for $14.00");
        expected.addAll(List.of("Starts next economy day after verified payment.", "Seven economy days; one new incident prevented.",
                "Existing incidents are not covered.", "Prepaid and nonrefundable; no cash payout."));
        expected.add("Service record unavailable");
        expected.addAll(statementLines());
        expected.add("Utility payment authorizes today's bill up to the button's maximum. A higher bill is refused.");
        expected.add("Repair buttons authorize only the named incident up to the stated maximum. "
                + "Upgrade and maintenance buttons show the maximum charge.");
        expected.add("Set asking rent with /property rent <dollars-per-day> while standing in this property.");
        assertEquals(String.join("\n", expected), BedrockNpcUi.ownerBody(d));
    }

    @Test void theBodyCountsOpenIssuesAndListsEachWithItsQuoteAndDeadline() {
        String body = BedrockNpcUi.ownerBody(data(0, 2));
        assertTrue(body.contains("Open service issues: 2\n#1 Plumbing | Repair quote $4.50\nDue Sep 7 06:00:00 UTC\n"
                + "#2 Plumbing | Repair quote $4.50\nDue Sep 7 06:00:00 UTC\n"), body);
    }

    @Test void pauseControlsAddTheAskingRentFirstAndThePauseNoteLast() {
        String body = BedrockNpcUi.ownerBody(data(4, 0, 0, Optional.empty()), true);
        assertTrue(body.contains("Asking rent: $12.00 / resident / day.\nSet asking rent with /property rent"), body);
        assertTrue(body.endsWith("Pause takes this property out of the NPC economy until you resume it."), body);
    }

    @Test void aPendingRentChangeIsListedBeforeTheRentHint() {
        OwnerDeskData d = data(4, 0, 0, Optional.of(new OwnerDeskData.PendingRent(1500, LocalDate.of(2026, 9, 10), 1)));
        String body = BedrockNpcUi.ownerBody(d);
        assertTrue(body.contains("Rent changes to $15.00 on 2026-09-10. 1 resident was told.\nSet asking rent with /property rent"), body);
    }

    @Test void itemRepairNotesJoinTheIssueLines() {
        String body = BedrockNpcUi.ownerBody(data(0, 0), false, List.of("Item note one", "Item note two"));
        assertTrue(body.contains("No unresolved service issue is recorded.\nItem note one\nItem note two\n"), body);
    }

    @Test void theBodyHasNoCarriageReturnsOrTrailingNewline() {
        String body = BedrockNpcUi.ownerBody(data(2, 1), true);
        assertFalse(body.contains("\r"));
        assertFalse(body.endsWith("\n"));
    }

    @Test void theEvictionBodyNamesTheTenantAndTheConfiguredPrice() {
        var resident = new OwnerDeskData.Resident(new UUID(0, 5), "Ada Lane", 0, "STANDARD", "NEUTRAL", 50);
        assertEquals("End Ada Lane's current tenancy for $10.00? Their identity and history are retained. "
                + "An unavailable tenancy is refused without a new charge.", BedrockNpcUi.evictionBody(resident, PurchasePrices.DEFAULT));
    }

    @Test void theRepairChoiceFormHasThreeButtonsAndTheJavaSlotsInOrder() {
        assertEquals(List.of(30, 32, 34), BedrockNpcUi.REPAIR_CHOICE_SLOTS);
        var issue = new OwnerDeskData.Issue(7, "PLUMBING", 450, CAPTURED);
        var form = BedrockNpcUi.repairChoiceForm(issue, null);
        assertEquals(3, form.labels().size());
        assertEquals(ItemRepairCopy.payCash(450), form.labels().get(0));
        assertEquals(ItemRepairCopy.HAND_IN_UNAVAILABLE, form.labels().get(1));
        assertEquals(ItemRepairCopy.BACK, form.labels().get(2));
        assertEquals(ItemRepairCopy.title(7), form.title());
    }
}
