package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Argument checks that refuse before any connection is taken, so a repository with no database reaches them. */
class PropertyRepositoryGuardsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Property HOME = new Property(new UUID(7, 1), new UUID(7, 2), "home-1", "town", "small", 2, 10_000,
            10_000, new UUID(7, 3), 0, 100, true);
    private final PropertyRepository repo = new PropertyRepository(null);
    private final PropertyRepository unconnected = new PropertyRepository(new Database());

    @Test void theBackendIdDefaultsToArm() {
        assertEquals("arm", repo.backendId());
    }

    @Test void aBackendIdOfUpToThirtyTwoCharactersIsKeptAsGiven() {
        repo.backendId("Lands");
        assertEquals("Lands", repo.backendId());
        repo.backendId("x".repeat(32));
        assertEquals("x".repeat(32), repo.backendId());
    }

    @Test void aBadBackendIdIsRefusedAndTheOldOneKept() {
        repo.backendId("lands");
        for (String bad : new String[]{null, "", "   ", "x".repeat(33)}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> repo.backendId(bad));
            assertEquals("Bad backend id", failure.getMessage());
        }
        assertEquals("lands", repo.backendId());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "5001,0", "0,-1", "0,50001", "5001,50001"})
    void aSeedPolicyOutsideTheEnvelopeIsRefused(long seed, long pool) {
        var failure = assertThrows(IllegalArgumentException.class, () -> repo.insertEnrollmentWithSeedPool(HOME, seed, pool));
        assertEquals("Seed policy outside approved release envelope", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "5000,50000", "5000,0"})
    void aSeedPolicyInsideTheEnvelopeGoesOnToTheDatabase(long seed, long pool) {
        var reached = assertThrows(SQLException.class, () -> unconnected.insertEnrollmentWithSeedPool(HOME, seed, pool),
                "past the envelope check the unconnected database is reached");
        assertEquals("database not connected", reached.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 31, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void aRentDelayOutsideZeroToThirtyIsRefused(int delay) {
        var failure = assertThrows(IllegalArgumentException.class, () -> repo.changeAskingRent(HOME, 11_000, DAY, delay));
        assertEquals("Invalid rent change delay", failure.getMessage());
    }

    @Test void aRentChangeDayOutsideTheSupportedYearsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> repo.changeAskingRent(HOME, 11_000, LocalDate.of(9001, 1, 1), 1));
        assertThrows(IllegalArgumentException.class, () -> repo.changeAskingRent(HOME, 11_000, LocalDate.of(0, 12, 31), 1));
        var reached = assertThrows(SQLException.class,
                () -> unconnected.changeAskingRent(HOME, 11_000, LocalDate.of(9000, 12, 31), 30), "year 9000 passes the check");
        assertEquals("database not connected", reached.getMessage());
        var first = assertThrows(SQLException.class,
                () -> unconnected.changeAskingRent(HOME, 11_000, LocalDate.of(1, 1, 1), 30), "year 1 passes the check");
        assertEquals("database not connected", first.getMessage());
    }

    @Test void aRentChangeNeedsAPropertyAndADay() {
        assertThrows(NullPointerException.class, () -> repo.changeAskingRent(null, 11_000, DAY, 1));
        assertThrows(NullPointerException.class, () -> repo.changeAskingRent(HOME, 11_000, null, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 513})
    void aDueRentBatchOutsideOneTo512IsRefused(int limit) {
        var failure = assertThrows(IllegalArgumentException.class, () -> repo.applyDueRentChanges(DAY, limit));
        assertEquals("Rent change batch", failure.getMessage());
    }

    @Test void theDueRentLimitsMatchTheEnrolledPropertyLimit() {
        assertEquals(512, PropertyRepository.MAX_DUE_RENT_CHANGES);
        assertEquals(30, PropertyRepository.MAX_RENT_DELAY_DAYS);
    }

    @Test void aDueRentStepNeedsADayAndAnExclusionSet() {
        assertThrows(NullPointerException.class, () -> repo.applyDueRentChanges(null, 1, Set.of()));
        assertThrows(NullPointerException.class, () -> repo.applyDueRentChanges(DAY, 1, null));
    }

    @Test void anOwnerChangeNeedsAPositiveNextEpoch() {
        var zero = assertThrows(SQLException.class, () -> repo.applyOwnerChange(HOME.propertyId(), null, HOME.ownerUuid(), 0, "enroll"));
        assertEquals("Owner change requires a positive next epoch", zero.getMessage());
        assertThrows(SQLException.class, () -> repo.applyOwnerChange(HOME.propertyId(), null, HOME.ownerUuid(), -4, "enroll"));
        assertThrows(NullPointerException.class, () -> repo.applyOwnerChange(null, null, HOME.ownerUuid(), 1, "enroll"));
    }

    @Test void aResumeNeedsAnObservedOwner() {
        assertThrows(NullPointerException.class, () -> repo.resumeExpected(HOME, null));
    }

    @Test void aReconcileAndAFindNeedTheirKeys() {
        assertThrows(NullPointerException.class, () -> repo.reconcileOwner(null, HOME.ownerUuid()));
        assertThrows(NullPointerException.class, () -> repo.findById(null));
        assertThrows(NullPointerException.class, () -> repo.dueRentStatus(null));
        assertThrows(NullPointerException.class, () -> repo.findByWorldRegion(HOME.worldUuid(), "home-1", null));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,5", "5,5", "3,9"})
    void aDueRentStatusAcceptsDueUpToPending(int due, int pending) {
        var status = new PropertyRepository.DueRentStatus(due, pending);
        assertEquals(due, status.due());
        assertEquals(pending, status.pending());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1", "6,5", "1,0"})
    void aDueRentStatusRefusesNegativeOrMoreDueThanPending(int due, int pending) {
        var failure = assertThrows(IllegalArgumentException.class, () -> new PropertyRepository.DueRentStatus(due, pending));
        assertEquals("Rent change status", failure.getMessage());
    }

    @Test void theOtherBackendLineIsEmptyWithoutStrandedHomes() {
        assertEquals("", PropertyRepository.otherBackendsText(null, "arm"));
        assertEquals("", PropertyRepository.otherBackendsText(new TreeMap<>(), "arm"));
    }

    @Test void oneStrandedHomeUnderOneBackendIsSingular() {
        var other = new TreeMap<String, Integer>();
        other.put("lands", 1);
        assertEquals("1 home was enrolled under lands; the configured backend is arm. It stays out of payments until "
                + "property.backend is lands again.", PropertyRepository.otherBackendsText(other, "arm"));
    }

    @Test void severalHomesUnderOneBackendArePlural() {
        var other = new TreeMap<String, Integer>();
        other.put("arm", 3);
        assertEquals("3 homes were enrolled under arm; the configured backend is worldguard-listed. They stay out of payments "
                + "until property.backend is arm again.", PropertyRepository.otherBackendsText(other, "worldguard-listed"));
    }

    @Test void homesUnderSeveralBackendsAreListedInNameOrder() {
        var other = new TreeMap<String, Integer>();
        other.put("towny", 2);
        other.put("lands", 1);
        assertEquals("3 homes were enrolled under other backends {lands=1, towny=2}; the configured backend is arm. They stay "
                + "out of payments until property.backend is their backend again.", PropertyRepository.otherBackendsText(other, "arm"));
    }

    @Test void theOtherBackendRefusalNamesTheBackend() {
        var refused = new PropertyRepository.EnrolledUnderOtherBackend("lands");
        assertEquals("lands", refused.backendId());
        assertEquals("Enrolled under lands; set property.backend back to lands to manage it.", refused.getMessage());
        assertEquals("Paused property changed", new PropertyRepository.ResumeRefused("Paused property changed").getMessage());
    }
}
