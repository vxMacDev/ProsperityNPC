package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.VoluntaryDepartureRepository.Policy;
import com.prosperitysmp.npc.property.VoluntaryDepartureRepository.Result;
import com.prosperitysmp.npc.property.VoluntaryDepartureRepository.Target;
import com.prosperitysmp.npc.sim.DailyRoll;
import com.prosperitysmp.npc.sim.SimulationLogic;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB. Seeded multi-day voluntary departures: neglect lowers occupancy, care keeps it. */
@Timeout(120)
class VoluntaryDepartureRepositoryIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final UUID WORLD = new UUID(9, 9), OWNER = new UUID(8, 8), PLAYER = new UUID(7, 7);
    private static final Policy MOODS = new Policy(true, 3, 7, 40, 50), CONDITION = new Policy(false, 3, 7, 40, 50);
    private static DB maria;
    private static Database db;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(java.nio.file.Files.createTempDirectory("npc-voluntary-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_voluntary_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            db = new Database(); db.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 8, 10_000));
            Field registered = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            registered.setAccessible(true);
            boolean v29 = false;
            for (Object migration : (List<?>) registered.get(null)) {
                Method version = migration.getClass().getDeclaredMethod("version");
                Method resource = migration.getClass().getDeclaredMethod("resource");
                version.setAccessible(true); resource.setAccessible(true);
                try (InputStream in = VoluntaryDepartureRepositoryIT.class.getResourceAsStream("/" + resource.invoke(migration));
                     Connection c = db.getConnection()) {
                    assertNotNull(in);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) exec(c, sql);
                }
                if ((Integer) version.invoke(migration) == 29) v29 = true;
            }
            assertTrue(v29, "V29 must be registered, not merely present on disk");
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
            for (String t : List.of("npc_voluntary_departures", "npc_tenancy_departures", "npc_rent_reservations", "npc_rent_cohort",
                    "npc_resident_memory", "npc_housing_members", "npc_tenants", "npc_resident_profiles", "npc_incidents",
                    "npc_utility_payments", "npc_utility_bills", "npc_properties"))
                exec(c, "DELETE FROM " + t);
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private static VoluntaryDepartureRepository repo(long seed, LocalDate day) {
        return new VoluntaryDepartureRepository(db, new SimulationLogic(new DailyRoll(seed)),
                Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }
    private static UUID id(int n) { return new UUID(1, n); }
    private static Target target(UUID property) { return new Target(property, OWNER, 0); }

    private UUID property(int n, int condition, int asking) throws SQLException {
        UUID id = id(1000 + n);
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                    + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,TRUE)",
                    id, WORLD, "r-" + n, "test", "small", 4, 100, asking, OWNER, 0, condition);
        }
        return id;
    }
    private UUID resident(int n, UUID property, int slot, int satisfaction, String moodDay, String cooldown) throws SQLException {
        UUID id = id(n);
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_resident_profiles VALUES(?,1,?,'House',1,'COOK','STEADY','ADULT','WARM','WELL_KEPT_HOME','a','b','c')",
                    id, "Resident " + n);
            exec(c, "INSERT INTO npc_housing_members(resident_id,world_uuid,market_id,class_key,tier,max_daily_rent_cents,"
                    + "minimum_condition,available_since) VALUES(?,?,'test','small','STANDARD',1000,0,'2026-01-01')", id, WORLD);
            exec(c, "INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype,satisfaction,mood_day,move_cooldown_until_day)"
                    + " VALUES(?,?,?,?,?,?,?,?)", id, "Resident " + n, property, slot, "COOK", satisfaction, moodDay, cooldown);
            exec(c, "INSERT INTO npc_resident_memory(resident_id,player_id,source_ref,kind,occurred_at_millis,economy_day)"
                    + " VALUES(?,?,'introduction','INTRODUCTION',1,'2026-10-01')", id, PLAYER);
        }
        return id;
    }
    private void openIncident(UUID property, LocalDate day) throws SQLException {
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_incidents(property_id,incident_type,cash_repair_cents,item_repair_spec,condition_damage,economy_day,due_at)"
                    + " VALUES(?,'LEAK',500,'none',5,?,?)", property, day.toString(), day.plusDays(1).atStartOfDay());
        }
    }
    private static int housed(UUID property) throws SQLException {
        return ((Number) scalar("SELECT COUNT(*) FROM npc_tenants WHERE property_id=?", property)).intValue();
    }
    private static int rows(String sql, Object... args) throws SQLException { return ((Number) scalar(sql, args)).intValue(); }

    /** Runs a day, then replays it; the replay must change nothing at all. */
    private static Result dayAndReplay(long seed, LocalDate day, List<Target> targets, Policy policy) throws SQLException {
        Result first = repo(seed, day).runDay(day, targets, policy);
        assertEquals(List.of(), first.failed());
        int departures = rows("SELECT COUNT(*) FROM npc_tenancy_departures"), receipts = rows("SELECT COUNT(*) FROM npc_voluntary_departures");
        long versions = ((Number) scalar("SELECT COALESCE(SUM(row_version),0) FROM npc_tenants")).longValue();
        Result replay = repo(seed, day).runDay(day, targets, policy);
        assertEquals(List.of(), replay.departed(), "replay of " + day + " is a no-op");
        assertEquals(List.of(), replay.failed(), "a replay is skipped, not refused by the database");
        assertEquals(departures, rows("SELECT COUNT(*) FROM npc_tenancy_departures"));
        assertEquals(receipts, rows("SELECT COUNT(*) FROM npc_voluntary_departures"));
        assertEquals(versions, ((Number) scalar("SELECT COALESCE(SUM(row_version),0) FROM npc_tenants")).longValue());
        return first;
    }

    @Test void neglectedHomeLosesATenantAndWellRunHomeDoesNotOverThirtySeededDays() throws Exception {
        UUID neglected = property(1, 10, 150), cared = property(2, 100, 90);
        resident(1, neglected, 0, 50, null, null); resident(2, neglected, 1, 50, null, null);
        resident(3, cared, 0, 50, null, null); resident(4, cared, 1, 50, null, null);
        openIncident(neglected, DAY.minusDays(6));
        var moods = new TenantMoodRepository(db);
        List<VoluntaryDepartureRepository.Departed> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            LocalDate day = DAY.plusDays(i);
            moods.updateDay(day);
            Result result = dayAndReplay(42L, day, List.of(target(neglected), target(cared)), MOODS);
            assertTrue(result.departed().size() <= 1, "one home can lose at most one resident a day");
            all.addAll(result.departed());
        }
        assertFalse(all.isEmpty(), "neglect lowers occupancy");
        assertTrue(housed(neglected) < 2);
        assertEquals(2, housed(cared), "a well-run home never loses a resident by choice");
        for (var d : all) {
            assertEquals(neglected, d.propertyId());
            assertTrue(d.score() < 40);
            assertEquals(com.prosperitysmp.npc.formulas.PropertyFormulas.leaveChanceBp(d.score(), false), d.chanceBp());
            TenancyDeparture fact = new TenantRepository(db).departure(d.residentId(), d.revisionBefore()).orElseThrow();
            assertEquals(TenancyDeparture.Cause.VOLUNTARY, fact.cause());
            assertNull(fact.purchaseEventId());
            assertEquals(OWNER, fact.ownerAtDeparture());
            LocalDate left = LocalDate.parse(String.valueOf(scalar(
                    "SELECT economy_day FROM npc_voluntary_departures WHERE resident_id=? AND occupancy_revision_before=?",
                    d.residentId(), d.revisionBefore())));
            assertEquals(left.plusDays(7), d.cooldownUntil(), "the normal 7 day move cooldown applies");
            assertEquals(d.cooldownUntil().toString(), scalar("SELECT move_cooldown_until_day FROM npc_tenants WHERE tenant_id=?", d.residentId()));
            assertEquals(1, rows("SELECT COUNT(*) FROM npc_tenants WHERE tenant_id=? AND property_id IS NULL AND slot IS NULL", d.residentId()),
                    "identity row kept, only occupancy cleared");
            assertEquals(1, rows("SELECT COUNT(*) FROM npc_resident_profiles WHERE resident_id=?", d.residentId()));
            assertEquals(1, rows("SELECT COUNT(*) FROM npc_resident_memory WHERE resident_id=?", d.residentId()), "memories kept");
            assertEquals(1, rows("SELECT COUNT(*) FROM npc_housing_members WHERE resident_id=? AND enabled=TRUE", d.residentId()));
        }
        assertEquals(0, rows("SELECT COUNT(*) FROM npc_economy_events"), "no money moves");
    }

    @Test void withoutMoodsConditionAndOverdueIncidentsDecide() throws Exception {
        UUID neglected = property(1, 20, 100), cared = property(2, 90, 100);
        resident(1, neglected, 0, 50, null, null); resident(2, neglected, 1, 50, null, null);
        resident(3, cared, 0, 50, null, null);
        openIncident(neglected, DAY.minusDays(3));
        int lost = 0;
        for (int i = 0; i < 30; i++) lost += dayAndReplay(42L, DAY.plusDays(i), List.of(target(neglected), target(cared)), CONDITION).departed().size();
        assertTrue(lost >= 1);
        assertEquals(1, housed(cared));
        // A clean, well-kept home never rolls, even on a seed and day where its roll would say leave.
        UUID clean = property(3, 50, 100);
        resident(5, clean, 0, 50, null, null);
        long seed = seedWhere(List.of(clean), DAY, 50, 1);
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, List.of(target(clean)), CONDITION).departed());
        openIncident(clean, DAY.minusDays(2));
        assertEquals(1, repo(seed, DAY).runDay(DAY, List.of(target(clean)), CONDITION).departed().size(), "an overdue incident is neglect");
    }

    @Test void atMostThreeDeparturesPerServerAndOnePerHomeEachDay() throws Exception {
        List<UUID> homes = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            UUID home = property(n, 10, 150); homes.add(home);
            resident(n * 10, home, 0, 0, DAY.toString(), null); resident(n * 10 + 1, home, 1, 0, DAY.toString(), null);
        }
        long seed = seedWhere(homes, DAY, 0, 4);
        Result result = dayAndReplay(seed, DAY, homes.stream().map(VoluntaryDepartureRepositoryIT::target).toList(), MOODS);
        assertEquals(3, result.departed().size());
        assertEquals(3, result.departed().stream().map(VoluntaryDepartureRepository.Departed::propertyId).distinct().count());
        var logic = new SimulationLogic(new DailyRoll(seed));
        List<UUID> expected = homes.stream().sorted(java.util.Comparator.comparing(UUID::toString))
                .filter(h -> logic.leavesToday(h, DAY.toString(), 0, false)).limit(3).toList();
        assertEquals(expected, result.departed().stream().map(VoluntaryDepartureRepository.Departed::propertyId).toList());
        assertEquals(9, homes.stream().mapToInt(h -> { try { return housed(h); } catch (SQLException e) { throw new RuntimeException(e); } }).sum());
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, homes.stream().map(VoluntaryDepartureRepositoryIT::target).toList(),
                new Policy(true, 3, 7, 40, 50)).departed(), "server cap reached for the day");
        assertThrows(IllegalArgumentException.class, () -> new Policy(true, 4, 7, 40, 50));
        assertEquals(List.of(), repo(seed, DAY.plusDays(1)).runDay(DAY.plusDays(1), List.of(), new Policy(true, 0, 7, 40, 50)).departed());
    }

    @Test void rentAlreadyReservedRecentMovesAndCooldownsAreNeverTouched() throws Exception {
        UUID home = property(1, 10, 150);
        UUID cohort = resident(1, home, 0, 0, DAY.toString(), null);
        UUID cooling = resident(2, home, 1, 0, DAY.toString(), DAY.plusDays(1).toString());
        long seed = seedWhere(List.of(home), DAY, 0, 1);
        try (Connection c = db.getConnection()) {
            exec(c, "SET FOREIGN_KEY_CHECKS=0");
            exec(c, "INSERT INTO npc_rent_reservations(property_id,economy_day,event_id,utility_event_id) VALUES(?,?,1,1)", home, DAY.toString());
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, List.of(target(home)), MOODS).departed(), "today's rent is reserved");
        try (Connection c = db.getConnection()) {
            exec(c, "DELETE FROM npc_rent_reservations");
            exec(c, "SET FOREIGN_KEY_CHECKS=0");
            exec(c, "INSERT INTO npc_rent_cohort(resident_id,economy_day,event_id) VALUES(?,?,1)", cohort, DAY.toString());
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, List.of(target(home)), MOODS).departed(),
                "one resident's rent is reserved today and the other is in cooldown");
        assertEquals(2, housed(home));
        LocalDate later = DAY.plusDays(1);
        long next = seedWhere(List.of(home), later, 0, 1);
        var result = repo(next, later).runDay(later, List.of(target(home)), MOODS);
        assertEquals(1, result.departed().size(), "cooldown over and no rent reserved on the next day");
        assertTrue(List.of(cohort, cooling).contains(result.departed().getFirst().residentId()));
    }

    @Test void changedOwnershipOrDisabledHomesAreSkipped() throws Exception {
        UUID home = property(1, 10, 150);
        resident(1, home, 0, 0, DAY.toString(), null);
        long seed = seedWhere(List.of(home), DAY, 0, 1);
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, List.of(new Target(home, OWNER, 1)), MOODS).departed());
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, List.of(new Target(home, PLAYER, 0)), MOODS).departed());
        try (Connection c = db.getConnection()) { exec(c, "UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", home); }
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, List.of(target(home)), MOODS).departed());
        assertEquals(1, housed(home));
        try (Connection c = db.getConnection()) { exec(c, "UPDATE npc_properties SET enabled=TRUE WHERE property_id=?", home); }
        assertEquals(1, repo(seed, DAY).runDay(DAY, List.of(target(home)), MOODS).departed().size(), "positive control");
    }

    @Test void moodsNeverRecordedMeansNoEvidenceAndNoDeparture() throws Exception {
        UUID home = property(1, 10, 150);
        resident(1, home, 0, 0, null, null);
        long seed = seedWhere(List.of(home), DAY, 0, 1);
        assertEquals(List.of(), repo(seed, DAY).runDay(DAY, List.of(target(home)), MOODS).departed());
        try (Connection c = db.getConnection()) { exec(c, "UPDATE npc_tenants SET mood_day=?", DAY.toString()); }
        assertEquals(1, repo(seed, DAY).runDay(DAY, List.of(target(home)), MOODS).departed().size(), "positive control");
    }

    @Test void voluntaryCauseCarriesNoPurchaseAndTheSchemaAgrees() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new TenancyDeparture(id(1), id(2), OWNER, 0, 0, 0,
                Instant.parse("2026-10-13T12:00:00Z"), TenancyDeparture.Cause.VOLUNTARY, 5L));
        UUID home = property(1, 10, 150);
        UUID r = resident(1, home, 0, 0, DAY.toString(), null);
        try (Connection c = db.getConnection()) {
            exec(c, "SET FOREIGN_KEY_CHECKS=0");
            assertThrows(SQLException.class, () -> exec(c, "INSERT INTO npc_tenancy_departures(resident_id,occupancy_revision_before,"
                    + "occupancy_revision_after,property_id,owner_at_departure,ownership_epoch,slot,departed_at,cause,purchase_event_id,payload_sha256)"
                    + " VALUES(?,0,1,?,?,0,0,NOW(3),'VOLUNTARY',5,?)", r, home, OWNER, "a".repeat(64)));
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
    }

    /** First seed where at least {@code wanted} of the homes roll leave at {@code score} on {@code day}. */
    private static long seedWhere(List<UUID> homes, LocalDate day, int score, int wanted) {
        for (long seed = 1; seed < 100_000; seed++) {
            var logic = new SimulationLogic(new DailyRoll(seed));
            if (homes.stream().filter(h -> logic.leavesToday(h, day.toString(), score, false)).count() >= wanted) return seed;
        }
        throw new AssertionError("no seed found");
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
}
