package com.prosperitysmp.npc.nav;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.nav.NavGraphRepository.CopyReport;
import com.prosperitysmp.npc.nav.NavGraphRepository.GraphInfo;
import com.prosperitysmp.npc.nav.NavGraphRepository.NodeSurface;
import com.prosperitysmp.npc.nav.NavGraphRepository.WaypointSetRow;
import java.util.HashSet;
import java.util.stream.Collectors;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph repository against a real MariaDB: versions, publish, listing, stored documents, restore and tag copying. */
class NavGraphRepositoryIT {
    private static final UUID WORLD = new UUID(0, 6801);
    private static final UUID OTHER_WORLD = new UUID(0, 6802);
    private static final String HASH = "c".repeat(64);
    private static DB mariaDb;
    private static Database database;
    private NavGraphRepository graphs;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        String schema = "npc_nav_repo_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
        applyResource("/db/migration/V1__initial_schema.sql");
        applyResource("/db/migration/V5__resident_route_tags.sql");
        applyResource("/db/migration/V39__nav_surface.sql");
        applyResource("/db/migration/V44__nav_graph_sources.sql");
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
    void emptyTables() throws SQLException {
        for (String table : List.of("npc_nav_tags", "npc_nav_edges", "npc_nav_nodes", "npc_nav_graphs",
                "npc_nav_waypoint_sets", "npc_nav_route_docs")) {
            execute("DELETE FROM " + table);
        }
        graphs = new NavGraphRepository(database);
    }

    /** Four sidewalk nodes in a square, one tagged, every neighbour pair joined both ways at cost 10. */
    private static NavGraph square() {
        return NavGraph.builder()
                .addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home").addNode(2, 1, 64, 0, NavSurface.SIDEWALK)
                .addNode(3, 0, 64, 1, NavSurface.SIDEWALK).addNode(4, 1, 64, 1, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).addBiEdge(1, 3, 10).addBiEdge(2, 4, 10).addBiEdge(3, 4, 10).build();
    }

    private UUID draft(UUID world, String region) throws SQLException {
        return graphs.saveDraft(world, region, "city", HASH, square());
    }

    private UUID published(UUID world, String region) throws SQLException {
        UUID id = draft(world, region);
        assertTrue(graphs.publish(id));
        return id;
    }

    private static String where(NavGraph.Node node) {
        return node.x() + "," + node.y() + "," + node.z();
    }

    // ---- saving ----

    @Test
    void aSavedDraftIsVersionOneAndDraftWithItsCountsAndPaletteAndScanSource() throws SQLException {
        UUID id = draft(WORLD, "mid");
        GraphInfo info = graphs.info(id);
        assertEquals(id, info.graphId());
        assertEquals(WORLD, info.worldUuid());
        assertEquals("mid", info.regionId());
        assertEquals("city", info.palette());
        assertEquals(1, info.version());
        assertEquals("DRAFT", info.status());
        assertEquals(4, info.nodeCount());
        assertEquals(8, info.edgeCount());
        assertEquals(HASH, info.paletteHash());
        assertEquals("scan", info.source());
        assertNull(info.originSha());
        assertNull(info.originRef());
    }

    @Test
    void aGraphSavedWithoutAPaletteHashStoresNone() throws SQLException {
        UUID id = graphs.saveDraft(WORLD, "mid", "old", square());
        assertNull(graphs.info(id).paletteHash());
        assertEquals("old", graphs.info(id).palette());
    }

    @Test
    void versionsCountUpWithinOneWorldAndRegionAndStartAtOneElsewhere() throws SQLException {
        assertEquals(1, graphs.info(draft(WORLD, "mid")).version());
        assertEquals(2, graphs.info(draft(WORLD, "mid")).version());
        assertEquals(3, graphs.info(draft(WORLD, "mid")).version());
        assertEquals(1, graphs.info(draft(WORLD, "other")).version());
        assertEquals(1, graphs.info(draft(OTHER_WORLD, "mid")).version());
    }

    @Test
    void aDraftRecordsHowItWasMadeAndFromWhichDocument() throws SQLException {
        String sha = "ab".repeat(32);
        UUID id = graphs.saveDraft(WORLD, "mid", "city", HASH, square(), NavGraphRepository.SOURCE_FILE, sha, "district.yml");
        GraphInfo info = graphs.info(id);
        assertEquals("file", info.source());
        assertEquals(sha, info.originSha());
        assertEquals("district.yml", info.originRef());
    }

    @Test
    void aNullSourceIsStoredAsScan() throws SQLException {
        UUID id = graphs.saveDraft(WORLD, "mid", "city", HASH, square(), null, null, null);
        assertEquals("scan", graphs.info(id).source());
    }

    @Test
    void anEdgeToAnIdNotInTheGraphIsLeftOutOfTheStoredEdges() throws SQLException {
        NavGraph dangling = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0)
                .addEdge(1, 2, 10).addEdge(1, 99, 10).addEdge(98, 2, 10).build();
        UUID id = graphs.saveDraft(WORLD, "mid", "city", HASH, dangling);
        assertEquals(1, graphs.info(id).edgeCount());
    }

    @Test
    void anEmptyGraphIsStoredAndLoadsBackEmptyNotNull() throws SQLException {
        UUID id = graphs.saveDraft(WORLD, "mid", "city", HASH, NavGraph.builder().build());
        assertEquals(0, graphs.info(id).nodeCount());
        NavGraph loaded = graphs.loadGraph(id);
        assertNotNull(loaded);
        assertEquals(0, loaded.nodeCount());
        assertEquals(0, loaded.edgeCount());
    }

    // ---- loading ----

    @Test
    void anUnknownGraphHasNoInfoAndLoadsAsNull() throws SQLException {
        UUID nobody = new UUID(7, 7);
        assertNull(graphs.info(nobody));
        assertNull(graphs.loadGraph(nobody));
    }

    @Test
    void aLoadedGraphKeepsPositionsTagsSurfacesWidthsAndCostsUnderNewIds() throws SQLException {
        NavGraph source = NavGraph.builder()
                .addNode(new NavGraph.Node(10, 5, 70, -3, Set.of("home", "work"), NavSurface.ACCESS, 0))
                .addNode(new NavGraph.Node(20, 6, 70, -3, Set.of(), NavSurface.ROAD, 9))
                .addEdge(10, 20, 24).addEdge(20, 10, 10).build();
        NavGraph loaded = graphs.loadGraph(graphs.saveDraft(WORLD, "mid", "city", HASH, source));
        Map<String, NavGraph.Node> byPosition = new HashMap<>();
        loaded.nodes().forEach(n -> byPosition.put(where(n), n));
        NavGraph.Node access = byPosition.get("5,70,-3");
        NavGraph.Node road = byPosition.get("6,70,-3");
        assertEquals(Set.of("home", "work"), access.tags());
        assertEquals(NavSurface.ACCESS, access.surface());
        assertEquals(0, access.roadWidth());
        assertEquals(Set.of(), road.tags());
        assertEquals(NavSurface.ROAD, road.surface());
        assertEquals(9, road.roadWidth());
        Map<String, Integer> costs = new HashMap<>();
        loaded.edges().forEach(e -> costs.put(where(loaded.node(e.from())) + ">" + where(loaded.node(e.to())), e.cost()));
        assertEquals(Map.of("5,70,-3>6,70,-3", 24, "6,70,-3>5,70,-3", 10), costs);
    }

    @Test
    void aNodeWithNoRecordedWidthIsStoredAsNullNotZero() throws SQLException {
        NavGraph source = NavGraph.builder().addNode(new NavGraph.Node(1, 0, 64, 0, Set.of(), NavSurface.ROAD, 0))
                .addNode(new NavGraph.Node(2, 1, 64, 0, Set.of(), NavSurface.ROAD, 4)).build();
        UUID id = graphs.saveDraft(WORLD, "mid", "city", HASH, source);
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT x, road_width FROM npc_nav_nodes WHERE graph_id = ? ORDER BY x")) {
            ps.setBytes(1, Uuids.toBytes(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getObject(2));
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(2));
            }
        }
    }

    @Test
    void nodeSurfacesListEveryNodeWithItsClassInAscendingStoredIdOrder() throws SQLException {
        NavGraph source = NavGraph.builder()
                .addNode(1, 3, 64, 3, NavSurface.CROSSING).addNode(2, 1, 64, 1, NavSurface.SIDEWALK).addNode(3, 2, 65, 2, NavSurface.UNKNOWN).build();
        UUID id = graphs.saveDraft(WORLD, "mid", "city", HASH, source);
        List<NodeSurface> rows = graphs.nodeSurfaces(id);
        assertEquals(3, rows.size());
        Set<String> positions = new HashSet<>();
        rows.forEach(r -> positions.add(r.x() + "," + r.y() + "," + r.z() + " " + r.surface()));
        assertEquals(Set.of("3,64,3 CROSSING", "1,64,1 SIDEWALK", "2,65,2 UNKNOWN"), positions);
        assertTrue(rows.get(0).nodeId() < rows.get(1).nodeId() && rows.get(1).nodeId() < rows.get(2).nodeId());
        assertEquals(List.of(), graphs.nodeSurfaces(new UUID(9, 9)));
    }

    // ---- publishing ----

    @Test
    void aRegionWithOnlyDraftsHasNoActiveGraph() throws SQLException {
        draft(WORLD, "mid");
        assertNull(graphs.activeGraphId(WORLD, "mid"));
        assertNull(graphs.loadActive(WORLD, "mid"));
        assertNull(graphs.loadActive(WORLD, "never-scanned"));
    }

    @Test
    void publishingMakesTheDraftActiveAndLoadActiveReturnsItsNodes() throws SQLException {
        UUID id = published(WORLD, "mid");
        assertEquals("ACTIVE", graphs.info(id).status());
        assertEquals(id, graphs.activeGraphId(WORLD, "mid"));
        assertEquals(4, graphs.loadActive(WORLD, "mid").nodeCount());
    }

    @Test
    void publishingANewerGraphArchivesTheOldOneAndLoadActiveFollowsTheNewer() throws SQLException {
        UUID first = published(WORLD, "mid");
        UUID second = draft(WORLD, "mid");
        assertEquals(first, graphs.activeGraphId(WORLD, "mid"), "a draft does not displace the live graph");
        assertTrue(graphs.publish(second));
        assertEquals("ARCHIVED", graphs.info(first).status());
        assertEquals("ACTIVE", graphs.info(second).status());
        assertEquals(second, graphs.activeGraphId(WORLD, "mid"));
    }

    @Test
    void onlyAnExistingDraftCanBePublished() throws SQLException {
        assertFalse(graphs.publish(null));
        assertFalse(graphs.publish(new UUID(5, 5)));
        UUID id = published(WORLD, "mid");
        assertFalse(graphs.publish(id), "an active graph is not a draft");
        UUID next = published(WORLD, "mid");
        assertEquals("ARCHIVED", graphs.info(id).status());
        assertFalse(graphs.publish(id), "an archived graph is not a draft");
        assertEquals(next, graphs.activeGraphId(WORLD, "mid"));
    }

    @Test
    void publishingOneRegionLeavesOtherRegionsAndWorldsActive() throws SQLException {
        UUID a = published(WORLD, "mid");
        UUID b = published(WORLD, "north");
        UUID c = published(OTHER_WORLD, "mid");
        published(WORLD, "mid");
        assertEquals("ACTIVE", graphs.info(b).status());
        assertEquals("ACTIVE", graphs.info(c).status());
        assertEquals("ARCHIVED", graphs.info(a).status());
    }

    @Test
    void aPublishStampsTheTimeItWasPublished() throws SQLException {
        UUID id = draft(WORLD, "mid");
        assertNull(scalar("SELECT published_at FROM npc_nav_graphs WHERE graph_id = ?", Uuids.toBytes(id)));
        graphs.publish(id);
        assertNotNull(scalar("SELECT published_at FROM npc_nav_graphs WHERE graph_id = ?", Uuids.toBytes(id)));
    }

    // ---- listing ----

    private void createdAt(UUID id, String time) throws SQLException {
        execute("UPDATE npc_nav_graphs SET created_at = ? WHERE graph_id = ?", time, Uuids.toBytes(id));
    }

    @Test
    void listReturnsTheNewestGraphsFirstUpToTheLimit() throws SQLException {
        UUID oldest = draft(WORLD, "a");
        UUID middle = draft(WORLD, "b");
        UUID newest = draft(WORLD, "c");
        createdAt(oldest, "2026-01-01 00:00:01.000");
        createdAt(middle, "2026-01-02 00:00:01.000");
        createdAt(newest, "2026-01-03 00:00:01.000");
        assertEquals(List.of(newest, middle, oldest), graphs.list(10).stream().map(GraphInfo::graphId).toList());
        assertEquals(List.of(newest, middle), graphs.list(2).stream().map(GraphInfo::graphId).toList());
    }

    @Test
    void aLimitBelowOneStillReturnsOneGraph() throws SQLException {
        draft(WORLD, "a");
        draft(WORLD, "b");
        assertEquals(1, graphs.list(0).size());
        assertEquals(1, graphs.list(-5).size());
    }

    @Test
    void listCarriesTheSourceAndOriginOfEachRow() throws SQLException {
        UUID id = graphs.saveDraft(WORLD, "a", "city", HASH, square(), "waypoints", "ef".repeat(32), null);
        GraphInfo row = graphs.list(5).get(0);
        assertEquals(id, row.graphId());
        assertEquals("waypoints", row.source());
        assertEquals("ef".repeat(32), row.originSha());
        assertEquals(4, row.nodeCount());
        assertEquals(8, row.edgeCount());
    }

    @Test
    void listActiveHoldsOnlyPublishedGraphsNewestFirst() throws SQLException {
        UUID live = published(WORLD, "live");
        UUID alsoLive = published(WORLD, "other");
        draft(WORLD, "draft-only");
        UUID retired = published(WORLD, "retired");
        published(WORLD, "retired");
        UUID retiredLive = graphs.activeGraphId(WORLD, "retired");
        createdAt(live, "2026-02-01 00:00:01.000");
        createdAt(retiredLive, "2026-02-02 00:00:01.000");
        createdAt(alsoLive, "2026-02-03 00:00:01.000");
        List<GraphInfo> active = graphs.listActive();
        assertEquals(List.of(alsoLive, retiredLive, live), active.stream().map(GraphInfo::graphId).toList());
        assertTrue(active.stream().allMatch(g -> g.status().equals("ACTIVE")));
        assertFalse(active.stream().anyMatch(g -> g.graphId().equals(retired)));
    }

    // ---- restore ----

    @Test
    void anArchivedGraphIsRestoredAsANewDraftOfTheNextVersionWithEverythingItHad() throws SQLException {
        UUID first = graphs.saveDraft(WORLD, "mid", "city", HASH, square(), "waypoints", "ab".repeat(32), "set");
        graphs.publish(first);
        published(WORLD, "mid");
        UUID restored = graphs.restore(first);
        GraphInfo info = graphs.info(restored);
        assertEquals("DRAFT", info.status());
        assertEquals(3, info.version());
        assertEquals(4, info.nodeCount());
        assertEquals(8, info.edgeCount());
        assertEquals(HASH, info.paletteHash());
        assertEquals("waypoints", info.source());
        assertEquals("ab".repeat(32), info.originSha());
        assertEquals("set", info.originRef());
        assertEquals(Set.of("home"), graphs.loadGraph(restored).nodes().stream().flatMap(n -> n.tags().stream()).collect(Collectors.toSet()));
        assertEquals("ARCHIVED", graphs.info(first).status(), "the original stays as it was");
    }

    @Test
    void onlyAnArchivedGraphCanBeRestored() throws SQLException {
        String message = "Only an ARCHIVED graph can be restored.";
        UUID active = published(WORLD, "mid");
        UUID draft = draft(WORLD, "mid");
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> graphs.restore(active)).getMessage());
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> graphs.restore(draft)).getMessage());
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> graphs.restore(new UUID(3, 3))).getMessage());
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> graphs.restore(null)).getMessage());
    }

    // ---- stored documents ----

    @Test
    void aStoredDocumentComesBackByItsShaAndStoringItAgainChangesNothing() throws SQLException {
        String text = "format: prosperity-nav-waypoints/1\npoints: []\nlinks: []\n";
        String sha = graphs.storeRouteDocument("waypoints", text);
        assertEquals(NavGraphRepository.documentSha(text), sha);
        assertEquals(text, graphs.routeDocument(sha));
        assertEquals(sha, graphs.storeRouteDocument("waypoints", text));
        assertEquals(1, ((Number) scalar("SELECT COUNT(*) FROM npc_nav_route_docs")).intValue());
    }

    @Test
    void aShaThatWasNeverStoredAndANullShaFindNothing() throws SQLException {
        assertNull(graphs.routeDocument("0".repeat(64)));
        assertNull(graphs.routeDocument(null));
    }

    @Test
    void aDocumentWhoseTextNoLongerMatchesItsShaIsNotReturned() throws SQLException {
        String sha = graphs.storeRouteDocument("file", "original");
        execute("UPDATE npc_nav_route_docs SET document = 'tampered' WHERE doc_sha256 = ?", sha);
        assertNull(graphs.routeDocument(sha));
    }

    @Test
    void textWithNonAsciiCharactersSurvivesTheRoundTrip() throws SQLException {
        String text = "name: café €\n";
        assertEquals(text, graphs.routeDocument(graphs.storeRouteDocument("file", text)));
    }

    // ---- waypoint sets ----

    private static final String EMPTY = "format: prosperity-nav-waypoints/1\npoints: []\nlinks: []\n";

    @Test
    void aRegionWithNoSetHasNone() throws SQLException {
        assertNull(graphs.waypointSet(WORLD, "mid"));
    }

    @Test
    void theFirstEditSeesNoDocumentAndTheNextSeesTheFirstEditsResult() throws SQLException {
        List<String> seen = new ArrayList<>();
        WaypointSetRow one = graphs.editWaypointSet(WORLD, "mid", "alice", EMPTY, current -> {
            seen.add(String.valueOf(current));
            return "first";
        });
        WaypointSetRow two = graphs.editWaypointSet(WORLD, "mid", "bob", EMPTY, current -> {
            seen.add(current);
            return "second";
        });
        assertEquals(List.of("null", "first"), seen);
        assertEquals(1, one.revision());
        assertEquals("first", one.document());
        assertEquals("alice", one.updatedBy());
        assertEquals(NavGraphRepository.documentSha("first"), one.docSha());
        assertEquals(2, two.revision());
        assertEquals("second", two.document());
        WaypointSetRow current = graphs.waypointSet(WORLD, "mid");
        assertEquals(2, current.revision());
        assertEquals("second", current.document());
        assertEquals("bob", current.updatedBy());
        assertEquals(two.docSha(), current.docSha());
    }

    @Test
    void everyEditsDocumentIsKeptForGood() throws SQLException {
        graphs.editWaypointSet(WORLD, "mid", "a", EMPTY, current -> "first");
        graphs.editWaypointSet(WORLD, "mid", "a", EMPTY, current -> "second");
        assertEquals("first", graphs.routeDocument(NavGraphRepository.documentSha("first")));
        assertEquals("second", graphs.routeDocument(NavGraphRepository.documentSha("second")));
        assertEquals(EMPTY, graphs.routeDocument(NavGraphRepository.documentSha(EMPTY)), "the seed is kept too");
    }

    @Test
    void theEditorNameIsCutToThirtyTwoCharactersAndMayBeNull() throws SQLException {
        WaypointSetRow cut = graphs.editWaypointSet(WORLD, "mid", "n".repeat(40), EMPTY, current -> "x");
        assertEquals("n".repeat(32), cut.updatedBy());
        assertEquals("n".repeat(32), graphs.waypointSet(WORLD, "mid").updatedBy());
        WaypointSetRow anonymous = graphs.editWaypointSet(WORLD, "mid", null, EMPTY, current -> "y");
        assertNull(anonymous.updatedBy());
        assertNull(graphs.waypointSet(WORLD, "mid").updatedBy());
    }

    @Test
    void anEditThatRefusesLeavesTheRevisionAndTheDocumentAsTheyWereAndPassesTheFailureOn() throws SQLException {
        graphs.editWaypointSet(WORLD, "mid", "a", EMPTY, current -> "kept");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> graphs.editWaypointSet(WORLD, "mid", "a", EMPTY, current -> {
                    throw new IllegalArgumentException("nope");
                }));
        assertEquals("nope", refused.getMessage());
        WaypointSetRow row = graphs.waypointSet(WORLD, "mid");
        assertEquals(1, row.revision());
        assertEquals("kept", row.document());
    }

    @Test
    void aFirstEditThatRefusesLeavesTheEmptySeedAtRevisionZero() throws SQLException {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        assertThrows(IllegalStateException.class, () -> graphs.editWaypointSet(WORLD, "fresh", "a", EMPTY, current -> {
            seen.set(String.valueOf(current));
            throw new IllegalStateException("refused");
        }));
        assertEquals("null", seen.get());
        WaypointSetRow row = graphs.waypointSet(WORLD, "fresh");
        assertEquals(0, row.revision());
        assertEquals(EMPTY, row.document());
        assertNull(row.updatedBy());
    }

    @Test
    void setsAreKeptPerWorldAndRegion() throws SQLException {
        graphs.editWaypointSet(WORLD, "mid", "a", EMPTY, current -> "one");
        graphs.editWaypointSet(OTHER_WORLD, "mid", "a", EMPTY, current -> "two");
        graphs.editWaypointSet(WORLD, "north", "a", EMPTY, current -> "three");
        assertEquals("one", graphs.waypointSet(WORLD, "mid").document());
        assertEquals("two", graphs.waypointSet(OTHER_WORLD, "mid").document());
        assertEquals("three", graphs.waypointSet(WORLD, "north").document());
    }

    // ---- copying tags ----

    private Map<String, Long> idsByPosition(UUID graph) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        for (NodeSurface row : graphs.nodeSurfaces(graph)) out.put(row.x() + "," + row.y() + "," + row.z(), row.nodeId());
        return out;
    }

    private UUID taggedSource() throws SQLException {
        NavGraph source = NavGraph.builder()
                .addNode(1, 1, 64, 1, NavSurface.SIDEWALK, "bank", "home")
                .addNode(2, 5, 64, 5, NavSurface.SIDEWALK, "work")
                .addNode(3, 9, 64, 9, NavSurface.SIDEWALK, "market")
                .addNode(4, 3, 64, 3, NavSurface.SIDEWALK, "home").build();
        return graphs.saveDraft(WORLD, "mid", "city", HASH, source);
    }

    private UUID copyTarget() throws SQLException {
        NavGraph target = NavGraph.builder()
                .addNode(1, 1, 64, 1, NavSurface.SIDEWALK)
                .addNode(2, 5, 65, 5, NavSurface.SIDEWALK)
                .addNode(3, 3, 64, 3, NavSurface.SIDEWALK, "home").build();
        return graphs.saveDraft(WORLD, "mid", "city", HASH, target);
    }

    @Test
    void theCopyReportNamesEveryTagWhatHappenedToItAndWhere() throws SQLException {
        UUID from = taggedSource();
        UUID to = copyTarget();
        Map<String, Long> ids = idsByPosition(to);
        CopyReport report = graphs.copyTags(from, to, true);
        assertTrue(report.written());
        assertEquals(1, report.exact());
        assertEquals(1, report.near());
        assertEquals(1, report.unmatched());
        assertEquals(1, report.skippedInvalid());
        assertEquals(0, report.refusedFull());
        assertEquals(2, report.inserted());
        assertEquals(1, report.alreadyPresent());
        assertEquals(3, report.anchorNodes());
        assertEquals(5, report.details().size());
        assertEquals(Set.of(
                "SKIPPED_INVALID bank @1,64,1",
                "EXACT home @1,64,1 -> node " + ids.get("1,64,1") + " SIDEWALK",
                "NEAR work @5,64,5 -> node " + ids.get("5,65,5") + " SIDEWALK dy=1",
                "UNMATCHED market @9,64,9",
                "PRESENT home @3,64,3 -> node " + ids.get("3,64,3") + " SIDEWALK"), Set.copyOf(report.details()));
    }

    @Test
    void withoutPartialAnyUnmatchedTagMeansNothingIsWrittenButTheReportStillCounts() throws SQLException {
        UUID from = taggedSource();
        UUID to = copyTarget();
        CopyReport report = graphs.copyTags(from, to, false);
        assertFalse(report.written());
        assertEquals(0, report.inserted());
        assertEquals(1, report.exact());
        assertEquals(1, report.unmatched());
        Set<String> tagsAfter = new HashSet<>();
        graphs.loadGraph(to).nodes().forEach(n -> tagsAfter.addAll(n.tags()));
        assertEquals(Set.of("home"), tagsAfter, "only the tag that was already there");
    }

    @Test
    void theCopiedTagsAreReadBackOnTheTargetAfterAPartialCopy() throws SQLException {
        UUID from = taggedSource();
        UUID to = copyTarget();
        graphs.copyTags(from, to, true);
        Map<String, Set<String>> byPosition = new HashMap<>();
        graphs.loadGraph(to).nodes().forEach(n -> byPosition.put(where(n), n.tags()));
        assertEquals(Set.of("home"), byPosition.get("1,64,1"));
        assertEquals(Set.of("work"), byPosition.get("5,65,5"));
        assertEquals(Set.of("home"), byPosition.get("3,64,3"));
    }

    @Test
    void copyingNeedsTwoDifferentGraphsThatExistInOneWorld() throws SQLException {
        UUID from = taggedSource();
        UUID to = copyTarget();
        assertEquals("Pick two different graphs.", assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(from, from, true)).getMessage());
        assertEquals("Pick two different graphs.", assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(null, to, true)).getMessage());
        assertEquals("Pick two different graphs.", assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(from, null, true)).getMessage());
        UUID missing = new UUID(8, 8);
        assertEquals("No graph " + missing, assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(missing, to, true)).getMessage());
        UUID elsewhere = graphs.saveDraft(OTHER_WORLD, "mid", "city", HASH, square());
        assertEquals("Those graphs are in different worlds.",
                assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(elsewhere, to, true)).getMessage());
    }

    @Test
    void onlyADraftCanReceiveCopiedTags() throws SQLException {
        UUID from = taggedSource();
        UUID active = published(WORLD, "other");
        assertEquals("Only an existing DRAFT graph can receive tags.",
                assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(from, active, true)).getMessage());
        assertEquals("Only an existing DRAFT graph can receive tags.",
                assertThrows(IllegalArgumentException.class, () -> graphs.copyTags(from, new UUID(4, 4), true)).getMessage());
    }

    // ---- fixtures ----

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static Object scalar(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1);
            }
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = NavGraphRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing migration " + path);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                st.setQueryTimeout(5);
                for (String statement : MigrationRunner.splitStatements(sql)) st.execute(statement);
            }
        }
    }
}
