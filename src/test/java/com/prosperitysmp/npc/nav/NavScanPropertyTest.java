package com.prosperitysmp.npc.nav;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scans of random drawn streets: whatever the ground looks like, the rules about roads must hold in the graph that comes out. */
class NavScanPropertyTest {
    private static final int SIZE = 16;
    private static final String PAINT = "SSSSSSRRRRWWAU..";

    private static StreetWorld random(long seed, boolean classified) {
        return random(seed, classified, 6);
    }

    private static StreetWorld random(long seed, boolean classified, int raised) {
        Random random = new Random(seed);
        StreetWorld.Builder builder = StreetWorld.grid(0, 0, SIZE, SIZE);
        // A few straight bands of road and sidewalk first, so crossings exist, then noise on top.
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                builder.set(x, z, z % 8 < 3 ? 'S' : 'R');
            }
        }
        for (int i = 0; i < 40; i++) {
            builder.set(random.nextInt(SIZE), random.nextInt(SIZE), PAINT.charAt(random.nextInt(PAINT.length())));
        }
        for (int i = 0; i < raised; i++) {
            builder.raise(random.nextInt(SIZE), random.nextInt(SIZE), 1);
        }
        return builder.build(classified);
    }

    private static Set<String> edgeKeys(NavGraph graph) {
        Set<String> out = new HashSet<>();
        for (NavGraph.Edge e : graph.edges()) {
            NavGraph.Node a = graph.node(e.from());
            NavGraph.Node b = graph.node(e.to());
            out.add(a.x() + "," + a.y() + "," + a.z() + ">" + b.x() + "," + b.y() + "," + b.z() + ":" + e.cost());
        }
        return out;
    }

    private static Set<String> nodeKeys(NavGraph graph) {
        Set<String> out = new HashSet<>();
        for (NavGraph.Node n : graph.nodes()) {
            out.add(n.x() + "," + n.y() + "," + n.z() + " " + n.surface() + " w" + n.roadWidth());
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30})
    void aScanOfRandomStreetsHasNoStructuralErrorAndNoRoadTurnsOrDiagonals(long seed) {
        NavGraph graph = random(seed, true).graph();
        NavGraphValidator.Report report = NavGraphValidator.validate(graph);
        long errors = report.issues().stream().filter(i -> i.severity() == NavGraphValidator.Severity.ERROR)
                .filter(i -> !i.code().equals("EMPTY")).count();
        assertEquals(0, errors, report.issues().toString());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30})
    void everyStepOfARandomFlatScanCanBeWalkedBackAtAPositiveCost(long seed) {
        NavGraph graph = random(seed, true, 0).graph();
        for (NavGraph.Edge e : graph.edges()) {
            assertTrue(e.cost() > 0, e.toString());
            assertTrue(graph.hasPositiveEdge(e.to(), e.from()), "no way back for " + e);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30})
    void scanningTheSameGroundTwiceGivesTheSameGraph(long seed) {
        NavGraph one = random(seed, true).graph();
        NavGraph two = random(seed, true).graph();
        assertEquals(nodeKeys(one), nodeKeys(two));
        assertEquals(edgeKeys(one), edgeKeys(two));
        assertEquals(one.edges(), two.edges());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void aCapturedSnapshotOfRandomGroundScansLikeTheGroundItself(long seed) {
        StreetWorld world = random(seed, true);
        NavScanLogic.Bounds bounds = world.bounds();
        NavTerrainCapture capture = new NavTerrainCapture(bounds, 2, 1);
        NavTerrainCapture.Reader reader = (x, y, z) -> {
            int value = 0;
            if (world.isSupport(x, y, z)) {
                value |= NavTerrainCapture.SUPPORT | world.surface(x, y, z).code() << NavTerrainCapture.SURFACE_SHIFT;
            }
            if (world.isClear(x, y, z)) {
                value |= NavTerrainCapture.CLEAR;
            }
            return (byte) value;
        };
        while (!capture.advance(reader, 4096, Long.MAX_VALUE, () -> 0L)) {
            // keep slicing until every cell has been read
        }
        NavGraph captured = NavScanLogic.scan(bounds, capture, 2, 1, StreetWorld.RULES).graph();
        NavGraph direct = world.graph();
        assertEquals(nodeKeys(direct), nodeKeys(captured));
        assertEquals(edgeKeys(direct), edgeKeys(captured));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void withoutClassesNoRoadOrCrossingNodeIsEverBuilt(long seed) {
        NavScanLogic.ScanResult scan = random(seed, false).scan(StreetWorld.RULES);
        assertEquals(0, scan.stats().streetNodes());
        assertEquals(0, scan.stats().totalDropped());
        assertTrue(scan.graph().nodes().stream().allMatch(n -> n.surface() == NavSurface.UNKNOWN));
    }
}
