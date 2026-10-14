package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.LegOutcome;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.TransferOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferProofTest {

    private static final long REQUEST = 500;
    private static final long[] AMOUNTS = {0, 300, 499, 500, 501};
    private static final Double[] INVALID_DOLLARS = {
            null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, -Double.MIN_VALUE, Double.MAX_VALUE, 1.0e18,
            Long.MAX_VALUE / 100.0, 8_388_608.0, 0.001, 1.005, 100.00000001
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
    void fractionalBalancesCannotCancelIntoApparentlyExactDelta(Leg leg) {
        double after = leg == Leg.DEBIT ? 95.001 : 105.001;
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, true, 5.0, 100.001, after, false)));
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(REQUEST, leg,
                new Observation(true, false, 0.0, 100.001, 100.001, false)));
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
