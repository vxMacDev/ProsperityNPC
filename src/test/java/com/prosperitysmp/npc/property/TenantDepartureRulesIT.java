package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB. A resident leaving keeps their identity and leaves one immutable departure fact. */
@Timeout(90)
class TenantDepartureRulesIT {
    private static final UUID WORLD = new UUID(14, 1);
    private static final UUID OWNER = new UUID(14, 2);
    private static final Instant NOW = Instant.parse("2026-10-18T09:15:30.250Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static HomesTestDb db;
    private TenantRepository tenants;
    private int next;

    @BeforeAll static void boot() throws Exception { db = HomesTestDb.start("npc_homes_tenant_"); }

    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }

    @BeforeEach void reset() throws SQLException {
        db.clear("npc_tenancy_departures", "npc_tenants", "npc_property_ownership", "npc_property_funding", "npc_properties");
        tenants = new TenantRepository(db.database(), CLOCK);
    }

    private UUID home(int epoch) throws SQLException {
        next++;
        var p = new Property(new UUID(15, next), WORLD, "home-" + next, "town", "small", 4, 10_000, 10_000, OWNER, epoch, 80, true);
        new PropertyRepository(db.database()).insertEnrollment(p, 0);
        return p.propertyId();
    }

    private UUID resident(UUID home, int slot) throws SQLException {
        next++;
        UUID id = new UUID(16, next);
        tenants.insert(new Tenant(id, "Resident " + next, home, slot, "STANDARD", "COOK", 50));
        return id;
    }

    private long revision(UUID resident) throws SQLException {
        return db.count("SELECT row_version FROM npc_tenants WHERE tenant_id=?", resident);
    }

    private TenancyDeparture leave(UUID resident, UUID expected) throws SQLException {
        try (Connection c = db.database().getConnection()) {
            c.setAutoCommit(false);
            try {
                var departure = TenantRepository.vacateVoluntarily(c, resident, expected, CLOCK);
                c.commit();
                return departure;
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            }
        }
    }

    @Test void aVoluntaryDepartureRecordsTheHomeOwnerSlotAndTime() throws SQLException {
        UUID home = home(3);
        UUID ada = resident(home, 2);
        long before = revision(ada);
        var departure = leave(ada, home);
        assertEquals(new TenancyDeparture(ada, home, OWNER, 3, before, 2, NOW, TenancyDeparture.Cause.VOLUNTARY, null), departure);
        assertEquals(before + 1, revision(ada));
        assertNull(db.scalar("SELECT property_id FROM npc_tenants WHERE tenant_id=?", ada));
        assertNull(db.scalar("SELECT slot FROM npc_tenants WHERE tenant_id=?", ada));
        assertEquals("Resident " + (next), db.scalar("SELECT display_name FROM npc_tenants WHERE tenant_id=?", ada),
                "identity is kept");
        assertEquals(Optional.of(departure), tenants.departure(ada, before));
        assertEquals(departure.fingerprint(), db.scalar("SELECT payload_sha256 FROM npc_tenancy_departures WHERE resident_id=?", ada));
    }

    @Test void aResidentLivingElsewhereIsNotMovedOut() throws SQLException {
        UUID home = home(0);
        UUID elsewhere = home(0);
        UUID ada = resident(elsewhere, 0);
        long before = revision(ada);
        assertNull(leave(ada, home));
        assertEquals(before, revision(ada));
        assertEquals(0, db.count("SELECT COUNT(*) FROM npc_tenancy_departures"));
        assertEquals(1, tenants.countByProperty(elsewhere));
    }

    @Test void anUnknownResidentHasNothingToRecord() throws SQLException {
        UUID home = home(0);
        assertNull(leave(new UUID(16, 999), home));
        assertEquals(0, db.count("SELECT COUNT(*) FROM npc_tenancy_departures"));
    }

    @Test void leavingTwiceRecordsOneDeparture() throws SQLException {
        UUID home = home(0);
        UUID ada = resident(home, 1);
        assertNotNull(leave(ada, home));
        assertNull(leave(ada, home));
        assertEquals(1, db.count("SELECT COUNT(*) FROM npc_tenancy_departures WHERE resident_id=?", ada));
    }

    @Test void aVoluntaryDepartureNeedsTheExpectedHomeAndATransaction() throws SQLException {
        UUID home = home(0);
        UUID ada = resident(home, 0);
        try (Connection c = db.database().getConnection()) {
            assertTrue(c.getAutoCommit());
            var refused = assertThrows(SQLException.class, () -> TenantRepository.vacateVoluntarily(c, ada, home, CLOCK));
            assertEquals("Vacating requires an explicit transaction", refused.getMessage());
            c.setAutoCommit(false);
            assertThrows(NullPointerException.class, () -> TenantRepository.vacateVoluntarily(c, ada, null, CLOCK));
            assertThrows(NullPointerException.class, () -> TenantRepository.vacateVoluntarily(c, null, home, CLOCK));
            assertThrows(NullPointerException.class, () -> TenantRepository.vacateVoluntarily(c, ada, home, null));
            c.rollback();
        }
        assertEquals(1, tenants.countByProperty(home));
    }

    @Test void aPlainVacateIsAGenericDepartureOnce() throws SQLException {
        UUID home = home(1);
        UUID ada = resident(home, 0);
        long before = revision(ada);
        assertTrue(tenants.vacate(ada));
        assertFalse(tenants.vacate(ada), "already out");
        var stored = tenants.departure(ada, before).orElseThrow();
        assertEquals(TenancyDeparture.Cause.DEPARTURE, stored.cause());
        assertEquals(OWNER, stored.ownerAtDeparture());
        assertEquals(1, stored.ownershipEpoch());
        assertEquals(NOW, stored.departedAt());
        assertEquals(0, tenants.countByProperty(home));
    }

    @Test void aDepartureLookupNeedsAValidRevision() {
        assertThrows(IllegalArgumentException.class, () -> tenants.departure(new UUID(16, 1), -1));
        assertThrows(IllegalArgumentException.class, () -> tenants.departure(new UUID(16, 1), Long.MAX_VALUE));
        assertThrows(NullPointerException.class, () -> tenants.departure(null, 0));
    }

    @Test void anUnrecordedDepartureIsEmptyNotProofOfStaying() throws SQLException {
        UUID home = home(0);
        UUID ada = resident(home, 0);
        assertEquals(Optional.empty(), tenants.departure(ada, revision(ada)));
        leave(ada, home);
        assertEquals(Optional.empty(), tenants.departure(ada, revision(ada)), "only the revision it left from has a fact");
    }

    @Test void aTamperedDepartureFingerprintIsRefusedOnRead() throws SQLException {
        UUID home = home(0);
        UUID ada = resident(home, 0);
        long before = revision(ada);
        leave(ada, home);
        db.exec("UPDATE npc_tenancy_departures SET payload_sha256=? WHERE resident_id=?", "0".repeat(64), ada);
        var refused = assertThrows(SQLException.class, () -> tenants.departure(ada, before));
        assertEquals("Departure fingerprint or revision mismatch", refused.getMessage());
    }

    @Test void aTamperedDepartureCauseIsRefusedOnRead() throws SQLException {
        UUID home = home(0);
        UUID ada = resident(home, 0);
        long before = revision(ada);
        leave(ada, home);
        db.exec("UPDATE npc_tenancy_departures SET cause='DEPARTURE' WHERE resident_id=?", ada);
        assertThrows(SQLException.class, () -> tenants.departure(ada, before));
    }

    @Test void theRepositoryNeedsADatabaseAndAClock() {
        assertThrows(NullPointerException.class, () -> new TenantRepository(null));
        assertThrows(NullPointerException.class, () -> new TenantRepository(db.database(), null));
    }
}
