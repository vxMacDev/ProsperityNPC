package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.StreetAudit.Code;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.prosperitysmp.npc.nav.StreetWorld.RULES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The width check on a stored graph. A scan keeps only the crossings that are clean, so the crossings
 * left in the graph say nothing about how wide the road is; the width has to be the one the scan measured.
 */
class StreetStoredWidthTest {
    private static final StreetAudit.Options OPTIONS = new StreetAudit.Options(RULES, 60_000_000_000L, 1024, System::nanoTime);

    private static NavGraph tagged(StreetWorld world) {
        NavGraph base = world.graph();
        return StreetWorld.tag(base, Map.of(StreetWorld.node(base, 2, 1).id(), "home",
                StreetWorld.node(base, 14, 9).id(), "work"));
    }

    private static void assertPasses(NavGraph graph) {
        var result = StreetAudit.audit(graph, null, OPTIONS);
        assertEquals(0, result.count(Code.ALONG_ROAD), () -> result.examples().toString());
        assertEquals(StreetAudit.Verdict.PASS, result.verdict(), () -> result.examples().toString());
    }

    @Test
    void lotPathFrontageLeavesAFewCrossingsAndTheGraphStillPasses() {
        // Lot-path blocks line the near curb except for a three-block gap: only the gap's crossings survive the scan.
        StreetWorld world = StreetWorld.strip(5, 20).fill(0, 2, 8, 2, 'A').fill(12, 2, 19, 2, 'A').build();
        NavGraph graph = tagged(world);
        assertEquals(3, StreetWorld.street(graph).stream().filter(n -> n.z() == 3).count());
        assertPasses(graph);
    }

    @Test
    void curbPostsInAStaggeredPatternLeaveCrossingsBetweenThemAndTheGraphStillPasses() {
        // No standable block at a few curb cells, on alternating sides, as lamp posts and trees leave it.
        StreetWorld world = StreetWorld.strip(5, 20).set(6, 2, '.').set(10, 2, '.').set(14, 2, '.')
                .set(8, 8, '.').set(12, 8, '.').build();
        NavGraph graph = tagged(world);
        assertTrue(StreetWorld.street(graph).stream().filter(n -> n.z() == 3).count() > 10);
        assertPasses(graph);
    }

    /** Sidewalk, a straight crossing of {@code widths.length} road cells, sidewalk; each road cell carries its width. */
    private static NavGraph handMade(int... widths) {
        NavGraph.Builder b = NavGraph.builder();
        b.addNode(new NavGraph.Node(1, 0, 64, 0, Set.of("home"), NavSurface.SIDEWALK));
        for (int i = 0; i < widths.length; i++) {
            b.addNode(new NavGraph.Node(2 + i, 0, 64, 1 + i, Set.of(), NavSurface.ROAD, widths[i]));
        }
        b.addNode(new NavGraph.Node(2 + widths.length, 0, 64, 1 + widths.length, Set.of("work"), NavSurface.SIDEWALK));
        for (int i = 0; i <= widths.length; i++) {
            b.addBiEdge(1 + i, 2 + i, 10);
        }
        return b.build();
    }

    @Test
    void aCrossingLongerThanItsRecordedWidthFails() {
        var result = StreetAudit.audit(handMade(3, 3, 3, 3, 3), null, OPTIONS);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.count(Code.ALONG_ROAD) > 0);
        assertTrue(result.examples().stream().anyMatch(v -> v.code() == Code.ALONG_ROAD && v.detail().contains("(3)")),
                result.examples().toString());
    }

    @Test
    void aCrossingNoLongerThanItsRecordedWidthPasses() {
        for (int width : new int[]{5, 6, 12}) {
            var result = StreetAudit.audit(handMade(width, width, width, width, width), null, OPTIONS);
            assertEquals(0, result.count(Code.ALONG_ROAD), "width " + width);
            assertEquals(StreetAudit.Verdict.PASS, result.verdict(), () -> result.examples().toString());
        }
    }

    @Test
    void theNarrowestRecordedCellDecides() {
        var result = StreetAudit.audit(handMade(5, 5, 2, 5, 5), null, OPTIONS);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.examples().stream().anyMatch(v -> v.code() == Code.ALONG_ROAD && v.detail().contains("(2)")),
                result.examples().toString());
    }

    @Test
    void aCrossingWithNoRecordedWidthIsUncheckedNeitherFailedNorPassed() {
        // An older or hand-made graph: the same strip along a one-block road would fail if its width were known.
        var result = StreetAudit.audit(handMade(0, 0, 0, 0, 0, 0, 0, 0), null, OPTIONS);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(0, result.count(Code.ALONG_ROAD));
        assertEquals(1, result.unchecked());
        assertTrue(StreetAudit.logLine("g", result).endsWith("unchecked=1"));
        // A width on only some cells of the crossing is no reading of the whole crossing.
        var partial = StreetAudit.audit(handMade(5, 5, 0, 5, 5), null, OPTIONS);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, partial.verdict());
        assertEquals(1, partial.unchecked());
        // Being unable to check one rule is no excuse for a rule that is broken elsewhere.
        NavGraph lateral = NavGraph.builder().addNode(new NavGraph.Node(1, 0, 64, 0, Set.of("home"), NavSurface.SIDEWALK))
                .addNode(new NavGraph.Node(2, 1, 64, 1, Set.of(), NavSurface.ROAD))
                .addNode(new NavGraph.Node(3, 2, 64, 2, Set.of("work"), NavSurface.SIDEWALK))
                .addBiEdge(1, 2, 14).addBiEdge(2, 3, 14).build();
        assertEquals(StreetAudit.Verdict.FAIL, StreetAudit.audit(lateral, null, OPTIONS).verdict());
    }

    @Test
    void aWorldReadingStillOverridesTheStoredWidth() {
        NavGraph graph = handMade(9, 9, 9, 9, 9);
        long mid = StreetChains.find(graph, id -> graph.node(id).surface()).getFirst().mid();
        var narrow = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(mid, 2), Map.of());
        assertEquals(StreetAudit.Verdict.FAIL, StreetAudit.audit(graph, narrow, OPTIONS).verdict());
        // The stored width does not stand in for a world reading that never happened.
        var unread = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, StreetAudit.audit(graph, unread, OPTIONS).verdict());
    }

    @Test
    void theScanRecordsHowFarTheStreetRunsSidewaysOfEveryCrossingNodeAndNothingElse() {
        for (int width : new int[]{3, 5, 9}) {
            NavGraph graph = StreetWorld.strip(width, 24).build().graph();
            assertFalse(StreetWorld.street(graph).isEmpty());
            for (NavGraph.Node n : graph.nodes()) {
                if (n.surface().isStreet()) {
                    // The crossing is `width` long; the road runs on either side of it up to that far, plus its own cell.
                    assertTrue(n.roadWidth() >= width && n.roadWidth() <= 2 * width + 1, "width " + width + " at " + n);
                } else {
                    assertEquals(0, n.roadWidth(), "width " + width + " at " + n);
                }
            }
        }
        // Exact readings: in the middle of a 24-long road a 5-block crossing sees 5 blocks of road each way; at the
        // end of the road only the way in has any.
        NavGraph strip = StreetWorld.strip(5, 24).build().graph();
        assertEquals(11, StreetWorld.node(strip, 12, 5).roadWidth());
        assertEquals(6, StreetWorld.node(strip, 0, 5).roadWidth());
        // At a junction the narrowest reading along the crossing is the one kept.
        NavGraph junction = StreetWorld.grid(0, 0, 9, 9).fill(0, 0, 8, 8, 'S').fill(3, 0, 5, 8, 'R').fill(0, 3, 8, 5, 'R')
                .build().graph();
        for (NavGraph.Node n : StreetWorld.street(junction)) {
            assertTrue(n.roadWidth() >= 3, "junction node " + n);
        }
        // A palette with no classes has no street, so nothing is recorded.
        NavGraph legacy = StreetWorld.strip(5, 12).build(false).graph();
        assertTrue(legacy.nodes().stream().allMatch(n -> n.roadWidth() == 0));
    }

    @Test
    void aCorridorKeepsTheRecordedWidths() {
        NavGraph source = tagged(StreetWorld.strip(5, 20).build());
        NavGraph corridor = NavCorridor.compact(source);
        List<NavGraph.Node> street = StreetWorld.street(corridor);
        assertFalse(street.isEmpty());
        assertTrue(street.stream().allMatch(n -> n.roadWidth() > 0 && n.roadWidth() == source.node(n.id()).roadWidth()));
        assertEquals(StreetAudit.Verdict.PASS, StreetAudit.audit(corridor, null, OPTIONS).verdict());
    }

    @Test
    void aNodeCannotHaveANegativeWidth() {
        assertThrows(IllegalArgumentException.class, () -> new NavGraph.Node(1, 0, 64, 0, Set.of(), NavSurface.ROAD, -1));
    }

    @Test
    void aGraphOverTheNodeCapIsInconclusiveAndNothingIsAudited() {
        NavGraph graph = tagged(StreetWorld.strip(5, 20).build());
        var capped = new StreetAudit.Options(RULES, 60_000_000_000L, 1024, System::nanoTime, graph.nodeCount() - 1);
        var result = StreetAudit.audit(graph, null, capped);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertTrue(result.overNodeCap());
        assertEquals(0, result.paths());
        assertEquals(graph.nodeCount(), result.nodes());
        // At the cap it still runs, and passes.
        var atCap = new StreetAudit.Options(RULES, 60_000_000_000L, 1024, System::nanoTime, graph.nodeCount());
        var normal = StreetAudit.audit(graph, null, atCap);
        assertFalse(normal.overNodeCap());
        assertEquals(StreetAudit.Verdict.PASS, normal.verdict());
        // Live classes do not lift the cap.
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, StreetAudit.audit(graph,
                new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of()), capped).verdict());
    }
}
