package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.ItemCustodyRepository.Decision;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository.OperatorResolution;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository.Receipt;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository.State;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** A custody row names a delivery run exactly when a delivery could have started. */
class ItemCustodyReceiptTest {
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final ItemCustody REQUEST = new ItemCustody(new UUID(3, 1), new UUID(3, 2), new UUID(3, 3),
            ItemCustody.Source.WORKER_OUTPUT, 4, new CustodyBytes(new byte[] {4, 5}), NOW, NOW.plusSeconds(600));
    private static final UUID RUN = new UUID(3, 4);

    @ParameterizedTest
    @CsvSource({"RESERVED, false", "DELIVERY_INTENT, true", "DELIVERED, true", "AMBIGUOUS, true", "CANCELLED, false",
            "EXPIRED, false", "RECONCILED_NOT_DELIVERED, true"})
    void aRunIsBoundExactlyWhenADeliveryStarted(State state, boolean started) {
        UUID run = started ? RUN : null;
        UUID wrong = started ? null : RUN;
        assertEquals(run, new Receipt(REQUEST, state, run, "outcome").deliveryRunId());
        assertEquals("Invalid custody run binding", assertThrows(IllegalArgumentException.class,
                () -> new Receipt(REQUEST, state, wrong, null)).getMessage());
    }

    @Test void thePersistedNamesAreFixed() {
        assertEquals(java.util.List.of("RESERVED", "DELIVERY_INTENT", "DELIVERED", "AMBIGUOUS", "CANCELLED", "EXPIRED",
                "RECONCILED_NOT_DELIVERED"), java.util.Arrays.stream(State.values()).map(Enum::name).toList());
        assertEquals(java.util.List.of("DELIVERY_UNCERTAIN", "INTERRUPTED_RUN", "CANCELLED_AFTER_INTENT"),
                java.util.Arrays.stream(ItemCustodyRepository.Uncertainty.values()).map(Enum::name).toList());
        assertEquals(java.util.List.of("VERIFIED_DELIVERED", "VERIFIED_NOT_DELIVERED", "RETURNED_BY_STAFF"),
                java.util.Arrays.stream(Decision.values()).map(Enum::name).toList());
        assertEquals("PAYLOAD_UNREADABLE", ItemCustodyRepository.PAYLOAD_UNREADABLE);
    }

    @Test void theOutcomeIsFreeTextAndMayBeAbsent() {
        assertNull(new Receipt(REQUEST, State.RESERVED, null, null).outcome());
        assertEquals("RESERVATION_EXPIRED", new Receipt(REQUEST, State.EXPIRED, null, "RESERVATION_EXPIRED").outcome());
    }

    @Test void aReceiptNeedsARequestAndAState() {
        assertThrows(NullPointerException.class, () -> new Receipt(null, State.RESERVED, null, null));
        assertThrows(NullPointerException.class, () -> new Receipt(REQUEST, null, null, null));
    }

    @Test void thePlayerBudgetsAreFixed() {
        assertEquals(8, ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER);
        assertEquals(512, ItemCustodyRepository.MAX_HELD_QUANTITY_PER_PLAYER);
        assertEquals(100, ItemCustodyRepository.MAX_SWEEP);
        assertEquals(4, ItemCustodyRepository.MAX_RETURN_STACKS);
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void anOperatorResolutionKeepsItsDecisionAndEvidence(Decision decision) {
        var evidence = new CustodyBytes(new byte[] {1});
        var resolution = new OperatorResolution(new UUID(4, 1), "staff.ops@host:1", decision, evidence);
        assertEquals(decision, resolution.decision());
        assertSame(evidence, resolution.evidence());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "two words", "tab\there", "slash/x", "café", "r\n"})
    void operatorReferencesOutsideTheGrammarAreRefused(String reference) {
        assertEquals("Invalid operator reference", assertThrows(IllegalArgumentException.class,
                () -> new OperatorResolution(new UUID(4, 1), reference, Decision.VERIFIED_DELIVERED,
                        new CustodyBytes(new byte[] {1}))).getMessage());
    }

    @Test void operatorReferencesAreOneToNinetySixCharacters() {
        var evidence = new CustodyBytes(new byte[] {1});
        assertEquals("r".repeat(96), new OperatorResolution(new UUID(4, 1), "r".repeat(96), Decision.VERIFIED_DELIVERED,
                evidence).operatorRef());
        assertThrows(IllegalArgumentException.class,
                () -> new OperatorResolution(new UUID(4, 1), "r".repeat(97), Decision.VERIFIED_DELIVERED, evidence));
        assertThrows(IllegalArgumentException.class,
                () -> new OperatorResolution(new UUID(4, 1), null, Decision.VERIFIED_DELIVERED, evidence));
        assertThrows(NullPointerException.class,
                () -> new OperatorResolution(null, "ops", Decision.VERIFIED_DELIVERED, evidence));
        assertThrows(NullPointerException.class, () -> new OperatorResolution(new UUID(4, 1), "ops", null, evidence));
        assertThrows(NullPointerException.class,
                () -> new OperatorResolution(new UUID(4, 1), "ops", Decision.VERIFIED_DELIVERED, null));
    }
}
