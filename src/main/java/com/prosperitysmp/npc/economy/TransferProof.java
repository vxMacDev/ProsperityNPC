package com.prosperitysmp.npc.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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

    /** Exclusive ceiling for any balance used as evidence. Below it a double's spacing is at most 2^-23 dollars. */
    public static final BigDecimal MAX_BALANCE_DOLLARS = new BigDecimal("1000000000");
    /** At debit time the receiver must stay at or below this after the credit (headroom for movement between legs). */
    public static final BigDecimal RECEIVER_LIMIT_DOLLARS = new BigDecimal("900000000");
    /** At credit time the receiver must stay this far below the ceiling, so rounding can never reach it. */
    public static final BigDecimal CREDIT_MARGIN_DOLLARS = new BigDecimal("0.01");

    public enum Leg {
        DEBIT, CREDIT
    }

    public enum LegOutcome {
        CONFIRMED, NO_MOVEMENT, AMBIGUOUS
    }

    public enum TransferOutcome {
        PAID, DECLINED, AMBIGUOUS
    }

    /** Why a leg stopped before the provider was asked to move money. */
    public enum Refusal {
        WRONG_THREAD, INVALID_REQUEST, PROVIDER_CHANGED,
        LEASE_REFUSED, LEASE_FAILED, PRECHECK_REFUSED, PRECHECK_FAILED,
        COUNTERPARTY_UNVERIFIABLE, BALANCE_UNVERIFIABLE, INSUFFICIENT_FUNDS;

        /** Reasons that point at a fault rather than a normal business refusal. */
        public boolean unexpected() {
            return this == WRONG_THREAD || this == INVALID_REQUEST || this == LEASE_FAILED || this == PRECHECK_FAILED;
        }
    }

    /**
     * Null dollar fields represent unavailable evidence, never zero. refusedBeforeCall is non-null only when
     * this code proved withdrawPlayer/depositPlayer was never invoked; refusalCause is then a class name.
     */
    public record Observation(boolean responsePresent, boolean responseSuccess,
                              Double reportedDollars, Double beforeDollars, Double afterDollars,
                              boolean threw, Refusal refusedBeforeCall, String refusalCause) {
        public Observation {
            boolean valid = refusedBeforeCall == null
                    ? refusalCause == null
                    : !responsePresent && !responseSuccess && reportedDollars == null && threw
                      && refusalCause != null && refusalCause.matches("[A-Za-z0-9_$.]{1,64}");
            if (!valid) throw new IllegalArgumentException("Invalid refusal evidence");
        }

        /** Called, or unknown: every row stored before refusals were recorded. */
        public Observation(boolean responsePresent, boolean responseSuccess, Double reportedDollars,
                           Double beforeDollars, Double afterDollars, boolean threw) {
            this(responsePresent, responseSuccess, reportedDollars, beforeDollars, afterDollars, threw, null, null);
        }

        /** A leg that stopped before any money call. Balances read before stopping are kept as evidence. */
        public static Observation refused(Refusal reason, Throwable cause, Double before, Double after) {
            return new Observation(false, false, null, before, after, true, Objects.requireNonNull(reason), causeName(cause));
        }
    }

    private TransferProof() {
    }

    /** The last 64 characters of the class name, or "none". Never the message: messages may carry data. */
    static String causeName(Throwable cause) {
        if (cause == null) return "none";
        String name = cause.getClass().getName().replaceAll("[^A-Za-z0-9_$.]", "_");
        return name.isEmpty() ? "none" : name.substring(Math.max(0, name.length() - 64));
    }

    /** Our own authority checks throw these. Anything else is a fault. */
    public static boolean expectedRefusal(Throwable t) {
        return t instanceof IllegalStateException || t instanceof NoSuchElementException;
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

    /** Exact binary value of a balance, or empty if null, NaN, infinite, negative, or at or above the ceiling. -0.0 is 0. */
    static Optional<BigDecimal> exactBalance(Double dollars) {
        if (dollars == null || !Double.isFinite(dollars) || dollars < 0) return Optional.empty();
        // The exact binary value, never valueOf: Double.toString digits changed in JDK 19.
        BigDecimal exact = new BigDecimal(dollars);
        return exact.compareTo(MAX_BALANCE_DOLLARS) < 0 ? Optional.of(exact) : Optional.empty();
    }

    /** The precision of one reported value: the gap to the next double. */
    private static BigDecimal precision(double dollars) {
        return new BigDecimal(Math.ulp(dollars));
    }

    /**
     * Whole cents that are certainly available. A whole-cent balance counts as its cent only when the value is that
     * cent to its own precision: the whole-cent rule's noise allowance may round up, and spending a cent that is
     * not there would overdraw an account that can borrow. Otherwise round the balance down by its own precision,
     * then down to a cent. Never more than the true balance.
     */
    public static OptionalLong availableCents(Double dollars) {
        Optional<BigDecimal> exact = exactBalance(dollars);
        if (exact.isEmpty()) return OptionalLong.empty();
        OptionalLong whole = toCents(dollars);
        if (whole.isPresent()
                && exact.get().compareTo(BigDecimal.valueOf(whole.getAsLong(), 2).subtract(precision(dollars))) >= 0) return whole;
        BigDecimal floor = exact.get().subtract(precision(dollars)).movePointRight(2).setScale(0, RoundingMode.FLOOR);
        return OptionalLong.of(Math.max(0L, floor.longValueExact()));
    }

    /** True when the account can receive cents and stay at or below limit. */
    public static boolean canReceive(Double dollars, long cents, BigDecimal limit) {
        Optional<BigDecimal> exact = exactBalance(dollars);
        return cents > 0 && exact.isPresent()
                && exact.get().add(BigDecimal.valueOf(cents, 2)).compareTo(limit) <= 0;
    }

    /**
     * Success requires the full reported request and the observed move. An explicit failure with no move is
     * NO_MOVEMENT; its reported amount must still be valid, but need not equal the request or zero. A leg
     * proved to have stopped before the provider call is NO_MOVEMENT. Missing responses, throws and
     * contradictory successful responses are always ambiguous, even when the observed delta is zero.
     * When both balances are whole cents the integer rule decides. Otherwise the exact binary balances must
     * show the request to within the precision of the two reported values.
     */
    public static LegOutcome evaluate(long requestCents, Leg leg, Observation observation) {
        if (requestCents <= 0 || leg == null || observation == null) return LegOutcome.AMBIGUOUS;
        if (observation.refusedBeforeCall() != null) return LegOutcome.NO_MOVEMENT;
        if (observation.threw() || !observation.responsePresent()) return LegOutcome.AMBIGUOUS;
        OptionalLong reported = toCents(observation.reportedDollars());
        if (reported.isEmpty()) return LegOutcome.AMBIGUOUS;
        OptionalLong before = toCents(observation.beforeDollars());
        OptionalLong after = toCents(observation.afterDollars());
        if (before.isPresent() && after.isPresent()) {
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
        // At least one balance is not a whole cent: compare the exact binary values.
        Optional<BigDecimal> b = exactBalance(observation.beforeDollars());
        Optional<BigDecimal> a = exactBalance(observation.afterDollars());
        if (b.isEmpty() || a.isEmpty()) return LegOutcome.AMBIGUOUS;
        BigDecimal moved = leg == Leg.DEBIT ? b.get().subtract(a.get()) : a.get().subtract(b.get());
        BigDecimal allowed = precision(observation.beforeDollars()).add(precision(observation.afterDollars()));
        if (!observation.responseSuccess()) {
            return moved.abs().compareTo(allowed) <= 0 ? LegOutcome.NO_MOVEMENT : LegOutcome.AMBIGUOUS;
        }
        return reported.getAsLong() == requestCents
                && moved.subtract(BigDecimal.valueOf(requestCents, 2)).abs().compareTo(allowed) <= 0
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

    /**
     * The operator's refund puts back exactly what a fully observed debit took: the debit moved the requested
     * cents out of the payer, and the refund moved the same cents back in. The refund is judged by its own change,
     * not by where it started, because the payer's balance may have moved for other reasons between the debit and
     * the refund. Signed balances are allowed here because some providers let a debit overdraw.
     */
    public static boolean refundRestoresDebit(long cents, Observation debit, long refundBeforeBits, long refundAfterBits) {
        if (cents <= 0 || debit == null || debit.refusedBeforeCall() != null || !debit.responsePresent() || !debit.responseSuccess()
                || debit.threw() || toCents(debit.reportedDollars()).orElse(-1) != cents
                || debit.beforeDollars() == null || debit.afterDollars() == null) return false;
        return fellBy(debit.beforeDollars(), debit.afterDollars(), cents)
                && fellBy(Double.longBitsToDouble(refundAfterBits), Double.longBitsToDouble(refundBeforeBits), cents);
    }

    /**
     * True when start minus end is exactly cents. When both are whole cents the integer rule decides; otherwise the
     * exact binary values must agree to within the precision of the two values. Signed values are allowed.
     */
    private static boolean fellBy(double start, double end, long cents) {
        if (!Double.isFinite(start) || !Double.isFinite(end)) return false;
        OptionalLong wholeStart = signedCents(start), wholeEnd = signedCents(end);
        if (wholeStart.isPresent() && wholeEnd.isPresent()) {
            // Both values are below 838,860,800 cents in size, so the difference cannot overflow.
            return wholeStart.getAsLong() - wholeEnd.getAsLong() == cents;
        }
        BigDecimal exactStart = new BigDecimal(start), exactEnd = new BigDecimal(end);
        if (exactStart.abs().compareTo(MAX_BALANCE_DOLLARS) >= 0 || exactEnd.abs().compareTo(MAX_BALANCE_DOLLARS) >= 0)
            return false;
        return exactStart.subtract(exactEnd).subtract(BigDecimal.valueOf(cents, 2)).abs()
                .compareTo(precision(start).add(precision(end))) <= 0;
    }

    /** The whole-cent value of a signed balance, or empty when its size is not a whole cent. */
    private static OptionalLong signedCents(double dollars) {
        OptionalLong size = toCents(Math.abs(dollars));
        if (size.isEmpty()) return size;
        return OptionalLong.of(dollars < 0 ? -size.getAsLong() : size.getAsLong());
    }

    /**
     * Builds before this one stopped a leg when the starting balance was not a whole cent, or when a debit
     * lacked the funds, and stored that as a throw with no response and an unchanged balance. Would that old
     * check have refused this stored leg? Only the offline recovery tools use this, and only together with
     * proof of which build wrote the row.
     */
    public static boolean legacyGateRefused(long cents, Leg leg, Observation o) {
        if (o == null || o.refusedBeforeCall() != null || o.responsePresent() || o.responseSuccess()
                || o.reportedDollars() != null || !o.threw() || o.beforeDollars() == null
                || !Objects.equals(bits(o.beforeDollars()), bits(o.afterDollars()))) return false;
        OptionalLong old = toCents(o.beforeDollars());
        return old.isEmpty() || (leg == Leg.DEBIT && old.getAsLong() < cents);
    }

    private static Long bits(Double d) {
        return d == null ? null : Double.doubleToRawLongBits(d);
    }
}
