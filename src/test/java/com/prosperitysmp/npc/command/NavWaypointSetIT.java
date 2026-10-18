package com.prosperitysmp.npc.command;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.nav.FileGraphCodec;
import com.prosperitysmp.npc.nav.NavCorridor;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.StreetAudit;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.origin.DocumentOrigin;
import com.prosperitysmp.npc.nav.waypoint.WaypointCodec;
import com.prosperitysmp.npc.nav.waypoint.WaypointDensifier;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Real document storage and the command's publish path; no Bukkit world or player is involved. */
@Timeout(30)
class NavWaypointSetIT {
    private static final UUID WORLD = new UUID(0, 6601);
    private static final UUID LEGACY = new UUID(0, 6602);
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    private static final String RULE_TEXT = DocumentOrigin.rules(RULES, 1);
    private static final String PALETTE = "a".repeat(64);
    private static final String EMPTY = WaypointCodec.canonical(WaypointSet.EMPTY);
    private static DB maria;
    private static int port;
    private Database database;
    private NavGraphRepository graphs;
    @TempDir Path reports;

    @BeforeAll static void start() throws Exception {
        var config = DBConfigurationBuilder.newBuilder();
        config.setPort(0);
        maria = DB.newEmbeddedDB(config.addArg("--bind-address=127.0.0.1").build());
        maria.start();
        port = config.getPort();
    }

    @AfterAll static void stop() throws Exception {
        if (maria != null) maria.stop();
    }

    @BeforeEach void schema() throws Exception {
        String schema = "npc_wp_" + UUID.randomUUID().toString().replace("-", "");
        maria.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", port, schema, "root", "", 6, 10_000L));
        apply("V1__initial_schema.sql");
        apply("V39__nav_surface.sql");
        execute("INSERT INTO npc_nav_graphs(graph_id,world_uuid,region_id,palette,version,status) "
                + "VALUES(?,?,'legacy','city',1,'ACTIVE')", LEGACY, WORLD);
        apply("V44__nav_graph_sources.sql");
        apply("V44__nav_graph_sources.sql");
        graphs = new NavGraphRepository(database);
    }

    @AfterEach void close() {
        if (database != null) database.close();
    }

    @Test void migrationReplayPreservesTheLegacyScan() throws Exception {
        var info = graphs.info(LEGACY);
        assertEquals("scan", info.source());
        assertEquals("ACTIVE", info.status());
        assertNull(info.originSha());
        assertNull(info.originRef());
        assertEquals(1, info.version());
        assertEquals(WORLD, info.worldUuid());
    }

    @Test void concurrentEditsKeepBothPointsAndTheirDocuments() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of("north", "south").stream().map(name -> pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return graphs.editWaypointSet(WORLD, "town", name, EMPTY, current -> {
                    var set = current == null ? WaypointSet.EMPTY : WaypointCodec.parse(current);
                    return WaypointCodec.canonical(set.addPoint(point(name, name.equals("north") ? 0 : 2),
                            WaypointSet.Limits.DEFAULT));
                });
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            var first = futures.get(0).get(8, TimeUnit.SECONDS);
            var second = futures.get(1).get(8, TimeUnit.SECONDS);
            assertNotEquals(first.revision(), second.revision());
            assertEquals(first.document(), graphs.routeDocument(first.docSha()));
            assertEquals(second.document(), graphs.routeDocument(second.docSha()));
        }
        graphs = new NavGraphRepository(database);
        var row = graphs.waypointSet(WORLD, "town");
        assertEquals(2, row.revision());
        assertEquals(List.of("north", "south"), WaypointCodec.parse(row.document()).points().stream()
                .map(WaypointSet.Point::name).toList());
        assertEquals(3, count("npc_nav_route_docs"));
        assertEquals(row.docSha(), NavGraphRepository.documentSha(row.document()));
    }

    @Test void aRefusedEditKeepsTheCurrentRevisionAndItsHistory() throws Exception {
        var row = writeSet(set());
        int documents = count("npc_nav_route_docs");
        assertThrows(WaypointSet.EditRefused.class, () -> graphs.editWaypointSet(WORLD, "town", "staff", EMPTY,
                current -> WaypointCodec.canonical(WaypointCodec.parse(current)
                        .addPoint(point("home", 9), WaypointSet.Limits.DEFAULT))));
        assertEquals(row, graphs.waypointSet(WORLD, "town"));
        assertEquals(row.document(), graphs.routeDocument(row.docSha()));
        assertEquals(documents, count("npc_nav_route_docs"));
    }

    @Test void editBuildPublishAndRestoreUseEachGraphsOwnDocument() throws Exception {
        var old = writeSet(set());
        UUID a = draft(old);
        assertPublished(publish(a, false, true, PALETTE, RULE_TEXT));
        var changed = writeSet(set().movePoint("work", 5, 64, 0));
        assertEquals(old.document(), graphs.routeDocument(old.docSha()));
        UUID stale = draft(old);
        assertRefused(publish(stale, false, true, PALETTE, RULE_TEXT), "older waypoint set");
        assertEquals("DRAFT", graphs.info(stale).status());
        assertEquals(a, graphs.activeGraphId(WORLD, "town"));
        UUID b = draft(changed);
        assertPublished(publish(b, false, true, PALETTE, RULE_TEXT));
        assertEquals("ARCHIVED", graphs.info(a).status());
        UUID restored = graphs.restore(a);
        assertEquals(old.docSha(), graphs.info(restored).originSha());
        assertRefused(publish(restored, false, true, PALETTE, RULE_TEXT), "older waypoint set");
        assertPublished(publish(restored, true, true, PALETTE, RULE_TEXT));
        assertEquals(restored, graphs.activeGraphId(WORLD, "town"));
        assertEquals("ARCHIVED", graphs.info(b).status());
        assertEquals(old.document(), graphs.routeDocument(graphs.info(restored).originSha()));
    }

    @ParameterizedTest @ValueSource(strings = {"palette", "reach", "length", "share", "step"})
    void aChangedBuildInputRefusesPublishEvenWithTheGeneralGateOff(String input) throws Exception {
        UUID id = draft(writeSet(set()));
        String palette = input.equals("palette") ? "b".repeat(64) : PALETTE;
        String rules = switch (input) {
            case "reach" -> DocumentOrigin.rules(new StreetRules(13, 16, 0.4), 1);
            case "length" -> DocumentOrigin.rules(new StreetRules(12, 17, 0.4), 1);
            case "share" -> DocumentOrigin.rules(new StreetRules(12, 16, 0.5), 1);
            case "step" -> DocumentOrigin.rules(RULES, 2);
            default -> RULE_TEXT;
        };
        assertRefused(publish(id, true, false, palette, rules), "changed since");
        assertEquals("DRAFT", graphs.info(id).status());
        assertNull(graphs.activeGraphId(WORLD, "town"));
        assertPublished(publish(id, false, false, PALETTE, RULE_TEXT));
    }

    @ParameterizedTest @ValueSource(strings = {"waypoints", "file"})
    void authoredTagsCannotBeEditedThroughScanCommands(String source) throws Exception {
        var row = writeSet(set());
        var graph = built(row.document());
        String text = source.equals("waypoints") ? row.document() : FileGraphCodec.write(graph, RULES);
        String sha = graphs.storeRouteDocument(source, text);
        UUID id = graphs.saveDraft(WORLD, "tags", "city", PALETTE, graph, source, sha, "route.yml");
        long node = graphs.loadGraph(id).nodes().iterator().next().id();
        String before = FileGraphCodec.write(graphs.loadGraph(id), RULES);
        assertThrows(IllegalArgumentException.class, () -> graphs.setDraftTag(id, node, "market", true));
        assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(id, id, true));
        assertEquals(before, FileGraphCodec.write(graphs.loadGraph(id), RULES));
    }

    @Test void fileDraftAndCorridorKeepOriginAcrossStorageAndRestore() throws Exception {
        NavGraph graph = built(WaypointCodec.canonical(set()));
        String text = FileGraphCodec.write(graph, RULES);
        String sha = graphs.storeRouteDocument("file", text);
        String fingerprint = DocumentOrigin.FILE.fingerprint(sha, PALETTE, RULE_TEXT);
        UUID first = graphs.saveDraft(WORLD, "file-town", "city", fingerprint, graph, "file", sha, "town.yml");
        String stored = FileGraphCodec.write(graphs.loadGraph(first), RULES);
        assertEquals(text, stored);
        var info = graphs.info(first);
        UUID compact = graphs.saveDraft(info.worldUuid(), info.regionId(), info.palette(), info.paletteHash(),
                NavCorridor.compact(graphs.loadGraph(first)), info.source(), info.originSha(), info.originRef());
        assertPublished(publish(first, false, true, PALETTE, RULE_TEXT));
        assertPublished(publish(compact, false, true, PALETTE, RULE_TEXT));
        UUID restored = graphs.restore(first);
        var copy = graphs.info(restored);
        assertEquals("file", copy.source());
        assertEquals(sha, copy.originSha());
        assertEquals("town.yml", copy.originRef());
        assertEquals(fingerprint, copy.paletteHash());
        assertEquals(text, graphs.routeDocument(copy.originSha()));
        assertEquals(text, FileGraphCodec.write(graphs.loadGraph(restored), RULES));
    }

    @Test void aCorruptStoredDocumentCannotAuthorizePublish() throws Exception {
        var row = writeSet(set());
        UUID id = draft(row);
        execute("UPDATE npc_nav_route_docs SET document=? WHERE doc_sha256=?", EMPTY, row.docSha());
        assertNull(graphs.routeDocument(row.docSha()));
        assertRefused(publish(id, true, false, PALETTE, RULE_TEXT), "changed since");
        assertEquals("DRAFT", graphs.info(id).status());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "unknown", "wrong-kind"})
    void missingOrUnrecognizedOriginsCannotPublish(String fault) throws Exception {
        var row = writeSet(set());
        String source = fault.equals("unknown") ? "future-format" : fault.equals("wrong-kind") ? "file" : "waypoints";
        String hash = DocumentOrigin.WAYPOINTS.fingerprint(row.docSha(), PALETTE, RULE_TEXT);
        UUID id = graphs.saveDraft(WORLD, "town", "city", hash, built(row.document()), source, row.docSha(), null);
        if (fault.equals("missing")) execute("DELETE FROM npc_nav_route_docs WHERE doc_sha256=?", row.docSha());
        assertRefused(publish(id, true, false, PALETTE, RULE_TEXT), fault.equals("unknown") ? "not known" : "changed since");
        assertEquals("DRAFT", graphs.info(id).status());
    }

    @Test void switchingOffTheGeneralGatePreservesTheScanBehavior() throws Exception {
        UUID id = graphs.saveDraft(WORLD, "scan-town", "city", "b".repeat(64), built(WaypointCodec.canonical(set())));
        assertPublished(publish(id, false, false, PALETTE, RULE_TEXT));
        assertEquals(id, graphs.activeGraphId(WORLD, "scan-town"));
    }

    private NavGraphRepository.WaypointSetRow writeSet(WaypointSet set) throws SQLException {
        return graphs.editWaypointSet(WORLD, "town", "staff", EMPTY, ignored -> WaypointCodec.canonical(set));
    }

    private UUID draft(NavGraphRepository.WaypointSetRow row) throws SQLException {
        String fingerprint = DocumentOrigin.WAYPOINTS.fingerprint(row.docSha(), PALETTE, RULE_TEXT);
        return graphs.saveDraft(WORLD, "town", "city", fingerprint, built(row.document()), "waypoints", row.docSha(), null);
    }

    private static NavGraph built(String document) {
        return WaypointDensifier.densify(WaypointCodec.parse(document), RULES, 1,
                WaypointDensifier.Limits.DEFAULT).graph();
    }

    private static WaypointSet.Point point(String name, int x) {
        return new WaypointSet.Point(name, x, 64, 0, WaypointSet.PointKind.SIDEWALK, new TreeSet<>());
    }

    private static WaypointSet set() {
        return WaypointSet.EMPTY.addPoint(point("home", 0).withTags(List.of("home", "market")), WaypointSet.Limits.DEFAULT)
                .addPoint(point("work", 4).withTags(List.of("work", "leisure")), WaypointSet.Limits.DEFAULT)
                .link("home", "work", WaypointSet.LinkKind.WALK, WaypointSet.Limits.DEFAULT);
    }

    private Reply publish(UUID id, boolean asBuilt, boolean gate, String palette, String rules) throws Exception {
        Class<?> type = Class.forName("com.prosperitysmp.npc.command.NpcNavCommand$PublishPolicy");
        var constructor = type.getDeclaredConstructor(boolean.class, boolean.class, Map.class, StreetAudit.Options.class,
                NpcNavCommand.ReportSink.class, String.class, boolean.class, String.class);
        constructor.setAccessible(true);
        var options = new StreetAudit.Options(RULES, 1_000_000_000L, 1024, System::nanoTime);
        var sink = new NpcNavCommand.ReportSink(Logger.getAnonymousLogger(), reports);
        Object policy = constructor.newInstance(gate, false, Map.of("city", palette), options, sink, "staff", asBuilt, rules);
        var method = NpcNavCommand.class.getDeclaredMethod("publishGraph", NavGraphRepository.class, UUID.class, type);
        method.setAccessible(true);
        return (Reply) method.invoke(null, graphs, id, policy);
    }

    private static void assertPublished(Reply reply) {
        assertTrue(reply.lines().stream().anyMatch(line -> line.text().startsWith("Published ")), reply.toString());
        assertNotNull(reply.invalidate());
    }

    private static void assertRefused(Reply reply, String reason) {
        assertTrue(reply.lines().stream().anyMatch(line -> line.text().contains(reason)), reply.toString());
        assertNull(reply.invalidate());
    }

    private void apply(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(in);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                execute(sql);
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = database.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID id) statement.setBytes(i + 1, Uuids.toBytes(id));
                else statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    private int count(String table) throws SQLException {
        try (var connection = database.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
