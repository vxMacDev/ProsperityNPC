package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a revalidation counts and says for each way a stored graph can differ from the world, one way at a time. */
class NavSweepRevalidationRulesTest {
    private static final int AIR = NavTerrainCapture.CLEAR;
    private static final int SOLID_SIDEWALK = NavTerrainCapture.SUPPORT | NavSweep.SOLID | NavSurface.SIDEWALK.code() << NavTerrainCapture.SURFACE_SHIFT;
    private static final int SOLID_ROAD = NavTerrainCapture.SUPPORT | NavSweep.SOLID | NavSurface.ROAD.code() << NavTerrainCapture.SURFACE_SHIFT;
    private static final int SOLID_PAINT = NavTerrainCapture.SUPPORT | NavSweep.SOLID | NavSurface.CROSSING.code() << NavTerrainCapture.SURFACE_SHIFT;
    private static final int SOLID_IN_PALETTE_NO_CLASS = NavTerrainCapture.SUPPORT | NavSweep.SOLID;
    private static final int SOLID_NOT_IN_PALETTE = NavSweep.SOLID;
    private static final int NOT_LOADED = 0x80;

    /** The world as a map of cells; every cell not listed is open air. */
    private static final class World {
        private final Map<Long, Integer> cells = new HashMap<>();

        World set(int x, int y, int z, int value) {
            cells.put(NavSweep.pack(x, y, z), value);
            return this;
        }

        /** The flat ground under node (x, 64, z): a solid sidewalk block at y 63 and open air above it. */
        World ground(int x, int z) {
            return set(x, 63, z, SOLID_SIDEWALK);
        }

        NavSweep.Reader reader() {
            return (x, y, z) -> (byte) cells.getOrDefault(NavSweep.pack(x, y, z), AIR).intValue();
        }
    }

    private static NavSweep.Revalidation run(NavSweep sweep, World world, String currentHash) {
        while (!sweep.step(world.reader(), 4096, Long.MAX_VALUE, () -> 0L)) {
            // read every planned cell
        }
        return sweep.revalidation(currentHash);
    }

    private static NavGraph one(NavSurface surface, String... tags) {
        return NavGraph.builder().addNode(1, 0, 64, 0, surface, tags).build();
    }

    private static NavSweep.Revalidation revalidate(NavGraph graph, World world) {
        return run(NavSweep.forRevalidation(graph, "h"), world, "h");
    }

    // ---- a single node ----

    @Test
    void aNodeOnItsStoredClassWithRoomAboveItIsOkAndTheGraphIsClean() {
        NavSweep.Revalidation report = revalidate(one(NavSurface.SIDEWALK), new World().ground(0, 0));
        assertEquals(NavSweep.Verdict.CLEAN, report.verdict());
        assertEquals(1, report.nodes());
        assertEquals(1, report.ok());
        assertEquals(0, report.changedClass() + report.unwalkable() + report.blocked() + report.footprint() + report.notLoaded());
        assertEquals("same", report.palette());
        assertEquals(List.of(), report.drifts());
        assertNull(report.driftBox());
    }

    @Test
    void aSupportThatIsNoLongerSolidIsUnwalkable() {
        NavSweep.Revalidation report = revalidate(one(NavSurface.SIDEWALK), new World());
        assertEquals(NavSweep.Verdict.DRIFTED, report.verdict());
        assertEquals(1, report.unwalkable());
        assertEquals(0, report.ok());
        assertEquals(List.of("UNWALKABLE node 1 at 0,64,0 nothing solid underneath"), report.lines());
        assertArrayEquals(new int[]{0, 64, 0, 0, 64, 0}, report.driftBox());
    }

    @Test
    void aSupportOfAnotherClassIsAChangedClassNamingBoth() {
        NavSweep.Revalidation report = revalidate(one(NavSurface.SIDEWALK), new World().set(0, 63, 0, SOLID_ROAD));
        assertEquals(1, report.changedClass());
        assertEquals(List.of("CHANGED_CLASS node 1 at 0,64,0 stored SIDEWALK now ROAD"), report.lines());
        assertEquals(0, report.ok());
    }

    @Test
    void aSolidBlockInNoClassReadsAsNoClass() {
        NavSweep.Revalidation report = revalidate(one(NavSurface.SIDEWALK), new World().set(0, 63, 0, SOLID_NOT_IN_PALETTE));
        assertEquals(List.of("CHANGED_CLASS node 1 at 0,64,0 stored SIDEWALK now no class"), report.lines());
    }

    @Test
    void anUnclassifiedNodeOnlyNeedsItsBlockToStillBeInThePalette() {
        NavSweep.Revalidation gone = revalidate(one(NavSurface.UNKNOWN), new World().set(0, 63, 0, SOLID_NOT_IN_PALETTE));
        assertEquals(1, gone.unwalkable());
        assertEquals(List.of("UNWALKABLE node 1 at 0,64,0 the block underneath is no longer in the palette"), gone.lines());
        NavSweep.Revalidation kept = revalidate(one(NavSurface.UNKNOWN), new World().set(0, 63, 0, SOLID_IN_PALETTE_NO_CLASS));
        assertEquals(NavSweep.Verdict.CLEAN, kept.verdict());
        assertEquals(1, kept.ok());
    }

    @Test
    void aBlockInTheWayOfTheFeetOrTheHeadIsBlockedOnce() {
        for (int y : new int[]{64, 65}) {
            NavSweep.Revalidation report = revalidate(one(NavSurface.SIDEWALK), new World().ground(0, 0).set(0, y, 0, SOLID_NOT_IN_PALETTE));
            assertEquals(1, report.blocked(), "block at " + y);
            assertEquals(List.of("BLOCKED node 1 at 0,64,0 no longer clear overhead"), report.lines());
        }
        NavSweep.Revalidation both = revalidate(one(NavSurface.SIDEWALK),
                new World().ground(0, 0).set(0, 64, 0, SOLID_NOT_IN_PALETTE).set(0, 65, 0, SOLID_NOT_IN_PALETTE));
        assertEquals(1, both.blocked());
        assertEquals(1, both.drifts().size());
    }

    @Test
    void aNodeBothChangedAndBlockedGivesTwoDriftsBlockedFirstAndCountsAsNeitherOk() {
        NavSweep.Revalidation report = revalidate(one(NavSurface.SIDEWALK),
                new World().set(0, 63, 0, SOLID_ROAD).set(0, 65, 0, SOLID_NOT_IN_PALETTE));
        assertEquals(1, report.changedClass());
        assertEquals(1, report.blocked());
        assertEquals(0, report.ok());
        assertEquals(List.of("BLOCKED node 1 at 0,64,0 no longer clear overhead", "CHANGED_CLASS node 1 at 0,64,0 stored SIDEWALK now ROAD"),
                report.lines());
    }

    @ParameterizedTest
    @CsvSource({"63", "64", "65"})
    void aCellNotLoadedUnderTheNodeOrAtItsFeetOrHeadMakesTheNodeNotLoadedNotDrifted(int y) {
        NavSweep.Revalidation report = revalidate(one(NavSurface.SIDEWALK), new World().ground(0, 0).set(0, y, 0, NOT_LOADED));
        assertEquals(1, report.notLoaded());
        assertEquals(0, report.ok());
        assertEquals(List.of(), report.drifts());
        assertEquals(NavSweep.Verdict.INCOMPLETE, report.verdict());
    }

    @Test
    void aDriftElsewhereOutranksNodesThatCouldNotBeRead() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addNode(2, 5, 64, 0, NavSurface.SIDEWALK).build();
        NavSweep.Revalidation report = revalidate(graph, new World().ground(0, 0).set(5, 63, 0, NOT_LOADED));
        assertEquals(NavSweep.Verdict.INCOMPLETE, report.verdict(), "one node read fine, the other could not be read");
        NavSweep.Revalidation drifted = revalidate(graph, new World().set(5, 63, 0, NOT_LOADED));
        assertEquals(NavSweep.Verdict.DRIFTED, drifted.verdict());
        assertEquals(1, drifted.notLoaded());
        assertEquals(1, drifted.unwalkable());
    }

    @Test
    void withoutAPaletteTheClassIsNotComparedButSolidGroundAndHeadroomStill() {
        NavGraph graph = one(NavSurface.SIDEWALK);
        NavSweep.Revalidation classChanged = run(NavSweep.forRevalidation(graph, null, false), new World().set(0, 63, 0, SOLID_ROAD), null);
        assertEquals(NavSweep.Verdict.CLEAN, classChanged.verdict());
        assertEquals(1, classChanged.ok());
        NavSweep.Revalidation notSolid = run(NavSweep.forRevalidation(graph, null, false), new World(), null);
        assertEquals(1, notSolid.unwalkable());
        NavSweep.Revalidation blocked = run(NavSweep.forRevalidation(graph, null, false),
                new World().ground(0, 0).set(0, 65, 0, SOLID_NOT_IN_PALETTE), null);
        assertEquals(1, blocked.blocked());
    }

    // ---- the palette hash ----

    @Test
    void aGraphStoredWithoutAHashReadsAsLegacyAndStaysClean() {
        NavSweep.Revalidation report = run(NavSweep.forRevalidation(one(NavSurface.SIDEWALK), null), new World().ground(0, 0), "anything");
        assertEquals("legacy", report.palette());
        assertEquals(NavSweep.Verdict.CLEAN, report.verdict());
    }

    @Test
    void aDifferentHashIsDriftEvenWhenEveryBlockMatches() {
        NavSweep.Revalidation report = run(NavSweep.forRevalidation(one(NavSurface.SIDEWALK), "old"), new World().ground(0, 0), "new");
        assertEquals("changed", report.palette());
        assertEquals(NavSweep.Verdict.DRIFTED, report.verdict());
        assertEquals(1, report.ok());
        assertEquals(List.of(), report.drifts());
    }

    @Test
    void aPaletteThatIsNoLongerDefinedCountsAsChanged() {
        NavSweep.Revalidation report = run(NavSweep.forRevalidation(one(NavSurface.SIDEWALK), "old"), new World().ground(0, 0), null);
        assertEquals("changed", report.palette());
        assertEquals(NavSweep.Verdict.DRIFTED, report.verdict());
    }

    // ---- ordering and the drift box ----

    private static NavGraph twoNodes(String firstTag, String secondTag) {
        NavGraph.Builder builder = NavGraph.builder();
        builder.addNode(1, 0, 64, 0, NavSurface.SIDEWALK, firstTag == null ? new String[0] : new String[]{firstTag});
        builder.addNode(2, 5, 70, -3, NavSurface.SIDEWALK, secondTag == null ? new String[0] : new String[]{secondTag});
        return builder.build();
    }

    @Test
    void driftsOfTaggedNodesComeFirstThenByNodeId() {
        NavSweep.Revalidation report = revalidate(twoNodes(null, "home"), new World());
        assertEquals(List.of("UNWALKABLE node 2 at 5,70,-3 [tagged] nothing solid underneath",
                "UNWALKABLE node 1 at 0,64,0 nothing solid underneath"), report.lines());
    }

    @Test
    void whenNeitherOrBothAreTaggedTheLowerNodeIdComesFirst() {
        assertEquals("UNWALKABLE node 1 at 0,64,0 nothing solid underneath", revalidate(twoNodes(null, null), new World()).lines().get(0));
        assertEquals("UNWALKABLE node 1 at 0,64,0 [tagged] nothing solid underneath", revalidate(twoNodes("home", "work"), new World()).lines().get(0));
    }

    @Test
    void theDriftBoxCoversEveryDriftedNode() {
        NavSweep.Revalidation report = revalidate(twoNodes(null, null), new World());
        assertArrayEquals(new int[]{0, 64, -3, 5, 70, 0}, report.driftBox());
    }

    // ---- steps between nodes ----

    private static NavGraph edgePair(int x2, int y2, int z2) {
        return NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addNode(2, x2, y2, z2, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).build();
    }

    @Test
    void aStepBetweenTwoNodesOnFlatGroundIsFine() {
        NavSweep.Revalidation report = revalidate(edgePair(1, 64, 0), new World().ground(0, 0).ground(1, 0));
        assertEquals(NavSweep.Verdict.CLEAN, report.verdict());
        assertEquals(0, report.footprint());
        assertEquals(2, report.ok());
    }

    @Test
    void aBlockAboveTheFarNodeIsBlockedForThatNodeAndAFootprintDriftForTheStepCountedOnceForBothDirections() {
        NavSweep.Revalidation report = revalidate(edgePair(1, 64, 0), new World().ground(0, 0).ground(1, 0).set(1, 65, 0, SOLID_NOT_IN_PALETTE));
        assertEquals(1, report.blocked());
        assertEquals(1, report.footprint());
        assertEquals(List.of("FOOTPRINT node 1 at 0,64,0 step to node 2 at 1,64,0 is no longer walkable",
                "BLOCKED node 2 at 1,64,0 no longer clear overhead"), report.lines());
    }

    @Test
    void aStepUpIsWalkableWhenEachColumnHasSolidGroundAtOneOfTheTwoHeights() {
        World world = new World().ground(0, 0).set(1, 64, 0, SOLID_SIDEWALK);
        assertEquals(NavSweep.Verdict.CLEAN, revalidate(edgePair(1, 65, 0), world).verdict());
        World hole = new World().ground(0, 0);
        NavSweep.Revalidation report = revalidate(edgePair(1, 65, 0), hole);
        assertEquals(1, report.footprint());
        assertEquals(1, report.unwalkable());
    }

    @Test
    void aHoleInACornerColumnOfADiagonalStepMakesTheStepUnwalkable() {
        World flat = new World().ground(0, 0).ground(1, 1).ground(1, 0).ground(0, 1);
        assertEquals(NavSweep.Verdict.CLEAN, revalidate(edgePair(1, 64, 1), flat).verdict());
        World holeAtCorner = new World().ground(0, 0).ground(1, 1).ground(0, 1);
        NavSweep.Revalidation report = revalidate(edgePair(1, 64, 1), holeAtCorner);
        assertEquals(1, report.footprint());
        assertEquals(0, report.unwalkable(), "both nodes still have ground under them");
    }

    @Test
    void aCornerColumnNotLoadedLeavesTheStepNotLoadedAndTheGraphIncomplete() {
        World world = new World().ground(0, 0).ground(1, 1).ground(1, 0).ground(0, 1).set(1, 64, 0, NOT_LOADED);
        NavSweep.Revalidation report = revalidate(edgePair(1, 64, 1), world);
        assertEquals(1, report.notLoaded());
        assertEquals(0, report.footprint());
        assertEquals(NavSweep.Verdict.INCOMPLETE, report.verdict());
    }

    @Test
    void aStepWithATaggedEndIsReportedAsTagged() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home").addNode(2, 1, 64, 0, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).build();
        NavSweep.Revalidation report = revalidate(graph, new World().ground(0, 0).ground(1, 0).set(1, 65, 0, SOLID_NOT_IN_PALETTE));
        assertTrue(report.lines().stream().anyMatch(l -> l.startsWith("FOOTPRINT node 1 at 0,64,0 [tagged] step to node 2")), report.lines().toString());
    }

    @Test
    void anEdgeWhoseEndIsMissingFromTheGraphIsIgnored() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addEdge(1, 99, 10).build();
        NavSweep.Revalidation report = revalidate(graph, new World().ground(0, 0));
        assertEquals(NavSweep.Verdict.CLEAN, report.verdict());
        assertEquals(0, report.footprint());
    }

    // ---- waypoint builds: crossing cells match road or paint alike ----

    private static NavGraph crossingGraph(NavSurface stored) {
        return NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addNode(2, 1, 64, 0, stored).addNode(3, 2, 64, 0, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).build();
    }

    private static World crossingWorld(int middle) {
        return new World().ground(0, 0).set(1, 63, 0, middle).ground(2, 0);
    }

    @ParameterizedTest
    @CsvSource({"ROAD,ROAD", "ROAD,CROSSING", "CROSSING,ROAD", "CROSSING,CROSSING"})
    void aCrossingCellMatchesWhicheverOfRoadOrPaintTheWorldShows(NavSurface stored, NavSurface seen) {
        int middle = seen == NavSurface.ROAD ? SOLID_ROAD : SOLID_PAINT;
        NavSweep sweep = NavSweep.forWaypointBuild(crossingGraph(stored), "h", Set.of(2L));
        NavSweep.Revalidation report = run(sweep, crossingWorld(middle), "h");
        assertEquals(NavSweep.Verdict.CLEAN, report.verdict(), report.lines().toString());
        assertEquals(Map.of(2L, seen), sweep.streetClasses());
    }

    @Test
    void aCrossingCellTheWorldShowsAsSidewalkIsAChangedClassAndReportsNoStreetClass() {
        NavSweep sweep = NavSweep.forWaypointBuild(crossingGraph(NavSurface.ROAD), "h", Set.of(2L));
        NavSweep.Revalidation report = run(sweep, crossingWorld(SOLID_SIDEWALK), "h");
        assertEquals(List.of("CHANGED_CLASS node 2 at 1,64,0 stored ROAD now SIDEWALK"), report.lines());
        assertEquals(Map.of(), sweep.streetClasses());
    }

    @Test
    void theSameSwapOnACellThatIsNotAPlannedCrossingCellStillCountsAsChanged() {
        NavSweep sweep = NavSweep.forRevalidation(crossingGraph(NavSurface.ROAD), "h");
        NavSweep.Revalidation report = run(sweep, crossingWorld(SOLID_PAINT), "h");
        assertEquals(List.of("CHANGED_CLASS node 2 at 1,64,0 stored ROAD now CROSSING"), report.lines());
    }

    @Test
    void streetClassesAreOnlyAvailableAfterAFinishedFootprintSweep() {
        NavSweep sweep = NavSweep.forWaypointBuild(crossingGraph(NavSurface.ROAD), "h", Set.of(2L));
        assertThrows(IllegalStateException.class, sweep::streetClasses);
        assertThrows(IllegalStateException.class, NavSweep.forClasses(crossingGraph(NavSurface.ROAD))::streetClasses);
    }
}
