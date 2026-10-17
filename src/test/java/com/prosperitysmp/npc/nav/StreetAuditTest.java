package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.StreetAudit.Code;
import com.prosperitysmp.npc.resident.ResidentRoutePlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.prosperitysmp.npc.nav.StreetWorld.RULES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit rules one by one on hand-built paths: each code fires on its fixture and not on the clean twin. */
class StreetAuditTest {
    private static final StreetAudit.Options OPTIONS = new StreetAudit.Options(RULES, 60_000_000_000L, 1024, System::nanoTime);

    /** Tiny graph builder: nodes at y 64, straight bidirectional links of cost 10. */
    private static final class G {
        final NavGraph.Builder b = NavGraph.builder();

        G n(long id, int x, int z, NavSurface surface, String... tags) {
            b.addNode(id, x, 64, z, surface, tags);
            return this;
        }

        G link(long a, long c) {
            b.addBiEdge(a, c, 10);
            return this;
        }

        NavGraph build() {
            return b.build();
        }
    }

    /** sidewalk 1 - road 2,3 - sidewalk 4 in a straight line along z, as a straight crossing is built. */
    private static G straightCrossing() {
        return new G().n(1, 0, 0, NavSurface.SIDEWALK).n(2, 0, 1, NavSurface.ROAD).n(3, 0, 2, NavSurface.ROAD)
                .n(4, 0, 3, NavSurface.SIDEWALK).link(1, 2).link(2, 3).link(3, 4);
    }

    private static Set<Code> codes(NavGraph g, List<Long> path) {
        return StreetAudit.checkPath(g, null, OPTIONS, path);
    }

    @Test
    void aStraightCrossingFromSidewalkToSidewalkBreaksNoRule() {
        assertEquals(Set.of(), codes(straightCrossing().build(), List.of(1L, 2L, 3L, 4L)));
        assertEquals(Set.of(), codes(straightCrossing().build(), List.of(4L, 3L, 2L, 1L)));
    }

    @Test
    void startingOrEndingOnTheStreetIsCaught() {
        NavGraph g = straightCrossing().build();
        assertEquals(Set.of(Code.START_ON_STREET), codes(g, List.of(2L, 3L, 4L)));
        assertEquals(Set.of(Code.END_ON_STREET), codes(g, List.of(1L, 2L, 3L)));
    }

    @Test
    void aCrossingNotEnteredAndLeftFromASidewalkIsCaught() {
        NavGraph bad = new G().n(7, 5, 0, NavSurface.ACCESS).n(8, 5, 1, NavSurface.ROAD).n(9, 5, 2, NavSurface.SIDEWALK)
                .link(7, 8).link(8, 9).build();
        assertEquals(Set.of(Code.NO_SIDEWALK_EXIT), codes(bad, List.of(7L, 8L, 9L)));
        NavGraph twin = new G().n(7, 5, 0, NavSurface.SIDEWALK).n(8, 5, 1, NavSurface.ROAD).n(9, 5, 2, NavSurface.SIDEWALK)
                .link(7, 8).link(8, 9).build();
        assertEquals(Set.of(), codes(twin, List.of(7L, 8L, 9L)));
        NavGraph leaving = new G().n(7, 5, 0, NavSurface.SIDEWALK).n(8, 5, 1, NavSurface.ROAD).n(9, 5, 2, NavSurface.ACCESS)
                .link(7, 8).link(8, 9).build();
        assertEquals(Set.of(Code.NO_SIDEWALK_EXIT), codes(leaving, List.of(7L, 8L, 9L)));
    }

    @Test
    void turningOnTheRoadOrCuttingDiagonallyIsLateral() {
        NavGraph turn = new G().n(1, 0, 0, NavSurface.SIDEWALK).n(2, 0, 1, NavSurface.ROAD).n(3, 1, 1, NavSurface.ROAD)
                .n(4, 1, 2, NavSurface.SIDEWALK).link(1, 2).link(2, 3).link(3, 4).build();
        assertEquals(Set.of(Code.LATERAL), codes(turn, List.of(1L, 2L, 3L, 4L)));
        NavGraph.Builder diagonal = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK)
                .addNode(2, 1, 64, 1, NavSurface.ROAD).addNode(3, 2, 64, 2, NavSurface.SIDEWALK)
                .addEdge(1, 2, 14).addEdge(2, 3, 14);
        assertEquals(Set.of(Code.LATERAL), codes(diagonal.build(), List.of(1L, 2L, 3L)));
        // Walking along the road is the same offence: two steps one way then back is not a straight crossing.
        NavGraph back = new G().n(1, 0, 0, NavSurface.SIDEWALK).n(2, 0, 1, NavSurface.ROAD).n(3, 0, 0, NavSurface.SIDEWALK)
                .link(1, 2).link(2, 3).build();
        assertEquals(Set.of(Code.LATERAL), codes(back, List.of(1L, 2L, 3L)));
    }

    /** A 2x2 sidewalk square with the opposite corners tagged, so the cheapest route is the diagonal between them. */
    private static NavGraph sidewalkSquare() {
        NavGraph base = StreetWorld.grid(0, 0, 2, 2).fill(0, 0, 1, 1, 'S').build().graph();
        return StreetWorld.tag(base, Map.of(StreetWorld.node(base, 0, 0).id(), "home", StreetWorld.node(base, 1, 1).id(), "work"));
    }

    @Test
    void aDiagonalStepPastARoadCornerIsLateralEvenWhenNoRoadNodeIsOnThePath() {
        NavGraph square = sidewalkSquare();
        long corner1 = StreetWorld.node(square, 1, 0).id();
        long corner2 = StreetWorld.node(square, 0, 1).id();
        long home = StreetWorld.node(square, 0, 0).id();
        long work = StreetWorld.node(square, 1, 1).id();
        // The world now shows both orthogonal corners as road; the walk between the sidewalk corners still goes straight over them.
        var road = new StreetAudit.Live(Map.of(corner1, NavSurface.ROAD, corner2, NavSurface.ROAD), Set.of(), 0,
                Map.of(corner1, 99), Map.of());
        assertEquals(Set.of(Code.LATERAL), StreetAudit.checkPath(square, road, OPTIONS, List.of(home, work)));
        var result = StreetAudit.audit(square, road, OPTIONS);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.count(Code.LATERAL) > 0);
        assertTrue(result.examples().stream().anyMatch(v -> v.code() == Code.LATERAL && v.detail().contains("corner")),
                result.examples().toString());
        // Control: with one corner road the diagonal is still cutting it, and with none it is fine.
        var oneRoad = new StreetAudit.Live(Map.of(corner1, NavSurface.ROAD), Set.of(), 0, Map.of(corner1, 99), Map.of());
        assertEquals(Set.of(Code.LATERAL), StreetAudit.checkPath(square, oneRoad, OPTIONS, List.of(home, work)));
        assertEquals(Set.of(), StreetAudit.checkPath(square, null, OPTIONS, List.of(home, work)));
        assertEquals(StreetAudit.Verdict.PASS, StreetAudit.audit(square, null, OPTIONS).verdict());
        // A road corner at another height is not under the diagonal.
        NavGraph.Builder lifted = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home")
                .addNode(2, 1, 64, 1, NavSurface.SIDEWALK, "work").addNode(3, 1, 80, 0, NavSurface.ROAD)
                .addNode(4, 0, 64, 1, NavSurface.SIDEWALK).addBiEdge(1, 2, 14);
        assertEquals(Set.of(), StreetAudit.checkPath(lifted.build(), null, OPTIONS, List.of(1L, 2L)));
    }

    @Test
    void aDiagonalStepWhoseCornersTheWorldShowsAsRoadIsLateralAndOneItCouldNotReadIsUnchecked() {
        // Only the two ends of the diagonal are in the graph; what stands beside them comes from the world reading.
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home")
                .addNode(2, 1, 64, 1, NavSurface.SIDEWALK, "work").addBiEdge(1, 2, 14).build();
        var step = StreetAudit.Diagonal.of(2, 1);
        assertEquals(step, StreetAudit.Diagonal.of(1, 2), "the step is the same either way round");
        var road = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of(), Set.of(step), Set.of());
        assertEquals(Set.of(Code.LATERAL), StreetAudit.checkPath(graph, road, OPTIONS, List.of(1L, 2L)));
        assertEquals(Set.of(Code.LATERAL), StreetAudit.checkPath(graph, road, OPTIONS, List.of(2L, 1L)));
        assertEquals(StreetAudit.Verdict.FAIL, StreetAudit.audit(graph, road, OPTIONS).verdict());

        var unread = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of(), Set.of(), Set.of(step));
        var result = StreetAudit.audit(graph, unread, OPTIONS);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(1, result.unchecked(), "one step, however many routes take it");
        // Road shown at one corner is a failure even though the other could not be read.
        var both = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of(), Set.of(step), Set.of(step));
        assertEquals(StreetAudit.Verdict.FAIL, StreetAudit.audit(graph, both, OPTIONS).verdict());
        // Control: a world reading that shows both corners clear passes.
        var clear = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of());
        assertEquals(StreetAudit.Verdict.PASS, StreetAudit.audit(graph, clear, OPTIONS).verdict());
    }

    @Test
    void aRunLongerThanTheLimitIsTooLongAndOneAtTheLimitIsFine() {
        for (int length : new int[]{RULES.maxCrossingLength(), RULES.maxCrossingLength() + 1}) {
            G g = new G().n(1000, 0, 0, NavSurface.SIDEWALK);
            List<Long> path = new ArrayList<>(List.of(1000L));
            for (int i = 1; i <= length; i++) {
                g.n(1000 + i, 0, i, NavSurface.ROAD).link(1000 + i - 1, 1000 + i);
                path.add(1000L + i);
            }
            g.n(2000, 0, length + 1, NavSurface.SIDEWALK).link(1000 + length, 2000);
            path.add(2000L);
            assertEquals(length > RULES.maxCrossingLength() ? Set.of(Code.TOO_LONG) : Set.of(),
                    codes(g.build(), path), "length " + length);
        }
    }

    @Test
    void aRunLongerThanTheRoadIsWideIsAlongTheRoadWhenTheWorldSaysSo() {
        G g = new G().n(1, 0, 0, NavSurface.SIDEWALK);
        for (int i = 1; i <= 5; i++) g.n(1 + i, 0, i, NavSurface.ROAD).link(i, 1 + i);
        g.n(7, 0, 6, NavSurface.SIDEWALK).link(6, 7);
        NavGraph graph = g.build();
        List<Long> path = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        long middle = 4L; // the middle cell by position: (0,3)
        var narrow = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(middle, 3), Map.of());
        var wide = new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(middle, 5), Map.of());
        assertEquals(Set.of(Code.ALONG_ROAD), StreetAudit.checkPath(graph, narrow, OPTIONS, path));
        assertEquals(Set.of(), StreetAudit.checkPath(graph, wide, OPTIONS, path));
        assertEquals(Set.of(), codes(graph, path), "one path is judged on the world's reading; the graph rule is separate");
    }

    @Test
    void aNodeOnABlockInNoClassIsOffPalette() {
        NavGraph g = straightCrossing().build();
        var off = new StreetAudit.Live(Map.of(), Set.of(4L), 0, Map.of(), Map.of());
        assertEquals(Set.of(Code.OFF_PALETTE), StreetAudit.checkPath(g, off, OPTIONS, List.of(1L, 2L, 3L, 4L)));
        assertEquals(Set.of(), StreetAudit.checkPath(g, off, OPTIONS, List.of(1L, 2L, 3L)).stream()
                .filter(c -> c == Code.OFF_PALETTE).collect(java.util.stream.Collectors.toSet()));
    }

    private static NavGraph streetWithCrosswalkAt(int crosswalkX) {
        return StreetWorld.strip(5, 40).fill(crosswalkX, 3, crosswalkX, 7, 'W').build().graph();
    }

    private static List<Long> straightPath(NavGraph g, int x) {
        return List.of(StreetWorld.node(g, x, 2).id(), StreetWorld.node(g, x, 3).id(), StreetWorld.node(g, x, 4).id(),
                StreetWorld.node(g, x, 5).id(), StreetWorld.node(g, x, 6).id(), StreetWorld.node(g, x, 7).id(),
                StreetWorld.node(g, x, 8).id());
    }

    @Test
    void aPlainCrossingBesideAClosePaintedOneIsCrosswalkSkipped() {
        // Crosswalk 3 blocks away: 30 + 60 + 30 = 120 against 240 + 60 for the plain crossing.
        NavGraph near = streetWithCrosswalkAt(3);
        assertEquals(Set.of(Code.CROSSWALK_SKIPPED), codes(near, straightPath(near, 0)));
        // Crosswalk 14 blocks away: 140 + 60 + 140 = 340, more than 300, so the plain crossing was right.
        NavGraph far = streetWithCrosswalkAt(14);
        assertEquals(Set.of(), codes(far, straightPath(far, 0)));
        // Using the painted crossing itself breaks nothing.
        assertEquals(Set.of(), codes(near, straightPath(near, 3)));
        // The boundary follows the configured reach: with reach 15 the crosswalk 14 away is worth it.
        var generous = new StreetAudit.Options(new StreetRules(15, 16, 0.4), 60_000_000_000L, 1024, System::nanoTime);
        NavGraph farRescanned = StreetWorld.strip(5, 40).fill(14, 3, 14, 7, 'W').build()
                .scan(new StreetRules(15, 16, 0.4)).graph();
        assertEquals(Set.of(Code.CROSSWALK_SKIPPED), StreetAudit.checkPath(farRescanned, null, generous,
                straightPath(farRescanned, 0)));
    }

    @Test
    void plannerFailuresAreRuleBreaks() {
        NavGraph g = straightCrossing().build();
        assertEquals(Set.of(Code.BUDGET_EXHAUSTED), StreetAudit.checkPath(g, null, OPTIONS,
                ResidentRoutePlanner.Status.BUDGET_EXHAUSTED, List.of()));
        assertEquals(Set.of(Code.NO_ROUTE), StreetAudit.checkPath(g, null, OPTIONS,
                ResidentRoutePlanner.Status.NO_ROUTE, List.of()));
    }

    // ---- whole-graph verdicts ----

    private static NavGraph cleanCity() {
        NavGraph base = StreetWorld.strip(5, 20).build().graph();
        return StreetWorld.tag(base, Map.of(StreetWorld.node(base, 2, 1).id(), "home",
                StreetWorld.node(base, 14, 9).id(), "work"));
    }

    @Test
    void aCleanClassifiedGraphPassesAndCountsWhatItChecked() {
        var result = StreetAudit.audit(cleanCity(), null, OPTIONS);
        assertEquals(StreetAudit.Verdict.PASS, result.verdict(), () -> result.examples().toString());
        assertEquals("runtime", result.mode());
        assertTrue(result.withinRuntimeLimits());
        assertTrue(result.starts() > 50 && result.paths() == result.starts() * 2, result.starts() + " starts " + result.paths() + " paths");
        assertEquals(5, result.maxRun());
        assertEquals(0, result.failedPaths());
        assertEquals(0, result.unclassified());
        assertTrue(StreetAudit.logLine("g", result).startsWith("NPCNAV-AUDIT graph=g mode=runtime verdict=PASS starts="));
        assertTrue(result.stops().stream().anyMatch(s -> s.contains("[home]") && s.contains("class=SIDEWALK")));
    }

    @Test
    void anUnclassifiedGraphIsInconclusiveNeverPass() {
        NavGraph base = StreetWorld.strip(5, 20).build(false).graph();
        NavGraph g = StreetWorld.tag(base, Map.of(StreetWorld.node(base, 2, 1).id(), "home"));
        var result = StreetAudit.audit(g, null, OPTIONS);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(g.nodeCount(), result.unclassified());
        assertEquals(0, result.failedPaths());
    }

    /** A width reading of 99 for every crossing in the graph, as a sweep that read all of them would report. */
    private static Map<Long, Integer> widths(NavGraph graph) {
        Map<Long, Integer> out = new java.util.HashMap<>();
        for (StreetChains.Chain chain : StreetChains.find(graph, id -> graph.node(id).surface())) {
            out.put(chain.mid(), 99);
        }
        return out;
    }

    @Test
    void unreadWorldCellsMakeALiveAuditInconclusive() {
        NavGraph city = cleanCity();
        var live = new StreetAudit.Live(Map.of(), Set.of(), 3, widths(city), Map.of());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, StreetAudit.audit(city, live, OPTIONS).verdict());
        assertEquals(StreetAudit.Verdict.PASS, StreetAudit.audit(city,
                new StreetAudit.Live(Map.of(), Set.of(), 0, widths(city), Map.of()), OPTIONS).verdict());
    }

    @Test
    void aCrossingWithNoWidthReadingIsUncheckedAndNeverPasses() {
        NavGraph city = cleanCity();
        Map<Long, Integer> widths = widths(city);
        int crossings = widths.size();
        assertTrue(crossings > 3);
        var none = StreetAudit.audit(city, new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of()), OPTIONS);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, none.verdict());
        assertEquals(crossings, none.unchecked());
        assertTrue(StreetAudit.logLine("g", none).endsWith("unchecked=" + crossings));
        // One reading short is one unchecked crossing.
        Map<Long, Integer> short1 = new java.util.HashMap<>(widths);
        short1.remove(short1.keySet().iterator().next());
        var one = StreetAudit.audit(city, new StreetAudit.Live(Map.of(), Set.of(), 0, short1, Map.of()), OPTIONS);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, one.verdict());
        assertEquals(1, one.unchecked());
        // Unread node cells and missing readings both count.
        var both = StreetAudit.audit(city, new StreetAudit.Live(Map.of(), Set.of(), 2, short1, Map.of()), OPTIONS);
        assertEquals(3, both.unchecked());
        // A rule that is broken is still a FAIL, whatever else could not be checked.
        NavGraph lateral = lateralFromAnUntaggedSidewalk();
        assertEquals(StreetAudit.Verdict.FAIL, StreetAudit.audit(lateral,
                new StreetAudit.Live(Map.of(), Set.of(), 0, Map.of(), Map.of()), OPTIONS).verdict());
    }

    @Test
    void aGraphWithNoTagsHasNothingToAuditSoItIsInconclusive() {
        var result = StreetAudit.audit(StreetWorld.strip(5, 20).build().graph(), null, OPTIONS);
        assertEquals(0, result.paths());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
    }

    @Test
    void theTimeLimitStopsTheAuditAndNeverPasses() {
        AtomicLong ticks = new AtomicLong();
        var slow = new StreetAudit.Options(RULES, 5_000_000L, 1024, () -> ticks.addAndGet(1_000_000L));
        var result = StreetAudit.audit(cleanCity(), null, slow);
        assertTrue(result.timedOut());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertTrue(result.paths() < 100);
    }

    /** Reads 0 everywhere except when asked from inside one of the named methods, where it is far past any limit. */
    private static LongSupplier lateIn(String... methods) {
        Set<String> names = Set.of(methods);
        return () -> StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> names.contains(f.getMethodName())))
                ? 1_000_000_000_000L : 0L;
    }

    private static NavGraph streetWithOnlyAFarCrosswalk() {
        NavGraph g = streetWithCrosswalkAt(14);
        return StreetWorld.tag(g, Map.of(StreetWorld.node(g, 0, 2).id(), "home", StreetWorld.node(g, 0, 8).id(), "work"));
    }

    @Test
    void theDeadlineIsCheckedInsideTheCrosswalkAlternativeSearchesNotOnlyBetweenPaths() {
        NavGraph g = streetWithOnlyAFarCrosswalk();
        var control = StreetAudit.audit(g, null, OPTIONS);
        assertEquals(StreetAudit.Verdict.PASS, control.verdict(), () -> control.examples().toString());
        assertFalse(control.timedOut());
        var slow = new StreetAudit.Options(RULES, 5_000_000_000L, 1024, lateIn("checkCrosswalkSkipped", "sidewalkDistances"));
        var result = StreetAudit.audit(g, null, slow);
        assertTrue(result.timedOut());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertTrue(result.paths() < control.paths(), "it stops searching once the time is up: " + result.paths());
    }

    @Test
    void preparationCountsAgainstTheClockAndTheClockStartsBeforeIt() {
        java.util.List<String> callers = new ArrayList<>();
        LongSupplier recording = () -> {
            callers.add(StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getMethodName)
                    .filter(name -> name.equals("run") || name.equals("prepare")).findFirst().orElse("other")));
            return 0L;
        };
        StreetAudit.audit(cleanCity(), null, new StreetAudit.Options(RULES, 60_000_000_000L, 1024, recording));
        assertEquals("run", callers.getFirst(), "the clock is read before preparation begins");
        assertTrue(callers.contains("prepare"), "and again while preparing");
        // A slow preparation alone gives an inconclusive audit that never started searching.
        var slow = new StreetAudit.Options(RULES, 5_000_000_000L, 1024, lateIn("prepare"));
        var result = StreetAudit.audit(cleanCity(), null, slow);
        assertTrue(result.timedOut());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(0, result.paths());
    }

    @Test
    void theDeadlineIsCheckedOnceMoreBeforeTheVerdict() {
        AtomicLong calls = new AtomicLong();
        var counting = new StreetAudit.Options(RULES, 60_000_000_000L, 1024, () -> {
            calls.incrementAndGet();
            return 0L;
        });
        var control = StreetAudit.audit(cleanCity(), null, counting);
        assertEquals(StreetAudit.Verdict.PASS, control.verdict());
        long last = calls.get();
        // Only the very last reading is late: every path was already analysed when time ran out.
        AtomicLong seen = new AtomicLong();
        var lateAtTheEnd = new StreetAudit.Options(RULES, 5_000_000_000L, 1024,
                () -> seen.incrementAndGet() == last ? 1_000_000_000_000L : 0L);
        var result = StreetAudit.audit(cleanCity(), null, lateAtTheEnd);
        assertEquals(control.paths(), result.paths(), "every path was analysed");
        assertTrue(result.timedOut());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
    }

    @Test
    void moreStartsThanTheCapIsInconclusive() {
        var small = new StreetAudit.Options(RULES, 60_000_000_000L, 10, System::nanoTime);
        var result = StreetAudit.audit(cleanCity(), null, small);
        assertTrue(result.startsCapped());
        assertEquals(10, result.starts());
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
    }

    @Test
    void aBrokenRuleOutranksAnythingUnchecked() {
        // Failing is a definite answer; being unable to finish is not.
        NavGraph g = lateralFromAnUntaggedSidewalk();
        var live = new StreetAudit.Live(Map.of(), Set.of(), 5, Map.of(), Map.of());
        assertEquals(StreetAudit.Verdict.FAIL, StreetAudit.audit(g, live, OPTIONS).verdict());
    }

    /** Stops T1 (home) and T2 (work) are joined by sidewalk; untagged C can only reach them by turning on a road. */
    private static NavGraph lateralFromAnUntaggedSidewalk() {
        return new G().n(1, 0, 0, NavSurface.SIDEWALK, "home").n(2, 0, -1, NavSurface.SIDEWALK)
                .n(3, 0, -2, NavSurface.SIDEWALK, "work").link(1, 2).link(2, 3)
                .n(10, 1, 2, NavSurface.SIDEWALK).n(11, 1, 1, NavSurface.ROAD).n(12, 0, 1, NavSurface.ROAD)
                .link(10, 11).link(11, 12).link(12, 1).build();
    }

    @Test
    void theAuditStartsFromEverySidewalkNodeNotJustFromTheStops() {
        NavGraph g = lateralFromAnUntaggedSidewalk();
        // From stop to stop the graph is clean: the only fault is reachable from the untagged node.
        assertEquals(Set.of(), codes(g, List.of(1L, 2L, 3L)));
        var result = StreetAudit.audit(g, null, OPTIONS);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.count(Code.LATERAL) > 0);
        assertTrue(result.examples().stream().anyMatch(v -> v.detail().contains("from node 10")));
    }

    @Test
    void aStopOnTheRoadFailsTheGraph() {
        NavGraph g = new G().n(1, 0, 0, NavSurface.SIDEWALK, "home").n(2, 0, 1, NavSurface.ROAD, "work")
                .n(3, 0, 2, NavSurface.SIDEWALK).link(1, 2).link(2, 3).build();
        var result = StreetAudit.audit(g, null, OPTIONS);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertEquals(1, result.stopsOnStreet());
        assertTrue(result.count(Code.END_ON_STREET) > 0);
    }

    @Test
    void aSidewalkNodeReachedOnlyByStepsUpIsAWarningNotAFailure() {
        NavGraph.Builder b = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home")
                .addNode(2, 1, 64, 0, NavSurface.SIDEWALK).addNode(3, 2, 65, 0, NavSurface.SIDEWALK, "work")
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10);
        var result = StreetAudit.audit(b.build(), null, OPTIONS);
        assertEquals(1, result.count(Code.RAISED_SIDEWALK));
        assertEquals(StreetAudit.Verdict.PASS, result.verdict());
        assertEquals(0, result.failedPaths());
    }

    @Test
    void aLargeDraftIsAuditedBetweenAnchorsAndNeverFromEveryNode() {
        // 1,100 nodes in a line is over the 1,024 runtime limit: authoring mode, anchor to anchor.
        G g = new G();
        for (int i = 0; i < 1100; i++) {
            g.n(i + 1, i, 0, NavSurface.SIDEWALK, i == 0 ? new String[]{"home"} : i == 1099 ? new String[]{"work"} : new String[0]);
            if (i > 0) g.link(i, i + 1);
        }
        var result = StreetAudit.audit(g.build(), null, OPTIONS);
        assertEquals("authoring", result.mode());
        assertFalse(result.withinRuntimeLimits());
        assertEquals(2, result.starts());
        assertEquals(2, result.paths(), "both ordered pairs");
        assertEquals(StreetAudit.Verdict.PASS, result.verdict());
    }
}
