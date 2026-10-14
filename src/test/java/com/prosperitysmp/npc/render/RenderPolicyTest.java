package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.render.RenderPolicy.Vec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderPolicyTest {

    private static Vec at(double x) {
        return new Vec(x, 0, 0);
    }

    @Test
    void rendersInRangeIgnoresOutOfRange() {
        Map<String, Vec> viewers = Map.of("v", at(0));
        Map<String, Vec> candidates = Map.of("near", at(10), "far", at(100));
        Set<String> r = RenderPolicy.select(viewers, candidates, 48, 3, 20);
        assertTrue(r.contains("near"));
        assertFalse(r.contains("far"));
        assertEquals(1, r.size());
    }

    @Test
    void perViewerCapKeepsNearest() {
        Map<String, Vec> viewers = Map.of("v", at(0));
        Map<String, Vec> candidates = Map.of("c1", at(5), "c2", at(10), "c3", at(15), "c4", at(20), "c5", at(25));
        Set<String> r = RenderPolicy.select(viewers, candidates, 48, 3, 20);
        assertEquals(Set.of("c1", "c2", "c3"), r);
    }

    @Test
    void serverCapTrimsToNearest() {
        Map<String, Vec> viewers = Map.of("v1", at(0), "v2", at(1000));
        Map<String, Vec> candidates = Map.of(
                "a1", at(5), "a2", at(10), "a3", at(15),
                "b1", at(1005), "b2", at(1010), "b3", at(1015));
        Set<String> r = RenderPolicy.select(viewers, candidates, 48, 3, 4);
        assertEquals(4, r.size());
        assertTrue(r.contains("a1"));
        assertTrue(r.contains("b1"));
    }

    @Test
    void candidateNearTwoViewersCountsOnce() {
        Map<String, Vec> viewers = Map.of("v1", at(0), "v2", at(20));
        Map<String, Vec> candidates = Map.of("x", at(10));
        Set<String> r = RenderPolicy.select(viewers, candidates, 48, 3, 20);
        assertEquals(Set.of("x"), r);
    }

    @Test
    void degenerateInputsRenderNothing() {
        Map<String, Vec> viewers = Map.of("v", at(0));
        Map<String, Vec> candidates = Map.of("c", at(1));
        assertTrue(RenderPolicy.select(Map.of(), candidates, 48, 3, 20).isEmpty());
        assertTrue(RenderPolicy.select(viewers, Map.of(), 48, 3, 20).isEmpty());
        assertTrue(RenderPolicy.select(viewers, candidates, 48, 0, 20).isEmpty());
        assertTrue(RenderPolicy.select(viewers, candidates, 48, 3, 0).isEmpty());
    }
}
