package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The dense route file ({@code format: prosperity-nav-graph/1}): a list of nodes and the steps between them,
 * so any tool can draw a district's paths. Strict: unknown keys and surfaces are refused, and every error names
 * the node id or edge index it is about. Costs on road and curb steps are always worked out here, through
 * {@link StreetCosts}, never taken from the file; a file may only make an ordinary step slower, never cheaper.
 */
public final class FileGraphCodec {

    public static final String FORMAT = "prosperity-nav-graph/1";
    public static final int MAX_TAGS = 8;
    private static final int MAX_WIDTH = 255;
    private static final Set<String> TOP = Set.of("format", "default-surface", "nodes", "edges");
    private static final Set<String> NODE_KEYS = Set.of("id", "at", "surface", "tags", "width");
    private static final Set<String> EDGE_KEYS = Set.of("from", "to", "cost");

    /** The most nodes and directed edges a file may describe; by default the runtime search limits. */
    public record Limits(int maxNodes, int maxEdges) {
        public static final Limits DEFAULT = new Limits(Dijkstra.MAX_VISITED, Dijkstra.MAX_EDGES_EXAMINED);

        public Limits {
            if (maxNodes > Dijkstra.MAX_GRAPH_NODES || maxEdges > Dijkstra.MAX_GRAPH_EDGES) {
                throw new IllegalArgumentException("route file limits above the graph limits");
            }
        }
    }

    /** Why a file was refused, naming the node id or edge index; never quoting the file. */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String message) {
            super(message);
        }
    }

    private FileGraphCodec() {
    }

    /** Parses a dense route file into a graph whose node ids are the file's ids. */
    public static NavGraph read(String text, StreetRules rules, int maxStep, Limits limits) {
        Object document;
        try {
            document = StrictYaml.load(text);
        } catch (StrictYaml.Refused refused) {
            throw new Refused(refused.getMessage());
        }
        Map<?, ?> top = map(document, "the file", TOP);
        if (!FORMAT.equals(top.get("format"))) throw new Refused("The file must start with format: " + FORMAT);
        NavSurface fallback = null;
        if (top.containsKey("default-surface")) {
            fallback = surface(top.get("default-surface"), "default-surface");
            if (fallback.isStreet()) throw new Refused("default-surface is sidewalk or access, never road or crossing.");
        }
        List<?> rawNodes = list(top.get("nodes"), "nodes");
        List<?> rawEdges = list(top.get("edges"), "edges");
        if (rawNodes.isEmpty()) throw new Refused("The file has no nodes.");
        if (rawNodes.size() > limits.maxNodes()) {
            throw new Refused("The file has " + rawNodes.size() + " nodes; the limit is " + limits.maxNodes() + ".");
        }
        Map<Long, NavGraph.Node> nodes = new TreeMap<>();
        Map<Long, Integer> widths = new HashMap<>();
        Set<String> cells = new HashSet<>();
        int index = 0;
        for (Object raw : rawNodes) {
            String where = "node " + index++;
            Map<?, ?> entry = map(raw, where, NODE_KEYS);
            long id = number(entry.get("id"), where + " id");
            if (id <= 0) throw new Refused(where + ": id must be a positive whole number.");
            where = "node " + id;
            if (nodes.containsKey(id)) throw new Refused(where + " appears twice.");
            List<?> at = list(entry.get("at"), where + " at");
            if (at.size() != 3) throw new Refused(where + ": at must be [x, y, z].");
            int x = (int) coordinate(at.get(0), where);
            int y = (int) coordinate(at.get(1), where);
            int z = (int) coordinate(at.get(2), where);
            if (!cells.add(x + "," + y + "," + z)) throw new Refused(where + " stands on the same block as another node.");
            NavSurface surface;
            if (entry.containsKey("surface")) {
                surface = surface(entry.get("surface"), where + " surface");
            } else if (fallback != null) {
                surface = fallback;
            } else {
                // Never UNKNOWN: an unclassified node would slip past the street audit.
                throw new Refused(where + " has no surface and the file has no default-surface.");
            }
            Set<String> tags = new TreeSet<>();
            if (entry.containsKey("tags")) {
                for (Object tag : list(entry.get("tags"), where + " tags")) {
                    if (!(tag instanceof String value) || !NavGraphRepository.isValidDraftTag(value)) {
                        throw new Refused(where + " has a tag that is not a route tag.");
                    }
                    tags.add(value);
                }
            }
            if (tags.size() > MAX_TAGS) throw new Refused(where + " has more than " + MAX_TAGS + " tags.");
            if (surface.isStreet() && !tags.isEmpty()) throw new Refused(where + " is on a road and cannot be a stop.");
            if (entry.containsKey("width")) {
                long width = number(entry.get("width"), where + " width");
                if (!surface.isStreet() || width < 1 || width > MAX_WIDTH) {
                    throw new Refused(where + ": width is only for road and crossing nodes, 1 to " + MAX_WIDTH + ".");
                }
                widths.put(id, (int) width);
            }
            nodes.put(id, new NavGraph.Node(id, x, y, z, tags, surface, 0));
        }

        record Step(long from, long to, Long cost, String where) { }
        List<Step> steps = new ArrayList<>();
        index = 0;
        for (Object raw : rawEdges) {
            String where = "edge " + index++;
            if (raw instanceof List<?> pair) {
                if (pair.size() != 2) throw new Refused(where + ": a pair is [from, to].");
                long a = number(pair.get(0), where);
                long b = number(pair.get(1), where);
                steps.add(new Step(a, b, null, where));
                steps.add(new Step(b, a, null, where));
            } else {
                Map<?, ?> entry = map(raw, where, EDGE_KEYS);
                Long cost = entry.containsKey("cost") ? number(entry.get("cost"), where + " cost") : null;
                steps.add(new Step(number(entry.get("from"), where + " from"), number(entry.get("to"), where + " to"), cost, where));
            }
        }
        if (steps.size() > limits.maxEdges()) {
            throw new Refused("The file has " + steps.size() + " directed edges; the limit is " + limits.maxEdges() + ".");
        }
        Set<String> seen = new HashSet<>();
        for (Step step : steps) {
            NavGraph.Node from = nodes.get(step.from());
            NavGraph.Node to = nodes.get(step.to());
            if (from == null || to == null) {
                throw new Refused(step.where() + " names node " + (from == null ? step.from() : step.to()) + ", which does not exist.");
            }
            if (!seen.add(step.from() + ">" + step.to())) {
                throw new Refused(step.where() + " repeats the step " + step.from() + " to " + step.to() + ".");
            }
            if (!adjacent(from, to, maxStep)) {
                throw new Refused(step.where() + ": nodes " + step.from() + " and " + step.to() + " are not next to each other.");
            }
            boolean street = from.surface().isStreet() || to.surface().isStreet();
            if (street && Math.abs((long) from.x() - to.x()) + Math.abs((long) from.z() - to.z()) != 1) {
                throw new Refused(step.where() + " touches a road but is not a straight step.");
            }
            if (step.cost() != null) {
                int base = stepCost(from, to);
                if (street) {
                    throw new Refused(step.where() + " sets a cost on a road step; road costs are worked out by the plugin.");
                }
                if (step.cost() < base || step.cost() > 10 * base) {
                    throw new Refused(step.where() + ": cost must be " + base + " to " + 10 * base + ".");
                }
            }
        }

        // Street costs depend on the whole crossing, so the crossings are found first on a provisional graph.
        NavGraph.Builder provisional = NavGraph.builder();
        nodes.values().forEach(provisional::addNode);
        for (Step step : steps) provisional.addEdge(step.from(), step.to(), 1);
        NavGraph shape = provisional.build();
        Map<Long, Integer> entryCost = new HashMap<>();
        Map<Long, Integer> chainLength = new HashMap<>();
        for (StreetChains.Chain chain : StreetChains.find(shape, id -> nodes.get(id).surface())) {
            int entry = StreetCosts.entry(rules, chain.cells().stream().map(NavGraph.Node::surface).toList());
            for (NavGraph.Node cell : chain.cells()) {
                entryCost.put(cell.id(), entry);
                chainLength.put(cell.id(), chain.length());
            }
        }
        NavGraph.Builder builder = NavGraph.builder();
        for (NavGraph.Node node : nodes.values()) {
            // A road node with no width from the file records its own crossing's length: the file's author
            // vouches the road is at least that wide. A live audit measures the real width.
            int width = node.surface().isStreet() ? widths.getOrDefault(node.id(), chainLength.getOrDefault(node.id(), 0)) : 0;
            builder.addNode(new NavGraph.Node(node.id(), node.x(), node.y(), node.z(), node.tags(), node.surface(), width));
        }
        steps.sort(Comparator.comparingLong(Step::from).thenComparingLong(Step::to));
        for (Step step : steps) {
            NavGraph.Node from = nodes.get(step.from());
            NavGraph.Node to = nodes.get(step.to());
            int cost;
            if (!from.surface().isStreet() && to.surface().isStreet()) {
                cost = entryCost.get(to.id());
            } else if (from.surface().isStreet() || to.surface().isStreet()) {
                cost = NavScanLogic.CARDINAL_COST;
            } else {
                cost = step.cost() != null ? step.cost().intValue() : stepCost(from, to);
            }
            builder.addEdge(step.from(), step.to(), cost);
        }
        return builder.build();
    }

    /** True when two nodes are one block apart sideways (diagonal included) and at most {@code maxStep} in height. */
    static boolean adjacent(NavGraph.Node a, NavGraph.Node b, int maxStep) {
        long dx = Math.abs((long) a.x() - b.x());
        long dz = Math.abs((long) a.z() - b.z());
        return dx <= 1 && dz <= 1 && dx + dz > 0 && Math.abs((long) a.y() - b.y()) <= maxStep;
    }

    /** The scanner's cost for an ordinary step: 10 straight, 14 diagonal. */
    static int stepCost(NavGraph.Node a, NavGraph.Node b) {
        return a.x() != b.x() && a.z() != b.z() ? NavScanLogic.DIAGONAL_COST : NavScanLogic.CARDINAL_COST;
    }

    /**
     * Writes a graph as a dense route file. Nodes are renumbered 1.. in (x, y, z) order. A pair of steps whose
     * costs are the ones a file would get anyway is written as {@code [a, b]}; anything else as one
     * {@code {from, to}} per direction, with the cost only where an ordinary step differs from its default.
     */
    public static String write(NavGraph graph, StreetRules rules) {
        List<NavGraph.Node> ordered = graph.nodes().stream().sorted(Comparator.comparingInt(NavGraph.Node::x)
                .thenComparingInt(NavGraph.Node::y).thenComparingInt(NavGraph.Node::z).thenComparingLong(NavGraph.Node::id)).toList();
        Map<Long, Long> ids = new HashMap<>();
        StringBuilder out = new StringBuilder("format: ").append(FORMAT).append('\n').append("nodes:\n");
        for (NavGraph.Node node : ordered) {
            if (node.surface() == NavSurface.UNKNOWN) {
                throw new Refused("Node " + node.id() + " has no surface class; only classified graphs can be exported.");
            }
            long id = ids.size() + 1L;
            ids.put(node.id(), id);
            out.append("  - {id: ").append(id).append(", at: [").append(node.x()).append(", ").append(node.y())
                    .append(", ").append(node.z()).append("], surface: ").append(node.surface().name().toLowerCase(Locale.ROOT));
            if (!node.tags().isEmpty()) {
                out.append(", tags: [");
                boolean first = true;
                for (String tag : new TreeSet<>(node.tags())) {
                    out.append(first ? "" : ", ").append('"').append(tag).append('"');
                    first = false;
                }
                out.append(']');
            }
            if (node.surface().isStreet() && node.roadWidth() > 0) out.append(", width: ").append(node.roadWidth());
            out.append("}\n");
        }
        Map<Long, Integer> entryCost = new HashMap<>();
        for (StreetChains.Chain chain : StreetChains.find(graph, id -> graph.node(id).surface())) {
            int entry = StreetCosts.entry(rules, chain.cells().stream().map(NavGraph.Node::surface).toList());
            for (NavGraph.Node cell : chain.cells()) entryCost.put(cell.id(), entry);
        }
        Map<String, Integer> costs = new HashMap<>();
        for (NavGraph.Edge edge : graph.edges()) {
            if (graph.hasNode(edge.from()) && graph.hasNode(edge.to())) {
                costs.put(ids.get(edge.from()) + ">" + ids.get(edge.to()), edge.cost());
            }
        }
        Map<Long, NavGraph.Node> byNewId = new HashMap<>();
        ids.forEach((old, fresh) -> byNewId.put(fresh, graph.node(old)));
        List<String> lines = new ArrayList<>();
        Set<String> done = new HashSet<>();
        List<long[]> pairs = new ArrayList<>();
        for (String key : costs.keySet()) {
            String[] parts = key.split(">");
            long a = Long.parseLong(parts[0]);
            long b = Long.parseLong(parts[1]);
            long low = Math.min(a, b);
            long high = Math.max(a, b);
            if (done.add(low + ">" + high)) pairs.add(new long[]{low, high});
        }
        pairs.sort(Comparator.<long[]>comparingLong(p -> p[0]).thenComparingLong(p -> p[1]));
        for (long[] pair : pairs) {
            Integer forward = costs.get(pair[0] + ">" + pair[1]);
            Integer back = costs.get(pair[1] + ">" + pair[0]);
            NavGraph.Node a = byNewId.get(pair[0]);
            NavGraph.Node b = byNewId.get(pair[1]);
            if (forward != null && back != null && forward == computed(a, b, entryCost) && back == computed(b, a, entryCost)) {
                lines.add("  - [" + pair[0] + ", " + pair[1] + "]");
                continue;
            }
            if (forward != null) lines.add(directed(pair[0], pair[1], a, b, forward, entryCost));
            if (back != null) lines.add(directed(pair[1], pair[0], b, a, back, entryCost));
        }
        out.append(lines.isEmpty() ? "edges: []\n" : "edges:\n");
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    private static String directed(long fromId, long toId, NavGraph.Node from, NavGraph.Node to, int cost,
                                   Map<Long, Integer> entryCost) {
        boolean street = from.surface().isStreet() || to.surface().isStreet();
        String line = "  - {from: " + fromId + ", to: " + toId;
        if (!street && cost != computed(from, to, entryCost)) line += ", cost: " + cost;
        return line + "}";
    }

    private static int computed(NavGraph.Node from, NavGraph.Node to, Map<Long, Integer> entryCost) {
        if (!from.surface().isStreet() && to.surface().isStreet()) return entryCost.getOrDefault(to.id(), -1);
        if (from.surface().isStreet() || to.surface().isStreet()) return NavScanLogic.CARDINAL_COST;
        return stepCost(from, to);
    }

    private static Map<?, ?> map(Object raw, String where, Set<String> allowed) {
        if (!(raw instanceof Map<?, ?> map)) throw new Refused(where + " must be a map.");
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !allowed.contains(text)) throw new Refused(where + " has an unknown key.");
        }
        return map;
    }

    private static List<?> list(Object raw, String where) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new Refused(where + " must be a list.");
        return list;
    }

    private static long number(Object raw, String where) {
        if (raw instanceof Integer value) return value;
        if (raw instanceof Long value) return value;
        throw new Refused(where + " must be a whole number.");
    }

    private static long coordinate(Object raw, String where) {
        if (raw instanceof Integer value) return value;
        throw new Refused(where + ": coordinates must be whole numbers.");
    }

    private static NavSurface surface(Object raw, String where) {
        if (raw instanceof String text) {
            switch (text) {
                case "sidewalk": return NavSurface.SIDEWALK;
                case "access": return NavSurface.ACCESS;
                case "road": return NavSurface.ROAD;
                case "crossing": return NavSurface.CROSSING;
                default: break;
            }
        }
        throw new Refused(where + " is sidewalk, access, road or crossing.");
    }
}
