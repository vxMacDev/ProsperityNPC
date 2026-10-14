package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The per-line comparison behind the collection save proof; runs without the installed Paper jar. */
class ItemSaveProofUnitsTest {
    @Test void everyLineMustMatchExactly() {
        assertTrue(ItemSaveProof.unitsMatch(new int[]{4, 0}, List.of(4, 0)), "positive control");
        assertFalse(ItemSaveProof.unitsMatch(new int[]{5, 0}, List.of(4, 0)));
        assertFalse(ItemSaveProof.unitsMatch(new int[]{4, 1}, List.of(4, 0)));
        assertFalse(ItemSaveProof.unitsMatch(new int[]{3, 0}, List.of(4, 0)));
        assertFalse(ItemSaveProof.unitsMatch(new int[]{4}, List.of(4, 0)));
        assertFalse(ItemSaveProof.unitsMatch(new int[]{}, List.of()));
    }

    @Test void markersNameTheHandInAndTheSide() {
        var id = new java.util.UUID(3, 4);
        assertEquals(id + "/before", ItemSaveProof.marker(id, false));
        assertEquals(id + "/after", ItemSaveProof.marker(id, true));
        assertNotEquals(ItemSaveProof.marker(id, true), ItemSaveProof.marker(new java.util.UUID(3, 5), true));
        assertEquals("prosperitynpc:item_take", ItemSaveProof.MARKER_KEY);
        assertNotNull(org.bukkit.NamespacedKey.fromString(ItemSaveProof.MARKER_KEY), "a valid Bukkit key");
    }

    @Test void savedFileMustBeTheDataVersionRecordedWithTheItems() {
        assertNull(ItemSaveProof.dataVersionProblem(4790, List.of(4790)), "positive control: same version");
        assertNotNull(ItemSaveProof.dataVersionProblem(4791, List.of(4790)), "upgraded server");
        assertNotNull(ItemSaveProof.dataVersionProblem(4789, List.of(4790)), "older file");
        assertNotNull(ItemSaveProof.dataVersionProblem(null, List.of(4790)), "a file with no DataVersion");
        assertNotNull(ItemSaveProof.dataVersionProblem(4790, List.of(4790, 4791)), "items recorded at two versions");
        assertTrue(ItemSaveProof.dataVersionProblem(4791, List.of(4790)).contains("only when no DataVersion was recorded"),
                "the refusal names the one case that is not checked");
        // Older records with no DataVersion keep the old allowance, whatever the file says.
        assertNull(ItemSaveProof.dataVersionProblem(4791, List.of()));
        assertNull(ItemSaveProof.dataVersionProblem(null, List.of()));
        assertNull(ItemSaveProof.dataVersionProblem(4791, null));
    }

    @Test void expectationsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 65_537, 0));
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 0, 257));
        assertEquals(256, new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 0, 256).takenUnits(), "positive control");
        // Without the server's NBT classes the proof is unavailable and refuses to capture.
        var proof = new ItemSaveProof(ClassLoader.getPlatformClassLoader());
        assertFalse(proof.available());
        assertTrue(proof.captureCollection(java.nio.file.Path.of("."), new java.util.UUID(1, 1),
                List.of(new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 1, 1)), null).isEmpty());
        assertTrue(proof.baseline(java.nio.file.Path.of("."), new java.util.UUID(1, 1), new java.util.UUID(2, 2),
                List.of(new CustodyBytes(new byte[]{1}))).isEmpty());
        assertFalse(proof.verify((ItemSaveProof.CollectionSnapshot) null));
    }
}
