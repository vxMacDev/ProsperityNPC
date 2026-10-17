package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.LegOutcome;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import com.prosperitysmp.npc.economy.TransferProof.TransferOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferProofTest {

    private static final long REQUEST = 500;
    private static final long[] AMOUNTS = {0, 300, 499, 500, 501};
    private static final Double[] INVALID_DOLLARS = {
            null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, -Double.MIN_VALUE, Double.MAX_VALUE, 1.0e18,
            Long.MAX_VALUE / 100.0, 8_388_608.0, 0.001, 1.005, 100.00000001
    };
    /** Balances that can never be evidence: missing, not finite, negative, or at or above the ceiling. */
    private static final Double[] INVALID_BALANCES = {
            null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, -Double.MIN_VALUE,
            1.0e9, Math.nextUp(1.0e9), 1.0e18, Double.MAX_VALUE
    };

    @ParameterizedTest
    @CsvSource({"0, 0", "0.01, 1", "0.29, 29", "0.30, 30", "5.01, 501",
            "1000000.01, 100000001", "8388607.99, 838860799"})
    void convertsWholeCentsExactly(double dollars, long cents) {
        assertEquals(OptionalLong.of(cents), TransferProof.toCents(dollars));
    }

    @Test
    void conversionRejectsMissingInvalidFractionalAndOverflowingValues() {
        for (Double invalid : INVALID_DOLLARS) {
            assertTrue(TransferProof.toCents(invalid).isEmpty(), "value=" + invalid);
        }
    }

    @Test
    void conversionAcceptsOnlyDocumentedAbsoluteNoise() {
        assertEquals(0.000000001, TransferProof.CENT_TOLERANCE_DOLLARS);
        for (double value : new double[]{1.0, 0.999999999, 1.000000001,
                Math.nextDown(1.0), Math.nextUp(1.0)}) {
            assertEquals(OptionalLong.of(100), TransferProof.toCents(value), "value=" + value);
        }
        for (double value : new double[]{0.999999998, 1.000000002, 0.999, 1.001}) {
            assertTrue(TransferProof.toCents(value).isEmpty(), "value=" + value);
        }
        assertEquals(OptionalLong.of(99), TransferProof.toCents(0.99));
        assertEquals(OptionalLong.of(101), TransferProof.toCents(1.01));
        assertEquals(OptionalLong.of(0), TransferProof.toCents(-0.0));
        assertEquals(OptionalLong.of(0), TransferProof.toCents(Double.MIN_VALUE));
        assertTrue(TransferProof.toCents(-Double.MIN_VALUE).isEmpty());
    }

    @Test
    void conversionDoesNotScaleToleranceWithBalance() {
        assertEquals(OptionalLong.of(100_000_001), TransferProof.toCents(Math.nextDown(1_000_000.01)));
        assertEquals(OptionalLong.of(100_000_001), TransferProof.toCents(Math.nextUp(1_000_000.01)));
        assertTrue(TransferProof.toCents(1_000_000.01000001).isEmpty());
        assertTrue(TransferProof.toCents(1_000_000.011).isEmpty());
        assertTrue(TransferProof.toCents(1.0e16).isEmpty(), "coarse whole dollars are not cent proof");
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void successRequiresFullReportedAndObservedAmounts(Leg leg) {
        for (long reported : AMOUNTS) {
            for (long moved : AMOUNTS) {
                LegOutcome expected = reported == REQUEST && moved == REQUEST
                        ? LegOutcome.CONFIRMED : LegOutcome.AMBIGUOUS;
                assertEquals(expected, TransferProof.evaluate(REQUEST, leg,
                        observation(leg, reported, moved, true)), "reported=" + reported + ", moved=" + moved);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void explicitFailureRequiresZeroObservedMovement(Leg leg) {
        for (long reported : AMOUNTS) {
            for (long moved : AMOUNTS) {
                LegOutcome expected = moved == 0 ? LegOutcome.NO_MOVEMENT : LegOutcome.AMBIGUOUS;
                assertEquals(expected, TransferProof.evaluate(REQUEST, leg,
                        observation(leg, reported, moved, false)), "reported=" + reported + ", moved=" + moved);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void wrongSignedDeltaIsNeverConfirmedOrNoMovement(Leg leg) {
        Leg opposite = leg == Leg.DEBIT ? Leg.CREDIT : Leg.DEBIT;
        for (boolean success : new boolean[]{false, true}) {
            assertEquals(LegOutcome.AMBIGUOUS,
                    TransferProof.evaluate(REQUEST, leg, observation(opposite, REQUEST, REQUEST, success)));
        }
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void missingResponseAndThrowsStayAmbiguousEvenAtZeroDelta(Leg leg) {
        for (long moved : AMOUNTS) {
            for (boolean success : new boolean[]{false, true}) {
                Observation original = observation(leg, moved, moved, success);
                assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                        new Observation(false, success, original.reportedDollars(), original.beforeDollars(),
                                original.afterDollars(), false)));
                assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                        new Observation(true, success, original.reportedDollars(), original.beforeDollars(),
                                original.afterDollars(), true)));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void invalidEvidenceInAnyFieldAlwaysFailsAmbiguous(Leg leg) {
        for (Double invalid : INVALID_DOLLARS) {
            for (boolean success : new boolean[]{false, true}) {
                Observation original = observation(leg, success ? REQUEST : 0, success ? REQUEST : 0, success);
                assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                        new Observation(true, success, invalid, original.beforeDollars(), original.afterDollars(), false)),
                        "reported=" + invalid);
            }
        }
        for (Double invalid : INVALID_BALANCES) {
            for (boolean success : new boolean[]{false, true}) {
                Observation original = observation(leg, success ? REQUEST : 0, success ? REQUEST : 0, success);
                assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                        new Observation(true, success, original.reportedDollars(), invalid, original.afterDollars(), false)),
                        "before=" + invalid);
                assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                        new Observation(true, success, original.reportedDollars(), original.beforeDollars(), invalid, false)),
                        "after=" + invalid);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void fractionalBalancesProveExactDelta(Leg leg) {
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, 100.001, leg == Leg.DEBIT ? 95.001 : 105.001, false)));
        // Half a millionth of a dollar short: far above the precision of two values near $100.
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, 100.001, leg == Leg.DEBIT ? 95.0010005 : 105.0009995, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, 100.001, leg == Leg.DEBIT ? 95.002 : 105.000, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, false, 0.0, 100.001, leg == Leg.DEBIT ? 100.0009995 : 100.0010005, false)));
        assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, false, 0.0, 100.001, 100.001, false)));
        // One gap of the larger value either way still counts; the request is never off by a whole cent.
        double before = 100.001, after = leg == Leg.DEBIT ? 95.001 : 105.001;
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, before, Math.nextUp(after), false)));
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, before, Math.nextDown(after), false)));
        // Both values sit in [64, 128), so each gap is 2^-46 and the allowance is exactly two gaps. Two gaps off is
        // still the request; three gaps off is not.
        assertEquals(Math.ulp(before), Math.ulp(after));
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, before, Math.nextUp(Math.nextUp(after)), false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, before, Math.nextUp(Math.nextUp(Math.nextUp(after))), false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, before, Math.nextDown(Math.nextDown(Math.nextDown(after))), false)));
    }

    @Test
    void prodReserveShape() {
        double reserve = 57492.488404927248686;
        assertEquals(OptionalLong.of(5_749_248), TransferProof.availableCents(reserve));
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(1234, Leg.DEBIT,
                new Observation(true, true, 12.34, reserve, 57480.148404927248686, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(1234, Leg.DEBIT,
                new Observation(true, true, 12.34, reserve, 57480.158404927248686, false)));
        assertEquals(OptionalLong.of(5_749_248), TransferProof.availableCents(57492.488404927));
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(1234, Leg.DEBIT,
                new Observation(true, true, 12.34, 57492.488404927, 57480.148404927, false)));
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(12_345, Leg.CREDIT,
                new Observation(true, true, 123.45, reserve, 57615.938404927248686, false)));
    }

    @Test
    void largestVerifiableBalance() {
        double largest = Math.nextDown(1.0e9);
        assertEquals(new BigDecimal("999999999.99999988079071044921875"), TransferProof.exactBalance(largest).orElseThrow());
        assertTrue(TransferProof.exactBalance(1.0e9).isEmpty());
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(500, Leg.CREDIT,
                new Observation(true, true, 5.0, 999_999_994.99, 999_999_999.99, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(500, Leg.DEBIT,
                new Observation(true, true, 5.0, 1.0e9, 999_999_995.0, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(500, Leg.CREDIT,
                new Observation(true, true, 5.0, 999_999_995.0, 1.0e9, false)));
        // Today's whole-cent rule stops below 8,388,608; the exact rule carries on above it.
        assertTrue(TransferProof.toCents(8_388_608.0).isEmpty());
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(100, Leg.CREDIT,
                new Observation(true, true, 1.0, 8_388_607.0, 8_388_608.0, false)));
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(100, Leg.DEBIT,
                new Observation(true, true, 1.0, 8_388_609.0, 8_388_608.0, false)));
    }

    @Test
    void exactBalanceUsesBinaryValue() {
        assertEquals(new BigDecimal("0.1000000000000000055511151231257827021181583404541015625"),
                TransferProof.exactBalance(0.1).orElseThrow());
        assertEquals(0, BigDecimal.ZERO.compareTo(TransferProof.exactBalance(-0.0).orElseThrow()));
        assertTrue(TransferProof.exactBalance(-Double.MIN_VALUE).isEmpty());
        assertTrue(TransferProof.exactBalance(null).isEmpty());
    }

    @Test
    void availableCentsFixtures() {
        assertEquals(OptionalLong.of(29), TransferProof.availableCents(0.29));
        assertEquals(OptionalLong.of(28), TransferProof.availableCents(0.2899995));
        assertEquals(OptionalLong.of(5_749_248), TransferProof.availableCents(57492.488404927248686));
        assertEquals(OptionalLong.of(123_456), TransferProof.availableCents(1234.5678901));
        assertEquals(OptionalLong.of(0), TransferProof.availableCents(-0.0));
        assertEquals(OptionalLong.empty(), TransferProof.availableCents(-Double.MIN_VALUE));
        assertEquals(OptionalLong.of(99_999_999_999L), TransferProof.availableCents(Math.nextDown(1.0e9)));
        assertEquals(OptionalLong.empty(), TransferProof.availableCents(1.0e9));
        assertEquals(OptionalLong.empty(), TransferProof.availableCents(Double.NaN));
        assertEquals(OptionalLong.empty(), TransferProof.availableCents(null));
        // Just under a whole cent, beyond the documented noise, never rounds up.
        assertEquals(OptionalLong.of(0), TransferProof.availableCents(0.009999));
        assertEquals(OptionalLong.of(838_860_800L), TransferProof.availableCents(8_388_608.0 + Math.ulp(8_388_608.0)));
        // Exactly on a cent in binary, but the true decimal may be up to one gap lower: one cent less is certain.
        assertEquals(OptionalLong.of(838_860_799L), TransferProof.availableCents(8_388_608.0));
    }

    @Test
    void availabilityNeverRoundsUpIntoACentThatIsNotThere() {
        // Within the whole-cent rule's noise allowance, but below the cent by far more than the value's own
        // precision: that cent is not in the account, so it is not available.
        assertEquals(OptionalLong.of(10_029), TransferProof.toCents(100.2899999995), "the whole-cent rule still reads it as the cent");
        assertEquals(OptionalLong.of(10_028), TransferProof.availableCents(100.2899999995));
        // Spending it from an account that may borrow would end just below zero, which can never be proved.
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(10_029, Leg.DEBIT,
                new Observation(true, true, 100.29, 100.2899999995, 100.2899999995 - 100.29, false)));
        // Above the cent, or the cent itself to its own precision, is the whole cent as before.
        assertEquals(OptionalLong.of(10_029), TransferProof.availableCents(100.2900000005));
        assertEquals(OptionalLong.of(10_029), TransferProof.availableCents(100.29));
        assertEquals(OptionalLong.of(29), TransferProof.availableCents(0.29));
        assertEquals(OptionalLong.of(10_000), TransferProof.availableCents(100.0));
        assertEquals(OptionalLong.of(838_860_799L), TransferProof.availableCents(8_388_607.99));
    }

    @Test
    void canReceiveFixtures() {
        BigDecimal receiver = TransferProof.RECEIVER_LIMIT_DOLLARS;
        BigDecimal credit = TransferProof.MAX_BALANCE_DOLLARS.subtract(TransferProof.CREDIT_MARGIN_DOLLARS);
        assertEquals(new BigDecimal("900000000"), receiver);
        assertEquals(new BigDecimal("999999999.99"), credit);
        assertTrue(TransferProof.canReceive(899_999_995.0, 500, receiver));
        assertFalse(TransferProof.canReceive(899_999_995.01, 500, receiver));
        // The double for 999,999,994.99 sits about 9.5e-9 above the decimal, so it is just over the limit.
        assertTrue(TransferProof.canReceive(Math.nextDown(999_999_994.99), 500, credit));
        assertFalse(TransferProof.canReceive(999_999_994.99, 500, credit));
        assertTrue(TransferProof.canReceive(999_999_994.75, 500, credit));
        assertFalse(TransferProof.canReceive(999_999_995.0, 500, credit));
        assertFalse(TransferProof.canReceive(Math.nextUp(899_999_995.0), 500, receiver));
        assertFalse(TransferProof.canReceive(999_999_999.93, 7, receiver));
        assertFalse(TransferProof.canReceive(999_999_999.93, 7, credit));
        assertFalse(TransferProof.canReceive(100.0, 0, receiver));
        assertFalse(TransferProof.canReceive(-1.0, 1, receiver));
        assertFalse(TransferProof.canReceive(null, 1, receiver));
        assertTrue(TransferProof.canReceive(0.2899995, 1, receiver));
    }

    @Test
    void refusedLegIsNoMovementOnlyWhenNotCalled() {
        for (Double balance : new Double[]{null, Double.NaN, 100.001, 57492.488404927248686}) {
            for (Leg leg : Leg.values()) {
                Observation refused = Observation.refused(Refusal.BALANCE_UNVERIFIABLE, null, balance, balance);
                assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(REQUEST, leg, refused), "balance=" + balance);
                assertTrue(refused.threw(), "a refusal still reads as threw for older checks");
            }
        }
        Observation refusedDebit = Observation.refused(Refusal.LEASE_REFUSED, new IllegalStateException("x"), null, null);
        assertEquals("java.lang.IllegalStateException", refusedDebit.refusalCause());
        assertEquals(TransferOutcome.DECLINED, TransferProof.evaluateTransfer(REQUEST, refusedDebit, null));
        Observation confirmed = observation(Leg.DEBIT, REQUEST, REQUEST, true);
        Observation refusedCredit = Observation.refused(Refusal.LEASE_REFUSED, null, null, null);
        assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(REQUEST, confirmed, refusedCredit));
        assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(REQUEST, refusedDebit, refusedCredit));
        assertEquals("none", refusedCredit.refusalCause());
        // A refusal can never carry a response, a reported value, threw=false, or no cause.
        assertThrows(IllegalArgumentException.class, () -> new Observation(true, false, null, null, null, true,
                Refusal.PROVIDER_CHANGED, "none"));
        assertThrows(IllegalArgumentException.class, () -> new Observation(false, true, null, null, null, true,
                Refusal.PROVIDER_CHANGED, "none"));
        assertThrows(IllegalArgumentException.class, () -> new Observation(false, false, 5.0, null, null, true,
                Refusal.PROVIDER_CHANGED, "none"));
        assertThrows(IllegalArgumentException.class, () -> new Observation(false, false, null, null, null, false,
                Refusal.PROVIDER_CHANGED, "none"));
        assertThrows(IllegalArgumentException.class, () -> new Observation(false, false, null, null, null, true,
                Refusal.PROVIDER_CHANGED, null));
        assertThrows(IllegalArgumentException.class, () -> new Observation(false, false, null, null, null, true,
                Refusal.PROVIDER_CHANGED, "bad cause!"));
        assertThrows(IllegalArgumentException.class, () -> new Observation(false, false, null, null, null, true,
                null, "java.lang.Error"));
        assertThrows(NullPointerException.class, () -> Observation.refused(null, null, null, null));
        // Only reasons that look like a fault count as unexpected.
        for (Refusal reason : Refusal.values()) {
            boolean fault = reason == Refusal.WRONG_THREAD || reason == Refusal.INVALID_REQUEST
                    || reason == Refusal.LEASE_FAILED || reason == Refusal.PRECHECK_FAILED;
            assertEquals(fault, reason.unexpected(), reason.name());
        }
        assertTrue(TransferProof.expectedRefusal(new IllegalStateException()));
        assertTrue(TransferProof.expectedRefusal(new java.util.NoSuchElementException()));
        assertFalse(TransferProof.expectedRefusal(new IllegalArgumentException()));
        assertFalse(TransferProof.expectedRefusal(new NoSuchMethodError()));
    }

    @Test
    void causeNameKeepsOnlyTheClassName() {
        class AVeryLongNamedRefusalThatGoesOnAndOnAndOnPastSixtyFourCharactersException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }
        String name = TransferProof.causeName(new AVeryLongNamedRefusalThatGoesOnAndOnAndOnPastSixtyFourCharactersException());
        assertEquals(64, name.length());
        assertTrue(name.endsWith("PastSixtyFourCharactersException"));
        assertEquals("none", TransferProof.causeName(null));
        assertEquals("java.lang.IllegalStateException", TransferProof.causeName(new IllegalStateException("secret 123")));
    }

    @Test
    void refundRestoresDebit() {
        Observation fractional = new Observation(true, true, 12.34, 57492.488404927248686, 57480.148404927248686, false);
        assertTrue(TransferProof.refundRestoresDebit(1234, fractional, bits(57480.148404927248686), bits(57492.488404927248686)));
        Observation overdraft = new Observation(true, true, 150.0, 18.5, -131.5, false);
        assertTrue(TransferProof.refundRestoresDebit(15_000, overdraft, bits(-131.5), bits(18.5)));
        Observation whole = new Observation(true, true, 10.0, 100.0, 90.0, false);
        assertTrue(TransferProof.refundRestoresDebit(1_000, whole, bits(90.0), bits(100.0)));
        // Swapped bits, one cent off, a refused or uncertain debit: all rejected.
        assertFalse(TransferProof.refundRestoresDebit(1_000, whole, bits(100.0), bits(90.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_001, whole, bits(90.0), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, new Observation(true, true, 10.0, 100.0, 90.01, false),
                bits(90.01), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1234, new Observation(true, true, 12.34, 57492.488404927248686,
                57480.158404927248686, false), bits(57480.158404927248686), bits(57492.488404927248686)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, Observation.refused(Refusal.INSUFFICIENT_FUNDS, null, 100.0, 100.0),
                bits(100.0), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, new Observation(true, true, 10.0, 100.0, 90.0, true), bits(90.0), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, new Observation(true, false, 10.0, 100.0, 90.0, false), bits(90.0), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, new Observation(false, false, null, 100.0, 90.0, false), bits(90.0), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, new Observation(true, true, 10.01, 100.0, 90.0, false), bits(90.0), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, null, bits(90.0), bits(100.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, new Observation(true, true, 10.0, 1.0e9, 1.0e9 - 10, false),
                bits(1.0e9 - 10), bits(1.0e9)));
    }

    @Test
    void aRefundIsJudgedByItsOwnChangeBecauseThePayerKeepsTrading() {
        // Between the debit and the refund the payer's balance moved for other reasons. The refund still puts back
        // exactly what the debit took, so it restores the payment even though it starts somewhere else.
        Observation whole = new Observation(true, true, 10.0, 100.0, 90.0, false);
        assertTrue(TransferProof.refundRestoresDebit(1_000, whole, bits(75.0), bits(85.0)));
        assertTrue(TransferProof.refundRestoresDebit(1_000, whole, bits(0.0), bits(10.0)));
        assertTrue(TransferProof.refundRestoresDebit(1_000, whole, bits(-3.5), bits(6.5)), "an overdrawn payer");
        assertFalse(TransferProof.refundRestoresDebit(1_000, whole, bits(75.0), bits(84.99)), "one cent short");
        assertFalse(TransferProof.refundRestoresDebit(1_000, whole, bits(75.0), bits(85.01)), "one cent over");
        assertFalse(TransferProof.refundRestoresDebit(1_000, whole, bits(85.0), bits(75.0)), "taken, not given back");
        assertFalse(TransferProof.refundRestoresDebit(1_000, whole, bits(Double.NaN), bits(85.0)));
        assertFalse(TransferProof.refundRestoresDebit(1_000, whole, bits(1.0e9), bits(1.0e9 + 10)));
        // EssentialsX adds the exact decimal; Vault reports each side as the nearest double.
        Observation fractional = new Observation(true, true, 12.34, 57492.488404927248686, 57480.148404927248686, false);
        assertTrue(TransferProof.refundRestoresDebit(1234, fractional,
                bits(new BigDecimal("60000.123456789").doubleValue()), bits(new BigDecimal("60012.463456789").doubleValue())));
        assertFalse(TransferProof.refundRestoresDebit(1234, fractional,
                bits(new BigDecimal("60000.123456789").doubleValue()), bits(new BigDecimal("60012.463456799").doubleValue())),
                "a hundred-millionth of a dollar too much is seen");
    }

    @Test
    void legacyGateRefused() {
        Observation fractional = new Observation(false, false, null, 100.001, 100.001, true);
        assertTrue(TransferProof.legacyGateRefused(500, Leg.DEBIT, fractional));
        assertTrue(TransferProof.legacyGateRefused(500, Leg.CREDIT, fractional));
        Observation shortFunds = new Observation(false, false, null, 4.99, 4.99, true);
        assertTrue(TransferProof.legacyGateRefused(500, Leg.DEBIT, shortFunds));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.CREDIT, shortFunds), "the old credit gate had no funds check");
        assertFalse(TransferProof.legacyGateRefused(499, Leg.DEBIT, shortFunds), "the old gate would have passed");
        assertTrue(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, null, Double.NaN, Double.NaN, true)));
        // Rejected shapes.
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, null));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, null, 100.001, 100.002, true)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, null, null, null, true)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, null, 100.001, null, true)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(true, false, null, 100.001, 100.001, true)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, true, null, 100.001, 100.001, true)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, 0.0, 100.001, 100.001, true)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, null, 100.001, 100.001, false)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, Observation.refused(Refusal.BALANCE_UNVERIFIABLE, null, 100.001, 100.001)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, null, 100.0, 100.0, true)));
        assertFalse(TransferProof.legacyGateRefused(500, Leg.DEBIT, new Observation(false, false, null, 0.001, -0.001, true)));
    }

    @Test
    void negativeBalancesAreNeverEvidence() {
        // An overdraft debit, 18.50 to -131.50, stays uncertain even though it moved exactly the request.
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(15_000, Leg.DEBIT,
                new Observation(true, true, 150.0, 18.5, -131.5, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(1, Leg.DEBIT,
                new Observation(true, true, 0.01, 0.0000005, -0.0099995, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(1, Leg.CREDIT,
                new Observation(true, true, 0.01, -0.0099995, 0.0000005, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(1, Leg.CREDIT,
                new Observation(true, false, 0.0, -0.0000005, -0.0000005, false)));
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void millionDollarBinaryNoiseDoesNotHideAMissingCent(Leg leg) {
        for (long cents : new long[]{1, REQUEST}) {
            double before = Math.nextUp(1_000_000.01);
            double signedDollars = (leg == Leg.DEBIT ? -cents : cents) / 100.0;
            double after = Math.nextDown(1_000_000.01 + signedDollars);
            assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(cents, leg,
                    new Observation(true, true, Math.nextUp(cents / 100.0), before, after, false)));

            double shortDollars = (leg == Leg.DEBIT ? -(cents - 1) : cents - 1) / 100.0;
            double shortAfter = Math.nextDown(1_000_000.01 + shortDollars);
            assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(cents, leg,
                    new Observation(true, true, cents / 100.0, before, shortAfter, false)));
        }
        assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, false, 0.0, Math.nextDown(1_000_000.01),
                        Math.nextUp(1_000_000.01), false)));
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void oneCentAtZeroBalanceBoundaryMustReallyMove(Leg leg) {
        double before = leg == Leg.DEBIT ? 0.01 : 0.0;
        double after = leg == Leg.DEBIT ? 0.0 : 0.01;
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(1, leg,
                new Observation(true, true, 0.01, before, after, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(1, leg,
                new Observation(true, true, 0.01, before, before, false)));
    }

    @Test
    void invalidRequestNullLegAndNullObservationFailAmbiguous() {
        Observation confirmed = observation(Leg.DEBIT, REQUEST, REQUEST, true);
        Observation declined = observation(Leg.DEBIT, 0, 0, false);
        for (long request : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(request, Leg.DEBIT, confirmed));
            assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(request, Leg.DEBIT, declined));
            assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(request, declined, null));
        }
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(Long.MAX_VALUE, Leg.DEBIT, confirmed));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, null, confirmed));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, Leg.DEBIT, null));
    }

    @Test
    void transferMatrixRequiresBothConfirmedOrOnlyUnattemptedCreditAfterDecline() {
        Observation[] debit = outcomes(Leg.DEBIT);
        Observation[] credit = outcomes(Leg.CREDIT);
        for (int d = 0; d < debit.length; d++) {
            for (int c = 0; c < credit.length; c++) {
                TransferOutcome expected = d == 0 && c == 0 ? TransferOutcome.PAID
                        : d == 1 && credit[c] == null ? TransferOutcome.DECLINED : TransferOutcome.AMBIGUOUS;
                assertEquals(expected, TransferProof.evaluateTransfer(REQUEST, debit[d], credit[c]),
                        "debit=" + d + ", credit=" + c);
            }
        }
    }

    @Test
    void successfulDebitWithShortMissingOrThrownCreditIsNotDeclined() {
        Observation debit = observation(Leg.DEBIT, REQUEST, REQUEST, true);
        for (long cents : new long[]{0, 300, 499, 501}) {
            assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(REQUEST, debit,
                    observation(Leg.CREDIT, REQUEST, cents, true)));
        }
        assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(REQUEST, debit, null));
        assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(REQUEST, debit,
                new Observation(true, true, 5.0, 100.0, 105.0, true)));
    }

    @Test
    void oneCentTransferRequiresTwoFullLegs() {
        Observation debit = new Observation(true, true, 0.01, 0.01, 0.0, false);
        Observation credit = new Observation(true, true, 0.01, 0.0, 0.01, false);
        assertEquals(TransferOutcome.PAID, TransferProof.evaluateTransfer(1, debit, credit));
        assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(1, debit, null));
        assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(REQUEST, debit, credit));
        assertEquals(TransferOutcome.AMBIGUOUS, TransferProof.evaluateTransfer(0, debit, credit));
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static Observation observation(Leg leg, long reportedCents, long movedCents, boolean success) {
        long afterCents = 10_000 + (leg == Leg.DEBIT ? -movedCents : movedCents);
        return new Observation(true, success, reportedCents / 100.0, 100.0, afterCents / 100.0, false);
    }

    private static Observation[] outcomes(Leg leg) {
        Observation confirmed = observation(leg, REQUEST, REQUEST, true);
        return new Observation[]{
                confirmed,
                observation(leg, 0, 0, false),
                observation(leg, REQUEST, 300, true),
                new Observation(false, false, null, 100.0, 100.0, false),
                new Observation(true, true, 5.0, confirmed.beforeDollars(), confirmed.afterDollars(), true),
                null
        };
    }
}
