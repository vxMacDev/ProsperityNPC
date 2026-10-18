package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Link;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The stored text of a waypoint set: whatever is stored must read back, so a region's set can never lock up. */
class WaypointCodecTest {

    private static final String POINTS = """
            format: prosperity-nav-waypoints/1
            points: [{name: a, at: [0, 64, 0]}, {name: b, at: [0, 64, 5]}]
            """;

    @Test
    void aLinkEndThatIsNotAPointNameIsRefused() {
        // The quote would have been copied into the canonical text unescaped, which then no longer reads.
        assertEquals("WP_NAME", assertThrows(EditRefused.class, () -> WaypointCodec.parse(POINTS
                + "links: [{a: 'x\"y', b: a}]\n")).code());
        assertEquals("WP_NAME", assertThrows(EditRefused.class, () -> WaypointCodec.parse(POINTS
                + "links: [{a: a, b: B}]\n")).code());
        assertEquals("WP_NAME", assertThrows(EditRefused.class, () -> WaypointCodec.parse(POINTS
                + "links: [{a: a, b: '" + "c".repeat(33) + "'}]\n")).code());
        assertEquals("WP_LINK_MISSING", assertThrows(EditRefused.class, () -> WaypointCodec.parse(POINTS
                + "links: [{a: a, b: nowhere}]\n")).code());
        // Positive control: the same file with good ends reads.
        assertEquals(1, WaypointCodec.parse(POINTS + "links: [{a: a, b: b}]\n").links().size());
    }

    @Test
    void aLinkCannotBeMadeWithABadName() {
        assertEquals("WP_NAME", assertThrows(EditRefused.class, () -> new Link("x\"y", "a", LinkKind.WALK)).code());
        assertEquals("WP_NAME", assertThrows(EditRefused.class, () -> new Link("a", "", LinkKind.WALK)).code());
        assertEquals("a", new Link("b", "a", LinkKind.CROSS).a());
    }

    @Test
    void aSetWhoseTextWouldNotReadBackIsNeverStored() {
        WaypointSet bad = WaypointSet.of(List.of(new Point("x\"y", 0, 64, 0, PointKind.SIDEWALK, null)), List.of());
        assertEquals("WP_FORMAT", assertThrows(EditRefused.class, () -> WaypointCodec.storable(bad)).code());
        WaypointSet longName = WaypointSet.of(List.of(new Point("a".repeat(33), 0, 64, 0, PointKind.SIDEWALK, null)), List.of());
        assertEquals("WP_FORMAT", assertThrows(EditRefused.class, () -> WaypointCodec.storable(longName)).code());
        WaypointSet good = WaypointSet.EMPTY.addPoint(new Point("a", 0, 64, 0, PointKind.SIDEWALK, null), Limits.DEFAULT);
        assertEquals(WaypointCodec.canonical(good), WaypointCodec.storable(good));
        assertEquals(WaypointCodec.canonical(WaypointSet.EMPTY), WaypointCodec.storable(WaypointSet.EMPTY));
    }

    /** Seeded random sets with awkward names, tags and coordinates: the canonical text always reads back as the same set. */
    @Test
    void everyValidSetRoundTripsThroughItsCanonicalText() {
        Random random = new Random(6601);
        List<String> awkward = List.of("y", "n", "yes", "no", "on", "off", "true", "false", "null", "0", "010", "0x10",
                "1_000", "-1", "_", "-", "1e5", "e5", "nan", "inf", "0b11", "12", "a-b", "z".repeat(32));
        List<String> tags = List.of("home", "work", "market", "leisure", "spawn", "work:cook", "work:courier",
                "home:" + new UUID(1, 2), "market:" + new UUID(3, 4));
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789_-";
        for (int round = 0; round < 300; round++) {
            WaypointSet set = WaypointSet.EMPTY;
            List<String> names = new ArrayList<>();
            int points = random.nextInt(12);
            for (int i = 0; i < points; i++) {
                String name;
                if (random.nextInt(3) == 0) {
                    name = awkward.get(random.nextInt(awkward.size()));
                } else {
                    StringBuilder text = new StringBuilder();
                    int length = 1 + random.nextInt(32);
                    for (int c = 0; c < length; c++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                    name = text.toString();
                }
                if (names.contains(name)) continue;
                TreeSet<String> pointTags = new TreeSet<>();
                for (int t = random.nextInt(4); t > 0; t--) pointTags.add(tags.get(random.nextInt(tags.size())));
                int x = random.nextBoolean() ? random.nextInt() : random.nextInt(200) - 100;
                Point point = new Point(name, x, random.nextInt(640) - 64, i, random.nextBoolean() ? PointKind.SIDEWALK
                        : PointKind.ACCESS, pointTags);
                set = set.addPoint(point, Limits.DEFAULT);
                names.add(name);
            }
            for (int i = 0; names.size() > 1 && i < 10; i++) {
                String a = names.get(random.nextInt(names.size()));
                String b = names.get(random.nextInt(names.size()));
                if (a.equals(b)) continue;
                set = set.link(a, b, LinkKind.values()[random.nextInt(LinkKind.values().length)], Limits.DEFAULT);
            }
            String text = WaypointCodec.canonical(set);
            WaypointSet back = WaypointCodec.parse(text);
            assertEquals(set, back, text);
            assertEquals(text, WaypointCodec.canonical(back));
            assertEquals(text, WaypointCodec.storable(set));
        }
    }
}
