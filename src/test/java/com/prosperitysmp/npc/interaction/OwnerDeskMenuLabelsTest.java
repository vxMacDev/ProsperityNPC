package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.ItemRepairRecipes;
import com.prosperitysmp.npc.economy.ItemRepairRuntime;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import java.util.Locale;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The owner desk's labels and lines, each branch with its exact text. Synthetic records, not client rendering. */
class OwnerDeskMenuLabelsTest {
    private static final UUID OWNER = new UUID(0, 11);
    private static final UUID WORLD = new UUID(0, 12);
    private static final LocalDate THROUGH = LocalDate.of(2026, 9, 7);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T05:00:00Z");
    private static final OwnerDeskData.Statement STATEMENT = new OwnerDeskData.Statement(THROUGH.minusDays(6), THROUGH, 321, 400, 2, 1);

    private static OwnerDeskData data(List<OwnerDeskData.Upgrade> upgrades, List<OwnerDeskData.Issue> issues,
                                      OwnerDeskData.Insurance insurance, Optional<OwnerDeskData.PendingRent> pending) {
        Property property = new Property(new UUID(0, 1), WORLD, "fixture-region", "test", "room", 2, 1000, 1200, OWNER, 3, 65, true);
        return new OwnerDeskData(property, 2, CAPTURED, List.of(), issues, upgrades, STATEMENT, insurance, Optional.empty(), pending);
    }

    private static OwnerDeskData data(List<OwnerDeskData.Upgrade> upgrades) {
        return data(upgrades, List.of(), new OwnerDeskData.Insurance(1400, null, null, false, true), Optional.empty());
    }

    private static OwnerDeskData insured(OwnerDeskData.Insurance insurance) {
        return data(List.of(), List.of(), insurance, Optional.empty());
    }

    private static OwnerDeskData.Upgrade up(String type, int level, boolean disabled) {
        return new OwnerDeskData.Upgrade(type, level, disabled);
    }

    @Test void thePauseCopyIsFixed() {
        assertEquals("Pause this property", OwnerDeskMenu.PAUSE_LABEL);
        assertEquals("Pause: leave the NPC economy", OwnerDeskMenu.PAUSE_CONFIRM);
        assertEquals("Resume this property", OwnerDeskMenu.RESUME_LABEL);
        assertEquals(List.of("Takes it out of the NPC economy until you resume.", "Tenants, incidents and history are kept.",
                "Asks you to confirm first."), OwnerDeskMenu.pauseLines());
    }

    @Test void thePauseBodiesNameTheRegion() {
        assertEquals("Pause harbor-7? NPC rent and bills stop until you resume. Tenants, incidents and history are kept.",
                OwnerDeskMenu.pauseBody("harbor-7"));
        assertEquals("harbor-7 is paused. Resume to put it back in the NPC economy. Tenants, incidents and history were kept. "
                + "You can also type /property resume.", OwnerDeskMenu.pausedBody("harbor-7"));
    }

    @Test void withoutTheRankFeatureAdvertisingJustSaysTheClickAuthorizes() {
        assertEquals(List.of("Click authorizes the displayed level and cost."),
                OwnerDeskMenu.advertisingLines(false, data(List.of(up("ADVERTISING", 3, false)))));
    }

    @Test void withTheRankFeatureAnUnboughtUpgradeIsNotActiveYet() {
        assertEquals(List.of("Advertising: not active yet",
                "Residents look at advertised homes first. Rent and score do not change.",
                "Click authorizes the displayed level and cost."), OwnerDeskMenu.advertisingLines(true, data(List.of())));
    }

    @ParameterizedTest @CsvSource({"1", "2", "3"})
    void withTheRankFeatureABoughtLevelIsMatchedEarlier(int level) {
        List<String> lines = OwnerDeskMenu.advertisingLines(true, data(List.of(up("ADVERTISING", level, false))));
        assertEquals("Advertising: matched earlier", lines.get(0));
        assertEquals(3, lines.size());
    }

    @Test void aSwitchedOffAdvertisingUpgradeSaysSoWhateverItsLevel() {
        List<String> lines = OwnerDeskMenu.advertisingLines(true, data(List.of(up("ADVERTISING", 2, true))));
        assertEquals("Advertising: switched off until repaired.", lines.get(0));
    }

    @Test void otherUpgradesNeverChangeTheAdvertisingLine() {
        List<String> lines = OwnerDeskMenu.advertisingLines(true, data(List.of(up("FACILITIES", 3, false), up("FACILITIES", 1, true))));
        assertEquals("Advertising: not active yet", lines.get(0));
    }

    @ParameterizedTest @EnumSource(UpgradeType.class)
    void anUpgradeNeverBoughtIsLevelOneNext(UpgradeType type) {
        assertEquals(1, OwnerDeskMenu.nextLevel(data(List.of()), type));
    }

    @Test void theNextLevelIsOneAboveTheHighestBoughtIncludingASwitchedOffOne() {
        assertEquals(2, OwnerDeskMenu.nextLevel(data(List.of(up("ADVERTISING", 1, false))), UpgradeType.ADVERTISING));
        assertEquals(3, OwnerDeskMenu.nextLevel(data(List.of(up("ADVERTISING", 2, true))), UpgradeType.ADVERTISING));
        assertEquals(4, OwnerDeskMenu.nextLevel(data(List.of(up("ADVERTISING", 3, false))), UpgradeType.ADVERTISING));
        assertEquals(1, OwnerDeskMenu.nextLevel(data(List.of(up("FACILITIES", 3, false))), UpgradeType.ADVERTISING));
    }

    @Test void theUpgradeTypeMatchIgnoresCase() {
        assertEquals(3, OwnerDeskMenu.nextLevel(data(List.of(up("advertising", 2, false))), UpgradeType.ADVERTISING));
    }

    @Test void theUpgradeButtonsNameTheNextLevelAndItsDefaultPrice() {
        OwnerDeskData none = data(List.of());
        assertEquals("Facilities level 1 for $150.00", OwnerDeskMenu.purchaseLabel(none, 24));
        assertEquals("Advertising level 1 for $100.00", OwnerDeskMenu.purchaseLabel(none, 26));
        OwnerDeskData two = data(List.of(up("FACILITIES", 1, false), up("ADVERTISING", 2, false)));
        assertEquals("Facilities level 2 for $350.00", OwnerDeskMenu.purchaseLabel(two, 24));
        assertEquals("Advertising level 3 for $500.00", OwnerDeskMenu.purchaseLabel(two, 26));
    }

    @Test void aMaximumLevelUpgradeButtonSaysSoWithoutAPrice() {
        OwnerDeskData top = data(List.of(up("FACILITIES", 3, false), up("ADVERTISING", 3, false)));
        assertEquals("Facilities at maximum level", OwnerDeskMenu.purchaseLabel(top, 24));
        assertEquals("Advertising at maximum level", OwnerDeskMenu.purchaseLabel(top, 26));
    }

    @Test void theMaintenanceButtonIgnoresTheUpgradeLevels() {
        assertEquals("Maintain condition for $10.00", OwnerDeskMenu.purchaseLabel(data(List.of(up("FACILITIES", 3, false))), 28));
    }

    @Test void aPendingRentChangeIsAppendedAsOneLine() {
        OwnerDeskData withPending = data(List.of(), List.of(), new OwnerDeskData.Insurance(1400, null, null, false, true),
                Optional.of(new OwnerDeskData.PendingRent(1200, LocalDate.of(2026, 10, 15), 2)));
        assertEquals(List.of("Asking rent: $10.00", "Rent changes to $12.00 on 2026-10-15. 2 residents were told."),
                OwnerDeskMenu.rentLines(withPending, List.of("Asking rent: $10.00")));
    }

    @Test void aSingleNoticeSaysOneResidentWasTold() {
        OwnerDeskData one = data(List.of(), List.of(), new OwnerDeskData.Insurance(1400, null, null, false, true),
                Optional.of(new OwnerDeskData.PendingRent(500, LocalDate.of(2026, 10, 15), 1)));
        assertEquals("Rent changes to $5.00 on 2026-10-15. 1 resident was told.",
                OwnerDeskMenu.rentLines(one, List.of()).get(0));
    }

    @Test void withNoPendingRentTheLinesAreReturnedAsTheyWere() {
        List<String> lines = List.of("a", "b");
        assertSame(lines, OwnerDeskMenu.rentLines(data(List.of()), lines));
    }

    @Test void noOpenIssueMeansNoOpenRepairAndTheNoIssueLine() {
        OwnerDeskData none = data(List.of());
        assertEquals("No open repair", OwnerDeskMenu.repairLabel(none));
        assertEquals(List.of("No unresolved service issue is recorded."), OwnerDeskMenu.issueLines(none));
    }

    @Test void theRepairButtonNamesTheFirstIssueAndItsQuote() {
        var first = new OwnerDeskData.Issue(12, "PLUMBING", 450, CAPTURED.plusSeconds(3600));
        var second = new OwnerDeskData.Issue(13, "BREAK_IN", 5000, CAPTURED.plusSeconds(7200));
        OwnerDeskData both = data(List.of(), List.of(first, second), new OwnerDeskData.Insurance(1400, null, null, false, true), Optional.empty());
        assertEquals("Repair #12 up to $4.50", OwnerDeskMenu.repairLabel(both));
        assertEquals(List.of("#12 Plumbing | Repair quote $4.50", "Due Sep 7 06:00:00 UTC",
                "#13 Break in | Repair quote $50.00", "Due Sep 7 07:00:00 UTC"), OwnerDeskMenu.issueLines(both));
    }

    @Test void itemRepairNotesAreAppendedToTheIssueLinesOnlyWhenThereAreSome() {
        OwnerDeskData none = data(List.of());
        assertEquals(OwnerDeskMenu.issueLines(none), OwnerDeskMenu.issueLines(none, List.of()));
        assertEquals(List.of("No unresolved service issue is recorded.", "note"), OwnerDeskMenu.issueLines(none, List.of("note")));
    }

    @ParameterizedTest @CsvSource({"false,false,0", "true,false,1", "false,true,1", "true,true,2"})
    void theItemRepairLinesFollowTheTwoSwitches(boolean on, boolean returns, int lines) {
        List<String> shown = OwnerDeskMenu.itemRepairLines(on, returns);
        assertEquals(lines, shown.size());
        if (on) assertEquals(ItemRepairCopy.DESK_NOTE, shown.get(0));
        if (returns) assertEquals(ItemRepairCopy.ITEMS_TO_COLLECT, shown.get(shown.size() - 1));
    }

    @Test void theWaitingReturnsAreAskedOnlyWhileTheRuntimeIsRunning() {
        AtomicInteger asked = new AtomicInteger();
        assertEquals(List.of(), OwnerDeskMenu.itemRepairLines(false, false, id -> {
            asked.incrementAndGet();
            return true;
        }, OWNER));
        assertEquals(0, asked.get());
        assertEquals(List.of(ItemRepairCopy.ITEMS_TO_COLLECT),
                OwnerDeskMenu.itemRepairLines(false, true, id -> {
                    asked.incrementAndGet();
                    assertEquals(OWNER, id);
                    return true;
                }, OWNER));
        assertEquals(1, asked.get());
        assertEquals(List.of(ItemRepairCopy.DESK_NOTE),
                OwnerDeskMenu.itemRepairLines(true, true, id -> false, OWNER));
    }

    @Test void theHandInButtonIsOfferedOnlyForAnAvailablePreview() {
        var ready = new ItemRepairRuntime.Preview(7, IncidentType.PESTS, 2375, List.of(new ItemRepairRecipes.Priced("PAPER", 16, 10)), "v1", null);
        var empty = new ItemRepairRuntime.Preview(7, IncidentType.PESTS, 2375, List.of(), "v1", null);
        var noVersion = new ItemRepairRuntime.Preview(7, IncidentType.PESTS, 2375, List.of(new ItemRepairRecipes.Priced("PAPER", 16, 10)), null, null);
        var unknown = new ItemRepairRuntime.Preview(7, IncidentType.PESTS, 2375, List.of(new ItemRepairRecipes.Priced("PAPER", 16, 10)), "v1",
                ItemRepairRecipes.Unavailable.PRICE_UNKNOWN);
        assertEquals(ItemRepairCopy.HAND_IN, OwnerDeskMenu.handInLabel(ready));
        assertEquals(ItemRepairCopy.HAND_IN_UNAVAILABLE, OwnerDeskMenu.handInLabel(empty));
        assertEquals(ItemRepairCopy.HAND_IN_UNAVAILABLE, OwnerDeskMenu.handInLabel(noVersion));
        assertEquals(ItemRepairCopy.HAND_IN_UNAVAILABLE, OwnerDeskMenu.handInLabel(unknown));
        assertEquals(ItemRepairCopy.HAND_IN_UNAVAILABLE, OwnerDeskMenu.handInLabel(null));
    }

    @Test void anUninsuredHomeOffersToBuyAndExplainsTheTerms() {
        OwnerDeskData open = insured(new OwnerDeskData.Insurance(1400, null, null, false, true));
        assertEquals("Buy seven-day insurance for $14.00", OwnerDeskMenu.insuranceLabel(open));
        assertEquals(List.of("Starts next economy day after verified payment.", "Seven economy days; one new incident prevented.",
                "Existing incidents are not covered.", "Prepaid and nonrefundable; no cash payout."), OwnerDeskMenu.insuranceLines(open));
    }

    @Test void aRunningTermIsCoveredAndNamesWhenItCanBeBoughtAgain() {
        LocalDate start = THROUGH.minusDays(2);
        OwnerDeskData covered = insured(new OwnerDeskData.Insurance(1400, start, start.plusDays(7), false, true));
        assertEquals("Insurance | Covered", OwnerDeskMenu.insuranceLabel(covered));
        assertEquals(List.of("Term: 2026-09-05 to 2026-09-11 | Economy days", "One prevention remains for new incidents.",
                "Repurchase available from 2026-09-11.", "Seven economy days; one new incident prevented.",
                "Existing incidents are not covered.", "Prepaid and nonrefundable; no cash payout."), OwnerDeskMenu.insuranceLines(covered));
    }

    @Test void aUsedPreventionIsShownInTheLabelAndTheLines() {
        LocalDate start = THROUGH.minusDays(2);
        OwnerDeskData used = insured(new OwnerDeskData.Insurance(1400, start, start.plusDays(7), true, true));
        assertEquals("Insurance | Prevention used", OwnerDeskMenu.insuranceLabel(used));
        assertEquals("The one prevention has been used.", OwnerDeskMenu.insuranceLines(used).get(1));
    }

    @Test void aTermThatHasNotStartedYetSaysWhenItStarts() {
        LocalDate start = THROUGH.plusDays(1);
        OwnerDeskData later = insured(new OwnerDeskData.Insurance(1400, start, start.plusDays(7), false, true));
        assertEquals("Insurance | Starts 2026-09-08", OwnerDeskMenu.insuranceLabel(later));
    }

    @Test void aTermEndingTomorrowCanBeRenewedNow() {
        LocalDate start = THROUGH.minusDays(6);
        OwnerDeskData renewing = insured(new OwnerDeskData.Insurance(1400, start, start.plusDays(7), false, true));
        assertEquals("Renew seven-day insurance for $14.00", OwnerDeskMenu.insuranceLabel(renewing));
        LocalDate oneDayLater = THROUGH.minusDays(5);
        assertEquals("Insurance | Covered",
                OwnerDeskMenu.insuranceLabel(insured(new OwnerDeskData.Insurance(1400, oneDayLater, oneDayLater.plusDays(7), false, true))));
    }

    @Test void aReservedNextTermBlocksAnotherPurchaseAndSaysSo() {
        LocalDate start = THROUGH.minusDays(6);
        OwnerDeskData reserved = insured(new OwnerDeskData.Insurance(1400, start, start.plusDays(7), false, true, true));
        assertEquals("Insurance | Covered", OwnerDeskMenu.insuranceLabel(reserved));
        assertEquals("The next seven-day term is already reserved.", OwnerDeskMenu.insuranceLines(reserved).get(2));
    }

    @Test void aPreviousOwnersTermDoesNotCoverTheNewOwnerWhoCanBuyAgain() {
        LocalDate start = THROUGH.minusDays(2);
        OwnerDeskData inherited = insured(new OwnerDeskData.Insurance(1400, start, start.plusDays(7), false, false));
        assertEquals("Buy seven-day insurance for $14.00", OwnerDeskMenu.insuranceLabel(inherited));
        assertEquals("Previous owner coverage does not transfer.", OwnerDeskMenu.insuranceLines(inherited).get(1));
    }

    @ParameterizedTest @EnumSource(IncidentType.class)
    void theIssueLineLowersTheTypeNameAndReplacesUnderscores(IncidentType type) {
        var issue = new OwnerDeskData.Issue(5, type.name(), 100, CAPTURED);
        OwnerDeskData d = data(List.of(), List.of(issue), new OwnerDeskData.Insurance(1400, null, null, false, true), Optional.empty());
        String expected = type.name().charAt(0) + type.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        assertEquals("#5 " + expected + " | Repair quote $1.00", OwnerDeskMenu.issueLines(d).get(0));
    }

    @Test void roomsMapOneToOneToTheCapacityAndTheirInventorySlotsStepByTwo() {
        var rooms = OwnerDeskMenu.rooms(data(List.of()));
        assertEquals(List.of(1, 2), rooms.stream().map(OwnerDeskMenu.Room::number).toList());
        assertEquals(List.of(10, 12), rooms.stream().map(OwnerDeskMenu.Room::inventorySlot).toList());
    }

    @ParameterizedTest @CsvSource({"false,true,true,true,true", "true,true,true,true,false", "false,false,true,true,false",
            "false,true,false,true,false", "false,true,true,false,false"})
    void aClickIsHonouredOnlyForTheSameActorsLeftClickInTheTopInventoryUnvetoed(boolean vetoed, boolean sameActor,
                                                                              boolean top, boolean left, boolean allowed) {
        var click = left ? ClickType.LEFT : ClickType.RIGHT;
        assertEquals(allowed, OwnerDeskMenu.clickAllowed(vetoed, sameActor, top, click));
    }

    @Test void everyOtherClickTypeIsRefused() {
        for (var click : ClickType.values()) {
            assertEquals(click == ClickType.LEFT,
                    OwnerDeskMenu.clickAllowed(false, true, true, click), click.name());
        }
    }
}
