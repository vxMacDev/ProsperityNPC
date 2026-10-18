package com.prosperitysmp.npc.nav;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Routes on random graphs checked against a plain shortest-distance computation and its smallest-id tie rule. */
class DijkstraPropertyTest {

    private record RawEdge(long from, long to, int cost) { }

    private record Spec(List<Long> ids, List<RawEdge> edges) { }

    private static Spec spec(long seed) {
        Random random = new Random(seed);
        int n = 5 + random.nextInt(26);
        Set<Long> chosen = new TreeSet<>();
        while (chosen.size() < n) chosen.add(1L + random.nextInt(120));
        List<Long> ids = new ArrayList<>(chosen);
        List<RawEdge> edges = new ArrayList<>();
        int count = n * 3;
        for (int i = 0; i < count; i++) {
            long from = ids.get(random.nextInt(n));
            long to = random.nextInt(12) == 0 ? 200 + random.nextInt(20) : ids.get(random.nextInt(n));
            int roll = random.nextInt(20);
            int cost = roll == 0 ? 0 : roll == 1 ? -1 - random.nextInt(5) : 1 + random.nextInt(8);
            edges.add(new RawEdge(from, to, cost));
        }
        return new Spec(ids, edges);
    }

    private static NavGraph build(Spec spec, Random shuffle) {
        List<Long> ids = new ArrayList<>(spec.ids());
        List<RawEdge> edges = new ArrayList<>(spec.edges());
        if (shuffle != null) {
            Collections.shuffle(ids, shuffle);
            Collections.shuffle(edges, shuffle);
        }
        NavGraph.Builder builder = NavGraph.builder();
        for (long id : ids) builder.addNode(id, (int) id, 64, 0);
        for (RawEdge e : edges) builder.addEdge(e.from(), e.to(), e.cost());
        return builder.build();
    }

    /** Distances from the start over positive edges between existing nodes, by repeated relaxation. */
    private static Map<Long, Long> distances(Spec spec, long start) {
        Set<Long> exists = new HashSet<>(spec.ids());
        Map<Long, Long> dist = new HashMap<>();
        dist.put(start, 0L);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (RawEdge e : spec.edges()) {
                if (e.cost() <= 0 || !exists.contains(e.from()) || !exists.contains(e.to()) || !dist.containsKey(e.from())) continue;
                long candidate = dist.get(e.from()) + e.cost();
                if (!dist.containsKey(e.to()) || candidate < dist.get(e.to())) {
                    dist.put(e.to(), candidate);
                    changed = true;
                }
            }
        }
        return dist;
    }

    private static long cheapest(Spec spec, long from, long to) {
        long best = Long.MAX_VALUE;
        for (RawEdge e : spec.edges()) {
            if (e.from() == from && e.to() == to && e.cost() > 0) best = Math.min(best, e.cost());
        }
        return best;
    }

    /** The route that takes the smallest-id optimal predecessor at every step back from the goal. */
    private static List<Long> canonical(Spec spec, Map<Long, Long> dist, long start, long goal) {
        LinkedList<Long> path = new LinkedList<>();
        long at = goal;
        path.addFirst(at);
        while (at != start) {
            long best = Long.MAX_VALUE;
            for (long u : spec.ids()) {
                long w = cheapest(spec, u, at);
                if (w != Long.MAX_VALUE && dist.containsKey(u) && dist.get(u) + w == dist.get(at)) best = Math.min(best, u);
            }
            at = best;
            path.addFirst(at);
        }
        return path;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40})
    void findGivesTheShortestCostAndTheSmallestIdRouteForEveryPairOfNodes(long seed) {
        Spec spec = spec(seed);
        NavGraph graph = build(spec, null);
        for (long start : spec.ids()) {
            Map<Long, Long> dist = distances(spec, start);
            for (long goal : spec.ids()) {
                Dijkstra.Path path = Dijkstra.find(graph, start, goal);
                if (!dist.containsKey(goal)) {
                    assertFalse(path.found(), start + " to " + goal);
                    assertEquals(-1L, path.cost());
                    continue;
                }
                assertEquals(dist.get(goal), path.cost(), start + " to " + goal);
                assertEquals(canonical(spec, dist, start, goal), path.nodes(), start + " to " + goal);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60})
    void findNearestPicksTheClosestTargetThenTheSmallestIdAndTheSameRoute(long seed) {
        Spec spec = spec(seed);
        NavGraph graph = build(spec, null);
        Random random = new Random(seed * 31);
        for (long start : spec.ids()) {
            Set<Long> targets = new HashSet<>();
            for (long id : spec.ids()) if (random.nextInt(5) == 0) targets.add(id);
            Map<Long, Long> dist = distances(spec, start);
            long bestId = -1;
            long bestDist = Long.MAX_VALUE;
            for (long t : new TreeSet<>(targets)) {
                if (dist.containsKey(t) && dist.get(t) < bestDist) {
                    bestDist = dist.get(t);
                    bestId = t;
                }
            }
            Dijkstra.BoundedPath result = Dijkstra.findNearest(graph, start, targets::contains, edge -> true);
            if (bestId < 0) {
                assertEquals(Dijkstra.SearchStatus.NO_ROUTE, result.status(), "from " + start + " to " + targets);
                continue;
            }
            assertEquals(Dijkstra.SearchStatus.FOUND, result.status());
            assertEquals(bestDist, result.cost(), "from " + start + " to " + targets);
            assertEquals(canonical(spec, dist, start, bestId), result.nodes());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80})
    void theRouteDoesNotDependOnTheOrderNodesAndEdgesWereAdded(long seed) {
        Spec spec = spec(seed);
        NavGraph plain = build(spec, null);
        NavGraph shuffled = build(spec, new Random(seed + 1000));
        for (long start : spec.ids()) {
            for (long goal : spec.ids()) {
                assertEquals(Dijkstra.find(plain, start, goal), Dijkstra.find(shuffled, start, goal), start + " to " + goal);
            }
        }
    }
}
