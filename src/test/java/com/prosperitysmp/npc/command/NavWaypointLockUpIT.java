package com.prosperitysmp.npc.command;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.waypoint.WaypointCodec;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A region's waypoint set can never be locked by a document that does not read back, and one that does not can be replaced. */
@Timeout(30)
class NavWaypointLockUpIT {
    private static final UUID WORLD = new UUID(0, 6611);
    private static final String EMPTY = WaypointCodec.canonical(WaypointSet.EMPTY);
    // What an unchecked link end used to store: the quote is copied in unescaped, so the text is not YAML.
    private static final String UNREADABLE = """
            format: prosperity-nav-waypoints/1
            points:
              - {name: a, at: [0, 64, 0], kind: sidewalk}
            links:
              - {a: a, b: "x"y", kind: walk}
            """;
    private static final String GOOD_FILE = """
            format: prosperity-nav-waypoints/1
            points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 5]}]
            links: [{a: a, b: b}]
            """;
    private static DB maria;
    private static int port;
    private Database database;
    private NavGraphRepository graphs;
    private NpcNavCommand.ReportSink sink;
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
        String schema = "npc_wplock_" + UUID.randomUUID().toString().replace("-", "");
        maria.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", port, schema, "root", "", 6, 10_000L));
        apply("V1__initial_schema.sql");
        apply("V39__nav_surface.sql");
        apply("V44__nav_graph_sources.sql");
        graphs = new NavGraphRepository(database);
        sink = new NpcNavCommand.ReportSink(Logger.getLogger("NavWaypointLockUpIT"), reports);
    }

    @AfterEach void close() {
        if (database != null) database.close();
    }

    private Reply edit(String op, java.util.function.UnaryOperator<WaypointSet> change, boolean replace) throws SQLException {
        return NavWaypointCommands.editSet(graphs, sink, WORLD, "town", op, "-", "staff", change, null, replace);
    }

    private static void assertRefused(Reply reply, String code) {
        Line line = reply.lines().getFirst();
        assertEquals(NamedTextColor.RED, line.color(), line.text());
        assertTrue(line.text().startsWith(code), line.text());
    }

    @Test void anEditThatWouldNotReadBackIsRefusedAndNothingIsStored() throws Exception {
        assertEquals(NamedTextColor.GREEN, edit("import", set -> WaypointCodec.parse(GOOD_FILE), true).lines().getFirst().color());
        var before = graphs.waypointSet(WORLD, "town");
        WaypointSet bad = WaypointSet.of(List.of(new WaypointSet.Point("x\"y", 1, 64, 1, WaypointSet.PointKind.SIDEWALK, null)), List.of());
        assertRefused(edit("import", set -> bad, true), "WP_FORMAT");
        assertEquals(before, graphs.waypointSet(WORLD, "town"));
        // The stored set still reads, so the next edit works.
        assertEquals(NamedTextColor.GREEN, edit("remove", set -> set.removePoint("b"), false).lines().getFirst().color());
        assertEquals(1, WaypointCodec.parse(graphs.waypointSet(WORLD, "town").document()).points().size());
    }

    @Test void aSetThatNoLongerReadsCanStillBeReplacedByAnImport() throws Exception {
        graphs.editWaypointSet(WORLD, "town", "old", EMPTY, current -> UNREADABLE);
        assertThrows(WaypointSet.EditRefused.class, () -> WaypointCodec.parse(graphs.waypointSet(WORLD, "town").document()));
        var stuck = graphs.waypointSet(WORLD, "town");
        // An ordinary edit reads the set first, so it is refused and changes nothing.
        assertRefused(edit("remove", set -> set.removePoint("a"), false), "WP_FORMAT");
        assertEquals(stuck, graphs.waypointSet(WORLD, "town"));
        // An import replaces the whole set without reading it.
        assertEquals(NamedTextColor.GREEN, edit("import", set -> WaypointCodec.parse(GOOD_FILE), true).lines().getFirst().color());
        var row = graphs.waypointSet(WORLD, "town");
        assertEquals(stuck.revision() + 1, row.revision());
        assertEquals(WaypointCodec.parse(GOOD_FILE), WaypointCodec.parse(row.document()));
        assertNotNull(NavWaypointCommands.listReply(graphs, WORLD, "town", 1));
    }

    @Test void aBuildRefusedBeforeTheWorldReadStillWritesItsReport(@TempDir Path builds, @TempDir Path imports) throws Exception {
        String bent = """
                format: prosperity-nav-waypoints/1
                points: [{name: a, at: [0, 64, 0]}, {name: b, at: [3, 64, 4]}]
                links: [{a: a, b: b}]
                """;
        assertEquals(NamedTextColor.GREEN, edit("import", set -> WaypointCodec.parse(bent), true).lines().getFirst().color());
        Reply reply = NavWaypointCommands.buildWaypoints(graphs, WORLD, "town",
                new NavWaypointCommands.PaletteChoice("city", null, "h"), NavWaypointCommandRulesTest.settings(builds),
                NavWaypointCommandRulesTest.config(imports), false);
        assertEquals(NamedTextColor.RED, reply.lines().getFirst().color());
        assertEquals(null, reply.next(), "no world read is started");
        String json = NavWaypointCommandRulesTest.onlyReport(builds, "wpbuild");
        assertTrue(json.contains("\"verdict\":\"REFUSED\""), json);
        assertTrue(json.contains("WP_NOT_AXIS"), json);
        assertTrue(json.contains(graphs.waypointSet(WORLD, "town").docSha()), json);
    }

    private void apply(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(in);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                try (var connection = database.getConnection(); var statement = connection.prepareStatement(sql)) {
                    statement.executeUpdate();
                }
            }
        }
    }
}
