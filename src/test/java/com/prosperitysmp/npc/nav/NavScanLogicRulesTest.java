package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.NavScanLogic.Bounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scan's input checks, its report numbers, and the way it picks the block a column stands on. */
class NavScanLogicRulesTest {

    private static String refusal(Bounds bounds, int headroom, int maxStep) {
        return assertThrows(IllegalArgumentException.class, () -> NavScanLogic.validate(bounds, headroom, maxStep)).getMessage();
    }

    private static final Bounds OK = new Bounds(0, 60, 9, 70, 0, 9);

    // ---- headroom and step ----

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 9, 100})
    void aHeadroomOutsideOneToEightIsRefused(int headroom) {
        assertEquals("headroom must be 1..8 and step 0..1", refusal(OK, headroom, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2, 5})
    void aMaxStepOutsideZeroToOneIsRefused(int maxStep) {
        assertEquals("headroom must be 1..8 and step 0..1", refusal(OK, 2, maxStep));
    }

    @ParameterizedTest
    @CsvSource({"1,0", "1,1", "8,0", "8,1", "2,1"})
    void theEdgesOfTheAllowedHeadroomAndStepRangesPass(int headroom, int maxStep) {
        assertDoesNotThrow(() -> NavScanLogic.validate(OK, headroom, maxStep));
    }

    // ---- bounds ----

    @ParameterizedTest
    @CsvSource({"5,4,0,0,0,0", "0,0,5,4,0,0", "0,0,0,0,5,4"})
    void anInvertedBoxIsRefused(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        assertEquals("invalid or overflowing scan bounds", refusal(new Bounds(minX, minY, maxX, maxY, minZ, maxZ), 2, 1));
    }

    @Test
    void aBoxWithTheSmallestIntAsAMinimumIsRefusedOnEveryAxis() {
        assertEquals("invalid or overflowing scan bounds", refusal(new Bounds(Integer.MIN_VALUE, 0, 0, 0, 0, 0), 2, 1));
        assertEquals("invalid or overflowing scan bounds", refusal(new Bounds(0, Integer.MIN_VALUE, 0, 0, 0, 0), 2, 1));
        assertEquals("invalid or overflowing scan bounds", refusal(new Bounds(0, 0, 0, 0, Integer.MIN_VALUE, 0), 2, 1));
    }

    @Test
    void aBoxWithTheLargestIntAsAMaximumIsRefusedOnXAndZAndOnYWithHeadroom() {
        assertEquals("invalid or overflowing scan bounds", refusal(new Bounds(0, 0, Integer.MAX_VALUE, 0, 0, 0), 2, 1));
        assertEquals("invalid or overflowing scan bounds", refusal(new Bounds(0, 0, 0, 0, 0, Integer.MAX_VALUE), 2, 1));
        assertEquals("invalid or overflowing scan bounds", refusal(new Bounds(0, Integer.MAX_VALUE - 1, 0, Integer.MAX_VALUE - 1, 0, 0), 2, 1));
    }

    @Test
    void aTopThatPlusHeadroomJustFitsTheIntRangePassesTheOverflowCheck() {
        Bounds top = new Bounds(0, Integer.MAX_VALUE - 2, 0, Integer.MAX_VALUE - 2, 0, 0);
        assertDoesNotThrow(() -> NavScanLogic.validate(top, 2, 1));
    }

    @Test
    void theColumnCountIsTheProductOfTheTwoSpans() {
        assertEquals(50, new Bounds(0, 0, 9, 0, 0, 4).columns());
        assertEquals(1, new Bounds(-5, 0, -5, 0, 7, 7).columns());
        assertEquals(8, new Bounds(-3, 0, 0, 0, -1, 0).columns());
    }

    @Test
    void aSpanTooWideToMultiplyIsAnOverflowRefusal() {
        Bounds wide = new Bounds(Integer.MIN_VALUE + 1, 0, Integer.MAX_VALUE - 1, 0, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1);
        assertEquals("scan dimensions overflow", assertThrows(IllegalArgumentException.class, wide::columns).getMessage());
    }

    @Test
    void twoHundredThousandColumnsPassAndOneMoreIsTooLarge() {
        assertDoesNotThrow(() -> NavScanLogic.validate(new Bounds(0, 64, 199_999, 64, 0, 0), 2, 1));
        assertEquals("scan area too large: 200001 columns (max 200000)",
                refusal(new Bounds(0, 64, 200_000, 64, 0, 0), 2, 1));
    }

    @Test
    void theTerrainReadBudgetIsTheTotalOfColumnsTimesHeightTimesHeadroomPlusOne() {
        // One column, headroom 1: 2,000,000 blocks of height is exactly the 4,000,000 read budget.
        assertDoesNotThrow(() -> NavScanLogic.validate(new Bounds(0, 0, 0, 1_999_999, 0, 0), 1, 1));
        assertEquals("scan exceeds terrain-read budget; use a smaller box",
                refusal(new Bounds(0, 0, 0, 2_000_000, 0, 0), 1, 1));
    }

    @Test
    void moreHeadroomShrinksTheHeightTheBudgetAllows() {
        assertDoesNotThrow(() -> NavScanLogic.validate(new Bounds(0, 0, 0, 999_999, 0, 0), 3, 1));
        assertEquals("scan exceeds terrain-read budget; use a smaller box",
                refusal(new Bounds(0, 0, 0, 1_000_000, 0, 0), 3, 1));
    }

    @Test
    void aNullBoundsIsRefusedAsNull() {
        assertThrows(NullPointerException.class, () -> NavScanLogic.validate(null, 2, 1));
    }

    @Test
    void scanningWithNullRulesIsRefused() {
        assertThrows(NullPointerException.class, () -> NavScanLogic.scan(StreetWorld.grid(0, 0, 3, 3).build().bounds(),
                StreetWorld.grid(0, 0, 3, 3).build(), 2, 1, null));
    }

    // ---- constants the reports and stored graphs depend on ----

    @Test
    void theStepCostsAndLimitsAreTheDocumentedNumbers() {
        assertEquals(10, NavScanLogic.CARDINAL_COST);
        assertEquals(14, NavScanLogic.DIAGONAL_COST);
        assertEquals(200_000L, NavScanLogic.MAX_COLUMNS);
        assertEquals(4_000_000L, NavScanLogic.MAX_TERRAIN_READS);
        assertEquals(4, NavScanLogic.MIN_PLATFORM);
    }

    @Test
    void theDropReasonsKeepTheirReportOrder() {
        assertEquals(List.of("BOX_EDGE", "TOO_LONG", "BLOCKED", "CURB", "STEP", "NOT_SIDEWALK", "ALONG_ROAD", "ISLAND"),
                java.util.Arrays.stream(NavScanLogic.Drop.values()).map(Enum::name).toList());
    }

    // ---- stats ----

    @Test
    void streetNodesAreTheRoadAndCrossingNodesOnly() {
        Map<NavSurface, Integer> nodes = new EnumMap<>(NavSurface.class);
        nodes.put(NavSurface.SIDEWALK, 40);
        nodes.put(NavSurface.ROAD, 6);
        nodes.put(NavSurface.CROSSING, 3);
        nodes.put(NavSurface.ACCESS, 2);
        NavScanLogic.Stats stats = new NavScanLogic.Stats(nodes, 1, 2, Map.of(), Map.of());
        assertEquals(9, stats.streetNodes());
    }

    @Test
    void aStatsWithNoStreetNodesCountsZero() {
        assertEquals(0, new NavScanLogic.Stats(Map.of(NavSurface.SIDEWALK, 4), 0, 0, Map.of(), Map.of()).streetNodes());
    }

    @Test
    void totalDroppedAddsEveryReason() {
        Map<NavScanLogic.Drop, Integer> dropped = new EnumMap<>(NavScanLogic.Drop.class);
        dropped.put(NavScanLogic.Drop.CURB, 3);
        dropped.put(NavScanLogic.Drop.ISLAND, 4);
        dropped.put(NavScanLogic.Drop.STEP, 1);
        assertEquals(8, new NavScanLogic.Stats(Map.of(), 0, 0, dropped, Map.of()).totalDropped());
        assertEquals(0, new NavScanLogic.Stats(Map.of(), 0, 0, Map.of(), Map.of()).totalDropped());
    }

    @Test
    void aScanKeepsOnlyTheFirstFiveCoordinatesOfEachDropReasonButCountsThemAll() {
        StreetWorld world = StreetWorld.strip(5, 20).fill(0, 5, 19, 5, 'U').build();
        NavScanLogic.Stats stats = world.scan(StreetWorld.RULES).stats();
        assertEquals(40, (int) stats.dropped().get(NavScanLogic.Drop.NOT_SIDEWALK));
        assertEquals(40, stats.totalDropped());
        assertEquals(List.of("0,64,3", "0,64,7", "1,64,3", "1,64,7", "2,64,3"),
                stats.firstDropped().get(NavScanLogic.Drop.NOT_SIDEWALK));
    }

    @Test
    void aScanThatDropsNothingReportsNoDropsAndNoSamples() {
        NavScanLogic.Stats stats = StreetWorld.strip(5, 6).build().scan(StreetWorld.RULES).stats();
        assertEquals(Map.of(), stats.dropped());
        assertEquals(Map.of(), stats.firstDropped());
        assertEquals(0, stats.totalDropped());
    }

    @Test
    void theReportedNodeCountsMatchTheGraphPerSurface() {
        NavScanLogic.ScanResult scan = StreetWorld.strip(5, 10).build().scan(StreetWorld.RULES);
        int sidewalk = (int) scan.graph().nodes().stream().filter(n -> n.surface() == NavSurface.SIDEWALK).count();
        int road = (int) scan.graph().nodes().stream().filter(n -> n.surface() == NavSurface.ROAD).count();
        assertEquals(sidewalk, (int) scan.stats().nodes().get(NavSurface.SIDEWALK));
        assertEquals(road, (int) scan.stats().nodes().get(NavSurface.ROAD));
        assertEquals(60, sidewalk);
        assertEquals(50, road);
        assertEquals(10, scan.stats().plainRays() + scan.stats().crosswalkRays());
    }

    // ---- which block a column stands on ----

    /** Raw terrain for stacked-support cases: supports and solids at listed blocks, air elsewhere. */
    private record Column(List<Integer> supports, List<Integer> solids) implements NavScanLogic.Terrain {
        @Override public boolean isSupport(int x, int y, int z) {
            return x == 0 && z == 0 && supports.contains(y);
        }

        @Override public boolean isClear(int x, int y, int z) {
            return !(x == 0 && z == 0 && (supports.contains(y) || solids.contains(y)));
        }
    }

    private static NavGraph scanColumn(Column column, int headroom) {
        return NavScanLogic.scan(new Bounds(0, 60, 0, 75, 0, 0), column, headroom, 1);
    }

    @Test
    void theHighestSupportWithRoomAboveItIsTheOneAColumnStandsOn() {
        NavGraph graph = scanColumn(new Column(List.of(64, 66), List.of()), 2);
        assertEquals(1, graph.nodeCount());
        assertEquals(67, graph.nodes().iterator().next().y());
    }

    @Test
    void aSupportWhoseHeadroomIsBlockedFallsThroughToTheLowerOne() {
        NavGraph graph = scanColumn(new Column(List.of(64, 70), List.of(72)), 2);
        assertEquals(1, graph.nodeCount());
        assertEquals(65, graph.nodes().iterator().next().y());
    }

    @Test
    void aLowerSupportWithTheUpperOneInItsHeadroomHasNoNode() {
        NavGraph graph = scanColumn(new Column(List.of(64, 66), List.of(68)), 2);
        assertEquals(0, graph.nodeCount());
    }

    @Test
    void theStandingBlockIsOneAboveTheSupport() {
        NavGraph graph = scanColumn(new Column(List.of(61), List.of()), 1);
        assertEquals(62, graph.nodes().iterator().next().y());
    }

    @Test
    void aSupportAtTheTopOfTheBoxUsesTheBlocksAboveItAsHeadroom() {
        NavGraph graph = scanColumn(new Column(List.of(75), List.of()), 3);
        assertEquals(1, graph.nodeCount());
        assertEquals(76, graph.nodes().iterator().next().y());
        assertEquals(0, scanColumn(new Column(List.of(75), List.of(77)), 3).nodeCount());
    }

    @Test
    void aSupportBelowTheBoxIsNeverConsidered() {
        assertEquals(0, scanColumn(new Column(List.of(59), List.of()), 2).nodeCount());
    }

    @Test
    void nodeIdsStartAtZeroAndRunInColumnOrderAlongXThenZ() {
        NavGraph graph = StreetWorld.grid(0, 0, 2, 2).fill(0, 0, 1, 1, 'S').build().graph();
        assertEquals(4, graph.nodeCount());
        assertEquals(0, graph.nodes().stream().filter(n -> n.x() == 0 && n.z() == 0).findFirst().orElseThrow().id());
        assertEquals(1, graph.nodes().stream().filter(n -> n.x() == 0 && n.z() == 1).findFirst().orElseThrow().id());
        assertEquals(2, graph.nodes().stream().filter(n -> n.x() == 1 && n.z() == 0).findFirst().orElseThrow().id());
        assertEquals(3, graph.nodes().stream().filter(n -> n.x() == 1 && n.z() == 1).findFirst().orElseThrow().id());
    }

    @Test
    void aMaxStepOfZeroOnlyJoinsColumnsAtTheSameHeight() {
        StreetWorld world = StreetWorld.grid(0, 0, 2, 1).fill(0, 0, 1, 0, 'S').raise(1, 0, 1).build();
        NavGraph flatOnly = NavScanLogic.scan(world.bounds(), world, 2, 0);
        assertEquals(2, flatOnly.nodeCount());
        assertEquals(0, flatOnly.edgeCount());
        NavGraph stepping = NavScanLogic.scan(world.bounds(), world, 2, 1);
        assertEquals(2, stepping.edgeCount());
        assertTrue(stepping.hasPositiveEdge(0, 1));
    }

    @Test
    void anEmptyWorldGivesAnEmptyGraph() {
        StreetWorld world = StreetWorld.grid(0, 0, 4, 4).build();
        NavGraph graph = world.graph();
        assertEquals(0, graph.nodeCount());
        assertEquals(0, graph.edgeCount());
    }
}
