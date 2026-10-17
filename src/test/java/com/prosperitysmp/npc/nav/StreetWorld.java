package com.prosperitysmp.npc.nav;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A flat test world drawn as characters, one per (x,z) column:
 * S sidewalk, A access, R road, W crossing paint, U plain walkable block with no class, . nothing.
 * Every support is at y 63 unless raised, so nodes stand at y 64. With {@code classified} false the
 * same blocks are all UNKNOWN, which is what a list-form palette gives.
 */
final class StreetWorld implements NavScanLogic.Terrain {
    static final int GROUND = 63;
    static final StreetRules RULES = new StreetRules(12, 16, 0.4);

    private final Map<Long, Character> cells;
    private final Map<Long, Integer> raised = new HashMap<>();
    private final boolean classified;
    private final int minX, minZ, maxX, maxZ;

    private StreetWorld(Builder b, boolean classified) {
        this.cells = new HashMap<>(b.cells);
        this.classified = classified;
        this.minX = b.minX;
        this.minZ = b.minZ;
        this.maxX = b.minX + b.sizeX - 1;
        this.maxZ = b.minZ + b.sizeZ - 1;
        this.raised.putAll(b.raised);
    }

    static final class Builder {
        private final Map<Long, Character> cells = new HashMap<>();
        private final Map<Long, Integer> raised = new HashMap<>();
        private final int minX, minZ, sizeX, sizeZ;

        Builder(int minX, int minZ, int sizeX, int sizeZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
        }

        Builder set(int x, int z, char c) {
            cells.put(key(x, z), c);
            return this;
        }

        Builder fill(int x1, int z1, int x2, int z2, char c) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    set(x, z, c);
                }
            }
            return this;
        }

        /** Lifts the support under (x,z) by dy blocks. */
        Builder raise(int x, int z, int dy) {
            raised.put(key(x, z), dy);
            return this;
        }

        StreetWorld build(boolean classified) {
            return new StreetWorld(this, classified);
        }

        StreetWorld build() {
            return build(true);
        }
    }

    static Builder grid(int minX, int minZ, int sizeX, int sizeZ) {
        return new Builder(minX, minZ, sizeX, sizeZ);
    }

    /** Sidewalk at z 0..2, road at z 3..2+width, sidewalk at z width+3..width+5, for x 0..length-1. */
    static Builder strip(int width, int length) {
        return grid(0, 0, length, width + 6)
                .fill(0, 0, length - 1, 2, 'S')
                .fill(0, 3, length - 1, 2 + width, 'R')
                .fill(0, width + 3, length - 1, width + 5, 'S');
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    char at(int x, int z) {
        return cells.getOrDefault(key(x, z), '.');
    }

    int ground(int x, int z) {
        return GROUND + raised.getOrDefault(key(x, z), 0);
    }

    NavScanLogic.Bounds bounds() {
        int lift = raised.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return new NavScanLogic.Bounds(minX, 60, maxX, 68 + Math.max(0, lift), minZ, maxZ);
    }

    @Override public boolean isSupport(int x, int y, int z) {
        return "SARWU".indexOf(at(x, z)) >= 0 && y == ground(x, z);
    }

    @Override public boolean isClear(int x, int y, int z) {
        return "SARWU".indexOf(at(x, z)) < 0 || y > ground(x, z);
    }

    @Override public NavSurface surface(int x, int y, int z) {
        if (!classified || !isSupport(x, y, z)) {
            return NavSurface.UNKNOWN;
        }
        return switch (at(x, z)) {
            case 'S' -> NavSurface.SIDEWALK;
            case 'A' -> NavSurface.ACCESS;
            case 'R' -> NavSurface.ROAD;
            case 'W' -> NavSurface.CROSSING;
            default -> NavSurface.UNKNOWN;
        };
    }

    NavScanLogic.ScanResult scan(StreetRules rules) {
        return NavScanLogic.scan(bounds(), this, 2, 1, rules);
    }

    NavGraph graph() {
        return scan(RULES).graph();
    }

    // ---- graph helpers shared by the street tests ----

    static NavGraph.Node node(NavGraph graph, int x, int z) {
        return nodes(graph, x, z).stream().findFirst().orElseThrow(() -> new AssertionError("no node at " + x + "," + z));
    }

    static List<NavGraph.Node> nodes(NavGraph graph, int x, int z) {
        return graph.nodes().stream().filter(n -> n.x() == x && n.z() == z).toList();
    }

    static List<NavGraph.Node> street(NavGraph graph) {
        return graph.nodes().stream().filter(n -> n.surface().isStreet()).toList();
    }

    static NavGraph tag(NavGraph graph, Map<Long, String> tags) {
        NavGraph.Builder builder = NavGraph.builder();
        for (NavGraph.Node n : graph.nodes()) {
            builder.addNode(n.withTags(tags.containsKey(n.id()) ? java.util.Set.of(tags.get(n.id())) : n.tags()));
        }
        graph.edges().forEach(e -> builder.addEdge(e.from(), e.to(), e.cost()));
        return builder.build();
    }
}
