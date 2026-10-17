package com.prosperitysmp.npc.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation for a draft navigation graph. A scan is only a starting
 * point, so a draft must pass validation before an admin may publish it. This
 * checks graph <em>integrity</em> (no dangling edges, self-loops, or non-positive
 * costs), <em>reachability</em> (orphans, disconnected districts), and required
 * semantic tags, and the street rules (no tag on a road, only straight steps on one, no turn on one).
 * It does not check per-block walkability, which the scanner enforces while building nodes:
 * {@code /npcnav revalidate} compares a published graph with the world, and
 * {@code /npcnav audit} proves the routes obey the sidewalk rules.
 */
public final class NavGraphValidator {

    public enum Severity {
        ERROR, WARN
    }

    public record Issue(Severity severity, String code, String message) {
    }

    public record Report(List<Issue> issues) {
        /** A graph is publishable only when it has no ERROR issues. */
        public boolean valid() {
            return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
        }

        public long errors() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
        }

        public long warnings() {
            return issues.stream().filter(i -> i.severity() == Severity.WARN).count();
        }
    }

    private NavGraphValidator() {
    }

    public static Report validate(NavGraph graph) {
        List<Issue> issues = new ArrayList<>();
        if (graph.nodeCount() == 0) {
            issues.add(new Issue(Severity.ERROR, "EMPTY", "graph has no nodes"));
            return new Report(issues);
        }

        Set<Long> touched = new HashSet<>();
        for (NavGraph.Edge e : graph.edges()) {
            if (!graph.hasNode(e.from())) {
                issues.add(new Issue(Severity.ERROR, "DANGLING_FROM", "edge references missing from-node " + e.from()));
            }
            if (!graph.hasNode(e.to())) {
                issues.add(new Issue(Severity.ERROR, "DANGLING_TO", "edge references missing to-node " + e.to()));
            }
            if (e.from() == e.to()) {
                issues.add(new Issue(Severity.ERROR, "SELF_LOOP", "self-loop edge at node " + e.from()));
            }
            if (e.cost() <= 0) {
                issues.add(new Issue(Severity.ERROR, "BAD_COST",
                        "edge " + e.from() + "->" + e.to() + " has non-positive cost " + e.cost()));
            }
            touched.add(e.from());
            touched.add(e.to());
        }

        int unknown = 0;
        for (NavGraph.Node n : graph.nodes()) {
            if (!touched.contains(n.id())) {
                issues.add(new Issue(Severity.WARN, "ORPHAN", "node " + n.id() + " has no edges (unreachable)"));
            }
            if (n.surface() == NavSurface.UNKNOWN) {
                unknown++;
            }
            if (n.surface().isStreet() && !n.tags().isEmpty()) {
                issues.add(new Issue(Severity.ERROR, "STREET_TAG",
                        "node " + n.id() + " is on a " + n.surface().name().toLowerCase(java.util.Locale.ROOT)
                                + " and cannot be a stop"));
            }
        }
        for (NavGraph.Edge e : graph.edges()) {
            NavGraph.Node from = graph.node(e.from());
            NavGraph.Node to = graph.node(e.to());
            if (from == null || to == null || !(from.surface().isStreet() || to.surface().isStreet())) {
                continue;
            }
            // Residents step onto, along and off a road one block at a time in a straight line.
            int dx = Math.abs(from.x() - to.x());
            int dz = Math.abs(from.z() - to.z());
            if (dx + dz != 1) {
                issues.add(new Issue(Severity.ERROR, "STREET_SHAPE",
                        "edge " + e.from() + "->" + e.to() + " touches a road but is not a straight step"));
            }
        }
        // Every step off a road cell, onto the road or along it must run the same way. One that joins
        // neighbours on both axes (curb included) is a turn on the road.
        Map<Long, boolean[]> streetAxes = new HashMap<>();
        for (NavGraph.Edge e : graph.edges()) {
            NavGraph.Node from = graph.node(e.from());
            NavGraph.Node to = graph.node(e.to());
            if (from == null || to == null || !(from.surface().isStreet() || to.surface().isStreet())
                    || Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()) != 1) {
                continue;
            }
            int axis = from.x() != to.x() ? 0 : 1;
            if (from.surface().isStreet()) {
                streetAxes.computeIfAbsent(from.id(), k -> new boolean[2])[axis] = true;
            }
            if (to.surface().isStreet()) {
                streetAxes.computeIfAbsent(to.id(), k -> new boolean[2])[axis] = true;
            }
        }
        streetAxes.entrySet().stream().filter(entry -> entry.getValue()[0] && entry.getValue()[1])
                .map(Map.Entry::getKey).sorted().forEach(id -> issues.add(new Issue(Severity.ERROR, "STREET_TURN",
                        "node " + id + " is on a road and joins road cells on both axes, so a path could turn on it")));
        if (unknown > 0) {
            issues.add(new Issue(Severity.WARN, "UNKNOWN_SURFACE",
                    unknown + " nodes have no surface class (scanned without a classified palette)"));
        }

        int components = weakComponents(graph);
        if (components > 1) {
            issues.add(new Issue(Severity.WARN, "DISCONNECTED",
                    components + " disconnected components; avatars cannot path between them"));
        }

        boolean hasSpawn = graph.nodes().stream().anyMatch(n -> n.hasTag("spawn"));
        if (!hasSpawn) {
            issues.add(new Issue(Severity.WARN, "NO_SPAWN", "no node is tagged 'spawn'"));
        }

        return new Report(issues);
    }

    /** Number of weakly-connected components (edges treated as undirected) via union-find. */
    private static int weakComponents(NavGraph graph) {
        Map<Long, Long> parent = new HashMap<>();
        for (NavGraph.Node n : graph.nodes()) {
            parent.put(n.id(), n.id());
        }
        for (NavGraph.Edge e : graph.edges()) {
            if (graph.hasNode(e.from()) && graph.hasNode(e.to())) {
                union(parent, e.from(), e.to());
            }
        }
        Set<Long> roots = new HashSet<>();
        for (Long id : parent.keySet()) {
            roots.add(find(parent, id));
        }
        return roots.size();
    }

    private static long find(Map<Long, Long> parent, long x) {
        long root = x;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        long cur = x;
        while (parent.get(cur) != root) {
            long next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<Long, Long> parent, long a, long b) {
        long ra = find(parent, a);
        long rb = find(parent, b);
        if (ra != rb) {
            parent.put(ra, rb);
        }
    }
}
