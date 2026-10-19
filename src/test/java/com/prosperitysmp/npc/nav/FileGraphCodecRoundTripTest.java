package com.prosperitysmp.npc.nav;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Random classified graphs written as a route file and read back: every node, tag and step cost must survive. */
class FileGraphCodecRoundTripTest {
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    private static final int SIZE = 6;
    private static final List<String> TAGS = List.of("spawn", "home", "work", "market", "leisure", "work:cook", "work:courier");

    /** Nodes on a small grid at heights 64 or 65, steps between neighbours (diagonals too) with costs from the default up to ten times it. */
    private static NavGraph random(long seed) {
        Random random = new Random(seed);
        NavGraph.Builder builder = NavGraph.builder();
        long[][] ids = new long[SIZE][SIZE];
        int[][] heights = new int[SIZE][SIZE];
        long next = 1;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if ((x != 0 || z != 0) && random.nextInt(10) < 3) {
                    continue;
                }
                ids[x][z] = next++;
                heights[x][z] = 64 + random.nextInt(2);
                Set<String> tags = new TreeSet<>();
                if (random.nextInt(10) < 3) {
                    tags.add(TAGS.get(random.nextInt(TAGS.size())));
                    if (random.nextBoolean()) tags.add(TAGS.get(random.nextInt(TAGS.size())));
                }
                NavSurface surface = random.nextInt(4) == 0 ? NavSurface.ACCESS : NavSurface.SIDEWALK;
                builder.addNode(new NavGraph.Node(ids[x][z], x, heights[x][z], z, tags, surface, 0));
            }
        }
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (ids[x][z] == 0) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if ((dx == 0 && dz == 0) || nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE || ids[nx][nz] == 0) continue;
                        if (random.nextInt(10) >= 5) continue;
                        int base = dx != 0 && dz != 0 ? NavScanLogic.DIAGONAL_COST : NavScanLogic.CARDINAL_COST;
                        int cost = random.nextInt(4) == 0 ? base * (1 + random.nextInt(10)) : base;
                        builder.addEdge(ids[x][z], ids[nx][nz], cost);
                    }
                }
            }
        }
        return builder.build();
    }

    private static Map<String, String> shape(NavGraph graph) {
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

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30})
    void aRandomGraphSurvivesBeingWrittenAndReadBack(long seed) {
        NavGraph original = random(seed);
        String text = FileGraphCodec.write(original, RULES);
        NavGraph back = FileGraphCodec.read(text, RULES, 1, FileGraphCodec.Limits.DEFAULT);
        assertEquals(shape(original), shape(back));
        assertEquals(original.nodeCount(), back.nodeCount());
        assertEquals(original.edgeCount(), back.edgeCount());
    }

    @ParameterizedTest
    @ValueSource(longs = {101, 102, 103, 104, 105, 106, 107, 108, 109, 110})
    void writingTheReadBackGraphGivesTheSameText(long seed) {
        String first = FileGraphCodec.write(random(seed), RULES);
        NavGraph back = FileGraphCodec.read(first, RULES, 1, FileGraphCodec.Limits.DEFAULT);
        assertEquals(first, FileGraphCodec.write(back, RULES));
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 202, 203, 204, 205})
    void theExportedTextIsPlainLfTextWithNoSurprisesInIt(long seed) {
        String text = FileGraphCodec.write(random(seed), RULES);
        assertTrue(text.startsWith("format: prosperity-nav-graph/1\nnodes:\n"));
        assertTrue(text.endsWith("\n"));
        assertEquals(-1, text.indexOf('\r'));
        assertEquals(-1, text.indexOf('\t'));
    }
}
