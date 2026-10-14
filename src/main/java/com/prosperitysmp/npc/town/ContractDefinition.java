package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import java.util.Objects;
import java.util.UUID;

/**
 * One authored contract: bring a quantity of one plain material to a resident before a deadline. SUPPLY goes back to
 * the giver, DELIVER goes to another resident, PARTNERSHIP is a partnership start or renewal paid in items (always
 * to the resident itself). The reward is familiarity only. A contract never grants money, items, permissions or XP.
 * The same checks as the SQL table, so a row that passes here also passes the database.
 */
public record ContractDefinition(String id, Kind kind, String title, String description, UUID giverId, UUID recipientId,
                                 String material, int quantity, RelationshipMemory.Stage minimumStage, int durationMinutes) {
    public enum Kind { SUPPLY, DELIVER, PARTNERSHIP }
    public static final int MAX_TITLE = 80;
    public static final int MAX_DESCRIPTION = 512;
    public static final int MAX_QUANTITY = 256;
    /** The SQL floor. Authored contracts must be at least {@link #MIN_AUTHORED_MINUTES}; partnership hand-overs use 10. */
    public static final int MIN_DURATION = 10;
    public static final int MIN_AUTHORED_MINUTES = 30;
    public static final int MAX_DURATION = 1440;
    public static final String PARTNER_START = "partner-start";
    public static final String PARTNER_RENEW = "partner-renew";
    public static final int PARTNER_WINDOW_MINUTES = 10;

    public ContractDefinition {
        if (id == null || !id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Contract ID must be 1..48 of a-z 0-9 _ -");
        Objects.requireNonNull(kind);
        requireText(title, MAX_TITLE, "title");
        requireText(description, MAX_DESCRIPTION, "description");
        Objects.requireNonNull(giverId);
        Objects.requireNonNull(recipientId);
        Objects.requireNonNull(minimumStage);
        if (material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid contract material");
        if (quantity < 1 || quantity > MAX_QUANTITY) throw new IllegalArgumentException("Contract quantity must be 1..256");
        if (durationMinutes < MIN_DURATION || durationMinutes > MAX_DURATION)
            throw new IllegalArgumentException("Contract duration must be 10..1440 minutes");
        switch (kind) {
            case SUPPLY -> { if (!giverId.equals(recipientId)) throw new IllegalArgumentException("A supply contract goes back to its giver"); }
            case DELIVER -> { if (giverId.equals(recipientId)) throw new IllegalArgumentException("A delivery contract goes to another resident"); }
            case PARTNERSHIP -> {
                if (!giverId.equals(recipientId)) throw new IllegalArgumentException("A partnership hand-over goes to the resident");
                if (!id.equals(PARTNER_START) && !id.equals(PARTNER_RENEW)) throw new IllegalArgumentException("Unknown partnership hand-over");
            }
        }
    }

    /** True for a partnership start (not a renewal). */
    public boolean partnerStart() { return kind == Kind.PARTNERSHIP && id.equals(PARTNER_START); }

    /** The hand-over that pays for a partnership start or renewal: 10 minutes, no daily contract slot. */
    public static ContractDefinition partnership(boolean start, UUID resident, String material, int quantity,
                                                 RelationshipMemory.Stage minimumStage) {
        return new ContractDefinition(start ? PARTNER_START : PARTNER_RENEW, Kind.PARTNERSHIP,
                start ? "Partnership" : "Partnership renewal", "Materials for a partnership.", resident, resident,
                material, quantity, minimumStage, PARTNER_WINDOW_MINUTES);
    }

    static void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid contract " + field);
    }
}
