package com.prosperitysmp.npc.nav.contract;

import com.prosperitysmp.npc.nav.NavGraph;

import java.util.Set;

/** The graph-source contract run against the block scanner, with the same two stops tagged after the scan. */
class ScanGraphSourceContractTest extends GraphSourceContract {

    @Override
    protected GraphSource subject() {
        return new GraphSource() {
            @Override
            public NavGraph build(Street street, Caps caps) {
                NavGraph graph = scanned(street);
                NavGraph.Builder builder = NavGraph.builder();
                for (NavGraph.Node n : graph.nodes()) {
                    boolean home = n.x() == 0 && n.z() == 2;
                    boolean work = n.x() == street.length() - 1 && n.z() == street.southCurb();
                    builder.addNode(home ? n.withTags(Set.of("home")) : work ? n.withTags(Set.of("work")) : n);
                }
                graph.edges().forEach(e -> builder.addEdge(e.from(), e.to(), e.cost()));
                return builder.build();
            }

            @Override
            public boolean capped() {
                return false;
            }
        };
    }
}
