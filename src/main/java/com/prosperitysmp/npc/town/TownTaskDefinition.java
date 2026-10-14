package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory;

import java.util.Objects;
import java.util.UUID;

/** Authored virtual sealed-note errand. Never grants items, money, permissions, or XP. */
public record TownTaskDefinition(String id, String title, String description, UUID giverId, UUID recipientId,
                                 RelationshipMemory.Stage minimumStage, int durationMinutes) {
    public static final int MAX_DESCRIPTION = 512;

    public TownTaskDefinition {
        if (id == null || !id.matches("[a-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("Task ID must be canonical and 1..48 characters");
        }
        requireText(title, 80, "title");
        requireText(description, MAX_DESCRIPTION, "description");
        Objects.requireNonNull(giverId);
        Objects.requireNonNull(recipientId);
        Objects.requireNonNull(minimumStage);
        if (giverId.equals(recipientId)) throw new IllegalArgumentException("Task residents must differ");
        if (durationMinutes < 5 || durationMinutes > 1440) {
            throw new IllegalArgumentException("Task duration must be 5..1440 minutes");
        }
    }

    private static void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid task " + field);
        }
    }
}
