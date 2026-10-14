package com.prosperitysmp.npc.interaction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuideGeometryTest {
    @Test void compassUsesMinecraftYawNotMathematicalZero() {
        assertEquals("Ahead", GuideGeometry.direction(0, 10, 0));
        assertEquals("Right", GuideGeometry.direction(-10, 0, 0));
        assertEquals("Left", GuideGeometry.direction(10, 0, 0));
        assertEquals("Ahead", GuideGeometry.direction(10, 0, -90));
        assertEquals("Behind", GuideGeometry.direction(0, -10, 0));
        assertEquals("Above/below", GuideGeometry.direction(0, 0, 20));
    }
    @Test void progressDoesNotOverflowWhenWalkingAwayOrMovingThroughAStop() {
        assertEquals(0, GuideGeometry.progress(40, 20));
        assertEquals(.5, GuideGeometry.progress(10, 20));
        assertEquals(1, GuideGeometry.progress(0, 20));
        assertEquals(0, GuideGeometry.progress(Double.NaN, 20));
    }
}
