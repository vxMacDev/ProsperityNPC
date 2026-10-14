package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** --inspect-delivery reports only what the file and the records show, and writes nothing. */
class CommerceRecoveryToolTest {
    private static final UUID NONCE = new UUID(11, 1);

    @Test void orderMarkerLineSaysOnlyWhatTheMarkerProves() {
        String after = CommerceRecoveryTool.orderMarkerLine(NONCE, ItemSaveProof.marker(NONCE, true));
        assertEquals("Marker: this order delivery's after marker. The file was saved at or after the delivery tick."
                + " The marker alone does not prove the items were added; compare the counts.", after);
        assertEquals("Marker: this order delivery's before marker. The file was saved before the items were given and does"
                + " not show them.", CommerceRecoveryTool.orderMarkerLine(NONCE, ItemSaveProof.marker(NONCE, false)));
        String none = "Marker: none for this order delivery. It ran without the save marker or its marker was lost, so the"
                + " marker says nothing about it.";
        assertEquals(none, CommerceRecoveryTool.orderMarkerLine(NONCE, null));
        assertEquals(none, CommerceRecoveryTool.orderMarkerLine(NONCE, ItemSaveProof.marker(new UUID(11, 2), true)),
                "another order's after marker says nothing about this one");
        assertEquals(none, CommerceRecoveryTool.orderMarkerLine(NONCE, ItemSaveProof.marker(new UUID(11, 2), false)));
        // Never the return tool's stronger wording.
        for (String line : List.of(after, none)) {
            assertFalse(line.contains("saved after the delivery."), line);
            assertFalse(line.contains("saved before this"), line);
            assertFalse(line.contains("—") || line.contains("–"), line);
        }
    }

    @Test void theReportShowsTheStatesBothCountsAndTheRecordedCounts() {
        var request = new ItemCustody(NONCE, new UUID(5, 5), NONCE, ItemCustody.Source.MERCHANT_GOODS, 32,
                new CustodyBytes(new byte[]{1}), java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(900));
        var held = new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.AMBIGUOUS, new UUID(1, 1), "EVIDENCE_UNCERTAIN");
        var read = new ItemSaveProof.SavedUnits(List.of(40), 4790, List.of(4790), List.of(48), null, null, ItemSaveProof.marker(NONCE, true));
        String none = CommerceRecoveryTool.deliveryReport(NONCE + " FUNDED", "STONE", held, read, null, "f".repeat(64));
        assertTrue(none.contains("| custody AMBIGUOUS EVIDENCE_UNCERTAIN |"), none);
        assertTrue(none.contains("| delivery_run_id " + new UUID(1, 1) + " |"), none);
        assertTrue(none.contains("| payload_sha256 " + request.payload().sha256()), none);
        assertTrue(none.contains("| player file sha256 " + "f".repeat(64)), none);
        assertTrue(none.endsWith("\nSTONE x32: storage and ender chest 40 | whole file 48 | no counts were recorded"), none);
        var recorded = new ItemDeliveryEvidence(request.playerId(), request.payload().sha256(), "1".repeat(64), "2".repeat(64), 8, 40, 0, true);
        assertTrue(CommerceRecoveryTool.deliveryReport(NONCE + " FUNDED", "STONE", held, read, recorded, "f".repeat(64))
                .endsWith(" | recorded before 8 | recorded after 40 | not placed 0"));
        assertEquals("DataVersion: file 4790 | recorded with the items [4790]", CommerceRecoveryTool.versionLine(read));
    }

    /** Read-only: the inspect path never calls a repository method that writes, and the tool has no decision command. */
    @Test void inspectDeliveryWritesNothing() throws Exception {
        String tool = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/commerce/CommerceRecoveryTool.java"))
                .replace("\r\n", "\n");
        int start = tool.indexOf("private static void inspectDelivery(");
        String inspect = tool.substring(start, tool.indexOf("static String deliveryReport(", start));
        assertTrue(inspect.contains("readSavedDelivery("), "this is the inspect path");
        for (String write : List.of("reconcile", "cancel", "recordDelivery", "markAmbiguous", "beginDelivery", "reserve(",
                "holdUnreadable", "expireReservations", "Files.write")) assertFalse(inspect.contains(write), write);
        assertTrue(inspect.contains("custody.find(payer,nonce,Instant.EPOCH)"), "read at an instant before any expiry");
        assertTrue(tool.contains("if (args[0].equals(\"--inspect-delivery\")) { inspectDelivery(config,evidence,root,password,reserve); return; }"));
        assertFalse(tool.contains("--resolve-delivery"), "no in-tool decision for a held order");
    }
}
