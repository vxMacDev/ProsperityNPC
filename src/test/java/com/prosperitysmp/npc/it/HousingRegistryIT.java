package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.market.HousingRegistry.Member;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Finite authoring storage only: admission must not perform tenancy, money, or memory work. */
@Timeout(45)
class HousingRegistryIT {
    private static final String V9 = "/db/migration/V9__housing_registry.sql";
    private static final UUID RESIDENT = new UUID(0, 17);
    private static final UUID WORLD = UUID.fromString("fedcba98-7654-3210-89ab-cdef01234567");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final HousingPolicy POLICY = new HousingPolicy(
            WORLD, "downtown", "room", HousingPolicy.Tier.STANDARD, 1200, 40);
    private static final String INSERT_MEMBER = """
            INSERT INTO npc_housing_members (resident_id,world_uuid,market_id,class_key,tier,
              max_daily_rent_cents,minimum_condition,available_since,row_version,enabled)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """;
    private static final List<String> SIDE_TABLES = List.of(
            "npc_resident_profiles", "npc_resident_memory", "npc_tenants", "npc_properties",
            "npc_property_funding", "npc_economy_events");
    private static DB mariaDb;
    private static Database database;
    private static String schemaName;
    private HousingRegistry registry;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schemaName = "npc_housing_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schemaName);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L));
            applyResource("/db/migration/V1__initial_schema.sql");
            applyResource("/db/migration/V2__resident_identity.sql");
            applyResource(V9);
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        dropWriteGuards();
        for (String table : List.of("npc_housing_members", "npc_resident_memory", "npc_resident_profiles",
                "npc_tenants", "npc_economy_events", "npc_property_funding", "npc_properties",
                "npc_housing_registry_lock")) {
            execute("DELETE FROM " + table);
        }
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        registry = new HousingRegistry(database);
        register(RESIDENT);
    }

    @AfterEach
    void dropWriteGuards() throws SQLException {
        if (database != null && database.isConnected()) {
            for (String action : List.of("insert", "update", "delete")) {
                execute("DROP TRIGGER IF EXISTS housing_profile_guard_" + action);
            }
            execute("DROP TRIGGER IF EXISTS housing_member_update_guard");
        }
    }

    @Test
    void emptyReadsAndUnknownAdmissionNeverFabricateAProfileEvenForAnExistingTenant() throws SQLException {
        UUID unknown = new UUID(0, 18);
        execute("INSERT INTO npc_tenants(tenant_id,display_name,archetype) VALUES (?,'Tenant only','GENERIC')", unknown);
        var before = snapshot();
        assertTrue(registry.find(RESIDENT).isEmpty());
        assertTrue(registry.find(unknown).isEmpty());
        assertTrue(registry.list().isEmpty());
        assertRejectedUnchanged(() -> registry.admit(unknown, POLICY, DAY));
        assertTrue(new ResidentRepository(database).find(unknown).isEmpty());
        assertEquals(before, snapshot());
    }

    @Test
    void admissionPersistsExactTypedPayloadAndAllowsFutureAvailability() throws SQLException {
        LocalDate future = LocalDate.of(9999, 12, 31);
        var before = sideRows();
        assertTrue(registry.admit(RESIDENT, POLICY, future));
        Member expected = new Member(RESIDENT, POLICY, future, 0, true);
        HousingRegistry reopened = new HousingRegistry(database);
        assertEquals(expected, reopened.find(RESIDENT).orElseThrow());
        assertEquals(List.of(expected), reopened.list());
        Map<String, String> stored = memberRows().getFirst();
        assertEquals(HexFormat.of().formatHex(Uuids.toBytes(RESIDENT)), stored.get("resident_id"));
        assertEquals(HexFormat.of().formatHex(Uuids.toBytes(WORLD)), stored.get("world_uuid"));
        assertEquals("9999-12-31", stored.get("available_since"));
        assertEquals(before, sideRows());
    }

    @Test
    void exactTypedRetriesAreWriteFreeAndNeverReenableOrResetRevision() throws SQLException {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        HousingPolicy equalPolicy = new HousingPolicy(WORLD, "downtown", "room", HousingPolicy.Tier.STANDARD, 1200, 40);
        assertNotSame(POLICY, equalPolicy);
        for (boolean enabled : List.of(true, false)) {
            execute("UPDATE npc_housing_members SET enabled=?,row_version=7 WHERE resident_id=?", enabled, RESIDENT);
            var before = snapshot();
            execute("""
                    CREATE TRIGGER housing_member_update_guard BEFORE UPDATE ON npc_housing_members FOR EACH ROW
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='even a no-change membership update is forbidden'
                    """);
            try {
                assertFalse(new HousingRegistry(database).admit(RESIDENT, equalPolicy, DAY));
                assertEquals(new Member(RESIDENT, POLICY, DAY, 7, enabled), registry.setEnabled(RESIDENT, 7, enabled));
                assertEquals(before, snapshot());
            } finally {
                execute("DROP TRIGGER housing_member_update_guard");
            }
        }
    }

    @Test
    void everyPolicyFieldAndAvailabilityAreImmutableOnRetryIncludingDisabledMembers() throws SQLException {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        List<HousingPolicy> conflicts = List.of(
                new HousingPolicy(new UUID(0, 1), "downtown", "room", POLICY.tier(), 1200, 40),
                new HousingPolicy(WORLD, "uptown", "room", POLICY.tier(), 1200, 40),
                new HousingPolicy(WORLD, "downtown", "apartment", POLICY.tier(), 1200, 40),
                new HousingPolicy(WORLD, "downtown", "room", HousingPolicy.Tier.PREMIUM, 1200, 40),
                new HousingPolicy(WORLD, "downtown", "room", POLICY.tier(), 1201, 40),
                new HousingPolicy(WORLD, "downtown", "room", POLICY.tier(), 1200, 41));
        for (boolean enabled : List.of(true, false)) {
            execute("UPDATE npc_housing_members SET enabled=?,row_version=9", enabled);
            for (HousingPolicy conflict : conflicts) {
                assertRejectedUnchanged(() -> registry.admit(RESIDENT, conflict, DAY));
            }
            assertRejectedUnchanged(() -> registry.admit(RESIDENT, POLICY, DAY.plusDays(1)));
        }
    }

    @Test
    void admissionAndTogglesNeverWriteIdentityEvenUnchangedOrTouchExistingSideState() throws SQLException {
        execute("UPDATE npc_resident_profiles SET name='Authored Identity',household='Chosen House',"
                + "catalogue_version=2,generation=8,wardrobe_work='authored/work' WHERE resident_id=?", RESIDENT);
        UUID property = new UUID(0, 800);
        execute("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents,owner_uuid,enabled)
                VALUES (?,?,'fixture','downtown','room',1,1000,1200,?,TRUE)
                """, property, WORLD, new UUID(0, 900));
        execute("""
                INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,tier,archetype,mood,satisfaction,
                  move_cooldown_until_day,schedule_template,state,row_version)
                VALUES (?,'Preserved Tenant',?,0,'PREMIUM','ARTISAN','HAPPY',73,'9999-12-31','NIGHT_SHIFT','WORKING',19)
                """, RESIDENT, property);
        execute("""
                INSERT INTO npc_property_funding(property_id,one_time_seed_cents,successful_cash_sinks_cents,
                  successful_cash_payouts_cents) VALUES (?,5000,4321,1234)
                """, property);
        execute("""
                INSERT INTO npc_economy_events(idempotency_key,requested_amount_cents,paid_amount_cents,source,
                  direction,property_id,counterparty_uuid,economy_day,state)
                VALUES ('housing-preservation',1200,1200,'RENT','PAYOUT',?,?,'2026-09-07','PAID')
                """, property, RESIDENT);
        new ResidentRepository(database).introduce(RESIDENT, new UUID(0, 901), Instant.parse("2026-09-07T12:00:00Z"), DAY);
        var before = sideRows();
        for (String action : List.of("insert", "update", "delete")) {
            execute("CREATE TRIGGER housing_profile_guard_" + action + " BEFORE " + action.toUpperCase(Locale.ROOT)
                    + " ON npc_resident_profiles FOR EACH ROW SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT='housing must never write a resident profile'");
        }
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        assertFalse(registry.admit(RESIDENT, POLICY, DAY));
        assertEquals(new Member(RESIDENT, POLICY, DAY, 1, false), registry.setEnabled(RESIDENT, 0, false));
        assertFalse(registry.admit(RESIDENT, POLICY, DAY));
        assertEquals(new Member(RESIDENT, POLICY, DAY, 2, true), registry.setEnabled(RESIDENT, 1, true));
        assertEquals(before, sideRows());
    }

    @Test
    void canonicalDateAndPolicyEndpointsRoundTripWithoutSqlDateCoercion() throws SQLException {
        List<LocalDate> days = List.of(LocalDate.of(1, 1, 1), LocalDate.of(2000, 2, 29), LocalDate.of(9999, 12, 31));
        for (int i = 0; i < days.size(); i++) {
            UUID id = new UUID(0, 100 + i);
            register(id);
            HousingPolicy policy = new HousingPolicy(WORLD, "a" + "0".repeat(31), "z" + "_".repeat(31),
                    HousingPolicy.Tier.PREMIUM, i == 0 ? 1 : Integer.MAX_VALUE, i == 0 ? 0 : 100);
            assertTrue(registry.admit(id, policy, days.get(i)));
            assertEquals(new Member(id, policy, days.get(i), 0, true), registry.find(id).orElseThrow());
            assertEquals(days.get(i).toString(), rows(
                    "SELECT available_since FROM npc_housing_members WHERE resident_id=?", id).getFirst().get("available_since"));
        }
    }

    @Test
    void invalidAdmissionDatesAndNullArgumentsRejectWithoutWrites() throws SQLException {
        for (LocalDate invalid : List.of(LocalDate.MIN, LocalDate.of(-1, 1, 1), LocalDate.of(0, 1, 1),
                LocalDate.of(10000, 1, 1), LocalDate.MAX)) {
            assertRejectedUnchanged(IllegalArgumentException.class, () -> registry.admit(RESIDENT, POLICY, invalid));
        }
        assertRejectedUnchanged(NullPointerException.class, () -> registry.admit(null, POLICY, DAY));
        assertRejectedUnchanged(NullPointerException.class, () -> registry.admit(RESIDENT, null, DAY));
        assertRejectedUnchanged(NullPointerException.class, () -> registry.admit(RESIDENT, POLICY, null));
        assertRejectedUnchanged(NullPointerException.class, () -> registry.find(null));
        assertRejectedUnchanged(NullPointerException.class, () -> registry.setEnabled(null, 0, false));
        assertTrue(registry.admit(RESIDENT, POLICY, DAY), "invalid requests must not poison the pool");
    }

    @Test
    void listingIncludesDisabledMembersInStableBinaryUuidOrder() throws SQLException {
        List<UUID> ordered = List.of(new UUID(0, 1), new UUID(0, Long.MAX_VALUE), new UUID(0, Long.MIN_VALUE),
                new UUID(Long.MAX_VALUE, 0), new UUID(Long.MIN_VALUE, 0), new UUID(-1, -1));
        for (UUID id : ordered.reversed()) {
            register(id);
            assertTrue(registry.admit(id, POLICY, DAY));
        }
        UUID disabled = ordered.get(2);
        registry.setEnabled(disabled, 0, false);
        var before = snapshot();
        List<Member> expected = ordered.stream().map(id -> new Member(id, POLICY, DAY,
                id.equals(disabled) ? 1 : 0, !id.equals(disabled))).toList();
        assertEquals(expected, registry.list());
        assertEquals(expected, new HousingRegistry(database).list());
        assertEquals(expected.get(2), registry.find(disabled).orElseThrow());
        assertEquals(before, snapshot());
    }

    @Test
    void enabledCasChangesOnlyFlagAndRevisionAndNoopsDoNotIncrement() throws SQLException {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        UUID neighbor = new UUID(0, 19);
        register(neighbor);
        assertTrue(registry.admit(neighbor, POLICY, DAY));
        var sides = sideRows();
        var neighborBefore = rows("SELECT * FROM npc_housing_members WHERE resident_id=?", neighbor);
        Map<String, String> before = rows("SELECT * FROM npc_housing_members WHERE resident_id=?", RESIDENT).getFirst();
        assertEquals(new Member(RESIDENT, POLICY, DAY, 0, true), registry.setEnabled(RESIDENT, 0, true));
        assertEquals(before, rows("SELECT * FROM npc_housing_members WHERE resident_id=?", RESIDENT).getFirst());
        for (int revision = 1; revision <= 2; revision++) {
            boolean enabled = revision == 2;
            Member expected = new Member(RESIDENT, POLICY, DAY, revision, enabled);
            assertEquals(expected, registry.setEnabled(RESIDENT, revision - 1, enabled));
            assertEquals(expected, new HousingRegistry(database).setEnabled(RESIDENT, revision, enabled));
            Map<String, String> changed = new LinkedHashMap<>(before);
            changed.put("enabled", enabled ? "1" : "0");
            changed.put("row_version", Integer.toString(revision));
            assertEquals(changed, rows("SELECT * FROM npc_housing_members WHERE resident_id=?", RESIDENT).getFirst());
            assertEquals(expected, registry.find(RESIDENT).orElseThrow());
        }
        assertEquals(neighborBefore, rows("SELECT * FROM npc_housing_members WHERE resident_id=?", neighbor));
        assertEquals(sides, sideRows());
    }

    @Test
    void missingInvalidStaleAndFutureCasRejectEvenWhenRequestedFlagAlreadyMatches() throws SQLException {
        assertRejectedUnchanged(() -> registry.setEnabled(RESIDENT, 0, true));
        assertRejectedUnchanged(() -> registry.setEnabled(new UUID(0, 404), 0, false));
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        execute("UPDATE npc_housing_members SET enabled=FALSE,row_version=1 WHERE resident_id=?", RESIDENT);
        for (long revision : new long[]{Long.MIN_VALUE, -1, 0, 2, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            Class<? extends Exception> type = revision < 0 || revision == Long.MAX_VALUE
                    ? IllegalArgumentException.class : SQLException.class;
            assertRejectedUnchanged(type, () -> registry.setEnabled(RESIDENT, revision, false));
            assertRejectedUnchanged(type, () -> registry.setEnabled(RESIDENT, revision, true));
        }
    }

    @Test
    void finalRevisionAdvanceSucceedsOnceAndExhaustedNoopsAndExactRetriesRemainValid() throws SQLException {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        execute("UPDATE npc_housing_members SET row_version=? WHERE resident_id=?", Long.MAX_VALUE - 2, RESIDENT);
        Member terminal = new Member(RESIDENT, POLICY, DAY, Long.MAX_VALUE - 1, false);
        assertEquals(terminal, registry.setEnabled(RESIDENT, Long.MAX_VALUE - 2, false));
        var before = snapshot();
        assertEquals(terminal, registry.setEnabled(RESIDENT, Long.MAX_VALUE - 1, false));
        assertFalse(registry.admit(RESIDENT, POLICY, DAY));
        assertRejectedUnchanged(() -> registry.setEnabled(RESIDENT, Long.MAX_VALUE - 1, true));
        assertEquals(terminal, registry.find(RESIDENT).orElseThrow());
        assertEquals(List.of(terminal), registry.list());
        assertEquals(before, snapshot());
    }

    @Test
    void concurrentExactAdmissionWaitsForSingletonAndCommitsExactlyOnce() throws Exception {
        var before = sideRows();
        List<Boolean> results = behindRegistryLock(
                () -> registry.admit(RESIDENT, POLICY, DAY),
                () -> new HousingRegistry(database).admit(RESIDENT, POLICY, DAY));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(List.of(new Member(RESIDENT, POLICY, DAY, 0, true)), registry.list());
        assertEquals(before, sideRows());
    }

    @Test
    void concurrentDifferentPolicyOrDateHasOneWinnerAndOnePayloadConflict() throws Exception {
        HousingPolicy different = new HousingPolicy(WORLD, "downtown", "room", HousingPolicy.Tier.PREMIUM, 1200, 40);
        for (boolean dateConflict : List.of(false, true)) {
            execute("DELETE FROM npc_housing_members");
            HousingPolicy otherPolicy = dateConflict ? POLICY : different;
            LocalDate otherDay = dateConflict ? DAY.plusDays(1) : DAY;
            var before = sideRows();
            List<Boolean> results = behindRegistryLock(
                    () -> admitOrReject(RESIDENT, POLICY, DAY, "Housing admission payload conflict"),
                    () -> admitOrReject(RESIDENT, otherPolicy, otherDay, "Housing admission payload conflict"));
            assertNotEquals(results.getFirst(), results.getLast());
            Member expected = results.getFirst() ? new Member(RESIDENT, POLICY, DAY, 0, true)
                    : new Member(RESIDENT, otherPolicy, otherDay, 0, true);
            assertEquals(List.of(expected), registry.list());
            assertEquals(before, sideRows());
        }
    }

    @Test
    void concurrentCasUsesSingletonAndRejectsStaleSecondWriterInsteadOfTreatingItAsANoop() throws Exception {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        var before = sideRows();
        Callable<Boolean> change = () -> {
            try {
                assertEquals(new Member(RESIDENT, POLICY, DAY, 1, false),
                        new HousingRegistry(database).setEnabled(RESIDENT, 0, false));
                return true;
            } catch (SQLException rejection) {
                assertDomainSql(rejection, "Housing membership changed");
                return false;
            }
        };
        List<Boolean> results = behindRegistryLock(change, change);
        assertNotEquals(results.getFirst(), results.getLast());
        assertEquals(List.of(new Member(RESIDENT, POLICY, DAY, 1, false)), registry.list());
        assertEquals(before, sideRows());
    }

    @Test
    void full512MemberRegistryCountsDisabledAndRetainsRetriesButRejectsThe513thProfile() throws SQLException {
        assertEquals(512, HousingRegistry.MAX_MEMBERS);
        List<UUID> cohort = seedMembers(512);
        execute("UPDATE npc_housing_members SET enabled=FALSE");
        assertEquals(513, rows("SELECT resident_id FROM npc_resident_profiles").size());
        List<Member> expected = cohort.stream().map(id -> new Member(id, POLICY, DAY, 0, false)).toList();
        var before = snapshot();
        assertEquals(expected, registry.list(), "the full cohort must not inherit the profile reader's 100-row bound");
        assertFalse(registry.admit(cohort.getFirst(), POLICY, DAY));
        assertEquals(expected.getLast(), registry.setEnabled(cohort.getLast(), 0, false));
        assertRejectedUnchanged(() -> registry.admit(RESIDENT, POLICY, DAY));
        assertTrue(registry.find(RESIDENT).isEmpty());
        assertEquals(before, snapshot());
    }

    @Test
    void concurrentAdmissionsForLastSlotCannotExceed512IncludingDisabledMembers() throws Exception {
        seedMembers(511);
        execute("UPDATE npc_housing_members SET enabled=FALSE");
        UUID other = new UUID(0, 18);
        register(other);
        var before = sideRows();
        List<Boolean> results = behindRegistryLock(
                () -> admitOrReject(RESIDENT, POLICY, DAY, "Housing registry capacity reached or invalid"),
                () -> admitOrReject(other, POLICY, DAY, "Housing registry capacity reached or invalid"));
        assertNotEquals(results.getFirst(), results.getLast());
        assertEquals(512, memberRows().size());
        assertEquals(512, registry.list().size());
        UUID winner = results.getFirst() ? RESIDENT : other;
        UUID loser = results.getFirst() ? other : RESIDENT;
        assertEquals(new Member(winner, POLICY, DAY, 0, true), registry.find(winner).orElseThrow());
        assertTrue(registry.find(loser).isEmpty());
        assertEquals(before, sideRows());
    }

    @Test
    void outOfBand513thMemberMakesListFailClosedInsteadOfReturningAPrefix() throws SQLException {
        List<UUID> cohort = seedMembers(513);
        var before = snapshot();
        assertEquals(513, memberRows().size());
        assertThrows(SQLException.class, registry::list);
        assertEquals(new Member(cohort.getLast(), POLICY, DAY, 0, true), registry.find(cohort.getLast()).orElseThrow());
        assertRejectedUnchanged(() -> registry.admit(RESIDENT, POLICY, DAY));
        assertEquals(before, snapshot());
    }

    @Test
    void missingSeededSingletonFailsClosedForNewAdmissionRetriesAndEveryToggle() throws SQLException {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        UUID other = new UUID(0, 18);
        register(other);
        execute("DELETE FROM npc_housing_registry_lock WHERE singleton_id=1");
        assertRejectedUnchanged(() -> registry.admit(other, POLICY, DAY));
        assertRejectedUnchanged(() -> registry.admit(RESIDENT, POLICY, DAY));
        assertRejectedUnchanged(() -> registry.setEnabled(RESIDENT, 0, true));
        assertRejectedUnchanged(() -> registry.setEnabled(RESIDENT, 0, false));
        assertTrue(rows("SELECT * FROM npc_housing_registry_lock").isEmpty(), "writers must not repair the missing lock");
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        assertTrue(registry.admit(other, POLICY, DAY));
    }

    @Test
    void invalidStoredPolicyStateRevisionAndNoncanonicalDatesFailClosedOnEveryMemberReader() throws SQLException {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        List<String> corruptions = List.of(
                "market_id='Downtown'", "market_id='bad/key'", "class_key='Room'", "class_key=''",
                "tier='standard'", "tier='UNKNOWN'", "max_daily_rent_cents=0", "max_daily_rent_cents=2147483648",
                "minimum_condition=-1", "minimum_condition=101", "enabled=-1", "enabled=2",
                "row_version=-1", "row_version=-9223372036854775808", "row_version=9223372036854775807",
                "available_since=''", "available_since='0000-01-01'", "available_since='2026-02-29'",
                "available_since='2026-02-30'", "available_since='2026-13-01'", "available_since='2026-01-00'",
                "available_since='2026-9-07'", "available_since='2026-09-7'", "available_since='2026/09/07'");
        for (String assignment : corruptions) {
            // Corrupt only this fixture connection; pooled sessions must regain CHECK enforcement.
            try (Connection c = database.getConnection()) {
                execute(c, "SET SESSION check_constraint_checks=OFF");
                try {
                    execute(c, "UPDATE npc_housing_members SET " + assignment + " WHERE resident_id=?", RESIDENT);
                } finally {
                    execute(c, "SET SESSION check_constraint_checks=ON");
                }
            }
            try {
                var before = snapshot();
                assertThrows(SQLException.class, () -> registry.find(RESIDENT), assignment);
                assertThrows(SQLException.class, registry::list, assignment);
                assertThrows(SQLException.class, () -> registry.admit(RESIDENT, POLICY, DAY), assignment);
                assertThrows(SQLException.class, () -> registry.setEnabled(RESIDENT, 0, true), assignment);
                assertThrows(SQLException.class, () -> registry.setEnabled(RESIDENT, 0, false), assignment);
                assertEquals(before, snapshot(), assignment);
            } finally {
                execute("DELETE FROM npc_housing_members WHERE resident_id=?", RESIDENT);
                insertMember(RESIDENT, true);
            }
        }
    }

    @Test
    void v9RepeatPreservesMembershipAndIdentityAndSeedsExactlyOneBinaryAsciiRegistry() throws Exception {
        assertTrue(registry.admit(RESIDENT, POLICY, DAY));
        registry.setEnabled(RESIDENT, 0, false);
        var before = snapshot();
        applyResource(V9);
        applyResource(V9);
        assertEquals(before, snapshot());
        assertEquals(List.of(Map.of("singleton_id", "1")), rows("SELECT * FROM npc_housing_registry_lock"));
        for (String column : List.of("market_id", "class_key", "tier", "available_since")) {
            assertEquals(List.of(Map.of("charset_name", "ascii", "collation_name", "ascii_bin")), rows(
                    "SELECT CHARACTER_SET_NAME AS charset_name,COLLATION_NAME AS collation_name "
                            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='npc_housing_members' "
                            + "AND COLUMN_NAME=?", schemaName, column));
        }
        for (String column : List.of("resident_id", "world_uuid")) {
            assertEquals(List.of(Map.of("data_type", "binary", "byte_length", "16")), rows(
                    "SELECT DATA_TYPE AS data_type,CHARACTER_OCTET_LENGTH AS byte_length FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='npc_housing_members' AND COLUMN_NAME=?", schemaName, column));
        }
        for (String assignment : List.of("market_id='Downtown'", "class_key='Room'", "tier='standard'",
                "enabled=2", "row_version=-1", "row_version=9223372036854775807")) {
            assertRejectedUnchanged(() -> execute("UPDATE npc_housing_members SET " + assignment + " WHERE resident_id=?", RESIDENT));
        }
        assertRejectedUnchanged(() -> execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (2)"));
        assertRejectedUnchanged(() -> execute("DELETE FROM npc_resident_profiles WHERE resident_id=?", RESIDENT));
        execute("DELETE FROM npc_housing_registry_lock");
        var members = memberRows();
        applyResource(V9);
        assertEquals(List.of(Map.of("singleton_id", "1")), rows("SELECT * FROM npc_housing_registry_lock"));
        assertEquals(members, memberRows());
    }

    private static void register(UUID resident) throws SQLException {
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(resident, "Resident " + resident)));
    }

    private static List<UUID> seedMembers(int count) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = new UUID(0, 1000 + i);
            register(id);
            ids.add(id);
        }
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT_MEMBER)) {
            ps.setQueryTimeout(5);
            c.setAutoCommit(false);
            try {
                for (UUID id : ids) {
                    bindMember(ps, id, true);
                    ps.addBatch();
                }
                assertEquals(count, ps.executeBatch().length);
                c.commit();
            } catch (SQLException | RuntimeException | AssertionError failure) {
                c.rollback();
                throw failure;
            }
        }
        return List.copyOf(ids);
    }

    private static void insertMember(UUID id, boolean enabled) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT_MEMBER)) {
            ps.setQueryTimeout(3);
            bindMember(ps, id, enabled);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void bindMember(PreparedStatement ps, UUID id, boolean enabled) throws SQLException {
        bind(ps, id, WORLD, POLICY.marketId(), POLICY.classKey(), POLICY.tier().name(),
                POLICY.maxDailyRentCents(), POLICY.minimumCondition(), DAY.toString(), 0L, enabled);
    }

    private static boolean admitOrReject(UUID id, HousingPolicy policy, LocalDate day, String message) throws SQLException {
        try {
            assertTrue(new HousingRegistry(database).admit(id, policy, day), "different payloads are never exact retries");
            return true;
        } catch (SQLException rejection) {
            assertDomainSql(rejection, message);
            return false;
        }
    }

    private static void assertDomainSql(SQLException rejection, String message) {
        assertFalse(rejection instanceof SQLTimeoutException, "a timeout is not a serialized domain rejection");
        assertNotEquals(1205, rejection.getErrorCode(), "lock wait timeout");
        assertNotEquals(1213, rejection.getErrorCode(), "deadlock");
        assertEquals(message, rejection.getMessage());
    }

    private static <T> List<T> behindRegistryLock(Callable<T> left, Callable<T> right) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<T> a;
            Future<T> b;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = blocker.prepareStatement(
                            "SELECT singleton_id FROM npc_housing_registry_lock WHERE singleton_id=1 FOR UPDATE")) {
                        ps.setQueryTimeout(3);
                        try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
                    }
                    a = workers.submit(left);
                    b = workers.submit(right);
                    assertRegistryLockWaiters();
                    assertFalse(a.isDone());
                    assertFalse(b.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "registry workers terminated");
        }
    }

    private static void assertRegistryLockWaiters() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<Map<String, String>> queries = List.of();
        while (System.nanoTime() < deadline) {
            queries = rows("SELECT INFO FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND DB=? "
                    + "AND INFO LIKE '%npc_housing_registry_lock%'", schemaName);
            if (queries.size() == 2) {
                for (Map<String, String> query : queries) {
                    String sql = query.get("INFO").stripLeading().toLowerCase(Locale.ROOT).replaceFirst(
                            "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
                    assertTrue(sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*"),
                            "both workers must block on the singleton locking read: " + sql);
                }
                return;
            }
            Thread.sleep(10);
        }
        fail("expected two singleton locking readers, saw " + queries);
    }

    private static void assertRejectedUnchanged(Executable operation) throws SQLException {
        assertRejectedUnchanged(SQLException.class, operation);
    }

    private static void assertRejectedUnchanged(Class<? extends Exception> type, Executable operation) throws SQLException {
        var before = snapshot();
        Exception rejection = assertThrows(type, operation);
        if (rejection instanceof SQLException sql) {
            assertFalse(sql instanceof SQLTimeoutException);
            assertNotEquals(1205, sql.getErrorCode());
            assertNotEquals(1213, sql.getErrorCode());
        }
        assertEquals(before, snapshot(), "rejected operation must leave membership, identity, and side state unchanged");
    }

    private static Map<String, List<Map<String, String>>> snapshot() throws SQLException {
        Map<String, List<Map<String, String>>> result = sideRows();
        result.put("npc_housing_members", memberRows());
        result.put("npc_housing_registry_lock", rows("SELECT * FROM npc_housing_registry_lock ORDER BY singleton_id"));
        return result;
    }

    private static Map<String, List<Map<String, String>>> sideRows() throws SQLException {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (String table : SIDE_TABLES) {
            result.put(table, rows("SELECT * FROM " + table + (table.equals("npc_resident_memory")
                    ? " ORDER BY resident_id,player_id,source_ref" : " ORDER BY 1")));
        }
        return result;
    }

    private static List<Map<String, String>> memberRows() throws SQLException {
        return rows("SELECT * FROM npc_housing_members ORDER BY resident_id");
    }

    private static List<Map<String, String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, String>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.put(rs.getMetaData().getColumnLabel(i), value instanceof byte[] bytes
                                ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = HousingRegistryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                execute(sql);
            }
        }
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, values); }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
            else ps.setObject(i + 1, values[i]);
        }
    }
}
