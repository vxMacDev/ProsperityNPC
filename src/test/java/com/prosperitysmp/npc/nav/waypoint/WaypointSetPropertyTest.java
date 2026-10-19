package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Limits;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Random waypoint sets: the same edits in any order give the same set, the same text and the same sha. */
class WaypointSetPropertyTest {
    private static final List<String> TAGS = List.of("spawn", "home", "work", "market", "leisure", "work:cook");

    private record Edit(String kind, String a, String b, String tag, Point point, LinkKind link) { }

    private static List<Edit> edits(long seed) {
        Random random = new Random(seed);
        int n = 6 + random.nextInt(5);
        List<Edit> edits = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int x;
            int z;
            do {
                x = random.nextInt(10);
                z = random.nextInt(10);
            } while (!used.add(x + "," + z));
            String name = "p" + i;
            names.add(name);
            PointKind kind = random.nextInt(4) == 0 ? PointKind.ACCESS : PointKind.SIDEWALK;
            edits.add(new Edit("point", name, null, null, new Point(name, x, 64 + random.nextInt(2), z, kind, null), null));
            if (random.nextInt(3) == 0) {
                edits.add(new Edit("tag", name, null, TAGS.get(random.nextInt(TAGS.size())), null, null));
            }
        }
        Set<String> linked = new HashSet<>();
        for (int i = 0; i < n * 2; i++) {
            String a = names.get(random.nextInt(n));
            String b = names.get(random.nextInt(n));
            if (a.equals(b) || !linked.add(a.compareTo(b) < 0 ? a + "-" + b : b + "-" + a)) continue;
            edits.add(new Edit("link", a, b, null, null, LinkKind.values()[random.nextInt(LinkKind.values().length)]));
        }
        return edits;
    }

    /** Applies the edits so that every point exists before anything refers to it; within that, any order. */
    private static WaypointSet apply(List<Edit> edits, Random order, boolean flipLinks) {
        List<Edit> points = new ArrayList<>();
        List<Edit> rest = new ArrayList<>();
        for (Edit e : edits) (e.kind().equals("point") ? points : rest).add(e);
        if (order != null) {
            Collections.shuffle(points, order);
            Collections.shuffle(rest, order);
        }
        WaypointSet set = WaypointSet.EMPTY;
        for (Edit e : points) set = set.addPoint(e.point(), Limits.DEFAULT);
        for (Edit e : rest) {
            if (e.kind().equals("tag")) {
                set = set.tag(e.a(), e.tag(), false);
            } else if (flipLinks) {
                set = set.link(e.b(), e.a(), e.link(), Limits.DEFAULT);
            } else {
                set = set.link(e.a(), e.b(), e.link(), Limits.DEFAULT);
            }
        }
        return set;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30})
    void theSameEditsInAnyOrderAndWithLinkEndsFlippedGiveEqualSetsAndTheSameSha(long seed) {
        List<Edit> edits = edits(seed);
        WaypointSet plain = apply(edits, null, false);
        WaypointSet shuffled = apply(edits, new Random(seed + 500), true);
        assertEquals(plain, shuffled);
        assertEquals(plain.hashCode(), shuffled.hashCode());
        assertEquals(WaypointCodec.canonical(plain), WaypointCodec.canonical(shuffled));
        assertEquals(WaypointCodec.sha(plain), WaypointCodec.sha(shuffled));
    }

    @ParameterizedTest
    @ValueSource(longs = {31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50})
    void aRandomSetReadsBackFromItsStoredTextUnchanged(long seed) {
        WaypointSet set = apply(edits(seed), null, false);
        String text = WaypointCodec.storable(set);
        WaypointSet back = WaypointCodec.parse(text);
        assertEquals(set, back);
        assertEquals(text, WaypointCodec.canonical(back));
        assertEquals(WaypointCodec.sha(set), WaypointCodec.sha(back));
    }

    @ParameterizedTest
    @ValueSource(longs = {51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70})
    void pointsAndLinksAreAlwaysListedInNameOrder(long seed) {
        WaypointSet set = apply(edits(seed), new Random(seed), seed % 2 == 0);
        List<String> names = set.points().stream().map(Point::name).toList();
        assertEquals(names.stream().sorted().toList(), names);
        List<String> keys = set.links().stream().map(l -> l.a() + "\u0000" + l.b()).toList();
        assertEquals(keys.stream().sorted().toList(), keys);
        assertEquals(true, set.links().stream().allMatch(l -> l.a().compareTo(l.b()) <= 0));
    }

    @ParameterizedTest
    @ValueSource(longs = {71, 72, 73, 74, 75, 76, 77, 78, 79, 80})
    void takingAPointOutAndPuttingItBackIsTheSameSetOnlyWhenItHadNoLinks(long seed) {
        WaypointSet set = apply(edits(seed), null, false);
        for (Point p : set.points()) {
            WaypointSet removed = set.removePoint(p.name());
            WaypointSet restored = removed.addPoint(p, Limits.DEFAULT);
            if (set.linksOf(p.name()).isEmpty()) {
                assertEquals(set, restored, p.name());
            } else {
                assertNotEquals(set, restored, p.name());
                assertEquals(set.links().size() - set.linksOf(p.name()).size(), removed.links().size());
            }
        }
    }
}
