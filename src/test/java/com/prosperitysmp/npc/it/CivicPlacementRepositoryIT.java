package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.town.CivicPlacement;
import com.prosperitysmp.npc.town.CivicPlacementRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real isolated MariaDB; migrations are applied explicitly without changing MigrationRunner. */
class CivicPlacementRepositoryIT {
    private static final UUID WORLD = new UUID(0, 301);
    private static final UUID OTHER_WORLD = new UUID(0, 302);
    private static final UUID HOME = new UUID(0, 401);
    private static final List<String> ANCHORS = List.of("home", "work", "market", "leisure");
    private static DB mariaDb;
    private static Database database;
    private static String schema;
    private CivicPlacementRepository placements;
    private ResidentRepository residents;
    private NavGraphRepository graphs;
    private ResidentProfile person;
    private UUID graph;

    @BeforeAll
    static void bootDatabase() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        schema = "npc_civic_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
        for (String resource : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                "V5__resident_route_tags.sql", "V15__tenancy_departures.sql",
                "V18__civic_placements.sql", "V18__civic_placements.sql", "V39__nav_surface.sql", "V44__nav_graph_sources.sql")) {
            applyResource("/db/migration/" + resource);
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) mariaDb.stop();
        }
    }

    @BeforeEach
    void freshTables() throws Exception {
        execute("DROP TRIGGER IF EXISTS civic_write_failure");
        for (String table : List.of("npc_civic_placements", "npc_tenancy_departures", "npc_tenants", "npc_properties",
                "npc_resident_memory", "npc_resident_profiles", "npc_nav_tags", "npc_nav_edges", "npc_nav_nodes", "npc_nav_graphs")) {
            execute("DELETE FROM " + table);
        }
        placements = new CivicPlacementRepository(database);
        residents = new ResidentRepository(database);
        graphs = new NavGraphRepository(database);
        person = ResidentCatalogue.betaCast().getFirst();
        assertTrue(residents.register(person));
        graph = active(WORLD, "Central_District", ANCHORS);
    }

    @Test
    void durableCanonicalReplayDoesNotCreateIdentityPropertyTenancyOrDuplicateRow() throws Exception {
        assertTrue(placements.place(person.id(), WORLD, "central_district"));
        CivicPlacement expected = new CivicPlacement(person.id(), WORLD, "Central_District", true, 1);
        assertEquals(List.of(expected), placements.listForDisplay(Set.of(WORLD), 10));
        placements = new CivicPlacementRepository(database);
        assertFalse(placements.place(person.id(), WORLD, "CENTRAL_DISTRICT"));
        assertEquals(expected, stored(person.id()));
        assertEquals(person, residents.find(person.id()).orElseThrow());
        assertEquals(1, count("npc_resident_profiles"));
        assertEquals(1, count("npc_civic_placements"));
        assertEquals(0, count("npc_tenants"));
        assertEquals(0, count("npc_properties"));
        applyResource("/db/migration/V18__civic_placements.sql");
        assertEquals(expected, stored(person.id()));
    }

    @Test
    void disablePreservesRowAndOnlyMeaningfulChangesAdvanceRevision() throws Exception {
        assertFalse(placements.disable(new UUID(0, 999)));
        assertFalse(placements.disable(person.id()));
        assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        assertTrue(placements.disable(person.id()));
        assertEquals(new CivicPlacement(person.id(), WORLD, "Central_District", false, 2), stored(person.id()));
        assertFalse(placements.disable(person.id()));
        assertTrue(placements.listForDisplay(Set.of(WORLD), 1).isEmpty());
        assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        assertEquals(3, stored(person.id()).revision());
        active(WORLD, "north", ANCHORS);
        assertTrue(placements.place(person.id(), WORLD, "north"));
        assertEquals(4, stored(person.id()).revision());
        active(OTHER_WORLD, "north", ANCHORS);
        assertTrue(placements.place(person.id(), OTHER_WORLD, "north"));
        assertEquals(new CivicPlacement(person.id(), OTHER_WORLD, "north", true, 5), stored(person.id()));
        assertEquals(1, count("npc_civic_placements"));
    }

    @Test
    void missingProfileAndWrongWorldDistrictOrDraftFailWithoutWrites() throws Exception {
        assertThrows(SQLException.class, () -> placements.place(new UUID(0, 999), WORLD, "Central_District"));
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), OTHER_WORLD, "Central_District"));
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "missing"));
        graphs.saveDraft(WORLD, "draft", "default", anchored(ANCHORS));
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "draft"));
        assertEquals(0, count("npc_civic_placements"));
        assertEquals(1, count("npc_resident_profiles"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"home", "work", "market", "leisure"})
    void everyAnchorIsRequiredAndRejectedRepointPreservesPriorPlacement(String missing) throws Exception {
        assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        active(WORLD, "incomplete", ANCHORS.stream().filter(tag -> !tag.equals(missing)).toList());
        CivicPlacement prior = stored(person.id());
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "incomplete"));
        assertEquals(prior, stored(person.id()));
    }

    @Test
    void anchorsCannotBeCombinedAcrossGraphsOrBorrowedFromOtherResidents() throws Exception {
        active(WORLD, "split", List.of("home", "work"));
        graphs.saveDraft(WORLD, "split", "default", anchored(List.of("market", "leisure")));
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "split"));
        UUID stranger = new UUID(0, 999);
        active(WORLD, "personal", ANCHORS.stream().map(tag -> tag + ':' + stranger).toList());
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "personal"));
        active(WORLD, "case_sensitive", List.of("HOME", "WORK", "MARKET", "LEISURE"));
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "case_sensitive"));
        assertEquals(0, count("npc_civic_placements"));
    }

    @Test
    void personalAndOccupationAnchorsMatchExistingRoutePlanner() throws Exception {
        active(WORLD, "personal", ANCHORS.stream().map(tag -> tag + ':' + person.id()).toList());
        assertTrue(placements.place(person.id(), WORLD, "personal"));
        active(WORLD, "occupation", List.of("home", "work:" + person.occupation().name().toLowerCase(Locale.ROOT),
                "market", "leisure"));
        assertTrue(placements.place(person.id(), WORLD, "occupation"));
        String wrong = person.occupation() == ResidentProfile.Occupation.COOK ? "courier" : "cook";
        active(WORLD, "wrong_job", List.of("home", "work:" + wrong, "market", "leisure"));
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "wrong_job"));
        assertEquals(2, stored(person.id()).revision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDLE", "MOVING", "DEPARTING", "UNKNOWN"})
    void occupiedTenantRejectsPlaceAndExcludesDisplayRegardlessOfState(String state) throws Exception {
        assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        try (Connection c = database.getConnection()) { insertTenant(c, person.id(), state); }
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "Central_District"));
        assertTrue(placements.listForDisplay(Set.of(WORLD), 100).isEmpty());
        assertEquals(1, stored(person.id()).revision());
        assertTrue(placements.disable(person.id()), "staff can disable a now-occupied placement");
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "Central_District"));
        assertEquals(2, stored(person.id()).revision());
    }

    @Test
    void actualVacateRestoresCivicDisplayWithoutRecreatingIdentityOrPlacement() throws Exception {
        assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        CivicPlacement prior = stored(person.id());
        try (Connection c = database.getConnection()) { insertTenant(c, person.id(), "IDLE"); }
        assertTrue(placements.listForDisplay(Set.of(WORLD), 100).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> placements.place(person.id(), WORLD, "Central_District"));
        assertTrue(new TenantRepository(database).vacate(person.id()));
        assertEquals(List.of(prior), placements.listForDisplay(Set.of(WORLD), 100));
        assertFalse(placements.place(person.id(), WORLD, "Central_District"));
        assertTrue(placements.disable(person.id()));
        assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        assertEquals(3, stored(person.id()).revision());
        assertEquals(person, residents.find(person.id()).orElseThrow());
        assertEquals(1, count("npc_resident_profiles"));
        assertEquals(1, count("npc_civic_placements"));
        assertEquals(1, count("npc_tenants"));
        assertEquals(1, count("npc_tenancy_departures"));
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT property_id,slot,row_version FROM npc_tenants WHERE tenant_id=?")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(person.id()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getBytes("property_id"));
                assertEquals(null, rs.getObject("slot"));
                assertEquals(1, rs.getLong("row_version"));
            }
        }
    }

    @Test
    void retainedVacantRowAllowsFirstPlacementWithoutModifyingTenancy() throws Exception {
        execute("INSERT INTO npc_tenants(tenant_id,display_name,archetype,row_version,state) "
                + "VALUES (?,'Departed resident','RESTORER',8,'DEPARTING')", person.id());
        assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        assertEquals(List.of(stored(person.id())), placements.listForDisplay(Set.of(WORLD), 100));
        assertEquals(1, count("npc_tenants"));
        assertEquals(1, count("npc_resident_profiles"));
        assertEquals(0, count("npc_properties"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"property_id=NULL", "slot=NULL"})
    void partialOccupancyFailsClosedForPlaceAndDisplay(String corruption) throws Exception {
        placements.place(person.id(), WORLD, "Central_District");
        try (Connection c = database.getConnection()) { insertTenant(c, person.id(), "IDLE"); }
        execute("UPDATE npc_tenants SET " + corruption);
        assertThrows(SQLException.class, () -> placements.place(person.id(), WORLD, "Central_District"));
        assertTrue(placements.listForDisplay(Set.of(WORLD), 100).isEmpty());
        assertEquals(1, stored(person.id()).revision());
    }

    @Test
    void displayIsImmutableWorldScopedDeterministicAndCapped() throws Exception {
        for (int i = 1; i <= 103; i++) {
            UUID id = new UUID(0, i);
            residents.register(ResidentCatalogue.draft(id, "Resident " + i));
            placements.place(id, WORLD, "Central_District");
        }
        placements.disable(new UUID(0, 1));
        try (Connection c = database.getConnection()) { insertTenant(c, new UUID(0, 2), "IDLE"); }
        active(OTHER_WORLD, "north", ANCHORS);
        placements.place(person.id(), OTHER_WORLD, "north");
        List<CivicPlacement> all = placements.listForDisplay(Set.of(WORLD), Integer.MAX_VALUE);
        assertEquals(100, all.size());
        assertEquals(new UUID(0, 3), all.getFirst().residentId());
        assertEquals(new UUID(0, 102), all.getLast().residentId());
        assertEquals(all, placements.listForDisplay(Set.of(WORLD), Integer.MAX_VALUE));
        assertEquals(all.subList(0, 2), placements.listForDisplay(Set.of(WORLD), 2));
        assertEquals(Set.of(person.id()), placements.listForDisplay(Set.of(OTHER_WORLD), 100).stream()
                .map(CivicPlacement::residentId).collect(java.util.stream.Collectors.toSet()));
        assertThrows(UnsupportedOperationException.class, all::clear);
        assertEquals(1, count("npc_properties"), "only the occupied-tenant test fixture exists");
        Set<UUID> worlds = new HashSet<>();
        for (int i = 0; i < 128; i++) worlds.add(new UUID(1, i));
        worlds.remove(new UUID(1, 0));
        worlds.add(WORLD);
        assertEquals(100, placements.listForDisplay(worlds, 101).size());
    }

    @Test
    void schemaEnforcesIdentitySinglePlacementAndValueBounds() throws Exception {
        assertThrows(SQLException.class, () -> execute("INSERT INTO npc_civic_placements VALUES(?,?,?,?,?)",
                new UUID(0, 999), WORLD, "central", true, 1L));
        placements.place(person.id(), WORLD, "Central_District");
        assertThrows(SQLException.class, () -> execute("INSERT INTO npc_civic_placements VALUES(?,?,?,?,?)",
                person.id(), OTHER_WORLD, "central", true, 1L));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_civic_placements SET revision=0"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_civic_placements SET enabled=2"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_civic_placements SET district_id='bad district'"));
        placements.disable(person.id());
        assertThrows(SQLException.class, () -> execute("DELETE FROM npc_resident_profiles WHERE resident_id=?", person.id()));
        assertEquals(1, count("npc_civic_placements"));
    }

    @Test
    void revisionExhaustionFailsClosedButExactReplayStillWorks() throws Exception {
        placements.place(person.id(), WORLD, "Central_District");
        execute("UPDATE npc_civic_placements SET revision=?", Long.MAX_VALUE);
        CivicPlacement prior = stored(person.id());
        assertFalse(placements.place(person.id(), WORLD, "Central_District"));
        assertThrows(SQLException.class, () -> placements.disable(person.id()));
        active(WORLD, "north", ANCHORS);
        assertThrows(SQLException.class, () -> placements.place(person.id(), WORLD, "north"));
        assertEquals(prior, stored(person.id()));
    }

    @Test
    void failedUpdateRollsBackAndPoolRemainsUsable() throws Exception {
        placements.place(person.id(), WORLD, "Central_District");
        CivicPlacement prior = stored(person.id());
        execute("CREATE TRIGGER civic_write_failure BEFORE UPDATE ON npc_civic_placements FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected civic failure'");
        assertThrows(SQLException.class, () -> placements.disable(person.id()));
        assertEquals(prior, stored(person.id()));
        active(WORLD, "north", ANCHORS);
        assertThrows(SQLException.class, () -> placements.place(person.id(), WORLD, "north"));
        assertEquals(prior, stored(person.id()));
        execute("DROP TRIGGER civic_write_failure");
        assertTrue(placements.disable(person.id()));
        try (Connection c = database.getConnection()) { assertTrue(c.getAutoCommit()); }
    }

    @Test
    void concurrentIdenticalPlaceIsOneWriteAndReadsCurrentPlacement() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             Connection blocker = database.getConnection()) {
            blocker.setAutoCommit(false);
            lock(blocker, "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE", person.id());
            Future<Boolean> first = workers.submit(() -> placements.place(person.id(), WORLD, "Central_District"));
            awaitWaiters("npc_resident_profiles", 1);
            Future<Boolean> second = workers.submit(() -> placements.place(person.id(), WORLD, "Central_District"));
            awaitWaiters("npc_resident_profiles", 2);
            blocker.rollback();
            assertEquals(1, (first.get(8, TimeUnit.SECONDS) ? 1 : 0) + (second.get(8, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, stored(person.id()).revision());
            assertEquals(1, count("npc_civic_placements"));
        }
    }

    @Test
    void concurrentDifferentPlacesSerializeMonotonicRevisions() throws Exception {
        placements.place(person.id(), WORLD, "Central_District");
        active(WORLD, "north", ANCHORS);
        active(OTHER_WORLD, "south", ANCHORS);
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             Connection blocker = database.getConnection()) {
            blocker.setAutoCommit(false);
            lock(blocker, "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE", person.id());
            Future<Boolean> first = workers.submit(() -> placements.place(person.id(), WORLD, "north"));
            awaitWaiters("npc_resident_profiles", 1);
            Future<Boolean> second = workers.submit(() -> placements.place(person.id(), OTHER_WORLD, "south"));
            awaitWaiters("npc_resident_profiles", 2);
            blocker.rollback();
            assertTrue(first.get(8, TimeUnit.SECONDS));
            assertTrue(second.get(8, TimeUnit.SECONDS));
            assertEquals(3, stored(person.id()).revision());
            assertEquals(1, count("npc_civic_placements"));
        }
    }

    @Test
    void uncommittedTenancyIsReadAfterLockWaitAndRejectsPlacement() throws Exception {
        try (ExecutorService workers = Executors.newSingleThreadExecutor();
             Connection admission = database.getConnection()) {
            admission.setAutoCommit(false);
            insertTenant(admission, person.id(), "IDLE");
            Future<Boolean> request = workers.submit(() -> placements.place(person.id(), WORLD, "Central_District"));
            awaitWaiters("npc_tenants", 1);
            assertFalse(request.isDone());
            admission.commit();
            var failure = assertThrows(ExecutionException.class, () -> request.get(8, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals(0, count("npc_civic_placements"));
        }
    }

    @Test
    void absentTenantLockFencesInsertUntilPlacementCommits() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             Connection blocker = database.getConnection()) {
            blocker.setAutoCommit(false);
            lock(blocker, "SELECT graph_id FROM npc_nav_graphs WHERE graph_id=? FOR UPDATE", graph);
            Future<Boolean> placement = workers.submit(() -> placements.place(person.id(), WORLD, "Central_District"));
            awaitWaiters("npc_nav_graphs", 1);
            Future<?> admission = workers.submit(() -> {
                try (Connection c = database.getConnection()) { insertTenant(c, person.id(), "IDLE"); }
                return null;
            });
            awaitWaiters("npc_tenants", 1);
            assertFalse(admission.isDone());
            blocker.rollback();
            assertTrue(placement.get(8, TimeUnit.SECONDS));
            admission.get(8, TimeUnit.SECONDS);
            assertEquals(1, count("npc_civic_placements"));
            assertTrue(placements.listForDisplay(Set.of(WORLD), 100).isEmpty());
        }
    }

    @Test
    void activeStatusIsCheckedWithCurrentLockingReadAfterPublicationChange() throws Exception {
        try (ExecutorService workers = Executors.newSingleThreadExecutor();
             Connection publisher = database.getConnection()) {
            publisher.setAutoCommit(false);
            lock(publisher, "SELECT graph_id FROM npc_nav_graphs WHERE graph_id=? FOR UPDATE", graph);
            Future<Boolean> request = workers.submit(() -> placements.place(person.id(), WORLD, "Central_District"));
            awaitWaiters("npc_nav_graphs", 1);
            try (PreparedStatement ps = publisher.prepareStatement("UPDATE npc_nav_graphs SET status='ARCHIVED' WHERE graph_id=?")) {
                ps.setQueryTimeout(5);
                ps.setBytes(1, Uuids.toBytes(graph));
                ps.executeUpdate();
            }
            publisher.commit();
            var failure = assertThrows(ExecutionException.class, () -> request.get(8, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals(0, count("npc_civic_placements"));
        }
    }

    @Test
    void queryTimeoutReleasesTransactionAndNextPlacementRecovers() throws Exception {
        try (ExecutorService workers = Executors.newSingleThreadExecutor();
             Connection blocker = database.getConnection()) {
            blocker.setAutoCommit(false);
            lock(blocker, "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE", person.id());
            Future<Boolean> request = workers.submit(() -> placements.place(person.id(), WORLD, "Central_District"));
            awaitWaiters("npc_resident_profiles", 1);
            var failure = assertThrows(ExecutionException.class, () -> request.get(8, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SQLTimeoutException);
            assertEquals(0, count("npc_civic_placements"));
            blocker.rollback();
            assertTrue(placements.place(person.id(), WORLD, "Central_District"));
        }
    }

    private UUID active(UUID world, String district, List<String> tags) throws SQLException {
        UUID id = graphs.saveDraft(world, district, "default", anchored(tags));
        assertTrue(graphs.publish(id));
        return id;
    }

    private static NavGraph anchored(List<String> tags) {
        return NavGraph.builder().addNode(0, 10, 64, 10, tags.toArray(String[]::new)).build();
    }

    private CivicPlacement stored(UUID resident) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT world_uuid,district_id,enabled,revision FROM npc_civic_placements WHERE resident_id=?")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(resident));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return new CivicPlacement(resident, Uuids.fromBytes(rs.getBytes(1)), rs.getString(2),
                        rs.getBoolean(3), rs.getLong(4));
            }
        }
    }

    private static void insertTenant(Connection c, UUID resident, String state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents) VALUES (?,?,'fixture_home','fixture','room',1,100,100)
                """)) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(HOME));
            ps.setBytes(2, Uuids.toBytes(WORLD));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO npc_tenants(tenant_id,display_name,archetype,state,property_id,slot) "
                        + "VALUES (?,'Staff resident','RESTORER',?,?,0)")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(resident));
            ps.setString(2, state);
            ps.setBytes(3, Uuids.toBytes(HOME));
            ps.executeUpdate();
        }
    }

    private static void lock(Connection c, String sql, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(id));
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
        }
    }

    private static void awaitWaiters(String table, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<String> queries = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            queries.clear();
            try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    SELECT INFO FROM information_schema.PROCESSLIST
                    WHERE ID<>CONNECTION_ID() AND DB=? AND INFO LIKE ? LIMIT 6
                    """)) {
                ps.setQueryTimeout(1);
                ps.setString(1, schema);
                ps.setString(2, '%' + table + '%');
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) queries.add(rs.getString(1));
                }
            }
            if (queries.size() == expected) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected " + expected + " lock waiters on " + table + ", saw " + queries);
    }

    private static long count(String table) throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < parameters.length; i++) {
                Object value = parameters[i];
                if (value instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, value);
            }
            ps.executeUpdate();
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = CivicPlacementRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing migration " + path);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                st.setQueryTimeout(5);
                for (String statement : MigrationRunner.splitStatements(sql)) st.execute(statement);
            }
        }
    }
}
