package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.property.IncidentServiceAssessment.Case;
import com.prosperitysmp.npc.property.IncidentServiceAssessment.Outcome;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** A proven repair is a cash event or an item hand-in, exactly one, and item repairs count like cash ones. */
class ItemRepairCaseTest {
    private static final UUID PROPERTY = new UUID(0, 2), ITEMS = new UUID(0, 3);
    private static final Instant DUE = Instant.parse("2026-09-05T12:00:00Z"), CREATED = DUE.minus(Duration.ofDays(1));

    private static Case make(Instant resolved, Long event, UUID items, Outcome outcome) {
        return new Case(9, PROPERTY, 0, CREATED, DUE, resolved, event, items, outcome);
    }

    @Test void itemRepairCountsOnTimeOrLate() {
        assertDoesNotThrow(() -> make(DUE, null, ITEMS, Outcome.ON_TIME));
        assertDoesNotThrow(() -> make(DUE.plusMillis(1), null, ITEMS, Outcome.LATE));
        assertDoesNotThrow(() -> make(DUE, 5L, null, Outcome.ON_TIME));
        assertThrows(IllegalArgumentException.class, () -> make(DUE, null, null, Outcome.ON_TIME));
        assertThrows(IllegalArgumentException.class, () -> make(DUE.plusMillis(1), null, null, Outcome.LATE));
    }

    @Test void neverBothCashAndItems() {
        for (Outcome outcome : Outcome.values())
            assertThrows(IllegalArgumentException.class, () -> make(DUE, 5L, ITEMS, outcome), outcome.name());
    }

    @Test void unprovenOutcomesRefuseAnItemRepairId() {
        assertThrows(IllegalArgumentException.class, () -> make(null, null, ITEMS, Outcome.OVERDUE));
        assertThrows(IllegalArgumentException.class, () -> make(DUE, null, ITEMS, Outcome.UNVERIFIED_RESOLUTION));
        assertDoesNotThrow(() -> make(DUE, null, null, Outcome.UNVERIFIED_RESOLUTION));
        assertDoesNotThrow(() -> make(null, null, null, Outcome.OVERDUE));
    }

    @Test void handoverReviewNeedsProofExactlyWhenResolved() {
        assertDoesNotThrow(() -> make(DUE, null, ITEMS, Outcome.HANDOVER_REVIEW));
        assertDoesNotThrow(() -> make(null, null, null, Outcome.HANDOVER_REVIEW));
        assertThrows(IllegalArgumentException.class, () -> make(null, null, ITEMS, Outcome.HANDOVER_REVIEW));
        assertThrows(IllegalArgumentException.class, () -> make(DUE, null, null, Outcome.HANDOVER_REVIEW));
    }

    @Test void theOldEightArgumentCaseIsACashCase() {
        var old = new Case(9, PROPERTY, 0, CREATED, DUE, DUE, 5L, Outcome.ON_TIME);
        assertNull(old.itemRepairId());
        assertEquals(make(DUE, 5L, null, Outcome.ON_TIME), old);
    }
}
