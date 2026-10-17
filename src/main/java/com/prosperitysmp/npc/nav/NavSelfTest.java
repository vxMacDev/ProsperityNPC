package com.prosperitysmp.npc.nav;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds two small streets in memory and runs the real scanner and audit on them, so staff can prove
 * the audit on the deployed jar can fail. The street is the same ground twice: scanned with no classes
 * (roads count as ordinary ground, so the shortest walk cuts diagonally across the road) and scanned
 * with classes (roads are crossed straight). The first must FAIL the audit and the second must PASS.
 * No world, no database.
 */
public final class NavSelfTest {

    public static final int ROAD_WIDTH = 9;
    private static final int GROUND_Y = 63;
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);

    public record Case(String name, StreetAudit.Verdict expected, StreetAudit.Result result) {
        public boolean ok() {
            return result.verdict() == expected;
        }
    }

    public record Report(List<Case> cases) {
        public boolean ok() {
            return cases.stream().allMatch(Case::ok);
        }

        public List<String> logLines() {
            return cases.stream().map(c -> StreetAudit.logLine(c.name(), c.result())).toList();
        }
    }

    private NavSelfTest() {
    }

    /** Sidewalk for z in 0..2, road for the next {@code width} rows, sidewalk again after. */
    private record Street(int width, boolean classified) implements NavScanLogic.Terrain {
        boolean ground(int z) {
            return z >= 0 && z <= width + 5;
        }

        boolean road(int z) {
            return z >= 3 && z <= 2 + width;
        }

        @Override public boolean isSupport(int x, int y, int z) {
            return y == GROUND_Y && ground(z);
        }

        @Override public boolean isClear(int x, int y, int z) {
            return y != GROUND_Y || !ground(z);
        }

        @Override public NavSurface surface(int x, int y, int z) {
            if (!classified || !isSupport(x, y, z)) {
                return NavSurface.UNKNOWN;
            }
            return road(z) ? NavSurface.ROAD : NavSurface.SIDEWALK;
        }

        NavScanLogic.Bounds bounds() {
            return new NavScanLogic.Bounds(-3, 60, width + 6, 66, 0, width + 5);
        }
    }

    public static Report run() {
        return new Report(List.of(
                new Case("selftest-legacy", StreetAudit.Verdict.FAIL, legacy()),
                new Case("selftest-city", StreetAudit.Verdict.PASS, city())));
    }

    // From (0,1) on one sidewalk to (width+3, width+4) on the other: with the road as plain ground the
    // single cheapest walk is the pure diagonal, which is exactly what the audit has to catch.
    private static NavGraph tag(NavGraph scanned, int width) {
        NavGraph.Builder builder = NavGraph.builder();
        for (NavGraph.Node node : scanned.nodes()) {
            Set<String> tags = new HashSet<>();
            if (node.x() == 0 && node.z() == 1) {
                tags.add("home");
            }
            if (node.x() == width + 3 && node.z() == width + 4) {
                tags.add("work");
            }
            builder.addNode(node.withTags(tags));
        }
        scanned.edges().forEach(e -> builder.addEdge(e.from(), e.to(), e.cost()));
        return builder.build();
    }

    private static StreetAudit.Options options() {
        return new StreetAudit.Options(RULES, 20_000_000_000L, 1024, System::nanoTime);
    }

    private static StreetAudit.Result legacy() {
        Street ground = new Street(ROAD_WIDTH, false);
        NavGraph graph = tag(NavScanLogic.scan(ground.bounds(), ground, 2, 1), ROAD_WIDTH);
        // The classes the current palette would give each node: that is what makes the road visible.
        Street truth = new Street(ROAD_WIDTH, true);
        Map<Long, NavSurface> classes = new HashMap<>();
        for (NavGraph.Node node : graph.nodes()) {
            classes.put(node.id(), truth.surface(node.x(), node.y() - 1, node.z()));
        }
        return StreetAudit.audit(graph, new StreetAudit.Live(classes, Set.of(), 0, Map.of(), Map.of()), options());
    }

    private static StreetAudit.Result city() {
        Street ground = new Street(ROAD_WIDTH, true);
        NavGraph graph = tag(NavScanLogic.scan(ground.bounds(), ground, 2, 1, RULES).graph(), ROAD_WIDTH);
        return StreetAudit.audit(graph, null, options());
    }
}
