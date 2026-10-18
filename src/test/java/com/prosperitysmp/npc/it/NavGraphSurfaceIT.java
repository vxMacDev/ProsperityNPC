package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.nav.NavCorridor;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavScanLogic;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.StreetRules;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V39, the stored surface classes and palette hash, and the tag and restore tools built on them (real MariaDB). */
class NavGraphSurfaceIT {
    private static final UUID WORLD = new UUID(0, 3901);
    private static final UUID LEGACY_GRAPH = new UUID(0, 3902);
    private static DB mariaDb;
    private static Database database;
    private NavGraphRepository graphs;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        String schema = "npc_nav_surface_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
        applyResource("/db/migration/V1__initial_schema.sql");
        applyResource("/db/migration/V44__nav_graph_sources.sql");
        applyResource("/db/migration/V5__resident_route_tags.sql");
        // A graph written before V39 existed, by the old jar's explicit column lists.
        execute("INSERT INTO npc_nav_graphs (graph_id, world_uuid, region_id, palette, version, status) VALUES (?, ?, 'old', 'c001-road44', 1, 'ACTIVE')",
                Uuids.toBytes(LEGACY_GRAPH), Uuids.toBytes(WORLD));
        execute("INSERT INTO npc_nav_nodes (graph_id, x, y, z) VALUES (?, 416, 72, -128)", Uuids.toBytes(LEGACY_GRAPH));
        execute("INSERT INTO npc_nav_nodes (graph_id, x, y, z) VALUES (?, 417, 72, -128)", Uuids.toBytes(LEGACY_GRAPH));
        execute("INSERT INTO npc_nav_tags (node_id, tag) SELECT node_id, 'home' FROM npc_nav_nodes WHERE x = 416");
        execute("INSERT INTO npc_nav_edges (graph_id, from_node_id, to_node_id, cost) SELECT ?, a.node_id, b.node_id, 10 "
                + "FROM npc_nav_nodes a JOIN npc_nav_nodes b ON b.x = a.x + 1", Uuids.toBytes(LEGACY_GRAPH));
        applyResource("/db/migration/V39__nav_surface.sql");
        applyResource("/db/migration/V39__nav_surface.sql");
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
        execute("DELETE FROM npc_nav_tags WHERE node_id IN (SELECT node_id FROM npc_nav_nodes WHERE graph_id <> ?)", Uuids.toBytes(LEGACY_GRAPH));
        execute("DELETE FROM npc_nav_edges WHERE graph_id <> ?", Uuids.toBytes(LEGACY_GRAPH));
        execute("DELETE FROM npc_nav_nodes WHERE graph_id <> ?", Uuids.toBytes(LEGACY_GRAPH));
        execute("DELETE FROM npc_nav_graphs WHERE graph_id <> ?", Uuids.toBytes(LEGACY_GRAPH));
        graphs = new NavGraphRepository(database);
    }

    // ---- V39 ----

    @Test
    void v39AppliedTwiceLeavesLegacyRowsAsUnknownSurfaceWithNoHash() throws SQLException {
        NavGraph legacy = graphs.loadGraph(LEGACY_GRAPH);
        assertEquals(2, legacy.nodeCount());
        assertTrue(legacy.nodes().stream().allMatch(n -> n.surface() == NavSurface.UNKNOWN));
        assertTrue(legacy.nodes().stream().allMatch(n -> n.roadWidth() == 0), "rows from before V39 have no recorded width");
        assertEquals(1, legacy.edgeCount());
        assertEquals(Set.of("home"), legacy.nodes().stream().filter(n -> n.x() == 416).findFirst().orElseThrow().tags());
        assertNull(graphs.info(LEGACY_GRAPH).paletteHash());
        assertEquals("ACTIVE", graphs.info(LEGACY_GRAPH).status());
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND ((TABLE_NAME = 'npc_nav_nodes' AND COLUMN_NAME IN ('surface', 'road_width'))
                   OR (TABLE_NAME = 'npc_nav_graphs' AND COLUMN_NAME = 'palette_hash')) ORDER BY COLUMN_NAME
                """)) {
            ps.setQueryTimeout(3);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("palette_hash", rs.getString(1));
                assertEquals("char(64)", rs.getString(2));
                assertEquals("YES", rs.getString(3));
                assertTrue(rs.next());
                assertEquals("road_width", rs.getString(1));
                assertTrue(rs.getString(2).startsWith("smallint") && rs.getString(2).endsWith("unsigned"), rs.getString(2));
                assertEquals("YES", rs.getString(3), "no width recorded is NULL");
                assertTrue(rs.next());
                assertEquals("surface", rs.getString(1));
                assertEquals("NO", rs.getString(3));
                assertFalse(rs.next(), "exactly one column per table, even after a second apply");
            }
        }
    }

    @Test
    void anOldJarsInsertAndSelectStillWorkAgainstTheNewColumns() throws SQLException {
        execute("INSERT INTO npc_nav_nodes (graph_id, x, y, z) VALUES (?, 1, 2, 3)", Uuids.toBytes(LEGACY_GRAPH));
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT x, y, z FROM npc_nav_nodes WHERE graph_id = ? AND x = 1")) {
            ps.setBytes(1, Uuids.toBytes(LEGACY_GRAPH));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
        execute("DELETE FROM npc_nav_nodes WHERE graph_id = ? AND x = 1", Uuids.toBytes(LEGACY_GRAPH));
    }

    // ---- a migration that cannot get its table lock ----

    private static int sessionLockWait(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT @@SESSION.lock_wait_timeout")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    @Test
    void aMigrationBlockedByAnOpenTransactionGivesUpQuicklyWithOneClearMessageAndLeavesTheSessionAsItWas() throws Exception {
        List<String> alter = List.of("ALTER TABLE npc_nav_nodes ADD COLUMN IF NOT EXISTS lock_probe TINYINT NULL, ALGORITHM=INSTANT");
        try (Connection holder = database.getConnection(); Connection migrating = database.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM npc_nav_nodes")) {
                assertTrue(rs.next()); // the open transaction now holds a metadata lock on the table
            }
            int before = sessionLockWait(migrating);
            long started = System.nanoTime();
            SQLException timeout = assertThrows(SQLException.class,
                    () -> MigrationRunner.applyStatements(migrating, "V39 (nav_surface)", alter, 1));
            double seconds = (System.nanoTime() - started) / 1e9;
            assertTrue(seconds < 8, "it must not wait out the long default lock wait: " + seconds + " s");
            assertTrue(timeout.getMessage().contains("V39 (nav_surface)") && timeout.getMessage().contains("statement 1 of 1")
                            && timeout.getMessage().contains("lock"), timeout.getMessage());
            assertEquals(before, sessionLockWait(migrating), "the wait limit is only for the migration");
            holder.rollback();
            // Control: the same statement applies at once when nothing holds the table.
            assertEquals(1, MigrationRunner.applyStatements(migrating, "V39 (nav_surface)", alter, 1));
        } finally {
            execute("ALTER TABLE npc_nav_nodes DROP COLUMN IF EXISTS lock_probe");
        }
    }

    // ---- fixtures ----

    /** Sidewalk rows z 0..2 and 6..8 with a three-wide road between, scanned the way the plugin scans it. */
    private static NavGraph street() {
        NavScanLogic.Terrain terrain = new NavScanLogic.Terrain() {
            @Override public boolean isSupport(int x, int y, int z) { return y == 63 && z >= 0 && z <= 8; }
            @Override public boolean isClear(int x, int y, int z) { return y != 63 || z < 0 || z > 8; }
            @Override public NavSurface surface(int x, int y, int z) {
                return z >= 3 && z <= 5 ? NavSurface.ROAD : NavSurface.SIDEWALK;
            }
        };
        return NavScanLogic.scan(new NavScanLogic.Bounds(0, 60, 9, 66, 0, 8), terrain, 2, 1, new StreetRules(12, 16, 0.4)).graph();
    }

    private static NavGraph withTags(NavGraph graph, Map<String, Set<String>> tagsByPosition) {
        NavGraph.Builder builder = NavGraph.builder();
        for (NavGraph.Node n : graph.nodes()) {
            builder.addNode(n.withTags(tagsByPosition.getOrDefault(n.x() + "," + n.z(), Set.of())));
        }
        graph.edges().forEach(e -> builder.addEdge(e.from(), e.to(), e.cost()));
        return builder.build();
    }

    private static Set<String> description(NavGraph g) {
        Set<String> out = new HashSet<>();
        for (NavGraph.Node n : g.nodes()) {
            out.add("node " + n.x() + "," + n.y() + "," + n.z() + " " + n.surface() + " " + new java.util.TreeSet<>(n.tags())
                    + " width " + n.roadWidth());
        }
        for (NavGraph.Edge e : g.edges()) {
            NavGraph.Node a = g.node(e.from());
            NavGraph.Node b = g.node(e.to());
            out.add("edge " + a.x() + "," + a.y() + "," + a.z() + ">" + b.x() + "," + b.y() + "," + b.z() + " " + e.cost());
        }
        return out;
    }

    private static long nodeAt(NavGraph g, int x, int y, int z) {
        return g.nodes().stream().filter(n -> n.x() == x && n.y() == y && n.z() == z).findFirst().orElseThrow().id();
    }

    // ---- save, load, corridor ----

    @Test
    void saveAndLoadRoundTripEverySurfaceAndTheHash() throws SQLException {
        NavGraph scanned = withTags(street(), Map.of("2,1", Set.of("home", "spawn"), "7,7", Set.of("work")));
        UUID id = graphs.saveDraft(WORLD, "city", "city", "a".repeat(64), scanned);
        NavGraph loaded = graphs.loadGraph(id);
        assertEquals(description(scanned), description(loaded));
        // The scan's measured road width is stored on the crossing nodes and only on those.
        assertTrue(scanned.nodes().stream().filter(n -> n.surface().isStreet()).allMatch(n -> n.roadWidth() > 0));
        assertEquals(scanned.nodes().stream().collect(Collectors.toMap(n -> n.x() + "," + n.z(), NavGraph.Node::roadWidth)),
                loaded.nodes().stream().collect(Collectors.toMap(n -> n.x() + "," + n.z(), NavGraph.Node::roadWidth)));
        assertEquals(Set.of(NavSurface.SIDEWALK, NavSurface.ROAD),
                loaded.nodes().stream().map(NavGraph.Node::surface).collect(Collectors.toSet()));
        assertEquals("a".repeat(64), graphs.info(id).paletteHash());
        var stored = graphs.nodeSurfaces(id);
        assertEquals(scanned.nodeCount(), stored.size());
        assertEquals(loaded.nodes().stream().filter(n -> n.surface().isStreet()).count(),
                stored.stream().filter(s -> s.surface().isStreet()).count());
        // The four-argument save keeps meaning "no hash".
        UUID plain = graphs.saveDraft(WORLD, "city", "default", scanned);
        assertNull(graphs.info(plain).paletteHash());
        assertEquals(description(scanned), description(graphs.loadGraph(plain)));
    }

    @Test
    void aCorridorKeepsTheSurfacesAndTheSourcesHash() throws SQLException {
        NavGraph scanned = withTags(street(), Map.of("2,1", Set.of("home"), "7,7", Set.of("work")));
        UUID id = graphs.saveDraft(WORLD, "city", "city", "b".repeat(64), scanned);
        NavGraph compact = NavCorridor.compact(graphs.loadGraph(id));
        assertTrue(compact.nodes().stream().anyMatch(n -> n.surface() == NavSurface.ROAD));
        assertTrue(compact.nodes().stream().noneMatch(n -> n.surface() == NavSurface.UNKNOWN));
        var info = graphs.info(id);
        UUID corridor = graphs.saveDraft(info.worldUuid(), info.regionId(), info.palette(), info.paletteHash(), compact);
        assertEquals("b".repeat(64), graphs.info(corridor).paletteHash());
        assertEquals(description(compact), description(graphs.loadGraph(corridor)));
    }

    // ---- tags on roads ----

    @Test
    void aStopCannotBeTaggedOnARoadNodeAndTagHereSnapsToTheSidewalk() throws SQLException {
        UUID id = graphs.saveDraft(WORLD, "city", "city", "c".repeat(64), street());
        NavGraph loaded = graphs.loadGraph(id);
        long road = nodeAt(loaded, 4, 64, 4);
        long sidewalk = nodeAt(loaded, 4, 64, 2);
        assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(id, road, "home", true));
        assertTrue(graphs.setDraftTag(id, sidewalk, "home", true));
        // Standing on the road, the nearest tag-able node is not the road node under the feet.
        assertEquals(Long.valueOf(nodeAt(loaded, 4, 64, 2)), graphs.nearestTagNode(id, WORLD, 4.5, 64, 3.4));
        assertNull(graphs.nearestTagNode(id, WORLD, 4.5, 64, 4.5), "only road nodes are within 1.5 blocks");
        assertEquals(Set.of("home"), graphs.loadGraph(id).node(sidewalk).tags());
    }

    // ---- copytags ----

    /** Source stops at three places; the target is a fresh scan where one has moved down and one now sits on a road. */
    private UUID sourceWithStops() throws SQLException {
        NavGraph g = NavGraph.builder()
                .addNode(1, 10, 64, 10, NavSurface.SIDEWALK, "home", "spawn", "crosswalk")
                .addNode(2, 20, 64, 10, NavSurface.SIDEWALK, "work")
                .addNode(3, 30, 65, 10, NavSurface.SIDEWALK, "market")
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).build();
        return graphs.saveDraft(WORLD, "city", "city", "d".repeat(64), g);
    }

    private UUID targetDraft() throws SQLException {
        NavGraph g = NavGraph.builder()
                .addNode(1, 10, 64, 10, NavSurface.SIDEWALK)
                .addNode(2, 20, 63, 10, NavSurface.SIDEWALK)
                .addNode(3, 30, 65, 10, NavSurface.ROAD)
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).build();
        return graphs.saveDraft(WORLD, "city", "city", "d".repeat(64), g);
    }

    private Map<String, Set<String>> tagsByPosition(UUID id) throws SQLException {
        return graphs.loadGraph(id).nodes().stream().filter(n -> !n.tags().isEmpty())
                .collect(Collectors.toMap(n -> n.x() + "," + n.y() + "," + n.z(), NavGraph.Node::tags));
    }

    @Test
    void copyTagsMatchesExactAndNearReportsTheRestAndNeverUsesARoad() throws SQLException {
        UUID source = sourceWithStops();
        UUID target = targetDraft();
        var report = graphs.copyTags(source, target, true);
        assertTrue(report.written());
        assertEquals(2, report.exact(), "home and spawn at the same position");
        assertEquals(1, report.near(), "work moved down one block");
        assertEquals(1, report.unmatched(), "market only has a road under it now");
        assertEquals(1, report.skippedInvalid(), "the legacy crosswalk tag is reported, not dropped silently");
        assertEquals(3, report.inserted());
        assertEquals(2, report.anchorNodes());
        assertTrue(report.details().stream().anyMatch(d -> d.startsWith("NEAR work") && d.contains("dy=-1") && d.contains("SIDEWALK")));
        assertTrue(report.details().stream().anyMatch(d -> d.startsWith("UNMATCHED market")));
        assertTrue(report.details().stream().anyMatch(d -> d.startsWith("SKIPPED_INVALID crosswalk")));
        assertEquals(Map.of("10,64,10", Set.of("home", "spawn"), "20,63,10", Set.of("work")), tagsByPosition(target));
        assertTrue(graphs.loadGraph(target).nodes().stream().filter(n -> n.surface().isStreet()).allMatch(n -> n.tags().isEmpty()));
        assertEquals(Set.of("home", "spawn", "work", "crosswalk", "market").size(),
                graphs.loadGraph(source).nodes().stream().mapToInt(n -> n.tags().size()).sum());
    }

    @Test
    void anUnmatchedTagWithoutPartialWritesNothingAtAll() throws SQLException {
        UUID source = sourceWithStops();
        UUID target = targetDraft();
        var report = graphs.copyTags(source, target, false);
        assertFalse(report.written());
        assertEquals(1, report.unmatched());
        assertEquals(0, report.inserted());
        assertEquals(Map.of(), tagsByPosition(target), "rolled back: not even the exact matches");
    }

    @Test
    void copyingTwiceIsIdempotent() throws SQLException {
        UUID source = sourceWithStops();
        UUID target = targetDraft();
        graphs.copyTags(source, target, true);
        var again = graphs.copyTags(source, target, true);
        assertEquals(0, again.inserted());
        assertEquals(3, again.alreadyPresent());
        assertEquals(Map.of("10,64,10", Set.of("home", "spawn"), "20,63,10", Set.of("work")), tagsByPosition(target));
    }

    @Test
    void theEightTagCapHoldsPerTargetNode() throws SQLException {
        NavGraph full = NavGraph.builder().addNode(1, 10, 64, 10, NavSurface.SIDEWALK, "spawn", "home", "work", "market",
                "leisure", "work:cook", "work:courier", "work:gardener").build();
        UUID target = graphs.saveDraft(WORLD, "city", "city", "d".repeat(64), full);
        NavGraph one = NavGraph.builder().addNode(1, 10, 64, 10, NavSurface.SIDEWALK, "work:carpenter").build();
        UUID source = graphs.saveDraft(WORLD, "city", "city", "d".repeat(64), one);
        var report = graphs.copyTags(source, target, false);
        assertFalse(report.written());
        assertEquals(1, report.refusedFull());
        assertEquals(8, graphs.loadGraph(target).nodes().iterator().next().tags().size());
        var partial = graphs.copyTags(source, target, true);
        assertTrue(partial.written());
        assertEquals(0, partial.inserted());
        assertEquals(8, graphs.loadGraph(target).nodes().iterator().next().tags().size());
    }

    @Test
    void onlyAnExistingDraftOfTheSameWorldCanReceiveTags() throws SQLException {
        UUID source = sourceWithStops();
        UUID active = targetDraft();
        assertTrue(graphs.publish(active));
        assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(source, active, true));
        UUID target = targetDraft();
        UUID elsewhere = graphs.saveDraft(UUID.randomUUID(), "city", "city", null, NavGraph.builder().addNode(1, 10, 64, 10).build());
        assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(elsewhere, target, true));
        assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(UUID.randomUUID(), target, true));
        assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(source, UUID.randomUUID(), true));
        assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(source, source, true));
        assertEquals(Map.of(), tagsByPosition(target));
    }

    // ---- restore ----

    @Test
    void restoreMakesAnIdenticalNewDraftAndNeverPublishes() throws SQLException {
        NavGraph scanned = withTags(street(), Map.of("2,1", Set.of("home"), "7,7", Set.of("work")));
        UUID first = graphs.saveDraft(WORLD, "city", "city", "e".repeat(64), scanned);
        assertTrue(graphs.publish(first));
        UUID second = graphs.saveDraft(WORLD, "city", "city", "f".repeat(64), street());
        assertTrue(graphs.publish(second));
        assertEquals("ARCHIVED", graphs.info(first).status());

        UUID restored = graphs.restore(first);
        var info = graphs.info(restored);
        assertEquals("DRAFT", info.status());
        assertEquals(graphs.info(second).version() + 1, info.version());
        assertEquals("city", info.palette());
        assertEquals("e".repeat(64), info.paletteHash());
        assertEquals(description(graphs.loadGraph(first)), description(graphs.loadGraph(restored)));
        assertEquals("ARCHIVED", graphs.info(first).status(), "the source stays archived");
        assertEquals(second, graphs.activeGraphId(WORLD, "city"), "restore never changes what is live");

        // Controls: an archived graph still cannot be published, and only archived graphs restore.
        assertFalse(graphs.publish(first));
        assertThrows(IllegalArgumentException.class, () -> graphs.restore(second));
        assertThrows(IllegalArgumentException.class, () -> graphs.restore(restored));
        assertThrows(IllegalArgumentException.class, () -> graphs.restore(null));
        assertThrows(IllegalArgumentException.class, () -> graphs.restore(UUID.randomUUID()));
        assertNotEquals(first, restored);
    }

    @Test
    void listActiveReturnsOnlyPublishedGraphs() throws SQLException {
        UUID draft = graphs.saveDraft(WORLD, "a", "city", null, street());
        UUID live = graphs.saveDraft(WORLD, "b", "city", "9".repeat(64), street());
        assertTrue(graphs.publish(live));
        List<UUID> active = graphs.listActive().stream().map(NavGraphRepository.GraphInfo::graphId).toList();
        assertTrue(active.contains(live) && active.contains(LEGACY_GRAPH));
        assertFalse(active.contains(draft));
    }

    // ---- plumbing ----

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = NavGraphSurfaceIT.class.getResourceAsStream(path)) {
            assertTrue(in != null, "missing migration " + path);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                st.setQueryTimeout(5);
                for (String statement : MigrationRunner.splitStatements(sql)) st.execute(statement);
            }
        }
    }
}
