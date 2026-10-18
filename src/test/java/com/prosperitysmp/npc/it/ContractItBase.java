package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.ContractDefinition;
import com.prosperitysmp.npc.town.PartnerOffer;
import com.prosperitysmp.npc.town.PartnershipRepository;
import com.prosperitysmp.npc.town.TownContractRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MariaDB with every registered migration for the contracts and partnerships ITs. No Bukkit, Vault or
 * inventory: take evidence is supplied the way ContractRuntime builds it. Each subclass boots its own server.
 */
abstract class ContractItBase {
    static final ZoneId ZONE = ZoneId.of("America/Chicago");
    static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    static final Instant NOW = DAY.atTime(12, 0).atZone(ZONE).toInstant();
    static final UUID WORLD = new UUID(0, 7000);
    static final ResidentCast CAST = ResidentCast.builtIn();
    static final UUID MARA = ResidentCatalogue.idFor("mara-vale");
    static final UUID ELLIS = ResidentCatalogue.idFor("ellis-ward");
    static final UUID NICO = ResidentCatalogue.idFor("nico-reed");
    /** Contracts and partnerships on, the shipped contracts and offers. */
    static final ContractCatalogue ON = ContractCatalogue.load(Map.of("town.contracts.enabled", true,
            "town.partnerships.enabled", true)::get, CAST, name -> true);
    static DB maria;
    static Database database;
    static List<String> tables;
    TownContractRepository contracts;
    PartnershipRepository partnerships;
    ResidentRepository residents;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_contracts_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 8, 10_000L));
            var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            field.setAccessible(true);
            int last = 0;
            for (Object migration : (List<?>) field.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                var version = migration.getClass().getDeclaredMethod("version");
                resource.setAccessible(true); version.setAccessible(true);
                last = (Integer) version.invoke(migration);
                try (InputStream in = ContractItBase.class.getResourceAsStream("/" + resource.invoke(migration))) {
                    assertNotNull(in);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
                }
            }
            assertEquals(56, last, "V56 is the newest migration");
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'")
                    .stream().map(List::getFirst).toList();
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); }
        finally { if (maria != null) { DB stopping = maria; maria = null; stopping.stop(); } database = null; }
    }

    @BeforeEach void clean() throws SQLException {
        dropTriggers();
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) if (!table.equals("npc_schema_migrations")) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        contracts = new TownContractRepository(database);
        partnerships = new PartnershipRepository(database);
        residents = new ResidentRepository(database);
        for (var profile : CAST.profiles()) assertTrue(residents.register(profile));
    }

    @AfterEach void dropTriggers() throws SQLException {
        if (database != null && database.isConnected()) {
            execute("DROP TRIGGER IF EXISTS contract_memory_failure");
            execute("DROP TRIGGER IF EXISTS contract_partnership_failure");
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    static ContractDefinition contract(String id) { return ON.find(id).orElseThrow(); }

    static PartnerOffer offer(UUID resident) { return ON.partnerOffer(resident).orElseThrow(); }

    /** Evidence of a clean take: the stack goes from before to before minus quantity, with a proven save and a normal return. */
    static ItemCollectionEvidence taken(UUID player, UUID run, int before, int quantity) {
        return new ItemCollectionEvidence(player, run, "a".repeat(64), "b".repeat(64), List.of(before), List.of(before - quantity), true, true);
    }

    /** An enrolled, unpaused home owned by player. */
    static UUID home(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                VALUES (?,?,?,'downtown','room',4,1000,1200,?,0,80,TRUE,1)
                """, id, WORLD, "contract-" + id, owner);
        return id;
    }

    /** Three positive days with the resident: Trusted. */
    void trusted(UUID resident, UUID player) throws SQLException {
        for (int i = 6; i >= 4; i--)
            residents.appendVerified(new RelationshipMemory.Event(resident, player, "repair:seed-" + i,
                    RelationshipMemory.Kind.REPAIR_COMPLETED, NOW.minusSeconds(86_400L * i), DAY.minusDays(i)));
    }

    /** n contracts kept for the resident, one per earlier day (10 or more days back, never two on one day). */
    static void kept(UUID resident, UUID player, int n) throws SQLException {
        execute("INSERT IGNORE INTO npc_town_contract_players(player_id) VALUES (?)", player);
        int existing = Integer.parseInt(scalar("SELECT COUNT(*) FROM npc_town_contracts WHERE player_id=?", player));
        for (int i = 0; i < n; i++) {
            int back = 10 + existing + i;
            long at = NOW.minusSeconds(86_400L * back).toEpochMilli();
            execute("""
                    INSERT INTO npc_town_contracts (contract_id, player_id, kind, definition_id, title, description, giver_id,
                    recipient_id, material, quantity, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day,
                    day_slot, expires_at_millis, status, handover_run_id, closed_at_millis, completed_economy_day)
                    VALUES (?,?,'SUPPLY','seed','Seed','Seed.',?,?,'COAL',1,'STRANGER',30,?,?,1,?,'COMPLETED',?,?,?)
                    """, UUID.randomUUID(), player, resident, resident, at, DAY.minusDays(back).toString(), at + 1_800_000L,
                    UUID.randomUUID(), at + 60_000L, DAY.minusDays(back).toString());
        }
    }

    /** Begins and finishes a partnership start at the given time and day. */
    TownContractRepository.Result partnerStart(UUID player, UUID resident, Instant at, LocalDate day) throws SQLException {
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(true, player, offer(resident), ON.terms(offer(resident)), run, at, day);
        if (begun.code() != TownContractRepository.Code.HANDOVER_STARTED) return begun;
        int quantity = offer(resident).quantity();
        return contracts.finishHandover(begun.contract().orElseThrow().contractId(), player, run, quantity + 5,
                taken(player, run, quantity + 5, quantity), at.plusSeconds(5), day, ON.terms(offer(resident)));
    }

    // ---- SQL helpers ------------------------------------------------------------------------------------------

    static int count(String table) throws SQLException { return Integer.parseInt(scalar("SELECT COUNT(*) FROM " + table)); }

    static String scalar(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql, values); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static Map<String, List<List<String>>> data() throws SQLException {
        var result = new LinkedHashMap<String, List<List<String>>>();
        for (String table : tables) result.put(table, rows("SELECT * FROM " + table));
        return result;
    }

    static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql, values); ResultSet rs = ps.executeQuery()) {
            var result = new ArrayList<List<String>>();
            while (rs.next()) {
                var row = new ArrayList<String>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                }
                result.add(row);
            }
            result.sort(Comparator.comparing(List::toString));
            return result;
        }
    }

    static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, values); }
    }

    static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, values)) { ps.execute(); }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        ps.setQueryTimeout(10);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, values[i]);
        }
        return ps;
    }
}
