package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB. The compare-and-set and bounds rules of the property row. */
@Timeout(90)
class PropertyRepositoryRulesIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final UUID WORLD = new UUID(8, 1);
    private static final UUID OWNER = new UUID(8, 2);
    private static final UUID BUYER = new UUID(8, 3);
    private static HomesTestDb db;
    private PropertyRepository repo;
    private int next;

    @BeforeAll static void boot() throws Exception { db = HomesTestDb.start("npc_homes_prop_"); }

    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }

    @BeforeEach void reset() throws SQLException {
        db.clear("npc_rent_change_notices", "npc_rent_changes", "npc_economy_events", "npc_tenants",
                "npc_property_ownership", "npc_property_funding", "npc_properties");
        repo = new PropertyRepository(db.database());
    }

    private Property home(int reference, UUID owner, boolean enabled) {
        next++;
        return new Property(new UUID(9, next), WORLD, "home-" + next, "town", "small", 2, reference, reference, owner, 0, 80, enabled);
    }

    private Property enrolled(int reference) throws SQLException {
        var p = home(reference, OWNER, true);
        repo.insertEnrollment(p, 0);
        return p;
    }

    private Property paused() throws SQLException {
        var p = home(10_000, OWNER, false);
        repo.insertEnrollment(p, 0);
        return p;
    }

    @Test void enrollmentWritesTheRowFundingAndAnEnrollHistoryRow() throws SQLException {
        var p = home(10_000, OWNER, true);
        repo.insertEnrollment(p, 2_500);
        assertEquals(p, repo.findById(p.propertyId()).orElseThrow());
        assertEquals("arm", db.scalar("SELECT backend_id FROM npc_properties WHERE property_id=?", p.propertyId()));
        assertArrayEquals(new long[]{2_500, 0, 0}, repo.fundingOf(p.propertyId()));
        assertEquals("enroll", db.scalar("SELECT source_snapshot FROM npc_property_ownership WHERE property_id=?", p.propertyId()));
        assertNull(db.scalar("SELECT old_owner_uuid FROM npc_property_ownership WHERE property_id=?", p.propertyId()));
    }

    @Test void anUnknownPropertyHasNoRowAndNoFunding() throws SQLException {
        assertTrue(repo.findById(new UUID(9, 999)).isEmpty());
        assertArrayEquals(new long[]{0, 0, 0}, repo.fundingOf(new UUID(9, 999)));
        assertTrue(repo.findByWorldRegion(WORLD, "nowhere").isEmpty());
    }

    @Test void aSecondEnrollmentOfTheSameRegionIsRefusedAndRolledBack() throws SQLException {
        var p = enrolled(10_000);
        var twin = new Property(new UUID(9, 500), WORLD, p.regionId(), "town", "small", 2, 10_000, 10_000, OWNER, 0, 80, true);
        assertThrows(SQLException.class, () -> repo.insertEnrollment(twin, 0));
        assertTrue(repo.findById(twin.propertyId()).isEmpty());
        assertEquals(0, db.count("SELECT COUNT(*) FROM npc_property_funding WHERE property_id=?", twin.propertyId()));
    }

    @Test void theSeedPoolGrantsAWholeAllowanceOrNothing() throws SQLException {
        var first = home(10_000, OWNER, true);
        var second = home(10_000, OWNER, true);
        var third = home(10_000, OWNER, true);
        repo.insertEnrollmentWithSeedPool(first, 5_000, 9_000);
        repo.insertEnrollmentWithSeedPool(second, 5_000, 9_000);
        repo.insertEnrollmentWithSeedPool(third, 4_000, 9_000);
        assertEquals(5_000, repo.fundingOf(first.propertyId())[0]);
        assertEquals(0, repo.fundingOf(second.propertyId())[0], "5000 more would pass the 9000 pool");
        assertEquals(4_000, repo.fundingOf(third.propertyId())[0], "exactly filling the pool is allowed");
    }

    @ParameterizedTest
    @CsvSource({"10000,5000,true", "10000,4999,false", "10000,15000,true", "10000,15001,false", "10000,0,false",
            "10001,5000,true", "10001,15001,true", "10001,15002,false", "1,1,true", "3,1,true", "3,4,true", "3,5,false"})
    void immediateRentMustStayWithinHalfToOneAndAHalfTimesTheReference(int reference, long cents, boolean allowed)
            throws SQLException {
        var p = enrolled(reference);
        if (allowed) {
            var updated = repo.setAskingRentExpected(p, cents);
            assertEquals(cents, updated.askingRentCents());
            assertEquals(cents, ((Number) db.scalar("SELECT asking_rent_cents FROM npc_properties WHERE property_id=?",
                    p.propertyId())).longValue());
        } else {
            var refused = assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.setAskingRentExpected(p, cents));
            assertEquals("Property changed or rent outside allowed range", refused.getMessage());
            assertEquals(reference, repo.findById(p.propertyId()).orElseThrow().askingRentCents());
        }
    }

    @Test void immediateRentRefusesAStaleOrPausedRecord() throws SQLException {
        var p = enrolled(10_000);
        repo.setAskingRentExpected(p, 11_000);
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.setAskingRentExpected(p, 12_000),
                "the caller's record still shows the old rent");
        var off = paused();
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.setAskingRentExpected(off, 10_000));
    }

    @Test void aDelayedRentChangeIsScheduledThenAppliedOnItsDay() throws SQLException {
        var p = enrolled(10_000);
        var change = repo.changeAskingRent(p, 12_000, DAY, 3);
        assertEquals(PropertyRepository.RentChange.Outcome.SCHEDULED, change.outcome());
        assertEquals(DAY.plusDays(3), change.effectiveDay());
        assertEquals(0, change.notices());
        assertEquals(10_000, change.property().askingRentCents(), "the rent does not change yet");
        assertEquals(new PropertyRepository.DueRentStatus(0, 1), repo.dueRentStatus(DAY.plusDays(2)));
        assertEquals(new PropertyRepository.DueRentStatus(1, 1), repo.dueRentStatus(DAY.plusDays(3)));
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(2), 512).changes().isEmpty());
        var due = repo.applyDueRentChanges(DAY.plusDays(3), 512);
        assertEquals(1, due.changes().size());
        assertEquals(PropertyRepository.DueRent.Resolution.APPLIED, due.changes().getFirst().resolution());
        assertEquals(12_000, due.changes().getFirst().after().askingRentCents());
        assertEquals(0, due.stillPending());
        assertEquals(0, repo.countPendingRentChanges());
        assertEquals("APPLIED", db.scalar("SELECT state FROM npc_rent_changes WHERE change_id=?", change.changeId()));
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(3), 512).changes().isEmpty(), "a replay changes nothing");
    }

    @Test void anExcludedHomeKeepsItsDueChangePending() throws SQLException {
        var p = enrolled(10_000);
        repo.changeAskingRent(p, 12_000, DAY, 1);
        var result = repo.applyDueRentChanges(DAY.plusDays(1), 512, Set.of(p.propertyId()));
        assertTrue(result.changes().isEmpty());
        assertEquals(List.of(p.propertyId()), result.deferred());
        assertEquals(1, result.stillPending());
        assertEquals(10_000, repo.findById(p.propertyId()).orElseThrow().askingRentCents());
    }

    @Test void theSameAmountAgainIsAlreadyScheduledAndTheCurrentAmountCancels() throws SQLException {
        var p = enrolled(10_000);
        var first = repo.changeAskingRent(p, 12_000, DAY, 2);
        var again = repo.changeAskingRent(first.property(), 12_000, DAY, 5);
        assertEquals(PropertyRepository.RentChange.Outcome.ALREADY_SCHEDULED, again.outcome());
        assertEquals(DAY.plusDays(2), again.effectiveDay(), "the first schedule keeps its day");
        assertEquals(first.changeId(), again.changeId());
        var cancel = repo.changeAskingRent(first.property(), 10_000, DAY, 2);
        assertEquals(PropertyRepository.RentChange.Outcome.CANCELLED, cancel.outcome());
        assertEquals("CANCELLED", db.scalar("SELECT state FROM npc_rent_changes WHERE change_id=?", first.changeId()));
        assertEquals(0, repo.countPendingRentChanges());
        var unchanged = repo.changeAskingRent(repo.findById(p.propertyId()).orElseThrow(), 10_000, DAY, 2);
        assertEquals(PropertyRepository.RentChange.Outcome.UNCHANGED, unchanged.outcome());
        assertEquals(0, unchanged.changeId());
    }

    @Test void aNewAmountReplacesTheOwnersPendingChange() throws SQLException {
        var p = enrolled(10_000);
        var first = repo.changeAskingRent(p, 12_000, DAY, 2);
        var second = repo.changeAskingRent(first.property(), 13_000, DAY, 4);
        assertEquals(PropertyRepository.RentChange.Outcome.SCHEDULED, second.outcome());
        assertNotEquals(first.changeId(), second.changeId());
        assertEquals("REPLACED", db.scalar("SELECT state FROM npc_rent_changes WHERE change_id=?", first.changeId()));
        assertEquals(1, repo.countPendingRentChanges());
    }

    @Test void aDelayedChangeOutsideTheBoundsIsRefused() throws SQLException {
        var p = enrolled(10_000);
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.changeAskingRent(p, 15_001, DAY, 2));
        assertEquals(0, repo.countPendingRentChanges());
    }

    @Test void aDelayedChangeOnAHomeWithoutAnOwnerIsRefused() throws SQLException {
        var p = home(10_000, null, true);
        repo.insertEnrollment(p, 0);
        var refused = assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.changeAskingRent(p, 12_000, DAY, 2));
        assertEquals("Rent change needs an owner", refused.getMessage());
        assertEquals(0, db.count("SELECT COUNT(*) FROM npc_rent_changes"));
    }

    @Test void aDelayedChangeWritesANoticePerCurrentResident() throws SQLException {
        var p = enrolled(10_000);
        for (int slot = 0; slot < 2; slot++)
            db.exec("INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype,satisfaction) VALUES(?,?,?,?,?,?)",
                    new UUID(10, slot), "Resident", p.propertyId(), slot, "COOK", 50);
        var change = repo.changeAskingRent(p, 12_000, DAY, 2);
        assertEquals(2, change.notices());
        assertEquals(2, db.count("SELECT COUNT(*) FROM npc_rent_change_notices WHERE change_id=?", change.changeId()));
    }

    @Test void resumeRefusesAnEnabledOrChangedRecord() throws SQLException {
        var on = enrolled(10_000);
        var enabled = assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(on, OWNER));
        assertEquals("Paused property changed", enabled.getMessage());
        var off = paused();
        var stale = off.withAskingRent(9_000);
        assertEquals("Paused property changed",
                assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(stale, OWNER)).getMessage());
        assertFalse(repo.findById(off.propertyId()).orElseThrow().enabled());
    }

    @Test void resumeRefusesWhileAnEventIsUnsettled() throws SQLException {
        var off = paused();
        db.exec("INSERT INTO npc_economy_events(idempotency_key,requested_amount_cents,source,direction,property_id,economy_day,state) "
                + "VALUES('k1',100,'RENT','PAYOUT',?,?,'CLAIMED')", off.propertyId(), DAY.toString());
        var refused = assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, BUYER));
        assertEquals("Unsettled property event requires review before resume", refused.getMessage());
        var row = repo.findById(off.propertyId()).orElseThrow();
        assertEquals(OWNER, row.ownerUuid(), "the refused resume writes no owner change");
        assertEquals(0, row.ownershipEpoch());
        db.exec("UPDATE npc_economy_events SET state='FAILED'");
        assertTrue(repo.resumeExpected(off, OWNER).enabled());
    }

    @Test void resumeWithTheSameOwnerOnlyEnables() throws SQLException {
        var off = paused();
        var resumed = repo.resumeExpected(off, OWNER);
        assertTrue(resumed.enabled());
        assertEquals(0, resumed.ownershipEpoch());
        assertEquals(1, db.count("SELECT COUNT(*) FROM npc_property_ownership WHERE property_id=?", off.propertyId()));
    }

    @Test void resumeWithANewOwnerBumpsTheEpochAndRecordsIt() throws SQLException {
        var off = paused();
        var resumed = repo.resumeExpected(off, BUYER);
        assertEquals(BUYER, resumed.ownerUuid());
        assertEquals(1, resumed.ownershipEpoch());
        assertTrue(resumed.enabled());
        assertEquals("resume-current-arm-owner", db.scalar(
                "SELECT source_snapshot FROM npc_property_ownership WHERE property_id=? AND ownership_epoch=1", off.propertyId()));
    }

    @Test void resumeAtTheLastEpochWithANewOwnerOverflowsAndWritesNothing() throws SQLException {
        var off = paused();
        db.exec("UPDATE npc_properties SET ownership_epoch=? WHERE property_id=?", Integer.MAX_VALUE, off.propertyId());
        var atMax = repo.findById(off.propertyId()).orElseThrow();
        assertThrows(ArithmeticException.class, () -> repo.resumeExpected(atMax, BUYER));
        var row = repo.findById(off.propertyId()).orElseThrow();
        assertFalse(row.enabled());
        assertEquals(OWNER, row.ownerUuid());
    }

    @Test void reconcileAtTheLastEpochIsRefused() throws SQLException {
        var p = enrolled(10_000);
        db.exec("UPDATE npc_properties SET ownership_epoch=? WHERE property_id=?", Integer.MAX_VALUE, p.propertyId());
        var atMax = repo.findById(p.propertyId()).orElseThrow();
        var refused = assertThrows(SQLException.class, () -> repo.reconcileOwner(atMax, BUYER));
        assertEquals("Ownership epoch exhausted", refused.getMessage());
        assertEquals(atMax, repo.reconcileOwner(atMax, OWNER), "the same owner needs no new epoch");
    }

    @Test void reconcileWritesTheObservedOwnerOnce() throws SQLException {
        var p = enrolled(10_000);
        var changed = repo.reconcileOwner(p, BUYER);
        assertEquals(p.withOwner(BUYER, 1), changed);
        assertEquals("arm-reconcile", db.scalar(
                "SELECT source_snapshot FROM npc_property_ownership WHERE property_id=? AND ownership_epoch=1", p.propertyId()));
        var stale = assertThrows(SQLException.class, () -> repo.reconcileOwner(p, BUYER));
        assertEquals("Stale property ownership observation", stale.getMessage());
    }

    @Test void reconcileRefusesAPausedHome() throws SQLException {
        var off = paused();
        assertEquals("Stale property ownership observation",
                assertThrows(SQLException.class, () -> repo.reconcileOwner(off, BUYER)).getMessage());
    }

    @Test void anOwnerChangeMustNameTheCurrentOwnerAndTheNextEpoch() throws SQLException {
        var p = enrolled(10_000);
        assertEquals("Stale ownership change", assertThrows(SQLException.class,
                () -> repo.applyOwnerChange(p.propertyId(), BUYER, BUYER, 1, "test")).getMessage());
        assertEquals("Stale ownership change", assertThrows(SQLException.class,
                () -> repo.applyOwnerChange(p.propertyId(), OWNER, BUYER, 2, "test")).getMessage());
        repo.applyOwnerChange(p.propertyId(), OWNER, BUYER, 1, "test");
        assertEquals(p.withOwner(BUYER, 1), repo.findById(p.propertyId()).orElseThrow());
        assertEquals("Ownership property missing", assertThrows(SQLException.class,
                () -> repo.applyOwnerChange(new UUID(9, 999), null, BUYER, 1, "test")).getMessage());
    }

    @Test void anOwnerChangeNeedsASource() throws SQLException {
        var p = enrolled(10_000);
        assertEquals("Invalid ownership source", assertThrows(SQLException.class,
                () -> repo.applyOwnerChange(p.propertyId(), OWNER, BUYER, 1, " ")).getMessage());
        assertEquals("Invalid ownership source", assertThrows(SQLException.class,
                () -> repo.applyOwnerChange(p.propertyId(), OWNER, BUYER, 1, "s".repeat(256))).getMessage());
        assertEquals(OWNER, repo.findById(p.propertyId()).orElseThrow().ownerUuid(), "nothing was written");
    }

    @Test void disableOnlyMatchesTheExpectedIncarnationOnce() throws SQLException {
        var p = enrolled(10_000);
        assertFalse(repo.disableExpected(p.withOwner(OWNER, 3)));
        assertFalse(repo.disableExpected(p.withOwner(BUYER, 0)));
        assertTrue(repo.disableExpected(p));
        assertFalse(repo.disableExpected(p), "already disabled");
        assertFalse(repo.findById(p.propertyId()).orElseThrow().enabled());
    }

    @Test void conditionIsClampedToZeroToOneHundred() throws SQLException {
        var p = enrolled(10_000);
        repo.setCondition(p.propertyId(), -5);
        assertEquals(0, repo.findById(p.propertyId()).orElseThrow().conditionScore());
        repo.setCondition(p.propertyId(), 150);
        assertEquals(100, repo.findById(p.propertyId()).orElseThrow().conditionScore());
        repo.setCondition(p.propertyId(), 42);
        assertEquals(42, repo.findById(p.propertyId()).orElseThrow().conditionScore());
    }

    @Test void homesUnderAnotherBackendAreLeftOutAndNamed() throws SQLException {
        var armHome = enrolled(10_000);
        repo.backendId("lands");
        var landsHome = enrolled(10_000);
        var landsPaused = paused();
        assertEquals(List.of(landsHome), repo.listEnrolled());
        assertEquals(java.util.Map.of("arm", 1), repo.enrolledUnderOtherBackends());
        var refused = assertThrows(PropertyRepository.EnrolledUnderOtherBackend.class,
                () -> repo.findByWorldRegion(WORLD, armHome.regionId(), "lands"));
        assertEquals("arm", refused.backendId());
        assertEquals(landsPaused, repo.findByWorldRegion(WORLD, landsPaused.regionId(), "lands").orElseThrow());
        assertEquals(armHome, repo.findByWorldRegion(WORLD, armHome.regionId()).orElseThrow(), "the plain lookup ignores the backend");
        repo.backendId("arm");
        assertEquals(List.of(armHome), repo.listEnrolled());
    }
}
