package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Link;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSetCheck.Issue;
import com.prosperitysmp.npc.nav.waypoint.WaypointSetCheck.Report;
import com.prosperitysmp.npc.nav.waypoint.WaypointSetCheck.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules a waypoint set keeps before it is built: each one's code, exact message, severity and boundary. */
class WaypointSetCheckTest {
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    private static final WaypointDensifier.Limits CAPS = WaypointDensifier.Limits.DEFAULT;

    private static Point point(String name, int x, int y, int z, String... tags) {
        return new Point(name, x, y, z, PointKind.SIDEWALK, new TreeSet<>(Set.of(tags)));
    }

    private static Point access(String name, int x, int y, int z) {
        return new Point(name, x, y, z, PointKind.ACCESS, new TreeSet<>());
    }

    /** Built with {@link WaypointSet#of}, which keeps whatever a file contained, so the check sees raw problems. */
    private static WaypointSet raw(List<Point> points, Link... links) {
        return WaypointSet.of(points, List.of(links));
    }

    private static Report check(WaypointSet set) {
        return WaypointSetCheck.check(set, RULES, 1, CAPS);
    }

    private static List<String> codes(Report report) {
        return report.issues().stream().map(Issue::code).toList();
    }

    private static Issue only(Report report, String code) {
        List<Issue> matching = report.issues().stream().filter(i -> i.code().equals(code)).toList();
        assertEquals(1, matching.size(), report.issues().toString());
        return matching.get(0);
    }

    // ---- a clean set ----

    @Test
    void anEmptySetIsOkWithOnlyTheNoStopsWarning() {
        Report report = check(WaypointSet.EMPTY);
        assertTrue(report.ok());
        assertEquals(List.of("WP_NO_STOPS"), codes(report));
        assertEquals(0, report.errors());
        assertEquals(1, report.warnings());
        assertEquals(0, report.points());
        assertEquals(0, report.links());
        assertEquals(0, report.cells());
        assertEquals(0, report.edges());
    }

    @Test
    void aTaggedWalkOfFiveBlocksIsCleanAndCountsItsBuiltCellsAndEdges() {
        WaypointSet set = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 5)),
                new Link("a", "b", LinkKind.WALK));
        Report report = check(set);
        assertTrue(report.ok());
        assertEquals(List.of(), report.issues());
        assertEquals(2, report.points());
        assertEquals(1, report.links());
        assertEquals(6, report.cells());
        assertEquals(10, report.edges());
    }

    @Test
    void aCrossingWithFourRoadBlocksBetweenTwoSidewalkPointsIsCleanAndCounted() {
        WaypointSet set = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 5)),
                new Link("a", "b", LinkKind.CROSS));
        Report report = check(set);
        assertTrue(report.ok(), report.issues().toString());
        assertEquals(6, report.cells());
        assertEquals(10, report.edges());
    }

    // ---- point rules ----

    @ParameterizedTest
    @ValueSource(strings = {"Bad", "has space", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "dot.name", "é"})
    void aPointNameOutsideTheNameRuleIsAnErrorThatNamesIt(String name) {
        Report report = check(raw(List.of(point(name, 0, 64, 0, "home"))));
        assertFalse(report.ok());
        assertEquals("point " + name + ": names are 1 to 32 of a-z, 0-9, _ and -", only(report, "WP_NAME").message());
        assertEquals(Severity.ERROR, only(report, "WP_NAME").severity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "z9", "_", "-", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "0"})
    void aPointNameInsideTheNameRuleIsClean(String name) {
        Report report = check(raw(List.of(point(name, 0, 64, 0, "home"))));
        assertTrue(report.ok(), report.issues().toString());
        assertFalse(codes(report).contains("WP_NAME"));
    }

    @Test
    void twoPointsOnTheSameBlockAreReportedOnceInNameOrder() {
        Report report = check(raw(List.of(point("b", 4, 70, -2, "home"), point("a", 4, 70, -2))));
        assertEquals("points a and b both stand at 4,70,-2", only(report, "WP_DUP_CELL").message());
        assertFalse(report.ok());
    }

    @Test
    void threePointsOnTheSameBlockAreReportedTwice() {
        Report report = check(raw(List.of(point("a", 1, 64, 1), point("b", 1, 64, 1), point("c", 1, 64, 1))));
        assertEquals(2, codes(report).stream().filter("WP_DUP_CELL"::equals).count());
        assertEquals("points a and b both stand at 1,64,1", report.issues().get(0).message());
        assertEquals("points a and c both stand at 1,64,1", report.issues().get(1).message());
    }

    @Test
    void sameColumnAtAnotherHeightIsAnotherBlock() {
        Report report = check(raw(List.of(point("a", 1, 64, 1, "home"), point("b", 1, 65, 1))));
        assertFalse(codes(report).contains("WP_DUP_CELL"));
    }

    @Test
    void aTagThatIsNotARouteTagIsAnErrorNamingThePoint() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "bank", "home"))));
        assertEquals("point a has a tag that is not a route tag", only(report, "WP_TAG").message());
        assertFalse(report.ok());
    }

    @Test
    void everyBadTagIsReportedOnItsOwn() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "bank", "mine", "home"))));
        assertEquals(2, codes(report).stream().filter("WP_TAG"::equals).count());
    }

    @Test
    void eightValidTagsAreFineAndANinthIsOverTheCap() {
        String uuid = new UUID(0, 1).toString();
        Point eight = point("a", 0, 64, 0, "spawn", "home", "work", "market", "leisure", "work:cook", "work:courier", "home:" + uuid);
        assertEquals(8, eight.tags().size());
        assertTrue(check(raw(List.of(eight))).ok());
        Point nine = point("a", 0, 64, 0, "spawn", "home", "work", "market", "leisure", "work:cook", "work:courier", "home:" + uuid,
                "market:" + uuid);
        Report report = check(raw(List.of(nine)));
        assertEquals("point a has more than 8 tags", only(report, "WP_TAG_CAP").message());
        assertFalse(report.ok());
    }

    // ---- link rules ----

    @Test
    void aLinkFromAPointToItselfIsAnErrorAndIsSkippedByTheRest() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home")), new Link("a", "a", LinkKind.WALK)));
        assertEquals("link a-a joins a point to itself", only(report, "WP_LINK_SELF").message());
        assertEquals(1, report.errors());
    }

    @Test
    void aLinkToAPointThatIsNotInTheSetNamesTheMissingEnd() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home")), new Link("a", "zz", LinkKind.WALK)));
        assertEquals("link a-zz names no point zz", only(report, "WP_LINK_MISSING").message());
        Report firstEndMissing = check(raw(List.of(point("m", 0, 64, 0, "home")), new Link("a", "m", LinkKind.WALK)));
        assertEquals("link a-m names no point a", only(firstEndMissing, "WP_LINK_MISSING").message());
    }

    @Test
    void aLinkWithBothEndsMissingNamesTheFirstEnd() {
        Report report = check(raw(List.of(point("m", 0, 64, 0, "home")), new Link("x", "y", LinkKind.WALK)));
        assertEquals("link x-y names no point x", only(report, "WP_LINK_MISSING").message());
    }

    @Test
    void aDiagonalLinkAndALinkBetweenTwoPointsOnOneBlockBothNeedABend() {
        Report diagonal = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 3, 64, 4)), new Link("a", "b", LinkKind.WALK)));
        assertEquals("link a-b does not run straight along x or z; add a point at the bend", only(diagonal, "WP_NOT_AXIS").message());
        Report stacked = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 70, 0)), new Link("a", "b", LinkKind.WALK)));
        assertFalse(codes(stacked).contains("WP_DUP_CELL"), "different heights are different blocks");
        assertEquals("link a-b does not run straight along x or z; add a point at the bend", only(stacked, "WP_NOT_AXIS").message());
    }

    @Test
    void aClimbOfExactlyOneBlockPerBlockIsAllowedAndOneMoreIsTooSteep() {
        Report allowed = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 3, 67, 0)), new Link("a", "b", LinkKind.WALK)));
        assertTrue(allowed.ok(), allowed.issues().toString());
        Report steep = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 3, 68, 0)), new Link("a", "b", LinkKind.WALK)));
        assertEquals("link a-b climbs 4 over 3 blocks", only(steep, "WP_TOO_STEEP").message());
    }

    @Test
    void aDescentIsJudgedLikeAClimb() {
        Report steep = check(raw(List.of(point("a", 0, 70, 0, "home"), point("b", 2, 67, 0)), new Link("a", "b", LinkKind.WALK)));
        assertEquals("link a-b climbs 3 over 2 blocks", only(steep, "WP_TOO_STEEP").message());
    }

    @Test
    void maxStepZeroMakesAnyClimbTooSteep() {
        WaypointSet set = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 5, 65, 0)), new Link("a", "b", LinkKind.WALK));
        assertTrue(WaypointSetCheck.check(set, RULES, 1, CAPS).ok());
        Report report = WaypointSetCheck.check(set, RULES, 0, CAPS);
        assertEquals("link a-b climbs 1 over 5 blocks", only(report, "WP_TOO_STEEP").message());
    }

    @Test
    void aLinkLongerThanTheNodeLimitIsReportedWithTheLimit() {
        WaypointDensifier.Limits tight = new WaypointDensifier.Limits(5, 100);
        WaypointSet set = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 5, 64, 0)), new Link("a", "b", LinkKind.WALK));
        Report report = WaypointSetCheck.check(set, RULES, 1, tight);
        assertEquals("Link a-b makes 6 nodes; the limit is 5. Split this district into two regions.",
                only(report, "WP_CAP_NODES").message());
        WaypointSet fits = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 4, 64, 0)), new Link("a", "b", LinkKind.WALK));
        assertTrue(WaypointSetCheck.check(fits, RULES, 1, tight).ok());
    }

    // ---- crossing rules ----

    @Test
    void aCrossingMustStartAndEndOnSidewalkPoints() {
        Report fromAccess = check(raw(List.of(access("a", 0, 64, 0), point("b", 0, 64, 5, "home")), new Link("a", "b", LinkKind.CROSS)));
        assertEquals("crossing a-b must start and end on sidewalk points", only(fromAccess, "WP_CROSS_ENDS").message());
        Report toAccess = check(raw(List.of(point("a", 0, 64, 0, "home"), access("b", 0, 64, 5)), new Link("a", "b", LinkKind.CROSSWALK)));
        assertEquals("crossing a-b must start and end on sidewalk points", only(toAccess, "WP_CROSS_ENDS").message());
    }

    @Test
    void aCrossingThatClimbsIsNotLevel() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 65, 6)), new Link("a", "b", LinkKind.CROSS)));
        assertEquals("crossing a-b is not level", only(report, "WP_CROSS_LEVEL").message());
    }

    @Test
    void aCrossingWithNoRoadBlockBetweenItsEndsIsTooShort() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 1)), new Link("a", "b", LinkKind.CROSS)));
        assertEquals("crossing a-b has 0 road blocks; it needs 1 to 16", only(report, "WP_CROSS_LEN").message());
    }

    @Test
    void aCrossingWithOneRoadBlockIsTheShortestAllowed() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 2)), new Link("a", "b", LinkKind.CROSS)));
        assertTrue(report.ok(), report.issues().toString());
    }

    @Test
    void aCrossingOfSixteenRoadBlocksIsAllowedAndSeventeenIsTooLong() {
        Report longest = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 17)), new Link("a", "b", LinkKind.CROSS)));
        assertTrue(longest.ok(), longest.issues().toString());
        Report tooLong = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 18)), new Link("a", "b", LinkKind.CROSS)));
        assertEquals("crossing a-b has 17 road blocks; it needs 1 to 16", only(tooLong, "WP_CROSS_LEN").message());
    }

    @Test
    void theCrossingLengthLimitFollowsTheStreetRules() {
        WaypointSet set = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 6)), new Link("a", "b", LinkKind.CROSSWALK));
        assertTrue(WaypointSetCheck.check(set, new StreetRules(12, 5, 0.4), 1, CAPS).ok());
        Report report = WaypointSetCheck.check(set, new StreetRules(12, 4, 0.4), 1, CAPS);
        assertEquals("crossing a-b has 5 road blocks; it needs 1 to 4", only(report, "WP_CROSS_LEN").message());
    }

    @Test
    void aCrossingThatBreaksSeveralRulesReportsEachOfThem() {
        Report report = check(raw(List.of(access("a", 0, 64, 0), point("b", 0, 65, 1, "home")), new Link("a", "b", LinkKind.CROSS)));
        assertEquals(List.of("WP_CROSS_ENDS", "WP_CROSS_LEVEL", "WP_CROSS_LEN"), codes(report));
    }

    @Test
    void aPointStandingOnTheRoadOfACrossingIsReportedWithItsBlock() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 6), point("c", 0, 64, 3)),
                new Link("a", "b", LinkKind.CROSS)));
        assertEquals("point c stands on crossing a-b at 0,64,3", only(report, "WP_CROSS_ON_POINT").message());
    }

    @Test
    void twoCrossingsThatShareARoadBlockMeetThereAndTheLaterOneIsNamedSecond() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 6),
                        point("c", -3, 64, 3), point("d", 3, 64, 3)),
                new Link("a", "b", LinkKind.CROSS), new Link("c", "d", LinkKind.CROSSWALK)));
        assertEquals("crossings a-b and c-d meet at 0,64,3", only(report, "WP_CROSS_JUNCTION").message());
    }

    @Test
    void aWalkLinkThatRunsOverACrossingIsReportedOncePerWalk() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 6),
                        point("e", -3, 64, 3), point("f", 3, 64, 3)),
                new Link("a", "b", LinkKind.CROSS), new Link("e", "f", LinkKind.WALK)));
        assertEquals("walk link e-f runs over crossing a-b at 0,64,3", only(report, "WP_WALK_ON_STREET").message());
    }

    @Test
    void aWalkThatMeetsACrossingOnlyAtItsCurbPointIsFine() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 6), point("e", -4, 64, 0)),
                new Link("a", "b", LinkKind.CROSS), new Link("e", "a", LinkKind.WALK)));
        assertTrue(report.ok(), report.issues().toString());
    }

    @Test
    void anUnusableCrossingDoesNotClaimRoadBlocksForJunctionOrWalkChecks() {
        Report report = check(raw(List.of(access("a", 0, 64, 0), point("b", 0, 64, 6, "home"),
                        point("e", -3, 64, 3), point("f", 3, 64, 3)),
                new Link("a", "b", LinkKind.CROSS), new Link("e", "f", LinkKind.WALK)));
        assertEquals(List.of("WP_CROSS_ENDS", "WP_DISCONNECTED"), codes(report));
    }

    // ---- warnings ----

    @Test
    void anIsolatedPointIsAWarningNotAnError() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 4), point("lone", 9, 64, 9)),
                new Link("a", "b", LinkKind.WALK)));
        assertTrue(report.ok());
        Issue issue = only(report, "WP_ISOLATED");
        assertEquals(Severity.WARNING, issue.severity());
        assertEquals("point lone has no links", issue.message());
        assertEquals(1, report.warnings());
    }

    @Test
    void groupsOfLinkedPointsAreCountedAndOneGroupIsNotAWarning() {
        WaypointSet two = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 4), point("c", 9, 64, 0), point("d", 9, 64, 4)),
                new Link("a", "b", LinkKind.WALK), new Link("c", "d", LinkKind.WALK));
        assertEquals("2 separate groups of linked points", only(check(two), "WP_DISCONNECTED").message());
        WaypointSet three = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 4), point("c", 9, 64, 0), point("d", 9, 64, 4),
                        point("e", 20, 64, 0), point("f", 20, 64, 4)),
                new Link("a", "b", LinkKind.WALK), new Link("c", "d", LinkKind.WALK), new Link("e", "f", LinkKind.WALK));
        assertEquals("3 separate groups of linked points", only(check(three), "WP_DISCONNECTED").message());
        WaypointSet one = raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 4), point("c", 0, 64, 8)),
                new Link("a", "b", LinkKind.WALK), new Link("b", "c", LinkKind.WALK));
        assertEquals(List.of(), check(one).issues());
    }

    @Test
    void aTagOnAnyPointRemovesTheNoStopsWarning() {
        WaypointSet untagged = raw(List.of(point("a", 0, 64, 0), point("b", 0, 64, 4)), new Link("a", "b", LinkKind.WALK));
        Report report = check(untagged);
        assertEquals("no point has a tag, so residents have nowhere to go", only(report, "WP_NO_STOPS").message());
        assertEquals(List.of(), check(raw(List.of(point("a", 0, 64, 0), point("b", 0, 64, 4, "market")),
                new Link("a", "b", LinkKind.WALK))).issues());
    }

    @Test
    void warningsAreAddedEvenWhenThereAreErrors() {
        Report report = check(raw(List.of(point("a", 0, 64, 0), point("b", 3, 64, 4)), new Link("a", "b", LinkKind.WALK)));
        assertEquals(List.of("WP_NOT_AXIS", "WP_NO_STOPS"), codes(report));
        assertEquals(1, report.errors());
        assertEquals(1, report.warnings());
    }

    @Test
    void aLinkWithAMissingEndDoesNotCountTowardGroups() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 4)),
                new Link("a", "ghost", LinkKind.WALK), new Link("b", "ghost", LinkKind.WALK)));
        assertEquals(2, codes(report).stream().filter("WP_ISOLATED"::equals).count());
        assertFalse(codes(report).contains("WP_DISCONNECTED"));
    }

    // ---- the report ----

    @Test
    void aSetWithErrorsIsNotBuiltSoItsCellAndEdgeCountsAreZero() {
        Report report = check(raw(List.of(point("a", 0, 64, 0, "home"), point("b", 0, 64, 5), point("Bad", 4, 64, 4)),
                new Link("a", "b", LinkKind.WALK)));
        assertFalse(report.ok());
        assertEquals(3, report.points());
        assertEquals(1, report.links());
        assertEquals(0, report.cells());
        assertEquals(0, report.edges());
    }

    @Test
    void anIssueLineIsItsCodeThenItsMessage() {
        Issue issue = new Issue(Severity.ERROR, "WP_X", "something broke");
        assertEquals("WP_X something broke", issue.line());
    }

    @Test
    void errorsAndWarningsAreCountedBySeverity() {
        Report report = new Report(List.of(new Issue(Severity.ERROR, "A", "a"), new Issue(Severity.WARNING, "B", "b"),
                new Issue(Severity.ERROR, "C", "c")), 0, 0, 0, 0);
        assertEquals(2, report.errors());
        assertEquals(1, report.warnings());
        assertFalse(report.ok());
    }

    @Test
    void aReportWithOnlyWarningsIsOk() {
        Report report = new Report(List.of(new Issue(Severity.WARNING, "B", "b")), 0, 0, 0, 0);
        assertTrue(report.ok());
    }

    @Test
    void aReportKeepsItsOwnCopyOfTheIssues() {
        List<Issue> source = new java.util.ArrayList<>(List.of(new Issue(Severity.WARNING, "B", "b")));
        Report report = new Report(source, 0, 0, 0, 0);
        source.add(new Issue(Severity.ERROR, "C", "c"));
        assertEquals(1, report.issues().size());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().clear());
    }
}
