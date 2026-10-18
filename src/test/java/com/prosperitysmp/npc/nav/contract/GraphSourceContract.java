package com.prosperitysmp.npc.nav.contract;

import com.prosperitysmp.npc.api.contract.CaseId;
import com.prosperitysmp.npc.api.contract.ContractMeta;
import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphValidator;
import com.prosperitysmp.npc.nav.NavScanLogic;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.render.RenderGeometry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What every way of making a navigation graph must give: staff waypoints, route files and the block scanner. Each
 * subject builds the same drawn street (sidewalk, road, sidewalk; one crossing per block along it) its own way, and
 * the cases check the graph that comes out. Critical cases each have a broken subject ({@link GraphSourceMutants})
 * that only they catch; {@code GraphSourceContractMetaTest} proves it.
 */
public abstract class GraphSourceContract {

    /** The street rules every subject builds with, the same numbers the street tests use. */
    public static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    public static final int GROUND = 63;
    /** The node and directed-edge limits of a resident's route search, which waypoint and file graphs must keep to. */
    public static final Caps RUNTIME = new Caps(Dijkstra.MAX_VISITED, Dijkstra.MAX_EDGES_EXAMINED);

    /**
     * Sidewalk at z 0..2, road at z 3..2+width (all crossing paint when {@code painted}), sidewalk at z width+3..width+5,
     * for x 0..length-1, every support at y 63. Stops: {@code home} on the north curb at x 0, {@code work} on the south
     * curb at the last x.
     */
    public record Street(int width, int length, boolean painted) {
        public int southCurb() {
            return width + 3;
        }

        /** The scanner's view of the street, and the reference its costs are compared with. */
        public NavScanLogic.Terrain terrain() {
            return new NavScanLogic.Terrain() {
                private boolean ground(int x, int z) {
                    return x >= 0 && x < length && z >= 0 && z <= width + 5;
                }

                @Override public boolean isSupport(int x, int y, int z) {
                    return ground(x, z) && y == GROUND;
                }

                @Override public boolean isClear(int x, int y, int z) {
                    return !ground(x, z) || y > GROUND;
                }

                @Override public NavSurface surface(int x, int y, int z) {
                    if (!isSupport(x, y, z)) return NavSurface.UNKNOWN;
                    if (z >= 3 && z <= width + 2) return painted ? NavSurface.CROSSING : NavSurface.ROAD;
                    return NavSurface.SIDEWALK;
                }
            };
        }

        public NavScanLogic.Bounds bounds() {
            return new NavScanLogic.Bounds(0, GROUND - 3, length - 1, GROUND + 5, 0, width + 5);
        }
    }

    /** The most nodes and directed edges a subject may make. */
    public record Caps(int maxNodes, int maxEdges) { }

    /** One way of making a graph. Throws IllegalArgumentException when it refuses the street. */
    public interface GraphSource {
        NavGraph build(Street street, Caps caps);

        /** True when this source keeps to the caps it is given (waypoints and route files; a scan has its own limits). */
        boolean capped();
    }

    public static final Street PLAIN = new Street(5, 12, false);
    public static final Street PAINTED = new Street(5, 12, true);
    /** 120 crossings of 7 road blocks: 1080 cells, more than a resident's route search covers. */
    public static final Street TOO_BIG = new Street(7, 120, false);

    private static volatile GraphSourceMutants active;

    protected abstract GraphSource subject();

    /** The subject, broken by the mutant under test during a meta run. */
    private GraphSource source() {
        GraphSource subject = subject();
        GraphSourceMutants mutant = active;
        return mutant == null ? subject : mutant.wrap(subject);
    }

    /** Runs a concrete suite with every subject broken by {@code mutant} (null: as written). One run at a time. */
    static synchronized ContractMeta.Result runWith(Class<? extends GraphSourceContract> suite, GraphSourceMutants mutant) {
        active = mutant;
        try {
            return ContractMeta.run(suite);
        } finally {
            active = null;
        }
    }

    /** The block scanner's graph of a street: the reference every subject's crossings are compared with. */
    public static NavGraph scanned(Street street) {
        return NavScanLogic.scan(street.bounds(), street.terrain(), 2, 1, RULES).graph();
    }

    /** Every street node and every edge that touches one, by coordinates. */
    static Map<String, String> streetPart(NavGraph graph) {
        Map<String, String> out = new TreeMap<>();
        for (NavGraph.Node n : graph.nodes()) {
            if (n.surface().isStreet()) out.put("node " + n.x() + "," + n.y() + "," + n.z(), n.surface().name());
        }
        for (NavGraph.Edge e : graph.edges()) {
            NavGraph.Node a = graph.node(e.from());
            NavGraph.Node b = graph.node(e.to());
            if (a == null || b == null) continue;
            if (a.surface().isStreet() || b.surface().isStreet()) {
                out.put("edge " + a.x() + "," + a.z() + ">" + b.x() + "," + b.z(), String.valueOf(e.cost()));
            }
        }
        return out;
    }

    /** The whole graph as text: nodes by id with every field, then the edges in order. */
    static String bytes(NavGraph graph) {
        StringBuilder out = new StringBuilder();
        graph.nodes().stream().sorted(Comparator.comparingLong(NavGraph.Node::id)).forEach(n -> out.append(n.id())
                .append(' ').append(n.x()).append(',').append(n.y()).append(',').append(n.z()).append(' ').append(n.surface())
                .append(' ').append(new TreeSet<>(n.tags())).append(" w").append(n.roadWidth()).append('\n'));
        for (NavGraph.Edge e : graph.edges()) out.append(e.from()).append('>').append(e.to()).append(' ').append(e.cost()).append('\n');
        return out.toString();
    }

    @Test
    @CaseId(value = "GS-01", critical = true)
    void everyEdgeJoinsNeighbouringNodesAtAPositiveCost() {
        for (Street street : List.of(PLAIN, PAINTED)) {
            NavGraph graph = source().build(street, RUNTIME);
            assertFalse(graph.edges().isEmpty());
            for (NavGraph.Edge e : graph.edges()) {
                NavGraph.Node from = graph.node(e.from());
                NavGraph.Node to = graph.node(e.to());
                // An edge to a missing node is the validator's to catch (GS-02).
                if (from == null || to == null) continue;
                assertTrue(RenderGeometry.adjacent(RenderGeometry.position(from), RenderGeometry.position(to)),
                        "edge " + e + " joins nodes that are not neighbours");
                assertTrue(e.cost() > 0, "edge " + e + " has no cost");
            }
        }
    }

    @Test
    @CaseId(value = "GS-02", critical = true)
    void theValidatorFindsNoError() {
        for (Street street : List.of(PLAIN, PAINTED)) {
            NavGraphValidator.Report report = NavGraphValidator.validate(source().build(street, RUNTIME));
            // A stop on a road is GS-04's rule; it is left to that case so each rule is caught in one place.
            List<String> errors = report.issues().stream().filter(i -> i.severity() == NavGraphValidator.Severity.ERROR)
                    .filter(i -> !i.code().equals("STREET_TAG")).map(i -> i.code() + " " + i.message()).toList();
            assertEquals(List.of(), errors);
        }
    }

    @Test
    @CaseId("GS-03")
    void theSameStreetGivesAByteIdenticalGraph() {
        GraphSource source = source();
        for (Street street : List.of(PLAIN, PAINTED)) {
            assertEquals(bytes(source.build(street, RUNTIME)), bytes(source.build(street, RUNTIME)));
        }
    }

    @Test
    @CaseId(value = "GS-04", critical = true)
    void noStopStandsOnARoad() {
        for (Street street : List.of(PLAIN, PAINTED)) {
            for (NavGraph.Node n : source().build(street, RUNTIME).nodes()) {
                if (n.surface().isStreet()) assertEquals(Set.of(), n.tags(), "street node " + n.id());
            }
        }
    }

    @Test
    @CaseId(value = "GS-05", critical = true)
    void everyStreetNodeLiesOnAStraightCrossingBetweenTwoSidewalks() {
        for (Street street : List.of(PLAIN, PAINTED)) {
            NavGraph graph = source().build(street, RUNTIME);
            Map<Long, Set<Long>> neighbours = new HashMap<>();
            for (NavGraph.Edge e : graph.edges()) {
                if (!graph.hasNode(e.from()) || !graph.hasNode(e.to())) continue;
                neighbours.computeIfAbsent(e.from(), k -> new TreeSet<>()).add(e.to());
                neighbours.computeIfAbsent(e.to(), k -> new TreeSet<>()).add(e.from());
            }
            Set<Long> seen = new HashSet<>();
            int crossings = 0;
            for (NavGraph.Node start : graph.nodes()) {
                if (!start.surface().isStreet() || !seen.add(start.id())) continue;
                crossings++;
                List<NavGraph.Node> cells = new ArrayList<>();
                Set<Long> curbs = new TreeSet<>();
                ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start.id()));
                while (!queue.isEmpty()) {
                    NavGraph.Node cell = graph.node(queue.poll());
                    cells.add(cell);
                    for (long next : neighbours.getOrDefault(cell.id(), Set.of())) {
                        if (graph.node(next).surface().isStreet()) {
                            if (seen.add(next)) queue.add(next);
                        } else {
                            curbs.add(next);
                        }
                    }
                }
                String where = "crossing at " + start.x() + "," + start.y() + "," + start.z();
                assertEquals(2, curbs.size(), where + " has " + curbs.size() + " curbs");
                List<NavGraph.Node> line = new ArrayList<>(cells);
                for (long curb : curbs) {
                    assertEquals(NavSurface.SIDEWALK, graph.node(curb).surface(), where + " ends on " + graph.node(curb).surface());
                    line.add(graph.node(curb));
                }
                boolean alongZ = line.stream().allMatch(n -> n.x() == line.getFirst().x());
                boolean alongX = line.stream().allMatch(n -> n.z() == line.getFirst().z());
                assertTrue(alongX || alongZ, where + " is not straight");
                line.sort(Comparator.comparingInt(n -> alongZ ? n.z() : n.x()));
                for (int i = 1; i < line.size(); i++) {
                    int step = alongZ ? line.get(i).z() - line.get(i - 1).z() : line.get(i).x() - line.get(i - 1).x();
                    assertEquals(1, step, where + " has a gap");
                }
                assertTrue(curbs.contains(line.getFirst().id()) && curbs.contains(line.getLast().id()),
                        where + " does not end on its curbs");
            }
            assertEquals(street.length(), crossings);
        }
    }

    @Test
    @CaseId(value = "GS-06", critical = true)
    void streetCostsEqualTheScanners() {
        for (Street street : List.of(PLAIN, PAINTED)) {
            Map<String, String> expected = streetPart(scanned(street));
            assertFalse(expected.isEmpty());
            assertEquals(expected, streetPart(source().build(street, RUNTIME)), street.toString());
        }
    }

    @Test
    @CaseId(value = "GS-07", critical = true)
    void waypointAndFileGraphsKeepToTheRouteSearchCaps() {
        GraphSource source = source();
        assumeTrue(source.capped(), "a scan has its own limits");
        NavGraph normal = source.build(PLAIN, RUNTIME);
        assertTrue(normal.nodeCount() <= RUNTIME.maxNodes() && normal.edgeCount() <= RUNTIME.maxEdges());
        NavGraph big;
        try {
            big = source.build(TOO_BIG, RUNTIME);
        } catch (IllegalArgumentException refused) {
            return;
        }
        if (big.nodeCount() > RUNTIME.maxNodes() || big.edgeCount() > RUNTIME.maxEdges()) {
            fail("built " + big.nodeCount() + " nodes and " + big.edgeCount() + " edges past the caps");
        }
    }
}
