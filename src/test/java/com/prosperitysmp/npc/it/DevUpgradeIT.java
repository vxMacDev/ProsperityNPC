package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real, disposable MariaDB rehearsal of the registered V5 -> V15 SQL resources.
 * No plugin startup, wallet/provider calls, runtime switches, or access to a live server.
 * HELD_DEV has no occupancy or financial rows; LEGACY_ROWS is synthetic SQL-only coverage.
 * This tests DDL recovery, not MigrationRunner.run's dispatch or the guarded updater.
 */
@Timeout(45)
class DevUpgradeIT {
    private enum Fixture { HELD_DEV, LEGACY_ROWS }

    private record Migration(int version, String description, String resource, List<String> statements) { }
    private record TableSnapshot(List<String> columns, List<String> primaryKey, List<List<String>> rows) { }

    private static final String TRACKER = "npc_schema_migrations";
    private static final String AT = "2026-09-06 22:17:00.123";
    private static final UUID WORLD = new UUID(0, 500);
    private static final UUID PLAYER = new UUID(0, 501);
    private static final UUID PROPERTY = new UUID(0, 502);
    private static final UUID GRAPH = new UUID(0, 503);
    private static final UUID ATTEMPT = new UUID(0, 504);
    private static final List<String> ADDED_TABLES = List.of(
            "npc_incident_rolls", "npc_housing_registry_lock", "npc_housing_members",
            "npc_housing_days", "npc_housing_moves", "npc_incident_service_receipts",
            "npc_utility_bills", "npc_utility_payments", "npc_tenancy_departures");
    private static final Map<String, Integer> REPLAY_WARNINGS = new TreeMap<>();
    private static DB mariaDb;
    private static String url;
    private static List<Migration> migrations;
    private static Map<String, String> cleanSchema;
    private static Method ensureTracker;
    private static long started;

    @BeforeAll
    static void bootDatabase() throws Exception {
        started = System.nanoTime();
        migrations = registeredMigrations();
        assertEquals(IntStream.rangeClosed(1, 15).boxed().toList(),
                migrations.stream().map(Migration::version).toList(), "registered V1-V15, in order");
        ensureTracker = MigrationRunner.class.getDeclaredMethod("ensureMigrationsTable", Connection.class);
        ensureTracker.setAccessible(true);
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            url = "jdbc:mariadb://localhost:" + cfg.getPort() + "/";
            try (Connection c = freshSchema()) {
                try {
                    for (Migration migration : migrations) apply(c, migration);
                    cleanSchema = schema(c);
                    System.out.println("DevUpgradeIT MariaDB=" + rows(c, "SELECT VERSION()").getFirst().getFirst()
                            + ", V15 base tables=" + cleanSchema.size() + " (includes " + TRACKER + "): "
                            + cleanSchema.keySet());
                } finally {
                    dropSchema(c);
                }
            }
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (mariaDb != null) {
            DB stopping = mariaDb;
            mariaDb = null;
            try { stopping.stop(); }
            finally {
                System.out.printf("DevUpgradeIT elapsed=%.3fs; observed replay warnings=%s%n",
                        (System.nanoTime() - started) / 1_000_000_000.0, REPLAY_WARNINGS);
            }
        }
    }

    static Stream<Arguments> upgradeBoundaries() {
        return Stream.of(Fixture.values()).flatMap(fixture -> Stream.concat(
                Stream.of(Arguments.of(fixture, 0, 0)),
                migrations.stream().filter(m -> m.version() > 5).flatMap(m ->
                        IntStream.rangeClosed(1, m.statements().size())
                                .mapToObj(prefix -> Arguments.of(fixture, m.version(), prefix)))));
    }

    @ParameterizedTest(name = "{0}: interrupt V{1} after {2} statements (V0 = clean upgrade)")
    @MethodSource("upgradeBoundaries")
    void preservesV5AcrossCleanUpgradeAndEveryPartialMigration(Fixture fixture, int interruptedVersion,
                                                              int completedStatements) throws Exception {
        try (Connection c = freshSchema()) {
            try {
                for (Migration migration : migrations) {
                    if (migration.version() <= 5) apply(c, migration);
                }
                seedV5(c, fixture);
                Map<String, TableSnapshot> before = snapshotV5(c);
                assertEquals(21, before.size(), "V5 domain tables, excluding the migration tracker");
                List<List<String>> oldTracker = rows(c, "SELECT * FROM " + TRACKER + " ORDER BY version");
                assertEquals(5, oldTracker.size());
                assertEquals("3", scalar(c, "SELECT COUNT(*) FROM npc_resident_profiles"));
                assertEquals(fixture == Fixture.HELD_DEV ? "0" : "3",
                        scalar(c, "SELECT COUNT(*) FROM npc_purchase_intents"));

                for (Migration migration : migrations) {
                    if (migration.version() <= 5) continue;
                    if (migration.version() == interruptedVersion) {
                        for (String sql : migration.statements().subList(0, completedStatements)) execute(c, sql);
                        assertEquals(Integer.toString(interruptedVersion - 1),
                                scalar(c, "SELECT MAX(version) FROM " + TRACKER),
                                "interrupted DDL is committed but its migration marker is absent");
                        assertPreserved(c, before, oldTracker);
                    }
                    // Deliberately restart at statement zero, including after the last DDL but before its marker.
                    apply(c, migration);
                    assertPreserved(c, before, oldTracker);
                    assertNoBackfill(c, before);
                }

                assertEquals(IntStream.rangeClosed(1, 15).mapToObj(i -> List.of(Integer.toString(i))).toList(),
                        rows(c, "SELECT version FROM " + TRACKER + " ORDER BY version"));
                assertEquals(migrations.stream().map(m -> List.of(m.description())).toList(),
                        rows(c, "SELECT description FROM " + TRACKER + " ORDER BY version"));
                TreeSet<String> expectedTables = new TreeSet<>(before.keySet());
                expectedTables.add(TRACKER);
                expectedTables.addAll(ADDED_TABLES);
                assertEquals(expectedTables, schema(c).keySet(), "actual V15 tables, not a guessed table count");
                assertEquals(cleanSchema, schema(c), "columns, indexes and constraints match a clean upgrade");
                assertEquals("0", scalar(c, "SELECT COUNT(*) FROM npc_purchase_intents WHERE "
                        + "repair_incident_id IS NOT NULL OR repair_type IS NOT NULL OR repair_snapshot_sha256 IS NOT NULL "
                        + "OR evict_tenancy_revision IS NOT NULL OR evict_slot IS NOT NULL"),
                        "legacy receipts must not acquire repair fingerprints or current-tenancy bindings");
            } finally {
                dropSchema(c);
            }
        }
    }

    /**
     * linked-accounts (V34) on an upgraded schema: every migration up to the latest applies on top of the V1
     * benefit-unit rows and leaves them byte for byte unchanged. V34 adds its own two tables, empty, and can be
     * replayed. The V1 tables are unused by main code and are not dropped.
     */
    @Test
    void linkedAccountsLeavesTheLegacyBenefitUnitRowsAsTheyAre() throws Exception {
        List<Migration> all = registeredMigrations(Integer.MAX_VALUE);
        assertEquals(IntStream.rangeClosed(1, all.size()).boxed().toList(), all.stream().map(Migration::version).toList(),
                "contiguous from V1");
        Migration linked = all.stream().filter(m -> m.version() == 34).findFirst().orElseThrow();
        assertEquals("linked_accounts", linked.description());
        try (Connection c = freshSchema()) {
            try {
                for (Migration migration : all) if (migration.version() <= 5) apply(c, migration);
                seedV5(c, Fixture.LEGACY_ROWS);
                List<List<String>> units = rows(c, "SELECT * FROM npc_benefit_units ORDER BY benefit_unit_id");
                List<List<String>> members = rows(c, "SELECT * FROM npc_benefit_unit_members ORDER BY member_uuid");
                assertEquals(1, units.size(), "the legacy unit row is there");
                assertEquals(1, members.size(), "the legacy member row is there");
                for (Migration migration : all) if (migration.version() > 5) apply(c, migration);
                // Replaying V34's DDL (a restart after its tables, before its marker) changes nothing.
                for (String sql : linked.statements()) execute(c, sql);
                assertEquals(units, rows(c, "SELECT * FROM npc_benefit_units ORDER BY benefit_unit_id"));
                assertEquals(members, rows(c, "SELECT * FROM npc_benefit_unit_members ORDER BY member_uuid"));
                assertEquals("0", scalar(c, "SELECT COUNT(*) FROM npc_account_links"));
                assertEquals("0", scalar(c, "SELECT COUNT(*) FROM npc_linked_account_days"));
                assertEquals(Integer.toString(all.size()), scalar(c, "SELECT MAX(version) FROM " + TRACKER));
            } finally {
                dropSchema(c);
            }
        }
    }

    /**
     * item-repairs (V35) on a fully upgraded schema: every partial run (stopped after any statement) replays to the
     * same tables, and a second replay changes nothing. The widened custody source accepts REPAIR_RETURN and the old
     * two and still refuses anything else; a hand-in cheaper than its floor or with a ratio under 100 percent is refused.
     */
    @Test
    @Timeout(240)
    void itemRepairsMigrationReplaysAfterAnyPartialRunAndKeepsItsChecks() throws Exception {
        List<Migration> all = registeredMigrations(Integer.MAX_VALUE);
        Migration items = all.stream().filter(m -> m.version() == 35).findFirst().orElseThrow();
        assertEquals("item_repairs", items.description());
        Map<String, String> clean = null;
        for (int prefix = 0; prefix <= items.statements().size(); prefix++) {
            try (Connection c = freshSchema()) {
                try {
                    for (Migration migration : all) if (migration.version() < 35) apply(c, migration);
                    for (String sql : items.statements().subList(0, prefix)) execute(c, sql);
                    apply(c, items);
                    for (String sql : items.statements()) execute(c, sql);
                    Map<String, String> schema = schema(c);
                    if (clean == null) clean = schema;
                    assertEquals(clean, schema, "interrupted after " + prefix + " statements");
                    if (prefix == items.statements().size()) assertItemRepairChecks(c);
                } finally {
                    dropSchema(c);
                }
            }
        }
    }

    /**
     * contracts-partnerships (V36) on a fully upgraded schema, after V34 and V35: every partial run replays to the same
     * tables and a second replay changes nothing. It only adds tables, so every table V35 left is unchanged, and the
     * new tables start empty. The checks refuse a supply contract to another resident and accept an honest one.
     */
    @Test
    @Timeout(300)
    void contractsMigrationReplaysAfterAnyPartialRunAndAltersNothingElse() throws Exception {
        List<Migration> all = registeredMigrations(Integer.MAX_VALUE);
        Migration contracts = all.stream().filter(m -> m.version() == 36).findFirst().orElseThrow();
        assertEquals("town_contracts_partnerships", contracts.description());
        assertEquals(38, all.getLast().version(), "V37 (partnership hand-in terms) and V38 follow it");
        Map<String, String> clean = null;
        for (int prefix = 0; prefix <= contracts.statements().size(); prefix++) {
            try (Connection c = freshSchema()) {
                try {
                    for (Migration migration : all) if (migration.version() < 36) apply(c, migration);
                    Map<String, String> before = schema(c);
                    for (String sql : contracts.statements().subList(0, prefix)) execute(c, sql);
                    apply(c, contracts);
                    for (String sql : contracts.statements()) execute(c, sql);
                    Map<String, String> schema = schema(c);
                    if (clean == null) clean = schema;
                    assertEquals(clean, schema, "interrupted after " + prefix + " statements");
                    for (var table : before.entrySet()) assertEquals(table.getValue(), schema.get(table.getKey()), table.getKey());
                    if (prefix == contracts.statements().size()) assertContractChecks(c);
                } finally {
                    dropSchema(c);
                }
            }
        }
    }

    /**
     * Partnership hand-in terms (V37) after V36: every partial run replays to the same schema and a second replay
     * changes nothing. Only npc_town_contracts changes. The check keeps the kept terms on PARTNERSHIP rows, all three
     * or none, in range.
     */
    @Test
    @Timeout(300)
    void handInTermsMigrationReplaysAfterAnyPartialRunAndAltersOnlyContracts() throws Exception {
        List<Migration> all = registeredMigrations(Integer.MAX_VALUE);
        Migration terms = all.stream().filter(m -> m.version() == 37).findFirst().orElseThrow();
        assertEquals("partnership_handin_terms", terms.description());
        assertEquals(38, all.getLast().version(), "V38 (item return staff decision) follows it");
        Map<String, String> clean = null;
        for (int prefix = 0; prefix <= terms.statements().size(); prefix++) {
            try (Connection c = freshSchema()) {
                try {
                    for (Migration migration : all) if (migration.version() < 37) apply(c, migration);
                    Map<String, String> before = schema(c);
                    for (String sql : terms.statements().subList(0, prefix)) execute(c, sql);
                    apply(c, terms);
                    for (String sql : terms.statements()) execute(c, sql);
                    Map<String, String> schema = schema(c);
                    if (clean == null) clean = schema;
                    assertEquals(clean, schema, "interrupted after " + prefix + " statements");
                    assertEquals(before.keySet(), schema.keySet(), "no table added or dropped");
                    for (var table : before.entrySet())
                        if (!table.getKey().equals("npc_town_contracts")) assertEquals(table.getValue(), schema.get(table.getKey()), table.getKey());
                    assertNotEquals(before.get("npc_town_contracts"), schema.get("npc_town_contracts"));
                    if (prefix == terms.statements().size()) assertHandInTermChecks(c);
                } finally {
                    dropSchema(c);
                }
            }
        }
    }

    /**
     * Item return staff decision (V38) after V37: every partial run replays to the same schema and a second replay changes
     * nothing. Only npc_item_custody_resolutions changes. The check takes RETURNED_BY_STAFF and still refuses anything else.
     */
    @Test
    @Timeout(300)
    void returnStaffDecisionMigrationReplaysAfterAnyPartialRunAndAltersOnlyResolutions() throws Exception {
        List<Migration> all = registeredMigrations(Integer.MAX_VALUE);
        Migration staff = all.stream().filter(m -> m.version() == 38).findFirst().orElseThrow();
        assertEquals("item_return_staff_decision", staff.description());
        assertEquals(38, all.getLast().version());
        Map<String, String> clean = null;
        for (int prefix = 0; prefix <= staff.statements().size(); prefix++) {
            try (Connection c = freshSchema()) {
                try {
                    for (Migration migration : all) if (migration.version() < 38) apply(c, migration);
                    Map<String, String> before = schema(c);
                    if (prefix == 0) assertResolutionDecisions(c, false);
                    for (String sql : staff.statements().subList(0, prefix)) execute(c, sql);
                    // A partial run still has the earlier check, so it refuses the new decision rather than taking a bad one.
                    if (prefix == 1) assertResolutionDecisions(c, false);
                    apply(c, staff);
                    for (String sql : staff.statements()) execute(c, sql);
                    Map<String, String> schema = schema(c);
                    if (clean == null) clean = schema;
                    assertEquals(clean, schema, "interrupted after " + prefix + " statements");
                    assertEquals(before.keySet(), schema.keySet(), "no table added or dropped");
                    for (var table : before.entrySet())
                        if (!table.getKey().equals("npc_item_custody_resolutions")) assertEquals(table.getValue(), schema.get(table.getKey()), table.getKey());
                    assertNotEquals(before.get("npc_item_custody_resolutions"), schema.get("npc_item_custody_resolutions"));
                    if (prefix == staff.statements().size()) assertResolutionDecisions(c, true);
                } finally {
                    dropSchema(c);
                }
            }
        }
    }

    /** Each decision on a fresh AMBIGUOUS return row, rolled back; RETURNED_BY_STAFF only when staffDecision is on. */
    private static void assertResolutionDecisions(Connection c, boolean staffDecision) throws SQLException {
        execute(c, "INSERT IGNORE INTO npc_item_custody_players VALUES (?)", PLAYER);
        for (String decision : List.of("VERIFIED_DELIVERED", "VERIFIED_NOT_DELIVERED", "RETURNED_BY_STAFF", "X")) {
            UUID nonce = UUID.randomUUID();
            execute(c, "INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,payload_sha256,"
                    + "reserved_at_millis,expires_at_millis,state,delivery_run_id,outcome_code) VALUES(?,?,?,'REPAIR_RETURN',1,'x',"
                    + "SHA2('x',256),0,900000,'RECONCILED_NOT_DELIVERED',?,'PAYLOAD_UNREADABLE')", nonce, PLAYER, UUID.randomUUID(), UUID.randomUUID());
            String insert = "INSERT INTO npc_item_custody_resolutions(request_nonce,resolution_id,operator_ref,decision,evidence_bytes,"
                    + "evidence_sha256,original_outcome) VALUES(?,?,'staff',?,'e',SHA2('e',256),'PAYLOAD_UNREADABLE')";
            boolean allowed = decision.startsWith("VERIFIED_") || (staffDecision && decision.equals("RETURNED_BY_STAFF"));
            if (allowed) execute(c, insert, nonce, UUID.randomUUID(), decision);
            else assertThrows(SQLException.class, () -> execute(c, insert, nonce, UUID.randomUUID(), decision), decision);
        }
    }

    private static void assertHandInTermChecks(Connection c) throws SQLException {
        var giver = ResidentCatalogue.betaCast().getFirst();
        execute(c, "INSERT INTO npc_resident_profiles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                giver.id(), ResidentCatalogue.VERSION, giver.name(), giver.household(), giver.generation(),
                giver.occupation().name(), giver.means().name(), giver.lifeStage().name(),
                giver.temperament().name(), giver.preference().name(), giver.wardrobe().everyday(),
                giver.wardrobe().work(), giver.wardrobe().leisure());
        // Kept terms are accepted on a partnership hand-in, and no terms on an older one or on a plain contract.
        handIn(c, giver.id(), "PARTNERSHIP", 40, 0, 7);
        handIn(c, giver.id(), "PARTNERSHIP", 0, 2, 30);
        handIn(c, giver.id(), "PARTNERSHIP", null, null, null);
        handIn(c, giver.id(), "SUPPLY", null, null, null);
        assertThrows(SQLException.class, () -> handIn(c, giver.id(), "SUPPLY", 40, 0, 7), "terms only on a partnership");
        assertThrows(SQLException.class, () -> handIn(c, giver.id(), "PARTNERSHIP", 40, 0, null), "all three or none");
        assertThrows(SQLException.class, () -> handIn(c, giver.id(), "PARTNERSHIP", null, 0, 7), "all three or none");
        assertThrows(SQLException.class, () -> handIn(c, giver.id(), "PARTNERSHIP", 40, null, 7), "all three or none");
        assertThrows(SQLException.class, () -> handIn(c, giver.id(), "PARTNERSHIP", 61, 0, 7), "fee over 60");
        assertThrows(SQLException.class, () -> handIn(c, giver.id(), "PARTNERSHIP", 0, 0, 7), "no perk");
        assertThrows(SQLException.class, () -> handIn(c, giver.id(), "PARTNERSHIP", 40, 0, 31), "term over 30");
    }

    private static void handIn(Connection c, UUID giver, String kind, Integer fee, Integer rank, Integer term) throws SQLException {
        UUID player = UUID.randomUUID();
        execute(c, "INSERT INTO npc_town_contract_players VALUES (?)", player);
        boolean partner = kind.equals("PARTNERSHIP");
        execute(c, "INSERT INTO npc_town_contracts (contract_id, player_id, kind, definition_id, title, description, giver_id, "
                + "recipient_id, material, quantity, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day, "
                + "day_slot, expires_at_millis, status, handover_run_id, partner_fee_discount_pct, partner_rank_bonus_points, "
                + "partner_term_days) VALUES (?,?,?,?,'Hand-in','Hand-in.',?,?,'IRON_INGOT',16,'TRUSTED',30,0,'2026-10-14',?,1800000,"
                + "'HANDOVER_INTENT',?,?,?,?)", UUID.randomUUID(), player, kind, partner ? "partner-start" : "wall-stone",
                giver, giver, partner ? 0 : 1, UUID.randomUUID(), fee, rank, term);
    }

    private static void assertContractChecks(Connection c) throws SQLException {
        for (String table : List.of("npc_town_contract_players", "npc_town_contracts", "npc_contract_handover_evidence",
                "npc_contract_handover_resolutions", "npc_partnerships", "npc_partnership_renewals"))
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + table), table);
        var cast = ResidentCatalogue.betaCast();
        for (var resident : cast)
            execute(c, "INSERT INTO npc_resident_profiles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    resident.id(), ResidentCatalogue.VERSION, resident.name(), resident.household(), resident.generation(),
                    resident.occupation().name(), resident.means().name(), resident.lifeStage().name(),
                    resident.temperament().name(), resident.preference().name(), resident.wardrobe().everyday(),
                    resident.wardrobe().work(), resident.wardrobe().leisure());
        execute(c, "INSERT INTO npc_town_contract_players VALUES (?)", PLAYER);
        String insert = "INSERT INTO npc_town_contracts (contract_id, player_id, kind, definition_id, title, description, giver_id, "
                + "recipient_id, material, quantity, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day, "
                + "day_slot, expires_at_millis, status) VALUES (?,?,'SUPPLY','wall-stone','Wall stone','Bring stone.',?,?,"
                + "'COBBLESTONE',64,'STRANGER',30,0,'2026-10-14',?,1800000,'ACTIVE')";
        // A supply contract back to its giver is accepted.
        execute(c, insert, UUID.randomUUID(), PLAYER, cast.get(0).id(), cast.get(0).id(), 1);
        assertThrows(SQLException.class, () -> execute(c, insert, UUID.randomUUID(), PLAYER, cast.get(0).id(), cast.get(1).id(), 2),
                "a supply contract cannot go to another resident");
    }

    private static void assertItemRepairChecks(Connection c) throws SQLException {
        execute(c, "INSERT INTO npc_item_custody_players VALUES (?)", PLAYER);
        for (String source : List.of("WORKER_OUTPUT", "MERCHANT_GOODS", "REPAIR_RETURN"))
            execute(c, "INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,payload_sha256,"
                    + "reserved_at_millis,expires_at_millis,state) VALUES(?,?,?,?,1,'x',SHA2('x',256),0,900000,'RESERVED')",
                    UUID.randomUUID(), PLAYER, UUID.randomUUID(), source);
        assertThrows(SQLException.class, () -> execute(c, "INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,"
                + "quantity,payload_bytes,payload_sha256,reserved_at_millis,expires_at_millis,state) "
                + "VALUES(?,?,?,'X',1,'x',SHA2('x',256),0,900000,'RESERVED')", UUID.randomUUID(), PLAYER, UUID.randomUUID()));
        execute(c, "INSERT INTO npc_properties (property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES (?,?,'items','town','room',1,1000,1000,?,0,80,1)",
                PROPERTY, WORLD, PLAYER);
        execute(c, "INSERT INTO npc_incidents (property_id,incident_type,cash_repair_cents,item_repair_spec,condition_damage,"
                + "economy_day,due_at) VALUES (?,'PESTS',2000,'16 paper + 8 baked_potato',5,'2026-09-06',?)", PROPERTY, AT);
        String incident = scalar(c, "SELECT MAX(incident_id) FROM npc_incidents");
        String insert = "INSERT INTO npc_item_repair_collections(collection_id,player_id,property_id,incident_id,ownership_epoch,"
                + "incident_type,repair_snapshot_sha256,quote_day,cash_cents,value_ratio_bps,required_value_cents,recipe_value_cents,"
                + "price_version,quote_sha256,state,run_id) VALUES(?,?,?,?,0,'PESTS',REPEAT('a',64),'2026-09-06',2000,?,?,?,'v1',"
                + "REPEAT('b',64),'CANCELLED',?)";
        // An honest row (ceil(2000 x 1.5) = 3000, worth 3200) is accepted.
        execute(c, insert, UUID.randomUUID(), PLAYER, PROPERTY, Long.parseLong(incident), 15000, 3000, 3200, UUID.randomUUID());
        assertThrows(SQLException.class, () -> execute(c, insert, UUID.randomUUID(), PLAYER, PROPERTY, Long.parseLong(incident),
                15000, 3000, 2999, UUID.randomUUID()), "worth less than the floor");
        assertThrows(SQLException.class, () -> execute(c, insert, UUID.randomUUID(), PLAYER, PROPERTY, Long.parseLong(incident),
                9999, 2000, 3200, UUID.randomUUID()), "ratio under 100 percent");
        assertThrows(SQLException.class, () -> execute(c, insert, UUID.randomUUID(), PLAYER, PROPERTY, Long.parseLong(incident),
                15000, 2999, 3200, UUID.randomUUID()), "required is not the exact ceiling");
    }

    private static List<Migration> registeredMigrations() throws Exception { return registeredMigrations(15); }

    private static List<Migration> registeredMigrations(int through) throws Exception {
        // Follow the other ITs: read the real registry without constructing a Bukkit JavaPlugin.
        Field field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        List<Migration> result = new ArrayList<>();
        for (Object entry : (List<?>) field.get(null)) {
            Method version = entry.getClass().getDeclaredMethod("version");
            Method description = entry.getClass().getDeclaredMethod("description");
            Method resource = entry.getClass().getDeclaredMethod("resource");
            version.setAccessible(true);
            description.setAccessible(true);
            resource.setAccessible(true);
            int number = (Integer) version.invoke(entry);
            if (number > through) continue;
            String path = "/" + resource.invoke(entry);
            try (InputStream in = DevUpgradeIT.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                List<String> statements = MigrationRunner.splitStatements(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
                assertFalse(statements.isEmpty(), path);
                result.add(new Migration(number, (String) description.invoke(entry), path, statements));
            }
        }
        return List.copyOf(result);
    }

    private static Connection freshSchema() throws Exception {
        String name = "npc_dev_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(name);
        Connection c = DriverManager.getConnection(url + name, "root", "");
        try {
            assertTrue(c.getAutoCommit(), "DDL interruption rehearsal uses MariaDB autocommit");
            execute(c, "SET time_zone = '+00:00'");
            ensureTracker.invoke(new MigrationRunner(null, null), c);
            return c;
        } catch (Exception | AssertionError failure) {
            try { dropSchema(c); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            c.close();
            throw failure;
        }
    }

    private static void dropSchema(Connection c) throws SQLException {
        String name = scalar(c, "SELECT DATABASE()");
        assertTrue(name.matches("npc_dev_upgrade_[a-f0-9]{32}"), "only drop this disposable local schema");
        execute(c, "DROP DATABASE " + quote(name));
    }

    private static void apply(Connection c, Migration migration) throws SQLException {
        for (String sql : migration.statements()) {
            try { execute(c, sql); }
            catch (SQLException failure) { throw new SQLException(migration.resource(), failure); }
        }
        execute(c, "INSERT INTO " + TRACKER + " (version, description) VALUES (?, ?)",
                migration.version(), migration.description());
    }

    private static void seedV5(Connection c, Fixture fixture) throws SQLException {
        var cast = ResidentCatalogue.betaCast();
        for (var resident : cast) {
            execute(c, "INSERT INTO npc_resident_profiles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    resident.id(), ResidentCatalogue.VERSION, resident.name(), resident.household(), resident.generation(),
                    resident.occupation().name(), resident.means().name(), resident.lifeStage().name(),
                    resident.temperament().name(), resident.preference().name(), resident.wardrobe().everyday(),
                    resident.wardrobe().work(), resident.wardrobe().leisure());
            execute(c, "INSERT INTO npc_resident_memory VALUES (?,?,'introduction','INTRODUCTION',?,?)",
                    resident.id(), PLAYER, 1788751020123L, "2026-09-06");
        }
        execute(c, "INSERT INTO npc_resident_memory VALUES (?,?,'introduction','INTRODUCTION',?,?)",
                cast.getFirst().id(), new UUID(0, 505), 1788822000456L, "2026-09-07");
        execute(c, """
                INSERT INTO npc_nav_graphs VALUES (?,?,'beta-corridor','smooth-stone',3,'PUBLISHED',?,?),
                  (?,?,'beta-draft','stone',1,'DRAFT',?,NULL)
                """, GRAPH, WORLD, AT, AT, new UUID(0, 506), WORLD, AT);
        for (int node = 1; node <= 6; node++) {
            execute(c, "INSERT INTO npc_nav_nodes VALUES (?,?,415,72,?)", node, GRAPH, -129 + node);
            if (node > 1) execute(c, "INSERT INTO npc_nav_edges VALUES (?,?,?,?,10)", node - 1, GRAPH, node - 1, node);
        }
        execute(c, "INSERT INTO npc_nav_tags VALUES (1,'spawn'),(6,'work:courier'),(1,?),(6,?)",
                "home:" + cast.getFirst().id(), "work:" + cast.get(2).id());
        execute(c, "INSERT INTO npc_audit_log (at,actor,action,detail) VALUES (?, 'residentlab', 'preview', ?)",
                AT, "Existing cast; all five main switches OFF; paused/resumed route.");
        if (fixture == Fixture.HELD_DEV) return;

        // These SQL fixtures are synthetic. They say nothing about a live server's contents or real money movement.
        execute(c, """
                INSERT INTO npc_properties VALUES
                  (?,?,'legacy-house','town','standard',2,1200,1350,?,7,67,0,?,4294967300,?),
                  (?,?,'unowned-house','town','standard',1,800,900,NULL,2,80,0,NULL,9,?)
                """, PROPERTY, WORLD, PLAYER, AT, AT, new UUID(0, 507), WORLD, AT);
        execute(c, """
                INSERT INTO npc_tenants VALUES
                  (?,'Mara Vale',?,0,'STANDARD','RESTORER','HAPPY',73,'2026-09-10','DEFAULT','IDLE',4294967301),
                  (?,'Ellis Ward',NULL,NULL,'STANDARD','SHOPKEEPER','NEUTRAL',48,NULL,'DEFAULT','IDLE',11),
                  (?,'Nico Reed',?,1,'STANDARD','COURIER','NEUTRAL',51,NULL,'DEFAULT','IDLE',13)
                """, cast.getFirst().id(), PROPERTY, cast.get(1).id(), cast.get(2).id(), PROPERTY);
        execute(c, "INSERT INTO npc_property_ownership VALUES (1,?,NULL,?,?,'legacy-arm-snapshot',7)",
                PROPERTY, PLAYER, AT);
        execute(c, "INSERT INTO npc_upgrades VALUES (1,?,'FACILITIES',2,4200,?,1)", PROPERTY, AT);
        execute(c, """
                INSERT INTO npc_incidents VALUES
                  (1,?,'PLUMBING',800,'iron_ingot:2',8,'FACILITIES','2026-09-06',?,
                    '2026-09-07 22:17:00.123',NULL,NULL,0),
                  (2,?,'PESTS',500,'wheat:4',5,NULL,'2026-09-05','2026-09-05 10:00:00.123',
                    '2026-09-06 10:00:00.123','2026-09-05 12:00:00.456',?,0)
                """, PROPERTY, AT, PROPERTY, PLAYER);
        execute(c, "INSERT INTO npc_resident_memory VALUES (?,?,'repair:legacy:2','REPAIR_COMPLETED',?,?)",
                cast.getFirst().id(), PLAYER, 1788619200456L, "2026-09-05");
        execute(c, "INSERT INTO npc_benefit_units VALUES (?,?,'legacy unit')", PLAYER, AT);
        execute(c, "INSERT INTO npc_benefit_unit_members VALUES (?,?,'MANUAL',?)", PLAYER, PLAYER, AT);
        execute(c, "INSERT INTO npc_operator_daily VALUES (1,?,'2026-09-05',1,2,1,3,2)", PLAYER);
        execute(c, "INSERT INTO npc_operator_reputation VALUES (?,63,8,?)", PLAYER, AT);
        execute(c, "INSERT INTO npc_property_funding VALUES (?,123,4294967400,987,?)", PROPERTY, AT);

        String[] kinds = {"UPGRADE", "MAINTAIN", "EVICT", "UTILITIES"};
        for (int i = 0; i < kinds.length; i++) {
            int event = 101 + i;
            boolean waiting = i == 1;
            execute(c, """
                    INSERT INTO npc_economy_events
                      (event_id,idempotency_key,requested_amount_cents,paid_amount_cents,source,direction,
                       property_id,counterparty_uuid,ownership_epoch,benefit_unit_id,economy_day,state,
                       created_at,claimed_at,finalized_at)
                    VALUES (?,?,4200,?,?,'PLAYER_TO_RESERVE',?,?,7,?,'2026-09-06',?,?,?,?)
                    """, event, "legacy:" + kinds[i].toLowerCase(java.util.Locale.ROOT), waiting ? null : 4200,
                    kinds[i], PROPERTY, PLAYER, PLAYER, waiting ? "PENDING" : "PAID", AT,
                    waiting ? null : AT, waiting ? null : "2026-09-06 22:18:00.456");
            if (i == 3) continue;
            execute(c, """
                    INSERT INTO npc_purchase_intents VALUES
                      (?,?,?,?,7,?,?,?,?,'2026-09-06',4200,?,?,?, ?,?)
                    """, event, new UUID(0, 600 + i), PROPERTY, PLAYER, kinds[i],
                    i == 0 ? "FACILITIES" : null, i == 0 ? 2 : null, i == 2 ? cast.get(1).id() : null,
                    "legacy-action:" + kinds[i], waiting ? "WAITING" : "APPLIED", waiting ? null : "APPLIED",
                    AT, waiting ? null : "2026-09-06 22:18:00.456");
        }
        execute(c, """
                INSERT INTO npc_transfer_attempts VALUES
                  (?,101,?,REPEAT('a',64),REPEAT('b',64),?,?,4200,'PAID','TRANSFER_CONFIRMED',?,?,?)
                """, ATTEMPT, new UUID(0, 700), PLAYER, new UUID(0, 701), AT, AT, AT);
        execute(c, "INSERT INTO npc_transfer_observations VALUES (?, 'DEBIT',1,1,?,?,?,0,?),"
                        + "(?, 'CREDIT',1,1,?,?,?,0,?)",
                ATTEMPT, Double.doubleToRawLongBits(42), Double.doubleToRawLongBits(100), Double.doubleToRawLongBits(58), AT,
                ATTEMPT, Double.doubleToRawLongBits(42), Double.doubleToRawLongBits(200), Double.doubleToRawLongBits(242), AT);
    }

    private static Map<String, TableSnapshot> snapshotV5(Connection c) throws SQLException {
        Map<String, TableSnapshot> result = new LinkedHashMap<>();
        for (String table : schema(c).keySet()) {
            if (table.equals(TRACKER)) continue;
            List<String> columns = rows(c, "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION", table)
                    .stream().map(List::getFirst).toList();
            List<String> key = rows(c, "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX", table)
                    .stream().map(List::getFirst).toList();
            assertFalse(key.isEmpty(), table);
            result.put(table, new TableSnapshot(columns, key, tableRows(c, table, columns, key)));
        }
        return result;
    }

    private static void assertPreserved(Connection c, Map<String, TableSnapshot> before,
                                        List<List<String>> oldTracker) throws SQLException {
        for (var entry : before.entrySet()) {
            var old = entry.getValue();
            assertEquals(old.rows(), tableRows(c, entry.getKey(), old.columns(), old.primaryKey()),
                    "every original V5 column and row must survive: " + entry.getKey());
        }
        assertEquals(oldTracker, rows(c, "SELECT * FROM " + TRACKER + " WHERE version<=5 ORDER BY version"),
                "V1-V5 migration timestamps and descriptions must survive");
    }

    private static void assertNoBackfill(Connection c, Map<String, TableSnapshot> before) throws SQLException {
        for (String table : schema(c).keySet()) {
            if (before.containsKey(table) || table.equals(TRACKER)) continue;
            if (table.equals("npc_housing_registry_lock")) {
                assertEquals(List.of(List.of("1")), rows(c, "SELECT singleton_id FROM " + quote(table)),
                        "V9's singleton lock is infrastructure, not an admission/history fact");
            } else {
                assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + quote(table)),
                        "migration must not manufacture lifecycle or financial evidence: " + table);
            }
        }
    }

    private static Map<String, String> schema(Connection c) throws SQLException {
        Map<String, String> result = new TreeMap<>();
        for (List<String> row : rows(c, "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            String table = row.getFirst();
            // Next generated ID is fixture state, not a DDL difference between clean and replayed upgrades.
            result.put(table, rows(c, "SHOW CREATE TABLE " + quote(table)).getFirst().get(1)
                    .replaceAll(" AUTO_INCREMENT=\\d+", ""));
        }
        return result;
    }

    private static List<List<String>> tableRows(Connection c, String table, List<String> columns,
                                                List<String> key) throws SQLException {
        return rows(c, "SELECT " + String.join(",", columns.stream().map(DevUpgradeIT::quote).toList())
                + " FROM " + quote(table) + " ORDER BY " + String.join(",", key.stream().map(DevUpgradeIT::quote).toList()));
    }

    private static String quote(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }

    private static String scalar(Connection c, String sql) throws SQLException { return rows(c, sql).getFirst().getFirst(); }

    private static List<List<String>> rows(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        if (values.length == 0) {
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(5);
                st.execute(sql);
                recordWarnings(st);
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(5);
                bind(ps, values);
                ps.execute();
                recordWarnings(ps);
            }
        }
    }

    private static void recordWarnings(Statement st) throws SQLException {
        // Guarded ALTER/CREATE and INSERT IGNORE can legitimately warn on replay. Do not invent
        // a zero-warning/strict-mode contract: surface actual diagnostics; SQL failures still propagate.
        for (SQLWarning warning = st.getWarnings(); warning != null; warning = warning.getNextWarning()) {
            REPLAY_WARNINGS.merge(warning.getErrorCode() + ": " + warning.getMessage(), 1, Integer::sum);
        }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
            else ps.setObject(i + 1, value);
        }
    }
}
