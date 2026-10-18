package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sweep's report text, the way it packs a cell into one number, and its plan before any world is read. */
class NavSweepReportTest {

    private static NavSweep.Revalidation report(NavSweep.Verdict verdict, List<NavSweep.Drift> drifts) {
        return new NavSweep.Revalidation(verdict, 40, 31, 3, 2, 1, 4, 5, "city", drifts, new int[]{0, 0, 0, 0, 0, 0});
    }

    private static NavSweep.Drift drift(long id, boolean tagged, String detail) {
        return new NavSweep.Drift("CHANGED_CLASS", id, tagged, 10, 64, -20, detail);
    }

    // ---- the log line ----

    @Test
    void theLogLineNamesTheGraphRegionVerdictAndEveryCount() {
        assertEquals("NPCNAV-REVALIDATE graph=g1 region=midtown verdict=DRIFTED ok=31 changed=3 unwalkable=2 blocked=1 footprint=4 "
                + "notloaded=5 palette=city tickets_remaining=0", report(NavSweep.Verdict.DRIFTED, List.of()).logLine("g1", "midtown", 0));
    }

    @Test
    void theLogLineCarriesTheTicketsStillHeld() {
        assertTrue(report(NavSweep.Verdict.CLEAN, List.of()).logLine("g", "r", 7).endsWith("tickets_remaining=7"));
    }

    @ParameterizedTest
    @CsvSource({"CLEAN", "DRIFTED", "INCOMPLETE", "WORLD_MISSING"})
    void everyVerdictIsWrittenByName(NavSweep.Verdict verdict) {
        assertTrue(report(verdict, List.of()).logLine("g", "r", 0).contains(" verdict=" + verdict + " "));
    }

    @Test
    void theVerdictsAreTheFourTheBotKnows() {
        assertEquals(List.of("CLEAN", "DRIFTED", "INCOMPLETE", "WORLD_MISSING"),
                java.util.Arrays.stream(NavSweep.Verdict.values()).map(Enum::name).toList());
    }

    // ---- the detail lines ----

    @Test
    void aDriftIsOneLineWithItsKindNodePositionAndDetail() {
        List<String> lines = report(NavSweep.Verdict.DRIFTED, List.of(drift(7, false, "was SIDEWALK now ROAD"))).lines();
        assertEquals(List.of("CHANGED_CLASS node 7 at 10,64,-20 was SIDEWALK now ROAD"), lines);
    }

    @Test
    void aTaggedNodeIsMarkedBetweenItsPositionAndItsDetail() {
        List<String> lines = report(NavSweep.Verdict.DRIFTED, List.of(drift(7, true, "was SIDEWALK now ROAD"))).lines();
        assertEquals(List.of("CHANGED_CLASS node 7 at 10,64,-20 [tagged] was SIDEWALK now ROAD"), lines);
    }

    @Test
    void noDriftsGiveNoLines() {
        assertEquals(List.of(), report(NavSweep.Verdict.CLEAN, List.of()).lines());
    }

    @Test
    void twentyDriftsAreAllListedWithNothingOmitted() {
        List<NavSweep.Drift> drifts = new ArrayList<>();
        for (int i = 1; i <= 20; i++) drifts.add(drift(i, false, "d"));
        List<String> lines = report(NavSweep.Verdict.DRIFTED, drifts).lines();
        assertEquals(20, lines.size());
        assertEquals("CHANGED_CLASS node 20 at 10,64,-20 d", lines.get(19));
    }

    @Test
    void aTwentyFirstDriftIsCountedAsOmittedAndNotListed() {
        List<NavSweep.Drift> drifts = new ArrayList<>();
        for (int i = 1; i <= 21; i++) drifts.add(drift(i, false, "d"));
        List<String> lines = report(NavSweep.Verdict.DRIFTED, drifts).lines();
        assertEquals(21, lines.size());
        assertEquals("CHANGED_CLASS node 20 at 10,64,-20 d", lines.get(19));
        assertEquals("(1 more omitted)", lines.get(20));
    }

    @Test
    void manyMoreDriftsReportTheExactNumberLeftOut() {
        List<NavSweep.Drift> drifts = new ArrayList<>();
        for (int i = 1; i <= 137; i++) drifts.add(drift(i, false, "d"));
        List<String> lines = report(NavSweep.Verdict.DRIFTED, drifts).lines();
        assertEquals(21, lines.size());
        assertEquals("(117 more omitted)", lines.get(20));
    }

    // ---- cell packing ----

    @ParameterizedTest
    @CsvSource({"0,0,0", "1,1,1", "-1,-1,-1", "30000000,319,-30000000", "-30000000,-64,30000000", "33554431,2047,33554431",
            "-33554432,-2048,-33554432", "16,64,-16", "-17,0,17"})
    void aPackedCellUnpacksToTheSameCoordinates(int x, int y, int z) {
        long cell = NavSweep.pack(x, y, z);
        assertEquals(x, NavSweep.x(cell));
        assertEquals(y, NavSweep.y(cell));
        assertEquals(z, NavSweep.z(cell));
    }

    @Test
    void neighbouringCellsPackToDifferentNumbers() {
        Set<Long> seen = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = 63; y <= 65; y++) {
                for (int z = -2; z <= 2; z++) {
                    assertTrue(seen.add(NavSweep.pack(x, y, z)), x + "," + y + "," + z);
                }
            }
        }
        assertEquals(75, seen.size());
    }

    @Test
    void swappingXAndZMakesADifferentCell() {
        assertTrue(NavSweep.pack(3, 64, 9) != NavSweep.pack(9, 64, 3));
    }

    // ---- planning ----

    private static NavGraph pair() {
        return NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home").addNode(2, 1, 64, 0, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).build();
    }

    @Test
    void aFootprintSweepPlansTheSupportFeetAndHeadCellOfEveryNode() {
        NavSweep sweep = NavSweep.forRevalidation(pair(), "h");
        // Two nodes, each the block below, the feet and the head: 6 cells; the edge adds nothing new at one height.
        assertEquals(6, sweep.total());
        assertEquals(0, sweep.read());
        assertEquals(false, sweep.done());
    }

    @Test
    void aClassSweepPlansOnlyTheSupportCellOfEachNode() {
        NavSweep sweep = NavSweep.forClasses(pair());
        assertEquals(2, sweep.total());
    }

    @Test
    void anEmptyGraphHasNothingToReadSoItIsDoneAtOnce() {
        NavSweep sweep = NavSweep.forRevalidation(NavGraph.builder().build(), null);
        assertEquals(0, sweep.total());
        assertTrue(sweep.done());
    }

    @Test
    void aStepBetweenHeightsAddsTheBlocksInBetweenToTheFootprint() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addNode(2, 1, 65, 0, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).build();
        // Nodes: (0,63..65) and (1,64..66) = 6 cells; the edge spans x 0..1, y 63..66 = 8 cells, 4 of them already planned each side.
        assertEquals(8, NavSweep.forRevalidation(graph, "h").total());
    }

    @Test
    void theChunksASweepNeedsAreTheChunksOfItsCellsInKeyOrder() {
        NavGraph graph = NavGraph.builder().addNode(1, 15, 64, 15, NavSurface.SIDEWALK).addNode(2, 16, 64, 15, NavSurface.SIDEWALK)
                .addNode(3, -1, 64, -1, NavSurface.SIDEWALK).build();
        List<Long> keys = NavSweep.forClasses(graph).chunkKeys();
        assertEquals(List.of(NavChunkLoader.key(-1, -1), NavChunkLoader.key(0, 0), NavChunkLoader.key(1, 0)).stream().sorted().toList(), keys);
    }

    @Test
    void aRevalidationAnswerNeedsAFinishedFootprintSweep() {
        assertThrows(IllegalStateException.class, () -> NavSweep.forRevalidation(pair(), "h").revalidation("h"));
        assertThrows(IllegalStateException.class, () -> NavSweep.forClasses(pair()).revalidation("h"));
    }

    @Test
    void aSweepNeedsAGraph() {
        assertThrows(NullPointerException.class, () -> NavSweep.forRevalidation(null, "h"));
    }
}
