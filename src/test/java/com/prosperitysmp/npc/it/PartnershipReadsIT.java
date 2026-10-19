package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.PartnerRateReader;
import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.PartnershipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Partnership reads, lapses, ends and the debit-time rate check, against real MariaDB with stored rows. */
@Timeout(180)
class PartnershipReadsIT extends ResidentsContractItSupport {

    private List<UUID> ownersOf(List<Partnership> rows) {
        return rows.stream().map(Partnership::ownerId).toList();
    }

    @Test void activeListIsNewestStartFirstAndSkipsEndedAndLapsedRows() throws SQLException {
        UUID oldest = UUID.randomUUID(), newest = UUID.randomUUID(), middle = UUID.randomUUID();
        UUID ended = UUID.randomUUID(), lapsed = UUID.randomUUID();
        seedPartnership(oldest, ELLIS, DAY.minusDays(2), DAY.plusDays(4), 40, 0, null);
        seedPartnership(newest, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        seedPartnership(middle, MARA, DAY.minusDays(1), DAY.plusDays(5), 25, 0, null);
        seedPartnership(ended, ELLIS, DAY, DAY.plusDays(6), 40, 0, "OWNER_ENDED");
        seedPartnership(lapsed, ELLIS, DAY.minusDays(10), DAY.minusDays(1), 40, 0, null);
        assertEquals(List.of(newest, middle, oldest), ownersOf(partnerships.listActive(null, DAY, 100)));
        assertEquals(List.of(newest, oldest), ownersOf(partnerships.listActive(ELLIS, DAY, 100)));
        assertEquals(List.of(middle), ownersOf(partnerships.listActive(MARA, DAY, 100)));
        assertEquals(List.of(), partnerships.listActive(NICO, DAY, 100));
    }

    @Test void aPartnershipIsListedOnItsLastDayAndNotOnTheNext() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertEquals(List.of(owner), ownersOf(partnerships.listActive(null, DAY.plusDays(6), 10)));
        assertEquals(List.of(), partnerships.listActive(null, DAY.plusDays(7), 10));
        assertEquals(1, partnerships.countActive(DAY.plusDays(6)));
        assertEquals(0, partnerships.countActive(DAY.plusDays(7)));
    }

    @Test void theActiveListIsClampedAndImmutable() throws SQLException {
        for (int i = 0; i < 3; i++) seedPartnership(UUID.randomUUID(), ELLIS, DAY.minusDays(i), DAY.plusDays(6), 40, 0, null);
        assertEquals(List.of(), partnerships.listActive(null, DAY, 0));
        assertEquals(List.of(), partnerships.listActive(null, DAY, -4));
        assertEquals(1, partnerships.listActive(null, DAY, 1).size());
        assertEquals(2, partnerships.listActive(null, DAY, 2).size());
        assertEquals(3, partnerships.listActive(null, DAY, 100_000).size());
        assertEquals(200, PartnershipRepository.MAX_LIST);
        assertThrows(UnsupportedOperationException.class, () -> partnerships.listActive(null, DAY, 10).clear());
    }

    @Test void partnershipsStartedTheSameDayListInIdOrder() throws SQLException {
        UUID first = seedPartnership(UUID.randomUUID(), ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        UUID second = seedPartnership(UUID.randomUUID(), ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        List<UUID> expected = Arrays.asList(first, second);
        expected.sort((a, b) -> Arrays.compareUnsigned(Uuids.toBytes(a), Uuids.toBytes(b)));
        assertEquals(expected, partnerships.listActive(null, DAY, 10).stream().map(Partnership::partnershipId).toList());
    }

    @Test void theActiveCountMatchesTheListForEveryDay() throws SQLException {
        seedPartnership(UUID.randomUUID(), ELLIS, DAY.minusDays(2), DAY.plusDays(4), 40, 0, null);
        seedPartnership(UUID.randomUUID(), ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        seedPartnership(UUID.randomUUID(), MARA, DAY.minusDays(1), DAY.plusDays(5), 25, 0, null);
        seedPartnership(UUID.randomUUID(), MARA, DAY, DAY.plusDays(6), 25, 0, "STAFF_ENDED");
        assertEquals(3, partnerships.countActive(DAY));
        assertEquals(3, partnerships.countActive(DAY.plusDays(4)));
        assertEquals(2, partnerships.countActive(DAY.plusDays(5)));
        assertEquals(1, partnerships.countActive(DAY.plusDays(6)));
        assertEquals(0, partnerships.countActive(DAY.plusDays(7)));
        for (int offset = 0; offset <= 7; offset++)
            assertEquals(partnerships.listActive(null, DAY.plusDays(offset), 100).size(), partnerships.countActive(DAY.plusDays(offset)));
    }

    @Test void aStoredPartnershipReadsBackWithEveryField() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID id = seedPartnership(owner, MARA, DAY.minusDays(1), DAY.plusDays(5), 25, 3, null);
        Partnership p = partnerships.listActive(MARA, DAY, 10).getFirst();
        assertEquals(id, p.partnershipId());
        assertEquals(owner, p.ownerId());
        assertEquals(MARA, p.residentId());
        assertEquals(DAY.minusDays(1), p.startedDay());
        assertEquals(DAY.plusDays(5), p.lastActiveDay());
        assertEquals(25, p.perks().feeDiscountPct());
        assertEquals(3, p.perks().rankBonusPoints());
        assertEquals(Partnership.Status.ACTIVE, p.status());
        assertNull(p.endReason());
        assertNull(p.endedAt());
        assertEquals(1, p.revision());
    }

    private Map<UUID, Integer> ranks(LocalDate day, Map<UUID, Integer> caps) throws SQLException {
        try (Connection c = database.getConnection()) { return PartnershipRepository.rankBonusByOwner(c, day, caps); }
    }

    @Test void theRankBonusIsTheLowerOfTheRowAndTheCurrentCapPerOwner() throws SQLException {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        seedPartnership(a, ELLIS, DAY.minusDays(2), DAY.plusDays(4), 10, 3, null);
        seedPartnership(b, MARA, DAY, DAY.plusDays(6), 10, 1, null);
        seedPartnership(c, NICO, DAY, DAY.plusDays(6), 20, 0, null);
        Map<UUID, Integer> caps = Map.of(ELLIS, 2, MARA, 4, NICO, 4);
        assertEquals(Map.of(a, 2, b, 1), ranks(DAY, caps));
    }

    @Test void aResidentMissingFromTheCapsOrWithACapOfZeroGivesNothing() throws SQLException {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        seedPartnership(a, ELLIS, DAY, DAY.plusDays(6), 10, 3, null);
        seedPartnership(b, MARA, DAY, DAY.plusDays(6), 10, 3, null);
        assertEquals(Map.of(a, 3), ranks(DAY, Map.of(ELLIS, 4)));
        assertEquals(Map.of(), ranks(DAY, Map.of(ELLIS, 0, MARA, 0)));
        assertEquals(Map.of(), ranks(DAY, Map.of(NICO, 4)));
    }

    @Test void noCapsMeansAnEmptyMapWithoutReadingAnything() throws SQLException {
        seedPartnership(UUID.randomUUID(), ELLIS, DAY, DAY.plusDays(6), 10, 3, null);
        assertEquals(Map.of(), ranks(DAY, Map.of()));
    }

    @Test void theRankBonusCoversOnlyTheStartedAndNotYetLapsedDays() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY.plusDays(3), DAY.plusDays(9), 10, 3, null);
        Map<UUID, Integer> caps = Map.of(ELLIS, 4);
        assertEquals(Map.of(), ranks(DAY.plusDays(2), caps));
        assertEquals(Map.of(owner, 3), ranks(DAY.plusDays(3), caps));
        assertEquals(Map.of(owner, 3), ranks(DAY.plusDays(9), caps));
        assertEquals(Map.of(), ranks(DAY.plusDays(10), caps));
    }

    @Test void anEndedPartnershipGivesNoRankBonus() throws SQLException {
        seedPartnership(UUID.randomUUID(), ELLIS, DAY, DAY.plusDays(6), 10, 3, "OWNER_ENDED");
        assertEquals(Map.of(), ranks(DAY, Map.of(ELLIS, 4)));
    }

    @Test void theRankMapIsImmutableAndRefusesMissingArguments() throws SQLException {
        seedPartnership(UUID.randomUUID(), ELLIS, DAY, DAY.plusDays(6), 10, 3, null);
        assertThrows(UnsupportedOperationException.class, () -> ranks(DAY, Map.of(ELLIS, 4)).clear());
        try (Connection c = database.getConnection()) {
            assertThrows(NullPointerException.class, () -> PartnershipRepository.rankBonusByOwner(null, DAY, Map.of()));
            assertThrows(NullPointerException.class, () -> PartnershipRepository.rankBonusByOwner(c, null, Map.of()));
            assertThrows(NullPointerException.class, () -> PartnershipRepository.rankBonusByOwner(c, DAY, null));
        }
    }

    @Test void theDebitReadReturnsTheActiveRowAndNothingForEndedOrAbsentOwners() throws SQLException {
        UUID owner = UUID.randomUUID(), ender = UUID.randomUUID();
        UUID id = seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        seedPartnership(ender, ELLIS, DAY, DAY.plusDays(6), 40, 0, "STAFF_ENDED");
        try (Connection c = database.getConnection()) {
            assertEquals(id, PartnershipRepository.activeForDebit(c, owner).orElseThrow().partnershipId());
            assertTrue(PartnershipRepository.activeForDebit(c, ender).isEmpty());
            assertTrue(PartnershipRepository.activeForDebit(c, UUID.randomUUID()).isEmpty());
        }
    }

    @Test void theDebitReadDoesNotLookAtTheDayAndWritesNoLapse() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY.minusDays(20), DAY.minusDays(14), 40, 0, null);
        try (Connection c = database.getConnection()) {
            Partnership p = PartnershipRepository.activeForDebit(c, owner).orElseThrow();
            assertEquals(Partnership.Status.ACTIVE, p.status());
            assertFalse(p.activeOn(DAY), "the caller decides from the day");
        }
        assertEquals("ACTIVE", scalar("SELECT status FROM npc_partnerships WHERE owner_id=?", owner));
    }

    @Test void readingTheCurrentPartnershipAfterItsLastDayLapsesItOnce() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY.minusDays(7), DAY.minusDays(1), 40, 0, null);
        assertTrue(partnerships.current(owner, DAY, NOW).isEmpty());
        assertEquals("ENDED", scalar("SELECT status FROM npc_partnerships WHERE owner_id=?", owner));
        assertEquals("LAPSED", scalar("SELECT end_reason FROM npc_partnerships WHERE owner_id=?", owner));
        assertEquals(String.valueOf(NOW.toEpochMilli()), scalar("SELECT ended_at_millis FROM npc_partnerships WHERE owner_id=?", owner));
        assertEquals("2", scalar("SELECT revision FROM npc_partnerships WHERE owner_id=?", owner));
        assertTrue(partnerships.current(owner, DAY, NOW.plusSeconds(60)).isEmpty());
        assertEquals(String.valueOf(NOW.toEpochMilli()), scalar("SELECT ended_at_millis FROM npc_partnerships WHERE owner_id=?", owner));
    }

    @Test void theCurrentPartnershipIsStillThereOnItsLastDay() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY.minusDays(6), DAY, 40, 0, null);
        assertEquals(owner, partnerships.current(owner, DAY, NOW).orElseThrow().ownerId());
        assertEquals("ACTIVE", scalar("SELECT status FROM npc_partnerships WHERE owner_id=?", owner));
    }

    @Test void endingRecordsTheReasonTheTimeAndAnotherRevision() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        Partnership ended = partnerships.end(owner, Partnership.EndReason.STAFF_ENDED, NOW.plusSeconds(30), DAY).orElseThrow();
        assertEquals(Partnership.Status.ENDED, ended.status());
        assertEquals(Partnership.EndReason.STAFF_ENDED, ended.endReason());
        assertEquals(NOW.plusSeconds(30), ended.endedAt());
        assertEquals(2, ended.revision());
        assertEquals("STAFF_ENDED", scalar("SELECT end_reason FROM npc_partnerships WHERE owner_id=?", owner));
        assertTrue(partnerships.current(owner, DAY, NOW).isEmpty());
        assertTrue(partnerships.end(owner, Partnership.EndReason.OWNER_ENDED, NOW.plusSeconds(60), DAY).isEmpty());
    }

    @Test void endingForALapseIsRefusedAndEndingWithNothingToEndFindsNothing() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertThrows(IllegalArgumentException.class, () -> partnerships.end(owner, Partnership.EndReason.LAPSED, NOW, DAY));
        assertEquals("ACTIVE", scalar("SELECT status FROM npc_partnerships WHERE owner_id=?", owner));
        assertTrue(partnerships.end(UUID.randomUUID(), Partnership.EndReason.OWNER_ENDED, NOW, DAY).isEmpty());
    }

    @Test void endingAPartnershipPastItsLastDayRecordsALapseNotTheRequestedReason() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY.minusDays(7), DAY.minusDays(1), 40, 0, null);
        assertTrue(partnerships.end(owner, Partnership.EndReason.OWNER_ENDED, NOW, DAY).isEmpty());
        assertEquals("LAPSED", scalar("SELECT end_reason FROM npc_partnerships WHERE owner_id=?", owner));
    }

    private PartnerRateReader reader(boolean on) {
        return new PartnerRateReader(partnerships, () -> ON, () -> on, () -> NOW);
    }

    private boolean grants(PartnerRateReader reader, UUID owner, UUID resident, LocalDate day, int pct) throws SQLException {
        try (Connection c = database.getConnection()) { return reader.stillGrants(c, owner, resident, day, pct); }
    }

    @Test void theDebitCheckHoldsForTheStoredPerkOnAnyDayInsideTheTerm() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertTrue(grants(reader(true), owner, ELLIS, DAY, 40));
        assertTrue(grants(reader(true), owner, ELLIS, DAY.plusDays(6), 40));
        assertFalse(grants(reader(true), owner, ELLIS, DAY.plusDays(7), 40));
        assertFalse(grants(reader(true), owner, ELLIS, DAY.minusDays(1), 40));
    }

    @Test void theDebitCheckIsAtMostTheEffectivePerk() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertTrue(grants(reader(true), owner, ELLIS, DAY, 1));
        assertTrue(grants(reader(true), owner, ELLIS, DAY, 39));
        assertFalse(grants(reader(true), owner, ELLIS, DAY, 41));
    }

    @Test void aSnapshotLowerThanTheConfigLimitsTheDebitCheck() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 25, 0, null);
        assertTrue(grants(reader(true), owner, ELLIS, DAY, 25));
        assertFalse(grants(reader(true), owner, ELLIS, DAY, 26));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 61, 100, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void aPercentOutsideOneToSixtyNeverGrants(int pct) throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertFalse(grants(reader(true), owner, ELLIS, DAY, pct));
    }

    @Test void thePartnershipsSwitchBeingOffNeverGrants() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertFalse(grants(reader(false), owner, ELLIS, DAY, 40));
    }

    @Test void aPartnershipWithAnotherResidentOrAnEndedOneNeverGrants() throws SQLException {
        UUID owner = UUID.randomUUID(), ender = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        seedPartnership(ender, ELLIS, DAY, DAY.plusDays(6), 40, 0, "OWNER_ENDED");
        assertFalse(grants(reader(true), owner, MARA, DAY, 25));
        assertFalse(grants(reader(true), ender, ELLIS, DAY, 40));
        assertFalse(grants(reader(true), UUID.randomUUID(), ELLIS, DAY, 40));
    }

    @Test void aTermNotYetStartedNeverGrants() throws SQLException {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY.plusDays(2), DAY.plusDays(8), 40, 0, null);
        assertFalse(grants(reader(true), owner, ELLIS, DAY, 40));
        assertFalse(grants(reader(true), owner, ELLIS, DAY.plusDays(1), 40));
        assertTrue(grants(reader(true), owner, ELLIS, DAY.plusDays(2), 40));
    }

    @Test void theDebitCheckRefusesMissingArguments() throws SQLException {
        try (Connection c = database.getConnection()) {
            PartnerRateReader reader = reader(true);
            assertThrows(NullPointerException.class, () -> reader.stillGrants(null, UUID.randomUUID(), ELLIS, DAY, 10));
            assertThrows(NullPointerException.class, () -> reader.stillGrants(c, null, ELLIS, DAY, 10));
            assertThrows(NullPointerException.class, () -> reader.stillGrants(c, UUID.randomUUID(), null, DAY, 10));
            assertThrows(NullPointerException.class, () -> reader.stillGrants(c, UUID.randomUUID(), ELLIS, null, 10));
        }
    }

    @Test void theFeeDiscountReadIsTheStoredPerkWhileTheTermRunsAndZeroAfterIt() throws Exception {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertEquals(40, reader(true).feeDiscountPct(owner, ELLIS, DAY));
        assertEquals(40, reader(true).feeDiscountPct(owner, ELLIS, DAY.plusDays(6)));
        assertEquals(0, reader(true).feeDiscountPct(owner, ELLIS, DAY.plusDays(7)));
        assertEquals("LAPSED", scalar("SELECT end_reason FROM npc_partnerships WHERE owner_id=?", owner));
    }

    @Test void theFeeDiscountReadIsZeroForAnotherResidentOrWithPartnershipsOff() throws Exception {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        assertEquals(0, reader(true).feeDiscountPct(owner, MARA, DAY));
        assertEquals(0, reader(false).feeDiscountPct(owner, ELLIS, DAY));
        assertFalse(reader(false).enabled());
        assertTrue(reader(true).enabled());
    }

    @Test void aCatalogueWithPartnershipsOffGivesNoPerkEvenWithAStoredPartnership() throws Exception {
        UUID owner = UUID.randomUUID();
        seedPartnership(owner, ELLIS, DAY, DAY.plusDays(6), 40, 0, null);
        ContractCatalogue off = ContractCatalogue.load(Map.of("town.partnerships.enabled", false)::get, CAST, name -> true);
        var reader = new PartnerRateReader(partnerships, () -> off, () -> true, () -> NOW);
        assertEquals(0, reader.feeDiscountPct(owner, ELLIS, DAY));
    }

    @Test void theReaderRefusesMissingCollaborators() {
        assertThrows(NullPointerException.class, () -> new PartnerRateReader(null, () -> ON, () -> true, () -> NOW));
        assertThrows(NullPointerException.class, () -> new PartnerRateReader(partnerships, null, () -> true, () -> NOW));
        assertThrows(NullPointerException.class, () -> new PartnerRateReader(partnerships, () -> ON, null, () -> NOW));
        assertThrows(NullPointerException.class, () -> new PartnerRateReader(partnerships, () -> ON, () -> true, null));
    }
}
