package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MariaDB tests. These prove persistence/geometry, not Bukkit permission or platform acceptance. */
class NavGraphTagsIT {
    private static final UUID WORLD = new UUID(0, 301);
    private static final String V5 = "/db/migration/V5__resident_route_tags.sql";
    private static final List<String> EIGHT_TAGS = List.of("spawn", "home", "work", "market", "leisure",
            "work:cook", "work:courier", "work:gardener");
    private static DB mariaDb;
    private static Database database;
    private static String schemaName;
    private NavGraphRepository graphs;
    private UUID graph;
    private long node;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        schemaName = "npc_nav_tags_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schemaName);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L));
        applyResource("/db/migration/V1__initial_schema.sql");
        applyResource("/db/migration/V44__nav_graph_sources.sql");
        // The repository writes the surface class and palette hash, so the graph tables need V39 too.
        applyResource("/db/migration/V39__nav_surface.sql");

        NavGraphRepository repository = new NavGraphRepository(database);
        UUID legacy = repository.saveDraft(WORLD, "legacy", "default",
                NavGraph.builder().addNode(0, 10, 64, 10, "spawn", "crosswalk").build());
        assertTrue(repository.publish(legacy));
        var before = repository.info(legacy);
        applyResource(V5);
        applyResource(V5);
        assertEquals(before, repository.info(legacy), "additive migration preserves the existing active graph");
        assertEquals(Set.of("spawn", "crosswalk"), repository.loadGraph(legacy).nodes().iterator().next().tags());
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
    void freshGraphs() throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(3);
            for (String table : List.of("npc_nav_tags", "npc_nav_edges", "npc_nav_nodes", "npc_nav_graphs")) {
                st.executeUpdate("DELETE FROM " + table);
            }
        }
        graphs = new NavGraphRepository(database);
        graph = draft(WORLD, "district");
        node = graphs.loadGraph(graph).nodes().iterator().next().id();
    }

    @Test
    void v5Provides64CharacterTags() throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_nav_tags' AND COLUMN_NAME = 'tag'
                """)) {
            ps.setQueryTimeout(3);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(64, rs.getInt(1));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"home", "work", "market", "leisure", "spawn"})
    void draftTagAddRemoveAreIdempotent(String tag) throws SQLException {
        assertFalse(graphs.setDraftTag(graph, node, tag, false));
        assertTrue(graphs.setDraftTag(graph, node, tag, true));
        assertFalse(graphs.setDraftTag(graph, node, tag, true));
        assertEquals(Set.of(tag), tags(graph, node));
        assertTrue(graphs.setDraftTag(graph, node, tag, false));
        assertFalse(graphs.setDraftTag(graph, node, tag, false));
        assertEquals(Set.of(), tags(graph, node));
        assertEquals("DRAFT", graphs.info(graph).status());
        assertNull(publishedAt(graph));
    }

    @ParameterizedTest
    @ValueSource(strings = {"home", "work", "market", "leisure"})
    void residentScopedTagsRoundTripAsCanonicalText(String kind) throws SQLException {
        String tag = kind + ":" + UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
        assertTrue(tag.length() > 32);
        assertTrue(graphs.setDraftTag(graph, node, tag, true));
        assertFalse(graphs.setDraftTag(graph, node, tag, true));
        assertEquals(Set.of(tag), tags(graph, node));
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT tag FROM npc_nav_tags WHERE node_id = ?")) {
            ps.setQueryTimeout(3);
            ps.setLong(1, node);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(tag, rs.getString(1), "no opaque storage codec or UUID truncation");
            }
        }
        assertTrue(graphs.setDraftTag(graph, node, tag, false));
        assertEquals(Set.of(), tags(graph, node));
    }

    @ParameterizedTest
    @EnumSource(Occupation.class)
    void everyOccupationHasAnExplicitLowercaseWorkTag(Occupation occupation) throws SQLException {
        String tag = "work:" + occupation.name().toLowerCase(Locale.ROOT);
        assertTrue(graphs.setDraftTag(graph, node, tag, true));
        assertEquals(Set.of(tag), tags(graph, node));
        assertThrows(IllegalArgumentException.class,
                () -> graphs.setDraftTag(graph, node, "work:" + occupation.name(), true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"HOME", " home", "home ", "home\n", "crosswalk", "owner", "private", "home:",
            "home:1-1-1-1-1", "home:abcdef12-3456-7890-abcd-ef1234567890:work",
            "home:ABCDEF12-3456-7890-ABCD-EF1234567890", "spawn:abcdef12-3456-7890-abcd-ef1234567890",
            "home:restorer", "work:miner", "work:Restorer", "work:restorer:extra", "work:cook;DELETE",
            "market:00000000000000000000000000000000", "leisure:\u0000"})
    void invalidTagsCannotAddOrRemoveAnything(String tag) throws SQLException {
        graphs.setDraftTag(graph, node, "home", true);
        assertFalse(NavGraphRepository.isValidDraftTag(tag));
        assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(graph, node, tag, true));
        assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(graph, node, tag, false));
        assertEquals(Set.of("home"), tags(graph, node));
    }

    @Test
    void excessiveTagLengthAndMissingTargetsAreRejected() throws SQLException {
        for (boolean add : List.of(true, false)) {
            assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(graph, node, "x".repeat(65), add));
            assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(null, node, "home", add));
            assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(UUID.randomUUID(), node, "home", add));
            for (long missing : new long[]{-1, 0, Long.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(graph, missing, "home", add));
            }
        }
        assertEquals(Set.of(), tags(graph, node));
        assertFalse(graphs.publish(null));
        assertFalse(graphs.publish(UUID.randomUUID()));
    }

    @Test
    void mismatchedGraphCannotAddOrRemoveAnotherGraphsTags() throws SQLException {
        UUID other = draft(UUID.randomUUID(), "other-world");
        long otherNode = graphs.loadGraph(other).nodes().iterator().next().id();
        graphs.setDraftTag(graph, node, "work", true);
        graphs.setDraftTag(other, otherNode, "home", true);
        var before = graphs.info(graph);
        var otherBefore = graphs.info(other);
        for (boolean add : List.of(true, false)) {
            assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(graph, otherNode, "home", add));
            assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(other, node, "work", add));
        }
        assertEquals(before, graphs.info(graph));
        assertEquals(otherBefore, graphs.info(other));
        assertEquals(Set.of("work"), tags(graph, node));
        assertEquals(Set.of("home"), tags(other, otherNode));
    }

    @Test
    void activeAndArchivedRefuseEvenIdempotentEditsAndRepublish() throws SQLException {
        graphs.setDraftTag(graph, node, "home", true);
        assertTrue(graphs.publish(graph));
        String firstPublished = publishedAt(graph);
        assertNotNull(firstPublished);
        assertFrozen(graph, node);
        UUID replacement = draft(WORLD, "district");
        assertTrue(graphs.publish(replacement));
        var replacementBefore = graphs.info(replacement);
        String replacementPublished = publishedAt(replacement);
        assertFrozen(graph, node);
        assertEquals("ARCHIVED", graphs.info(graph).status());
        assertEquals(firstPublished, publishedAt(graph));
        assertEquals(replacementBefore, graphs.info(replacement));
        assertEquals(replacementPublished, publishedAt(replacement));
        assertEquals(replacement, graphs.activeGraphId(WORLD, "district"));
    }

    @Test
    void publishArchivesOnlyTheSameWorldAndRegionAndPreservesTags() throws SQLException {
        UUID otherWorld = draft(UUID.randomUUID(), "district");
        UUID otherRegion = draft(WORLD, "another-district");
        assertTrue(graphs.publish(otherWorld));
        assertTrue(graphs.publish(otherRegion));
        graphs.setDraftTag(graph, node, "spawn", true);
        assertTrue(graphs.publish(graph));
        UUID next = draft(WORLD, "district");
        assertTrue(graphs.publish(next));
        assertEquals(Set.of("spawn"), tags(graph, node));
        assertEquals("ARCHIVED", graphs.info(graph).status());
        assertEquals("ACTIVE", graphs.info(next).status());
        assertEquals("ACTIVE", graphs.info(otherWorld).status());
        assertEquals("ACTIVE", graphs.info(otherRegion).status());
    }

    @Test
    void eightTagCapAllowsIdempotentRepeatsAndRemovals() throws SQLException {
        for (String tag : EIGHT_TAGS) assertTrue(graphs.setDraftTag(graph, node, tag, true));
        assertEquals(Set.copyOf(EIGHT_TAGS), tags(graph, node));
        assertFalse(graphs.setDraftTag(graph, node, "spawn", true));
        assertFalse(graphs.setDraftTag(graph, node, "work:carpenter", false));
        assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(graph, node, "work:carpenter", true));
        assertEquals(Set.copyOf(EIGHT_TAGS), tags(graph, node));
        assertTrue(graphs.setDraftTag(graph, node, "spawn", false));
        assertTrue(graphs.setDraftTag(graph, node, "work:carpenter", true));
        assertEquals(8, tags(graph, node).size());
    }

    @Test
    void capIncludesExistingScannerTagsWithoutChangingThem() throws SQLException {
        UUID legacy = graphs.saveDraft(WORLD, "legacy-tags", "default", NavGraph.builder()
                .addNode(0, 10, 64, 10, "crosswalk", "spawn", "a", "b", "c", "d", "e", "f").build());
        long legacyNode = graphs.loadGraph(legacy).nodes().iterator().next().id();
        Set<String> before = tags(legacy, legacyNode);
        assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(legacy, legacyNode, "home", true));
        assertEquals(before, tags(legacy, legacyNode));
        assertFalse(graphs.setDraftTag(legacy, legacyNode, "spawn", true));
        assertTrue(graphs.setDraftTag(legacy, legacyNode, "spawn", false));
        assertTrue(graphs.setDraftTag(legacy, legacyNode, "home", true));
        assertTrue(tags(legacy, legacyNode).contains("crosswalk"));
    }

    @Test
    void nearestNodeRequiresActualWorldAndSphericalDistanceToStandingCenter() throws SQLException {
        assertEquals(Long.valueOf(node), graphs.nearestTagNode(graph, WORLD, 10.5, 64, 10.5));
        assertEquals(Long.valueOf(node), graphs.nearestTagNode(graph, WORLD, 12, 64, 10.5));
        assertEquals(Long.valueOf(node), graphs.nearestTagNode(graph, WORLD, 10.5, 65.5, 10.5));
        assertNull(graphs.nearestTagNode(graph, WORLD, 12.0001, 64, 10.5));
        assertNull(graphs.nearestTagNode(graph, WORLD, 10.5, 65.5001, 10.5));
        assertNull(graphs.nearestTagNode(graph, WORLD, 11.6, 64, 11.6), "bounding cube is not a sphere");
        assertNull(graphs.nearestTagNode(graph, WORLD, 10.5, 66, 10.5), "another floor is not nearby");
        assertNull(graphs.nearestTagNode(graph, UUID.randomUUID(), 10.5, 64, 10.5));
        assertNull(graphs.nearestTagNode(UUID.randomUUID(), WORLD, 10.5, 64, 10.5));
        assertThrows(IllegalArgumentException.class, () -> graphs.nearestTagNode(graph, null, 10.5, 64, 10.5));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> graphs.nearestTagNode(graph, WORLD, invalid, 64, 10.5));
            assertThrows(IllegalArgumentException.class, () -> graphs.nearestTagNode(graph, WORLD, 10.5, invalid, 10.5));
            assertThrows(IllegalArgumentException.class, () -> graphs.nearestTagNode(graph, WORLD, 10.5, 64, invalid));
        }
        assertEquals(Set.of(), tags(graph, node));
    }

    @Test
    void nearestNodeUsesVerticalDistanceAndDeterministicIdTies() throws SQLException {
        UUID stacked = graphs.saveDraft(WORLD, "stacked", "default", NavGraph.builder()
                .addNode(0, 10, 64, 10).addNode(1, 10, 66, 10).build());
        NavGraph loaded = graphs.loadGraph(stacked);
        long lower = loaded.nodes().stream().filter(n -> n.y() == 64).findFirst().orElseThrow().id();
        long upper = loaded.nodes().stream().filter(n -> n.y() == 66).findFirst().orElseThrow().id();
        assertEquals(Long.valueOf(lower), graphs.nearestTagNode(stacked, WORLD, 10.5, 64.1, 10.5));
        assertEquals(Long.valueOf(upper), graphs.nearestTagNode(stacked, WORLD, 10.5, 65.9, 10.5));
        assertEquals(Long.valueOf(Math.min(lower, upper)), graphs.nearestTagNode(stacked, WORLD, 10.5, 65, 10.5));
    }

    @Test
    void concurrentDuplicateAddsAreIdempotentAfterTheInitialGraphLock() throws Exception {
        List<Boolean> results = behindGraphLock(
                () -> graphs.setDraftTag(graph, node, "home", true),
                () -> new NavGraphRepository(database).setDraftTag(graph, node, "home", true));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(Set.of("home"), tags(graph, node));
    }

    @Test
    void concurrentAddsCannotOverrunTheLastTagSlot() throws Exception {
        for (String tag : EIGHT_TAGS.subList(0, 7)) graphs.setDraftTag(graph, node, tag, true);
        List<Boolean> results = behindGraphLock(() -> addOrRefused("work:gardener"), () -> addOrRefused("work:carpenter"));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(8, tags(graph, node).size());
        assertTrue(tags(graph, node).containsAll(EIGHT_TAGS.subList(0, 7)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentTagAndPublishSerializeAtTheTargetGraph(boolean tagSubmittedFirst) throws Exception {
        Callable<Boolean> tag = () -> addOrRefused("home");
        Callable<Boolean> publish = () -> new NavGraphRepository(database).publish(graph);
        List<Boolean> result = tagSubmittedFirst ? behindGraphLock(tag, publish) : behindGraphLock(publish, tag);
        boolean changed = result.get(tagSubmittedFirst ? 0 : 1);
        assertTrue(result.get(tagSubmittedFirst ? 1 : 0));
        assertEquals("ACTIVE", graphs.info(graph).status());
        assertEquals(changed ? Set.of("home") : Set.of(), tags(graph, node));
        assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(graph, node, "home", false));
    }

    @Test
    void concurrentPublishOnlyAcceptsTheDraftOnce() throws Exception {
        List<Boolean> result = behindGraphLock(() -> graphs.publish(graph), () -> graphs.publish(graph));
        assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
        assertEquals("ACTIVE", graphs.info(graph).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "ARCHIVED"})
    void waitingTagRechecksStatusAfterLockOwnerCommits(String status) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result;
            try (Connection blocker = lockGraph()) {
                try {
                    result = worker.submit(() -> graphs.setDraftTag(graph, node, "home", true));
                    assertInitialLocks(awaitGraphWaiters(1));
                    try (PreparedStatement ps = blocker.prepareStatement("UPDATE npc_nav_graphs SET status = ? WHERE graph_id = ?")) {
                        ps.setQueryTimeout(3);
                        ps.setString(1, status);
                        ps.setBytes(2, Uuids.toBytes(graph));
                        assertEquals(1, ps.executeUpdate());
                    }
                    blocker.commit();
                } finally {
                    blocker.rollback();
                }
            }
            ExecutionException refusal = assertThrows(ExecutionException.class, () -> result.get(8, TimeUnit.SECONDS));
            assertTrue(refusal.getCause() instanceof IllegalArgumentException);
            assertEquals(Set.of(), tags(graph, node));
            assertEquals(status, graphs.info(graph).status());
        } finally {
            stopWorkers(worker);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void blockedTagOrPublishHasAFiveSecondQueryDeadlineAndRollsBack(boolean tagging) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            try (Connection blocker = lockGraph()) {
                try {
                    Future<Boolean> result = worker.submit(() -> tagging
                            ? graphs.setDraftTag(graph, node, "home", true) : graphs.publish(graph));
                    assertInitialLocks(awaitGraphWaiters(1));
                    ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(8, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof SQLException, "blocked query must fail with a JDBC deadline");
                    assertEquals("DRAFT", graphs.info(graph).status());
                    assertEquals(Set.of(), tags(graph, node));
                    assertNull(publishedAt(graph));
                } finally {
                    blocker.rollback();
                }
            }
            assertTrue(graphs.setDraftTag(graph, node, "home", true), "connection/locks recover after timeout");
            assertTrue(graphs.publish(graph));
        } finally {
            stopWorkers(worker);
        }
    }

    private UUID draft(UUID world, String region) throws SQLException {
        return graphs.saveDraft(world, region, "default", NavGraph.builder().addNode(0, 10, 64, 10).build());
    }

    private Set<String> tags(UUID id, long nodeId) throws SQLException {
        return graphs.loadGraph(id).node(nodeId).tags();
    }

    private String publishedAt(UUID id) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT published_at FROM npc_nav_graphs WHERE graph_id = ?")) {
            ps.setQueryTimeout(3);
            ps.setBytes(1, Uuids.toBytes(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private void assertFrozen(UUID id, long nodeId) throws SQLException {
        var before = graphs.info(id);
        Set<String> beforeTags = tags(id, nodeId);
        for (String tag : List.of("home", "work")) {
            for (boolean add : List.of(true, false)) {
                assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(id, nodeId, tag, add));
            }
        }
        assertFalse(graphs.publish(id));
        assertEquals(before, graphs.info(id));
        assertEquals(beforeTags, tags(id, nodeId));
    }

    private boolean addOrRefused(String tag) throws SQLException {
        try {
            return graphs.setDraftTag(graph, node, tag, true);
        } catch (IllegalArgumentException refused) {
            return false;
        }
    }

    private Connection lockGraph() throws SQLException {
        Connection c = database.getConnection();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT graph_id FROM npc_nav_graphs WHERE graph_id = ? FOR UPDATE")) {
                ps.setQueryTimeout(3);
                ps.setBytes(1, Uuids.toBytes(graph));
                try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
            }
            return c;
        } catch (SQLException | RuntimeException | Error failure) {
            c.close();
            throw failure;
        }
    }

    private <T> List<T> behindGraphLock(Callable<T> firstCall, Callable<T> secondCall) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<T> first;
            Future<T> second;
            try (Connection blocker = lockGraph()) {
                try {
                    first = workers.submit(firstCall);
                    assertInitialLocks(awaitGraphWaiters(1));
                    second = workers.submit(secondCall);
                    assertInitialLocks(awaitGraphWaiters(2));
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                    assertEquals("DRAFT", graphs.info(graph).status());
                } finally {
                    blocker.rollback();
                }
            }
            return List.of(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
        } finally {
            stopWorkers(workers);
        }
    }

    private static void assertInitialLocks(List<String> queries) {
        for (String query : queries) {
            String sql = query.strip().toLowerCase(Locale.ROOT).replaceFirst(
                    "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
            assertTrue(sql.matches("(?s)select\\s+.+\\s+from\\s+npc_nav_graphs\\s+where\\s+graph_id\\s*=.+\\s+for\\s+update\\s*"),
                    "must wait at the initial graph-row locking read, not a later tag/status write: " + query);
        }
    }

    private static List<String> awaitGraphWaiters(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<String> queries = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            queries.clear();
            try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    SELECT INFO FROM information_schema.PROCESSLIST
                    WHERE ID <> CONNECTION_ID() AND DB = ? AND INFO LIKE '%npc_nav_%' LIMIT 6
                    """)) {
                ps.setQueryTimeout(1);
                ps.setString(1, schemaName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) queries.add(rs.getString(1));
                }
            }
            if (queries.size() == expected) return List.copyOf(queries);
            // PROCESSLIST sees lock waiters omitted by INNODB_TRX's cached snapshot.
            Thread.sleep(20);
        }
        throw new AssertionError("expected " + expected + " graph-lock waiters, saw " + queries);
    }

    private static void stopWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "navigation test workers terminated");
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = NavGraphTagsIT.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing migration " + path);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                st.setQueryTimeout(5);
                for (String statement : MigrationRunner.splitStatements(sql)) st.execute(statement);
            }
        }
    }
}
