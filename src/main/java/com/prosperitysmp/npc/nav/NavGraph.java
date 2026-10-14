package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable navigation graph for a district: waypoint nodes with a world
 * position and semantic tags, plus directed, positively-weighted edges. The
 * scanner derives a graph from block material, so nothing is pinned to hardcoded
 * coordinates and it survives map changes. The graph is then validated and
 * published. Pathfinding ({@link Dijkstra}) and validation ({@link NavGraphValidator})
 * operate purely on this structure with no world or database access.
 */
public final class NavGraph {

    /** A walkable waypoint. {@code tags} are explicit, reviewable semantics (spawn, crosswalk, …). */
    public record Node(long id, int x, int y, int z, Set<String> tags) {
        public Node {
            tags = tags == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(tags));
        }

        public boolean hasTag(String tag) {
            return tags.contains(tag);
        }
    }

    /** A directed walk link with a positive traversal cost. */
    public record Edge(long from, long to, int cost) {
    }

    private final Map<Long, Node> nodes;
    private final List<Edge> edges;
    private final Map<Long, List<Edge>> adjacency;
    private final Map<Long, Set<Long>> positiveTargets;

    private NavGraph(Map<Long, Node> nodes, List<Edge> edges) {
        this.nodes = Map.copyOf(nodes);
        this.edges = List.copyOf(edges);
        Map<Long, List<Edge>> adj = new HashMap<>();
        Map<Long, Set<Long>> targets = new HashMap<>();
        for (Edge e : this.edges) {
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            if (e.cost() > 0 && this.nodes.containsKey(e.from()) && this.nodes.containsKey(e.to())) {
                targets.computeIfAbsent(e.from(), k -> new HashSet<>()).add(e.to());
            }
        }
        adj.replaceAll((id, neighbors) -> List.copyOf(neighbors));
        this.adjacency = Map.copyOf(adj);
        targets.replaceAll((id, values) -> Set.copyOf(values));
        this.positiveTargets = Map.copyOf(targets);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public boolean hasNode(long id) {
        return nodes.containsKey(id);
    }

    public Node node(long id) {
        return nodes.get(id);
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    public List<Edge> edges() {
        return edges;
    }

    /** Out-edges from a node (empty if none / unknown node). */
    public List<Edge> neighbors(long id) {
        return adjacency.getOrDefault(id, List.of());
    }

    /** Expected constant-time directed membership for per-tick traversal checks. */
    public boolean hasPositiveEdge(long from, long to) {
        return positiveTargets.getOrDefault(from, Set.of()).contains(to);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates nodes and edges (the scanner's build surface), then freezes into a graph. */
    public static final class Builder {
        private final Map<Long, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();

        public Builder addNode(long id, int x, int y, int z, String... tags) {
            nodes.put(id, new Node(id, x, y, z, new HashSet<>(Arrays.asList(tags))));
            return this;
        }

        public Builder addNode(Node node) {
            nodes.put(node.id(), node);
            return this;
        }

        public Builder addEdge(long from, long to, int cost) {
            edges.add(new Edge(from, to, cost));
            return this;
        }

        /** Adds both directions of a walkable link. */
        public Builder addBiEdge(long a, long b, int cost) {
            edges.add(new Edge(a, b, cost));
            edges.add(new Edge(b, a, cost));
            return this;
        }

        public NavGraph build() {
            return new NavGraph(nodes, edges);
        }
    }
}
