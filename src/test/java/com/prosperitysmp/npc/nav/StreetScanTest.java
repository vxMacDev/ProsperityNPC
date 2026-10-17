package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.prosperitysmp.npc.nav.StreetWorld.RULES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a classified scan turns roads into straight crossings: shapes, drops and exact costs. */
class StreetScanTest {
    private static final int LENGTH = 24;

    private static NavGraph strip(int width) {
        return StreetWorld.strip(width, LENGTH).build().graph();
    }

    private static boolean street(NavGraph g, long id) {
        return g.node(id).surface().isStreet();
    }

    private static int cost(NavGraph g, NavGraph.Node from, NavGraph.Node to) {
        return g.neighbors(from.id()).stream().filter(e -> e.to() == to.id()).mapToInt(NavGraph.Edge::cost)
                .findFirst().orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to));
    }

    /** The staircase road: street where |x - z| <= 1, sidewalk everywhere else on a 14x14 field. */
    private static StreetWorld diagonalRoad() {
        var grid = StreetWorld.grid(0, 0, 14, 14).fill(0, 0, 13, 13, 'S');
        for (int x = 0; x < 14; x++) {
            for (int z = 0; z < 14; z++) {
                if (Math.abs(x - z) <= 1) grid.set(x, z, 'R');
            }
        }
        return grid.build();
    }

    private static List<NavGraph> fixtures() {
        return List.of(strip(3), strip(5), strip(9), strip(13), diagonalRoad().graph(), junction().graph());
    }

    /** Two three-wide roads crossing, sidewalk in the four corners. */
    private static StreetWorld junction() {
        return StreetWorld.grid(0, 0, 9, 9).fill(0, 0, 8, 8, 'S').fill(3, 0, 5, 8, 'R').fill(0, 3, 8, 5, 'R').build();
    }

    // S1
    @Test
    void streetEdgesJoinOnlyConsecutiveCellsOfOneStraightLane() {
        for (NavGraph g : fixtures()) {
            assertFalse(StreetWorld.street(g).isEmpty());
            for (NavGraph.Node n : StreetWorld.street(g)) {
                Set<String> axes = new HashSet<>();
                for (NavGraph.Edge e : g.neighbors(n.id())) {
                    NavGraph.Node to = g.node(e.to());
                    if (!to.surface().isStreet()) continue;
                    assertEquals(1, Math.abs(to.x() - n.x()) + Math.abs(to.z() - n.z()), "consecutive cells only");
                    axes.add(to.x() != n.x() ? "x" : "z");
                }
                assertTrue(axes.size() <= 1, "a street node never turns: " + n);
            }
        }
    }

    @Test
    void crossingsThatShareACellKeepSeparateLanesSoNoPathCanTurnThere() {
        NavGraph g = diagonalRoad().graph();
        int shared = 0;
        for (NavGraph.Node n : StreetWorld.street(g)) {
            List<NavGraph.Node> here = StreetWorld.nodes(g, n.x(), n.z());
            if (here.size() > 1) {
                shared++;
                assertEquals(2, here.size(), "at most one lane per axis");
                assertTrue(g.neighbors(here.get(0).id()).stream().noneMatch(e -> e.to() == here.get(1).id()));
                assertTrue(g.neighbors(here.get(1).id()).stream().noneMatch(e -> e.to() == here.get(0).id()));
            }
        }
        assertTrue(shared > 0, "the diagonal road must really have a cell crossed from two directions");
    }

    // S2
    @Test
    void noDiagonalEdgeEverTouchesAStreetNodeSoNoCornerIsCutAcrossARoad() {
        for (NavGraph g : fixtures()) {
            long diagonals = g.edges().stream().filter(e -> e.cost() == NavScanLogic.DIAGONAL_COST).count();
            assertTrue(diagonals > 0, "sidewalks still have diagonals");
            for (NavGraph.Edge e : g.edges()) {
                NavGraph.Node a = g.node(e.from());
                NavGraph.Node b = g.node(e.to());
                if (a.surface().isStreet() || b.surface().isStreet()) {
                    assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), "straight step: " + e);
                }
            }
        }
    }

    @Test
    void aSidewalkCornerNextToARoadGetsNoDiagonalAcrossIt() {
        NavGraph g = strip(5);
        // The sidewalk node at the road's edge has no diagonal onto the road row.
        NavGraph.Node corner = StreetWorld.node(g, 4, 2);
        assertTrue(g.neighbors(corner.id()).stream().noneMatch(e ->
                e.cost() == NavScanLogic.DIAGONAL_COST && g.node(e.to()).z() == 3));
    }

    // S3
    @ParameterizedTest
    @ValueSource(ints = {3, 5, 9, 13})
    void everyStreetNodeLiesOnARayWithSidewalkAtBothEnds(int width) {
        NavGraph g = strip(width);
        for (NavGraph.Node n : StreetWorld.street(g)) {
            // Walk both ways along the lane; each end must step off onto a sidewalk.
            for (int dir : new int[]{-1, 1}) {
                NavGraph.Node at = n;
                NavGraph.Node end = null;
                for (int guard = 0; guard < 40 && end == null; guard++) {
                    final NavGraph.Node here = at;
                    var next = g.neighbors(here.id()).stream().map(e -> g.node(e.to()))
                            .filter(m -> m.x() == here.x() && (m.z() - here.z()) * dir > 0).findFirst();
                    if (next.isEmpty()) break;
                    if (next.get().surface().isStreet()) at = next.get();
                    else end = next.get();
                }
                assertNotNull(end, "ray from " + n + " direction " + dir);
                assertEquals(NavSurface.SIDEWALK, end.surface());
            }
        }
    }

    @Test
    void aCrossingThatReachesTheBoxEdgeIsDroppedAndTheSameStreetIsKeptWithSidewalkInsideTheBox() {
        // The far sidewalk is outside the box, so the crossing cannot be proven to land on a sidewalk.
        var clipped = StreetWorld.grid(0, 0, 10, 8).fill(0, 0, 9, 2, 'S').fill(0, 3, 9, 7, 'R').build().scan(RULES);
        assertEquals(0, clipped.stats().streetNodes());
        assertEquals(10, clipped.stats().dropped().get(NavScanLogic.Drop.BOX_EDGE));
        var whole = StreetWorld.grid(0, 0, 10, 9).fill(0, 0, 9, 2, 'S').fill(0, 3, 9, 7, 'R').fill(0, 8, 9, 8, 'S')
                .build().scan(RULES);
        assertEquals(10 * 5, whole.stats().streetNodes());
        assertNull(whole.stats().dropped().get(NavScanLogic.Drop.BOX_EDGE));
    }

    // S4
    @Test
    void aCrossingLongerThanTheLimitIsDroppedAndOneAtTheLimitIsKept() {
        StreetWorld wide = StreetWorld.strip(13, LENGTH).build();
        var tooLong = wide.scan(new StreetRules(12, 12, 0.4));
        assertEquals(0, tooLong.stats().streetNodes());
        assertTrue(tooLong.stats().dropped().get(NavScanLogic.Drop.TOO_LONG) > 0);
        var exact = wide.scan(new StreetRules(12, 13, 0.4));
        assertEquals(13 * LENGTH, exact.stats().streetNodes());
        assertNull(exact.stats().dropped().get(NavScanLogic.Drop.TOO_LONG));
    }

    // S5
    @Test
    void aRayRunningLengthwiseAlongARoadIsDroppedButAFiveBlockDrivewayGapIsKept() {
        // A short road segment 5 long and 2 wide with sidewalk at both ends and on both sides.
        var segment = StreetWorld.grid(0, 0, 11, 6).fill(0, 0, 10, 5, 'S').fill(3, 2, 7, 3, 'R').build().scan(RULES);
        assertTrue(segment.stats().dropped().get(NavScanLogic.Drop.ALONG_ROAD) > 0,
                "walking the 5-cell length of a 2-wide road is walking along it");
        assertEquals(10, segment.stats().streetNodes(), "only the five across-the-width crossings survive");
        long alongX = StreetWorld.street(segment.graph()).stream().filter(n ->
                segment.graph().neighbors(n.id()).stream().anyMatch(e -> segment.graph().node(e.to()).z() == n.z()
                        && segment.graph().node(e.to()).surface().isStreet())).count();
        assertEquals(0, alongX, "no street node steps sideways along the road");

        // A 5-wide driveway cuts a 2-deep sidewalk strip and joins a four-lane road that runs beside it.
        var driveway = StreetWorld.grid(0, 0, 25, 10).fill(0, 2, 24, 3, 'S').fill(10, 2, 14, 3, 'R')
                .fill(0, 4, 24, 7, 'R').fill(0, 8, 24, 9, 'S').fill(0, 0, 24, 1, 'S').build().scan(RULES);
        NavGraph dg = driveway.graph();
        for (int row : new int[]{2, 3}) {
            assertTrue(StreetWorld.nodes(dg, 12, row).stream().anyMatch(n -> dg.neighbors(n.id()).stream()
                            .anyMatch(e -> dg.node(e.to()).z() == row && dg.node(e.to()).x() != 12)),
                    "the 5-block gap is crossed lengthwise along the sidewalk in row " + row);
        }
    }

    @Test
    void aStubFeedingARoadIsNotWalkedLengthwiseJustBecauseItsMiddleLandsInTheJunction() {
        // A three-wide dead-end stub, six cells long, opens onto a nine-wide main road. A ray down the stub and
        // straight across the main road is 15 cells: its middle cell is in the main road (31 wide), but the
        // cells in the stub are only 3 wide, so most of that ray is running along the stub.
        var world = StreetWorld.grid(0, -8, 31, 23).fill(10, -8, 20, 2, 'S').fill(0, 0, 30, 2, 'S')
                .fill(0, 3, 30, 11, 'R').fill(0, 12, 30, 14, 'S').fill(14, -3, 16, 2, 'R').build();
        var result = world.scan(RULES);
        assertTrue(result.stats().dropped().get(NavScanLogic.Drop.ALONG_ROAD) >= 3, "one per stub column");
        NavGraph g = result.graph();
        for (NavGraph.Edge e : g.edges()) {
            NavGraph.Node from = g.node(e.from());
            NavGraph.Node to = g.node(e.to());
            boolean lengthwise = from.surface().isStreet() && to.surface().isStreet() && from.z() != to.z()
                    && Math.min(from.z(), to.z()) <= 2 && from.x() >= 14 && from.x() <= 16;
            assertFalse(lengthwise, "no lane runs down the stub: " + from + " -> " + to);
        }
        // Control: the stub is still crossed across its own width.
        assertFalse(StreetWorld.nodes(g, 15, -1).isEmpty());
        // Control: the main road is still crossed across its width beside the stub.
        assertFalse(StreetWorld.nodes(result.graph(), 12, 5).isEmpty());
    }

    @Test
    void aLoneSidewalkBlockInARoadIsNoPlaceToStartOrEndACrossing() {
        var control = StreetWorld.strip(9, 30).build().scan(RULES);
        assertFalse(StreetWorld.nodes(control.graph(), 12, 7).isEmpty());
        assertTrue(StreetWorld.nodes(control.graph(), 12, 7).stream().allMatch(n -> n.surface().isStreet()));
        assertNull(control.stats().dropped().get(NavScanLogic.Drop.ISLAND));

        var island = StreetWorld.strip(9, 30).set(12, 7, 'S').build().scan(RULES);
        assertTrue(island.stats().dropped().get(NavScanLogic.Drop.ISLAND) >= 2);
        NavGraph g = island.graph();
        NavGraph.Node block = StreetWorld.node(g, 12, 7);
        assertEquals(NavSurface.SIDEWALK, block.surface());
        assertTrue(g.neighbors(block.id()).isEmpty(), "the lone block is not joined to anything");
        assertTrue(StreetWorld.nodes(g, 12, 4).isEmpty());
    }

    @Test
    void lonelyBlocksStrungAlongARoadDoNotLetAPathWalkAlongIt() {
        // Single blocks every six columns down the middle of a nine-wide road: each hop is shorter than the
        // road is wide, so without a minimum size they chain into a lane along the road.
        var world = StreetWorld.strip(9, 30);
        for (int x = 2; x < 30; x += 6) world.set(x, 7, 'S');
        NavGraph g = world.build().graph();
        for (NavGraph.Edge e : g.edges()) {
            NavGraph.Node from = g.node(e.from());
            NavGraph.Node to = g.node(e.to());
            if (from.surface().isStreet() || to.surface().isStreet()) {
                assertEquals(from.x(), to.x(), "a street edge only ever steps across the road");
            }
        }
    }

    @Test
    void aBlockInNoClassWithSidewalkBehindItIsACurbNotAHoleInTheRoad() {
        // A curb row of blocks outside the palette on the far side of the road, then more sidewalk behind it.
        var curbed = StreetWorld.strip(5, LENGTH).fill(0, 8, LENGTH - 1, 8, '.').build().scan(RULES);
        assertEquals(LENGTH, curbed.stats().dropped().get(NavScanLogic.Drop.CURB));
        assertNull(curbed.stats().dropped().get(NavScanLogic.Drop.BLOCKED));
        // A hole in the middle of the road has road behind it: that stays BLOCKED.
        var holey = StreetWorld.strip(5, LENGTH).set(12, 5, '.').build().scan(RULES);
        assertNull(holey.stats().dropped().get(NavScanLogic.Drop.CURB));
        assertEquals(2, holey.stats().dropped().get(NavScanLogic.Drop.BLOCKED));
    }

    // S6
    @Test
    void anUnclassifiedBlockInsideTheRoadDropsThatCrossingAndReportsWhere() {
        StreetWorld world = StreetWorld.strip(5, LENGTH).build();
        var holey = StreetWorld.strip(5, LENGTH).set(12, 5, '.').build();
        assertEquals(5 * LENGTH, world.scan(RULES).stats().streetNodes());
        var result = holey.scan(RULES);
        assertEquals(2, result.stats().dropped().get(NavScanLogic.Drop.BLOCKED), "each curb sees the hole once");
        assertEquals(List.of("12,64,3"), result.stats().firstDropped().get(NavScanLogic.Drop.BLOCKED).subList(0, 1));
        assertEquals(5 * (LENGTH - 1), result.stats().streetNodes(), "the other columns are untouched");
        assertTrue(StreetWorld.nodes(result.graph(), 12, 3).isEmpty());
    }

    // S7
    @ParameterizedTest
    @ValueSource(ints = {5, 9, 13})
    void edgeCostsAreExactlyTheModel(int width) {
        int penalty = RULES.entryPenalty();
        assertEquals(240, penalty);
        var world = StreetWorld.strip(width, LENGTH).fill(10, 3, 10, 2 + width, 'W').build();
        NavGraph g = world.graph();
        // Plain column 5: stepping on costs 10 + P from either curb, everything else 10.
        NavGraph.Node nearCurb = StreetWorld.node(g, 5, 2);
        NavGraph.Node farCurb = StreetWorld.node(g, 5, width + 3);
        NavGraph.Node first = StreetWorld.node(g, 5, 3);
        NavGraph.Node last = StreetWorld.node(g, 5, 2 + width);
        assertEquals(10 + penalty, cost(g, nearCurb, first));
        assertEquals(10 + penalty, cost(g, farCurb, last));
        assertEquals(10, cost(g, first, nearCurb), "leaving the road is never penalised");
        assertEquals(10, cost(g, last, farCurb));
        for (int z = 3; z < 2 + width; z++) {
            assertEquals(10, cost(g, StreetWorld.node(g, 5, z), StreetWorld.node(g, 5, z + 1)));
            assertEquals(10, cost(g, StreetWorld.node(g, 5, z + 1), StreetWorld.node(g, 5, z)));
        }
        // Painted column 10: the whole crossing is paint, so no penalty anywhere.
        assertEquals(10, cost(g, StreetWorld.node(g, 10, 2), StreetWorld.node(g, 10, 3)));
        assertEquals(10, cost(g, StreetWorld.node(g, 10, width + 3), StreetWorld.node(g, 10, 2 + width)));
        assertEquals(NavSurface.CROSSING, StreetWorld.node(g, 10, 3).surface());
        assertEquals(NavSurface.ROAD, StreetWorld.node(g, 5, 3).surface());
    }

    @Test
    void penaltyFollowsTheConfiguredReach() {
        NavGraph g = StreetWorld.strip(5, LENGTH).build().scan(new StreetRules(3, 16, 0.4)).graph();
        assertEquals(10 + 60, cost(g, StreetWorld.node(g, 5, 2), StreetWorld.node(g, 5, 3)));
        NavGraph none = StreetWorld.strip(5, LENGTH).build().scan(new StreetRules(0, 16, 0.4)).graph();
        assertEquals(10, cost(none, StreetWorld.node(none, 5, 2), StreetWorld.node(none, 5, 3)));
    }

    // S8
    @Test
    void everyRayEdgeExistsInBothDirections() {
        for (NavGraph g : fixtures()) {
            for (NavGraph.Edge e : g.edges()) {
                if (!street(g, e.from()) && !street(g, e.to())) continue;
                assertTrue(g.edges().stream().anyMatch(r -> r.from() == e.to() && r.to() == e.from()), "reverse of " + e);
            }
        }
    }

    // ---- crosswalk vs plain, with the DEV c001 shapes ----

    private static StreetWorld devRoad() {
        // Nine-wide road, solid edge lines (z 3 and 11), a lane dash in the middle (z 7) every fourth column,
        // and a zebra stripe across the road at column 12. All the paint is crossing material.
        var grid = StreetWorld.strip(9, 30);
        grid.fill(0, 3, 29, 3, 'W').fill(0, 11, 29, 11, 'W');
        for (int x = 0; x < 30; x += 4) grid.set(x, 7, 'W');
        grid.fill(12, 3, 12, 11, 'W');
        return grid.build();
    }

    @Test
    void aZebraStripeIsACrosswalkWhileDashesAndEdgeLinesAreAPlainCrossing() {
        var result = devRoad().scan(RULES);
        NavGraph g = result.graph();
        // Column 12 is all paint: 9 of 9. Column 8 sees both edge lines and a dash: 3 of 9, under 0.4.
        assertEquals(10, cost(g, StreetWorld.node(g, 12, 2), StreetWorld.node(g, 12, 3)));
        assertEquals(10 + RULES.entryPenalty(), cost(g, StreetWorld.node(g, 8, 2), StreetWorld.node(g, 8, 3)),
                "a mid-block crossing over dashes and edge lines is a plain crossing");
        assertEquals(10 + RULES.entryPenalty(), cost(g, StreetWorld.node(g, 9, 2), StreetWorld.node(g, 9, 3)));
        assertEquals(1, result.stats().crosswalkRays());
        assertEquals(29, result.stats().plainRays());
        assertEquals(NavSurface.CROSSING, StreetWorld.node(g, 8, 3).surface(), "paint cells keep their class");
    }

    @Test
    void theShareThresholdIsTheConfiguredFraction() {
        // Column 12 has paint on 3 of its 7 inner cells (0.43): a crosswalk at 0.4, plain at 0.5.
        var world = StreetWorld.strip(9, 30).fill(12, 4, 12, 6, 'W').build();
        assertEquals(1, world.scan(RULES).stats().crosswalkRays());
        assertEquals(1, world.scan(new StreetRules(12, 16, 0.3)).stats().crosswalkRays());
        assertEquals(0, world.scan(new StreetRules(12, 16, 0.5)).stats().crosswalkRays());
    }

    @Test
    void aLoneLaneDashNeverMakesACrosswalkHoweverLowTheShareIsSet() {
        // devRoad has a dash every fourth column and edge lines on both sides, and one real zebra at column 12.
        var result = devRoad().scan(new StreetRules(12, 16, 0.05));
        assertEquals(1, result.stats().crosswalkRays(), "only the zebra");
    }

    @Test
    void theMeasuredDottedZebrasOfTheDevMapAreCrosswalks() {
        // c001 zebras are dotted, not solid: a nine-wide road reads r W r W r W r W r and an eleven-wide
        // one W r W r W r W r W r W (edge paint included). Over the whole ray the second scores 6/11, but
        // its inner cells score 4/9 = 0.44, which is what the share is measured on.
        var nine = StreetWorld.strip(9, 30);
        for (int z : new int[]{4, 6, 8, 10}) nine.set(12, z, 'W');
        var r9 = nine.build().scan(RULES);
        assertEquals(1, r9.stats().crosswalkRays());
        assertEquals(10, cost(r9.graph(), StreetWorld.node(r9.graph(), 12, 2), StreetWorld.node(r9.graph(), 12, 3)),
                "a dotted zebra is entered at the plain step cost");
        var eleven = StreetWorld.strip(11, 30);
        for (int z : new int[]{3, 5, 7, 9, 11, 13}) eleven.set(12, z, 'W');
        var r11 = eleven.build().scan(RULES);
        assertEquals(1, r11.stats().crosswalkRays());
    }

    @Test
    void edgeLinesOnBothSidesOfAShortRoadDoNotMakeEveryCrossingACrosswalk() {
        // A five-wide road with a solid edge line on each side scored 2/5 = 0.4 over the whole ray.
        var world = StreetWorld.strip(5, 30);
        world.fill(0, 3, 29, 3, 'W').fill(0, 7, 29, 7, 'W');
        var result = world.build().scan(RULES);
        assertEquals(0, result.stats().crosswalkRays());
        assertEquals(30, result.stats().plainRays());
        NavGraph g = result.graph();
        assertEquals(10 + RULES.entryPenalty(), cost(g, StreetWorld.node(g, 9, 2), StreetWorld.node(g, 9, 3)));
        // One dash on top of the edge lines does not change that.
        var dashed = StreetWorld.strip(5, 30);
        dashed.fill(0, 3, 29, 3, 'W').fill(0, 7, 29, 7, 'W').set(8, 5, 'W');
        assertEquals(0, dashed.build().scan(RULES).stats().crosswalkRays());
    }

    /**
     * Two sidewalk platforms joined by a one-block lane eight blocks long. Road material sits on both sides of the
     * lane, {@code lift} blocks higher (0 puts it at the lane's own level).
     */
    private static StreetWorld laneBetweenPlatforms(int lift) {
        var world = StreetWorld.grid(0, -4, 16, 11).fill(0, 0, 3, 2, 'S').fill(12, 0, 15, 2, 'S').fill(4, 1, 11, 1, 'R');
        for (int x = 4; x <= 11; x++) {
            for (int z : new int[]{-4, -3, -2, -1, 0, 2, 3, 4, 5, 6}) {
                world.set(x, z, 'R');
                if (lift > 0) world.raise(x, z, lift);
            }
        }
        return world.build();
    }

    @Test
    void roadAtAnotherElevationBesideALaneDoesNotMakeTheLaneWideEnoughToWalkAlong() {
        // Control: with the neighbouring road at the lane's own level it really is a wide road, so the lane is a crossing.
        var level = laneBetweenPlatforms(0).scan(RULES);
        assertFalse(StreetWorld.nodes(level.graph(), 8, 1).isEmpty());
        // Sixteen blocks higher those cells are not part of the lane's road: it is a one-block strip running lengthwise.
        var lifted = laneBetweenPlatforms(16).scan(RULES);
        assertTrue(StreetWorld.nodes(lifted.graph(), 8, 1).isEmpty());
        assertEquals(0, lifted.stats().streetNodes());
        assertEquals(2, lifted.stats().dropped().get(NavScanLogic.Drop.ALONG_ROAD), "once from each platform");
        // One block higher is still a step a resident can take, so it counts as the same road.
        assertFalse(StreetWorld.nodes(laneBetweenPlatforms(1).scan(RULES).graph(), 8, 1).isEmpty());
        // Two blocks is not.
        assertTrue(StreetWorld.nodes(laneBetweenPlatforms(2).scan(RULES).graph(), 8, 1).isEmpty());
    }

    @Test
    void aThreeWideCrossingWithOneInnerPaintCellIsPlainAndPaysTheEntryPenalty() {
        // Column 10 is road, paint, road: one painted inner cell is below the two a crosswalk needs.
        var result = StreetWorld.strip(3, LENGTH).set(10, 4, 'W').build().scan(RULES);
        assertEquals(0, result.stats().crosswalkRays());
        assertEquals(LENGTH, result.stats().plainRays());
        NavGraph g = result.graph();
        assertEquals(10 + RULES.entryPenalty(), cost(g, StreetWorld.node(g, 10, 2), StreetWorld.node(g, 10, 3)));
        assertEquals(10 + RULES.entryPenalty(), cost(g, StreetWorld.node(g, 10, 6), StreetWorld.node(g, 10, 5)));
        // Even a fully painted three-block crossing has just the one inner cell.
        var solid = StreetWorld.strip(3, LENGTH).fill(10, 3, 10, 5, 'W').build().scan(RULES);
        assertEquals(0, solid.stats().crosswalkRays());
    }

    @Test
    void aSingleBlockOfPaintAcrossAOneBlockRoadIsNotACrosswalk() {
        var result = StreetWorld.strip(1, 12).set(5, 3, 'W').build().scan(RULES);
        assertEquals(0, result.stats().crosswalkRays());
        assertEquals(12, result.stats().plainRays());
    }

    @Test
    void accessGroundIsWalkableButNeverStartsOrEndsACrossing() {
        // A plaza of access blocks on the far side: the road meets it, so there is nothing to land on.
        var world = StreetWorld.grid(0, 0, 8, 9).fill(0, 0, 7, 2, 'S').fill(0, 3, 7, 5, 'R').fill(0, 6, 7, 8, 'A').build();
        var result = world.scan(RULES);
        assertEquals(0, result.stats().streetNodes());
        assertEquals(8, result.stats().dropped().get(NavScanLogic.Drop.NOT_SIDEWALK));
        // Access still joins the sidewalk it touches.
        NavGraph g = result.graph();
        assertTrue(g.nodes().stream().anyMatch(n -> n.surface() == NavSurface.ACCESS));
    }

    @Test
    void aStepTooHighOnTheFarSideDropsTheCrossing() {
        var world = StreetWorld.strip(5, 8).raise(3, 8, 2).raise(3, 9, 2).raise(3, 10, 2).build();
        var result = world.scan(RULES);
        assertEquals(1, result.stats().dropped().get(NavScanLogic.Drop.STEP));
        assertTrue(StreetWorld.nodes(result.graph(), 3, 4).isEmpty());
        assertEquals(7 * 5, result.stats().streetNodes());
    }

    @Test
    void statsCountNodesPerClassAndRaysByKind() {
        var result = StreetWorld.strip(5, LENGTH).fill(10, 3, 10, 7, 'W').build().scan(RULES);
        assertEquals(LENGTH * 6, result.stats().nodes().get(NavSurface.SIDEWALK));
        assertEquals(5 * (LENGTH - 1), result.stats().nodes().get(NavSurface.ROAD));
        assertEquals(5, result.stats().nodes().get(NavSurface.CROSSING));
        assertEquals(1, result.stats().crosswalkRays());
        assertEquals(LENGTH - 1, result.stats().plainRays());
        assertEquals(result.graph().nodeCount(), result.stats().nodes().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void anEverywhereUnclassifiedPaletteBuildsNoCrossingsAtAll() {
        // The same drawing scanned with no classes: roads are ordinary ground, exactly as before streets existed.
        NavGraph g = StreetWorld.strip(5, LENGTH).build(false).scan(RULES).graph();
        assertTrue(StreetWorld.street(g).isEmpty());
        assertTrue(g.nodes().stream().allMatch(n -> n.surface() == NavSurface.UNKNOWN));
        assertEquals(LENGTH * 11, g.nodeCount());
    }

    @Test
    void aDiagonalNeverCutsTheCornerOfAOneBlockRoad() {
        // Sidewalk at (0,0) and (1,1) with road on the other two corners: a diagonal would step across the road.
        var world = StreetWorld.grid(0, 0, 4, 4).fill(0, 0, 3, 3, 'S').set(1, 0, 'R').set(0, 1, 'R').build();
        NavGraph g = world.graph();
        NavGraph.Node a = StreetWorld.node(g, 0, 0);
        NavGraph.Node b = StreetWorld.node(g, 1, 1);
        assertTrue(g.neighbors(a.id()).stream().noneMatch(e -> e.to() == b.id()), "no diagonal over the road corner");
        assertTrue(g.neighbors(b.id()).stream().noneMatch(e -> e.to() == a.id()));
        // Control: the same drawing with ordinary ground on the two corners has the diagonal.
        NavGraph open = StreetWorld.grid(0, 0, 4, 4).fill(0, 0, 3, 3, 'S').build().graph();
        assertTrue(open.neighbors(StreetWorld.node(open, 0, 0).id()).stream()
                .anyMatch(e -> e.to() == StreetWorld.node(open, 1, 1).id() && e.cost() == NavScanLogic.DIAGONAL_COST));
    }

    @Test
    void aBoxThatClipsAWideRoadNeverTurnsItsLengthIntoACrossing() {
        // A 5x5 block of road ringed by sidewalk. Scanned whole, it can be crossed either way.
        var whole = StreetWorld.grid(0, 0, 11, 7).fill(0, 0, 10, 6, 'S').fill(3, 1, 7, 5, 'R').build().scan(RULES);
        assertEquals(50, whole.stats().streetNodes(), "five lanes in each direction");
        // Scanned with the box ending halfway across, the road looks only two wide and five long: that is
        // its length, not a crossing. The box edge can drop a crossing but never invent one.
        var clipped = StreetWorld.grid(0, 0, 11, 3).fill(0, 0, 10, 2, 'S').fill(3, 1, 7, 2, 'R').build().scan(RULES);
        assertEquals(0, clipped.stats().streetNodes());
        assertTrue(clipped.stats().dropped().get(NavScanLogic.Drop.BOX_EDGE) >= 9);
        assertNull(clipped.stats().dropped().get(NavScanLogic.Drop.ALONG_ROAD));
    }

    @Test
    void paintInsideACrossingNeverAddsCostAndOnlyTheEntryOfAPlainOneIsCharged() {
        int penalty = RULES.entryPenalty();
        // Column 12 alternates road and paint (R W R W R: 2 of the 3 inner cells, a crosswalk); column 4 has one dash (plain).
        var world = StreetWorld.strip(5, LENGTH).set(12, 4, 'W').set(12, 6, 'W').set(4, 5, 'W').build();
        NavGraph g = world.graph();
        for (int z = 2; z < 8; z++) {
            assertEquals(10, cost(g, StreetWorld.node(g, 12, z), StreetWorld.node(g, 12, z + 1)), "crosswalk down z=" + z);
            assertEquals(10, cost(g, StreetWorld.node(g, 12, z + 1), StreetWorld.node(g, 12, z)), "crosswalk up z=" + z);
        }
        assertEquals(10 + penalty, cost(g, StreetWorld.node(g, 4, 2), StreetWorld.node(g, 4, 3)));
        for (int z = 3; z < 7; z++) {
            assertEquals(10, cost(g, StreetWorld.node(g, 4, z), StreetWorld.node(g, 4, z + 1)), "plain down z=" + z);
            assertEquals(10, cost(g, StreetWorld.node(g, 4, z + 1), StreetWorld.node(g, 4, z)), "plain up z=" + z);
        }
        assertEquals(10 + penalty, cost(g, StreetWorld.node(g, 4, 8), StreetWorld.node(g, 4, 7)), "entered from the far curb");
        assertEquals(10, cost(g, StreetWorld.node(g, 4, 7), StreetWorld.node(g, 4, 8)));
    }
}
