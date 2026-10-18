package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives nodes and edges for a material scan. Pure: no world or Bukkit access,
 * terrain facts arrive through {@link Terrain}. A node sits at the standing
 * position above the topmost walkable support in each column that has enough
 * headroom. Cardinal edges join columns at most {@code maxStep} apart in height.
 * A diagonal edge is added only when both shared orthogonal neighbours are also
 * walkable within step, so a path never cuts a corner through a wall.
 *
 * <p>With a classified palette, road and crossing columns are not ordinary ground: they become
 * nodes only on a straight "crossing ray" from one sidewalk to the sidewalk directly opposite.
 * No ordinary edge ever touches a road cell, so a path cannot cut a corner across a road, turn on
 * one or walk along one. A palette with no classes (every surface UNKNOWN) scans exactly as before.
 *
 * <p>A scan is only a starting point: stacked floors and doors are not inferred
 * (one surface per column), and an admin adds semantic tags later.
 * {@link NavGraphValidator} checks the result before publish.
 */
public final class NavScanLogic {

    /** Terrain facts for the scan. Implemented over a real world by the scanner. */
    public interface Terrain {
        /** True if (x,y,z) is a walkable support block (its material is in the palette). */
        boolean isSupport(int x, int y, int z);

        /** True if (x,y,z) is clear (passable): used for headroom above a support. */
        boolean isClear(int x, int y, int z);

        /** The class of the support block at (x,y,z); UNKNOWN when the palette has no classes. */
        default NavSurface surface(int x, int y, int z) {
            return NavSurface.UNKNOWN;
        }
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

    /** Why a candidate crossing was not kept. */
    public enum Drop { BOX_EDGE, TOO_LONG, BLOCKED, CURB, STEP, NOT_SIDEWALK, ALONG_ROAD, ISLAND }

    /** What a scan built and what it threw away, with the first few coordinates of each drop reason. */
    public record Stats(Map<NavSurface, Integer> nodes, int crosswalkRays, int plainRays,
                        Map<Drop, Integer> dropped, Map<Drop, List<String>> firstDropped) {
        public int streetNodes() {
            return nodes.getOrDefault(NavSurface.ROAD, 0) + nodes.getOrDefault(NavSurface.CROSSING, 0);
        }

        public int totalDropped() {
            return dropped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public record ScanResult(NavGraph graph, Stats stats) { }

    /** Districts are small, so world-wide scans are refused. */
    public static final long MAX_COLUMNS = 200_000L;
    public static final long MAX_TERRAIN_READS = 4_000_000L;
    public static final int CARDINAL_COST = 10;
    public static final int DIAGONAL_COST = 14;
    /** A crossing never starts or ends on a patch of ground smaller than this: a lone block in a road is no place to stand. */
    static final int MIN_PLATFORM = 4;
    /** How many coordinates are kept per drop reason for the scan report. */
    private static final int DROP_SAMPLES = 5;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private NavScanLogic() {
    }

    /** The classic scan: every palette block is ordinary ground. */
    public static NavGraph scan(Bounds b, Terrain terrain, int headroom, int maxStep) {
        return scan(b, terrain, headroom, maxStep, StreetRules.LEGACY).graph();
    }

    public static ScanResult scan(Bounds b, Terrain terrain, int headroom, int maxStep, StreetRules rules) {
        validate(b, headroom, maxStep);
        Objects.requireNonNull(rules);
        NavGraph.Builder builder = NavGraph.builder();
        // packed (x,z) -> {standY, nodeId, surface code}; plus a stable node list for the edge pass.
        Map<Long, long[]> column = new HashMap<>();
        List<long[]> nodes = new ArrayList<>(); // {id, x, standY, z, surface code}
        // Road and crossing columns are not nodes: they only matter to the crossing rays below.
        Map<Long, int[]> streetCols = new HashMap<>(); // packed (x,z) -> {standY, surface code}
        StatsBuilder stats = new StatsBuilder();
        long nextId = 0;

        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                int standY = topWalkable(b, terrain, x, z, headroom);
                if (standY == Integer.MIN_VALUE) {
                    continue;
                }
                NavSurface surface = terrain.surface(x, standY - 1, z);
                if (surface.isStreet()) {
                    streetCols.put(pack(x, z), new int[]{standY, surface.code()});
                    continue;
                }
                long id = nextId++;
                builder.addNode(id, x, standY, z, surface);
                column.put(pack(x, z), new long[]{standY, id, surface.code()});
                nodes.add(new long[]{id, x, standY, z, surface.code()});
                stats.count(surface);
            }
        }

        connect(builder, column, nodes, maxStep);
        if (!streetCols.isEmpty()) {
            addCrossings(builder, b, column, nodes, streetCols, maxStep, rules, nextId, stats,
                    platformSizes(column, nodes, maxStep));
        }
        return new ScanResult(builder.build(), stats.build());
    }

    /** Reject before touching Terrain, including integer-loop wrap and unbounded vertical work. */
    public static void validate(Bounds b, int headroom, int maxStep) {
        Objects.requireNonNull(b);
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

    private static void connect(NavGraph.Builder builder, Map<Long, long[]> column,
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
                // Only base columns are looked up, so a diagonal never passes beside a road cell.
                long[] o1 = column.get(pack(x + d[0], z));
                long[] o2 = column.get(pack(x, z + d[1]));
                if (o1 != null && o2 != null
                        && Math.abs((int) o1[0] - standY) <= maxStep
                        && Math.abs((int) o2[0] - standY) <= maxStep) {
                    builder.addEdge(id, nb[1], DIAGONAL_COST);
                }
            }
        }
    }

    /** Size of the patch of ordinary ground (any class) each node belongs to, keyed by node id. */
    private static Map<Long, Integer> platformSizes(Map<Long, long[]> column, List<long[]> nodes, int maxStep) {
        Map<Long, Integer> sizes = new HashMap<>();
        Map<Long, Boolean> seen = new HashMap<>();
        for (long[] start : nodes) {
            if (seen.putIfAbsent(start[0], Boolean.TRUE) != null) {
                continue;
            }
            List<long[]> patch = new ArrayList<>();
            List<long[]> queue = new ArrayList<>();
            queue.add(start);
            for (int head = 0; head < queue.size(); head++) {
                long[] n = queue.get(head);
                patch.add(n);
                for (int[] d : CARDINALS) {
                    int nx = (int) n[1] + d[0];
                    int nz = (int) n[3] + d[1];
                    long[] nb = column.get(pack(nx, nz));
                    if (nb != null && Math.abs((int) nb[0] - (int) n[2]) <= maxStep
                            && seen.putIfAbsent(nb[1], Boolean.TRUE) == null) {
                        queue.add(new long[]{nb[1], nx, nb[0], nz, nb[2]});
                    }
                }
            }
            for (long[] n : patch) {
                sizes.put(n[0], patch.size());
            }
        }
        return sizes;
    }

    /**
     * A kept crossing: street cells {x, z, standY, surface code} from the origin curb to the far curb, and
     * the narrowest road width the scan measured beside them.
     */
    private record Ray(int axis, int fixed, int low, long near, long far, List<int[]> cells, int width) { }

    private record RayKey(int axis, int fixed, int low) { }

    /**
     * Crossing rays. From every sidewalk node, in each cardinal direction whose neighbour is a street
     * cell, walk straight over street cells. The ray is kept only when it ends on another sidewalk
     * node within the length limit and is not running lengthwise along a road. Each kept cell becomes
     * a node of its own lane, so two crossings that share a cell can never be joined into a turn.
     */
    private static void addCrossings(NavGraph.Builder builder, Bounds b, Map<Long, long[]> column,
                                     List<long[]> nodes, Map<Long, int[]> streetCols, int maxStep,
                                     StreetRules rules, long firstId, StatsBuilder stats,
                                     Map<Long, Integer> platforms) {
        // The same crossing is found from both curbs; it is built once.
        Map<RayKey, Ray> kept = new HashMap<>();
        for (long[] n : nodes) {
            if (n[4] != NavSurface.SIDEWALK.code()) {
                continue;
            }
            for (int[] d : CARDINALS) {
                Ray ray = cast(b, column, streetCols, n, d, maxStep, rules, stats, platforms);
                if (ray != null) {
                    kept.putIfAbsent(new RayKey(ray.axis(), ray.fixed(), ray.low()), ray);
                }
            }
        }
        List<Ray> ordered = new ArrayList<>(kept.values());
        ordered.sort(Comparator.comparingInt(Ray::axis).thenComparingInt(Ray::fixed).thenComparingInt(Ray::low));
        long nextId = firstId;
        for (Ray ray : ordered) {
            List<NavSurface> surfaces = new ArrayList<>();
            for (int[] cell : ray.cells()) {
                surfaces.add(NavSurface.fromCode(cell[3]));
            }
            boolean crosswalk = rules.isCrosswalk(surfaces);
            if (crosswalk) {
                stats.crosswalkRays++;
            } else {
                stats.plainRays++;
            }
            // Only stepping onto a plain crossing costs extra; walking over it and leaving it do not.
            int entry = StreetCosts.entry(rules, surfaces);
            long[] ids = new long[ray.cells().size()];
            for (int i = 0; i < ids.length; i++) {
                int[] cell = ray.cells().get(i);
                ids[i] = nextId++;
                NavSurface surface = NavSurface.fromCode(cell[3]);
                builder.addNode(new NavGraph.Node(ids[i], cell[0], cell[2], cell[1], Set.of(), surface, ray.width()));
                stats.count(surface);
            }
            builder.addEdge(ray.near(), ids[0], entry);
            builder.addEdge(ids[0], ray.near(), CARDINAL_COST);
            for (int i = 0; i + 1 < ids.length; i++) {
                builder.addEdge(ids[i], ids[i + 1], CARDINAL_COST);
                builder.addEdge(ids[i + 1], ids[i], CARDINAL_COST);
            }
            builder.addEdge(ray.far(), ids[ids.length - 1], entry);
            builder.addEdge(ids[ids.length - 1], ray.far(), CARDINAL_COST);
        }
    }

    /** Walks one candidate crossing; returns it when kept, or null after recording why it was dropped. */
    private static Ray cast(Bounds b, Map<Long, long[]> column, Map<Long, int[]> streetCols, long[] origin,
                            int[] d, int maxStep, StreetRules rules, StatsBuilder stats,
                            Map<Long, Integer> platforms) {
        int originX = (int) origin[1];
        int originY = (int) origin[2];
        int originZ = (int) origin[3];
        int x = originX + d[0];
        int z = originZ + d[1];
        int[] first = streetCols.get(pack(x, z));
        if (first == null || Math.abs(first[0] - originY) > maxStep) {
            return null; // not the start of a crossing
        }
        String where = x + "," + first[0] + "," + z;
        if (platforms.getOrDefault(origin[0], 0) < MIN_PLATFORM) {
            stats.drop(Drop.ISLAND, where);
            return null;
        }
        List<int[]> cells = new ArrayList<>();
        int prevY = originY;
        long[] far;
        while (true) {
            if (x < b.minX() || x > b.maxX() || z < b.minZ() || z > b.maxZ()) {
                stats.drop(Drop.BOX_EDGE, where);
                return null;
            }
            int[] street = streetCols.get(pack(x, z));
            if (street == null) {
                far = column.get(pack(x, z));
                break;
            }
            if (cells.size() >= rules.maxCrossingLength()) {
                stats.drop(Drop.TOO_LONG, where);
                return null;
            }
            if (Math.abs(street[0] - prevY) > maxStep) {
                stats.drop(Drop.STEP, where);
                return null;
            }
            cells.add(new int[]{x, z, street[0], street[1]});
            prevY = street[0];
            x += d[0];
            z += d[1];
        }
        if (far == null) {
            // Sidewalk right behind the blocker means a curb or a plot outline, not a hole in the road.
            long[] beyond = column.get(pack(x + d[0], z + d[1]));
            stats.drop(beyond != null && beyond[2] == NavSurface.SIDEWALK.code() ? Drop.CURB : Drop.BLOCKED, where);
            return null;
        }
        if (far[2] != NavSurface.SIDEWALK.code()) {
            stats.drop(Drop.NOT_SIDEWALK, where);
            return null;
        }
        if (Math.abs((int) far[0] - prevY) > maxStep) {
            stats.drop(Drop.STEP, where);
            return null;
        }
        if (platforms.getOrDefault(far[1], 0) < MIN_PLATFORM) {
            stats.drop(Drop.ISLAND, where);
            return null;
        }
        int length = cells.size();
        // Owner rule: never walk along a road. A genuine crossing is no longer than the road is wide, so
        // the street must extend at least that far sideways at EVERY cell. Checking only the middle let a
        // stub running down a road's mouth through when its middle cell landed in the junction.
        int perpX = d[0] == 0 ? 1 : 0;
        int perpZ = d[0] == 0 ? 0 : 1;
        boolean alongRoad = false;
        boolean narrowForSure = false;
        int width = Integer.MAX_VALUE;
        for (int[] cell : cells) {
            int extent = 1;
            boolean leftBox = false;
            for (int sign = -1; sign <= 1; sign += 2) {
                int sideY = cell[2];
                for (int k = 1; k <= length; k++) {
                    int px = cell[0] + sign * k * perpX;
                    int pz = cell[1] + sign * k * perpZ;
                    if (px < b.minX() || px > b.maxX() || pz < b.minZ() || pz > b.maxZ()) {
                        leftBox = true;
                        break;
                    }
                    int[] beside = streetCols.get(pack(px, pz));
                    // Road at another height is a different road, not more width for this one.
                    if (beside == null || Math.abs(beside[0] - sideY) > maxStep) {
                        break;
                    }
                    sideY = beside[0];
                    extent++;
                }
            }
            width = Math.min(width, extent);
            if (extent < length) {
                alongRoad = true;
                // A box edge can only lose a crossing, never invent one: the true extent is never smaller.
                narrowForSure |= !leftBox;
            }
        }
        if (alongRoad) {
            stats.drop(narrowForSure ? Drop.ALONG_ROAD : Drop.BOX_EDGE, where);
            return null;
        }
        int axis = d[0] != 0 ? 0 : 1;
        int fixed = axis == 0 ? originZ : originX;
        int low = Integer.MAX_VALUE;
        for (int[] cell : cells) {
            low = Math.min(low, axis == 0 ? cell[0] : cell[1]);
        }
        return new Ray(axis, fixed, low, origin[0], far[1], cells, width);
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

    /** Mutable tallies while a scan runs; frozen into {@link Stats} at the end. */
    private static final class StatsBuilder {
        private final Map<NavSurface, Integer> nodes = new EnumMap<>(NavSurface.class);
        private final Map<Drop, Integer> dropped = new EnumMap<>(Drop.class);
        private final Map<Drop, List<String>> samples = new EnumMap<>(Drop.class);
        int crosswalkRays;
        int plainRays;

        void count(NavSurface surface) {
            nodes.merge(surface, 1, Integer::sum);
        }

        void drop(Drop reason, String where) {
            dropped.merge(reason, 1, Integer::sum);
            List<String> list = samples.computeIfAbsent(reason, r -> new ArrayList<>());
            if (list.size() < DROP_SAMPLES) {
                list.add(where);
            }
        }

        Stats build() {
            // Enum-ordered maps, so reports list classes and reasons in a fixed order.
            Map<Drop, List<String>> frozen = new EnumMap<>(Drop.class);
            samples.forEach((reason, list) -> frozen.put(reason, List.copyOf(list)));
            return new Stats(Collections.unmodifiableMap(new EnumMap<>(nodes)), crosswalkRays, plainRays,
                    Collections.unmodifiableMap(new EnumMap<>(dropped)), Collections.unmodifiableMap(frozen));
        }
    }
}
