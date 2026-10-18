package com.prosperitysmp.npc.commerce;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** The offline tool's argument grammar, refused before any environment check, and its fixed report lines. */
class CommerceRecoveryToolUsageTest {
    private static final String USAGE = "Expected --reconcile-payment|--cancel-unstarted|--inspect-delivery"
            + " <private profile.properties> <private evidence.properties> [--apply], or --reconcile-payment"
            + " <private profile.properties> <private evidence.properties> --apply --distinct-refund,"
            + " or --classify <private profile.properties>";
    private static final UUID NONCE = new UUID(21, 1);
    private static final UUID PLAYER = new UUID(21, 2);

    static Stream<Arguments> refusedArguments() {
        return Stream.of(
                Arguments.of("no arguments", new String[0]),
                Arguments.of("classify without a profile", new String[]{"--classify"}),
                Arguments.of("classify with an evidence file", new String[]{"--classify", "p", "e"}),
                Arguments.of("classify with apply", new String[]{"--classify", "p", "--apply"}),
                Arguments.of("classify in the wrong case", new String[]{"--Classify", "p"}),
                Arguments.of("classify after the profile", new String[]{"p", "--classify"}),
                Arguments.of("reconcile without evidence", new String[]{"--reconcile-payment", "p"}),
                Arguments.of("reconcile with an unknown flag", new String[]{"--reconcile-payment", "p", "e", "--dry-run"}),
                Arguments.of("distinct refund without apply", new String[]{"--reconcile-payment", "p", "e", "--distinct-refund"}),
                Arguments.of("distinct refund before apply",
                        new String[]{"--reconcile-payment", "p", "e", "--distinct-refund", "--apply"}),
                Arguments.of("a sixth argument",
                        new String[]{"--reconcile-payment", "p", "e", "--apply", "--distinct-refund", "--apply"}),
                Arguments.of("cleanup with apply", new String[]{"--cancel-unstarted", "p", "e", "--apply"}),
                Arguments.of("cleanup with distinct refund",
                        new String[]{"--cancel-unstarted", "p", "e", "--apply", "--distinct-refund"}),
                Arguments.of("inspect with apply", new String[]{"--inspect-delivery", "p", "e", "--apply"}),
                Arguments.of("inspect without evidence", new String[]{"--inspect-delivery", "p"}),
                Arguments.of("cleanup without evidence", new String[]{"--cancel-unstarted", "p"}),
                Arguments.of("apply as the command", new String[]{"--apply", "p", "e"}),
                Arguments.of("an unknown command", new String[]{"--refund", "p", "e"}),
                Arguments.of("a command with no dashes", new String[]{"classify", "p"}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedArguments")
    void argumentsOutsideTheGrammarAreRefusedWithTheUsageLine(String name, String[] args) {
        var refused = assertThrows(IllegalArgumentException.class, () -> CommerceRecoveryTool.main(args), name);
        assertEquals(USAGE, refused.getMessage());
    }

    @Test void theUsageLineNamesNoPathOrSecret() {
        String usage = assertThrows(IllegalArgumentException.class,
                () -> CommerceRecoveryTool.main(new String[]{"--refund", "/data/private/profile.properties", "e"})).getMessage();
        assertFalse(usage.contains("/"), usage);
        assertFalse(usage.contains("password"), usage);
        assertFalse(usage.contains("127.0.0.1"), usage);
    }

    @Test void aFileWithoutADataVersionAndItemsWithoutOneSayNoneAndNotCompared() {
        var read = new ItemSaveProof.SavedUnits(List.of(0), null, List.of(), List.of(0), null, null, null);
        assertEquals("DataVersion: file none | recorded with the items none, so not compared",
                CommerceRecoveryTool.versionLine(read));
    }

    @Test void everyRecordedDataVersionIsListedInOrder() {
        var read = new ItemSaveProof.SavedUnits(List.of(3), 4790, List.of(4789, 4790), List.of(3), null, null, null);
        assertEquals("DataVersion: file 4790 | recorded with the items [4789, 4790]", CommerceRecoveryTool.versionLine(read));
    }

    @Test void aFileWithoutADataVersionStillListsWhatTheItemsRecorded() {
        var read = new ItemSaveProof.SavedUnits(List.of(3), null, List.of(4790), List.of(3), null, null, null);
        assertEquals("DataVersion: file none | recorded with the items [4790]", CommerceRecoveryTool.versionLine(read));
    }

    @Test void aReservedDeliveryWithNoOutcomeOrRunReadsExactly() {
        var request = request(ItemCustody.Source.WORKER_OUTPUT, 32);
        var reserved = new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.RESERVED, null, null);
        var read = new ItemSaveProof.SavedUnits(List.of(7), 4790, List.of(4790), List.of(9), null, null, null);
        String hash = "0".repeat(64);
        assertEquals("Order delivery " + NONCE + " | order " + NONCE + " RESERVED | custody RESERVED | delivery_run_id null"
                + " | payload_sha256 4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a"
                + " | player file sha256 " + hash
                + "\nOAK_LOG x32: storage and ender chest 7 | whole file 9 | no counts were recorded",
                CommerceRecoveryTool.deliveryReport(NONCE + " RESERVED", "OAK_LOG", reserved, read, null, hash));
    }

    @Test void aDeliveredOrderShowsItsOutcomeAndEveryRecordedCount() {
        var request = request(ItemCustody.Source.MERCHANT_GOODS, 8);
        UUID run = new UUID(21, 3);
        var delivered = new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.DELIVERED, run, "MARKER_CONFIRMED");
        var read = new ItemSaveProof.SavedUnits(List.of(12), 4790, List.of(), List.of(20), null, null, null);
        var recorded = new ItemDeliveryEvidence(PLAYER, request.payload().sha256(), "1".repeat(64), "2".repeat(64), 4, 12, 0, true);
        String report = CommerceRecoveryTool.deliveryReport("o DELIVERED", "STONE", delivered, read, recorded, "a".repeat(64));
        assertEquals("Order delivery " + NONCE + " | order o DELIVERED | custody DELIVERED MARKER_CONFIRMED | delivery_run_id "
                + run + " | payload_sha256 " + request.payload().sha256() + " | player file sha256 " + "a".repeat(64)
                + "\nSTONE x8: storage and ender chest 12 | whole file 20 | recorded before 4 | recorded after 12 | not placed 0",
                report);
    }

    @Test void onlyTheFirstLineOfAReadIsReported() {
        var request = request(ItemCustody.Source.MERCHANT_GOODS, 1);
        var held = new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.AMBIGUOUS, new UUID(21, 4), "DELIVERY_UNCERTAIN");
        var read = new ItemSaveProof.SavedUnits(List.of(5, 99), 4790, List.of(), List.of(6, 98), null, null, null);
        var recorded = new ItemDeliveryEvidence(PLAYER, request.payload().sha256(), "1".repeat(64), "2".repeat(64), 5, 5, 1, false);
        String report = CommerceRecoveryTool.deliveryReport("o AMBIGUOUS", "COAL", held, read, recorded, "b".repeat(64));
        assertTrue(report.endsWith("\nCOAL x1: storage and ender chest 5 | whole file 6 | recorded before 5 | recorded after 5"
                + " | not placed 1"), report);
        assertTrue(report.contains(" | custody AMBIGUOUS DELIVERY_UNCERTAIN | "), report);
        assertFalse(report.contains("99"), report);
        assertFalse(report.contains("98"), report);
    }

    private static ItemCustody request(ItemCustody.Source source, int quantity) {
        Instant at = Instant.parse("2026-10-18T09:00:00Z");
        return new ItemCustody(NONCE, PLAYER, new UUID(21, 9), source, quantity, new CustodyBytes(new byte[]{1}), at,
                at.plusSeconds(900));
    }
}
