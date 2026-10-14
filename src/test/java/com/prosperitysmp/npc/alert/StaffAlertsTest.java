package com.prosperitysmp.npc.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prosperitysmp.npc.alert.StaffAlerts.Kind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaffAlertsTest {
    private boolean on = true;
    private LocalDate day = LocalDate.of(2026, 10, 13);
    private final List<String> logged = new ArrayList<>();
    private final StaffAlerts alerts = new StaffAlerts(() -> on, () -> day, logged::add);

    @Test
    void dedupesPerKindAndSubjectPerDay() {
        assertTrue(alerts.raise(Kind.RENT_PROPERTY_HELD, "p1", "Rent held: a."));
        assertFalse(alerts.raise(Kind.RENT_PROPERTY_HELD, "p1", "Rent held: a."));
        assertTrue(alerts.raise(Kind.RENT_PROPERTY_HELD, "p2", "Rent held: b."));
        assertTrue(alerts.raise(Kind.RESERVE_BELOW_FLOOR, "p1", "Floor."));
        assertEquals(3, logged.size());
        assertEquals(3, alerts.drain(20).size());
        day = day.plusDays(1);
        assertTrue(alerts.raise(Kind.RENT_PROPERTY_HELD, "p1", "Rent held: a."));
        assertEquals(4, logged.size());
    }

    @Test
    void boundedQueueDropsOldestAndCountsThem() {
        for (int i = 0; i < 70; i++) alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "s" + i, "alert " + i);
        assertEquals(64, alerts.size());
        assertEquals(6, alerts.droppedCount());
        var first = alerts.drain(3);
        assertEquals("6 older staff alerts were dropped.", first.get(0));
        assertEquals("alert 6", first.get(1));
        assertEquals("alert 7", first.get(2));
        assertEquals(0, alerts.droppedCount());
        assertEquals(20, alerts.drain(500).size());
        assertEquals(1, alerts.drain(-4).size());
    }

    @Test
    void flagOffQueuesAndLogsNothing() {
        on = false;
        assertFalse(alerts.raise(Kind.ARM_HOOK_UNAVAILABLE, "arm-hook", "x"));
        assertEquals(0, alerts.size());
        assertTrue(logged.isEmpty());
        assertTrue(alerts.drain(5).isEmpty());
        on = true;
        assertTrue(alerts.raise(Kind.ARM_HOOK_UNAVAILABLE, "arm-hook", "x"));
    }

    @Test
    void textIsOneLineWithoutDashesAndCapNearIsNinetyPercent() {
        alerts.raise(Kind.DAILY_PHASE_FAILED, "phase-4", "line one\nline two — end");
        assertEquals("line one line two end", alerts.drain(1).get(0));
        assertFalse(StaffAlerts.capNear(13499, 15000));
        assertTrue(StaffAlerts.capNear(13500, 15000));
        assertFalse(StaffAlerts.capNear(100, 0));
    }
    @Test
    void itemRepairHeldIsItsOwnKindPerReference() {
        assertTrue(alerts.raise(Kind.ITEM_REPAIR_HELD, "c1", "Staff alert: Item repair held for review. Reference c1."));
        assertFalse(alerts.raise(Kind.ITEM_REPAIR_HELD, "c1", "Staff alert: Item repair held for review. Reference c1."));
        assertTrue(alerts.raise(Kind.ITEM_REPAIR_HELD, "c2", "Staff alert: Item repair held for review. Reference c2."));
        // Same subject, different kind: separate alert.
        assertTrue(alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "c1", "Payment held."));
        assertEquals(List.of("Staff alert: Item repair held for review. Reference c1.",
                "Staff alert: Item repair held for review. Reference c2.", "Payment held."), alerts.drain(20));
    }

    @Test
    void handoverHeldIsItsOwnKindOncePerContractPerDay() {
        // contracts-partnerships: a held hand-over alerts once per contract per day, separate from item repairs.
        assertTrue(alerts.raise(Kind.HANDOVER_HELD, "k1", "Contract handover held for review."));
        assertFalse(alerts.raise(Kind.HANDOVER_HELD, "k1", "Contract handover held for review."));
        assertTrue(alerts.raise(Kind.ITEM_REPAIR_HELD, "k1", "Item repair held."));
        assertTrue(alerts.raise(Kind.HANDOVER_HELD, "k2", "Contract handover held for review, second."));
        assertEquals(List.of("Contract handover held for review.", "Item repair held.",
                "Contract handover held for review, second."), alerts.drain(20));
    }
}
