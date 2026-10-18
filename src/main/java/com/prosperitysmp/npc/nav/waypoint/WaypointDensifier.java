package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavScanLogic;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.StreetCosts;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Link;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a waypoint set into a navigation graph: one node per block along every link, joined the way the
 * block scanner joins them. Pure and deterministic: the same set always gives a byte-identical graph.
 *
 * <p>A link runs straight along x or z; it never bends, so no cell can end up inside a wall. Heights are
 * spread evenly along the link in exact integers, worked out from the end that sorts first, so a link gives
 * the same cells whichever way round it was written. Walk links join cells both ways at the ordinary step
 * cost. Crossing links put road (or crosswalk) cells between their two sidewalk points and cost them exactly
 * as the scanner costs a crossing ray, through {@link StreetCosts}.
 */
public final class WaypointDensifier {

    /** The graph limits: by default the runtime search limits, so every waypoint graph is fully searchable. */
    public record Limits(int maxNodes, int maxEdges) {
        public static final Limits DEFAULT = new Limits(Dijkstra.MAX_VISITED, Dijkstra.MAX_EDGES_EXAMINED);
    }

    /**
     * The built graph, where each node came from ("point hub" or "link corner-hub, cell 3"), how many crossings
     * came out painted and plain, and every crossing's node ids from one curb to the other (curbs included).
     */
    public record Result(NavGraph graph, Map<Long, String> provenance, int crosswalkLinks, int plainLinks,
                         List<long[]> crossings) {
        public Result {
            List<long[]> copy = new ArrayList<>();
            for (long[] chain : crossings) copy.add(chain.clone());
            crossings = Collections.unmodifiableList(copy);
        }

        /** Every crossing cell between the curbs: the cells whose paint the world read decides. */
        public Set<Long> crossingCells() {
            Set<Long> out = new java.util.TreeSet<>();
            for (long[] chain : crossings) {
                for (int i = 1; i < chain.length - 1; i++) out.add(chain[i]);
            }
            return out;
        }
    }

    private record Draft(NavSurface surface, Set<String> tags, String from, boolean point) { }

    private WaypointDensifier() {
    }

    /**
     * Every cell along a link, from the end that sorts first by (x, y, z) to the other, both ends included;
     * null when the link does not run straight along x or z.
     */
    public static List<int[]> cells(Point a, Point b) {
        return cells(a, b, Dijkstra.MAX_GRAPH_NODES);
    }

    static List<int[]> cells(Point a, Point b, int maxCells) {
        Point s = WaypointSet.byCell().compare(a, b) <= 0 ? a : b;
        Point e = s == a ? b : a;
        long dx = (long) e.x() - s.x();
        long dz = (long) e.z() - s.z();
        if ((dx != 0 && dz != 0) || (dx == 0 && dz == 0)) {
            return null;
        }
        long length = Math.abs(dx) + Math.abs(dz);
        int limit = Math.min(maxCells, Dijkstra.MAX_GRAPH_NODES);
        if (length + 1 > limit) {
            throw new EditRefused("WP_CAP_NODES", "Link " + a.name() + "-" + b.name() + " makes "
                    + (length + 1) + " nodes; the limit is " + limit + ". Split this district into two regions.");
        }
        int n = (int) length;
        int sx = Long.signum(dx);
        int sz = Long.signum(dz);
        long dy = (long) e.y() - s.y();
        List<int[]> out = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            // Round half up, in exact integers.
            int y = (int) ((long) s.y() + Math.floorDiv(2 * dy * i + n, 2L * n));
            out.add(new int[]{s.x() + sx * i, y, s.z() + sz * i});
        }
        return out;
    }

    /** True when {@code cells} starts at point {@code a}. */
    static boolean startsAt(List<int[]> cells, Point a) {
        int[] first = cells.getFirst();
        return first[0] == a.x() && first[1] == a.y() && first[2] == a.z();
    }

    public static Result densify(WaypointSet set, StreetRules rules, int maxStep, Limits limits) {
        Map<String, Draft> drafts = new HashMap<>();
        for (Point p : set.points()) {
            drafts.put(key(p.x(), p.y(), p.z()), new Draft(p.kind() == PointKind.ACCESS ? NavSurface.ACCESS
                    : NavSurface.SIDEWALK, p.tags(), "point " + p.name(), true));
            if (drafts.size() > limits.maxNodes()) throw capRefused(drafts.size(), -1, limits);
        }
        List<Link> crossings = new ArrayList<>();
        List<Link> walks = new ArrayList<>();
        for (Link link : set.links()) {
            Point a = set.point(link.a());
            Point b = set.point(link.b());
            if (a == null || b == null) {
                throw new EditRefused("WP_LINK_MISSING", "Link " + link.label() + " names a point that does not exist.");
            }
            List<int[]> cells = cells(a, b, limits.maxNodes());
            if (cells == null) {
                throw new EditRefused("WP_NOT_AXIS", "Link " + link.label() + " does not run straight along x or z; add a point at the bend.");
            }
            int length = cells.size() - 1;
            long dy = Math.abs((long) a.y() - b.y());
            if (dy > (long) length * maxStep) {
                throw new EditRefused("WP_TOO_STEEP", "Link " + link.label() + " climbs " + dy + " over " + length + " blocks.");
            }
            if (link.kind().crossing()) {
                if (a.kind() != PointKind.SIDEWALK || b.kind() != PointKind.SIDEWALK) {
                    throw new EditRefused("WP_CROSS_ENDS", "Crossing " + link.label() + " must start and end on sidewalk points.");
                }
                if (dy != 0) {
                    throw new EditRefused("WP_CROSS_LEVEL", "Crossing " + link.label() + " is not level.");
                }
                int interior = length - 1;
                if (interior < 1 || interior > rules.maxCrossingLength()) {
                    throw new EditRefused("WP_CROSS_LEN", "Crossing " + link.label() + " has " + interior
                            + " road blocks; it needs 1 to " + rules.maxCrossingLength() + ".");
                }
                crossings.add(link);
            } else {
                walks.add(link);
            }
        }
        // Crossings first, so a clash is always reported against the crossing's own cells.
        for (Link link : crossings) {
            List<int[]> cells = cells(set.point(link.a()), set.point(link.b()), limits.maxNodes());
            NavSurface surface = link.kind() == LinkKind.CROSSWALK ? NavSurface.CROSSING : NavSurface.ROAD;
            for (int i = 1; i < cells.size() - 1; i++) {
                int[] c = cells.get(i);
                Draft existing = drafts.get(key(c[0], c[1], c[2]));
                if (existing != null) {
                    throw new EditRefused(existing.point() ? "WP_CROSS_ON_POINT" : "WP_CROSS_JUNCTION",
                            "Crossing " + link.label() + " cell " + i + " at " + c[0] + "," + c[1] + "," + c[2]
                                    + " is already " + existing.from() + ".");
                }
                drafts.put(key(c[0], c[1], c[2]), new Draft(surface, Set.of(),
                        "link " + link.label() + ", cell " + i, false));
                if (drafts.size() > limits.maxNodes()) throw capRefused(drafts.size(), -1, limits);
            }
        }
        for (Link link : walks) {
            Point a = set.point(link.a());
            Point b = set.point(link.b());
            List<int[]> cells = cells(a, b, limits.maxNodes());
            NavSurface surface = a.kind() == PointKind.ACCESS && b.kind() == PointKind.ACCESS
                    ? NavSurface.ACCESS : NavSurface.SIDEWALK;
            for (int i = 1; i < cells.size() - 1; i++) {
                int[] c = cells.get(i);
                String at = key(c[0], c[1], c[2]);
                Draft existing = drafts.get(at);
                if (existing != null && existing.surface().isStreet()) {
                    throw new EditRefused("WP_WALK_ON_STREET", "Walk link " + link.label() + " cell " + i + " at "
                            + c[0] + "," + c[1] + "," + c[2] + " runs over " + existing.from() + ".");
                }
                if (existing == null || (!existing.point() && surface == NavSurface.SIDEWALK
                        && existing.surface() == NavSurface.ACCESS)) {
                    drafts.put(at, new Draft(surface, Set.of(), "link " + link.label() + ", cell " + i, false));
                    if (drafts.size() > limits.maxNodes()) throw capRefused(drafts.size(), -1, limits);
                }
            }
        }
        if (drafts.size() > limits.maxNodes()) {
            throw capRefused(drafts.size(), -1, limits);
        }

        // Ids follow (x, y, z), so the graph never depends on the order things were added.
        TreeMap<int[], Draft> ordered = new TreeMap<>(WaypointDensifier::compareCell);
        for (Map.Entry<String, Draft> entry : drafts.entrySet()) {
            String[] parts = entry.getKey().split(",");
            ordered.put(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])},
                    entry.getValue());
        }
        Map<String, Long> ids = new HashMap<>();
        long next = 1;
        for (int[] cell : ordered.keySet()) {
            ids.put(key(cell[0], cell[1], cell[2]), next++);
        }
        Map<Long, Integer> widths = new HashMap<>();
        TreeMap<Long, TreeMap<Long, Integer>> edges = new TreeMap<>();
        int crosswalks = 0;
        int plain = 0;
        List<long[]> chains = new ArrayList<>();
        for (Link link : crossings) {
            List<int[]> cells = cells(set.point(link.a()), set.point(link.b()), limits.maxNodes());
            long[] chain = new long[cells.size()];
            chains.add(chain);
            List<NavSurface> street = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                int[] c = cells.get(i);
                chain[i] = ids.get(key(c[0], c[1], c[2]));
                if (i > 0 && i < cells.size() - 1) {
                    street.add(ordered.get(c).surface());
                    widths.put(chain[i], cells.size() - 2);
                }
            }
            int entry = StreetCosts.entry(rules, street);
            if (rules.isCrosswalk(street)) crosswalks++;
            else plain++;
            int last = chain.length - 1;
            edge(edges, chain[0], chain[1], entry);
            edge(edges, chain[1], chain[0], NavScanLogic.CARDINAL_COST);
            for (int i = 1; i + 1 < last; i++) {
                edge(edges, chain[i], chain[i + 1], NavScanLogic.CARDINAL_COST);
                edge(edges, chain[i + 1], chain[i], NavScanLogic.CARDINAL_COST);
            }
            edge(edges, chain[last], chain[last - 1], entry);
            edge(edges, chain[last - 1], chain[last], NavScanLogic.CARDINAL_COST);
        }
        for (Link link : walks) {
            List<int[]> cells = cells(set.point(link.a()), set.point(link.b()), limits.maxNodes());
            for (int i = 0; i + 1 < cells.size(); i++) {
                long from = ids.get(key(cells.get(i)[0], cells.get(i)[1], cells.get(i)[2]));
                long to = ids.get(key(cells.get(i + 1)[0], cells.get(i + 1)[1], cells.get(i + 1)[2]));
                edge(edges, from, to, NavScanLogic.CARDINAL_COST);
                edge(edges, to, from, NavScanLogic.CARDINAL_COST);
            }
        }
        int edgeCount = edges.values().stream().mapToInt(Map::size).sum();
        if (edgeCount > limits.maxEdges()) {
            throw capRefused(drafts.size(), edgeCount, limits);
        }
        NavGraph.Builder builder = NavGraph.builder();
        Map<Long, String> provenance = new TreeMap<>();
        for (Map.Entry<int[], Draft> entry : ordered.entrySet()) {
            int[] c = entry.getKey();
            long id = ids.get(key(c[0], c[1], c[2]));
            Draft draft = entry.getValue();
            builder.addNode(new NavGraph.Node(id, c[0], c[1], c[2], draft.tags(), draft.surface(),
                    widths.getOrDefault(id, 0)));
            provenance.put(id, draft.from());
        }
        edges.forEach((from, targets) -> targets.forEach((to, cost) -> builder.addEdge(from, to, cost)));
        return new Result(builder.build(), Collections.unmodifiableMap(provenance), crosswalks, plain, chains);
    }

    /**
     * The same graph with each crossing cell set to the street class the world read found there ({@code observed};
     * a cell missing from it keeps its class), and each crossing's two curb-to-street steps costed again through
     * {@link StreetCosts}, exactly as the scanner costs a crossing over that paint. Nothing else changes: the same
     * nodes, ids, tags, widths and edge order.
     */
    public static Result restripe(Result built, Map<Long, NavSurface> observed, StreetRules rules) {
        NavGraph graph = built.graph();
        Map<Long, NavSurface> surfaces = new HashMap<>();
        Map<String, Integer> entryCosts = new HashMap<>();
        int crosswalks = 0;
        int plain = 0;
        for (long[] chain : built.crossings()) {
            List<NavSurface> street = new ArrayList<>();
            for (int i = 1; i < chain.length - 1; i++) {
                NavSurface stored = graph.node(chain[i]).surface();
                NavSurface seen = observed.get(chain[i]);
                NavSurface surface = seen != null && seen.isStreet() ? seen : stored;
                surfaces.put(chain[i], surface);
                street.add(surface);
            }
            int entry = StreetCosts.entry(rules, street);
            if (rules.isCrosswalk(street)) crosswalks++;
            else plain++;
            int last = chain.length - 1;
            entryCosts.put(chain[0] + ">" + chain[1], entry);
            entryCosts.put(chain[last] + ">" + chain[last - 1], entry);
        }
        NavGraph.Builder builder = NavGraph.builder();
        graph.nodes().stream().sorted(java.util.Comparator.comparingLong(NavGraph.Node::id)).forEach(node ->
                builder.addNode(new NavGraph.Node(node.id(), node.x(), node.y(), node.z(), node.tags(),
                        surfaces.getOrDefault(node.id(), node.surface()), node.roadWidth())));
        for (NavGraph.Edge edge : graph.edges()) {
            builder.addEdge(edge.from(), edge.to(), entryCosts.getOrDefault(edge.from() + ">" + edge.to(), edge.cost()));
        }
        return new Result(builder.build(), built.provenance(), crosswalks, plain, built.crossings());
    }

    private static EditRefused capRefused(int nodes, int edges, Limits limits) {
        return new EditRefused("WP_CAP_NODES", "The set makes " + nodes + " nodes"
                + (edges >= 0 ? " and " + edges + " edges" : "") + "; the limit is " + limits.maxNodes() + " nodes and "
                + limits.maxEdges() + " edges. Split this district into two regions.");
    }

    /** Keeps the cheaper cost when two links lay the same step. */
    private static void edge(TreeMap<Long, TreeMap<Long, Integer>> edges, long from, long to, int cost) {
        edges.computeIfAbsent(from, k -> new TreeMap<>()).merge(to, cost, Math::min);
    }

    static int compareCell(int[] a, int[] b) {
        int c = Integer.compare(a[0], b[0]);
        if (c != 0) return c;
        c = Integer.compare(a[1], b[1]);
        return c != 0 ? c : Integer.compare(a[2], b[2]);
    }

    static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
