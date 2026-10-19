package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.render.RenderPolicy.Vec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The exact edges of avatar selection: radius, per-viewer and server caps, tie order and the grace window. */
class RenderPolicyBoundaryTest {
    private static final Vec ORIGIN = new Vec(0, 64, 0);

    private static Map<String, Vec> map(Object... pairs) {
        Map<String, Vec> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (Vec) pairs[i + 1]);
        return out;
    }

    private static List<String> select(Map<String, Vec> viewers, Map<String, Vec> candidates, double radius, int perViewer, int server) {
        return new ArrayList<>(RenderPolicy.select(viewers, candidates, radius, perViewer, server));
    }

    @Test void aCandidateExactlyOnTheRadiusIsSelectedAndOneJustBeyondIsNot() {
        Map<String, Vec> viewers = map("v", ORIGIN);
        assertEquals(List.of("c"), select(viewers, map("c", new Vec(10, 64, 0)), 10, 5, 5));
        assertEquals(List.of(), select(viewers, map("c", new Vec(10.0001, 64, 0)), 10, 5, 5));
        assertEquals(List.of(), select(viewers, map("c", new Vec(10, 64, 0)), 9.9999, 5, 5));
    }

    @Test void theRadiusIsMeasuredInThreeDimensions() {
        Map<String, Vec> viewers = map("v", ORIGIN);
        assertEquals(List.of("above"), select(viewers, map("above", new Vec(0, 74, 0)), 10, 5, 5));
        assertEquals(List.of(), select(viewers, map("above", new Vec(0, 74.5, 0)), 10, 5, 5));
        assertEquals(List.of("diagonal"), select(viewers, map("diagonal", new Vec(5, 69, 5)), 10, 5, 5));
        assertEquals(List.of(), select(viewers, map("diagonal", new Vec(6, 72, 6)), 10, 5, 5));
    }

    @Test void aRadiusOfZeroSelectsOnlyWhatStandsOnTheViewer() {
        Map<String, Vec> viewers = map("v", ORIGIN);
        assertEquals(List.of("here"), select(viewers, map("here", ORIGIN, "near", new Vec(0.000001, 64, 0)), 0, 5, 5));
    }

    @ParameterizedTest @ValueSource(doubles = {-0.0001, -1, -1e9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void anInvalidRadiusSelectsNothing(double radius) {
        assertEquals(List.of(), select(map("v", ORIGIN), map("c", ORIGIN), radius, 5, 5));
    }

    @Test void aLimitOfZeroOrLessSelectsNothing() {
        Map<String, Vec> viewers = map("v", ORIGIN);
        Map<String, Vec> candidates = map("c", ORIGIN);
        assertEquals(List.of(), select(viewers, candidates, 10, 0, 5));
        assertEquals(List.of(), select(viewers, candidates, 10, -1, 5));
        assertEquals(List.of(), select(viewers, candidates, 10, 5, 0));
        assertEquals(List.of(), select(viewers, candidates, 10, 5, -1));
        assertEquals(List.of("c"), select(viewers, candidates, 10, 1, 1));
    }

    @Test void noViewersOrNoCandidatesSelectsNothing() {
        assertEquals(List.of(), select(Map.of(), map("c", ORIGIN), 10, 5, 5));
        assertEquals(List.of(), select(map("v", ORIGIN), Map.of(), 10, 5, 5));
    }

    @Test void equidistantCandidatesAreRankedByIdSoTheCapKeepsTheLowerId() {
        Map<String, Vec> candidates = map("zed", new Vec(5, 64, 0), "abe", new Vec(-5, 64, 0), "mid", new Vec(0, 64, 5));
        assertEquals(List.of("abe"), select(map("v", ORIGIN), candidates, 10, 1, 5));
        assertEquals(List.of("abe", "mid"), select(map("v", ORIGIN), candidates, 10, 2, 5));
        assertEquals(List.of("abe", "mid", "zed"), select(map("v", ORIGIN), candidates, 10, 3, 5));
    }

    @Test void theResultIsOrderedNearestFirstWhateverTheInputOrder() {
        Map<String, Vec> forward = map("a", new Vec(3, 64, 0), "b", new Vec(1, 64, 0), "c", new Vec(2, 64, 0));
        Map<String, Vec> backward = map("c", new Vec(2, 64, 0), "b", new Vec(1, 64, 0), "a", new Vec(3, 64, 0));
        assertEquals(List.of("b", "c", "a"), select(map("v", ORIGIN), forward, 10, 5, 5));
        assertEquals(List.of("b", "c", "a"), select(map("v", ORIGIN), backward, 10, 5, 5));
    }

    @Test void eachViewerGrantsItsOwnNearestCandidatesIndependently() {
        Map<String, Vec> viewers = map("west", new Vec(-100, 64, 0), "east", new Vec(100, 64, 0));
        Map<String, Vec> candidates = map("w1", new Vec(-99, 64, 0), "w2", new Vec(-98, 64, 0), "e1", new Vec(99, 64, 0), "e2", new Vec(97, 64, 0));
        assertEquals(List.of("e1", "w1"), select(viewers, candidates, 10, 1, 10));
        assertEquals(List.of("e1", "w1", "w2", "e2"), select(viewers, candidates, 10, 2, 10));
    }

    @Test void theServerCapKeepsTheCandidatesNearestToAGrantingViewer() {
        Map<String, Vec> viewers = map("west", new Vec(-100, 64, 0), "east", new Vec(100, 64, 0));
        Map<String, Vec> candidates = map("w1", new Vec(-95, 64, 0), "e1", new Vec(99, 64, 0), "e2", new Vec(94, 64, 0));
        assertEquals(List.of("e1"), select(viewers, candidates, 10, 5, 1));
        assertEquals(List.of("e1", "w1"), select(viewers, candidates, 10, 5, 2));
    }

    @Test void aCandidateNearTwoViewersIsCountedOnceAtItsNearestDistance() {
        Map<String, Vec> viewers = map("a", new Vec(-4, 64, 0), "b", new Vec(1, 64, 0));
        Map<String, Vec> candidates = map("shared", ORIGIN, "far", new Vec(8, 64, 0));
        assertEquals(List.of("shared", "far"), select(viewers, candidates, 10, 5, 5));
    }

    @Test void aCandidateWithNotANumberCoordinatesIsNeverSelected() {
        Map<String, Vec> candidates = map("bad", new Vec(Double.NaN, 64, 0), "ok", new Vec(1, 64, 0));
        assertEquals(List.of("ok"), select(map("v", ORIGIN), candidates, 10, 5, 5));
    }

    @Test void theGraceWindowIsHalfOpenSoARetainedAvatarExpiresExactlyAtTheGrace() {
        Map<String, Long> active = Map.of("old", 0L);
        assertEquals(Set.of("old"), RenderPolicy.retain(active, Set.of(), 999, 1000, 5));
        assertEquals(Set.of(), RenderPolicy.retain(active, Set.of(), 1000, 1000, 5));
        assertEquals(Set.of(), RenderPolicy.retain(active, Set.of(), 1001, 1000, 5));
    }

    @Test void aNegativeGraceOrCapRetainsNothingBeyondTheDesired() {
        Map<String, Long> active = Map.of("old", 0L, "want", 0L);
        assertEquals(Set.of("want"), RenderPolicy.retain(active, Set.of("want"), 10, -5, 5));
        assertEquals(Set.of(), RenderPolicy.retain(active, Set.of("want"), 10, 1000, -1));
        assertEquals(Set.of(), RenderPolicy.retain(active, Set.of("want"), 10, 1000, 0));
    }

    @Test void aDesiredAvatarThatIsNotActiveIsNotRetained() {
        assertEquals(Set.of(), RenderPolicy.retain(Map.of("a", 0L), Set.of("b"), 10, 0, 5));
    }

    @Test void desiredAvatarsTakeTheSlotsBeforeGraceOnes() {
        Map<String, Long> active = new LinkedHashMap<>();
        active.put("grace", 9L);
        active.put("want", 0L);
        assertEquals(List.of("want"), new ArrayList<>(RenderPolicy.retain(active, Set.of("want"), 10, 1000, 1)));
        assertEquals(List.of("want", "grace"), new ArrayList<>(RenderPolicy.retain(active, Set.of("want"), 10, 1000, 2)));
    }

    @Test void graceAvatarsAreKeptMostRecentlySeenFirstThenByIdUpToTheCap() {
        Map<String, Long> active = new LinkedHashMap<>();
        active.put("b", 5L);
        active.put("a", 5L);
        active.put("c", 8L);
        active.put("stale", 0L);
        assertEquals(List.of("c", "a", "b"), new ArrayList<>(RenderPolicy.retain(active, Set.of(), 10, 6, 3)));
        assertEquals(List.of("c", "a"), new ArrayList<>(RenderPolicy.retain(active, Set.of(), 10, 6, 2)));
    }

    @Test void theWorldSelectionNeverGrantsAcrossWorldsAndKeepsTheGlobalCapByDistance() {
        Map<String, Map<String, Vec>> viewers = Map.of("overworld", map("v", ORIGIN), "nether", map("n", ORIGIN));
        Map<String, Map<String, Vec>> candidates = Map.of(
                "overworld", map("o1", new Vec(2, 64, 0), "o2", new Vec(6, 64, 0)),
                "nether", map("n1", new Vec(4, 64, 0)),
                "end", map("e1", new Vec(1, 64, 0)));
        assertEquals(List.of("o1", "n1", "o2"), new ArrayList<>(RenderPolicy.selectWorlds(viewers, candidates, 10, 5, 10)));
        assertEquals(List.of("o1", "n1"), new ArrayList<>(RenderPolicy.selectWorlds(viewers, candidates, 10, 5, 2)));
        assertEquals(List.of(), new ArrayList<>(RenderPolicy.selectWorlds(viewers, candidates, 10, 5, 0)));
        assertEquals(List.of(), new ArrayList<>(RenderPolicy.selectWorlds(viewers, candidates, 10, 5, -3)));
    }
}
