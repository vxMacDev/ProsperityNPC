package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointSetTest {

    static Point point(String name, int x, int y, int z) {
        return new Point(name, x, y, z, PointKind.SIDEWALK, null);
    }

    private static WaypointSet sample() {
        return WaypointSet.EMPTY
                .addPoint(new Point("hub", 416, 72, -128, PointKind.SIDEWALK, new TreeSet<>(Set.of("home", "work"))), Limits.DEFAULT)
                .addPoint(point("corner", 416, 72, -139), Limits.DEFAULT)
                .link("hub", "corner", LinkKind.WALK, Limits.DEFAULT);
    }

    @Test
    void editsNeverChangeTheSetTheyStartFrom() {
        WaypointSet before = sample();
        String text = WaypointCodec.canonical(before);
        before.addPoint(point("market", 454, 72, -139), Limits.DEFAULT);
        before.removePoint("hub");
        before.unlink("corner", "hub");
        before.tag("corner", "market", false);
        assertEquals(text, WaypointCodec.canonical(before));
    }

    @Test
    void theCanonicalTextIsTheDocumentedFormat() {
        assertEquals("""
                format: prosperity-nav-waypoints/1
                points:
                  - {name: corner, at: [416, 72, -139], kind: sidewalk}
                  - {name: hub, at: [416, 72, -128], kind: sidewalk, tags: [home, work]}
                links:
                  - {a: corner, b: hub, kind: walk}
                """, WaypointCodec.canonical(sample()));
        assertEquals("format: prosperity-nav-waypoints/1\npoints: []\nlinks: []\n", WaypointCodec.canonical(WaypointSet.EMPTY));
    }

    @Test
    void canonicalTextRoundTripsIncludingAwkwardNamesAndTags() {
        WaypointSet set = sample()
                .addPoint(new Point("123", 1, 2, 3, PointKind.ACCESS, new TreeSet<>(Set.of("work:cook", "home:" + new java.util.UUID(1, 2)))), Limits.DEFAULT)
                .addPoint(point("yes", 5, 6, 7), Limits.DEFAULT)
                .addPoint(point("-x_1", 8, 9, 10), Limits.DEFAULT)
                .link("123", "yes", LinkKind.CROSSWALK, Limits.DEFAULT);
        String text = WaypointCodec.canonical(set);
        WaypointSet back = WaypointCodec.parse(text);
        assertEquals(set, back);
        assertEquals(text, WaypointCodec.canonical(back));
    }

    @Test
    void theShaDoesNotDependOnEditOrder() {
        WaypointSet a = WaypointSet.EMPTY.addPoint(point("a", 0, 64, 0), Limits.DEFAULT)
                .addPoint(point("b", 0, 64, 5), Limits.DEFAULT).link("a", "b", LinkKind.WALK, Limits.DEFAULT)
                .tag("a", "home", false);
        WaypointSet b = WaypointSet.EMPTY.addPoint(point("b", 0, 64, 5), Limits.DEFAULT)
                .addPoint(point("a", 0, 64, 0), Limits.DEFAULT).tag("a", "home", false)
                .link("b", "a", LinkKind.WALK, Limits.DEFAULT);
        assertEquals(WaypointCodec.sha(a), WaypointCodec.sha(b));
    }

    /** Mutant guards: a sha that ignored the link kind, the point kind or the links would pass the order test alone. */
    @Test
    void theShaCoversKindsAndLinks() {
        WaypointSet base = sample();
        assertNotEquals(WaypointCodec.sha(base), WaypointCodec.sha(base.link("hub", "corner", LinkKind.CROSS, Limits.DEFAULT)));
        assertNotEquals(WaypointCodec.sha(base), WaypointCodec.sha(base.unlink("hub", "corner")));
        WaypointSet access = WaypointSet.EMPTY.addPoint(new Point("hub", 416, 72, -128, PointKind.ACCESS, new TreeSet<>(Set.of("home", "work"))), Limits.DEFAULT)
                .addPoint(point("corner", 416, 72, -139), Limits.DEFAULT).link("hub", "corner", LinkKind.WALK, Limits.DEFAULT);
        assertNotEquals(WaypointCodec.sha(base), WaypointCodec.sha(access));
        assertEquals(64, WaypointCodec.sha(base).length());
    }

    @Test
    void removingAPointRemovesItsLinks() {
        WaypointSet set = sample().addPoint(point("market", 416, 72, -150), Limits.DEFAULT)
                .link("corner", "market", LinkKind.WALK, Limits.DEFAULT);
        WaypointSet after = set.removePoint("corner");
        assertNull(after.point("corner"));
        assertTrue(after.links().isEmpty());
        assertEquals(List.of("hub", "market"), after.points().stream().map(Point::name).toList());
    }

    @Test
    void nameTagAndCapRulesRefuseWithTheirCodes() {
        assertEquals("WP_NAME", assertThrows(EditRefused.class, () -> WaypointSet.EMPTY.addPoint(point("Hub", 0, 0, 0), Limits.DEFAULT)).code());
        assertEquals("WP_NAME", assertThrows(EditRefused.class, () -> WaypointSet.EMPTY.addPoint(point("a".repeat(33), 0, 0, 0), Limits.DEFAULT)).code());
        assertEquals("WP_DUP_NAME", assertThrows(EditRefused.class, () -> sample().addPoint(point("hub", 1, 1, 1), Limits.DEFAULT)).code());
        assertEquals("WP_DUP_CELL", assertThrows(EditRefused.class, () -> sample().addPoint(point("other", 416, 72, -128), Limits.DEFAULT)).code());
        assertEquals("WP_LINK_SELF", assertThrows(EditRefused.class, () -> sample().link("hub", "hub", LinkKind.WALK, Limits.DEFAULT)).code());
        assertEquals("WP_LINK_MISSING", assertThrows(EditRefused.class, () -> sample().link("hub", "nowhere", LinkKind.WALK, Limits.DEFAULT)).code());
        assertEquals("WP_TAG", assertThrows(EditRefused.class, () -> sample().tag("hub", "bank", false)).code());
        WaypointSet full = sample();
        for (String tag : List.of("spawn", "market", "leisure", "work:cook", "work:courier", "work:gardener")) full = full.tag("hub", tag, false);
        WaypointSet eight = full;
        assertEquals(8, eight.point("hub").tags().size());
        assertEquals("WP_TAG_CAP", assertThrows(EditRefused.class, () -> eight.tag("hub", "home:" + new java.util.UUID(0, 9), false)).code());
        Limits two = new Limits(2, 1);
        assertEquals("WP_CAP_POINTS", assertThrows(EditRefused.class, () -> sample().addPoint(point("x", 9, 9, 9), two)).code());
        WaypointSet three = WaypointSet.EMPTY.addPoint(point("x", 0, 0, 0), Limits.DEFAULT).addPoint(point("y", 0, 0, 1), Limits.DEFAULT)
                .addPoint(point("z", 0, 0, 2), Limits.DEFAULT).link("x", "y", LinkKind.WALK, two);
        assertEquals("WP_CAP_LINKS", assertThrows(EditRefused.class, () -> three.link("y", "z", LinkKind.WALK, two)).code());
        // Changing an existing link's kind is not a new link.
        three.link("x", "y", LinkKind.CROSS, two);
    }

    @Test
    void theReaderRefusesStructuralProblemsWithACode() {
        assertEquals("WP_DUP_NAME", assertThrows(EditRefused.class, () -> WaypointCodec.parse("""
                format: prosperity-nav-waypoints/1
                points:
                  - {name: a, at: [0, 64, 0]}
                  - {name: a, at: [0, 64, 5]}
                """)).code());
        assertEquals("WP_LINK_DUP", assertThrows(EditRefused.class, () -> WaypointCodec.parse("""
                format: prosperity-nav-waypoints/1
                points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 5]}]
                links: [{a: a, b: b}, {a: b, b: a, kind: cross}]
                """)).code());
        assertEquals("WP_FORMAT", assertThrows(EditRefused.class, () -> WaypointCodec.parse("""
                format: prosperity-nav-waypoints/1
                points: [{name: a, at: [0, 64, 0], colour: red}]
                """)).code());
        assertEquals("WP_FORMAT", assertThrows(EditRefused.class, () -> WaypointCodec.parse("format: other/1\n")).code());
        assertEquals("WP_FORMAT", assertThrows(EditRefused.class, () -> WaypointCodec.parse("""
                format: prosperity-nav-waypoints/1
                points: &p [{name: a, at: [0, 64, 0]}]
                links: *p
                """)).code());
        // A sparse file may leave out the point and link kinds.
        WaypointSet sparse = WaypointCodec.parse("""
                format: prosperity-nav-waypoints/1
                points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 5], kind: access, tags: [home]}]
                links: [{a: b, b: a}]
                """);
        assertEquals(PointKind.SIDEWALK, sparse.point("a").kind());
        assertEquals(LinkKind.WALK, sparse.link("a", "b").kind());
    }
}
