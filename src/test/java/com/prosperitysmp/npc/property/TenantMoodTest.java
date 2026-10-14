package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.resident.ResidentDialogue;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.prosperitysmp.npc.property.TenantMood.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantMoodTest {
    @Test void bandsCoverEverySatisfactionOnce() {
        assertEquals(Band.UNHAPPY, Band.of(0));
        assertEquals(Band.UNHAPPY, Band.of(29));
        assertEquals(Band.NEUTRAL, Band.of(30));
        assertEquals(Band.NEUTRAL, Band.of(50));
        assertEquals(Band.NEUTRAL, Band.of(54));
        assertEquals(Band.CONTENT, Band.of(55));
        assertEquals(Band.CONTENT, Band.of(79));
        assertEquals(Band.HAPPY, Band.of(80));
        assertEquals(Band.HAPPY, Band.of(100));
        assertThrows(IllegalArgumentException.class, () -> Band.of(-1));
        assertThrows(IllegalArgumentException.class, () -> Band.of(101));
    }

    @Test void conditionDrivesTheTerm() {
        assertEquals(20, conditionTerm(100));
        assertEquals(0, conditionTerm(60));
        assertEquals(-30, conditionTerm(0));
        assertTrue(conditionTerm(80) > conditionTerm(40));
        assertThrows(IllegalArgumentException.class, () -> conditionTerm(101));
    }

    @Test void incidentsCostMoreTheLongerTheyStayOpenAndAreCapped() {
        assertEquals(0, incidentTerm(List.of()));
        assertEquals(-5, incidentTerm(List.of(0)));
        assertEquals(-15, incidentTerm(List.of(5)));
        assertEquals(-15, incidentTerm(List.of(50)), "age is capped");
        assertEquals(-5, incidentTerm(List.of(-3)), "a future date never helps");
        assertTrue(incidentTerm(List.of(4)) < incidentTerm(List.of(1)));
        assertEquals(-10, incidentTerm(List.of(0, 0)));
        assertEquals(-MAX_INCIDENT_PENALTY, incidentTerm(List.of(9, 9, 9, 9, 9, 9, 9, 9)));
    }

    @Test void billOutcomeMovesTheTerm() {
        assertEquals(5, billTerm(Bill.PAID));
        assertEquals(-10, billTerm(Bill.UNPAID));
        assertEquals(0, billTerm(Bill.NONE));
    }

    @Test void rentAgainstReferenceBands() {
        assertEquals(10, rentTerm(90, 100));
        assertEquals(5, rentTerm(91, 100));
        assertEquals(5, rentTerm(100, 100));
        assertEquals(0, rentTerm(110, 100));
        assertEquals(-8, rentTerm(111, 100));
        assertEquals(-8, rentTerm(125, 100));
        assertEquals(-15, rentTerm(126, 100));
        assertEquals(0, rentTerm(500, 0), "no reference means no signal");
        assertEquals(10, rentTerm(0, 100));
        assertThrows(IllegalArgumentException.class, () -> rentTerm(-1, 100));
    }

    @Test void targetCombinesInputsAndClamps() {
        assertEquals(50 + 20 + 5 + 10, target(new Inputs(100, List.of(), Bill.PAID, 90, 100)));
        assertEquals(0, target(new Inputs(0, List.of(9, 9, 9, 9, 9, 9, 9, 9), Bill.UNPAID, 200, 100)));
        assertEquals(85, target(new Inputs(100, List.of(), Bill.PAID, 1, 100)), "best case stays inside the range");
        assertEquals(50, target(new Inputs(60, List.of(), Bill.NONE, 105, 100)));
    }

    @Test void stepMovesAtMostFiveTowardTarget() {
        assertEquals(55, next(50, 90));
        assertEquals(45, next(50, 10));
        assertEquals(52, next(50, 52));
        assertEquals(50, next(50, 50));
        assertEquals(98, next(95, 98));
        assertEquals(0, next(2, 0));
        assertThrows(IllegalArgumentException.class, () -> next(101, 50));
    }

    @Test void neglectFallsOverDaysAndCareRecovers() {
        var neglected = new Inputs(10, List.of(3, 3), Bill.UNPAID, 140, 100);
        int sat = 50, days = 0;
        while (Band.of(sat) != Band.UNHAPPY && days < 30) { sat = next(sat, target(neglected)); days++; }
        assertEquals(Band.UNHAPPY, Band.of(sat));
        assertTrue(days >= 4 && days < 30, "gradual, not instant");
        var cared = new Inputs(100, List.of(), Bill.PAID, 90, 100);
        for (int i = 0; i < 40; i++) sat = next(sat, target(cared));
        assertEquals(Band.HAPPY, Band.of(sat));
    }

    @Test void inputsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Inputs(101, List.of(), Bill.NONE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Inputs(50, List.of(), Bill.NONE, -1, 1));
        assertThrows(NullPointerException.class, () -> new Inputs(50, List.of(), null, 1, 1));
    }

    @Test void everyBandHasOneShortPlainDialogueLine() {
        var seen = new java.util.HashSet<String>();
        for (Band band : Band.values()) {
            String line = ResidentDialogue.moodLine(band);
            assertTrue(line.length() > 10 && line.length() <= 60, line);
            assertFalse(line.contains("—") || line.contains("–"));
            assertTrue(seen.add(line));
        }
    }
}
