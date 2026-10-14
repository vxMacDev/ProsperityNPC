package com.prosperitysmp.npc.sim;

import com.prosperitysmp.npc.formulas.IncidentType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationLogicTest {

    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // ---- DailyRoll: the "restarts cannot reroll" guarantee -----------------

    @Test
    void roll_isDeterministic() {
        DailyRoll r = new DailyRoll(12345L);
        assertEquals(r.roll(P, "2026-07-18", "incident-occurs", 10_000),
                r.roll(P, "2026-07-18", "incident-occurs", 10_000));
    }

    @Test
    void roll_inRange() {
        DailyRoll r = new DailyRoll(999L);
        for (int i = 0; i < 500; i++) {
            int v = r.roll(P, "day-" + i, "x", 100);
            assertTrue(v >= 0 && v < 100, "roll out of range: " + v);
        }
    }

    @Test
    void roll_variesByDayAndSeed() {
        DailyRoll r = new DailyRoll(1L);
        // Different days almost never collide over a handful of samples.
        assertNotEquals(r.roll(P, "a", "x", 1_000_000), r.roll(P, "b", "x", 1_000_000));
        // Different install seeds diverge too.
        DailyRoll r2 = new DailyRoll(2L);
        assertNotEquals(r.roll(P, "a", "x", 1_000_000), r2.roll(P, "a", "x", 1_000_000));
    }

    // ---- Occupancy respects capacity ---------------------------------------

    @Test
    void occupancy_neverFillsWhenFull() {
        SimulationLogic sim = new SimulationLogic(new DailyRoll(7L));
        for (int i = 0; i < 1000; i++) {
            var change = sim.occupancyForDay(P, "d" + i, 100.0, 3, 4, 4, false);
            assertNotEquals(SimulationLogic.OccupancyChange.FILL, change);
        }
    }

    @Test
    void occupancy_neverLeavesWhenEmpty() {
        SimulationLogic sim = new SimulationLogic(new DailyRoll(7L));
        for (int i = 0; i < 1000; i++) {
            var change = sim.occupancyForDay(P, "d" + i, 10.0, 0, 0, 4, true);
            assertNotEquals(SimulationLogic.OccupancyChange.LEAVE, change);
        }
    }

    // ---- Incident rate tracks the configured probability -------------------

    @Test
    void incidentRate_tracksChance() {
        SimulationLogic sim = new SimulationLogic(new DailyRoll(4242L));
        int n = 10_000;
        int at100 = 0;   // condition 100 -> 1% chance
        int at0 = 0;     // condition 0   -> 8% chance
        for (int i = 0; i < n; i++) {
            String day = "day-" + i;
            if (sim.incidentForDay(P, day, 100).isPresent()) {
                at100++;
            }
            if (sim.incidentForDay(P, day, 0).isPresent()) {
                at0++;
            }
        }
        // Expected ~100 (1%) and ~800 (8%); wide tolerance for a deterministic hash.
        assertTrue(at100 > 40 && at100 < 200, "condition-100 incidents=" + at100);
        assertTrue(at0 > 600 && at0 < 1000, "condition-0 incidents=" + at0);
        // Bad condition must clearly produce more incidents than good condition.
        assertTrue(at0 > at100 * 3, "expected far more incidents at condition 0");
    }

    @Test
    void incidentType_isValid() {
        SimulationLogic sim = new SimulationLogic(new DailyRoll(1L));
        // Force many incidents at low condition and confirm every type is a real one.
        for (int i = 0; i < 200; i++) {
            Optional<IncidentType> t = sim.incidentForDay(P, "d" + i, 0);
            t.ifPresent(it -> assertTrue(it == IncidentType.PESTS || it == IncidentType.PLUMBING
                    || it == IncidentType.BREAK_IN));
        }
    }
}
