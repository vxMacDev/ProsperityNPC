package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * A sliced, read-only look at the world cells a published graph depends on. The cells are worked out
 * up front (each node's support and the two blocks above it, and the footprint of every edge, which is
 * what a walking resident is checked against), then read a few hundred per tick by {@link #step}.
 * The pure evaluators afterwards say whether the world still matches the graph
 * ({@link #revalidation}), or what class each node really stands on ({@link #live}).
 *
 * <p>The reader is the only thing that touches a world, so tests drive it with a fake. A chunk that is
 * not loaded is reported by the reader as {@link #NOT_LOADED}, and such cells are never counted as fine.
 *
 * <p>Only {@link #step} belongs on the main thread. Planning the cells, {@link #planWidthProbes},
 * {@link #chunkKeys}, {@link #revalidation} and {@link #live} are pure and meant for a worker; block names
 * for the report are taken inside the sliced reads, so nothing afterwards needs the world.
 *
 * <p>A class sweep reads in two rounds. The first reads what every node stands on; the second reads the
 * street sideways from every crossing, and which crossings there are depends on what the first round found
 * (a graph scanned without classes stores none), so {@link #planWidthProbes} works them out in between.
 * That round also reads the two corner columns of every diagonal step between sidewalk-side nodes.
 */
public final class NavSweep {

    /** Reader flags: {@link NavTerrainCapture#SUPPORT} and {@link NavTerrainCapture#CLEAR}, plus these. */
    public static final byte SOLID = 0x20;
    public static final byte NOT_LOADED = (byte) 0x80;

    // The tallest step between neighbouring cells that still counts as one street, as the scan allows.
    private static final int MAX_STEP = StreetAudit.MAX_STEP;

    // What the world shows in a diagonal step's corner column.
    private static final int CORNER_CLEAR = 0;
    private static final int CORNER_ROAD = 1;
    private static final int CORNER_UNREAD = 2;

    @FunctionalInterface
    public interface Reader {
        byte read(int x, int y, int z);
    }

    @FunctionalInterface
    public interface MaterialNamer {
        String name(int x, int y, int z);
    }

    public enum Verdict { CLEAN, DRIFTED, INCOMPLETE, WORLD_MISSING }

    /** One place where the world no longer matches the graph. */
    public record Drift(String kind, long nodeId, boolean tagged, int x, int y, int z, String detail) { }

    public record Revalidation(Verdict verdict, int nodes, int ok, int changedClass, int unwalkable, int blocked,
                               int footprint, int notLoaded, String palette, List<Drift> drifts,
                               int[] driftBox) {
        /** The one line written to the server log (and read by the DEV bot) for a revalidation. */
        public String logLine(String graph, String region, int ticketsRemaining) {
            return "NPCNAV-REVALIDATE graph=" + graph + " region=" + region + " verdict=" + verdict + " ok=" + ok
                    + " changed=" + changedClass + " unwalkable=" + unwalkable + " blocked=" + blocked
                    + " footprint=" + footprint + " notloaded=" + notLoaded + " palette=" + palette
                    + " tickets_remaining=" + ticketsRemaining;
        }

        /** At most 20 detail lines, tagged nodes first, then how many were left out. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            for (Drift drift : drifts) {
                if (out.size() == 20) {
                    break;
                }
                out.add(drift.kind() + " node " + drift.nodeId() + " at " + drift.x() + "," + drift.y() + ","
                        + drift.z() + (drift.tagged() ? " [tagged]" : "") + " " + drift.detail());
            }
            if (drifts.size() > 20) {
                out.add("(" + (drifts.size() - 20) + " more omitted)");
            }
            return out;
        }
    }

    private final NavGraph graph;
    private final String storedHash;
    private final boolean footprints;
    private long[] cells;
    private byte[] values;
    private final Map<Long, Integer> index = new HashMap<>();
    // Class sweeps: the support cells of tagged nodes, whose block names are taken while they are read.
    private final Set<Long> named = new HashSet<>();
    private final Map<Long, String> names = new HashMap<>();
    private List<StreetChains.Chain> probed = List.of();
    // Diagonal steps between sidewalk-side nodes, whose two corner columns are read whether or not the graph has nodes there.
    private List<NavGraph.Node[]> diagonals = List.of();
    private int maxCrossing;
    private boolean probesPlanned;
    private int next;

    private NavSweep(NavGraph graph, String storedHash, boolean footprints) {
        this.graph = Objects.requireNonNull(graph);
        this.storedHash = storedHash;
        this.footprints = footprints;
        Set<Long> wanted = new LinkedHashSet<>();
        List<NavGraph.Node> ordered = graph.nodes().stream().sorted(Comparator.comparingLong(NavGraph.Node::id)).toList();
        for (NavGraph.Node node : ordered) {
            wanted.add(pack(node.x(), node.y() - 1, node.z()));
            if (footprints) {
                wanted.add(pack(node.x(), node.y(), node.z()));
                wanted.add(pack(node.x(), node.y() + 1, node.z()));
            }
        }
        if (footprints) {
            for (NavGraph.Edge edge : graph.edges()) {
                NavGraph.Node from = graph.node(edge.from());
                NavGraph.Node to = graph.node(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                int lowY = Math.min(from.y(), to.y());
                int highY = Math.max(from.y(), to.y());
                for (int x = Math.min(from.x(), to.x()); x <= Math.max(from.x(), to.x()); x++) {
                    for (int z = Math.min(from.z(), to.z()); z <= Math.max(from.z(), to.z()); z++) {
                        for (int y = lowY - 1; y <= highY + 1; y++) {
                            wanted.add(pack(x, y, z));
                        }
                    }
                }
            }
        } else {
            for (NavGraph.Node node : ordered) {
                if (!node.tags().isEmpty()) {
                    named.add(pack(node.x(), node.y() - 1, node.z()));
                }
            }
        }
        cells = new long[0];
        values = new byte[0];
        append(wanted);
    }

    /** Adds cells not planned yet; they are read after everything already planned. */
    private void append(Set<Long> wanted) {
        List<Long> fresh = new ArrayList<>();
        for (long cell : wanted) {
            if (!index.containsKey(cell)) {
                fresh.add(cell);
            }
        }
        long[] grown = java.util.Arrays.copyOf(cells, cells.length + fresh.size());
        for (int i = 0; i < fresh.size(); i++) {
            grown[cells.length + i] = fresh.get(i);
            index.put(fresh.get(i), cells.length + i);
        }
        values = java.util.Arrays.copyOf(values, grown.length);
        cells = grown;
    }

    /** Compares a graph with the world: support still solid and in the same class, headroom, edge footprints. */
    public static NavSweep forRevalidation(NavGraph graph, String storedHash) {
        return new NavSweep(graph, storedHash, true);
    }

    /**
     * Reads what class each node really stands on now, then (after {@link #planWidthProbes}) how far the
     * street runs sideways from each crossing, for a live audit.
     */
    public static NavSweep forClasses(NavGraph graph) {
        return new NavSweep(graph, null, false);
    }

    /** Reads up to {@code readBudget} cells within {@code nanosBudget}; a failed read marks nothing read. */
    public boolean step(Reader reader, int readBudget, long nanosBudget, LongSupplier clock) {
        return step(reader, null, readBudget, nanosBudget, clock);
    }

    /** As {@link #step(Reader, int, long, LongSupplier)}; {@code namer} names the support block of tagged nodes as it is read. */
    public boolean step(Reader reader, MaterialNamer namer, int readBudget, long nanosBudget, LongSupplier clock) {
        Objects.requireNonNull(reader);
        Objects.requireNonNull(clock);
        if (readBudget < 1 || readBudget > 4096 || nanosBudget < 1) {
            throw new IllegalArgumentException("invalid sweep slice budget");
        }
        long start = clock.getAsLong();
        for (int reads = 0; next < cells.length && reads < readBudget; reads++) {
            if (clock.getAsLong() - start >= nanosBudget) {
                break;
            }
            long cell = cells[next];
            byte value = reader.read(x(cell), y(cell), z(cell));
            if (namer != null && named.contains(cell) && !unloaded(value & 0xFF)) {
                names.put(cell, namer.name(x(cell), y(cell), z(cell)));
            }
            values[next] = value;
            next++;
        }
        return done();
    }

    public boolean done() {
        return next == cells.length;
    }

    public int total() {
        return cells.length;
    }

    public int read() {
        return next;
    }

    /** Every chunk (packed as in {@link NavChunkLoader#key}) holding a cell this sweep reads. */
    public List<Long> chunkKeys() {
        Set<Long> keys = new TreeSet<>();
        for (long cell : cells) {
            keys.add(NavChunkLoader.key(x(cell) >> 4, z(cell) >> 4));
        }
        return new ArrayList<>(keys);
    }

    private int value(int x, int y, int z) {
        Integer at = index.get(pack(x, y, z));
        if (at == null) {
            throw new IllegalStateException("cell was not planned");
        }
        return values[at] & 0xFF;
    }

    private static boolean unloaded(int value) {
        return (value & 0x80) != 0;
    }

    private static boolean clear(int value) {
        return (value & NavTerrainCapture.CLEAR) != 0;
    }

    private static boolean solid(int value) {
        return (value & SOLID) != 0;
    }

    private static NavSurface surface(int value) {
        return NavSurface.fromCode((value & NavTerrainCapture.SURFACE_MASK) >> NavTerrainCapture.SURFACE_SHIFT);
    }

    // Mirrors the checks a walking resident is held to: solid ground, two clear blocks, on every swept column.
    private static boolean standable(int below, int feet, int head) {
        return solid(below) && clear(feet) && clear(head);
    }

    /** Only valid once {@link #done()}. {@code currentHash} is the effective palette hash as configured now. */
    public Revalidation revalidation(String currentHash) {
        if (!done() || !footprints) {
            throw new IllegalStateException("revalidation needs a finished footprint sweep");
        }
        List<Drift> drifts = new ArrayList<>();
        int ok = 0;
        int changed = 0;
        int unwalkable = 0;
        int blocked = 0;
        int footprint = 0;
        int notLoaded = 0;
        for (NavGraph.Node node : graph.nodes().stream().sorted(Comparator.comparingLong(NavGraph.Node::id)).toList()) {
            int support = value(node.x(), node.y() - 1, node.z());
            int feet = value(node.x(), node.y(), node.z());
            int head = value(node.x(), node.y() + 1, node.z());
            if (unloaded(support) || unloaded(feet) || unloaded(head)) {
                notLoaded++;
                continue;
            }
            boolean tagged = !node.tags().isEmpty();
            boolean fine = true;
            if (!solid(support)) {
                unwalkable++;
                fine = false;
                drifts.add(new Drift("UNWALKABLE", node.id(), tagged, node.x(), node.y(), node.z(), "nothing solid underneath"));
            } else if (node.surface() == NavSurface.UNKNOWN) {
                if ((support & NavTerrainCapture.SUPPORT) == 0) {
                    unwalkable++;
                    fine = false;
                    drifts.add(new Drift("UNWALKABLE", node.id(), tagged, node.x(), node.y(), node.z(),
                            "the block underneath is no longer in the palette"));
                }
            } else if ((support & NavTerrainCapture.SUPPORT) == 0 || surface(support) != node.surface()) {
                changed++;
                fine = false;
                String now = (support & NavTerrainCapture.SUPPORT) == 0 ? "no class" : surface(support).name();
                drifts.add(new Drift("CHANGED_CLASS", node.id(), tagged, node.x(), node.y(), node.z(),
                        "stored " + node.surface() + " now " + now));
            }
            if (!clear(feet) || !clear(head)) {
                blocked++;
                fine = false;
                drifts.add(new Drift("BLOCKED", node.id(), tagged, node.x(), node.y(), node.z(), "no longer clear overhead"));
            }
            if (fine) {
                ok++;
            }
        }
        Set<String> seenEdges = new HashSet<>();
        for (NavGraph.Edge edge : graph.edges()) {
            NavGraph.Node from = graph.node(edge.from());
            NavGraph.Node to = graph.node(edge.to());
            if (from == null || to == null
                    || !seenEdges.add(Math.min(from.id(), to.id()) + ":" + Math.max(from.id(), to.id()))) {
                continue;
            }
            int result = footprintResult(from, to);
            if (result < 0) {
                notLoaded++;
            } else if (result > 0) {
                footprint++;
                boolean tagged = !from.tags().isEmpty() || !to.tags().isEmpty();
                drifts.add(new Drift("FOOTPRINT", from.id(), tagged, from.x(), from.y(), from.z(),
                        "step to node " + to.id() + " at " + to.x() + "," + to.y() + "," + to.z() + " is no longer walkable"));
            }
        }
        drifts.sort(Comparator.comparing((Drift d) -> !d.tagged()).thenComparingLong(Drift::nodeId)
                .thenComparing(Drift::kind));
        String palette = storedHash == null ? "legacy" : storedHash.equals(currentHash) ? "same" : "changed";
        boolean drifted = !drifts.isEmpty() || palette.equals("changed");
        Verdict verdict = drifted ? Verdict.DRIFTED : notLoaded > 0 ? Verdict.INCOMPLETE : Verdict.CLEAN;
        return new Revalidation(verdict, graph.nodeCount(), ok, changed, unwalkable, blocked, footprint, notLoaded,
                palette, List.copyOf(drifts), driftBox(drifts));
    }

    /** -1 not fully loaded, 0 walkable, 1 blocked. */
    private int footprintResult(NavGraph.Node from, NavGraph.Node to) {
        int lowY = Math.min(from.y(), to.y());
        int highY = Math.max(from.y(), to.y());
        boolean bad = false;
        for (int x = Math.min(from.x(), to.x()); x <= Math.max(from.x(), to.x()); x++) {
            for (int z = Math.min(from.z(), to.z()); z <= Math.max(from.z(), to.z()); z++) {
                for (int y = lowY - 1; y <= highY + 1; y++) {
                    if (unloaded(value(x, y, z))) {
                        return -1;
                    }
                }
                boolean walkable = clear(value(x, highY, z)) && clear(value(x, highY + 1, z))
                        && (standable(value(x, lowY - 1, z), value(x, lowY, z), value(x, lowY + 1, z))
                        || standable(value(x, highY - 1, z), value(x, highY, z), value(x, highY + 1, z)));
                bad |= !walkable;
            }
        }
        return bad ? 1 : 0;
    }

    private int[] driftBox(List<Drift> drifts) {
        if (drifts.isEmpty()) {
            return null;
        }
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Drift d : drifts) {
            box[0] = Math.min(box[0], d.x());
            box[1] = Math.min(box[1], d.y());
            box[2] = Math.min(box[2], d.z());
            box[3] = Math.max(box[3], d.x());
            box[4] = Math.max(box[4], d.y());
            box[5] = Math.max(box[5], d.z());
        }
        return box;
    }

    /** True once a class sweep has read what every node stands on and still has to plan its width probes. */
    public boolean needsWidthProbes() {
        return !footprints && done() && !probesPlanned;
    }

    /** What a node stands on according to the world; the stored class when the block is in no class or was not read. */
    private NavSurface effectiveSurface(NavGraph.Node node) {
        int support = value(node.x(), node.y() - 1, node.z());
        if (!unloaded(support) && (support & NavTerrainCapture.SUPPORT) != 0 && solid(support)) {
            return surface(support);
        }
        return node.surface();
    }

    /**
     * Plans the second round of a class sweep: for every crossing the world shows (a stored graph with no
     * classes has none until the world is read), the cells beside each of its cells. Only valid once the
     * first round is read; afterwards {@link #done()} is false until the new cells are read too.
     *
     * <p>A run of street cells longer than {@code maxCrossingLength} is already a walk along a road, not a
     * crossing (a graph that was never classified joins a whole road network into one), so nothing is read
     * beside it; the audit reports it from the classes alone.
     */
    public void planWidthProbes(int maxCrossingLength) {
        if (!needsWidthProbes()) {
            throw new IllegalStateException("width probes are planned once, after the node classes are read");
        }
        Map<Long, NavSurface> effective = new HashMap<>();
        for (NavGraph.Node node : graph.nodes()) {
            effective.put(node.id(), effectiveSurface(node));
        }
        maxCrossing = maxCrossingLength;
        probed = StreetChains.find(graph, effective::get);
        Set<Long> wanted = new LinkedHashSet<>();
        for (StreetChains.Chain chain : probed) {
            if (chain.length() > maxCrossing) {
                continue;
            }
            int px = chain.alongX() ? 0 : 1;
            int pz = chain.alongX() ? 1 : 0;
            for (NavGraph.Node cell : chain.cells()) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    for (int k = 1; k <= chain.length(); k++) {
                        // Each column may sit one step higher or lower than the one before it, so k columns out
                        // the street can have risen or fallen k steps from the crossing's own support.
                        for (int y = cell.y() - 1 - k * MAX_STEP; y <= cell.y() - 1 + k * MAX_STEP; y++) {
                            wanted.add(pack(cell.x() + sign * k * px, y, cell.z() + sign * k * pz));
                        }
                    }
                }
            }
        }
        // A diagonal step also crosses the two corner columns beside it. A step that touches a street node is
        // already reported by the run rules, so only steps between sidewalk-side nodes need their corners read.
        List<NavGraph.Node[]> steps = new ArrayList<>();
        Set<StreetAudit.Diagonal> seen = new HashSet<>();
        for (NavGraph.Edge edge : graph.edges()) {
            NavGraph.Node from = graph.node(edge.from());
            NavGraph.Node to = graph.node(edge.to());
            if (from == null || to == null || !diagonal(from, to)
                    || effective.get(from.id()).isStreet() || effective.get(to.id()).isStreet()
                    || !seen.add(StreetAudit.Diagonal.of(from.id(), to.id()))) {
                continue;
            }
            steps.add(new NavGraph.Node[]{from, to});
            for (int[] corner : corners(from, to)) {
                for (int y = Math.min(from.y(), to.y()) - 2; y <= Math.max(from.y(), to.y()); y++) {
                    wanted.add(pack(corner[0], y, corner[1]));
                }
            }
        }
        diagonals = steps;
        append(wanted);
        probesPlanned = true;
    }

    private static boolean diagonal(NavGraph.Node a, NavGraph.Node b) {
        return Math.abs(b.x() - a.x()) == 1 && Math.abs(b.z() - a.z()) == 1;
    }

    /** The x,z of the two columns a diagonal step passes beside. */
    private static int[][] corners(NavGraph.Node a, NavGraph.Node b) {
        return new int[][]{{b.x(), a.z()}, {a.x(), b.z()}};
    }

    /**
     * What each node really stands on now, for a live audit. A node whose support is in no class is
     * off-palette; one in an unloaded chunk is unchecked. The support block of tagged nodes is named as
     * it was read, so staff learn which materials belong in the {@code access} list. A crossing whose
     * sideways cells were not all read has no width here, and the audit counts that as unchecked.
     */
    public StreetAudit.Live live() {
        if (!done() || footprints || !probesPlanned) {
            throw new IllegalStateException("live classes need a finished class sweep with its width probes read");
        }
        Map<Long, NavSurface> classes = new HashMap<>();
        Set<Long> offPalette = new HashSet<>();
        Map<Long, String> materials = new HashMap<>();
        int unchecked = 0;
        for (NavGraph.Node node : graph.nodes()) {
            int support = value(node.x(), node.y() - 1, node.z());
            if (unloaded(support)) {
                unchecked++;
                continue;
            }
            if ((support & NavTerrainCapture.SUPPORT) == 0 || !solid(support)) {
                offPalette.add(node.id());
            } else {
                classes.put(node.id(), surface(support));
            }
            String name = names.get(pack(node.x(), node.y() - 1, node.z()));
            if (name != null) {
                materials.put(node.id(), name);
            }
        }
        Map<Long, Integer> extents = new HashMap<>();
        for (StreetChains.Chain chain : probed) {
            if (chain.length() > maxCrossing) {
                continue;
            }
            Integer extent = extent(chain);
            if (extent != null) {
                extents.put(chain.mid(), extent);
            }
        }
        Set<StreetAudit.Diagonal> roadCorners = new HashSet<>();
        Set<StreetAudit.Diagonal> unreadCorners = new HashSet<>();
        for (NavGraph.Node[] step : diagonals) {
            int worst = CORNER_CLEAR;
            for (int[] corner : corners(step[0], step[1])) {
                int state = cornerState(corner[0], corner[1], Math.min(step[0].y(), step[1].y()) - 2,
                        Math.max(step[0].y(), step[1].y()));
                // Road at either corner is a failure whatever the other shows; an unread one only matters without road.
                worst = worst == CORNER_ROAD || state == CORNER_ROAD ? CORNER_ROAD : Math.max(worst, state);
            }
            StreetAudit.Diagonal key = StreetAudit.Diagonal.of(step[0].id(), step[1].id());
            if (worst == CORNER_ROAD) {
                roadCorners.add(key);
            } else if (worst == CORNER_UNREAD) {
                unreadCorners.add(key);
            }
        }
        return new StreetAudit.Live(classes, offPalette, unchecked, extents, materials, roadCorners, unreadCorners);
    }

    /**
     * What stands in a corner column between support heights {@code lowY} and {@code highY}: the topmost
     * classed block there is what a walker would stand on, and it is road or not. A cell that was not read
     * before that is found leaves the column unread.
     */
    private int cornerState(int x, int z, int lowY, int highY) {
        for (int y = highY; y >= lowY; y--) {
            int cell = value(x, y, z);
            if (unloaded(cell)) {
                return CORNER_UNREAD;
            }
            if ((cell & NavTerrainCapture.SUPPORT) != 0) {
                return surface(cell).isStreet() ? CORNER_ROAD : CORNER_CLEAR;
            }
        }
        return CORNER_CLEAR;
    }

    /**
     * The narrowest sideways run of street cells over every cell of the chain (so a stub that only meets a
     * wide junction in its middle still counts as narrow); null if any needed cell is unread.
     */
    private Integer extent(StreetChains.Chain chain) {
        int px = chain.alongX() ? 0 : 1;
        int pz = chain.alongX() ? 1 : 0;
        int narrowest = Integer.MAX_VALUE;
        for (NavGraph.Node at : chain.cells()) {
            int extent = 1;
            for (int sign = -1; sign <= 1; sign += 2) {
                int supportY = at.y() - 1;
                for (int k = 1; k <= chain.length(); k++) {
                    // Street at another height is a different road: the column counts only if its topmost
                    // classed block is street within one step of the cell before it, as when the scan measured it.
                    int top = Integer.MIN_VALUE;
                    boolean street = false;
                    for (int y = supportY + MAX_STEP; y >= supportY - MAX_STEP; y--) {
                        int cell = value(at.x() + sign * k * px, y, at.z() + sign * k * pz);
                        if (unloaded(cell)) {
                            return null;
                        }
                        if ((cell & NavTerrainCapture.SUPPORT) != 0) {
                            top = y;
                            street = surface(cell).isStreet();
                            break;
                        }
                    }
                    if (!street) {
                        break;
                    }
                    supportY = top;
                    extent++;
                }
            }
            narrowest = Math.min(narrowest, extent);
        }
        return narrowest;
    }

    // A block coordinate packed into one long: 26 bits each for x and z, 12 for y.
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    static int x(long cell) {
        return (int) (cell >> 38);
    }

    static int z(long cell) {
        return (int) ((cell << 26) >> 38);
    }

    static int y(long cell) {
        return (int) (cell & 0xFFF) - 2048;
    }
}
