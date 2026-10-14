package com.prosperitysmp.npc.commerce;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Main-thread evidence from one item take (ItemInventoryCustody.collect), not authorization. Units count plain
 * items in inventory storage plus the ender chest, the same scope the save proof reads back. durable is set
 * only after ItemSaveProof verified the saved player file. Shared by every feature that takes items.
 */
public record ItemCollectionEvidence(UUID playerId, UUID collectionId, String beforeInventorySha256,
                                     String afterInventorySha256, List<Integer> beforeUnits, List<Integer> afterUnits,
                                     boolean returnedNormally, boolean durable) {
    public static final int MAX_LINES = 4;

    public ItemCollectionEvidence {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(collectionId);
        ItemDeliveryEvidence.requireHash(beforeInventorySha256);
        ItemDeliveryEvidence.requireHash(afterInventorySha256);
        beforeUnits = List.copyOf(beforeUnits);
        afterUnits = List.copyOf(afterUnits);
        if (beforeUnits.isEmpty() || beforeUnits.size() > MAX_LINES || beforeUnits.size() != afterUnits.size())
            throw new IllegalArgumentException("Collection evidence needs one count per line");
        for (int i = 0; i < beforeUnits.size(); i++) {
            int before = beforeUnits.get(i), after = afterUnits.get(i);
            if (before < 0 || after < 0 || before > ItemDeliveryEvidence.MAX_OBSERVED_UNITS
                    || after > ItemDeliveryEvidence.MAX_OBSERVED_UNITS)
                throw new IllegalArgumentException("Inventory observation exceeds bounds");
        }
    }

    public ItemCollectionEvidence withDurable(boolean verified) {
        return new ItemCollectionEvidence(playerId, collectionId, beforeInventorySha256, afterInventorySha256,
                beforeUnits, afterUnits, returnedNormally, durable && verified);
    }

    /**
     * True only when it is the same player, the counts at the start equal the recorded ones, exactly the asked
     * quantity of every line left, the inventory changed, the take returned normally and the save was proven.
     */
    public boolean confirms(UUID player, List<Integer> expectedBefore, List<Integer> quantities) {
        if (!playerId.equals(player) || expectedBefore.size() != beforeUnits.size() || quantities.size() != beforeUnits.size())
            return false;
        for (int i = 0; i < beforeUnits.size(); i++) {
            if (!beforeUnits.get(i).equals(expectedBefore.get(i))) return false;
            if (beforeUnits.get(i) - afterUnits.get(i) != quantities.get(i)) return false;
        }
        return !beforeInventorySha256.equals(afterInventorySha256) && returnedNormally && durable;
    }

    /** "[16,4]" style, one non-negative whole number per line, for the evidence table. */
    public static String json(List<Integer> units) {
        var out = new StringBuilder("[");
        for (int i = 0; i < units.size(); i++) {
            if (i > 0) out.append(',');
            out.append(units.get(i));
        }
        return out.append(']').toString();
    }

    public static List<Integer> parseJson(String text) {
        if (text == null || !text.matches("\\[[0-9]{1,5}(,[0-9]{1,5}){0,3}]")) throw new IllegalArgumentException("Invalid unit list");
        return Arrays.stream(text.substring(1, text.length() - 1).split(",")).map(Integer::valueOf).toList();
    }
}
