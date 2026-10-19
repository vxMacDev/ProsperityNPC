package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.town.ContractDefinition;
import com.prosperitysmp.npc.town.TownContract;
import com.prosperitysmp.npc.town.TownContractRepository;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The contract repository's plain reads and its guard branches, against real MariaDB. */
@Timeout(180)
class TownContractReadsIT extends ResidentsContractItSupport {

    @Test void findIsScopedToTheOwnerAndPeekIsNot() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run", NOW);
        TownContract stored = contracts.find(id, player).orElseThrow();
        assertEquals(stored, contracts.peek(id).orElseThrow());
        assertEquals("nico-coal-run", stored.definition().id());
        assertEquals(TownContract.Status.ACTIVE, stored.status());
        assertTrue(contracts.find(id, UUID.randomUUID()).isEmpty());
        assertTrue(contracts.find(UUID.randomUUID(), player).isEmpty());
        assertTrue(contracts.peek(UUID.randomUUID()).isEmpty());
    }

    @Test void aStoredContractReadsBackWithTheDefinitionItWasAcceptedUnder() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone", NOW.plusMillis(7));
        TownContract t = contracts.peek(id).orElseThrow();
        ContractDefinition d = t.definition();
        assertEquals(ContractDefinition.Kind.SUPPLY, d.kind());
        assertEquals("COBBLESTONE", d.material());
        assertEquals(64, d.quantity());
        assertEquals(1440, d.durationMinutes());
        assertEquals(MARA, d.giverId());
        assertEquals(MARA, d.recipientId());
        assertEquals(NOW.plusMillis(7), t.acceptedAt());
        assertEquals(NOW.plusMillis(7).plusSeconds(1440 * 60L), t.expiresAt());
        assertEquals(DAY, t.acceptedEconomyDay());
        assertEquals(1, t.daySlot());
    }

    @Test void currentForAPlayerWhoNeverHadAContractWritesNothing() throws SQLException {
        assertTrue(contracts.current(UUID.randomUUID(), NOW).isEmpty());
        assertEquals(0, count("npc_town_contract_players"));
        assertEquals(0, count("npc_town_contracts"));
    }

    @Test void currentReturnsTheOpenContractUntilItsDeadlineAndThenNothing() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run", NOW);
        Instant expires = contracts.peek(id).orElseThrow().expiresAt();
        assertEquals(id, contracts.current(player, NOW).orElseThrow().contractId());
        assertEquals(id, contracts.current(player, expires.minusMillis(1)).orElseThrow().contractId());
        assertTrue(contracts.current(player, expires).isEmpty());
        assertEquals(TownContract.Status.EXPIRED, contracts.peek(id).orElseThrow().status());
    }

    @Test void heldHandOversAreListedOldestFirstWithTheirSubject() throws SQLException {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        UUID late = hold(a, "mara-wall-stone", NOW.plusSeconds(20));
        UUID early = hold(b, "mara-wall-stone", NOW);
        UUID middle = hold(c, "mara-wall-stone", NOW.plusSeconds(10));
        assertEquals(3, contracts.heldCount());
        List<TownContractRepository.Held> held = contracts.listHeld(100);
        assertEquals(List.of(early, middle, late), held.stream().map(TownContractRepository.Held::contractId).toList());
        assertEquals(List.of(b, c, a), held.stream().map(TownContractRepository.Held::playerId).toList());
        TownContractRepository.Held first = held.getFirst();
        assertEquals(ContractDefinition.Kind.SUPPLY, first.kind());
        assertEquals("mara-wall-stone", first.definitionId());
        assertEquals(MARA, first.residentId());
    }

    @Test void theHeldListIsClampedToOneThroughOneHundredAndIsImmutable() throws SQLException {
        for (int i = 0; i < 3; i++) hold(UUID.randomUUID(), "mara-wall-stone", NOW.plusSeconds(i));
        assertEquals(1, contracts.listHeld(0).size());
        assertEquals(1, contracts.listHeld(-5).size());
        assertEquals(1, contracts.listHeld(1).size());
        assertEquals(2, contracts.listHeld(2).size());
        assertEquals(3, contracts.listHeld(1000).size());
        assertThrows(UnsupportedOperationException.class, () -> contracts.listHeld(10).clear());
    }

    @Test void withNothingHeldTheCountIsZeroAndTheListEmpty() throws SQLException {
        assertEquals(0, contracts.heldCount());
        assertEquals(List.of(), contracts.listHeld(100));
        UUID player = UUID.randomUUID();
        accept(player, "nico-coal-run", NOW);
        assertEquals(0, contracts.heldCount());
    }

    @Test void aResolvedHandOverIsNoLongerHeld() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = hold(player, "mara-wall-stone", NOW);
        assertEquals(1, contracts.heldCount());
        var resolved = contracts.resolve(id, TownContractRepository.Decision.VERIFIED_NOT_CONSUMED, "staff.test", "case-1",
                NOW.plusSeconds(60), DAY, null);
        assertEquals(Code.RESOLVED_NOT_CONSUMED, resolved.code());
        assertEquals(0, contracts.heldCount());
        assertEquals(List.of(), contracts.listHeld(10));
    }

    @Test void theDailySlotCountFollowsTheAcceptanceDayAndIgnoresPartnershipHandOvers() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(0, contracts.slotsUsed(player, DAY));
        accept(player, "nico-coal-run", NOW);
        assertEquals(1, contracts.slotsUsed(player, DAY));
        assertEquals(0, contracts.slotsUsed(player, DAY.plusDays(1)));
        assertEquals(0, contracts.slotsUsed(player, DAY.minusDays(1)));
        assertEquals(0, contracts.slotsUsed(UUID.randomUUID(), DAY));
    }

    @Test void thePartnerDayIsUsedOnlyByAStartedHandOverOnThatDay() throws SQLException {
        UUID owner = eligible(ELLIS);
        assertFalse(contracts.partnerDayUsed(owner, DAY));
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW, DAY).code());
        assertTrue(contracts.partnerDayUsed(owner, DAY));
        assertFalse(contracts.partnerDayUsed(owner, DAY.plusDays(1)));
        assertFalse(contracts.partnerDayUsed(owner, DAY.minusDays(1)));
        assertFalse(contracts.partnerDayUsed(UUID.randomUUID(), DAY));
    }

    @Test void anOrdinaryContractDoesNotUseThePartnerDay() throws SQLException {
        UUID player = UUID.randomUUID();
        accept(player, "nico-coal-run", NOW);
        assertFalse(contracts.partnerDayUsed(player, DAY));
    }

    @Test void seatsCountStartedPartnershipsAndStartsStillBeingHandedOver() throws SQLException {
        UUID first = eligible(ELLIS);
        assertEquals(Code.PARTNER_STARTED, partnerStart(first, ELLIS, NOW, DAY).code());
        assertEquals(1, contracts.seatsUsed(ELLIS, DAY));
        UUID second = eligible(ELLIS);
        assertEquals(Code.HANDOVER_STARTED, contracts.beginPartnership(true, second, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), NOW, DAY).code());
        assertEquals(2, contracts.seatsUsed(ELLIS, DAY));
        assertEquals(0, contracts.seatsUsed(MARA, DAY));
        assertEquals(0, contracts.seatsUsed(NICO, DAY));
    }

    @Test void aSeatIsHeldThroughTheLastDayOfTheTermAndFreedAfterIt() throws SQLException {
        UUID owner = eligible(ELLIS);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW, DAY).code());
        assertEquals(1, contracts.seatsUsed(ELLIS, DAY.plusDays(6)));
        assertEquals(0, contracts.seatsUsed(ELLIS, DAY.plusDays(7)));
    }

    @Test void aStartStillBeingHandedOverKeepsItsSeatOnAnyDay() throws SQLException {
        UUID owner = eligible(ELLIS);
        assertEquals(Code.HANDOVER_STARTED, contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), NOW, DAY).code());
        assertEquals(1, contracts.seatsUsed(ELLIS, DAY));
        assertEquals(1, contracts.seatsUsed(ELLIS, DAY.plusDays(40)));
    }

    @Test void aRenewalHandOverReservesNoSeat() throws SQLException {
        UUID owner = eligible(ELLIS);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW, DAY).code());
        LocalDate renewDay = DAY.plusDays(5);
        Instant renewAt = NOW.plusSeconds(5 * 86_400L);
        assertEquals(Code.HANDOVER_STARTED, contracts.beginPartnership(false, owner, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), renewAt, renewDay).code());
        assertEquals(1, contracts.seatsUsed(ELLIS, renewDay));
    }

    @Test void aHomeThatIsEnrolledCountsAndAPausedOrForeignOneDoesNot() throws SQLException {
        UUID owner = UUID.randomUUID();
        assertFalse(contracts.ownsEnrolledHome(owner));
        UUID property = home(owner);
        assertTrue(contracts.ownsEnrolledHome(owner));
        assertFalse(contracts.ownsEnrolledHome(UUID.randomUUID()));
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", property);
        assertFalse(contracts.ownsEnrolledHome(owner));
    }

    @Test void oneEnabledHomeIsEnoughEvenWithAPausedOne() throws SQLException {
        UUID owner = UUID.randomUUID();
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", home(owner));
        assertFalse(contracts.ownsEnrolledHome(owner));
        home(owner);
        assertTrue(contracts.ownsEnrolledHome(owner));
    }

    @Test void advertisingIsTopOnlyWhenEveryEnabledHomeIsAtLevelThree() throws SQLException {
        UUID owner = UUID.randomUUID();
        assertFalse(contracts.advertisingTop(owner), "no home at all");
        UUID one = home(owner);
        assertFalse(contracts.advertisingTop(owner), "a home with no advertising row");
        upgrade(one, "ADVERTISING", 2, null);
        assertFalse(contracts.advertisingTop(owner), "level two");
        execute("UPDATE npc_upgrades SET level=3 WHERE property_id=? AND upgrade_type='ADVERTISING'", one);
        assertTrue(contracts.advertisingTop(owner));
        UUID two = home(owner);
        assertFalse(contracts.advertisingTop(owner), "a second home without the upgrade");
        upgrade(two, "ADVERTISING", 3, null);
        assertTrue(contracts.advertisingTop(owner));
    }

    @Test void onlyTheAdvertisingUpgradeCountsAndOnlyForTheOwnersEnabledHomes() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID one = home(owner);
        upgrade(one, "FACILITIES", 3, null);
        assertFalse(contracts.advertisingTop(owner), "facilities level three is not advertising");
        upgrade(one, "ADVERTISING", 3, null);
        assertTrue(contracts.advertisingTop(owner));
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", home(owner));
        assertTrue(contracts.advertisingTop(owner), "a paused home without advertising is ignored");
        assertFalse(contracts.advertisingTop(UUID.randomUUID()));
    }

    @Test void anAdvertisingUpgradeSwitchedOffByAnIncidentIsNotAtTheTop() throws SQLException {
        UUID owner = UUID.randomUUID();
        upgrade(home(owner), "ADVERTISING", 3, 77L);
        assertFalse(contracts.advertisingTop(owner));
    }

    @Test void keptContractsCountFromTheSinceDayInclusive() throws SQLException {
        UUID player = UUID.randomUUID();
        kept(MARA, player, 1);
        LocalDate completed = DAY.minusDays(10);
        assertEquals(1, contracts.keptFor(MARA, player, completed));
        assertEquals(1, contracts.keptFor(MARA, player, completed.minusDays(5)));
        assertEquals(0, contracts.keptFor(MARA, player, completed.plusDays(1)));
    }

    @Test void keptContractsCountOnlyTheGiverAndPlayerAskedFor() throws SQLException {
        UUID player = UUID.randomUUID();
        kept(MARA, player, 2);
        assertEquals(2, contracts.keptFor(MARA, player, DAY.minusDays(60)));
        assertEquals(0, contracts.keptFor(ELLIS, player, DAY.minusDays(60)));
        assertEquals(0, contracts.keptFor(MARA, UUID.randomUUID(), DAY.minusDays(60)));
    }

    @Test void aCancelledOrOpenContractIsNotKept() throws SQLException {
        UUID player = UUID.randomUUID();
        seedContract(player, MARA, MARA, "SUPPLY", "CANCELLED", DAY.minusDays(3), 1, null);
        seedContract(player, MARA, MARA, "SUPPLY", "EXPIRED", DAY.minusDays(4), 1, null);
        seedContract(player, MARA, MARA, "SUPPLY", "ACTIVE", DAY.minusDays(5), 1, null);
        assertEquals(0, contracts.keptFor(MARA, player, DAY.minusDays(60)));
        seedContract(player, MARA, MARA, "SUPPLY", "COMPLETED", DAY.minusDays(6), 1, DAY.minusDays(6));
        assertEquals(1, contracts.keptFor(MARA, player, DAY.minusDays(60)));
    }

    @Test void aDeliveryIsKeptForItsGiverNotItsRecipient() throws SQLException {
        UUID player = UUID.randomUUID();
        seedContract(player, NICO, MARA, "DELIVER", "COMPLETED", DAY.minusDays(3), 1, DAY.minusDays(3));
        assertEquals(1, contracts.keptFor(NICO, player, DAY.minusDays(60)));
        assertEquals(0, contracts.keptFor(MARA, player, DAY.minusDays(60)));
    }

    @Test void aPartnershipHandOverIsNeverAKeptContract() throws SQLException {
        UUID player = UUID.randomUUID();
        seedContract(player, ELLIS, ELLIS, "PARTNERSHIP", "COMPLETED", DAY.minusDays(3), 0, DAY.minusDays(3));
        assertEquals(0, contracts.keptFor(ELLIS, player, DAY.minusDays(60)));
    }

    @Test void theKeptWindowIsThirtyEconomyDaysEndingToday() {
        assertEquals(LocalDate.of(2026, 9, 15), TownContractRepository.keptSince(DAY));
        assertEquals(LocalDate.of(2026, 12, 31), TownContractRepository.keptSince(LocalDate.of(2027, 1, 29)));
        assertEquals(DAY.minusDays(29), TownContractRepository.keptSince(DAY));
    }

    @Test void acceptRefusesAPartnershipDefinitionAndAnOutOfRangeDailyLimit() throws SQLException {
        ContractDefinition partnership = ContractDefinition.partnership(true, ELLIS, "IRON_INGOT", 16,
                RelationshipMemory.Stage.TRUSTED);
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> contracts.accept(partnership, player, NOW, DAY, 1));
        for (int perDay : new int[] {0, -1, 4, 100})
            assertThrows(IllegalArgumentException.class, () -> contracts.accept(contract("nico-coal-run"), player, NOW, DAY, perDay));
        assertEquals(0, count("npc_town_contracts"));
    }

    @Test void cancellingSomeoneElsesOrAnUnknownContractFindsNothing() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run", NOW);
        assertEquals(Code.NOT_FOUND, contracts.cancel(id, UUID.randomUUID(), NOW.plusSeconds(5)).code());
        assertEquals(Code.NOT_FOUND, contracts.cancel(UUID.randomUUID(), player, NOW.plusSeconds(5)).code());
        assertEquals(TownContract.Status.ACTIVE, contracts.peek(id).orElseThrow().status());
    }

    @Test void cancellingAtOrAfterTheDeadlineExpiresTheContractAtTheDeadline() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run", NOW);
        Instant expires = contracts.peek(id).orElseThrow().expiresAt();
        var result = contracts.cancel(id, player, expires);
        assertEquals(Code.EXPIRED, result.code());
        TownContract stored = contracts.peek(id).orElseThrow();
        assertEquals(TownContract.Status.EXPIRED, stored.status());
        assertEquals(expires, stored.closedAt());
    }

    @Test void cancellingOneMillisecondBeforeTheDeadlineStillCancels() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run", NOW);
        Instant late = contracts.peek(id).orElseThrow().expiresAt().minusMillis(1);
        assertEquals(Code.CANCELLED, contracts.cancel(id, player, late).code());
        assertEquals(late, contracts.peek(id).orElseThrow().closedAt());
    }

    @Test void cancellingBeforeTheContractWasAcceptedIsATimeRegression() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run", NOW);
        assertEquals(Code.TIME_REGRESSION, contracts.cancel(id, player, NOW.minusMillis(1)).code());
        assertEquals(TownContract.Status.ACTIVE, contracts.peek(id).orElseThrow().status());
    }

    @Test void acceptingBeforeAnEarlierAcceptanceIsATimeRegression() throws SQLException {
        UUID player = UUID.randomUUID();
        accept(player, "nico-coal-run", NOW);
        contracts.cancel(contracts.current(player, NOW).orElseThrow().contractId(), player, NOW.plusSeconds(1));
        assertEquals(Code.TIME_REGRESSION, contracts.accept(contract("mara-wall-stone"), player, NOW.minusMillis(1), DAY, 3).code());
        assertEquals(Code.TIME_REGRESSION, contracts.accept(contract("mara-wall-stone"), player, NOW.plusSeconds(2), DAY.minusDays(1), 3).code());
        assertEquals(Code.ACCEPTED, contracts.accept(contract("mara-wall-stone"), player, NOW.plusSeconds(2), DAY, 3).code());
    }

    @Test void aHandOverForAnUnknownOrCancelledContractIsRefused() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(Code.NOT_FOUND, contracts.beginHandover(UUID.randomUUID(), player, MARA, UUID.randomUUID(), NOW).code());
        UUID id = accept(player, "mara-wall-stone", NOW);
        assertEquals(Code.CANCELLED, contracts.cancel(id, player, NOW.plusSeconds(1)).code());
        assertEquals(Code.NOT_ACTIVE, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(2)).code());
    }

    @Test void aHandOverAtTheDeadlineExpiresItAndOneMillisecondEarlierStarts() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone", NOW);
        Instant expires = contracts.peek(id).orElseThrow().expiresAt();
        var early = contracts.beginHandover(id, player, MARA, UUID.randomUUID(), expires.minusMillis(1));
        assertEquals(Code.HANDOVER_STARTED, early.code());
        UUID other = UUID.randomUUID();
        UUID second = accept(other, "mara-wall-stone", NOW);
        assertEquals(Code.EXPIRED, contracts.beginHandover(second, other, MARA, UUID.randomUUID(), expires).code());
        assertEquals(TownContract.Status.EXPIRED, contracts.peek(second).orElseThrow().status());
    }

    @Test void aHandOverBeforeTheAcceptanceTimeIsATimeRegression() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone", NOW);
        assertEquals(Code.TIME_REGRESSION, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.minusMillis(1)).code());
        assertEquals(TownContract.Status.ACTIVE, contracts.peek(id).orElseThrow().status());
    }

    @Test void markingAnotherRunAmbiguousChangesNothing() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone", NOW);
        UUID run = UUID.randomUUID();
        contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(1));
        Optional<TownContract> wrongRun = contracts.markAmbiguous(id, player, UUID.randomUUID());
        assertEquals(TownContract.Status.HANDOVER_INTENT, wrongRun.orElseThrow().status());
        assertTrue(contracts.markAmbiguous(UUID.randomUUID(), player, run).isEmpty());
        assertThrows(SQLException.class, () -> contracts.markAmbiguous(id, UUID.randomUUID(), run),
                "a player with no contract guard is an invariant failure, not a quiet miss");
        assertEquals(TownContract.Status.AMBIGUOUS, contracts.markAmbiguous(id, player, run).orElseThrow().status());
        assertEquals(1, contracts.heldCount());
    }

    @Test void cancellingBeforeTheTakeForTheWrongRunLeavesTheHandOverOpen() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone", NOW);
        UUID run = UUID.randomUUID();
        contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(1));
        assertEquals(TownContract.Status.HANDOVER_INTENT, contracts.cancelBeforeTake(id, player, UUID.randomUUID()).orElseThrow().status());
        assertTrue(contracts.cancelBeforeTake(UUID.randomUUID(), player, run).isEmpty());
        assertThrows(SQLException.class, () -> contracts.cancelBeforeTake(id, UUID.randomUUID(), run));
        assertEquals(TownContract.Status.ACTIVE, contracts.cancelBeforeTake(id, player, run).orElseThrow().status());
        assertNull(contracts.peek(id).orElseThrow().runId());
    }

    @Test void anUnconfirmedBeginWithNoRowDoesNothing() throws SQLException {
        assertTrue(contracts.cancelUnconfirmedBegin(UUID.randomUUID(), UUID.randomUUID()).isEmpty());
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone", NOW);
        UUID run = UUID.randomUUID();
        contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(1));
        assertTrue(contracts.cancelUnconfirmedBegin(player, UUID.randomUUID()).isEmpty());
        assertTrue(contracts.cancelUnconfirmedBegin(UUID.randomUUID(), run).isEmpty());
        assertEquals(TownContract.Status.HANDOVER_INTENT, contracts.peek(id).orElseThrow().status());
        assertEquals(TownContract.Status.ACTIVE, contracts.cancelUnconfirmedBegin(player, run).orElseThrow().status());
    }

    @Test void recoveringStaleIntentsHoldsOnlyHandOversInFlightAndHonoursTheLimit() throws SQLException {
        for (int i = 0; i < 3; i++) {
            UUID player = UUID.randomUUID();
            UUID id = accept(player, "mara-wall-stone", NOW.plusSeconds(i));
            contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(10 + i));
        }
        accept(UUID.randomUUID(), "nico-coal-run", NOW);
        assertEquals(2, contracts.recoverStaleIntents(2).size());
        assertEquals(2, contracts.heldCount());
        assertEquals(1, contracts.recoverStaleIntents(100).size());
        assertEquals(0, contracts.recoverStaleIntents(100).size());
        assertEquals(3, contracts.heldCount());
    }
}
