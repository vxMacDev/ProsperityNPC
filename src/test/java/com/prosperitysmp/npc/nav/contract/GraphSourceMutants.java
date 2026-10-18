package com.prosperitysmp.npc.nav.contract;

import com.prosperitysmp.npc.api.contract.ContractMeta;
import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.contract.GraphSourceContract.Caps;
import com.prosperitysmp.npc.nav.contract.GraphSourceContract.GraphSource;
import com.prosperitysmp.npc.nav.contract.GraphSourceContract.Street;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Deliberately broken graph sources, one per case of {@link GraphSourceContract}. Each wraps a working subject and
 * breaks exactly one rule in the graph it returns. Used only by the contract meta-test.
 */
public enum GraphSourceMutants implements ContractMeta.Mutant {
    /** Adds a step between two sidewalk nodes that are not neighbours. */
    NON_ADJACENT_EDGE("GS-01", graph -> {
        List<NavGraph.Node> walk = walkNodes(graph);
        NavGraph.Node from = walk.getFirst();
        NavGraph.Node far = walk.stream().max(Comparator.comparingInt(n -> Math.abs(n.x() - from.x()) + Math.abs(n.z() - from.z())))
                .orElseThrow();
        return rebuild(graph, graph.nodes(), extra(graph, new NavGraph.Edge(from.id(), far.id(), 10)));
    }),
    /** Adds a step to a node that does not exist. */
    DANGLING_EDGE("GS-02", graph -> {
        long missing = graph.nodes().stream().mapToLong(NavGraph.Node::id).max().orElse(0) + 1000;
        return rebuild(graph, graph.nodes(), extra(graph, new NavGraph.Edge(walkNodes(graph).getFirst().id(), missing, 10)));
    }),
    /** Numbers the nodes differently every time it builds, as ids taken from a hash set's order would. */
    UNSTABLE_IDS("GS-03", new UnaryOperator<>() {
        private final AtomicInteger builds = new AtomicInteger();

        @Override
        public NavGraph apply(NavGraph graph) {
            List<Long> ids = graph.nodes().stream().map(NavGraph.Node::id).sorted().toList();
            int shift = builds.incrementAndGet() % ids.size();
            if (shift == 0) shift = 1;
            Map<Long, Long> renumber = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) renumber.put(ids.get(i), ids.get((i + shift) % ids.size()));
            List<NavGraph.Node> nodes = new ArrayList<>();
            for (NavGraph.Node n : graph.nodes()) {
                nodes.add(new NavGraph.Node(renumber.get(n.id()), n.x(), n.y(), n.z(), n.tags(), n.surface(), n.roadWidth()));
            }
            List<NavGraph.Edge> edges = new ArrayList<>();
            for (NavGraph.Edge e : graph.edges()) {
                edges.add(new NavGraph.Edge(renumber.getOrDefault(e.from(), e.from()), renumber.getOrDefault(e.to(), e.to()), e.cost()));
            }
            return rebuild(graph, nodes, edges);
        }
    }),
    /** Makes the first crossing cell a stop. */
    TAGS_A_CROSSING("GS-04", graph -> {
        long first = streetNodes(graph).getFirst().id();
        List<NavGraph.Node> nodes = new ArrayList<>();
        for (NavGraph.Node n : graph.nodes()) nodes.add(n.id() == first ? n.withTags(Set.of("home")) : n);
        return rebuild(graph, nodes, graph.edges());
    }),
    /** Lets a crossing end on an access path instead of a sidewalk. */
    CROSSING_ENDS_ON_ACCESS("GS-05", graph -> {
        long curb = graph.edges().stream()
                .filter(e -> graph.hasNode(e.from()) && graph.hasNode(e.to()))
                .filter(e -> graph.node(e.to()).surface().isStreet() && !graph.node(e.from()).surface().isStreet())
                .mapToLong(NavGraph.Edge::from).min().orElseThrow();
        List<NavGraph.Node> nodes = new ArrayList<>();
        for (NavGraph.Node n : graph.nodes()) {
            nodes.add(n.id() == curb ? new NavGraph.Node(n.id(), n.x(), n.y(), n.z(), n.tags(), NavSurface.ACCESS, n.roadWidth()) : n);
        }
        return rebuild(graph, nodes, graph.edges());
    }),
    /** Charges the curb step's entry cost when leaving the crossing as well. */
    ENTRY_COST_ON_EXIT("GS-06", graph -> {
        Map<String, Integer> costs = new HashMap<>();
        for (NavGraph.Edge e : graph.edges()) costs.put(e.from() + ">" + e.to(), e.cost());
        List<NavGraph.Edge> edges = new ArrayList<>();
        for (NavGraph.Edge e : graph.edges()) {
            NavGraph.Node from = graph.node(e.from());
            NavGraph.Node to = graph.node(e.to());
            boolean exit = from != null && to != null && from.surface().isStreet() && !to.surface().isStreet();
            edges.add(exit ? new NavGraph.Edge(e.from(), e.to(), costs.getOrDefault(e.to() + ">" + e.from(), e.cost())) : e);
        }
        return rebuild(graph, graph.nodes(), edges);
    }),
    /** Builds with no cap but the graph's own size limits. */
    SKIPS_THE_CAP("GS-07", graph -> graph);

    private final String caseId;
    private final UnaryOperator<NavGraph> breaks;

    GraphSourceMutants(String caseId, UnaryOperator<NavGraph> breaks) {
        this.caseId = caseId;
        this.breaks = breaks;
    }

    @Override
    public String caseId() {
        return caseId;
    }

    @Override
    public Object mutate(Object subject) {
        return wrap((GraphSource) subject);
    }

    /** True when the mutant can show on this subject: the cap is only a rule for sources that take one. */
    public boolean appliesTo(GraphSource subject) {
        return this != SKIPS_THE_CAP || subject.capped();
    }

    GraphSource wrap(GraphSource inner) {
        GraphSourceMutants mutant = this;
        return new GraphSource() {
            @Override
            public NavGraph build(Street street, Caps caps) {
                Caps used = mutant == SKIPS_THE_CAP ? new Caps(Dijkstra.MAX_GRAPH_NODES, Dijkstra.MAX_GRAPH_EDGES) : caps;
                return breaks.apply(inner.build(street, used));
            }

            @Override
            public boolean capped() {
                return inner.capped();
            }
        };
    }

    private static List<NavGraph.Node> walkNodes(NavGraph graph) {
        return graph.nodes().stream().filter(n -> !n.surface().isStreet()).sorted(Comparator.comparingLong(NavGraph.Node::id)).toList();
    }

    private static List<NavGraph.Node> streetNodes(NavGraph graph) {
        return graph.nodes().stream().filter(n -> n.surface().isStreet()).sorted(Comparator.comparingLong(NavGraph.Node::id)).toList();
    }

    private static List<NavGraph.Edge> extra(NavGraph graph, NavGraph.Edge edge) {
        List<NavGraph.Edge> edges = new ArrayList<>(graph.edges());
        edges.add(edge);
        return edges;
    }

    private static NavGraph rebuild(NavGraph graph, Iterable<NavGraph.Node> nodes, List<NavGraph.Edge> edges) {
        NavGraph.Builder builder = NavGraph.builder();
        List<NavGraph.Node> sorted = new ArrayList<>();
        nodes.forEach(sorted::add);
        sorted.sort(Comparator.comparingLong(NavGraph.Node::id));
        sorted.forEach(builder::addNode);
        edges.forEach(e -> builder.addEdge(e.from(), e.to(), e.cost()));
        return builder.build();
    }
}
