package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.waypoint.WaypointDensifier;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileGraphCodecTest {

    private static final StreetRules RULES = StreetWorld.RULES;
    private static final FileGraphCodec.Limits CAPS = FileGraphCodec.Limits.DEFAULT;

    private static NavGraph read(String text) {
        return FileGraphCodec.read(text, RULES, 1, CAPS);
    }

    private static String refusal(String text) {
        return assertThrows(FileGraphCodec.Refused.class, () -> read(text)).getMessage();
    }

    /** A graph described by coordinates only, so two graphs with different ids can be compared. */
    static Map<String, String> shape(NavGraph graph) {
        Map<String, String> out = new TreeMap<>();
        for (NavGraph.Node n : graph.nodes()) {
            out.put("node " + n.x() + "," + n.y() + "," + n.z(), n.surface() + " " + new TreeSet<>(n.tags()) + " w" + n.roadWidth());
        }
        for (NavGraph.Edge e : graph.edges()) {
            NavGraph.Node a = graph.node(e.from());
            NavGraph.Node b = graph.node(e.to());
            out.put("edge " + a.x() + "," + a.y() + "," + a.z() + ">" + b.x() + "," + b.y() + "," + b.z(), String.valueOf(e.cost()));
        }
        return out;
    }

    private static final String SAMPLE = """
            format: prosperity-nav-graph/1
            default-surface: sidewalk
            nodes:
              - {id: 1, at: [416, 72, -128], surface: sidewalk, tags: [spawn]}
              - {id: 2, at: [417, 72, -128]}
              - {id: 3, at: [418, 72, -127]}
              - {id: 4, at: [418, 72, -128], surface: road}
              - {id: 5, at: [418, 72, -129], surface: sidewalk}
            edges:
              - [1, 2]
              - {from: 2, to: 3, cost: 28}
              - {from: 3, to: 2}
              - [3, 4]
              - [4, 5]
            """;

    @Test
    void aValidFileParsesWithComputedStreetCosts() {
        NavGraph graph = read(SAMPLE);
        assertEquals(5, graph.nodeCount());
        assertEquals(Set.of("spawn"), graph.node(1).tags());
        assertEquals(NavSurface.SIDEWALK, graph.node(2).surface());
        assertEquals(NavSurface.ROAD, graph.node(4).surface());
        assertEquals(1, graph.node(4).roadWidth(), "a road node without a width records its crossing's length");
        Map<String, Integer> costs = new TreeMap<>();
        graph.edges().forEach(e -> costs.put(e.from() + ">" + e.to(), e.cost()));
        assertEquals(10, costs.get("1>2"));
        assertEquals(28, costs.get("2>3"), "an explicit cost may make an ordinary step slower");
        assertEquals(14, costs.get("3>2"), "a diagonal defaults to 14");
        assertEquals(10 + 20 * 12, costs.get("3>4"), "curb onto a plain crossing pays the entry");
        assertEquals(10, costs.get("4>3"));
        assertEquals(10 + 20 * 12, costs.get("5>4"));
        assertTrue(NavGraphValidator.validate(graph).valid());
    }

    @Test
    void jsonIsAccepted() {
        NavGraph graph = read("""
                {"format": "prosperity-nav-graph/1",
                 "nodes": [{"id": 1, "at": [0, 64, 0], "surface": "sidewalk", "tags": ["home"]},
                           {"id": 2, "at": [1, 64, 0], "surface": "access"}],
                 "edges": [[1, 2]]}
                """);
        assertEquals(2, graph.nodeCount());
        assertEquals(2, graph.edgeCount());
        assertEquals(NavSurface.ACCESS, graph.node(2).surface());
    }

    @Test
    void everyErrorNamesItsNodeOrEdge() {
        String head = "format: prosperity-nav-graph/1\nnodes:\n  - {id: 1, at: [0, 64, 0], surface: sidewalk}\n";
        assertTrue(refusal(head + "  - {id: 7, at: [1, 64, 0]}\n").contains("node 7"), "a missing surface is refused, never UNKNOWN");
        assertTrue(refusal(head + "  - {id: 7, at: [1, 64, 0], surface: unknown}\n").contains("node 7"));
        assertTrue(refusal(head + "  - {id: 1, at: [1, 64, 0], surface: sidewalk}\n").contains("node 1"));
        assertTrue(refusal(head + "  - {id: 2, at: [0, 64, 0], surface: sidewalk}\n").contains("node 2"));
        assertTrue(refusal(head + "  - {id: 0, at: [1, 64, 0], surface: sidewalk}\n").contains("node 1"));
        assertTrue(refusal(head + "  - {id: 2, at: [1, 64, 0], surface: road, tags: [home]}\n").contains("node 2"));
        assertTrue(refusal(head + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, width: 3}\n").contains("node 2"));
        assertTrue(refusal(head + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, tags: [bank]}\n").contains("node 2"));
        assertTrue(refusal(head + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, colour: red}\n").contains("unknown key"));
        String two = head + "  - {id: 2, at: [3, 64, 0], surface: sidewalk}\n  - {id: 3, at: [1, 64, 0], surface: road}\n"
                + "  - {id: 4, at: [1, 64, 1], surface: sidewalk}\nedges:\n  - [1, 3]\n";
        assertTrue(refusal(two + "  - [1, 2]\n").contains("edge 1"), "not next to each other");
        assertTrue(refusal(two + "  - [1, 9]\n").contains("edge 1"));
        assertTrue(refusal(two + "  - [1, 3]\n").contains("edge 1"), "a repeated step");
        String diagonalOntoRoad = head + "  - {id: 3, at: [1, 64, 1], surface: road}\nedges:\n  - [1, 3]\n";
        assertTrue(refusal(diagonalOntoRoad).contains("edge 0"));
        assertTrue(refusal("format: prosperity-nav-graph/2\nnodes: []\n").contains("format"));
        assertTrue(refusal("format: prosperity-nav-graph/1\ndefault-surface: road\nnodes: []\n").contains("default-surface"));
    }

    @Test
    void costsMayOnlyMakeAnOrdinaryStepSlower() {
        String head = "format: prosperity-nav-graph/1\ndefault-surface: sidewalk\nnodes:\n"
                + "  - {id: 1, at: [0, 64, 0]}\n  - {id: 2, at: [1, 64, 0]}\n  - {id: 3, at: [2, 64, 0], surface: road}\n"
                + "  - {id: 4, at: [3, 64, 0]}\nedges:\n";
        assertTrue(refusal(head + "  - {from: 1, to: 2, cost: 9}\n").contains("edge 0"));
        assertTrue(refusal(head + "  - {from: 1, to: 2, cost: 101}\n").contains("edge 0"));
        assertDoesNotThrow(() -> read(head + "  - {from: 1, to: 2, cost: 100}\n"));
        assertTrue(refusal(head + "  - {from: 2, to: 3, cost: 10}\n").contains("road"), "never a cost on a road step");
    }

    @Test
    void oversizedCostsCannotWrapIntoAnAcceptedInteger() {
        String head = "format: prosperity-nav-graph/1\ndefault-surface: sidewalk\nnodes:\n"
                + "  - {id: 1, at: [0, 64, 0]}\n  - {id: 2, at: [1, 64, 0]}\nedges:\n";
        for (long cost : new long[]{4_294_967_306L, -4_294_967_286L}) {
            assertTrue(refusal(head + "  - {from: 1, to: 2, cost: " + cost + "}\n").contains("edge 0"));
        }
    }

    @Test
    void distantCoordinatesCannotWrapIntoAnAdjacentStep() {
        for (int axis = 0; axis < 3; axis++) {
            int[] a = {0, 64, 0}, b = {1, 64, 0};
            a[axis] = Integer.MIN_VALUE;
            b[axis] = Integer.MAX_VALUE;
            String text = "format: prosperity-nav-graph/1\ndefault-surface: sidewalk\nnodes:\n"
                    + "  - {id: 1, at: " + java.util.Arrays.toString(a) + "}\n"
                    + "  - {id: 2, at: " + java.util.Arrays.toString(b) + "}\nedges:\n  - [1, 2]\n";
            assertTrue(refusal(text).contains("edge 0"));
        }
    }

    @Test
    void capsAreEnforced() {
        StringBuilder text = new StringBuilder("format: prosperity-nav-graph/1\ndefault-surface: sidewalk\nnodes:\n");
        for (int i = 1; i <= 6; i++) text.append("  - {id: ").append(i).append(", at: [").append(i).append(", 64, 0]}\n");
        assertThrows(FileGraphCodec.Refused.class, () -> FileGraphCodec.read(text.toString(), RULES, 1, new FileGraphCodec.Limits(5, 100)));
        assertDoesNotThrow(() -> FileGraphCodec.read(text.toString(), RULES, 1, new FileGraphCodec.Limits(6, 100)));
        assertThrows(IllegalArgumentException.class, () -> new FileGraphCodec.Limits(Dijkstra.MAX_GRAPH_NODES + 1, 10));
    }

    @Test
    void aliasesBombsAndDuplicateKeysAreRefusedByTheStrictLoader() {
        String bomb = """
                format: prosperity-nav-graph/1
                a: &a [x, x, x, x, x, x, x, x, x]
                b: &b [*a, *a, *a, *a, *a, *a, *a, *a, *a]
                c: &c [*b, *b, *b, *b, *b, *b, *b, *b, *b]
                d: &d [*c, *c, *c, *c, *c, *c, *c, *c, *c]
                nodes: [*d, *d, *d, *d, *d, *d, *d, *d, *d]
                """;
        assertTrue(refusal(bomb).contains("alias") || refusal(bomb).contains("anchor"));
        assertTrue(refusal("format: prosperity-nav-graph/1\nnodes: []\nnodes: []\n").contains("repeats a key"));
        assertTrue(refusal("format: prosperity-nav-graph/1\nnodes: !!set {a, b}\n").contains("tag"));
        assertTrue(refusal("format: prosperity-nav-graph/1\n<<: {nodes: []}\n").contains("merge"));
    }

    /** Mutant guard: Bukkit's loader takes aliases without a word, so a file parsed through it would pass. */
    @Test
    void bukkitsLoaderWouldHaveAcceptedWhatTheStrictOneRefuses() {
        String aliased = "format: prosperity-nav-graph/1\nnodes: &n []\nedges: *n\n";
        YamlConfiguration bukkit = new YamlConfiguration();
        assertDoesNotThrow(() -> bukkit.loadFromString(aliased));
        assertThrows(StrictYaml.Refused.class, () -> StrictYaml.load(aliased));
    }

    @Test
    void importFilesAreBareNamesReadAsUtf8WithinTheLimit(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("district.yml"), SAMPLE, StandardCharsets.UTF_8);
        assertEquals(SAMPLE, StrictYaml.readImportFile(dir, "district.yml", 1 << 20));
        for (String bad : List.of("../district.yml", "sub/district.yml", "district.txt", ".hidden.yml", "a b.yml", "",
                "x".repeat(70) + ".yml")) {
            assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(dir, bad, 1 << 20), bad);
        }
        assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(dir, "district.yml", 10), "over the byte limit");
        assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(dir, "missing.yml", 1 << 20));
        Files.write(dir.resolve("latin.yml"), new byte[]{'a', ':', ' ', (byte) 0xE9, '\n'});
        assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(dir, "latin.yml", 1 << 20), "not UTF-8");
        Files.createDirectory(dir.resolve("folder.yml"));
        assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(dir, "folder.yml", 1 << 20));
    }

    // 7.4: import(export(graph)) equals the graph for every fixture, including a dev.62 scan graph.
    @Test
    void exportThenImportGivesTheSameGraph() {
        NavGraph scanned = StreetWorld.strip(5, 12).build().graph();
        NavGraph tagged = StreetWorld.tag(scanned, Map.of(StreetWorld.node(scanned, 2, 1).id(), "home",
                StreetWorld.node(scanned, 9, 9).id(), "work:cook"));
        NavGraph painted = StreetWorld.strip(4, 8).fill(0, 3, 7, 6, 'W').build().graph();
        WaypointSet set = WaypointSet.EMPTY
                .addPoint(new Point("a", 0, 64, 0, PointKind.SIDEWALK, new TreeSet<>(Set.of("spawn"))), Limits.DEFAULT)
                .addPoint(new Point("b", 0, 64, 6, PointKind.SIDEWALK, null), Limits.DEFAULT)
                .addPoint(new Point("c", 6, 64, 6, PointKind.ACCESS, null), Limits.DEFAULT)
                .addPoint(new Point("d", 6, 64, 0, PointKind.SIDEWALK, null), Limits.DEFAULT)
                .link("a", "b", LinkKind.CROSS, Limits.DEFAULT).link("b", "c", LinkKind.WALK, Limits.DEFAULT)
                .link("a", "d", LinkKind.WALK, Limits.DEFAULT);
        NavGraph built = WaypointDensifier.densify(set, RULES, 1, WaypointDensifier.Limits.DEFAULT).graph();
        NavGraph slow = read(SAMPLE);
        for (NavGraph graph : List.of(tagged, painted, built, slow)) {
            String text = FileGraphCodec.write(graph, RULES);
            NavGraph back = FileGraphCodec.read(text, RULES, 1, new FileGraphCodec.Limits(Dijkstra.MAX_GRAPH_NODES, Dijkstra.MAX_GRAPH_EDGES));
            assertEquals(shape(graph), shape(back));
            assertEquals(text, FileGraphCodec.write(back, RULES), "a second round trip writes the same bytes");
        }
        assertThrows(FileGraphCodec.Refused.class, () -> FileGraphCodec.write(StreetWorld.strip(3, 6).build(false).graph(), RULES),
                "an unclassified graph has no surfaces to write");
    }
}
