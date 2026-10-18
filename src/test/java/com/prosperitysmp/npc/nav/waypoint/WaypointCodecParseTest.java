package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.NavGraphRepository;
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

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every way a waypoint file can be refused, with the code and message staff see, and the quoting of the stored text. */
class WaypointCodecParseTest {
    private static final String HEAD = "format: prosperity-nav-waypoints/1\n";
    private static final String MARKER = "SECRET_MARKER_44";

    private static EditRefused refused(String text) {
        return assertThrows(EditRefused.class, () -> WaypointCodec.parse(text));
    }

    private static WaypointSet parse(String body) {
        return WaypointCodec.parse(HEAD + body);
    }

    // ---- structure ----

    @Test
    void aFileWithOnlyTheFormatLineIsAnEmptySet() {
        WaypointSet set = WaypointCodec.parse(HEAD);
        assertEquals(List.of(), set.points());
        assertEquals(List.of(), set.links());
        assertEquals(WaypointSet.EMPTY, set);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "format: other/1\n", "format: prosperity-nav-waypoints/2\n", "format: 1\n", "points: []\n"})
    void aMissingOrWrongFormatLineIsRefused(String text) {
        EditRefused failure = refused(text);
        assertEquals("WP_FORMAT", failure.code());
    }

    @Test
    void theFormatRefusalTellsStaffWhatTheFirstLineMustBe() {
        assertEquals("The file must start with format: prosperity-nav-waypoints/1", refused("format: nope\n").getMessage());
    }

    @Test
    void aFileThatIsNotAMapIsRefused() {
        EditRefused failure = refused("- a\n- b\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("the file must be a map.", failure.getMessage());
    }

    @Test
    void anUnknownTopLevelKeyIsRefused() {
        EditRefused failure = refused(HEAD + "extra: 1\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("the file has an unknown key.", failure.getMessage());
    }

    @Test
    void pointsThatAreNotAListAreRefused() {
        EditRefused failure = refused(HEAD + "points: {a: 1}\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("points must be a list.", failure.getMessage());
    }

    @Test
    void aPointThatIsNotAMapIsRefusedWithItsIndex() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}, plain]\n");
        assertEquals("point 1 must be a map.", failure.getMessage());
    }

    @Test
    void anUnknownPointKeyIsRefusedWithItsIndex() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0], colour: red}]\n");
        assertEquals("point 0 has an unknown key.", failure.getMessage());
        assertEquals("WP_FORMAT", failure.code());
    }

    @Test
    void aStrictLoaderRefusalComesBackAsAFormatRefusalWithoutQuotingTheFile() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0], " + MARKER + ": &x 1}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("The file uses a YAML anchor; anchors are not allowed.", failure.getMessage());
        assertFalse(failure.getMessage().contains(MARKER));
    }

    // ---- points ----

    @Test
    void aPointWithoutANameIsRefused() {
        EditRefused failure = refused(HEAD + "points: [{at: [0, 64, 0]}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("point 0 name must be text.", failure.getMessage());
    }

    @Test
    void aNumericNameIsNotText() {
        EditRefused failure = refused(HEAD + "points: [{name: 5, at: [0, 64, 0]}]\n");
        assertEquals("point 0 name must be text.", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hub", "has space", "a.b", "", "é"})
    void aNameOutsideTheNameRuleIsRefusedWithItsPointIndex(String name) {
        EditRefused failure = refused(HEAD + "points: [{name: '" + name + "', at: [0, 64, 0]}]\n");
        assertEquals("WP_NAME", failure.code());
        assertEquals("point 0: names are 1 to 32 of a-z, 0-9, _ and -.", failure.getMessage());
    }

    @Test
    void aThirtyThreeCharacterNameIsRefusedAndThirtyTwoIsRead() {
        assertEquals("WP_NAME", refused(HEAD + "points: [{name: " + "a".repeat(33) + ", at: [0, 64, 0]}]\n").code());
        assertEquals("a".repeat(32), parse("points: [{name: " + "a".repeat(32) + ", at: [0, 64, 0]}]\n").points().get(0).name());
    }

    @Test
    void aRepeatedPointNameIsRefusedWithTheName() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}, {name: a, at: [0, 64, 1]}]\n");
        assertEquals("WP_DUP_NAME", failure.code());
        assertEquals("Point a appears twice.", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[0, 64]", "[0, 64, 0, 1]"})
    void anAtThatIsNotThreeCoordinatesIsRefused(String at) {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: " + at + "}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("Point a: at must be [x, y, z].", failure.getMessage());
    }

    @Test
    void aPointWithNoAtIsTreatedAsHavingNoCoordinates() {
        EditRefused failure = refused(HEAD + "points: [{name: a}]\n");
        assertEquals("Point a: at must be [x, y, z].", failure.getMessage());
    }

    @Test
    void anAtThatIsNotAListIsRefused() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: 5}]\n");
        assertEquals("a at must be a list.", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1.5, 64, 0]", "['1', 64, 0]", "[0, true, 0]", "[0, 64, null]", "[0, 64, 99999999999]"})
    void aCoordinateThatIsNotAWholeNumberInRangeIsRefused(String at) {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: " + at + "}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("a: coordinates must be whole numbers.", failure.getMessage());
    }

    @Test
    void negativeAndLargeCoordinatesInRangeAreRead() {
        Point point = parse("points: [{name: a, at: [-2147483648, 0, 2147483647]}]\n").point("a");
        assertEquals(Integer.MIN_VALUE, point.x());
        assertEquals(0, point.y());
        assertEquals(Integer.MAX_VALUE, point.z());
    }

    @ParameterizedTest
    @CsvSource({"sidewalk,SIDEWALK", "access,ACCESS"})
    void aPointKindIsReadFromItsWord(String word, PointKind expected) {
        assertEquals(expected, parse("points: [{name: a, at: [0, 64, 0], kind: " + word + "}]\n").point("a").kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "SIDEWALK", "Access", "''", "5"})
    void aPointKindThatIsNotSidewalkOrAccessIsRefused(String word) {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0], kind: " + word + "}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertTrue(failure.getMessage().equals("Point a: kind is sidewalk or access.")
                || failure.getMessage().equals("a kind must be text."), failure.getMessage());
    }

    @Test
    void aPointWithNoKindIsASidewalkPoint() {
        assertEquals(PointKind.SIDEWALK, parse("points: [{name: a, at: [0, 64, 0]}]\n").point("a").kind());
    }

    @Test
    void tagsAreReadSortedAndDeduplicated() {
        Point point = parse("points: [{name: a, at: [0, 64, 0], tags: [work, home, home, market]}]\n").point("a");
        assertEquals(List.of("home", "market", "work"), List.copyOf(point.tags()));
    }

    @Test
    void aTagThatIsNotARouteTagIsRefusedWithThePointName() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0], tags: [bank]}]\n");
        assertEquals("WP_TAG", failure.code());
        assertEquals("Point a has a tag that is not a route tag.", failure.getMessage());
    }

    @Test
    void aTagThatIsNotTextIsRefused() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0], tags: [5]}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("a tag must be text.", failure.getMessage());
    }

    @Test
    void tagsThatAreNotAListAreRefused() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0], tags: home}]\n");
        assertEquals("a tags must be a list.", failure.getMessage());
    }

    @Test
    void nineDistinctRouteTagsAreOverTheCapAndEightAreNot() {
        String uuid = new UUID(0, 5).toString();
        String eight = "spawn, home, work, market, leisure, work:cook, work:courier, home:" + uuid;
        assertEquals(8, parse("points: [{name: a, at: [0, 64, 0], tags: [" + eight + "]}]\n").point("a").tags().size());
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0], tags: [" + eight + ", market:" + uuid + "]}]\n");
        assertEquals("WP_TAG_CAP", failure.code());
        assertEquals("Point a has more than 8 tags.", failure.getMessage());
    }

    @Test
    void aRepeatedTagCountsOnceTowardTheCap() {
        String repeated = "home, home, home, home, home, home, home, home, home, home";
        assertEquals(Set.of("home"), parse("points: [{name: a, at: [0, 64, 0], tags: [" + repeated + "]}]\n").point("a").tags());
    }

    // ---- links ----

    @Test
    void aLinkThatIsNotAMapIsRefusedWithItsIndex() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}]\nlinks: [a]\n");
        assertEquals("link 0 must be a map.", failure.getMessage());
    }

    @Test
    void anUnknownLinkKeyIsRefused() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 3]}]\n"
                + "links: [{a: a, b: b, cost: 3}]\n");
        assertEquals("link 0 has an unknown key.", failure.getMessage());
    }

    @Test
    void aLinkWithoutAnEndIsRefused() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}]\nlinks: [{a: a}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("link 0 b must be text.", failure.getMessage());
    }

    @Test
    void aLinkToItselfIsRefusedWithTheName() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}]\nlinks: [{a: a, b: a}]\n");
        assertEquals("WP_LINK_SELF", failure.code());
        assertEquals("link 0 links a to itself.", failure.getMessage());
    }

    @Test
    void aLinkNamingAPointThatIsNotThereNamesTheMissingEnd() {
        String points = HEAD + "points: [{name: a, at: [0, 64, 0]}]\n";
        EditRefused second = refused(points + "links: [{a: a, b: gone}]\n");
        assertEquals("WP_LINK_MISSING", second.code());
        assertEquals("link 0 names no point gone.", second.getMessage());
        EditRefused first = refused(points + "links: [{a: gone, b: a}]\n");
        assertEquals("link 0 names no point gone.", first.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"walk,WALK", "cross,CROSS", "crosswalk,CROSSWALK"})
    void aLinkKindIsReadFromItsWord(String word, LinkKind expected) {
        WaypointSet set = parse("points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 4]}]\nlinks: [{a: a, b: b, kind: " + word + "}]\n");
        assertEquals(expected, set.link("a", "b").kind());
    }

    @Test
    void aLinkWithNoKindIsAWalk() {
        WaypointSet set = parse("points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 4]}]\nlinks: [{a: a, b: b}]\n");
        assertEquals(LinkKind.WALK, set.link("a", "b").kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "WALK", "cross-walk", "run"})
    void aLinkKindThatIsNotWalkCrossOrCrosswalkIsRefused(String word) {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 4]}]\n"
                + "links: [{a: a, b: b, kind: " + word + "}]\n");
        assertEquals("WP_FORMAT", failure.code());
        assertEquals("link 0: kind is walk, cross or crosswalk.", failure.getMessage());
    }

    @Test
    void theSameLinkWrittenInEitherOrderIsARepeat() {
        EditRefused failure = refused(HEAD + "points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 4]}]\n"
                + "links: [{a: a, b: b}, {a: b, b: a}]\n");
        assertEquals("WP_LINK_DUP", failure.code());
        assertEquals("Link a-b appears twice.", failure.getMessage());
    }

    @Test
    void linkEndsWrittenOutOfOrderAreStoredInOrder() {
        WaypointSet set = parse("points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 4]}]\nlinks: [{a: b, b: a}]\n");
        assertEquals("a", set.links().get(0).a());
        assertEquals("b", set.links().get(0).b());
    }

    // ---- JSON ----

    @Test
    void aJsonFileOfTheSameShapeReadsTheSameAsYaml() {
        WaypointSet yaml = parse("points: [{name: a, at: [0, 64, 0], tags: [home]}, {name: b, at: [0, 64, 4]}]\nlinks: [{a: a, b: b}]\n");
        WaypointSet json = WaypointCodec.parse("{\"format\": \"prosperity-nav-waypoints/1\", \"points\": ["
                + "{\"name\": \"a\", \"at\": [0, 64, 0], \"tags\": [\"home\"]}, {\"name\": \"b\", \"at\": [0, 64, 4]}], "
                + "\"links\": [{\"a\": \"a\", \"b\": \"b\"}]}");
        assertEquals(yaml, json);
    }

    // ---- canonical text quoting ----

    @ParameterizedTest
    @ValueSource(strings = {"y", "n", "yes", "no", "on", "off", "true", "false", "null"})
    void aNameYamlWouldReadAsAnotherTypeIsQuotedInTheStoredText(String name) {
        WaypointSet set = WaypointSet.EMPTY.addPoint(new Point(name, 0, 64, 0, PointKind.SIDEWALK, null), Limits.DEFAULT);
        String text = WaypointCodec.canonical(set);
        assertTrue(text.contains("{name: \"" + name + "\", at: [0, 64, 0], kind: sidewalk}"), text);
        assertEquals(set, WaypointCodec.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hub", "a1", "a-b_c", "market-2"})
    void aPlainLowercaseNameIsNotQuoted(String name) {
        WaypointSet set = WaypointSet.EMPTY.addPoint(new Point(name, 0, 64, 0, PointKind.SIDEWALK, null), Limits.DEFAULT);
        assertTrue(WaypointCodec.canonical(set).contains("{name: " + name + ", at:"), WaypointCodec.canonical(set));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1a", "_x", "-x", "007", "5"})
    void aNameThatDoesNotStartWithALetterIsQuoted(String name) {
        WaypointSet set = WaypointSet.EMPTY.addPoint(new Point(name, 0, 64, 0, PointKind.SIDEWALK, null), Limits.DEFAULT);
        assertTrue(WaypointCodec.canonical(set).contains("{name: \"" + name + "\", at:"), WaypointCodec.canonical(set));
        assertEquals(set, WaypointCodec.parse(WaypointCodec.canonical(set)));
    }

    @Test
    void tagsWithColonsAreQuotedInTheStoredText() {
        Point point = new Point("a", 0, 64, 0, PointKind.ACCESS, new TreeSet<>(Set.of("work:cook", "home")));
        String text = WaypointCodec.canonical(WaypointSet.of(List.of(point), List.of()));
        assertTrue(text.contains("kind: access, tags: [home, \"work:cook\"]}"), text);
    }

    @Test
    void theStoredTextHasPointsByNameThenLinksByEndsOneToALine() {
        WaypointSet set = WaypointSet.of(
                List.of(new Point("b", 1, 2, 3, PointKind.SIDEWALK, null), new Point("a", -1, 0, 7, PointKind.ACCESS, null)),
                List.of(new Link("b", "a", LinkKind.CROSSWALK)));
        assertEquals("""
                format: prosperity-nav-waypoints/1
                points:
                  - {name: a, at: [-1, 0, 7], kind: access}
                  - {name: b, at: [1, 2, 3], kind: sidewalk}
                links:
                  - {a: a, b: b, kind: crosswalk}
                """, WaypointCodec.canonical(set));
    }

    @Test
    void theShaOfEmptyIsTheShaOfItsCanonicalText() {
        assertEquals(NavGraphRepository.documentSha("format: prosperity-nav-waypoints/1\npoints: []\nlinks: []\n"),
                WaypointCodec.sha(WaypointSet.EMPTY));
    }

    @Test
    void aSetThatReadsBackTheSameIsStoredAsItsCanonicalText() {
        WaypointSet set = parse("points: [{name: a, at: [0, 64, 0], tags: [home]}, {name: b, at: [0, 64, 4]}]\nlinks: [{a: a, b: b, kind: cross}]\n");
        assertEquals(WaypointCodec.canonical(set), WaypointCodec.storable(set));
    }
}
