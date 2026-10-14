package com.prosperitysmp.npc.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, deterministic selection of which tenant avatars to render, given viewer
 * positions and the on-demand limits (§10.2): a viewer sees at most
 * {@code maxPerViewer} of its nearest candidates within {@code radius}, and the
 * server renders at most {@code maxServer} total. No Bukkit/Citizens coupling —
 * the {@link RenderManager} maps tenants→positions, applies this, and spawns/walks.
 *
 * <p>Rendering is purely cosmetic: this decides visibility only and never touches
 * economy, occupancy, or settlement.
 */
public final class RenderPolicy {

    /** A world position (avatar candidate or viewer). */
    public record Vec(double x, double y, double z) {
        double dist2(Vec o) {
            double dx = x - o.x;
            double dy = y - o.y;
            double dz = z - o.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private RenderPolicy() {
    }

    /**
     * The set of candidate ids to render. Each viewer grants render slots to its
     * nearest {@code maxPerViewer} candidates within {@code radius}; a candidate
     * granted by any viewer is eligible; the overall set is then trimmed to
     * {@code maxServer}, keeping those closest to a granting viewer.
     */
    public static Set<String> select(Map<String, Vec> viewers, Map<String, Vec> candidates,
                                     double radius, int maxPerViewer, int maxServer) {
        Set<String> result = new LinkedHashSet<>();
        if (viewers.isEmpty() || candidates.isEmpty() || maxPerViewer <= 0 || maxServer <= 0
                || !Double.isFinite(radius) || radius < 0) {
            return result;
        }
        double r2 = radius * radius;
        Map<String, Double> bestDist = new HashMap<>(); // candidate -> min dist² to a granting viewer

        for (Vec viewer : viewers.values()) {
            List<Map.Entry<String, Double>> inRange = new ArrayList<>();
            for (Map.Entry<String, Vec> c : candidates.entrySet()) {
                double d2 = viewer.dist2(c.getValue());
                if (d2 <= r2) {
                    inRange.add(Map.entry(c.getKey(), d2));
                }
            }
            inRange.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                    .thenComparing(Map.Entry::getKey));
            int granted = 0;
            for (Map.Entry<String, Double> e : inRange) {
                if (granted >= maxPerViewer) {
                    break;
                }
                bestDist.merge(e.getKey(), e.getValue(), Math::min);
                granted++;
            }
        }

        // Rank even below the cap: callers must not spawn in HashMap iteration order.
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(bestDist.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey));
        for (int i = 0; i < Math.min(maxServer, ranked.size()); i++) {
            result.add(ranked.get(i).getKey());
        }
        return result;
    }

    /** Stable global cap across worlds; distances never cross world boundaries. */
    public static Set<String> selectWorlds(Map<String, Map<String, Vec>> viewers,
                                           Map<String, Map<String, Vec>> candidates,
                                           double radius, int perViewer, int serverCap) {
        Map<String, Double> distances = new HashMap<>();
        candidates.forEach((world, points) -> {
            Map<String, Vec> watchers = viewers.getOrDefault(world, Map.of());
            for (String id : select(watchers, points, radius, perViewer, serverCap)) {
                double distance = watchers.values().stream().mapToDouble(v -> v.dist2(points.get(id)))
                        .min().orElse(Double.POSITIVE_INFINITY);
                distances.merge(id, distance, Math::min);
            }
        });
        Set<String> result = new LinkedHashSet<>();
        distances.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.max(0, serverCap)).forEach(e -> result.add(e.getKey()));
        return result;
    }

    /** Grace never overrides a lowered hard cap. Desired ids have first claim on retained slots. */
    public static Set<String> retain(Map<String, Long> active, Set<String> desired,
                                     long now, long grace, int cap) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : desired) {
            if (result.size() >= Math.max(0, cap)) {
                break;
            }
            if (active.containsKey(id)) {
                result.add(id);
            }
        }
        active.entrySet().stream().filter(e -> !desired.contains(e.getKey()))
                .filter(e -> now - e.getValue() < Math.max(0, grace))
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.max(0, cap - result.size())).forEach(e -> result.add(e.getKey()));
        return result;
    }
}
