package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Budgets;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Handle;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentDecision;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentResolution;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Receipt;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.State;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** The order journal's records refuse every state a paid order can never be in. */
class CommerceOrderRepositoryRecordsTest {
    private static final UUID NONCE = new UUID(7, 1), PLAYER = new UUID(7, 2), RESERVE = new UUID(7, 3);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Instant ISSUED = Instant.parse("2026-10-18T10:00:00Z");
    private static final String HASH = "c".repeat(64);

    private static CommerceOrder order(ItemCustody.Source source, int delay) {
        return new CommerceOrder(CommerceOrder.idFor(NONCE, PLAYER, source, DAY), NONCE, PLAYER, new UUID(7, 4), source,
                "work-stone", "STONE", 32, 1_815, DAY, ISSUED, delay);
    }

    private static final CommerceOrder WORK = order(ItemCustody.Source.WORKER_OUTPUT, 300);
    private static final Handle HANDLE = new Handle(WORK.orderId(), new UUID(7, 5), PLAYER, RESERVE, HASH);

    private static Receipt receipt(State state, boolean withHandle, boolean credit) {
        boolean funded = state == State.FUNDED;
        return new Receipt(WORK, state, withHandle ? HANDLE : null, credit, funded ? ISSUED : null,
                funded ? ISSUED.plusSeconds(300) : null, null, null);
    }

    @ParameterizedTest(name = "{0}: without credit {1}, with credit {2}")
    @CsvSource({"RESERVED, true, false", "DEBIT_INTENT, true, false", "CREDIT_READY, true, false",
            "CREDIT_INTENT, false, true", "FUNDED, false, true", "DECLINED, true, false", "AMBIGUOUS, true, true",
            "CANCELLED, true, false", "COMPENSATED, true, true"})
    void eachStateFixesWhetherTheCreditStarted(State state, boolean validWithoutCredit, boolean validWithCredit) {
        boolean handled = state != State.RESERVED && state != State.CANCELLED;
        for (boolean credit : new boolean[] {false, true}) {
            boolean valid = credit ? validWithCredit : validWithoutCredit;
            if (valid) assertEquals(state, receipt(state, handled, credit).state(), state + " credit " + credit);
            else assertEquals("Invalid commerce receipt", assertThrows(IllegalArgumentException.class,
                    () -> receipt(state, handled, credit), state + " credit " + credit).getMessage());
            boolean flippedHandle = !handled;
            assertThrows(IllegalArgumentException.class, () -> receipt(state, flippedHandle, credit),
                    state + " with the handle " + (flippedHandle ? "present" : "missing"));
        }
    }

    @Test void theHandleMustBelongToThisOrderAndPayer() {
        var otherOrder = new Handle(new UUID(8, 8), new UUID(7, 5), PLAYER, RESERVE, HASH);
        var otherPayer = new Handle(WORK.orderId(), new UUID(7, 5), new UUID(8, 9), RESERVE, HASH);
        assertThrows(IllegalArgumentException.class, () -> new Receipt(WORK, State.DEBIT_INTENT, otherOrder, false, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Receipt(WORK, State.DEBIT_INTENT, otherPayer, false, null, null, null, null));
        assertEquals(HANDLE, new Receipt(WORK, State.DEBIT_INTENT, HANDLE, false, null, null, null, null).handle());
    }

    @Test void aFundedReceiptHasItsFundingTimeAndTheOutputDelayAfterIt() {
        Instant funded = ISSUED.plusSeconds(42);
        var ok = new Receipt(WORK, State.FUNDED, HANDLE, true, funded, funded.plusSeconds(300), WORK.orderId(), "FUNDED");
        assertEquals(funded.plusSeconds(300), ok.outputReadyAt());
        assertThrows(IllegalArgumentException.class,
                () -> new Receipt(WORK, State.FUNDED, HANDLE, true, null, funded.plusSeconds(300), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Receipt(WORK, State.FUNDED, HANDLE, true, funded, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Receipt(WORK, State.FUNDED, HANDLE, true, funded, funded.plusSeconds(299), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Receipt(WORK, State.FUNDED, HANDLE, true, funded, funded.plusSeconds(301), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Receipt(WORK, State.FUNDED, HANDLE, true,
                ISSUED.minusMillis(1), ISSUED.minusMillis(1).plusSeconds(300), null, null), "funded before it was issued");
        assertEquals(ISSUED, new Receipt(WORK, State.FUNDED, HANDLE, true, ISSUED, ISSUED.plusSeconds(300), null, null).fundedAt());
    }

    @Test void merchantGoodsAreReadyTheMomentTheyAreFunded() {
        var goods = order(ItemCustody.Source.MERCHANT_GOODS, 0);
        var handle = new Handle(goods.orderId(), new UUID(7, 5), PLAYER, RESERVE, HASH);
        Instant funded = ISSUED.plusSeconds(5);
        assertEquals(funded, new Receipt(goods, State.FUNDED, handle, true, funded, funded, null, null).outputReadyAt());
        assertThrows(IllegalArgumentException.class,
                () -> new Receipt(goods, State.FUNDED, handle, true, funded, funded.plusSeconds(1), null, null));
    }

    @Test void onlyAFundedReceiptCarriesTimesOrACustodyNonceAndTheNonceIsTheOrderId() {
        assertThrows(IllegalArgumentException.class, () -> new Receipt(WORK, State.FUNDED, HANDLE, true, ISSUED,
                ISSUED.plusSeconds(300), new UUID(9, 9), null), "another custody nonce");
        for (State state : new State[] {State.DEBIT_INTENT, State.AMBIGUOUS, State.COMPENSATED, State.DECLINED}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Receipt(WORK, state, HANDLE, false, ISSUED, null, null, null), state + " with a funding time");
            assertThrows(IllegalArgumentException.class,
                    () -> new Receipt(WORK, state, HANDLE, false, null, ISSUED, null, null), state + " with an output time");
            assertThrows(IllegalArgumentException.class,
                    () -> new Receipt(WORK, state, HANDLE, false, null, null, WORK.orderId(), null), state + " with custody");
        }
    }

    @Test void aReceiptNeedsAnOrderAndAState() {
        assertThrows(NullPointerException.class, () -> new Receipt(null, State.RESERVED, null, false, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new Receipt(WORK, null, null, false, null, null, null, null));
    }

    @Test void aHandleNeverPaysTheReserveFromItself() {
        assertEquals("Commerce payer equals reserve", assertThrows(IllegalArgumentException.class,
                () -> new Handle(WORK.orderId(), new UUID(7, 5), RESERVE, RESERVE, HASH)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Handle(WORK.orderId(), new UUID(7, 5), PLAYER, RESERVE, "C".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new Handle(WORK.orderId(), new UUID(7, 5), PLAYER, RESERVE, null));
        assertThrows(NullPointerException.class, () -> new Handle(null, new UUID(7, 5), PLAYER, RESERVE, HASH));
        assertThrows(NullPointerException.class, () -> new Handle(WORK.orderId(), null, PLAYER, RESERVE, HASH));
        assertThrows(NullPointerException.class, () -> new Handle(WORK.orderId(), new UUID(7, 5), null, RESERVE, HASH));
        assertThrows(NullPointerException.class, () -> new Handle(WORK.orderId(), new UUID(7, 5), PLAYER, null, HASH));
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 1, 1, true", "4096, 262144, 262144, 409600000000, true", "0, 1, 1, 1, false",
            "4097, 1, 1, 1, false", "1, 0, 1, 1, false", "1, 262145, 1, 1, false", "1, 1, 0, 1, false",
            "1, 1, 262145, 1, false", "1, 1, 1, 0, false", "1, 1, 1, 409600000001, false"})
    void budgetsHaveExplicitLimits(int orders, int units, int materialUnits, long cents, boolean accepted) {
        if (accepted) assertEquals(cents, new Budgets(orders, units, materialUnits, cents).globalCostCentsPerDay());
        else assertEquals("Commerce budget exceeds bounds",
                assertThrows(IllegalArgumentException.class, () -> new Budgets(orders, units, materialUnits, cents)).getMessage());
    }

    private static PaymentResolution resolution(String operator, String caseRef, String notes, PaymentDecision decision,
                                                Long before, Long after, String refundHash, Instant at) {
        return new PaymentResolution(new UUID(6, 1), operator, caseRef, notes, HASH, decision,
                new CustodyBytes(new byte[] {9}), before, after, refundHash, at);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentDecision.class, mode = EnumSource.Mode.EXCLUDE, names = "VERIFIED_REFUNDED_DEBIT")
    void aDecisionWithoutARefundCarriesNoRefundEvidence(PaymentDecision decision) {
        assertEquals(decision, resolution("ops", "case-1", "checked", decision, null, null, null, ISSUED).decision());
        assertThrows(IllegalArgumentException.class, () -> resolution("ops", "case-1", "checked", decision, 1L, null, null, ISSUED));
        assertThrows(IllegalArgumentException.class, () -> resolution("ops", "case-1", "checked", decision, null, 1L, null, ISSUED));
        assertThrows(IllegalArgumentException.class, () -> resolution("ops", "case-1", "checked", decision, null, null, HASH, ISSUED));
    }

    @Test void aRefundDecisionNeedsAllThreePiecesOfRefundEvidence() {
        var refund = resolution("ops", "case-1", "checked", PaymentDecision.VERIFIED_REFUNDED_DEBIT,
                Double.doubleToRawLongBits(5.0), Double.doubleToRawLongBits(23.15), "d".repeat(64), ISSUED);
        assertEquals("d".repeat(64), refund.refundEvidenceSha256());
        assertEquals("Refund evidence does not match decision", assertThrows(IllegalArgumentException.class,
                () -> resolution("ops", "case-1", "checked", PaymentDecision.VERIFIED_REFUNDED_DEBIT, 1L, 2L, null, ISSUED)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> resolution("ops", "case-1", "checked", PaymentDecision.VERIFIED_REFUNDED_DEBIT, null, 2L, HASH, ISSUED));
        assertThrows(IllegalArgumentException.class,
                () -> resolution("ops", "case-1", "checked", PaymentDecision.VERIFIED_REFUNDED_DEBIT, 1L, null, HASH, ISSUED));
        assertEquals("Invalid SHA-256 evidence", assertThrows(IllegalArgumentException.class,
                () -> resolution("ops", "case-1", "checked", PaymentDecision.VERIFIED_REFUNDED_DEBIT, 1L, 2L, "D".repeat(64), ISSUED))
                .getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "ops@example.test", "A.b:c_d-e@1", "case:2026-10-18"})
    void referencesInTheGrammarAreAccepted(String reference) {
        var accepted = resolution(reference, reference, "n", PaymentDecision.VERIFIED_PAID, null, null, null, ISSUED);
        assertEquals(reference, accepted.operatorRef());
        assertEquals(reference, accepted.caseRef());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "two words", "new\nline", "slash/ref", "semi;colon", "café", "quote\"",
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456"})
    void referencesOutsideTheGrammarAreRefused(String reference) {
        assertEquals("Invalid recovery reference", assertThrows(IllegalArgumentException.class,
                () -> resolution(reference, "case", "n", PaymentDecision.VERIFIED_PAID, null, null, null, ISSUED)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> resolution("ops", reference, "n", PaymentDecision.VERIFIED_PAID, null, null, null, ISSUED));
    }

    @Test void ninetySixCharacterReferencesAreTheLongest() {
        String longest = "r".repeat(96);
        assertEquals(longest, resolution(longest, "c", "n", PaymentDecision.VERIFIED_PAID, null, null, null, ISSUED).operatorRef());
        assertThrows(IllegalArgumentException.class,
                () -> resolution(null, "c", "n", PaymentDecision.VERIFIED_PAID, null, null, null, ISSUED));
    }

    @Test void notesAreNonBlankAndAtMostTwoThousandFortyEightCharacters() {
        assertEquals(2_048, resolution("o", "c", "n".repeat(2_048), PaymentDecision.VERIFIED_PAID, null, null, null, ISSUED)
                .notes().length());
        for (String notes : new String[] {null, "", "   ", "\n\t", "n".repeat(2_049)})
            assertEquals("Recovery notes exceed bounds", assertThrows(IllegalArgumentException.class,
                    () -> resolution("o", "c", notes, PaymentDecision.VERIFIED_PAID, null, null, null, ISSUED)).getMessage());
        assertEquals("line one\nline two", resolution("o", "c", "line one\nline two", PaymentDecision.VERIFIED_PAID,
                null, null, null, ISSUED).notes(), "notes may span lines");
    }

    @Test void theResolutionTimeIsTruncatedAndNeverBeforeTheEpoch() {
        assertEquals(Instant.parse("2026-10-18T10:00:00.123Z"), resolution("o", "c", "n", PaymentDecision.VERIFIED_PAID,
                null, null, null, Instant.parse("2026-10-18T10:00:00.123987Z")).resolvedAt());
        assertEquals("Invalid commerce time", assertThrows(IllegalArgumentException.class, () -> resolution("o", "c", "n",
                PaymentDecision.VERIFIED_PAID, null, null, null, Instant.EPOCH.minusMillis(1))).getMessage());
        assertThrows(NullPointerException.class,
                () -> resolution("o", "c", "n", PaymentDecision.VERIFIED_PAID, null, null, null, null));
    }

    @Test void theRequestHashAndCaseEvidenceAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentResolution(new UUID(6, 1), "o", "c", "n", "x",
                PaymentDecision.VERIFIED_PAID, new CustodyBytes(new byte[] {9}), null, null, null, ISSUED));
        assertThrows(NullPointerException.class, () -> new PaymentResolution(new UUID(6, 1), "o", "c", "n", HASH,
                PaymentDecision.VERIFIED_PAID, null, null, null, null, ISSUED));
        assertThrows(NullPointerException.class, () -> new PaymentResolution(null, "o", "c", "n", HASH,
                PaymentDecision.VERIFIED_PAID, new CustodyBytes(new byte[] {9}), null, null, null, ISSUED));
        assertThrows(NullPointerException.class, () -> new PaymentResolution(new UUID(6, 1), "o", "c", "n", HASH,
                null, new CustodyBytes(new byte[] {9}), null, null, null, ISSUED));
    }
}
