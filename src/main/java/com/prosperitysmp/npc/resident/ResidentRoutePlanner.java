package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.render.RenderGeometry;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure per-activity planning over a published graph snapshot; no world, clock or I/O access. */
public final class ResidentRoutePlanner {
    public enum Status { AT_DESTINATION, WALK, NO_ANCHOR, NO_ROUTE, BUDGET_EXHAUSTED }

    /**
     * Paths include the start and destination. Failures have an empty path and cost -1.
     * targetTag is the selected tier, or the requested generic tag when no tier was
     * selected (missing anchors or an oversized graph). Counters exclude anchor lookup.
     */
    public record Decision(Status status, List<Long> nodes, long cost, String targetTag,
                           int visited, int edgesExamined) {
        public Decision {
            nodes = List.copyOf(nodes);
        }
    }

    private ResidentRoutePlanner() { }

    /** Call when the activity or graph changes, not for every rendered movement step. */
    public static Decision plan(ResidentProfile person, ResidentRoutine.Intent intent,
                                NavGraph graph, long start) {
        Objects.requireNonNull(person);
        Objects.requireNonNull(intent);
        Objects.requireNonNull(graph);
        String destinationTag = Objects.requireNonNull(intent.destinationTag());
        if (graph.nodeCount() > Dijkstra.MAX_GRAPH_NODES || graph.edgeCount() > Dijkstra.MAX_GRAPH_EDGES) {
            return new Decision(Status.BUDGET_EXHAUSTED, List.of(), -1L, destinationTag, 0, 0);
        }
        String targetTag = anchorTag(person, intent, graph);
        if (targetTag == null) {
            return new Decision(Status.NO_ANCHOR, List.of(), -1L, destinationTag, 0, 0);
        }
        return search(graph, start, targetTag);
    }

    /**
     * Routes to the nearest node carrying exactly this tag, with no tier fallback. A missing tag is NO_ANCHOR.
     * Used for a property-scoped home such as {@code home:<propertyId>}.
     */
    public static Decision planToTag(NavGraph graph, long start, String tag) {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(tag);
        if (graph.nodeCount() > Dijkstra.MAX_GRAPH_NODES || graph.edgeCount() > Dijkstra.MAX_GRAPH_EDGES) {
            return new Decision(Status.BUDGET_EXHAUSTED, List.of(), -1L, tag, 0, 0);
        }
        for (NavGraph.Node node : graph.nodes()) {
            if (node.hasTag(tag)) return search(graph, start, tag);
        }
        return new Decision(Status.NO_ANCHOR, List.of(), -1L, tag, 0, 0);
    }

    private static Decision search(NavGraph graph, long start, String targetTag) {
        Dijkstra.BoundedPath path = Dijkstra.findNearest(graph, start,
                id -> graph.node(id).hasTag(targetTag),
                edge -> RenderGeometry.adjacent(RenderGeometry.position(graph.node(edge.from())),
                        RenderGeometry.position(graph.node(edge.to()))));
        Status status = switch (path.status()) {
            case FOUND -> path.nodes().size() == 1 ? Status.AT_DESTINATION : Status.WALK;
            case NO_ROUTE -> Status.NO_ROUTE;
            case BUDGET_EXHAUSTED -> Status.BUDGET_EXHAUSTED;
        };
        return new Decision(status, path.nodes(), path.cost(), targetTag, path.visited(), path.edgesExamined());
    }

    private static String anchorTag(ResidentProfile person, ResidentRoutine.Intent intent, NavGraph graph) {
        String destination = intent.destinationTag();
        String personal = destination + ':' + person.id();
        String occupation = intent.activity() == ResidentRoutine.Activity.WORK
                ? "work:" + person.occupation().name().toLowerCase(Locale.ROOT) : null;
        boolean hasOccupation = false;
        boolean hasGeneric = false;
        // One capped pass selects a tier, not a target list. Explicit unreachable
        // anchors still own the intent; routing must never fall back to another tier.
        for (NavGraph.Node node : graph.nodes()) {
            if (node.hasTag(personal)) return personal;
            if (occupation != null && node.hasTag(occupation)) hasOccupation = true;
            if (node.hasTag(destination)) hasGeneric = true;
        }
        return hasOccupation ? occupation : hasGeneric ? destination : null;
    }
}
