package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.NavScanLogic.Bounds;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavScanLogicTest {

    /** A synthetic world: air everywhere except recorded blocks; support is a walkable subset. */
    private static final class FakeWorld implements NavScanLogic.Terrain {
        private final Set<String> blocks = new HashSet<>();
        private final Set<String> support = new HashSet<>();

        FakeWorld support(int x, int y, int z) {
            String k = key(x, y, z);
            support.add(k);
            blocks.add(k);
            return this;
        }

        FakeWorld solid(int x, int y, int z) {
            blocks.add(key(x, y, z));
            return this;
        }

        @Override
        public boolean isSupport(int x, int y, int z) {
            return support.contains(key(x, y, z));
        }

        @Override
        public boolean isClear(int x, int y, int z) {
            return !blocks.contains(key(x, y, z));
        }

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }

    private static Bounds box(int minX, int maxX, int minZ, int maxZ) {
        return new Bounds(minX, 60, maxX, 70, minZ, maxZ);
    }

    private static long nodeAt(NavGraph g, int x, int y, int z) {
        for (NavGraph.Node n : g.nodes()) {
            if (n.x() == x && n.y() == y && n.z() == z) {
                return n.id();
            }
        }
        return -1L;
    }

    private static boolean linked(NavGraph g, long from, long to) {
        return g.neighbors(from).stream().anyMatch(e -> e.to() == to);
    }

    @Test
    void flatPlatformConnectsEveryNeighbour() {
        FakeWorld w = new FakeWorld();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                w.support(x, 64, z);
            }
        }
        NavGraph g = NavScanLogic.scan(box(0, 2, 0, 2), w, 2, 1);
        assertEquals(9, g.nodeCount());
        long center = nodeAt(g, 1, 65, 1);
        assertEquals(8, g.neighbors(center).size(), "center reaches 4 cardinal + 4 diagonal");
    }

    @Test
    void stepUpWithinLimitConnectsBeyondDoesNot() {
        FakeWorld ok = new FakeWorld().support(0, 64, 0).support(1, 65, 0); // step 1
        NavGraph gOk = NavScanLogic.scan(box(0, 1, 0, 0), ok, 2, 1);
        assertTrue(linked(gOk, nodeAt(gOk, 0, 65, 0), nodeAt(gOk, 1, 66, 0)));

        FakeWorld tooHigh = new FakeWorld().support(0, 64, 0).support(1, 66, 0); // step 2
        NavGraph gHigh = NavScanLogic.scan(box(0, 1, 0, 0), tooHigh, 2, 1);
        assertFalse(linked(gHigh, nodeAt(gHigh, 0, 65, 0), nodeAt(gHigh, 1, 67, 0)));
    }

    @Test
    void blockedHeadroomProducesNoNode() {
        FakeWorld w = new FakeWorld().support(1, 64, 1).solid(1, 66, 1); // only 1 clear block above
        NavGraph g = NavScanLogic.scan(box(1, 1, 1, 1), w, 2, 1);
        assertEquals(0, g.nodeCount());
    }

    @Test
    void diagonalRequiresBothOrthogonalNeighbours() {
        // L-shape: (0,0),(1,0),(1,1) exist; (0,1) is missing -> no diagonal (0,0)->(1,1).
        FakeWorld w = new FakeWorld().support(0, 64, 0).support(1, 64, 0).support(1, 64, 1);
        NavGraph g = NavScanLogic.scan(box(0, 1, 0, 1), w, 2, 1);
        assertFalse(linked(g, nodeAt(g, 0, 65, 0), nodeAt(g, 1, 65, 1)), "no corner-cut through the gap");
        assertTrue(linked(g, nodeAt(g, 0, 65, 0), nodeAt(g, 1, 65, 0)), "cardinal still links");
    }

    @Test
    void fullSquareAllowsTheDiagonal() {
        FakeWorld w = new FakeWorld()
                .support(0, 64, 0).support(1, 64, 0).support(0, 64, 1).support(1, 64, 1);
        NavGraph g = NavScanLogic.scan(box(0, 1, 0, 1), w, 2, 1);
        assertTrue(linked(g, nodeAt(g, 0, 65, 0), nodeAt(g, 1, 65, 1)), "both orthogonals present -> diagonal ok");
    }

    @Test
    void gapLeavesNodesUnlinked() {
        FakeWorld w = new FakeWorld().support(0, 64, 0).support(2, 64, 0); // (1,*,0) missing
        NavGraph g = NavScanLogic.scan(box(0, 2, 0, 0), w, 2, 1);
        assertEquals(2, g.nodeCount());
        assertEquals(0, g.edgeCount());
    }

    @Test
    void oversizeScanIsRejected() {
        FakeWorld w = new FakeWorld();
        Bounds huge = new Bounds(0, 60, 1_000, 70, 0, 1_000); // ~1M columns > MAX_COLUMNS
        assertThrows(IllegalArgumentException.class, () -> NavScanLogic.scan(huge, w, 2, 1));
    }

    @Test
    void dimensionsDoNotWrapBeforeTheLimitCheck() {
        assertEquals(4_294_967_295L, new Bounds(Integer.MIN_VALUE, 60, Integer.MAX_VALUE - 1, 70, 0, 0).columns());
        assertThrows(IllegalArgumentException.class, () -> new Bounds(Integer.MIN_VALUE, 60,
                Integer.MAX_VALUE, 70, Integer.MIN_VALUE, Integer.MAX_VALUE).columns());
    }

    @Test
    void invalidOrUnboundedWorkIsRejectedBeforeAnyTerrainRead() {
        NavScanLogic.Terrain noReads = new NavScanLogic.Terrain() {
            public boolean isSupport(int x, int y, int z) { throw new AssertionError("unexpected terrain access"); }
            public boolean isClear(int x, int y, int z) { throw new AssertionError("unexpected terrain access"); }
        };
        for (Bounds invalid : java.util.List.of(
                new Bounds(2, 60, 1, 70, 0, 0), new Bounds(0, 70, 0, 60, 0, 0),
                new Bounds(0, 60, 0, 70, 2, 1),
                new Bounds(Integer.MAX_VALUE, 60, Integer.MAX_VALUE, 70, 0, 0),
                new Bounds(0, Integer.MIN_VALUE, 0, 0, 0, 0),
                new Bounds(0, 0, 0, Integer.MAX_VALUE, 0, 0),
                new Bounds(0, -10000000, 0, 10000000, 0, 0))) {
            assertThrows(IllegalArgumentException.class, () -> NavScanLogic.scan(invalid, noReads, 2, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> NavScanLogic.scan(box(0, 0, 0, 0), noReads, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> NavScanLogic.scan(box(0, 0, 0, 0), noReads, 9, 1));
        assertThrows(IllegalArgumentException.class, () -> NavScanLogic.scan(box(0, 0, 0, 0), noReads, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> NavScanLogic.scan(box(0, 0, 0, 0), noReads, 2, 2));
    }

    // ---- golden legacy output: a scan with no classes must be exactly the pre-street scan ----

    private static FakeWorld mixedField() {
        FakeWorld w = new FakeWorld();
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                if ((x * 5 + z * 3) % 7 == 0) continue; // holes
                int y = 63 + ((x * 7 + z * 13) % 3 == 0 ? 1 : 0); // steps up and down
                w.support(x, y, z);
                if ((x + z) % 11 == 0) w.solid(x, y + 2, z); // roof: no headroom
            }
        }
        return w;
    }

    /** The scan as it was before streets, written out independently of NavScanLogic. */
    private static Set<String> referenceScan(NavScanLogic.Terrain t, Bounds b) {
        java.util.Map<String, Integer> standY = new java.util.HashMap<>();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.maxY(); y >= b.minY(); y--) {
                    if (t.isSupport(x, y, z) && t.isClear(x, y + 1, z) && t.isClear(x, y + 2, z)) {
                        standY.put(x + "," + z, y + 1);
                        break;
                    }
                }
            }
        }
        Set<String> out = new HashSet<>();
        for (var e : standY.entrySet()) {
            String[] xz = e.getKey().split(",");
            int x = Integer.parseInt(xz[0]);
            int z = Integer.parseInt(xz[1]);
            int y = e.getValue();
            out.add("node " + x + "," + y + "," + z);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Integer n = standY.get((x + d[0]) + "," + (z + d[1]));
                if (n != null && Math.abs(n - y) <= 1) out.add("edge " + x + "," + y + "," + z + " -> " + (x + d[0]) + "," + n + "," + (z + d[1]) + " 10");
            }
            for (int[] d : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                Integer n = standY.get((x + d[0]) + "," + (z + d[1]));
                Integer o1 = standY.get((x + d[0]) + "," + z);
                Integer o2 = standY.get(x + "," + (z + d[1]));
                if (n != null && Math.abs(n - y) <= 1 && o1 != null && o2 != null
                        && Math.abs(o1 - y) <= 1 && Math.abs(o2 - y) <= 1) {
                    out.add("edge " + x + "," + y + "," + z + " -> " + (x + d[0]) + "," + n + "," + (z + d[1]) + " 14");
                }
            }
        }
        return out;
    }

    private static Set<String> describe(NavGraph g) {
        Set<String> out = new HashSet<>();
        for (NavGraph.Node n : g.nodes()) out.add("node " + n.x() + "," + n.y() + "," + n.z());
        for (NavGraph.Edge e : g.edges()) {
            NavGraph.Node a = g.node(e.from());
            NavGraph.Node b = g.node(e.to());
            out.add("edge " + a.x() + "," + a.y() + "," + a.z() + " -> " + b.x() + "," + b.y() + "," + b.z() + " " + e.cost());
        }
        return out;
    }

    @Test
    void goldenLegacyScanIsExactlyTheOldNodesEdgesAndCosts() {
        FakeWorld w = mixedField();
        Bounds b = new Bounds(0, 60, 11, 70, 0, 11);
        NavGraph g = NavScanLogic.scan(b, w, 2, 1);
        Set<String> expected = referenceScan(w, b);
        assertEquals(expected, describe(g));
        assertTrue(expected.stream().anyMatch(l -> l.endsWith(" 14")) && expected.stream().anyMatch(l -> l.endsWith(" 10")));
        assertEquals(Set.of(10, 14), g.edges().stream().map(NavGraph.Edge::cost).collect(java.util.stream.Collectors.toSet()));
        assertTrue(g.nodes().stream().allMatch(n -> n.surface() == NavSurface.UNKNOWN));
        // The streets-aware entry point with legacy rules gives the same graph and no crossings.
        var result = NavScanLogic.scan(b, w, 2, 1, StreetRules.LEGACY);
        assertEquals(g.edges(), result.graph().edges());
        assertEquals(0, result.stats().streetNodes());
        assertEquals(0, result.stats().totalDropped());
    }

    @Test
    void goldenFieldIsNotTrivial() {
        // Control: the field really has holes, steps and roofs, so the golden comparison can fail.
        NavGraph g = NavScanLogic.scan(new Bounds(0, 60, 11, 70, 0, 11), mixedField(), 2, 1);
        assertTrue(g.nodeCount() > 90 && g.nodeCount() < 144, "nodes " + g.nodeCount());
        assertTrue(g.nodes().stream().map(NavGraph.Node::y).distinct().count() >= 2);
    }
}
