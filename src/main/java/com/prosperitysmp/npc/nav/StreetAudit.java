package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.resident.ResidentRoutePlanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * Proves, from a built graph alone, that routes obey the sidewalk rules: they start and end on
 * sidewalks, step onto a road only to cross it in a straight line from sidewalk to sidewalk, never
 * run along a road, and take a painted crosswalk whenever the router would. Pure: no world access.
 * Classes normally come from the stored node surfaces; a live sweep can supply them instead. Without a
 * sweep, how far each crossing may run is the road width the scan recorded on it; a crossing with none is
 * unchecked, never failed or passed on that point.
 *
 * <p>A graph small enough to publish is audited the way residents walk it: from every non-street node
 * to every tag with {@link ResidentRoutePlanner#planToTag}. A larger draft is audited between every
 * ordered pair of tagged nodes, which is what corridor authoring keeps. The verdict can be FAIL, and it
 * is never PASS while anything is unclassified, unchecked or cut short by the time limit.
 */
public final class StreetAudit {

    public enum Verdict { PASS, FAIL, INCONCLUSIVE }

    public enum Code {
        START_ON_STREET, END_ON_STREET, STOP_ON_STREET, NO_SIDEWALK_EXIT, LATERAL, TOO_LONG, ALONG_ROAD,
        OFF_PALETTE, CROSSWALK_SKIPPED, BUDGET_EXHAUSTED, NO_ROUTE, RAISED_SIDEWALK
    }

    /** A diagonal step between two nodes, the same step whichever way round it is walked. */
    public record Diagonal(long low, long high) {
        public static Diagonal of(long a, long b) {
            return new Diagonal(Math.min(a, b), Math.max(a, b));
        }
    }

    /**
     * Facts from a world sweep. Any field may be empty; {@code classes} overrides stored surfaces.
     * {@code roadCorners} are the diagonal steps with road under a corner column in the world, and
     * {@code unreadCorners} those with a corner column the sweep could not read; both are read from the
     * world itself, so they hold even where the graph has no node beside the step.
     */
    public record Live(Map<Long, NavSurface> classes, Set<Long> offPalette, int unchecked,
                       Map<Long, Integer> extents, Map<Long, String> materials,
                       Set<Diagonal> roadCorners, Set<Diagonal> unreadCorners) {
        public Live {
            classes = Map.copyOf(classes);
            offPalette = Set.copyOf(offPalette);
            extents = Map.copyOf(extents);
            materials = Map.copyOf(materials);
            roadCorners = Set.copyOf(roadCorners);
            unreadCorners = Set.copyOf(unreadCorners);
        }

        public Live(Map<Long, NavSurface> classes, Set<Long> offPalette, int unchecked,
                    Map<Long, Integer> extents, Map<Long, String> materials) {
            this(classes, offPalette, unchecked, extents, materials, Set.of(), Set.of());
        }
    }

    /** {@code maxNodes}: a graph with more nodes than this is not audited at all, and the verdict is INCONCLUSIVE. */
    public record Options(StreetRules rules, long maxNanos, int maxStarts, LongSupplier clock, int maxNodes) {
        public static final int DEFAULT_MAX_NODES = 50_000;

        public Options(StreetRules rules, long maxNanos, int maxStarts, LongSupplier clock) {
            this(rules, maxNanos, maxStarts, clock, DEFAULT_MAX_NODES);
        }
    }

    public record Violation(Code code, String detail) { }

    public record Result(String mode, Verdict verdict, int nodes, int edges, boolean withinRuntimeLimits,
                         int starts, int paths, int failedPaths, int maxRun, Map<Code, Integer> counts,
                         int unclassified, int unchecked, int offPaletteNodes, int stopsOnStreet,
                         boolean timedOut, boolean startsCapped, boolean overNodeCap, List<Violation> examples,
                         List<String> stops) {
        public Result(String mode, Verdict verdict, int nodes, int edges, boolean withinRuntimeLimits, int starts,
                      int paths, int failedPaths, int maxRun, Map<Code, Integer> counts, int unclassified,
                      int unchecked, int offPaletteNodes, int stopsOnStreet, boolean timedOut, boolean startsCapped,
                      List<Violation> examples, List<String> stops) {
            this(mode, verdict, nodes, edges, withinRuntimeLimits, starts, paths, failedPaths, maxRun, counts,
                    unclassified, unchecked, offPaletteNodes, stopsOnStreet, timedOut, startsCapped, false, examples, stops);
        }

        public int count(Code code) {
            return counts.getOrDefault(code, 0);
        }
    }

    private static final int MAX_EXAMPLES = 50;
    /** The tallest step a scan allows between neighbouring cells. */
    static final int MAX_STEP = 1;

    /** The one line written to the server log (and read by the DEV bot) for an audit. */
    public static String logLine(String graph, Result r) {
        return "NPCNAV-AUDIT graph=" + graph + " mode=" + r.mode() + " verdict=" + r.verdict() + " starts=" + r.starts()
                + " paths=" + r.paths() + " failed=" + r.failedPaths() + " maxRun=" + r.maxRun()
                + " lateral=" + r.count(Code.LATERAL) + " crosswalkSkipped=" + r.count(Code.CROSSWALK_SKIPPED)
                + " offPalette=" + r.offPaletteNodes() + " budget=" + r.count(Code.BUDGET_EXHAUSTED)
                + " unclassified=" + r.unclassified() + " unchecked=" + r.unchecked();
    }
    private static final Set<Code> WARNING_ONLY = EnumSet.of(Code.RAISED_SIDEWALK);

    private final NavGraph graph;
    private final Live live;
    private final Options options;
    private final Map<Long, NavSurface> surface = new HashMap<>();
    private final Map<Long, StreetChains.Chain> chainOf = new HashMap<>();
    private final List<StreetChains.Chain> chains = new ArrayList<>();
    private final List<StreetChains.Chain> crosswalks = new ArrayList<>();
    private final Map<Long, Map<Long, Long>> sidewalkCosts = new HashMap<>();
    // Street nodes by column, to see which corner cells a diagonal step passes over.
    private final Map<Long, List<NavGraph.Node>> streetColumns = new HashMap<>();
    private final Map<Code, Integer> counts = new EnumMap<>(Code.class);
    private final List<Violation> examples = new ArrayList<>();
    private int maxRun;
    // Diagonal steps on audited paths whose corner columns the world reading could not see.
    private final Set<Diagonal> unreadSteps = new HashSet<>();
    // Crossings of a stored graph that carry no scan-measured road width.
    private int unrecordedCrossings;
    // The time limit covers preparation too; checkPath leaves it off because it judges one path with no limit.
    private long started;
    private boolean timing;
    private boolean timedOut;

    private StreetAudit(NavGraph graph, Live live, Options options) {
        this.graph = graph;
        this.live = live;
        this.options = options;
    }

    public static Result audit(NavGraph graph, Live live, Options options) {
        return new StreetAudit(graph, live, options).run();
    }

    private NavSurface surface(long id) {
        return surface.get(id);
    }

    /** Works out each node's effective class and the crossings of the graph. */
    private void prepare() {
        for (NavGraph.Node node : graph.nodes()) {
            NavSurface stored = node.surface();
            surface.put(node.id(), live != null && live.classes().containsKey(node.id())
                    ? live.classes().get(node.id()) : stored);
        }
        for (NavGraph.Node node : graph.nodes()) {
            if (surface(node.id()).isStreet()) {
                streetColumns.computeIfAbsent(column(node.x(), node.z()), k -> new ArrayList<>()).add(node);
            }
        }
        for (StreetChains.Chain chain : StreetChains.find(graph, this::surface)) {
            if (expired()) {
                return;
            }
            chains.add(chain);
            for (NavGraph.Node cell : chain.cells()) {
                chainOf.put(cell.id(), chain);
            }
            List<NavSurface> along = chain.cells().stream().map(cell -> surface(cell.id())).toList();
            if (options.rules().isCrosswalk(along) && chain.curbs().size() == 2) {
                crosswalks.add(chain);
            }
        }
    }

    /** The rules one hand-built path breaks (warnings excluded); used to test each rule on its own. */
    static Set<Code> checkPath(NavGraph graph, Live live, Options options, List<Long> path) {
        return checkPath(graph, live, options, ResidentRoutePlanner.Status.WALK, path);
    }

    static Set<Code> checkPath(NavGraph graph, Live live, Options options, ResidentRoutePlanner.Status status,
                               List<Long> path) {
        StreetAudit audit = new StreetAudit(graph, live, options);
        audit.prepare();
        Set<Code> found = audit.pathCodes(status, path, "path: ");
        found.removeAll(WARNING_ONLY);
        return found;
    }

    /** True once the time limit has passed; the audit then stops searching and cannot pass. */
    private boolean expired() {
        if (timing && !timedOut && options.clock().getAsLong() - started > options.maxNanos()) {
            timedOut = true;
        }
        return timedOut;
    }

    private Result run() {
        if (graph.nodeCount() > options.maxNodes()) {
            // Too big to audit within any sensible time; say so rather than start.
            return new Result("authoring", Verdict.INCONCLUSIVE, graph.nodeCount(), graph.edgeCount(), false, 0, 0, 0, 0,
                    new EnumMap<>(Code.class), 0, 0, 0, 0, false, false, true, List.of(), List.of());
        }
        started = options.clock().getAsLong();
        timing = true;
        prepare();
        boolean runtime = graph.nodeCount() <= Dijkstra.MAX_VISITED && graph.edgeCount() <= Dijkstra.MAX_EDGES_EXAMINED;
        Set<Long> offPalette = live == null ? Set.of() : live.offPalette();
        int unclassified = 0;
        for (NavGraph.Node node : graph.nodes()) {
            if (surface(node.id()) == NavSurface.UNKNOWN && !offPalette.contains(node.id())) {
                unclassified++;
            }
        }

        List<String> stops = new ArrayList<>();
        int stopsOnStreet = 0;
        for (NavGraph.Node node : graph.nodes().stream().sorted(Comparator.comparingLong(NavGraph.Node::id)).toList()) {
            if (node.tags().isEmpty()) {
                continue;
            }
            String material = live == null ? null : live.materials().get(node.id());
            stops.add("node " + node.id() + " " + new TreeSet<>(node.tags()) + " support="
                    + (material == null ? "?" : material) + " class=" + surface(node.id()));
            if (surface(node.id()).isStreet()) {
                stopsOnStreet++;
            }
        }
        for (NavGraph.Node node : graph.nodes()) {
            if (offPalette.contains(node.id())) {
                example(Code.OFF_PALETTE, "node " + node.id() + " at " + where(node.id()) + " stands on a block in no class");
            }
        }
        countRaisedSidewalks();
        if (live == null) {
            checkStoredWidths();
        } else {
            flagStreetsTooLongToCross();
        }

        int starts = 0;
        int paths = 0;
        int failed = 0;
        boolean capped = false;
        List<Long> startIds;
        if (runtime) {
            startIds = graph.nodes().stream().filter(n -> !surface(n.id()).isStreet())
                    .map(NavGraph.Node::id).sorted().toList();
        } else {
            startIds = graph.nodes().stream().filter(n -> !n.tags().isEmpty())
                    .map(NavGraph.Node::id).sorted().toList();
        }
        if (startIds.size() > options.maxStarts()) {
            capped = true;
            startIds = startIds.subList(0, options.maxStarts());
        }
        List<String> tags = graph.nodes().stream().flatMap(n -> n.tags().stream()).distinct().sorted().toList();
        search:
        for (long start : startIds) {
            if (expired()) {
                break;
            }
            starts++;
            if (runtime) {
                for (String tag : tags) {
                    if (expired()) {
                        break search;
                    }
                    ResidentRoutePlanner.Decision decision = ResidentRoutePlanner.planToTag(graph, start, tag);
                    paths++;
                    if (analyze(start, tag, decision.status(), decision.nodes())) {
                        failed++;
                    }
                    if (timedOut) {
                        break search;
                    }
                }
            } else {
                for (long goal : startIds) {
                    if (goal == start) {
                        continue;
                    }
                    if (expired()) {
                        break search;
                    }
                    Dijkstra.Path path = Dijkstra.find(graph, start, goal);
                    paths++;
                    ResidentRoutePlanner.Status status = path.found()
                            ? ResidentRoutePlanner.Status.WALK : ResidentRoutePlanner.Status.NO_ROUTE;
                    if (analyze(start, "node " + goal, status, path.nodes())) {
                        failed++;
                    }
                    if (timedOut) {
                        break search;
                    }
                }
            }
        }

        // One last look at the clock: the final path can finish after the limit without any check seeing it.
        expired();
        // Every crossing needs a reading of the road beside it; one the sweep could not read is unchecked, not fine.
        int unchecked = live == null ? unrecordedCrossings
                : live.unchecked() + unmeasuredCrossings() + unreadSteps.size();
        boolean anyFail = stopsOnStreet > 0 || !offPalette.isEmpty();
        for (Map.Entry<Code, Integer> entry : counts.entrySet()) {
            if (!WARNING_ONLY.contains(entry.getKey()) && entry.getValue() > 0) {
                anyFail = true;
            }
        }
        Verdict verdict = anyFail ? Verdict.FAIL
                : paths == 0 || unclassified > 0 || unchecked > 0 || timedOut || capped
                ? Verdict.INCONCLUSIVE : Verdict.PASS;
        return new Result(runtime ? "runtime" : "authoring", verdict, graph.nodeCount(), graph.edgeCount(), runtime,
                starts, paths, failed, maxRun, new EnumMap<>(counts), unclassified, unchecked, offPalette.size(),
                stopsOnStreet, timedOut, capped, false, List.copyOf(examples), List.copyOf(stops));
    }

    private void add(Code code, String detail) {
        counts.merge(code, 1, Integer::sum);
        example(code, detail);
    }

    private void example(Code code, String detail) {
        if (examples.size() < MAX_EXAMPLES) {
            examples.add(new Violation(code, detail));
        }
    }

    private String where(long id) {
        NavGraph.Node node = graph.node(id);
        return node.x() + "," + node.y() + "," + node.z();
    }

    /** A sidewalk node reached only by stepping up from lower ground is probably a wall top or a ledge. */
    private void countRaisedSidewalks() {
        Map<Long, List<Long>> inbound = new HashMap<>();
        for (NavGraph.Edge e : graph.edges()) {
            if (graph.hasNode(e.from()) && graph.hasNode(e.to())
                    && !surface(e.from()).isStreet() && !surface(e.to()).isStreet()) {
                inbound.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
            }
        }
        for (NavGraph.Node node : graph.nodes().stream().sorted(Comparator.comparingLong(NavGraph.Node::id)).toList()) {
            List<Long> from = inbound.get(node.id());
            if (surface(node.id()) != NavSurface.SIDEWALK || from == null) {
                continue;
            }
            if (from.stream().allMatch(id -> graph.node(id).y() == node.y() - 1)) {
                add(Code.RAISED_SIDEWALK, "node " + node.id() + " at " + where(node.id()) + " is only reached by stepping up");
            }
        }
    }

    /** Returns true when the path broke a rule. */
    private boolean analyze(long start, String goal, ResidentRoutePlanner.Status status, List<Long> path) {
        Set<Code> found = pathCodes(status, path, "from node " + start + " to " + goal + ": ");
        for (Code code : found) {
            counts.merge(code, 1, Integer::sum);
        }
        return found.stream().anyMatch(code -> !WARNING_ONLY.contains(code));
    }

    private Set<Code> pathCodes(ResidentRoutePlanner.Status status, List<Long> path, String label) {
        Set<Code> found = EnumSet.noneOf(Code.class);
        if (status == ResidentRoutePlanner.Status.BUDGET_EXHAUSTED) {
            record(found, Code.BUDGET_EXHAUSTED, label + "search budget exhausted");
        } else if (status == ResidentRoutePlanner.Status.NO_ROUTE || status == ResidentRoutePlanner.Status.NO_ANCHOR) {
            record(found, Code.NO_ROUTE, label + "no route");
        }
        int n = path.size();
        if (n > 0) {
            if (surface(path.get(0)).isStreet()) {
                record(found, Code.START_ON_STREET, label + "starts on a road at " + where(path.get(0)));
            }
            if (surface(path.get(n - 1)).isStreet()) {
                record(found, Code.END_ON_STREET, label + "ends on a road at " + where(path.get(n - 1)));
            }
            if (live != null) {
                for (long id : path) {
                    if (live.offPalette().contains(id)) {
                        record(found, Code.OFF_PALETTE, label + "passes a block in no class at " + where(id));
                        break;
                    }
                }
            }
        }
        for (int k = 0; k + 1 < n; k++) {
            checkDiagonal(found, label, graph.node(path.get(k)), graph.node(path.get(k + 1)));
        }
        int i = 0;
        while (i < n) {
            if (!surface(path.get(i)).isStreet()) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && surface(path.get(j)).isStreet()) {
                j++;
            }
            checkRun(found, label, path, i, j);
            i = j;
        }
        return found;
    }

    /**
     * A diagonal step also crosses the two corner cells beside it. If either stands on a road, the step cuts
     * across that corner even though no road node is on the path. A live reading says so for the corners of
     * every step it covers, including ones the graph has no node beside; a corner it could not read is unchecked.
     */
    private void checkDiagonal(Set<Code> found, String label, NavGraph.Node a, NavGraph.Node b) {
        if (Math.abs(b.x() - a.x()) != 1 || Math.abs(b.z() - a.z()) != 1) {
            return;
        }
        if (live != null) {
            Diagonal step = Diagonal.of(a.id(), b.id());
            if (live.roadCorners().contains(step)) {
                record(found, Code.LATERAL, label + "the step at " + where(a.id()) + " cuts a road corner");
                return;
            }
            if (live.unreadCorners().contains(step)) {
                unreadSteps.add(step);
            }
        }
        int[][] corners = {{b.x(), a.z()}, {a.x(), b.z()}};
        for (int[] corner : corners) {
            for (NavGraph.Node beside : streetColumns.getOrDefault(column(corner[0], corner[1]), List.of())) {
                if (Math.abs(beside.y() - a.y()) <= MAX_STEP || Math.abs(beside.y() - b.y()) <= MAX_STEP) {
                    record(found, Code.LATERAL, label + "the step at " + where(a.id()) + " cuts a road corner at "
                            + where(beside.id()));
                    return;
                }
            }
        }
    }

    /** Adds the code once per path; the first occurrence supplies the example text. */
    private void record(Set<Code> found, Code code, String detail) {
        if (found.add(code)) {
            example(code, detail);
        }
    }

    private void checkRun(Set<Code> found, String label, List<Long> path, int from, int to) {
        int length = to - from;
        maxRun = Math.max(maxRun, length);
        String at = "run of " + length + " at " + where(path.get(from));
        long before = from > 0 ? path.get(from - 1) : -1;
        long after = to < path.size() ? path.get(to) : -1;
        if ((before >= 0 && surface(before) != NavSurface.SIDEWALK)
                || (after >= 0 && surface(after) != NavSurface.SIDEWALK)) {
            record(found, Code.NO_SIDEWALK_EXIT, label + at + " is not entered and left from a sidewalk");
        }
        // Every step must be one block in the same cardinal direction as the first.
        List<Long> chain = new ArrayList<>();
        if (before >= 0) {
            chain.add(before);
        }
        chain.addAll(path.subList(from, to));
        if (after >= 0) {
            chain.add(after);
        }
        int firstDx = 0;
        int firstDz = 0;
        for (int k = 0; k + 1 < chain.size(); k++) {
            NavGraph.Node a = graph.node(chain.get(k));
            NavGraph.Node b = graph.node(chain.get(k + 1));
            int dx = b.x() - a.x();
            int dz = b.z() - a.z();
            if (k == 0) {
                firstDx = dx;
                firstDz = dz;
            }
            if (Math.abs(dx) + Math.abs(dz) != 1 || dx != firstDx || dz != firstDz) {
                record(found, Code.LATERAL, label + at + " is not a straight step across");
                break;
            }
        }
        if (length > options.rules().maxCrossingLength()) {
            record(found, Code.TOO_LONG, label + at + " is longer than " + options.rules().maxCrossingLength());
        }
        StreetChains.Chain chain0 = chainOf.get(path.get(from));
        if (live != null && chain0 != null) {
            // The sweep reports the narrowest sideways width along the whole chain, keyed by its middle cell.
            Integer extent = live.extents().get(chain0.mid());
            if (extent != null && length > extent) {
                record(found, Code.ALONG_ROAD, label + at + " is longer than the road is wide (" + extent + ")");
            }
        }
        List<NavSurface> along = new ArrayList<>();
        for (int k = from; k < to; k++) {
            along.add(surface(path.get(k)));
        }
        if (before >= 0 && after >= 0 && !options.rules().isCrosswalk(along)) {
            checkCrosswalkSkipped(found, label + at, before, after, length);
        }
    }

    /**
     * A run of street cells longer than any crossing may be is a strip along a road, however wide the road
     * is. The sweep does not probe beside it, so it is reported here instead.
     */
    private void flagStreetsTooLongToCross() {
        for (StreetChains.Chain chain : chains) {
            if (chain.length() > options.rules().maxCrossingLength()) {
                add(Code.ALONG_ROAD, "street run of " + chain.length() + " at " + where(chain.mid())
                        + " is longer than any crossing (" + options.rules().maxCrossingLength() + ")");
            }
        }
    }

    /** Crossings (as the live classes show them) that the world reading gave no width for. */
    private int unmeasuredCrossings() {
        int missing = 0;
        for (StreetChains.Chain chain : chains) {
            if (chain.length() <= options.rules().maxCrossingLength() && !live.extents().containsKey(chain.mid())) {
                missing++;
            }
        }
        return missing;
    }

    /**
     * With no reading of the world, each crossing is held to the road width the scan recorded on it: a
     * crossing longer than that is a strip along a road, not a way across it. A crossing with no recorded
     * width (an older graph, or one made by hand) cannot be judged, so it is counted as unchecked.
     */
    private void checkStoredWidths() {
        for (StreetChains.Chain chain : chains) {
            int width = recordedWidth(chain);
            if (width == 0) {
                unrecordedCrossings++;
            } else if (chain.length() > width) {
                add(Code.ALONG_ROAD, "crossing of " + chain.length() + " at " + where(chain.mid())
                        + " is longer than the road is wide (" + width + ")");
            }
        }
    }

    /** The narrowest width recorded along the chain, or 0 unless every cell of it has one. */
    private static int recordedWidth(StreetChains.Chain chain) {
        int narrowest = Integer.MAX_VALUE;
        for (NavGraph.Node cell : chain.cells()) {
            if (cell.roadWidth() == 0) {
                return 0;
            }
            narrowest = Math.min(narrowest, cell.roadWidth());
        }
        return narrowest;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** A plain crossing is a mistake when the router would have preferred a painted one. */
    private void checkCrosswalkSkipped(Set<Code> found, String text, long entry, long exit, int length) {
        long plain = options.rules().entryPenalty() + (long) NavScanLogic.CARDINAL_COST * (length + 1);
        for (StreetChains.Chain crosswalk : crosswalks) {
            if (expired()) {
                return;
            }
            long a = crosswalk.curbs().get(0);
            long b = crosswalk.curbs().get(1);
            long cross = (long) NavScanLogic.CARDINAL_COST * (crosswalk.length() + 1);
            long viaAB = sidewalkCost(entry, a) + cross + sidewalkCost(b, exit);
            long viaBA = sidewalkCost(entry, b) + cross + sidewalkCost(a, exit);
            if (Math.min(viaAB, viaBA) < plain) {
                record(found, Code.CROSSWALK_SKIPPED, text + " skips a painted crosswalk at "
                        + where(crosswalk.mid()));
                return;
            }
        }
    }

    private static final long UNREACHABLE = Long.MAX_VALUE / 4;

    /** Cheapest walk between two nodes using only sidewalk-side edges; UNREACHABLE when there is none. */
    private long sidewalkCost(long from, long to) {
        Map<Long, Long> dist = sidewalkCosts.computeIfAbsent(from, this::sidewalkDistances);
        return dist.getOrDefault(to, UNREACHABLE);
    }

    private Map<Long, Long> sidewalkDistances(long source) {
        Map<Long, Long> dist = new HashMap<>();
        Set<Long> settled = new HashSet<>();
        PriorityQueue<long[]> queue = new PriorityQueue<>((x, y) -> Long.compare(x[1], y[1]));
        dist.put(source, 0L);
        queue.add(new long[]{source, 0});
        int settledCount = 0;
        while (!queue.isEmpty()) {
            long[] top = queue.poll();
            if (!settled.add(top[0])) {
                continue;
            }
            // A search over a whole district can be long; look at the clock now and then.
            if (++settledCount % 256 == 0 && expired()) {
                break;
            }
            for (NavGraph.Edge e : graph.neighbors(top[0])) {
                if (e.cost() <= 0 || !graph.hasNode(e.to()) || surface(e.to()).isStreet()) {
                    continue;
                }
                long next = top[1] + e.cost();
                if (next < dist.getOrDefault(e.to(), Long.MAX_VALUE)) {
                    dist.put(e.to(), next);
                    queue.add(new long[]{e.to(), next});
                }
            }
        }
        return dist;
    }
}
