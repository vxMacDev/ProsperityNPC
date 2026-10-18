package com.prosperitysmp.npc.interaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The eight compass words at every sector edge, for any facing, and the progress bar's clamps. */
class GuideGeometryBoundaryTest {
    /** The offset (dx, dz) of a target {@code degrees} clockwise of straight ahead for a player facing yaw zero (south). */
    private static String word(double degrees, float yaw) {
        double radians = Math.toRadians(degrees);
        return GuideGeometry.direction(-Math.sin(radians) * 100, Math.cos(radians) * 100, yaw);
    }

    @ParameterizedTest
    @CsvSource({"0,Ahead", "45,Ahead-right", "90,Right", "135,Behind-right", "180,Behind", "225,Behind-left", "270,Left",
            "315,Ahead-left", "360,Ahead", "-45,Ahead-left", "-90,Left", "-180,Behind", "720,Ahead"})
    void eachCompassPointIsNamedAtItsCentre(double degrees, String expected) {
        assertEquals(expected, word(degrees, 0));
    }

    @ParameterizedTest
    @CsvSource({"22.4,Ahead", "22.6,Ahead-right", "67.4,Ahead-right", "67.6,Right", "112.4,Right", "112.6,Behind-right",
            "157.4,Behind-right", "157.6,Behind", "202.4,Behind", "202.6,Behind-left", "247.4,Behind-left", "247.6,Left",
            "292.4,Left", "292.6,Ahead-left", "337.4,Ahead-left", "337.6,Ahead", "-22.4,Ahead", "-22.6,Ahead-left"})
    void theSectorsChangeHalfwayBetweenTwoCompassPoints(double degrees, String expected) {
        assertEquals(expected, word(degrees, 0));
    }

    @ParameterizedTest
    @CsvSource({"0,0,Ahead", "45,45,Ahead", "90,90,Ahead", "180,180,Ahead", "-90,-90,Ahead", "0,90,Left",
            "0,-90,Right", "90,0,Right", "0,180,Behind", "0,45,Ahead-left"})
    void theWordFollowsTheAngleBetweenTheTargetAndTheFacing(double targetDegrees, float yaw, String expected) {
        // At yaw 90 the player faces negative x, which is where a target 90 degrees clockwise of south lies.
        assertEquals(expected, word(targetDegrees, yaw));
    }

    @ParameterizedTest @ValueSource(floats = {360, -360, 720, 3600})
    void aWholeNumberOfTurnsInTheYawChangesNothing(float yaw) {
        assertEquals(word(90, 0), word(90, yaw));
        assertEquals(word(200, 0), word(200, yaw));
    }

    @Test void aTargetStraightUpOrDownHasNoCompassWord() {
        assertEquals("Above/below", GuideGeometry.direction(0, 0, 0));
        assertEquals("Above/below", GuideGeometry.direction(0, 0, 123.4f));
        assertEquals("Above/below", GuideGeometry.direction(-0.0, 0.0, 0));
    }

    @Test void aTinyButNonzeroOffsetStillHasADirection() {
        assertEquals("Ahead", GuideGeometry.direction(0, 1e-9, 0));
        assertEquals("Behind", GuideGeometry.direction(0, -1e-9, 0));
        assertEquals("Right", GuideGeometry.direction(-1e-9, 0, 0));
    }

    @Test void anOffsetOrYawThatIsNotAFiniteNumberIsJustNearby() {
        assertEquals("Nearby", GuideGeometry.direction(Double.NaN, 1, 0));
        assertEquals("Nearby", GuideGeometry.direction(1, Double.NaN, 0));
        assertEquals("Nearby", GuideGeometry.direction(Double.POSITIVE_INFINITY, 1, 0));
        assertEquals("Nearby", GuideGeometry.direction(1, Double.NEGATIVE_INFINITY, 0));
        assertEquals("Nearby", GuideGeometry.direction(1, 1, Float.NaN));
        assertEquals("Nearby", GuideGeometry.direction(1, 1, Float.POSITIVE_INFINITY));
    }

    @ParameterizedTest
    @CsvSource({"20,20,0.0", "10,20,0.5", "0,20,1.0", "5,20,0.75", "19,20,0.05", "40,20,0.0", "25,20,0.0", "-5,20,1.0",
            "1,1,0.0", "0.5,1,0.5"})
    void progressIsTheShareOfTheStartingDistanceAlreadyCovered(double distance, double initial, double expected) {
        assertEquals(expected, GuideGeometry.progress(distance, initial), 1e-12);
    }

    @ParameterizedTest @CsvSource({"10,0", "10,-5", "0,0", "0,-1"})
    void aStartingDistanceOfZeroOrLessGivesNoProgress(double distance, double initial) {
        assertEquals(0.0, GuideGeometry.progress(distance, initial));
    }

    @Test void aDistanceThatIsNotAFiniteNumberGivesNoProgress() {
        assertEquals(0.0, GuideGeometry.progress(Double.NaN, 20));
        assertEquals(0.0, GuideGeometry.progress(Double.POSITIVE_INFINITY, 20));
        assertEquals(0.0, GuideGeometry.progress(Double.NEGATIVE_INFINITY, 20));
        assertEquals(0.0, GuideGeometry.progress(10, Double.NaN));
        assertEquals(0.0, GuideGeometry.progress(10, Double.POSITIVE_INFINITY));
    }

    @Test void progressNeverLeavesTheZeroToOneRange() {
        for (double distance : new double[] {-1e9, -1, 0, 0.1, 5, 19.99, 20, 20.01, 1e9})
            assertTrue(GuideGeometry.progress(distance, 20) >= 0 && GuideGeometry.progress(distance, 20) <= 1, "distance " + distance);
    }
}
