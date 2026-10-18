package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.StrictYaml;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules behind the waypoint and route-file commands: argument reading, export names and publish caps. */
class NavWaypointCommandRulesTest {

    private static boolean world(String op, String... tokens) {
        return NavWaypointCommands.worldCandidate(op, new ArrayList<>(List.of(tokens)));
    }

    @Test
    void aTrailingWorldIsOnlyReadWhenThereAreMoreTokensThanTheOpNeeds() {
        // A point, tag or file that shares a world's name ("spawn") stays what it is.
        assertFalse(world("remove", "spawn"));
        assertTrue(world("remove", "spawn", "world"));
        assertFalse(world("unlink", "a", "spawn"));
        assertTrue(world("unlink", "a", "b", "world"));
        assertFalse(world("link", "a", "spawn"));
        assertFalse(world("link", "a", "b", "cross"));
        assertFalse(world("link", "a", "b", "CROSSWALK"));
        assertTrue(world("link", "a", "b", "world"));
        assertTrue(world("link", "a", "b", "walk", "world"));
        assertFalse(world("tag", "hub", "spawn"));
        assertFalse(world("tag", "hub", "spawn", "remove"));
        assertTrue(world("tag", "hub", "spawn", "world"));
        assertTrue(world("tag", "hub", "spawn", "remove", "world"));
        assertFalse(world("import", "spawn.yml"));
        assertTrue(world("import", "spawn.yml", "world"));
        assertFalse(world("list"));
        assertFalse(world("list", "2"));
        assertTrue(world("list", "world"));
        assertTrue(world("list", "2", "world"));
        assertFalse(world("build", "load"));
        assertTrue(world("build", "city"));
        assertTrue(world("build", "city", "load", "world"));
        assertFalse(world("check"));
        assertTrue(world("check", "world"));
        assertTrue(world("export", "world"));
    }

    @Test
    void exportNamesAlwaysFitTheImportFileRule() {
        String stamp = "-20261018120000";
        String graph = "-0123abcd" + stamp;
        // A short region keeps today's name.
        assertEquals("town" + stamp + ".yml", NavWaypointCommands.exportName("town", stamp));
        assertEquals("town" + graph + ".yml", NavWaypointCommands.exportName("town", graph));
        for (int length = 1; length <= 128; length++) {
            String region = "r".repeat(length);
            for (String suffix : List.of(stamp, graph)) {
                String name = NavWaypointCommands.exportName(region, suffix);
                assertTrue(StrictYaml.FILE_NAME.matcher(name).matches(), name);
                assertTrue(name.endsWith(suffix + ".yml"), name);
            }
        }
        // Two long regions that share their first letters still get different names.
        String a = NavWaypointCommands.exportName("district_" + "x".repeat(60) + "_north", graph);
        String b = NavWaypointCommands.exportName("district_" + "x".repeat(60) + "_south", graph);
        assertNotEquals(a, b);
        assertTrue(a.startsWith("district_"), a);
    }

    private static NavGraph line(int nodes, int extraEdges) {
        NavGraph.Builder builder = NavGraph.builder();
        for (int i = 1; i <= nodes; i++) builder.addNode(i, i, 64, 0, NavSurface.SIDEWALK);
        for (int i = 1; i < nodes; i++) builder.addBiEdge(i, i + 1, 10);
        for (int i = 0; i < extraEdges; i++) builder.addEdge(1, 2, 10);
        return builder.build();
    }

    @Test
    void publishRefusesWaypointAndFileGraphsBeyondTheRouteSearchCaps() {
        assertNull(NpcNavCommand.overRouteSearchCaps(line(1024, 0)));
        assertNotNull(NpcNavCommand.overRouteSearchCaps(line(1025, 0)));
        NavGraph edges = line(10, 8192 - 18);
        assertEquals(8192, edges.edgeCount());
        assertNull(NpcNavCommand.overRouteSearchCaps(edges));
        assertNotNull(NpcNavCommand.overRouteSearchCaps(line(10, 8192 - 17)));
    }

    static NpcNavCommand.Settings settings(Path reports) {
        return new NpcNavCommand.Settings(new StreetRules(12, 16, 0.4), null, 1_000_000_000L, 64, 1024, true, 1024, 256,
                64, 1, 4, 4, new NpcNavCommand.ReportSink(Logger.getLogger("NavWaypointCommandRulesTest"), reports), 1);
    }

    static NavWaypointCommands.Config config(Path importDir) {
        return new NavWaypointCommands.Config(WaypointSet.Limits.DEFAULT, 1024, "", false, importDir, 1 << 20);
    }

    /** The one report a refused build or import wrote, as text. */
    static String onlyReport(Path reports, String kind) throws Exception {
        try (Stream<Path> files = Files.list(reports)) {
            List<Path> written = files.toList();
            assertEquals(1, written.size(), written.toString());
            assertTrue(written.getFirst().getFileName().toString().startsWith(kind + "-town-"), written.toString());
            return Files.readString(written.getFirst());
        }
    }

    @Test
    void anImportRefusedBeforeTheWorldReadStillWritesItsReport(@TempDir Path importDir, @TempDir Path reports) throws Exception {
        Files.writeString(importDir.resolve("bad.yml"), "format: other/1\nnodes: []\n");
        Reply reply = NavWaypointCommands.importGraph(null, new UUID(0, 6612), "town", "bad.yml",
                new NavWaypointCommands.PaletteChoice("", null, ""), settings(reports), config(importDir), false);
        assertEquals(NamedTextColor.RED, reply.lines().getFirst().color());
        assertNull(reply.next(), "no world read is started");
        String json = onlyReport(reports, "import");
        assertTrue(json.contains("\"verdict\":\"REFUSED\""), json);
        assertTrue(json.contains("\"stage\":\"read\""), json);
        assertFalse(json.contains("other/1"), "the report never quotes the file: " + json);
    }

    @Test
    void theHelpNamesAsbuilt() throws Exception {
        try (InputStream bytes = NpcNavCommand.class.getResourceAsStream("NpcNavCommand.class")) {
            assertNotNull(bytes);
            String constants = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
            // The help line itself, not the publish usage line that already named it.
            assertTrue(constants.contains("validate <graphId> | publish <graphId> [legacy|asbuilt] | info <graphId> | list"));
        }
    }
}
