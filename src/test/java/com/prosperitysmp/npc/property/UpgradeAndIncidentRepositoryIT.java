package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB. Upgrade levels and the record-only incident API. */
@Timeout(90)
class UpgradeAndIncidentRepositoryIT {
    private static final UUID WORLD = new UUID(11, 1);
    private static final long FUTURE = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli();
    private static final long PAST = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
    private static HomesTestDb db;
    private UpgradeRepository upgrades;
    private IncidentRepository incidents;
    private UUID property;
    private int next;

    @BeforeAll static void boot() throws Exception { db = HomesTestDb.start("npc_homes_upg_"); }

    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }

    @BeforeEach void reset() throws SQLException {
        db.clear("npc_upgrades", "npc_incidents", "npc_property_ownership", "npc_property_funding", "npc_properties");
        upgrades = new UpgradeRepository(db.database());
        incidents = new IncidentRepository(db.database());
        property = enroll();
    }

    private UUID enroll() throws SQLException {
        next++;
        var p = new Property(new UUID(12, next), WORLD, "home-" + next, "town", "small", 2, 10_000, 10_000, new UUID(13, next), 0, 80, true);
        new PropertyRepository(db.database()).insertEnrollment(p, 0);
        return p.propertyId();
    }

    @Test void anUpgradeNeverBoughtIsLevelZero() throws SQLException {
        assertEquals(0, upgrades.levelOf(property, "ADVERTISING"));
        assertEquals(0, upgrades.advertisingLevel(property));
        assertEquals(0, upgrades.facilitiesLevel(property));
        assertEquals(0, upgrades.totalLevels(property));
        assertEquals(0, upgrades.effectiveLevel(property, UpgradeType.FACILITIES));
        assertEquals(0, upgrades.levelOf(property, "GARDEN"));
    }

    @Test void raisingALevelReplacesItAndAddsUpWhatWasPaid() throws SQLException {
        upgrades.setLevel(property, "ADVERTISING", 1, 10_000);
        upgrades.setLevel(property, "ADVERTISING", 2, 25_000);
        assertEquals(2, upgrades.advertisingLevel(property));
        assertEquals(35_000L, db.count("SELECT purchase_cost_cents FROM npc_upgrades WHERE property_id=? AND upgrade_type='ADVERTISING'",
                property));
        assertEquals(1, db.count("SELECT COUNT(*) FROM npc_upgrades WHERE property_id=?", property));
        assertNotNull(db.scalar("SELECT installed_at FROM npc_upgrades WHERE property_id=?", property));
    }

    @Test void totalLevelsSumsBothKindsForOneHomeOnly() throws SQLException {
        upgrades.setLevel(property, "ADVERTISING", 2, 0);
        upgrades.setLevel(property, "FACILITIES", 3, 0);
        UUID other = enroll();
        upgrades.setLevel(other, "FACILITIES", 1, 0);
        assertEquals(5, upgrades.totalLevels(property));
        assertEquals(1, upgrades.totalLevels(other));
        assertEquals(3, upgrades.facilitiesLevel(property));
    }

    @Test void aLevelAboveThreeIsRefusedByTheDatabase() throws SQLException {
        upgrades.setLevel(property, "FACILITIES", 3, 0);
        assertThrows(SQLException.class, () -> upgrades.setLevel(property, "FACILITIES", 4, 0));
        assertThrows(SQLException.class, () -> upgrades.setLevel(property, "FACILITIES", -1, 0));
        assertEquals(3, upgrades.facilitiesLevel(property));
    }

    @Test void anIncidentDisablesTheEffectiveLevelButNotTheInstalledOne() throws SQLException {
        upgrades.setLevel(property, "ADVERTISING", 2, 0);
        upgrades.setLevel(property, "FACILITIES", 1, 0);
        db.exec("UPDATE npc_upgrades SET disabled_incident_id=77 WHERE property_id=? AND upgrade_type='ADVERTISING'", property);
        assertEquals(0, upgrades.effectiveLevel(property, UpgradeType.ADVERTISING));
        assertEquals(2, upgrades.advertisingLevel(property));
        assertEquals(1, upgrades.effectiveLevel(property, UpgradeType.FACILITIES));
        assertEquals(3, upgrades.totalLevels(property), "billing still counts the installed level");
    }

    @Test void effectiveLevelNeedsAPropertyAndAType() {
        assertThrows(NullPointerException.class, () -> upgrades.effectiveLevel(null, UpgradeType.ADVERTISING));
        assertThrows(NullPointerException.class, () -> upgrades.effectiveLevel(property, null));
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void anOpenIncidentRecordsTheTypesFixedTerms(IncidentType type) throws SQLException {
        incidents.create(property, type, "FACILITIES", "2026-10-18", FUTURE, false);
        assertTrue(incidents.hasOpenIncident(property));
        assertFalse(incidents.hasOverdueIncident(property));
        assertEquals(type.name(), db.scalar("SELECT incident_type FROM npc_incidents WHERE property_id=?", property));
        assertEquals(type.cashRepairCents(), db.count("SELECT cash_repair_cents FROM npc_incidents WHERE property_id=?", property));
        assertEquals(type.conditionDamage(), db.count("SELECT condition_damage FROM npc_incidents WHERE property_id=?", property));
        assertEquals(type.itemRepairSpec(), db.scalar("SELECT item_repair_spec FROM npc_incidents WHERE property_id=?", property));
        assertEquals("FACILITIES", db.scalar("SELECT affected_upgrade_type FROM npc_incidents WHERE property_id=?", property));
        assertNull(db.scalar("SELECT resolved_at FROM npc_incidents WHERE property_id=?", property));
    }

    @Test void aPreventedIncidentIsResolvedDoesNoDamageAndBlocksNothing() throws SQLException {
        incidents.create(property, IncidentType.BREAK_IN, null, "2026-10-18", PAST, true);
        assertFalse(incidents.hasOpenIncident(property));
        assertFalse(incidents.hasOverdueIncident(property), "a prevented incident is never overdue");
        assertTrue(incidents.hasRollForDay(property, "2026-10-18"), "it still uses up the day's roll");
        assertEquals(0, db.count("SELECT condition_damage FROM npc_incidents WHERE property_id=?", property));
        assertEquals(Boolean.TRUE, db.scalar("SELECT prevented FROM npc_incidents WHERE property_id=?", property));
        assertNotNull(db.scalar("SELECT resolved_at FROM npc_incidents WHERE property_id=?", property));
        assertNull(db.scalar("SELECT affected_upgrade_type FROM npc_incidents WHERE property_id=?", property));
    }

    @Test void anOpenIncidentPastItsDueTimeIsOverdue() throws SQLException {
        incidents.create(property, IncidentType.PESTS, null, "2026-10-18", PAST, false);
        assertTrue(incidents.hasOverdueIncident(property));
        db.exec("UPDATE npc_incidents SET resolved_at=NOW(3) WHERE property_id=?", property);
        assertFalse(incidents.hasOverdueIncident(property));
        assertFalse(incidents.hasOpenIncident(property));
    }

    @Test void theDueTimeIsStoredAsUtcWallClock() throws SQLException {
        long due = Instant.parse("2026-10-18T12:34:56.789Z").toEpochMilli();
        incidents.create(property, IncidentType.PLUMBING, null, "2026-10-18", due, false);
        assertEquals("2026-10-18 12:34:56.789",
                db.scalar("SELECT DATE_FORMAT(due_at, '%Y-%m-%d %H:%i:%s.%f') FROM npc_incidents WHERE property_id=?", property)
                        .toString().substring(0, 23));
    }

    @Test void theDaysRollIsPerPropertyAndDay() throws SQLException {
        incidents.create(property, IncidentType.PESTS, null, "2026-10-18", FUTURE, false);
        UUID other = enroll();
        assertTrue(incidents.hasRollForDay(property, "2026-10-18"));
        assertFalse(incidents.hasRollForDay(property, "2026-10-19"));
        assertFalse(incidents.hasRollForDay(other, "2026-10-18"));
        assertFalse(incidents.hasOpenIncident(other));
    }
}
