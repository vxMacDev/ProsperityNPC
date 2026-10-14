package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * Shortest-path over a {@link NavGraph}. Deterministic: the frontier and the
 * equal-distance tie-breaks both order by node id, so a restart replays the
 * identical route rather than reshuffling avatars. Non-positive-cost or dangling
 * edges are skipped defensively (the validator reports them as errors before publish).
 */
public final class Dijkstra {

    public static final int MAX_VISITED = 1024;
    public static final int MAX_EDGES_EXAMINED = 8192;
    public static final int MAX_GRAPH_NODES = 20_000;
    public static final int MAX_GRAPH_EDGES = 160_000;

    public enum SearchStatus { FOUND, NO_ROUTE, BUDGET_EXHAUSTED }

    /** Counts include settled targets and every inspected edge, including rejected edges. */
    public record BoundedPath(SearchStatus status, List<Long> nodes, long cost,
                              int visited, int edgesExamined) {
        public BoundedPath {
            nodes = List.copyOf(nodes);
        }
    }

    /** An ordered node path and its total cost; {@link #none()} when unreachable. */
    public record Path(List<Long> nodes, long cost) {
        public Path {
            nodes = List.copyOf(nodes);
        }

        public boolean found() {
            return !nodes.isEmpty();
        }

        public static Path none() {
            return new Path(List.of(), -1L);
        }
    }

    private Dijkstra() {
    }

    /**
     * Nearest matching target by actual cost, then target id. Predicates must be
     * pure and constant-time; no target collection or neighbor sorting is needed.
     * Only positive, existing, directed edges reach the traversal predicate.
     * Limits are inclusive; incomplete searches return no partial route. Oversize
     * inputs fail before either predicate runs, even when the start is a target.
     * Unlike {@link #find}, this method is bounded and accepts a geometry filter.
     */
    public static BoundedPath findNearest(NavGraph graph, long start, LongPredicate target,
                                          Predicate<NavGraph.Edge> traversable) {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(target);
        Objects.requireNonNull(traversable);
        if (graph.nodeCount() > MAX_GRAPH_NODES || graph.edgeCount() > MAX_GRAPH_EDGES) {
            return unfinished(SearchStatus.BUDGET_EXHAUSTED, 0, 0);
        }
        if (!graph.hasNode(start)) {
            return unfinished(SearchStatus.NO_ROUTE, 0, 0);
        }

        Map<Long, Long> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        Set<Long> settled = new HashSet<>();
        PriorityQueue<long[]> frontier = new PriorityQueue<>((a, b) ->
                a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
        dist.put(start, 0L);
        frontier.add(new long[]{start, 0L});
        int edgesExamined = 0;

        while (!frontier.isEmpty()) {
            long[] top = frontier.poll();
            long u = top[0];
            long distance = top[1];
            if (settled.contains(u)) continue;
            if (settled.size() == MAX_VISITED) {
                return unfinished(SearchStatus.BUDGET_EXHAUSTED, settled.size(), edgesExamined);
            }
            settled.add(u);
            // Positive costs ensure all equal-cost targets and predecessors are
            // already discovered before the winning target is settled.
            if (target.test(u)) {
                List<Long> path = new ArrayList<>();
                for (Long at = u; at != null; at = prev.get(at)) path.add(at);
                Collections.reverse(path);
                return new BoundedPath(SearchStatus.FOUND, path, distance, settled.size(), edgesExamined);
            }
            for (NavGraph.Edge edge : graph.neighbors(u)) {
                if (edgesExamined == MAX_EDGES_EXAMINED) {
                    return unfinished(SearchStatus.BUDGET_EXHAUSTED, settled.size(), edgesExamined);
                }
                edgesExamined++;
                if (edge.from() != u || edge.cost() <= 0 || !graph.hasNode(edge.to())
                        || settled.contains(edge.to()) || !traversable.test(edge)) continue;
                long next = distance + edge.cost();
                Long current = dist.get(edge.to());
                if (current == null || next < current) {
                    dist.put(edge.to(), next);
                    prev.put(edge.to(), u);
                    frontier.add(new long[]{edge.to(), next});
                } else if (next == current) {
                    Long predecessor = prev.get(edge.to());
                    if (predecessor == null || u < predecessor) prev.put(edge.to(), u);
                }
            }
        }
        return unfinished(SearchStatus.NO_ROUTE, settled.size(), edgesExamined);
    }

    private static BoundedPath unfinished(SearchStatus status, int visited, int edgesExamined) {
        return new BoundedPath(status, List.of(), -1L, visited, edgesExamined);
    }

    public static Path find(NavGraph graph, long start, long goal) {
        if (!graph.hasNode(start) || !graph.hasNode(goal)) {
            return Path.none();
        }
        if (start == goal) {
            return new Path(List.of(start), 0L);
        }

        Map<Long, Long> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        Set<Long> settled = new HashSet<>();
        // Frontier ordered by (distance, node id) so ties resolve deterministically.
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) ->
                a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
        dist.put(start, 0L);
        pq.add(new long[]{start, 0L});

        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            long u = top[0];
            long d = top[1];
            if (!settled.add(u)) {
                continue; // stale queue entry
            }
            if (u == goal) {
                break;
            }
            for (NavGraph.Edge e : graph.neighbors(u)) {
                if (e.cost() <= 0 || !graph.hasNode(e.to()) || settled.contains(e.to())) {
                    continue;
                }
                long nd = d + e.cost();
                Long cur = dist.get(e.to());
                if (cur == null || nd < cur) {
                    dist.put(e.to(), nd);
                    prev.put(e.to(), u);
                    pq.add(new long[]{e.to(), nd});
                } else if (nd == cur) {
                    // Equal distance: prefer the smaller predecessor for a stable route.
                    Long p = prev.get(e.to());
                    if (p == null || u < p) {
                        prev.put(e.to(), u);
                    }
                }
            }
        }

        if (!dist.containsKey(goal)) {
            return Path.none();
        }
        List<Long> path = new ArrayList<>();
        for (Long at = goal; at != null; at = prev.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return new Path(path, dist.get(goal));
    }
}
