package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Link;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edits to a waypoint set: each refusal's code and message, the caps at their boundaries, and what an edit leaves alone. */
class WaypointSetEditsTest {
    private static final Limits LIMITS = Limits.DEFAULT;

    private static Point at(String name, int x, int y, int z) {
        return new Point(name, x, y, z, PointKind.SIDEWALK, null);
    }

    private static WaypointSet three() {
        return WaypointSet.EMPTY.addPoint(at("a", 0, 64, 0), LIMITS).addPoint(at("b", 0, 64, 5), LIMITS)
                .addPoint(at("c", 5, 64, 5), LIMITS);
    }

    private static EditRefused refused(Runnable edit) {
        return assertThrows(EditRefused.class, edit::run);
    }

    // ---- the word types ----

    @ParameterizedTest
    @CsvSource({"sidewalk,SIDEWALK", "access,ACCESS"})
    void aPointKindIsParsedFromItsLowercaseWordAndWritesItBack(String word, PointKind kind) {
        assertSame(kind, PointKind.parse(word));
        assertEquals(word, kind.word());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sidewalk", "ACCESS", "road", "", " access", "access "})
    void anyOtherPointKindWordIsRefused(String word) {
        assertEquals("unknown point kind", assertThrows(IllegalArgumentException.class, () -> PointKind.parse(word)).getMessage());
    }

    @ParameterizedTest
    @CsvSource({"walk,WALK,false", "cross,CROSS,true", "crosswalk,CROSSWALK,true"})
    void aLinkKindIsParsedFromItsWordAndOnlyWalkIsNotACrossing(String word, LinkKind kind, boolean crossing) {
        assertSame(kind, LinkKind.parse(word));
        assertEquals(word, kind.word());
        assertEquals(crossing, kind.crossing());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Walk", "CROSS", "cross-walk", "", "road", "cross "})
    void anyOtherLinkKindWordIsRefused(String word) {
        assertEquals("unknown link kind", assertThrows(IllegalArgumentException.class, () -> LinkKind.parse(word)).getMessage());
    }

    @Test
    void theDocumentedLimitsAndFormatAreUnchanged() {
        assertEquals(new Limits(600, 1200), Limits.DEFAULT);
        assertEquals("prosperity-nav-waypoints/1", WaypointSet.FORMAT);
        assertEquals(8, WaypointSet.MAX_TAGS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "_", "-", "a-b_c9", "abcdefghijklmnopqrstuvwxyz012345"})
    void namesInsideTheRuleMatch(String name) {
        assertTrue(WaypointSet.NAME.matcher(name).matches(), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "a b", "a.b", "abcdefghijklmnopqrstuvwxyz0123456", "a\n", "é", "a/b"})
    void namesOutsideTheRuleDoNotMatch(String name) {
        assertFalse(WaypointSet.NAME.matcher(name).matches(), name);
    }

    // ---- points and links as values ----

    @Test
    void aPointKeepsItsTagsSortedAndCannotBeChangedThroughThem() {
        Point point = new Point("a", 1, 2, 3, PointKind.SIDEWALK, new TreeSet<>(Set.of("work", "home", "market")));
        assertEquals(List.of("home", "market", "work"), List.copyOf(point.tags()));
        assertThrows(UnsupportedOperationException.class, () -> point.tags().add("leisure"));
    }

    @Test
    void aPointMustHaveANameAndAKind() {
        assertThrows(NullPointerException.class, () -> new Point(null, 0, 0, 0, PointKind.SIDEWALK, null));
        assertThrows(NullPointerException.class, () -> new Point("a", 0, 0, 0, null, null));
    }

    @Test
    void movedKeepsTheNameKindAndTagsAndChangesOnlyTheBlock() {
        Point point = new Point("a", 1, 2, 3, PointKind.ACCESS, new TreeSet<>(Set.of("home")));
        Point moved = point.moved(7, 8, 9);
        assertEquals(new Point("a", 7, 8, 9, PointKind.ACCESS, new TreeSet<>(Set.of("home"))), moved);
        assertEquals(1, point.x(), "the original does not move");
    }

    @Test
    void withTagsReplacesTheWholeTagSetAndKeepsThePosition() {
        Point point = new Point("a", 1, 2, 3, PointKind.ACCESS, new TreeSet<>(Set.of("home")));
        Point other = point.withTags(List.of("work", "market"));
        assertEquals(List.of("market", "work"), List.copyOf(other.tags()));
        assertEquals(1, other.x());
        assertEquals(PointKind.ACCESS, other.kind());
        assertEquals(Set.of("home"), point.tags());
    }

    @Test
    void theCellOfAPointIsItsThreeCoordinatesJoinedByCommas() {
        assertEquals("-4,70,12", at("a", -4, 70, 12).cell());
    }

    @Test
    void aLinkIsStoredWithItsSmallerEndFirstWhicheverWayItWasWritten() {
        Link link = new Link("zed", "abe", LinkKind.CROSS);
        assertEquals("abe", link.a());
        assertEquals("zed", link.b());
        assertEquals("abe-zed", link.label());
        assertEquals("abe\u0000zed", link.key());
        assertEquals(new Link("abe", "zed", LinkKind.CROSS), link);
    }

    @Test
    void aLinkEndWithABadNameIsRefusedWithTheNameRuleMessage() {
        EditRefused failure = assertThrows(EditRefused.class, () -> new Link("Bad", "ok", LinkKind.WALK));
        assertEquals("WP_NAME", failure.code());
        assertEquals("Link ends are point names: 1 to 32 of a-z, 0-9, _ and -.", failure.getMessage());
    }

    @Test
    void anEditRefusedIsAnIllegalArgumentExceptionCarryingItsCode() {
        EditRefused failure = new EditRefused("WP_X", "message");
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals("WP_X", failure.code());
        assertEquals("message", failure.getMessage());
    }

    @Test
    void byCellOrdersByXThenYThenZ() {
        List<Point> points = new ArrayList<>(List.of(at("p5", 1, 1, 1), at("p4", 1, 0, 9), at("p3", 0, 5, 5), at("p2", 0, 5, 4),
                at("p1", -1, 9, 9)));
        points.sort(WaypointSet.byCell());
        assertEquals(List.of("p1", "p2", "p3", "p4", "p5"), points.stream().map(Point::name).toList());
    }

    // ---- reading a set ----

    @Test
    void pointsAndLinksComeBackSortedAndAreCopies() {
        WaypointSet set = WaypointSet.EMPTY.addPoint(at("m", 0, 64, 0), LIMITS).addPoint(at("c", 0, 64, 4), LIMITS)
                .addPoint(at("x", 4, 64, 0), LIMITS)
                .link("x", "m", LinkKind.WALK, LIMITS).link("m", "c", LinkKind.WALK, LIMITS);
        assertEquals(List.of("c", "m", "x"), set.points().stream().map(Point::name).toList());
        assertEquals(List.of("c-m", "m-x"), set.links().stream().map(Link::label).toList());
        assertThrows(UnsupportedOperationException.class, () -> set.points().clear());
        assertThrows(UnsupportedOperationException.class, () -> set.links().clear());
    }

    @Test
    void pointLookupFindsByNameAndMissesWithNull() {
        WaypointSet set = three();
        assertEquals(5, set.point("c").x());
        assertNull(set.point("nope"));
    }

    @Test
    void linkLookupIgnoresTheOrderOfTheEnds() {
        WaypointSet set = three().link("c", "a", LinkKind.CROSSWALK, LIMITS);
        assertEquals(LinkKind.CROSSWALK, set.link("a", "c").kind());
        assertEquals(LinkKind.CROSSWALK, set.link("c", "a").kind());
        assertNull(set.link("a", "b"));
    }

    @Test
    void linksOfListsEveryLinkTouchingThePointInOrder() {
        WaypointSet set = three().link("a", "b", LinkKind.WALK, LIMITS).link("c", "a", LinkKind.CROSS, LIMITS)
                .link("b", "c", LinkKind.WALK, LIMITS);
        assertEquals(List.of("a-b", "a-c"), set.linksOf("a").stream().map(Link::label).toList());
        assertEquals(List.of("a-b", "b-c"), set.linksOf("b").stream().map(Link::label).toList());
        assertEquals(List.of(), set.linksOf("nobody"));
    }

    @Test
    void ofKeepsTheLastPointWithAGivenNameAndOneLinkPerPair() {
        WaypointSet set = WaypointSet.of(List.of(at("a", 0, 64, 0), at("a", 9, 64, 9), at("b", 0, 64, 4)),
                List.of(new Link("a", "b", LinkKind.WALK), new Link("b", "a", LinkKind.CROSS)));
        assertEquals(2, set.points().size());
        assertEquals(9, set.point("a").x());
        assertEquals(1, set.links().size());
        assertEquals(LinkKind.CROSS, set.link("a", "b").kind());
    }

    // ---- addPoint ----

    @Test
    void addingAPointNeverChangesTheEmptySet() {
        WaypointSet added = WaypointSet.EMPTY.addPoint(at("a", 0, 64, 0), LIMITS);
        assertEquals(1, added.points().size());
        assertEquals(0, WaypointSet.EMPTY.points().size());
    }

    @Test
    void aPointNameThatBreaksTheRuleIsRefused() {
        EditRefused failure = refused(() -> WaypointSet.EMPTY.addPoint(at("Bad Name", 0, 64, 0), LIMITS));
        assertEquals("WP_NAME", failure.code());
        assertEquals("Point names are 1 to 32 of a-z, 0-9, _ and -.", failure.getMessage());
    }

    @Test
    void aRepeatedNameIsRefusedBeforeARepeatedBlock() {
        EditRefused failure = refused(() -> three().addPoint(at("a", 0, 64, 0), LIMITS));
        assertEquals("WP_DUP_NAME", failure.code());
        assertEquals("There is already a point named a.", failure.getMessage());
    }

    @Test
    void aRepeatedBlockNamesThePointAlreadyThere() {
        EditRefused failure = refused(() -> three().addPoint(at("new", 5, 64, 5), LIMITS));
        assertEquals("WP_DUP_CELL", failure.code());
        assertEquals("Point c already stands at 5,64,5.", failure.getMessage());
    }

    @Test
    void thePointCapRefusesTheNextPointAtExactlyTheLimitButNotBelowIt() {
        Limits two = new Limits(2, 10);
        WaypointSet one = WaypointSet.EMPTY.addPoint(at("a", 0, 64, 0), two);
        WaypointSet full = one.addPoint(at("b", 0, 64, 1), two);
        EditRefused failure = refused(() -> full.addPoint(at("c", 0, 64, 2), two));
        assertEquals("WP_CAP_POINTS", failure.code());
        assertEquals("This region already has 2 points (nav.waypoints.max-points). Split this district into two regions.",
                failure.getMessage());
    }

    @Test
    void aBadTagOnANewPointIsRefusedWithThePointName() {
        Point bad = new Point("a", 0, 64, 0, PointKind.SIDEWALK, new TreeSet<>(Set.of("bank")));
        EditRefused failure = refused(() -> WaypointSet.EMPTY.addPoint(bad, LIMITS));
        assertEquals("WP_TAG", failure.code());
        assertEquals("Point a has a tag that is not a route tag.", failure.getMessage());
    }

    @Test
    void aNewPointWithNineTagsIsOverTheTagCap() {
        String uuid = new UUID(1, 1).toString();
        TreeSet<String> tags = new TreeSet<>(Set.of("spawn", "home", "work", "market", "leisure", "work:cook", "work:courier",
                "home:" + uuid, "market:" + uuid));
        EditRefused failure = refused(() -> WaypointSet.EMPTY.addPoint(new Point("a", 0, 64, 0, PointKind.SIDEWALK, tags), LIMITS));
        assertEquals("WP_TAG_CAP", failure.code());
        assertEquals("Point a would have more than 8 tags.", failure.getMessage());
    }

    // ---- movePoint ----

    @Test
    void movingAPointChangesItsBlockAndKeepsItsLinks() {
        WaypointSet set = three().link("a", "b", LinkKind.WALK, LIMITS).movePoint("a", 0, 64, -3);
        assertEquals(-3, set.point("a").z());
        assertNotEquals(three().point("a").z(), set.point("a").z());
        assertEquals(1, set.links().size());
    }

    @Test
    void movingAPointOntoAnotherPointsBlockIsRefusedNamingThatPoint() {
        EditRefused failure = refused(() -> three().movePoint("a", 5, 64, 5));
        assertEquals("WP_DUP_CELL", failure.code());
        assertEquals("Point c already stands at 5,64,5.", failure.getMessage());
    }

    @Test
    void movingAPointToItsOwnBlockIsAllowed() {
        WaypointSet same = three().movePoint("a", 0, 64, 0);
        assertEquals(three(), same);
    }

    @Test
    void movingAPointThatIsNotThereIsRefused() {
        EditRefused failure = refused(() -> three().movePoint("zz", 1, 1, 1));
        assertEquals("WP_LINK_MISSING", failure.code());
        assertEquals("No point named zz.", failure.getMessage());
    }

    // ---- removePoint ----

    @Test
    void removingAPointThatIsNotThereIsRefused() {
        EditRefused failure = refused(() -> three().removePoint("zz"));
        assertEquals("No point named zz.", failure.getMessage());
    }

    @Test
    void removingAPointKeepsLinksBetweenTheOthers() {
        WaypointSet set = three().link("a", "b", LinkKind.WALK, LIMITS).link("b", "c", LinkKind.WALK, LIMITS)
                .link("a", "c", LinkKind.WALK, LIMITS).removePoint("a");
        assertEquals(List.of("b-c"), set.links().stream().map(Link::label).toList());
    }

    // ---- link and unlink ----

    @Test
    void linkingAPointToItselfIsRefusedNamingIt() {
        EditRefused failure = refused(() -> three().link("a", "a", LinkKind.WALK, LIMITS));
        assertEquals("WP_LINK_SELF", failure.code());
        assertEquals("A point cannot be linked to itself (a).", failure.getMessage());
    }

    @Test
    void linkingToAMissingPointNamesTheMissingOne() {
        assertEquals("No point named zz.", refused(() -> three().link("a", "zz", LinkKind.WALK, LIMITS)).getMessage());
        assertEquals("No point named zz.", refused(() -> three().link("zz", "a", LinkKind.WALK, LIMITS)).getMessage());
        assertEquals("No point named x.", refused(() -> three().link("x", "y", LinkKind.WALK, LIMITS)).getMessage());
    }

    @Test
    void linkingTheSamePairAgainChangesTheKindWithoutAddingALink() {
        WaypointSet walk = three().link("a", "b", LinkKind.WALK, LIMITS);
        WaypointSet crossing = walk.link("b", "a", LinkKind.CROSSWALK, LIMITS);
        assertEquals(1, crossing.links().size());
        assertEquals(LinkKind.CROSSWALK, crossing.link("a", "b").kind());
        assertEquals(LinkKind.WALK, walk.link("a", "b").kind());
    }

    @Test
    void theLinkCapRefusesANewLinkAtTheLimitButAllowsChangingAnExistingOne() {
        Limits one = new Limits(10, 1);
        WaypointSet set = three().link("a", "b", LinkKind.WALK, one);
        EditRefused failure = refused(() -> set.link("b", "c", LinkKind.WALK, one));
        assertEquals("WP_CAP_LINKS", failure.code());
        assertEquals("This region already has 1 links (nav.waypoints.max-links). Split this district into two regions.",
                failure.getMessage());
        assertEquals(LinkKind.CROSS, set.link("a", "b", LinkKind.CROSS, one).link("a", "b").kind());
    }

    @Test
    void unlinkingRemovesTheLinkInEitherOrder() {
        WaypointSet set = three().link("a", "b", LinkKind.WALK, LIMITS).link("b", "c", LinkKind.WALK, LIMITS);
        assertEquals(List.of("b-c"), set.unlink("b", "a").links().stream().map(Link::label).toList());
        assertEquals(List.of("a-b"), set.unlink("c", "b").links().stream().map(Link::label).toList());
    }

    @Test
    void unlinkingAPairThatIsNotLinkedNamesTheLink() {
        EditRefused failure = refused(() -> three().unlink("c", "a"));
        assertEquals("WP_LINK_MISSING", failure.code());
        assertEquals("There is no link a-c.", failure.getMessage());
    }

    // ---- tags ----

    @Test
    void addingATagTwiceLeavesOneAndRemovingItClearsIt() {
        WaypointSet tagged = three().tag("a", "home", false).tag("a", "home", false);
        assertEquals(Set.of("home"), tagged.point("a").tags());
        assertEquals(Set.of(), tagged.tag("a", "home", true).point("a").tags());
    }

    @Test
    void removingATagThePointNeverHadChangesNothing() {
        assertEquals(three(), three().tag("a", "home", true));
    }

    @Test
    void removingAnInvalidTagIsAllowedBecauseItCannotBeThere() {
        assertEquals(three(), three().tag("a", "bank", true));
    }

    @Test
    void addingAnInvalidTagIsRefusedWithTheListOfRouteTags() {
        EditRefused failure = refused(() -> three().tag("a", "bank", false));
        assertEquals("WP_TAG", failure.code());
        assertEquals("Not a route tag: use home, work, market, leisure, spawn, "
                + "home/work/market/leisure:<residentUUID>, or work:<occupation>.", failure.getMessage());
    }

    @Test
    void taggingAPointThatIsNotThereIsRefused() {
        assertEquals("No point named zz.", refused(() -> three().tag("zz", "home", false)).getMessage());
    }

    @Test
    void theNinthTagIsRefusedAndTheEighthIsNot() {
        String uuid = new UUID(2, 2).toString();
        WaypointSet set = three();
        for (String tag : List.of("spawn", "home", "work", "market", "leisure", "work:cook", "work:courier")) {
            set = set.tag("a", tag, false);
        }
        WaypointSet seven = set;
        WaypointSet eight = seven.tag("a", "home:" + uuid, false);
        assertEquals(8, eight.point("a").tags().size());
        EditRefused failure = refused(() -> eight.tag("a", "market:" + uuid, false));
        assertEquals("Point a would have more than 8 tags.", failure.getMessage());
    }

    // ---- equality ----

    @Test
    void twoSetsBuiltInDifferentOrdersAreEqualAndShareAHash() {
        WaypointSet one = three().link("a", "b", LinkKind.WALK, LIMITS).tag("c", "market", false);
        WaypointSet other = WaypointSet.EMPTY.addPoint(at("c", 5, 64, 5), LIMITS).addPoint(at("b", 0, 64, 5), LIMITS)
                .addPoint(at("a", 0, 64, 0), LIMITS).tag("c", "market", false).link("b", "a", LinkKind.WALK, LIMITS);
        assertEquals(one, other);
        assertEquals(one.hashCode(), other.hashCode());
    }

    @Test
    void setsThatDifferInAPointALinkKindOrATagAreNotEqual() {
        WaypointSet base = three().link("a", "b", LinkKind.WALK, LIMITS);
        assertNotEquals(base, base.movePoint("c", 6, 64, 5));
        assertNotEquals(base, base.link("a", "b", LinkKind.CROSS, LIMITS));
        assertNotEquals(base, base.tag("a", "home", false));
        assertNotEquals(base, base.unlink("a", "b"));
    }

    @Test
    void aSetIsNotEqualToNullOrToAnotherKindOfObject() {
        assertNotEquals(three(), null);
        assertNotEquals(three(), "three");
    }
}
