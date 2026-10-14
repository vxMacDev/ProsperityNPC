package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownTaskTest {
    private static final UUID GIVER = new UUID(0, 1);
    private static final UUID RECIPIENT = new UUID(0, 2);
    private static final Instant AT = Instant.parse("2026-09-29T12:00:00.123456789Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "note space", "note:1", "../note", "note/1", "note\\1", "note\n"})
    void rejectsNoncanonicalIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> definition(id, "A note", "A sealed note", 5));
    }

    @Test
    void validatesAuthoredBoundsWithoutSilentlyRewritingInput() {
        assertEquals(5, definition("_-0", "A", "B", 5).durationMinutes());
        definition("x".repeat(48), "T".repeat(80), "D".repeat(512), 1440);
        assertThrows(IllegalArgumentException.class, () -> definition("x".repeat(49), "A", "B", 5));
        assertThrows(IllegalArgumentException.class, () -> definition("note", "T".repeat(81), "B", 5));
        assertThrows(IllegalArgumentException.class, () -> definition("note", "A", "D".repeat(513), 5));
        for (String text : new String[] {"", " ", " padded", "padded ", "control\nline", "control\u0000"}) {
            assertThrows(IllegalArgumentException.class, () -> definition("note", text, "B", 5));
            assertThrows(IllegalArgumentException.class, () -> definition("note", "A", text, 5));
        }
        for (int duration : new int[] {-1, 0, 4, 1441, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> definition("note", "A", "B", duration));
        }
        assertThrows(IllegalArgumentException.class, () -> new TownTaskDefinition("note", "A", "B",
                GIVER, GIVER, RelationshipMemory.Stage.STRANGER, 5));
        assertThrows(NullPointerException.class, () -> new TownTaskDefinition("note", "A", "B",
                GIVER, RECIPIENT, null, 5));
    }

    @Test
    void taskNormalizesMillisecondsAndChecksTerminalEvidence() {
        TownTask task = task(TownTask.Status.ACTIVE, null, null);
        assertEquals(Instant.ofEpochMilli(AT.toEpochMilli()), task.acceptedAt());
        assertEquals(task.acceptedAt().plusSeconds(300), task.expiresAt());
        task(TownTask.Status.COMPLETED, AT.plusSeconds(1), DAY);
        task(TownTask.Status.CANCELLED, AT.plusSeconds(1), null);
        task(TownTask.Status.EXPIRED, AT.plusSeconds(300), null);
        assertThrows(IllegalArgumentException.class, () -> task(TownTask.Status.ACTIVE, AT, null));
        assertThrows(NullPointerException.class, () -> task(TownTask.Status.COMPLETED, AT, null));
        assertThrows(IllegalArgumentException.class, () -> task(TownTask.Status.COMPLETED, AT, DAY.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> task(TownTask.Status.COMPLETED, AT.plusSeconds(300), DAY));
        assertThrows(IllegalArgumentException.class, () -> task(TownTask.Status.CANCELLED, AT.minusMillis(1), null));
        assertThrows(IllegalArgumentException.class, () -> task(TownTask.Status.CANCELLED, AT, DAY));
        assertThrows(IllegalArgumentException.class, () -> task(TownTask.Status.EXPIRED, AT.plusSeconds(301), null));
        assertThrows(IllegalArgumentException.class, () -> TownTask.normalize(Instant.MAX));
    }

    private static TownTask task(TownTask.Status status, Instant closed, LocalDate completedDay) {
        return new TownTask(UUID.randomUUID(), UUID.randomUUID(), definition("note", "A", "B", 5),
                AT, DAY, AT.plusSeconds(300), status, closed, completedDay);
    }

    private static TownTaskDefinition definition(String id, String title, String description, int minutes) {
        return new TownTaskDefinition(id, title, description, GIVER, RECIPIENT, RelationshipMemory.Stage.STRANGER, minutes);
    }
}
