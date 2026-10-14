package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The offline recovery tool's last gate before it writes: an unclear decision (a chest move looks like a take; an
 * unproven "not delivered" would give items twice) goes ahead only with a staff reason. main() needs root and a stopped
 * server, so the gate is a pure function and its use on both resolve paths is pinned in the source.
 */
class ItemRepairRecoveryGateTest {
    private static final UUID PLAYER = new UUID(7, 1), OTHER_PLAYER = new UUID(7, 2);
    private static final UUID HAND_IN = new UUID(8, 1), OTHER_HAND_IN = new UUID(8, 2);

    @Test void aClearDecisionNeedsNoReason() {
        assertEquals("", ItemRepairRecoveryTool.checkedOverride(null, ""));
        assertEquals("", ItemRepairRecoveryTool.checkedOverride(null, null));
        assertEquals("checked in game", ItemRepairRecoveryTool.checkedOverride(null, "  checked in game "));
    }

    @Test void anUnclearDecisionIsRefusedWithoutAReason() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> ItemRepairRecoveryTool.checkedOverride("No recorded take counts.", ""));
        assertTrue(refused.getMessage().startsWith("No recorded take counts."), refused.getMessage());
        assertTrue(refused.getMessage().contains("override_reason"));
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecoveryTool.checkedOverride("x", "   "));
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecoveryTool.checkedOverride("x", null));
        // Positive control: the same problem with a reason goes ahead and the reason is what gets recorded.
        assertEquals("player showed the chest on stream", ItemRepairRecoveryTool.checkedOverride("x", "player showed the chest on stream"));
    }

    @Test void anOverlongReasonIsRefused() {
        assertEquals(512, ItemRepairRecoveryTool.checkedOverride("x", "r".repeat(512)).length());
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecoveryTool.checkedOverride("x", "r".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecoveryTool.checkedOverride(null, "r".repeat(513)));
    }

    @Test void recordedCountsBindOnlyTheirOwnPlayerAndHandIn() {
        assertDoesNotThrow(() -> ItemRepairRecoveryTool.requireBinding(PLAYER, HAND_IN, PLAYER, HAND_IN));
        assertThrows(IllegalStateException.class, () -> ItemRepairRecoveryTool.requireBinding(OTHER_PLAYER, HAND_IN, PLAYER, HAND_IN));
        assertThrows(IllegalStateException.class, () -> ItemRepairRecoveryTool.requireBinding(PLAYER, OTHER_HAND_IN, PLAYER, HAND_IN));
    }

    private static final com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits FOUR_ZERO =
            new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(java.util.List.of(4, 0), 4790, java.util.List.of(4790),
                    java.util.List.of(4, 0), null, null);

    /**
     * P1-B repro. The take records [20,8] to [4,0], the save fails and the hand-in is held. A crash restores [20,8]. After
     * the restart the player moves [16,8] into a chest and the server saves and stops. The file now shows [4,0], which
     * agrees with the recorded counts, but it is not the take's own save.
     */
    @Test void restoredItemsMovedIntoAChestNeedAReason() {
        var recorded = new com.prosperitysmp.npc.commerce.ItemCollectionEvidence(PLAYER, HAND_IN, "a".repeat(64), "b".repeat(64),
                java.util.List.of(20, 8), java.util.List.of(4, 0), true, false);
        String problem = ItemRepairRecoveryTool.handInProblem(FOUR_ZERO, ItemRepairRepository.Decision.VERIFIED_COLLECTED,
                java.util.List.of(20, 8), java.util.List.of(16, 8), recorded);
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecoveryTool.checkedOverride(problem, ""),
                "a free repair: the restored items are in the chest");
        assertEquals("checked the chest", ItemRepairRecoveryTool.checkedOverride(problem, "checked the chest"), "staff can still decide");
        // A proven save does not help either: play since the save can have moved items, and no hash shows there was none.
        var proven = new com.prosperitysmp.npc.commerce.ItemCollectionEvidence(PLAYER, HAND_IN, "a".repeat(64), "b".repeat(64),
                java.util.List.of(20, 8), java.util.List.of(4, 0), true, true);
        for (var decision : ItemRepairRepository.Decision.values())
            for (var evidence : java.util.Arrays.asList(recorded, proven, null))
                assertTrue(ItemRepairRecoveryTool.handInProblem(FOUR_ZERO, decision, java.util.List.of(20, 8), java.util.List.of(16, 8), evidence)
                        .startsWith(ItemRepairRecoveryTool.UNPROVEN_FILE), "every hand-in decision needs a reason");
        // The other checks still run and are reported with it.
        assertTrue(ItemRepairRecoveryTool.handInProblem(FOUR_ZERO, ItemRepairRepository.Decision.VERIFIED_COLLECTED,
                java.util.List.of(20, 8), java.util.List.of(16, 8), null).contains("chest"));
        assertTrue(ItemRepairRecoveryTool.handInProblem(new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(java.util.List.of(4, 0), 4791,
                java.util.List.of(4790), java.util.List.of(4, 0), null, null), ItemRepairRepository.Decision.VERIFIED_COLLECTED, java.util.List.of(20, 8), java.util.List.of(16, 8),
                proven).contains("DataVersion 4791"));
    }

    /** The same on the return path: a stack recorded as added, a failed save, a restore, then 16 more picked up. */
    @Test void aReturnRestoredAndRefilledFromAChestNeedsAReason() {
        var recorded = new com.prosperitysmp.npc.commerce.ItemDeliveryEvidence(PLAYER, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                4, 20, 0, false);
        var twenty = new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(java.util.List.of(20), 4790, java.util.List.of(4790),
                java.util.List.of(20), null, null);
        String problem = ItemRepairRecoveryTool.returnProblem(twenty,
                com.prosperitysmp.npc.commerce.ItemCustodyRepository.Decision.VERIFIED_DELIVERED, 16, recorded);
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecoveryTool.checkedOverride(problem, ""),
                "the player loses the return: the 16 in the file came from a chest");
        var nothing = new com.prosperitysmp.npc.commerce.ItemDeliveryEvidence(PLAYER, "a".repeat(64), "b".repeat(64), "b".repeat(64),
                4, 4, 16, true);
        var four = new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(java.util.List.of(4), 4790, java.util.List.of(4790),
                java.util.List.of(4), null, null);
        for (var decision : com.prosperitysmp.npc.commerce.ItemCustodyRepository.Decision.values())
            for (var evidence : java.util.Arrays.asList(recorded, nothing, null))
                for (var file : java.util.List.of(twenty, four))
                    assertTrue(ItemRepairRecoveryTool.returnProblem(file, decision, 16, evidence).startsWith(ItemRepairRecoveryTool.UNPROVEN_FILE),
                            "every return decision needs a reason");
    }

    @Test void bothResolvePathsPassTheGateBeforeWritingAnything() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/ItemRepairRecoveryTool.java"));
        String call = "checkedOverride(problem, evidence.getProperty(\"override_reason\", \"\"))";
        assertEquals(2, code.split(java.util.regex.Pattern.quote(call), -1).length - 1, "the hand-in path and the return path");
        // Each call comes after that path's problems() and before its evidence bundle and repository write.
        int from = 0;
        for (String write : new String[] {"repository.reconcile(collection, player, resolution", "repository.resolveReturn(request, run, resolution"}) {
            int gate = code.indexOf(call, from);
            int problems = code.lastIndexOf("String problem = ", gate);
            int bundle = code.indexOf("part(bundle, override.getBytes", gate);
            int written = code.indexOf(write, gate);
            assertTrue(problems >= from && problems < gate, "problems() feeds the gate: " + write);
            assertTrue(bundle > gate && written > bundle, "nothing is written before the gate: " + write);
            from = written;
        }
        assertEquals(1, code.split(java.util.regex.Pattern.quote("problem != null && override.isEmpty()"), -1).length - 1,
                "the gate condition lives only in checkedOverride");
        assertTrue(code.contains("if (recorded != null) requireBinding(recorded.playerId(), recorded.collectionId(), player, collection);"));
    }
}
