package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnerDeskDataTest {
    private static final LocalDate END = LocalDate.of(2026, 9, 7);

    @Test
    void snapshotDefensivelyCopiesAllLists() {
        var residents = new ArrayList<>(List.of(new OwnerDeskData.Resident(UUID.randomUUID(),
                "Desk Fixture", 0, "STANDARD", "NEUTRAL", 50)));
        var issues = new ArrayList<>(List.of(new OwnerDeskData.Issue(1, "PLUMBING", 50, Instant.EPOCH)));
        var upgrades = new ArrayList<>(List.of(new OwnerDeskData.Upgrade("FACILITIES", 1, true)));
        Property property = new Property(UUID.randomUUID(), UUID.randomUUID(), "desk", "market", "room",
                2, 100, 150, UUID.randomUUID(), 3, 80, true);
        var data = new OwnerDeskData(property, 42, Instant.EPOCH, residents, issues, upgrades,
                statement(100, 30, 1, 2));
        residents.clear();
        issues.clear();
        upgrades.clear();
        assertEquals(property, data.property());
        assertEquals(42, data.revision());
        assertEquals(Instant.EPOCH, data.capturedAt());
        assertEquals(1, data.residents().size());
        assertEquals(1, data.issues().size());
        assertEquals(1, data.upgrades().size());
        assertThrows(UnsupportedOperationException.class, () -> data.residents().clear());
        assertThrows(UnsupportedOperationException.class, () -> data.issues().clear());
        assertThrows(UnsupportedOperationException.class, () -> data.upgrades().clear());
    }

    @Test
    void exactNetSupportsLossZeroAndLongBoundariesWithoutFloatingPoint() {
        assertEquals(70, statement(100, 30, 0, 0).netCents());
        assertEquals(-70, statement(30, 100, 0, 0).netCents());
        assertEquals(0, statement(0, 0, 2, 3).netCents());
        assertEquals(Long.MAX_VALUE, statement(Long.MAX_VALUE, 0, 0, 0).netCents());
        assertEquals(-Long.MAX_VALUE, statement(0, Long.MAX_VALUE, 0, 0).netCents());
        assertEquals(1, statement(Long.MAX_VALUE, Long.MAX_VALUE - 1, 0, 0).netCents());
    }

    @Test
    void statementRejectsNegativeMoneyCountsAndWrongWindow() {
        assertThrows(IllegalArgumentException.class, () -> statement(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> statement(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> statement(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> statement(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new OwnerDeskData.Statement(END.minusDays(7), END, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OwnerDeskData.Statement(END, END.minusDays(6), 0, 0, 0, 0));
    }

    @Test
    void renewalUsesNextDayStartAndScheduledTermKeepsItsReservedInterval() {
        var current = new OwnerDeskData.Insurance(1600, END.minusDays(6), END.plusDays(1), true, true);
        assertEquals(false, current.canPurchase(END.minusDays(1)));
        assertEquals(true, current.canPurchase(END));
        var scheduled = new OwnerDeskData.Insurance(1600, END.plusDays(1), END.plusDays(8), false, true);
        assertEquals(false, scheduled.canPurchase(END));
        var previousOwner = new OwnerDeskData.Insurance(1600, END.minusDays(6), END.plusDays(1), false, false);
        assertEquals(true, previousOwner.canPurchase(END));
        Property property = new Property(UUID.randomUUID(), UUID.randomUUID(), "desk", "market", "room",
                2, 100, 150, UUID.randomUUID(), 3, 80, true);
        var data = new OwnerDeskData(property, 1, Instant.EPOCH, List.of(), List.of(), List.of(),
                statement(0, 0, 0, 0), current);
        assertEquals(true, data.canPurchaseInsurance());
    }

    private static OwnerDeskData.Statement statement(long gross, long expense, int pending, int review) {
        return new OwnerDeskData.Statement(END.minusDays(6), END, gross, expense, pending, review);
    }
}
