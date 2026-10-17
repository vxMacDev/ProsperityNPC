package com.prosperitysmp.npc.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * The crossings of a built graph: each maximal group of road/crossing nodes joined to one another is
 * one straight crossing (a "chain"). Pure; shared by the audit and by the sweep that plans world probes.
 */
final class StreetChains {

    /**
     * @param cells the street nodes, ordered by position
     * @param mid the id of the middle cell by that order
     * @param curbs ids of the non-street nodes the chain steps onto and off
     * @param alongX true when the chain runs along the x axis (so its width is measured along z)
     */
    record Chain(List<NavGraph.Node> cells, long mid, List<Long> curbs, boolean alongX) {
        int length() {
            return cells.size();
        }
    }

    private StreetChains() {
    }

    static List<Chain> find(NavGraph graph, LongFunction<NavSurface> surface) {
        Map<Long, List<Long>> links = new HashMap<>();
        Map<Long, Set<Long>> curbsOf = new HashMap<>();
        for (NavGraph.Edge e : graph.edges()) {
            if (!graph.hasNode(e.from()) || !graph.hasNode(e.to())) {
                continue;
            }
            boolean fromStreet = surface.apply(e.from()).isStreet();
            boolean toStreet = surface.apply(e.to()).isStreet();
            if (fromStreet && toStreet) {
                links.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
                links.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
            } else if (fromStreet) {
                curbsOf.computeIfAbsent(e.from(), k -> new HashSet<>()).add(e.to());
            } else if (toStreet) {
                curbsOf.computeIfAbsent(e.to(), k -> new HashSet<>()).add(e.from());
            }
        }
        List<NavGraph.Node> streetNodes = graph.nodes().stream().filter(n -> surface.apply(n.id()).isStreet())
                .sorted(Comparator.comparingLong(NavGraph.Node::id)).toList();
        Set<Long> seen = new HashSet<>();
        List<Chain> chains = new ArrayList<>();
        for (NavGraph.Node start : streetNodes) {
            if (!seen.add(start.id())) {
                continue;
            }
            List<NavGraph.Node> cells = new ArrayList<>();
            Set<Long> curbs = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start.id()));
            while (!queue.isEmpty()) {
                long id = queue.poll();
                cells.add(graph.node(id));
                curbs.addAll(curbsOf.getOrDefault(id, Set.of()));
                for (long next : links.getOrDefault(id, List.of())) {
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            cells.sort(Comparator.comparingInt(NavGraph.Node::x).thenComparingInt(NavGraph.Node::z)
                    .thenComparingLong(NavGraph.Node::id));
            boolean alongX;
            if (cells.size() > 1) {
                alongX = cells.get(0).z() == cells.get(1).z();
            } else {
                NavGraph.Node only = cells.getFirst();
                alongX = curbs.stream().map(graph::node).anyMatch(c -> c.x() != only.x());
            }
            chains.add(new Chain(List.copyOf(cells), cells.get(cells.size() / 2).id(),
                    curbs.stream().sorted().toList(), alongX));
        }
        return chains;
    }
}
