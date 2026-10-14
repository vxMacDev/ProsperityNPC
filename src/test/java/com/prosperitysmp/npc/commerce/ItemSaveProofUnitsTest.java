package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The per-line comparison behind the collection save proof; runs without the installed Paper jar. */
class ItemSaveProofUnitsTest {
    @Test void everyLineMustMatchExactly() {
        assertTrue(ItemSaveProof.unitsMatch(new int[]{4, 0}, List.of(4, 0)), "an exact match");
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
        assertNull(ItemSaveProof.dataVersionProblem(4790, List.of(4790)), "same version");
        assertNotNull(ItemSaveProof.dataVersionProblem(4791, List.of(4790)), "upgraded server");
        assertNotNull(ItemSaveProof.dataVersionProblem(4789, List.of(4790)), "older file");
        assertNotNull(ItemSaveProof.dataVersionProblem(null, List.of(4790)), "a file with no DataVersion");
        assertNotNull(ItemSaveProof.dataVersionProblem(4790, List.of(4790, 4791)), "items recorded at two versions");
        assertTrue(ItemSaveProof.dataVersionProblem(4791, List.of(4790)).contains("only when no DataVersion was recorded"),
                "the refusal names the one case that is not checked");
        // Records with no DataVersion are not checked, whatever the file says.
        assertNull(ItemSaveProof.dataVersionProblem(4791, List.of()));
        assertNull(ItemSaveProof.dataVersionProblem(null, List.of()));
        assertNull(ItemSaveProof.dataVersionProblem(4791, null));
    }

    @Test void expectationsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 65_537, 0));
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 0, 257));
        assertEquals(256, new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 0, 256).takenUnits(), "the largest taken count is accepted");
        // Without the server's NBT classes the proof is unavailable and refuses to capture.
        var proof = new ItemSaveProof(ClassLoader.getPlatformClassLoader());
        assertFalse(proof.available());
        assertTrue(proof.captureCollection(java.nio.file.Path.of("."), new java.util.UUID(1, 1),
                List.of(new ItemSaveProof.Expectation(new CustodyBytes(new byte[]{1}), 1, 1)), null).isEmpty());
        assertTrue(proof.baseline(java.nio.file.Path.of("."), new java.util.UUID(1, 1), new java.util.UUID(2, 2),
                List.of(new CustodyBytes(new byte[]{1}))).isEmpty());
        assertFalse(proof.verify((ItemSaveProof.CollectionSnapshot) null));
    }

    /** Each writer has its own key, so no delivery or hand-in overwrites another's marker. */
    @Test void markerKeysAreFixedAndDistinctPerSource() {
        assertEquals("prosperitynpc:item_take", ItemSaveProof.MARKER_KEY);
        assertEquals("prosperitynpc:item_return", ItemSaveProof.RETURN_MARKER_KEY);
        assertEquals("prosperitynpc:item_delivery", ItemSaveProof.DELIVERY_MARKER_KEY);
        assertEquals(3, java.util.Set.of(ItemSaveProof.MARKER_KEY, ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.DELIVERY_MARKER_KEY).size());
        for (String key : List.of(ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.DELIVERY_MARKER_KEY))
            assertNotNull(org.bukkit.NamespacedKey.fromString(key), "a valid Bukkit key: " + key);
        assertEquals(ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.markerKeyFor(ItemCustody.Source.REPAIR_RETURN));
        assertEquals(ItemSaveProof.DELIVERY_MARKER_KEY, ItemSaveProof.markerKeyFor(ItemCustody.Source.MERCHANT_GOODS));
        assertEquals(ItemSaveProof.DELIVERY_MARKER_KEY, ItemSaveProof.markerKeyFor(ItemCustody.Source.WORKER_OUTPUT));
    }

    /**
     * Smoke test: without the server's NBT classes every delivery read and capture comes back empty, for returns and orders
     * alike. Each call is also stopped by a null check or a caught exception, so this cannot see the available() guard
     * itself; deliveryMethodsStartWithTheAvailableGuard pins that.
     */
    @Test void unavailableProofRefusesDeliveryBaselineAndCapture() {
        var proof = new ItemSaveProof(ClassLoader.getPlatformClassLoader());
        assertFalse(proof.available());
        var path = java.nio.file.Path.of(".");
        var evidence = new ItemDeliveryEvidence(new java.util.UUID(1, 1), new CustodyBytes(new byte[]{1}).sha256(),
                "1".repeat(64), "2".repeat(64), 0, 8, 0, true);
        for (var source : ItemCustody.Source.values()) {
            var batch = new ItemCustody(new java.util.UUID(2, 2), new java.util.UUID(1, 1), new java.util.UUID(3, 3), source, 8,
                    new CustodyBytes(new byte[]{1}), java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(900));
            assertTrue(proof.deliveryBaseline(path, batch).isEmpty(), source.name());
            assertTrue(proof.deliveryBaselineOf(path, new byte[]{1, 2, 3}, batch).isEmpty(), source.name());
            assertTrue(proof.captureDelivery(path, batch, evidence, null).isEmpty(), source.name());
            assertTrue(proof.capture(path, batch, evidence).isEmpty(), source.name());
            assertTrue(proof.readSavedDelivery(new byte[]{1, 2, 3}, batch).isEmpty(), source.name());
        }
    }

    /** Each delivery method that reaches the NBT classes refuses first when they are missing. */
    @Test void deliveryMethodsStartWithTheAvailableGuard() throws Exception {
        String code = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/prosperitysmp/npc/commerce/ItemSaveProof.java"))
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ").replaceAll("\\s+", " ");
        for (String signature : List.of(
                "Optional<Baseline> deliveryBaselineOf(Path file, byte[] bytes, ItemCustody batch) {",
                "private Optional<Snapshot> capture(Path mainWorldFolder, ItemCustody batch, ItemDeliveryEvidence evidence, Baseline baseline) {",
                "public Optional<SavedUnits> readSavedDelivery(byte[] savedFile, ItemCustody batch) {",
                "public boolean verify(Snapshot snapshot) {")) {
            int at = code.indexOf(signature);
            assertTrue(at >= 0, "missing: " + signature);
            assertEquals(at, code.lastIndexOf(signature), "declared once: " + signature);
            assertTrue(code.startsWith(" if (!available() || ", at + signature.length()), "first line refuses: " + signature);
        }
    }
}
