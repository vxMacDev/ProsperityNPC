package com.prosperitysmp.npc.nav;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chains found on random drawn graphs: they must partition the street cells exactly as the street's own connections do. */
class StreetChainsPropertyTest {
    private static final int SIZE = 8;

    private static NavGraph random(long seed) {
        Random random = new Random(seed);
        NavSurface[] all = NavSurface.values();
        NavGraph.Builder builder = NavGraph.builder();
        long[][] ids = new long[SIZE][SIZE];
        long next = 1;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                ids[x][z] = next++;
                // Street cells are the common case so that chains are long enough to be interesting.
                NavSurface surface = random.nextInt(10) < 5 ? NavSurface.ROAD : all[random.nextInt(all.length)];
                builder.addNode(ids[x][z], x, 64, z, surface);
            }
        }
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (x + 1 < SIZE && random.nextInt(10) < 6) {
                    if (random.nextBoolean()) builder.addBiEdge(ids[x][z], ids[x + 1][z], 10);
                    else builder.addEdge(ids[x][z], ids[x + 1][z], 10);
                }
                if (z + 1 < SIZE && random.nextInt(10) < 6) {
                    if (random.nextBoolean()) builder.addBiEdge(ids[x][z], ids[x][z + 1], 10);
                    else builder.addEdge(ids[x][z + 1], ids[x][z], 10);
                }
            }
        }
        return builder.build();
    }

    private static long find(Map<Long, Long> parent, long x) {
        while (parent.get(x) != x) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30})
    void chainsAreExactlyTheConnectedGroupsOfStreetCellsWithTheirCurbs(long seed) {
        NavGraph graph = random(seed);
        List<StreetChains.Chain> chains = StreetChains.find(graph, id -> graph.node(id).surface());

        Set<Long> street = new TreeSet<>();
        graph.nodes().stream().filter(n -> n.surface().isStreet()).forEach(n -> street.add(n.id()));

        // Expected groups: street cells joined by an edge between two street cells, in either direction.
        Map<Long, Long> parent = new HashMap<>();
        for (long id : street) parent.put(id, id);
        for (NavGraph.Edge e : graph.edges()) {
            if (street.contains(e.from()) && street.contains(e.to())) parent.put(find(parent, e.from()), find(parent, e.to()));
        }
        Set<Long> roots = new HashSet<>();
        for (long id : street) roots.add(find(parent, id));
        assertEquals(roots.size(), chains.size());

        Set<Long> seen = new HashSet<>();
        for (StreetChains.Chain chain : chains) {
            List<Long> cellIds = chain.cells().stream().map(NavGraph.Node::id).toList();
            assertFalse(cellIds.isEmpty());
            Set<Long> group = new HashSet<>();
            for (long id : cellIds) {
                assertTrue(street.contains(id), "a chain holds only street cells");
                assertTrue(seen.add(id), "no cell is in two chains");
                group.add(find(parent, id));
            }
            assertEquals(1, group.size(), "one chain is one connected group");

            assertEquals(chain.cells().get(chain.cells().size() / 2).id(), chain.mid());
            assertEquals(chain.cells().size(), chain.length());

            List<NavGraph.Node> sorted = chain.cells().stream().sorted(java.util.Comparator.comparingInt(NavGraph.Node::x)
                    .thenComparingInt(NavGraph.Node::z).thenComparingLong(NavGraph.Node::id)).toList();
            assertEquals(sorted, chain.cells());

            Set<Long> expectedCurbs = new TreeSet<>();
            for (NavGraph.Edge e : graph.edges()) {
                boolean fromIn = cellIds.contains(e.from());
                boolean toIn = cellIds.contains(e.to());
                if (fromIn && !street.contains(e.to())) expectedCurbs.add(e.to());
                if (toIn && !street.contains(e.from())) expectedCurbs.add(e.from());
            }
            assertEquals(List.copyOf(expectedCurbs), chain.curbs());

            if (chain.cells().size() > 1) {
                assertEquals(chain.cells().get(0).z() == chain.cells().get(1).z(), chain.alongX());
            }
        }
        assertEquals(street, seen, "every street cell is in a chain");
    }
}
