package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.render.RenderPolicy.Vec;

/** Pure checks for the short, published graph segments accepted by the visual beta. */
public final class RenderGeometry {
    static final double ARRIVAL = 0.35;
    static final double CORRIDOR = 0.45;

    private RenderGeometry() {
    }

    public static Vec position(NavGraph.Node node) {
        return new Vec(node.x() + 0.5, node.y(), node.z() + 0.5);
    }

    static boolean arrived(Vec position, Vec target) {
        return position.dist2(target) <= ARRIVAL * ARRIVAL;
    }

    static long nearest(NavGraph graph, Vec position) {
        long best = -1;
        double distance = Double.POSITIVE_INFINITY;
        for (NavGraph.Node node : graph.nodes()) {
            double d = position(node).dist2(position);
            if (d < distance || (d == distance && node.id() < best)) {
                best = node.id();
                distance = d;
            }
        }
        return best;
    }

    public static boolean adjacent(Vec from, Vec to) {
        double dx = Math.abs(from.x() - to.x());
        double dz = Math.abs(from.z() - to.z());
        return dx <= 1 && dz <= 1 && dx + dz > 0 && Math.abs(from.y() - to.y()) <= 1;
    }

    static boolean edge(NavGraph graph, long from, long to) {
        return graph.hasNode(from) && graph.hasNode(to)
                && adjacent(position(graph.node(from)), position(graph.node(to)))
                && graph.hasPositiveEdge(from, to);
    }

    /** Finite 3D segment, not an infinite line or XZ-only corridor. */
    static boolean confined(Vec point, Vec from, Vec to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double length2 = from.dist2(to);
        if (!(length2 > 0) || !Double.isFinite(length2)) {
            return arrived(point, from);
        }
        double t = ((point.x() - from.x()) * dx + (point.y() - from.y()) * dy
                + (point.z() - from.z()) * dz) / length2;
        t = Math.max(0, Math.min(1, t));
        return point.dist2(new Vec(from.x() + t * dx, from.y() + t * dy, from.z() + t * dz))
                <= CORRIDOR * CORRIDOR;
    }
}
