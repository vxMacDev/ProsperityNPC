package com.prosperitysmp.npc.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;

/**
 * Pure classification of an observed two-leg transfer, not authorization to move
 * money or grant benefits. Callers must bind observations to the correct accounts
 * and capture each balance immediately around its call without interleaving.
 * This does not establish provider durability or a committed ledger receipt.
 */
public final class TransferProof {

    /**
     * Absolute per-value allowance: one billionth of a dollar (one ten-millionth
     * of a cent). It never scales with the balance. Values whose double spacing
     * exceeds this allowance are rejected, even if apparently whole-cent values.
     */
    public static final double CENT_TOLERANCE_DOLLARS = 0.000000001;

    private static final BigDecimal CENT_TOLERANCE = BigDecimal.valueOf(CENT_TOLERANCE_DOLLARS);

    public enum Leg {
        DEBIT, CREDIT
    }

    public enum LegOutcome {
        CONFIRMED, NO_MOVEMENT, AMBIGUOUS
    }

    public enum TransferOutcome {
        PAID, DECLINED, AMBIGUOUS
    }

    /** Null dollar fields represent unavailable evidence, never zero. */
    public record Observation(boolean responsePresent, boolean responseSuccess,
                              Double reportedDollars, Double beforeDollars, Double afterDollars,
                              boolean threw) {
    }

    private TransferProof() {
    }

    /**
     * Converts a nonnegative dollar value to integer cents without truncation or
     * saturation. Only the documented absolute noise around a cent is accepted;
     * nonfinite, missing, negative, fractional-cent, coarse or overflowing values
     * return empty. Decimal conversion uses {@link BigDecimal#valueOf(double)}.
     * Signed zero is treated as zero. Million-dollar balances remain supported.
     */
    public static OptionalLong toCents(Double dollars) {
        if (dollars == null || !Double.isFinite(dollars) || dollars < 0
                || Math.ulp(dollars) > CENT_TOLERANCE_DOLLARS) {
            return OptionalLong.empty();
        }
        BigDecimal value = BigDecimal.valueOf(dollars);
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_EVEN);
        if (value.subtract(rounded).abs().compareTo(CENT_TOLERANCE) > 0) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(rounded.movePointRight(2).longValueExact());
        } catch (ArithmeticException overflow) {
            return OptionalLong.empty();
        }
    }

    /**
     * Success requires the full reported request and the exact signed cent delta.
     * An explicit failure with zero observed delta is NO_MOVEMENT; its reported
     * amount must still be valid, but need not equal the request or zero.
     * Missing responses, throws and contradictory successful responses are always
     * ambiguous. A throw remains ambiguous even when the observed delta is zero.
     */
    public static LegOutcome evaluate(long requestCents, Leg leg, Observation observation) {
        if (requestCents <= 0 || leg == null || observation == null
                || observation.threw() || !observation.responsePresent()) {
            return LegOutcome.AMBIGUOUS;
        }
        OptionalLong reported = toCents(observation.reportedDollars());
        OptionalLong before = toCents(observation.beforeDollars());
        OptionalLong after = toCents(observation.afterDollars());
        if (reported.isEmpty() || before.isEmpty() || after.isEmpty()) {
            return LegOutcome.AMBIGUOUS;
        }

        // Normalize each balance before subtracting; subtracting doubles loses cent evidence.
        // Both values are nonnegative longs, so their signed difference cannot overflow.
        long delta = leg == Leg.DEBIT
                ? before.getAsLong() - after.getAsLong()
                : after.getAsLong() - before.getAsLong();
        if (!observation.responseSuccess()) {
            return delta == 0 ? LegOutcome.NO_MOVEMENT : LegOutcome.AMBIGUOUS;
        }
        return reported.getAsLong() == requestCents && delta == requestCents
                ? LegOutcome.CONFIRMED : LegOutcome.AMBIGUOUS;
    }

    /**
     * Debit means the payer's leg and credit the recipient's, in either business
     * direction. Only null credit means it was not attempted. No outcome permits
     * automatic retry, refund or compensation; unresolved evidence stays ambiguous.
     */
    public static TransferOutcome evaluateTransfer(long requestCents, Observation debit, Observation credit) {
        LegOutcome debitOutcome = evaluate(requestCents, Leg.DEBIT, debit);
        if (credit == null) {
            return debitOutcome == LegOutcome.NO_MOVEMENT
                    ? TransferOutcome.DECLINED : TransferOutcome.AMBIGUOUS;
        }
        return debitOutcome == LegOutcome.CONFIRMED
                && evaluate(requestCents, Leg.CREDIT, credit) == LegOutcome.CONFIRMED
                ? TransferOutcome.PAID : TransferOutcome.AMBIGUOUS;
    }
}
