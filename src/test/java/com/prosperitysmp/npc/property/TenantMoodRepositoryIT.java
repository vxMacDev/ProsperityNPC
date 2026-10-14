package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB. Daily mood step: inputs from real rows, idempotent per economy day. */
@Timeout(60)
class TenantMoodRepositoryIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static DB maria;
    private static Database db;
    private TenantMoodRepository moods;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(java.nio.file.Files.createTempDirectory("npc-mood-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_mood_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            db = new Database(); db.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 8, 10_000));
            for (int v = 1; v <= 28; v++) applyVersion(v);
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @AfterAll static void stop() throws Exception {
        try { if (db != null) db.close(); }
        finally { if (maria != null) { DB old = maria; maria = null; old.stop(); } }
    }
    @BeforeEach void reset() throws SQLException {
        try (Connection c = db.getConnection()) {
            exec(c, "SET FOREIGN_KEY_CHECKS=0");
            for (String t : List.of("npc_utility_payments", "npc_utility_bills", "npc_incidents", "npc_tenants", "npc_properties"))
                exec(c, "DELETE FROM " + t);
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
        moods = new TenantMoodRepository(db);
    }

    private UUID property(int condition, int reference, int asking, boolean enabled) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                    + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, UUID.randomUUID(), "r-" + id, "test", "small", 2, reference, asking, UUID.randomUUID(), 0, condition, enabled);
        }
        return id;
    }
    private UUID tenant(UUID property, int slot, int satisfaction) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype,satisfaction) VALUES(?,?,?,?,?,?)",
                    id, "Resident", property, slot, "COOK", satisfaction);
        }
        return id;
    }
    private void incident(UUID property, LocalDate day, boolean resolved, boolean prevented) throws SQLException {
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_incidents(property_id,incident_type,cash_repair_cents,item_repair_spec,condition_damage,economy_day,due_at,resolved_at,prevented)"
                    + " VALUES(?,?,?,?,?,?,NOW(3)," + (resolved ? "NOW(3)" : "NULL") + ",?)",
                    property, "LEAK", 500, "none", 5, day.toString(), prevented);
        }
    }
    private long nextEvent = 1;
    private void bill(UUID property, LocalDate day, String paymentState) throws SQLException {
        try (Connection c = db.getConnection()) {
            exec(c, "SET FOREIGN_KEY_CHECKS=0");
            exec(c, "INSERT INTO npc_utility_bills(property_id,economy_day,owner_uuid,ownership_epoch,property_revision,capacity,occupied_tenants,"
                    + "installed_upgrade_levels,issued_at,period_start,due_at,clock_zone,reset_hour,amount_cents,payload_sha256) "
                    + "VALUES(?,?,?,0,0,2,1,0,'2026-10-12 12:00:00.000','2026-10-12 09:00:00.000','2026-10-13 09:00:00.000','UTC',4,175,?)",
                    property, day.toString(), UUID.randomUUID(), "a".repeat(64));
            if (paymentState != null) {
                String extra = paymentState.equals("PAID") ? ",'TRANSFER_CONFIRMED',NOW(3)"
                        : paymentState.equals("DECLINED") ? ",'PAYMENT_DECLINED',NULL"
                        : ",'UNCERTAIN',NULL";
                exec(c, "INSERT INTO npc_utility_payments(request_id,property_id,economy_day,attempt_number,event_id,state,outcome_code,paid_at) "
                        + "VALUES(?,?,?,1,?,'" + paymentState + "'" + extra + ")", UUID.randomUUID(), property, day.toString(), nextEvent++);
            }
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
    }
    private int satisfaction(UUID tenant) throws SQLException { return ((Number) scalar("SELECT satisfaction FROM npc_tenants WHERE tenant_id=?", tenant)).intValue(); }
    private String mood(UUID tenant) throws SQLException { return String.valueOf(scalar("SELECT mood FROM npc_tenants WHERE tenant_id=?", tenant)); }

    @Test void carefulOwnerRaisesMoodOneStepAtATime() throws Exception {
        UUID p = property(100, 100, 90, true); UUID t = tenant(p, 0, 50);
        bill(p, DAY.minusDays(1), "PAID");
        assertEquals(1, moods.updateDay(DAY));
        assertEquals(55, satisfaction(t));
        assertEquals("CONTENT", mood(t));
        assertEquals(TenantMood.Band.CONTENT, moods.band(t).orElseThrow());
    }

    @Test void neglectedPropertyFallsGradually() throws Exception {
        UUID p = property(10, 100, 150, true); UUID t = tenant(p, 0, 50);
        incident(p, DAY.minusDays(4), false, false);
        bill(p, DAY.minusDays(1), "DECLINED");
        LocalDate day = DAY;
        while (!"UNHAPPY".equals(mood(t)) && day.isBefore(DAY.plusDays(30))) { moods.updateDay(day); day = day.plusDays(1); }
        assertEquals("UNHAPPY", mood(t));
        assertTrue(satisfaction(t) < 30);
        assertTrue(day.isAfter(DAY.plusDays(2)), "gradual");
    }

    @Test void resolvedPreventedAndUnsettledInputsDoNotPunish() throws Exception {
        UUID p = property(60, 100, 105, true); UUID t = tenant(p, 0, 50);
        incident(p, DAY.minusDays(3), true, false);
        incident(p, DAY.minusDays(2), false, true);
        bill(p, DAY.minusDays(1), "REVIEW_REQUIRED");
        moods.updateDay(DAY);
        assertEquals(50, satisfaction(t), "target 50 means no movement");
        assertEquals("NEUTRAL", mood(t));
    }

    @Test void secondRunOnTheSameDayChangesNothing() throws Exception {
        UUID p = property(10, 100, 150, true); UUID a = tenant(p, 0, 50); UUID b = tenant(p, 1, 80);
        assertEquals(2, moods.updateDay(DAY));
        int sa = satisfaction(a), sb = satisfaction(b);
        assertEquals(0, moods.updateDay(DAY));
        assertEquals(0, moods.updateDay(DAY.minusDays(1)), "an earlier day never rewinds or reapplies");
        assertEquals(sa, satisfaction(a));
        assertEquals(sb, satisfaction(b));
        assertEquals(2, moods.updateDay(DAY.plusDays(1)));
        assertEquals(sa - 5, satisfaction(a));
    }

    @Test void interruptedBatchContinuesWithoutDoubleStepping() throws Exception {
        UUID p = property(10, 100, 150, true);
        UUID a = tenant(p, 0, 50), b = tenant(p, 1, 50);
        assertEquals(1, moods.updateDay(DAY, 1));
        assertEquals(1, moods.updateDay(DAY, 1));
        assertEquals(0, moods.updateDay(DAY, 1));
        assertEquals(45, satisfaction(a));
        assertEquals(45, satisfaction(b));
    }

    @Test void disabledPropertiesAndNeverUpdatedResidentsAreLeftAlone() throws Exception {
        UUID off = property(0, 100, 150, false); UUID t = tenant(off, 0, 50);
        assertEquals(0, moods.updateDay(DAY));
        assertEquals(50, satisfaction(t));
        assertEquals("NEUTRAL", mood(t));
        assertTrue(moods.band(t).isEmpty(), "no band is claimed before the first update");
        assertTrue(moods.band(UUID.randomUUID()).isEmpty());
    }

    @Test void updateNeverChangesOccupancyRevisionOrRent() throws Exception {
        UUID p = property(30, 100, 130, true); UUID t = tenant(p, 0, 50);
        long version = ((Number) scalar("SELECT row_version FROM npc_tenants WHERE tenant_id=?", t)).longValue();
        moods.updateDay(DAY);
        assertEquals(version, ((Number) scalar("SELECT row_version FROM npc_tenants WHERE tenant_id=?", t)).longValue());
        assertEquals(0, ((Number) scalar("SELECT slot FROM npc_tenants WHERE tenant_id=?", t)).intValue());
        assertEquals(130, ((Number) scalar("SELECT asking_rent_cents FROM npc_properties WHERE property_id=?", p)).intValue());
    }

    @Test void vacatedResidentHasNoBand() throws Exception {
        UUID p = property(100, 100, 90, true); UUID t = tenant(p, 0, 50);
        moods.updateDay(DAY);
        assertTrue(moods.band(t).isPresent());
        try (Connection c = db.getConnection()) { exec(c, "UPDATE npc_tenants SET property_id=NULL WHERE tenant_id=?", t); }
        assertTrue(moods.band(t).isEmpty(), "a resident without a home must not report a home mood");
    }

    @Test void batchSizeIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> moods.updateDay(DAY, 0));
        assertThrows(IllegalArgumentException.class, () -> moods.updateDay(DAY, TenantMoodRepository.MAX_BATCH + 1));
    }

    private static Object scalar(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getObject(1); }
        }
    }
    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setQueryTimeout(5); bind(ps, args); ps.execute(); }
    }
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, args[i]);
        }
    }
    private static void applyVersion(int version) throws Exception {
        var dir = java.nio.file.Path.of("src/main/resources/db/migration");
        try (var files = java.nio.file.Files.list(dir)) {
            var file = files.filter(f -> f.getFileName().toString().startsWith("V" + version + "__")).findFirst().orElseThrow();
            try (Connection c = db.getConnection()) {
                for (String sql : MigrationRunner.splitStatements(java.nio.file.Files.readString(file, StandardCharsets.UTF_8))) exec(c, sql);
            }
        }
    }
}
