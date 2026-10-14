package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure node/edge derivation for a material scan (§10.1), with no world or
 * Bukkit coupling — terrain facts arrive through {@link Terrain}. A node is placed
 * at the standing position above the topmost walkable support in each column that
 * has enough headroom. Cardinal edges connect columns at most {@code maxStep}
 * apart in height; a diagonal edge is added only when both shared orthogonal
 * neighbours are also walkable within step (no corner-cutting through a wall).
 *
 * <p>Material scanning is "a seed, not unquestioned truth": stacked floors and
 * doors are not inferred (one surface per column), and semantic tags are added
 * later by an admin. {@link NavGraphValidator} checks the result before publish.
 */
public final class NavScanLogic {

    /** Terrain facts for the scan. Implemented over a real world by the scanner. */
    public interface Terrain {
        /** True if (x,y,z) is a walkable support block (its material is in the palette). */
        boolean isSupport(int x, int y, int z);

        /** True if (x,y,z) is clear (passable) — used for headroom above a support. */
        boolean isClear(int x, int y, int z);
    }

    /** Inclusive scan box. */
    public record Bounds(int minX, int minY, int maxX, int maxY, int minZ, int maxZ) {
        public long columns() {
            try {
                return Math.multiplyExact((long) maxX - minX + 1, (long) maxZ - minZ + 1);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("scan dimensions overflow", overflow);
            }
        }
    }

    /** Safety bound — districts are small; refuse world-wide scans (§ non-goals). */
    public static final long MAX_COLUMNS = 200_000L;
    public static final long MAX_TERRAIN_READS = 4_000_000L;
    public static final int CARDINAL_COST = 10;
    public static final int DIAGONAL_COST = 14;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private NavScanLogic() {
    }

    public static NavGraph scan(Bounds b, Terrain terrain, int headroom, int maxStep) {
        validate(b, headroom, maxStep);
        NavGraph.Builder builder = NavGraph.builder();
        // packed (x,z) -> {standY, nodeId}; plus a stable node list for the edge pass.
        Map<Long, long[]> column = new HashMap<>();
        List<long[]> nodes = new ArrayList<>(); // {id, x, standY, z}
        long nextId = 0;

        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                int standY = topWalkable(b, terrain, x, z, headroom);
                if (standY == Integer.MIN_VALUE) {
                    continue;
                }
                long id = nextId++;
                builder.addNode(id, x, standY, z);
                column.put(pack(x, z), new long[]{standY, id});
                nodes.add(new long[]{id, x, standY, z});
            }
        }

        return connect(builder, column, nodes, maxStep);
    }

    /** Reject before touching Terrain, including integer-loop wrap and unbounded vertical work. */
    public static void validate(Bounds b, int headroom, int maxStep) {
        java.util.Objects.requireNonNull(b);
        if (headroom < 1 || headroom > 8 || maxStep < 0 || maxStep > 1) {
            throw new IllegalArgumentException("headroom must be 1..8 and step 0..1");
        }
        if (b.minX() > b.maxX() || b.minY() > b.maxY() || b.minZ() > b.maxZ()
                || b.minX() == Integer.MIN_VALUE || b.minZ() == Integer.MIN_VALUE
                || b.minY() == Integer.MIN_VALUE || b.maxX() == Integer.MAX_VALUE
                || b.maxZ() == Integer.MAX_VALUE || b.maxY() > Integer.MAX_VALUE - headroom) {
            throw new IllegalArgumentException("invalid or overflowing scan bounds");
        }
        if (b.columns() > MAX_COLUMNS) {
            throw new IllegalArgumentException("scan area too large: " + b.columns()
                    + " columns (max " + MAX_COLUMNS + ")");
        }
        long height = (long) b.maxY() - b.minY() + 1;
        // Divide before multiplying so the rejection itself cannot overflow.
        if (height > MAX_TERRAIN_READS / b.columns() / (headroom + 1L)) {
            throw new IllegalArgumentException("scan exceeds terrain-read budget; use a smaller box");
        }
    }

    private static NavGraph connect(NavGraph.Builder builder, Map<Long, long[]> column,
                                    List<long[]> nodes, int maxStep) {
        for (long[] n : nodes) {
            long id = n[0];
            int x = (int) n[1];
            int standY = (int) n[2];
            int z = (int) n[3];
            for (int[] d : CARDINALS) {
                long[] nb = column.get(pack(x + d[0], z + d[1]));
                if (nb != null && Math.abs((int) nb[0] - standY) <= maxStep) {
                    builder.addEdge(id, nb[1], CARDINAL_COST);
                }
            }
            for (int[] d : DIAGONALS) {
                long[] nb = column.get(pack(x + d[0], z + d[1]));
                if (nb == null || Math.abs((int) nb[0] - standY) > maxStep) {
                    continue;
                }
                long[] o1 = column.get(pack(x + d[0], z));
                long[] o2 = column.get(pack(x, z + d[1]));
                if (o1 != null && o2 != null
                        && Math.abs((int) o1[0] - standY) <= maxStep
                        && Math.abs((int) o2[0] - standY) <= maxStep) {
                    builder.addEdge(id, nb[1], DIAGONAL_COST);
                }
            }
        }
        return builder.build();
    }

    /** Standing Y (support Y + 1) of the topmost support with {@code headroom} clear above, or MIN_VALUE. */
    private static int topWalkable(Bounds b, Terrain terrain, int x, int z, int headroom) {
        for (int y = b.maxY(); y >= b.minY(); y--) {
            if (!terrain.isSupport(x, y, z)) {
                continue;
            }
            boolean clear = true;
            for (int h = 1; h <= headroom; h++) {
                if (!terrain.isClear(x, y + h, z)) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return y + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
