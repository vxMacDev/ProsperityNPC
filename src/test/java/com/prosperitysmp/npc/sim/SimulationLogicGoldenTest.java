package com.prosperitysmp.npc.sim;

import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.sim.SimulationLogic.OccupancyChange;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed daily decisions for one property and install seed, so a restart can never reroll an outcome. */
class SimulationLogicGoldenTest {
    private static final UUID P = new UUID(0, 1);
    private final SimulationLogic sim = new SimulationLogic(new DailyRoll(4242L));

    @Test void incidentDaysAndTypesAtConditionZeroAreFixed() {
        var found = new ArrayList<String>();
        for (int i = 0; i < 49; i++) {
            Optional<IncidentType> incident = sim.incidentForDay(P, "d" + i, 0);
            if (incident.isPresent()) found.add(i + ":" + incident.get().name());
        }
        assertEquals(List.of("4:PLUMBING", "6:PESTS", "33:PESTS", "35:PESTS", "38:PLUMBING", "42:BREAK_IN",
                "46:BREAK_IN", "48:PESTS"), found);
    }

    @Test void atConditionOneHundredOnlyTheOnePercentDaysHaveIncidents() {
        var days = new ArrayList<Integer>();
        for (int i = 0; i < 400; i++) if (sim.incidentForDay(P, "d" + i, 100).isPresent()) days.add(i);
        assertEquals(List.of(48, 123, 262, 379), days);
    }

    @Test void conditionsPastTheClampBehaveLikeTheClamp() {
        for (int i = 0; i < 300; i++) {
            assertEquals(sim.incidentForDay(P, "d" + i, 0), sim.incidentForDay(P, "d" + i, -50), "8% ceiling");
            assertEquals(sim.incidentForDay(P, "d" + i, 100), sim.incidentForDay(P, "d" + i, 90), "1% floor");
        }
    }

    @Test void occupancyDecisionsForOneOccupiedTwoRoomHomeAreFixed() {
        var line = new StringBuilder();
        for (int i = 0; i < 40; i++)
            line.append(sim.occupancyForDay(P, "d" + i, 50.0, 0, 1, 2, false).name().charAt(0));
        assertEquals("NNFNNNNNLNNNNNNNNNNNNNFNLFNNNNNFNNNNNLNL", line.toString());
    }

    @Test void leavesTodayIsTheLeaveHalfOfTheOccupancyDecision() {
        for (int i = 0; i < 500; i++) {
            String day = "d" + i;
            boolean viaOccupancy = sim.occupancyForDay(P, day, 50.0, 0, 1, 2, false) == OccupancyChange.LEAVE;
            assertEquals(viaOccupancy, sim.leavesToday(P, day, 50.0, false), day);
            boolean overdue = sim.occupancyForDay(P, day, 20.0, 3, 1, 1, true) == OccupancyChange.LEAVE;
            assertEquals(overdue, sim.leavesToday(P, day, 20.0, true), day);
        }
    }

    @Test void aFullHomeNeverFillsAndAnEmptyHomeNeverLeaves() {
        for (int i = 0; i < 2_000; i++) {
            String day = "d" + i;
            assertNotEquals(OccupancyChange.FILL, sim.occupancyForDay(P, day, 100.0, 3, 2, 2, false));
            assertNotEquals(OccupancyChange.LEAVE, sim.occupancyForDay(P, day, 0.0, 0, 0, 2, true));
            assertEquals(OccupancyChange.NONE, sim.occupancyForDay(P, day, 50.0, 0, 0, 0, false), "no rooms, no change");
        }
    }

    @Test void aLeaveIsDecidedBeforeAFill() {
        int both = 0;
        for (int i = 0; i < 2_000; i++) {
            String day = "d" + i;
            boolean leaves = sim.leavesToday(P, day, 0.0, true);
            boolean fillsWhenEmpty = sim.occupancyForDay(P, day, 0.0, 0, 0, 4, true) == OccupancyChange.FILL;
            if (leaves && fillsWhenEmpty) {
                both++;
                assertEquals(OccupancyChange.LEAVE, sim.occupancyForDay(P, day, 0.0, 0, 1, 4, true), day);
            }
        }
        assertTrue(both > 0, "some day rolls both a leave and a fill");
    }

    @Test void leavingIndexIsTheVoluntaryLeaveRoll() {
        var indexes = new ArrayList<Integer>();
        for (int i = 0; i < 12; i++) indexes.add(sim.leavingIndex(P, "d" + i, 4));
        assertEquals(List.of(2, 2, 1, 2, 3, 2, 3, 0, 1, 0, 3, 2), indexes);
        assertEquals(new DailyRoll(4242L).roll(P, "d0", "voluntary-leave-resident", 9), sim.leavingIndex(P, "d0", 9));
    }

    @Test void leavingIndexWithOneCandidateIsAlwaysTheFirst() {
        for (int i = 0; i < 365; i++) assertEquals(0, sim.leavingIndex(new UUID(i, i), "2026-01-01+" + i, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void leavingIndexWithNoCandidatesIsRefused(int count) {
        assertThrows(IllegalArgumentException.class, () -> sim.leavingIndex(P, "d", count));
    }

    @Test void leavingIndexVariesAcrossAYearAndRepeatsForTheSameDay() {
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 365; i++) {
            int index = sim.leavingIndex(P, "day-" + i, 3);
            assertTrue(index >= 0 && index < 3);
            assertEquals(index, sim.leavingIndex(P, "day-" + i, 3));
            seen.add(index);
        }
        assertEquals(3, seen.size());
    }
}
