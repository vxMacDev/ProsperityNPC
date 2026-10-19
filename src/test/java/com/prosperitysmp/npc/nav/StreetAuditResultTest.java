package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit's value types, its log line, and the size and start limits it holds itself to. */
class StreetAuditResultTest {
    private static final StreetAudit.Options OPTIONS = new StreetAudit.Options(StreetWorld.RULES, 60_000_000_000L, 1024, System::nanoTime);

    // ---- value types ----

    @Test
    void aDiagonalIsTheSameStepWhicheverNodeIsNamedFirst() {
        assertEquals(new StreetAudit.Diagonal(3, 5), StreetAudit.Diagonal.of(5, 3));
        assertEquals(StreetAudit.Diagonal.of(3, 5), StreetAudit.Diagonal.of(5, 3));
        assertEquals(new StreetAudit.Diagonal(4, 4), StreetAudit.Diagonal.of(4, 4));
        assertEquals(new StreetAudit.Diagonal(-7, 2), StreetAudit.Diagonal.of(2, -7));
    }

    @Test
    void aLiveReadingKeepsItsOwnCopiesOfEverythingItWasGiven() {
        Map<Long, NavSurface> classes = new HashMap<>(Map.of(1L, NavSurface.ROAD));
        Set<Long> off = new HashSet<>(Set.of(2L));
        Map<Long, Integer> extents = new HashMap<>(Map.of(3L, 9));
        Map<Long, String> materials = new HashMap<>(Map.of(4L, "STONE"));
        Set<StreetAudit.Diagonal> roads = new HashSet<>(Set.of(StreetAudit.Diagonal.of(1, 2)));
        Set<StreetAudit.Diagonal> unread = new HashSet<>(Set.of(StreetAudit.Diagonal.of(3, 4)));
        StreetAudit.Live live = new StreetAudit.Live(classes, off, 5, extents, materials, roads, unread);
        classes.clear();
        off.clear();
        extents.clear();
        materials.clear();
        roads.clear();
        unread.clear();
        assertEquals(Map.of(1L, NavSurface.ROAD), live.classes());
        assertEquals(Set.of(2L), live.offPalette());
        assertEquals(Map.of(3L, 9), live.extents());
        assertEquals(Map.of(4L, "STONE"), live.materials());
        assertEquals(Set.of(StreetAudit.Diagonal.of(1, 2)), live.roadCorners());
        assertEquals(Set.of(StreetAudit.Diagonal.of(3, 4)), live.unreadCorners());
        assertThrows(UnsupportedOperationException.class, () -> live.classes().put(9L, NavSurface.ROAD));
        assertThrows(UnsupportedOperationException.class, () -> live.offPalette().add(9L));
    }

    @Test
    void theShortLiveConstructorHasNoDiagonalCorners() {
        StreetAudit.Live live = new StreetAudit.Live(Map.of(), Set.of(), 2, Map.of(), Map.of());
        assertEquals(Set.of(), live.roadCorners());
        assertEquals(Set.of(), live.unreadCorners());
        assertEquals(2, live.unchecked());
    }

    @Test
    void theShortOptionsConstructorUsesTheDefaultNodeCap() {
        assertEquals(50_000, StreetAudit.Options.DEFAULT_MAX_NODES);
        assertEquals(50_000, OPTIONS.maxNodes());
    }

    @Test
    void theShortResultConstructorIsNotOverTheNodeCap() {
        StreetAudit.Result result = new StreetAudit.Result("runtime", StreetAudit.Verdict.PASS, 1, 2, true, 3, 4, 0, 5, Map.of(),
                0, 0, 0, 0, false, false, List.of(), List.of());
        assertFalse(result.overNodeCap());
    }

    @Test
    void countOfACodeNeverSeenIsZero() {
        Map<StreetAudit.Code, Integer> counts = new EnumMap<>(StreetAudit.Code.class);
        counts.put(StreetAudit.Code.LATERAL, 3);
        StreetAudit.Result result = new StreetAudit.Result("runtime", StreetAudit.Verdict.FAIL, 1, 2, true, 3, 4, 1, 5, counts,
                0, 0, 0, 0, false, false, List.of(), List.of());
        assertEquals(3, result.count(StreetAudit.Code.LATERAL));
        assertEquals(0, result.count(StreetAudit.Code.NO_ROUTE));
    }

    @Test
    void theVerdictsAndRuleCodesKeepTheirReportOrder() {
        assertEquals(List.of("PASS", "FAIL", "INCONCLUSIVE"), java.util.Arrays.stream(StreetAudit.Verdict.values()).map(Enum::name).toList());
        assertEquals(List.of("START_ON_STREET", "END_ON_STREET", "STOP_ON_STREET", "NO_SIDEWALK_EXIT", "LATERAL", "TOO_LONG", "ALONG_ROAD",
                        "OFF_PALETTE", "CROSSWALK_SKIPPED", "BUDGET_EXHAUSTED", "NO_ROUTE", "RAISED_SIDEWALK"),
                java.util.Arrays.stream(StreetAudit.Code.values()).map(Enum::name).toList());
    }

    // ---- the log line ----

    @Test
    void theLogLineIsOneLineOfNamedCountsTheBotReads() {
        Map<StreetAudit.Code, Integer> counts = new EnumMap<>(StreetAudit.Code.class);
        counts.put(StreetAudit.Code.LATERAL, 2);
        counts.put(StreetAudit.Code.CROSSWALK_SKIPPED, 1);
        counts.put(StreetAudit.Code.BUDGET_EXHAUSTED, 3);
        StreetAudit.Result result = new StreetAudit.Result("authoring", StreetAudit.Verdict.FAIL, 50, 120, false, 3, 12, 4, 7, counts,
                6, 8, 5, 0, false, false, List.of(), List.of());
        assertEquals("NPCNAV-AUDIT graph=g mode=authoring verdict=FAIL starts=3 paths=12 failed=4 maxRun=7 lateral=2 "
                + "crosswalkSkipped=1 offPalette=5 budget=3 unclassified=6 unchecked=8", StreetAudit.logLine("g", result));
    }

    @Test
    void aCodeWithNoCountIsWrittenAsZeroInTheLogLine() {
        StreetAudit.Result result = new StreetAudit.Result("runtime", StreetAudit.Verdict.PASS, 1, 1, true, 1, 1, 0, 0, Map.of(),
                0, 0, 0, 0, false, false, List.of(), List.of());
        assertEquals("NPCNAV-AUDIT graph=x mode=runtime verdict=PASS starts=1 paths=1 failed=0 maxRun=0 lateral=0 crosswalkSkipped=0 "
                + "offPalette=0 budget=0 unclassified=0 unchecked=0", StreetAudit.logLine("x", result));
    }

    // ---- limits ----

    private static NavGraph nodesOnly(int count) {
        NavGraph.Builder builder = NavGraph.builder();
        for (int i = 1; i <= count; i++) builder.addNode(i, i, 64, 0, NavSurface.SIDEWALK);
        return builder.build();
    }

    @Test
    void aGraphWithMoreNodesThanTheCapIsNotAuditedAtAllAndIsInconclusive() {
        StreetAudit.Options options = new StreetAudit.Options(StreetWorld.RULES, 60_000_000_000L, 1024, System::nanoTime, 4);
        StreetAudit.Result result = StreetAudit.audit(nodesOnly(5), null, options);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertTrue(result.overNodeCap());
        assertEquals("authoring", result.mode());
        assertEquals(5, result.nodes());
        assertEquals(0, result.edges());
        assertFalse(result.withinRuntimeLimits());
        assertEquals(0, result.starts());
        assertEquals(0, result.paths());
        assertEquals(List.of(), result.examples());
        assertEquals(List.of(), result.stops());
    }

    @Test
    void aGraphAtExactlyTheNodeCapIsAudited() {
        StreetAudit.Options options = new StreetAudit.Options(StreetWorld.RULES, 60_000_000_000L, 1024, System::nanoTime, 4);
        StreetAudit.Result result = StreetAudit.audit(nodesOnly(4), null, options);
        assertFalse(result.overNodeCap());
        assertEquals("runtime", result.mode());
        assertTrue(result.withinRuntimeLimits());
    }

    @Test
    void moreStartsThanTheStartCapAreCappedAndTheAuditCannotPass() {
        NavGraph base = StreetWorld.strip(5, 12).build().graph();
        NavGraph tagged = StreetWorld.tag(base, Map.of(StreetWorld.node(base, 2, 1).id(), "home", StreetWorld.node(base, 9, 9).id(), "work"));
        StreetAudit.Options capped = new StreetAudit.Options(StreetWorld.RULES, 60_000_000_000L, 2, System::nanoTime);
        StreetAudit.Result result = StreetAudit.audit(tagged, null, capped);
        assertTrue(result.startsCapped());
        assertEquals(2, result.starts());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        StreetAudit.Result full = StreetAudit.audit(tagged, null, OPTIONS);
        assertFalse(full.startsCapped());
        assertEquals(StreetAudit.Verdict.PASS, full.verdict());
    }

    @Test
    void aStartCapOfExactlyTheNumberOfStartsIsNotACap() {
        NavGraph base = StreetWorld.strip(5, 4).build().graph();
        NavGraph tagged = StreetWorld.tag(base, Map.of(StreetWorld.node(base, 1, 1).id(), "home", StreetWorld.node(base, 2, 9).id(), "work"));
        long walkable = tagged.nodes().stream().filter(n -> !n.surface().isStreet()).count();
        StreetAudit.Options exact = new StreetAudit.Options(StreetWorld.RULES, 60_000_000_000L, (int) walkable, System::nanoTime);
        StreetAudit.Result result = StreetAudit.audit(tagged, null, exact);
        assertFalse(result.startsCapped());
        assertEquals((int) walkable, result.starts());
    }

    // ---- stops and unclassified counts ----

    @Test
    void everyTaggedNodeIsListedWithItsSortedTagsItsSupportBlockAndItsClass() {
        NavGraph base = StreetWorld.strip(5, 6).build().graph();
        long home = StreetWorld.node(base, 1, 1).id();
        NavGraph tagged = StreetWorld.tag(base, Map.of(home, "home"));
        StreetAudit.Result without = StreetAudit.audit(tagged, null, OPTIONS);
        assertEquals(List.of("node " + home + " [home] support=? class=SIDEWALK"), without.stops());
        StreetAudit.Live live = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of(home, "STONE_BRICKS"));
        StreetAudit.Result with = StreetAudit.audit(tagged, live, OPTIONS);
        assertEquals(List.of("node " + home + " [home] support=STONE_BRICKS class=SIDEWALK"), with.stops());
    }

    @Test
    void aGraphOfUnclassifiedNodesCountsThemAllAndCannotPass() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "home").addNode(2, 1, 64, 0, "work").addBiEdge(1, 2, 10).build();
        StreetAudit.Result result = StreetAudit.audit(graph, null, OPTIONS);
        assertEquals(2, result.unclassified());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
    }

    @Test
    void aNodeLiveReadingsPutOffPaletteIsNotCountedAsUnclassified() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "home").addNode(2, 1, 64, 0, "work").addBiEdge(1, 2, 10).build();
        StreetAudit.Live live = new StreetAudit.Live(Map.of(), Set.of(2L), 0, Map.of(), Map.of());
        StreetAudit.Result result = StreetAudit.audit(graph, live, OPTIONS);
        assertEquals(1, result.unclassified());
        assertEquals(1, result.offPaletteNodes());
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.examples().stream().anyMatch(v -> v.code() == StreetAudit.Code.OFF_PALETTE));
    }

    @Test
    void aLiveClassOverridesTheStoredOneForTheStopsOnStreetCount() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home").addNode(2, 1, 64, 0, NavSurface.SIDEWALK, "work")
                .addBiEdge(1, 2, 10).build();
        StreetAudit.Live live = new StreetAudit.Live(Map.of(1L, NavSurface.ROAD), Set.of(), 0, Map.of(), Map.of());
        StreetAudit.Result result = StreetAudit.audit(graph, live, OPTIONS);
        assertEquals(1, result.stopsOnStreet());
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertEquals(0, StreetAudit.audit(graph, null, OPTIONS).stopsOnStreet());
    }
}
