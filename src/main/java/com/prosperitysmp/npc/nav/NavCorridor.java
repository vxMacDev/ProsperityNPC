package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.render.RenderGeometry;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Off-thread authoring only; retains captured geometry and every tagged anchor. */
public final class NavCorridor {
    private NavCorridor() { }

    public static NavGraph compact(NavGraph source) {
        if (source.nodeCount() > Dijkstra.MAX_GRAPH_NODES || source.edgeCount() > Dijkstra.MAX_GRAPH_EDGES)
            throw new IllegalArgumentException("Source exceeds authoring limits; narrow the scan.");
        if (!NavGraphValidator.validate(source).valid())
            throw new IllegalArgumentException("Source graph is invalid.");
        for (var edge : source.edges()) {
            if (!RenderGeometry.adjacent(RenderGeometry.position(source.node(edge.from())),
                    RenderGeometry.position(source.node(edge.to()))))
                throw new IllegalArgumentException("Source contains non-adjacent edges.");
        }
        var anchors = source.nodes().stream().filter(n -> !n.tags().isEmpty())
                .sorted(Comparator.comparingLong(NavGraph.Node::id)).toList();
        if (anchors.size() < 2 || anchors.size() > 8)
            throw new IllegalArgumentException("Tag 2..8 destination nodes before corridor authoring.");
        Set<Long> keep = new HashSet<>();
        // Both directions matter: never infer a return path from outbound reachability.
        for (var from : anchors) for (var to : anchors) {
            if (Thread.currentThread().isInterrupted())
                throw new IllegalStateException("Authoring interrupted.");
            var path = Dijkstra.find(source, from.id(), to.id());
            if (!path.found()) throw new IllegalArgumentException("Tagged destinations are disconnected.");
            keep.addAll(path.nodes());
        }
        var builder = NavGraph.builder();
        source.nodes().stream().filter(n -> keep.contains(n.id()))
                .sorted(Comparator.comparingLong(NavGraph.Node::id)).forEach(builder::addNode);
        source.edges().stream().filter(e -> keep.contains(e.from()) && keep.contains(e.to()))
                .forEach(e -> builder.addEdge(e.from(), e.to(), e.cost()));
        var result = builder.build();
        if (result.nodeCount() > Dijkstra.MAX_VISITED || result.edgeCount() > Dijkstra.MAX_EDGES_EXAMINED)
            throw new IllegalArgumentException("Corridor still exceeds runtime limits; shorten or split the district.");
        return result;
    }
}
