package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.economy.UtilityPrices;

import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.market.HousingDecisionPlan.HomeRank;
import com.prosperitysmp.npc.market.HousingDecisionPlan.RankStatus;
import com.prosperitysmp.npc.market.OwnerHousingView;
import com.prosperitysmp.npc.market.PremiumHousingEligibility;
import com.prosperitysmp.npc.market.PremiumHousingEligibility.Reason;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Pure presentation checks; these are synthetic records, not gameplay or client-render evidence. */
class OwnerDeskMenuTest {
    private static final UUID OWNER = new UUID(0, 302), WORLD = new UUID(0, 304);
    private static final LocalDate END = LocalDate.of(2026, 9, 7);

    @Test void configuredPricesAppearInBothOwnerMenusWithoutChangingDefaultLabels() {
        var view = data(OWNER, WORLD, "fixture", true, 1, List.of());
        var prices = com.prosperitysmp.npc.economy.PurchasePrices.load(java.util.Map.of(
                "maintenance-cents", "1750", "upgrades", java.util.Map.of(
                        "advertising", List.of("13700", "27000", "52000"),
                        "facilities", List.of("18500", "37000", "72000"))));
        assertEquals("Maintain condition for $10.00", OwnerDeskMenu.purchaseLabel(view, 28));
        var buttons = BedrockNpcUi.ownerButtons(view, false, false, prices);
        for (int slot : List.of(24, 26, 28)) {
            String label = OwnerDeskMenu.purchaseLabel(view, slot, prices);
            assertEquals(label, buttons.labels().get(buttons.slots().indexOf(slot)));
        }
        assertEquals("Maintain condition for $17.50", OwnerDeskMenu.purchaseLabel(view, 28, prices));
        assertTrue(OwnerDeskMenu.purchaseLabel(view, 24, prices).endsWith("$185.00"));
        assertTrue(OwnerDeskMenu.purchaseLabel(view, 26, prices).endsWith("$137.00"));
        assertEquals(BedrockNpcUi.ownerButtons(view), BedrockNpcUi.ownerButtons(view, false, false,
                com.prosperitysmp.npc.economy.PurchasePrices.DEFAULT));
    }

    @Test void evictionConfirmationAndSuggestedCommandUseTheSameConfiguredAmount() {
        var resident = resident(0);
        var prices = com.prosperitysmp.npc.economy.PurchasePrices.load(java.util.Map.of("eviction-cents", "2375"));
        assertEquals("End tenancy: " + resident.name() + " | $23.75", OwnerDeskMenu.evictionLabel(resident, prices));
        String command = OwnerDeskMenu.evictionCommand(resident, prices);
        assertEquals("/tenants evict " + resident.id() + " 23.75", command);
        assertEquals(prices.evictionCents(), com.prosperitysmp.npc.util.Money.parseDollarsToCents(command.substring(command.lastIndexOf(' ') + 1)));
        assertTrue(BedrockNpcUi.evictionBody(resident, prices).contains("for $23.75?"));
        assertEquals("End tenancy: " + resident.name() + " | $10.00", OwnerDeskMenu.evictionLabel(resident));
    }

    @Test void zeroBasedDatabaseSlotsDisplayRoomsOneThroughFourWithoutLosingEdges() {
        var first = resident(0);
        var last = resident(3);
        var rooms = OwnerDeskMenu.rooms(data(OWNER, WORLD, "fixture", true, 4, List.of(last, first)));
        assertEquals(List.of(1, 2, 3, 4), rooms.stream().map(OwnerDeskMenu.Room::number).toList());
        assertEquals(List.of(10, 12, 14, 16), rooms.stream().map(OwnerDeskMenu.Room::inventorySlot).toList());
        assertSame(first, rooms.getFirst().resident());
        assertSame(last, rooms.getLast().resident());
        assertNull(rooms.get(1).resident());
        assertNull(rooms.get(2).resident());
        assertThrows(UnsupportedOperationException.class, () -> rooms.clear());
    }

    @Test void everySupportedCapacityHasExactlyItsActualVacancies() {
        for (int capacity = 1; capacity <= 4; capacity++) {
            var rooms = OwnerDeskMenu.rooms(data(OWNER, WORLD, "fixture", true, capacity, List.of()));
            assertEquals(capacity, rooms.size());
            assertTrue(rooms.stream().allMatch(r -> r.resident() == null));
        }
    }
    @Test void tenancySelectionKeepsSnapshotIdentityAndNeverMapsControlsOrVacancies() {
        var first=resident(0);var last=resident(3);
        var view=data(OWNER,WORLD,"fixture",true,4,List.of(last,first));
        assertSame(first,OwnerDeskMenu.evictionTarget(view,10));
        assertSame(last,OwnerDeskMenu.evictionTarget(view,16));
        for(int slot:List.of(-1,11,12,14,22,24,32,34,40,42,45))assertNull(OwnerDeskMenu.evictionTarget(view,slot));
        assertEquals("End tenancy: "+first.name()+" | $10.00",OwnerDeskMenu.evictionLabel(first));
        assertEquals(1000,OwnerDeskMenu.EVICTION_CENTS);
    }

    @Test void statementUsesPaidTotalsAndCountsNotAskingRentOrBudget() {
        var lines = OwnerDeskMenu.statementLines(data(OWNER, WORLD, "fixture", true, 1, List.of()));
        assertEquals(List.of("2026-09-01 to 2026-09-07 | Economy days", "Income received: $3.21",
                "Expenses paid: $4.00", "Net settled: -$0.79", "Pending: 2 | Needs review: 1",
                "Your current ownership only."), lines);
    }

    @Test void configuredBillsAppendOneLineWithoutChangingTheSettledStatement() {
        var base = data(OWNER, WORLD, "fixture", true, 1, List.of());
        assertEquals(OwnerDeskMenu.statementLines(base), OwnerDeskMenu.statementAndLinkLines(base));
        var bills = new OwnerDeskData(base.property(), base.revision(), base.capturedAt(), base.residents(),
                base.issues(), base.upgrades(), base.statement(), base.insurance(), base.housing(), base.pendingRent(),
                base.linked(), base.partner(), Optional.of(new OwnerDeskData.Bills(2, 3, 1)));
        var lines = OwnerDeskMenu.statementAndLinkLines(bills);
        assertEquals(7, lines.size());
        assertEquals(OwnerDeskMenu.statementLines(base), lines.subList(0, 6));
        assertEquals("Bills: 2 paid | 3 unpaid | 1 expired", lines.getLast());
        assertTrue(BedrockNpcUi.ownerBody(bills).contains(lines.getLast()));
        assertFalse(BedrockNpcUi.ownerBody(base).contains("Bills:"));
    }

    @Test void missingServiceRecordDoesNotInventAnUnratedOrZeroScore() {
        assertEquals(List.of("Service record unavailable"),
                OwnerDeskMenu.housingLines(data(OWNER, WORLD, "fixture", true, 1, List.of())));
    }

    @Test void ratedServiceRecordKeepsActualDenominatorsAndExplainsFailedThresholds() {
        var lines=OwnerDeskMenu.housingLines(housingData(false));
        assertEquals(List.of("Owner-wide service record | 29-day window",
                "Aug 9 05:00:00 UTC to Sep 7 05:00:00 UTC",
                "Operator: 70 / 75 | Property: 79.80 / 100",
                "Assessed utility days: 7 / 7 | Bills assessed: 14",
                "Incidents assessed: 4 / 1", "Mature tenancies assessed: 1 / 1",
                "Premium: Score, condition or rent thresholds not met",
                "Requires condition >= 80, property >= 80, operator >= 70, rent <= 125% of reference.",
                "Evidence only; matching depends on feature flags, availability and caps."),lines);
        assertTrue(String.join("\n",lines).length()<=700);
        assertThrows(UnsupportedOperationException.class,lines::clear);
    }

    @Test void qualifyingEvidenceDoesNotPromiseAdmissionOrRescaleOperatorScore() {
        var lines=OwnerDeskMenu.housingLines(housingData(true));
        assertTrue(lines.contains("Operator: 70 / 75 | Property: 95.50 / 100"));
        assertTrue(lines.contains("Assessed utility days: 7 / 7 | Bills assessed: 14"));
        assertTrue(lines.contains("Incidents assessed: 4 / 1"));
        assertTrue(lines.contains("Mature tenancies assessed: 1 / 1"));
        assertTrue(lines.contains("Premium evidence qualifies"));
        assertTrue(lines.contains("Evidence only; matching depends on feature flags, availability and caps."));
        assertFalse(String.join("\n",lines).contains("thresholds not met"));
        assertTrue(String.join("\n",lines).length()<=700);
    }

    @Test void recordedUnratedAndReviewStatesStayDistinctFromUnavailableEvidence() {
        var base=data(OWNER,WORLD,"fixture",true,1,List.of());
        for(var status:List.of(RankStatus.UNRATED_HELD,RankStatus.SERVICE_REVIEW)) {
            var rating=new HomeRank(status,OptionalInt.empty(),OptionalDouble.empty(),Set.of());
            var premium=new PremiumHousingEligibility.Result(false,
                    status==RankStatus.SERVICE_REVIEW?Reason.SERVICE_REVIEW:Reason.INSUFFICIENT_UTILITY_DAYS,
                    0,0,0,0,OptionalInt.empty(),OptionalDouble.empty());
            var lines=OwnerDeskMenu.housingLines(withHousing(base,rating,premium));
            assertTrue(lines.contains(status==RankStatus.SERVICE_REVIEW?"Rating: Needs review":"Rating: Unrated"));
            assertFalse(lines.contains("Service record unavailable"));
            assertTrue(lines.stream().noneMatch(line->line.startsWith("Operator:")));
            assertTrue(String.join("\n",lines).length()<=700);
        }
    }

    @Test void insuranceDisplayKeepsExactPriceDatesAndOnePreventionPolicy() {
        var base=data(OWNER,WORLD,"fixture",true,4,List.of());
        assertEquals("Buy seven-day insurance for $20.00",OwnerDeskMenu.insuranceLabel(base));
        assertTrue(OwnerDeskMenu.insuranceLines(base).contains("Existing incidents are not covered."));
        assertTrue(OwnerDeskMenu.insuranceLines(base).contains("Prepaid and nonrefundable; no cash payout."));
        var used=new OwnerDeskData(base.property(),base.revision(),base.capturedAt(),base.residents(),base.issues(),
                base.upgrades(),base.statement(),new OwnerDeskData.Insurance(2000,END.minusDays(1),END.plusDays(6),true,true));
        assertEquals("Insurance | Prevention used",OwnerDeskMenu.insuranceLabel(used));
        assertFalse(used.insurance().canPurchase());
        assertTrue(OwnerDeskMenu.insuranceLines(used).contains("The one prevention has been used."));
        assertNull(OwnerDeskMenu.evictionTarget(base,30));
    }

    @Test void configuredInsurancePremiumReachesBothMenusFromTheSameDeskSnapshot() {
        var base = data(OWNER, WORLD, "fixture", true, 2, List.of());
        var priced = new OwnerDeskData(base.property(), base.revision(), base.capturedAt(), base.residents(), base.issues(),
                base.upgrades(), base.statement(), new OwnerDeskData.Insurance(1777, null, null, false, true));
        String label = "Buy seven-day insurance for $17.77";
        assertEquals(label, OwnerDeskMenu.insuranceLabel(priced));
        var buttons = BedrockNpcUi.ownerButtons(priced);
        assertEquals(label, buttons.labels().get(buttons.slots().indexOf(30)));
        assertTrue(BedrockNpcUi.ownerBody(priced).contains(label));
        assertTrue(OwnerDeskMenu.insuranceLines(priced).contains("Seven economy days; one new incident prevented."));
    }

    @Test void insuranceRenewalIsVisibleOnLastCoveredDayButNotWhenNextTermIsQueued() {
        var base=data(OWNER,WORLD,"fixture",true,2,List.of());
        var lastDay=new OwnerDeskData(base.property(),base.revision(),base.capturedAt(),base.residents(),base.issues(),
                base.upgrades(),base.statement(),new OwnerDeskData.Insurance(1600,END.minusDays(6),END.plusDays(1),true,true));
        assertTrue(lastDay.canPurchaseInsurance());
        assertEquals("Renew seven-day insurance for $16.00",OwnerDeskMenu.insuranceLabel(lastDay));
        assertTrue(OwnerDeskMenu.insuranceLines(lastDay).contains("Repurchase available from "+END+"."));
        var queued=new OwnerDeskData(base.property(),base.revision(),base.capturedAt(),base.residents(),base.issues(),
                base.upgrades(),base.statement(),new OwnerDeskData.Insurance(1600,END.plusDays(1),END.plusDays(8),false,true));
        assertFalse(queued.canPurchaseInsurance());
        assertEquals("Insurance | Starts "+END.plusDays(1),OwnerDeskMenu.insuranceLabel(queued));
    }

    @Test void utilityAuthorizationLimitUsesOccupancyAndAllInstalledLevelsNotRent() {
        var base = data(OWNER, WORLD, "fixture", true, 4, List.of(resident(0), resident(1)));
        var upgraded = new OwnerDeskData(base.property(), base.revision(), base.capturedAt(), base.residents(),
                base.issues(), List.of(new OwnerDeskData.Upgrade("FACILITIES", 2, true)), base.statement());
        assertEquals(250, OwnerDeskMenu.utilityLimit(base));
        assertEquals(350, OwnerDeskMenu.utilityLimit(upgraded));
    }

    @Test void allClickTypesAndLowerInventoryPathsFailClosedExceptAuthorizedTopLeft() {
        for (ClickType click : ClickType.values()) {
            assertEquals(click == ClickType.LEFT, OwnerDeskMenu.clickAllowed(false, true, true, click));
            assertFalse(OwnerDeskMenu.clickAllowed(true, true, true, click));
            assertFalse(OwnerDeskMenu.clickAllowed(false, false, true, click));
            assertFalse(OwnerDeskMenu.clickAllowed(false, true, false, click));
        }
        assertFalse(OwnerDeskMenu.clickAllowed(false, true, true, null));
    }

    @Test void delayedResultMustMatchExactOwnerWorldAndRegionAndRemainEnabled() {
        var ticket = new OwnerDeskSessions().open(OWNER, WORLD, "fixture", 0).orElseThrow();
        assertTrue(OwnerDeskController.matches(ticket, data(OWNER, WORLD, "fixture", true, 1, List.of())));
        assertFalse(OwnerDeskController.matches(ticket, null));
        assertFalse(OwnerDeskController.matches(ticket, data(UUID.randomUUID(), WORLD, "fixture", true, 1, List.of())));
        assertFalse(OwnerDeskController.matches(ticket, data(OWNER, UUID.randomUUID(), "fixture", true, 1, List.of())));
        assertFalse(OwnerDeskController.matches(ticket, data(OWNER, WORLD, "Fixture", true, 1, List.of())));
        assertFalse(OwnerDeskController.matches(ticket, data(OWNER, WORLD, "fixture", false, 1, List.of())));
    }

    @Test void currentUtilityRatesSupplyANewBillMaximumForBothMenus() {
        var view = data(OWNER, WORLD, "fixture", true, 4, List.of(resident(0), resident(1)));
        var rates = new UtilityPrices(200, 125, 100);
        assertEquals(450, OwnerDeskMenu.utilityLimit(view, rates));
        assertEquals("Pay utility up to $4.50", OwnerDeskMenu.utilityLabel(view, rates));
        var buttons = BedrockNpcUi.ownerButtons(view, false, false,
                com.prosperitysmp.npc.economy.PurchasePrices.DEFAULT, rates);
        assertEquals(OwnerDeskMenu.utilityLabel(view, rates), buttons.labels().get(buttons.slots().indexOf(42)));
    }

    @Test void savedUtilityBillKeepsItsMaximumAfterPricesAndInstalledLevelsChange() {
        var base = data(OWNER, WORLD, "fixture", true, 4, List.of(resident(0), resident(1)));
        var view = withUtility(base, OWNER);
        var changed = new UtilityPrices(5000, 100, 100);
        assertEquals(750, OwnerDeskMenu.utilityLimit(view, changed));
        assertEquals("Pay utility up to $7.50", OwnerDeskMenu.utilityLabel(view, changed));
        var buttons = BedrockNpcUi.ownerButtons(view, false, false,
                com.prosperitysmp.npc.economy.PurchasePrices.DEFAULT, changed);
        assertEquals("Pay utility up to $7.50", buttons.labels().get(buttons.slots().indexOf(42)));
    }

    @Test void billFromEarlierOwnershipIsShownForReview() {
        var view = withUtility(data(OWNER, WORLD, "fixture", true, 4, List.of()), new UUID(0, 999));
        assertFalse(view.canPayUtility());
        assertEquals("Utilities require ownership review", OwnerDeskMenu.utilityLabel(view, UtilityPrices.DEFAULT));
        var buttons = BedrockNpcUi.ownerButtons(view);
        assertEquals("Utilities require ownership review", buttons.labels().get(buttons.slots().indexOf(42)));
    }

    private static OwnerDeskData withUtility(OwnerDeskData base, UUID billOwner) {
        var bill = new UtilityBill(base.property().propertyId(), billOwner, 3, 2, END, 4, 2, 3,
                base.capturedAt(), "UTC", 4, new UtilityPrices(200, 125, 100));
        return new OwnerDeskData(base.property(), base.revision(), base.capturedAt(), base.residents(), base.issues(),
                base.upgrades(), base.statement(), base.insurance(), base.housing(), base.pendingRent(), base.linked(),
                base.partner(), base.bills(), java.util.Optional.of(bill));
    }

    private static OwnerDeskData.Resident resident(int slot) {
        return new OwnerDeskData.Resident(UUID.randomUUID(), "Fixture room " + slot, slot, "STANDARD", "NEUTRAL", 50);
    }
    static OwnerDeskData housingData(boolean qualifies) {
        var base=data(OWNER,WORLD,"fixture",true,4,List.of(resident(0),resident(1),resident(2),resident(3)));
        var p=base.property();
        var property=new Property(p.propertyId(),p.worldUuid(),p.regionId(),p.marketId(),p.classKey(),p.capacity(),
                p.referenceRentCents(),qualifies?1000:1188,p.ownerUuid(),p.ownershipEpoch(),qualifies?100:82,p.enabled());
        base=new OwnerDeskData(property,base.revision(),base.capturedAt(),base.residents(),base.issues(),
                List.of(new OwnerDeskData.Upgrade("FACILITIES",3,false)),base.statement(),base.insurance());
        // Fourteen bills over seven days, three of four incidents on time, one retained mature tenancy.
        int operator=PropertyFormulas.operatorScore(1.0,0.75,1.0);
        double score=PropertyFormulas.propertyScore(property.conditionScore(),PropertyFormulas.facilitiesScore(3),
                PropertyFormulas.rentFairness(property.askingRentCents(),property.referenceRentCents()),operator);
        var rating=new HomeRank(RankStatus.RATED,OptionalInt.of(operator),OptionalDouble.of(score),Set.of());
        var premium=new PremiumHousingEligibility.Result(qualifies,
                qualifies?Reason.ELIGIBLE:Reason.PREMIUM_THRESHOLDS_NOT_MET,14,7,4,1,
                OptionalInt.of(operator),OptionalDouble.of(score));
        return withHousing(base,rating,premium);
    }
    private static OwnerDeskData withHousing(OwnerDeskData base,HomeRank rating,PremiumHousingEligibility.Result premium) {
        var view=new OwnerHousingView(base.property().propertyId(),base.property().ownerUuid(),base.capturedAt(),
                base.capturedAt().minus(Duration.ofDays(29)),rating,premium);
        return new OwnerDeskData(base.property(),base.revision(),base.capturedAt(),base.residents(),base.issues(),
                base.upgrades(),base.statement(),base.insurance(),Optional.of(view));
    }
    private static OwnerDeskData data(UUID owner, UUID world, String region, boolean enabled,
                                      int capacity, List<OwnerDeskData.Resident> residents) {
        return new OwnerDeskData(new Property(new UUID(0, 1), world, region, "test", "room", capacity,
                1000, 1200, owner, 3, 65, enabled), 2, Instant.parse("2026-09-07T05:00:00Z"),
                residents, List.of(), List.of(), new OwnerDeskData.Statement(END.minusDays(6), END, 321, 400, 2, 1));
    }
}
