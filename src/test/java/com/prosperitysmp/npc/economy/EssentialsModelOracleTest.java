package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.LegOutcome;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.SplittableRandom;
import java.util.function.DoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Models the provider instead of restating the rule. EssentialsX keeps each balance as an exact decimal with up
 * to twelve fractional digits, applies the request as a decimal, and hands Vault a double made from that
 * decimal: the nearest double, or (EssentialsX, seen in 2.20.1 to 2.22.0) one step lower when the nearest is above
 * the decimal. The checks below say what any correct rule must do with such a provider:
 * an exact transfer is always confirmed, a transfer off by a cent never is, and a move well above the precision of
 * the reported doubles is never taken for the request or for no movement.
 */
class EssentialsModelOracleTest {
    private static final int CASES = 20_000;
    private static final BigDecimal CEILING = new BigDecimal("999999999.99");
    private static final BigDecimal TWELVE_DIGITS = BigDecimal.ONE.movePointLeft(12);
    /**
     * The whole-cent rule's documented noise allowance is 1e-9 per value, so two values can hide up to 2e-9.
     * Offsets at or below this bound are left to that rule; the extra 1e-9 covers the decimal rounding of each double.
     */
    private static final BigDecimal WHOLE_CENT_NOISE = new BigDecimal("0.000000003");

    /**
     * How the provider turns the requested double into a decimal, and whether it reports balances one step lower when
     * the nearest double is above them. SHORTEST_DECIMAL_REPORTED_DOWN is EssentialsX (VaultEconomyProvider, read from
     * 2.22.0: BigDecimal.valueOf for deposit and withdraw, getDoubleValue steps the reported balance down).
     * EXACT_BINARY_REPORTED_DOWN is not a provider anyone has shown to exist. It keeps every safety property, but not
     * completeness: a large credit to a small balance can be off by about 1.5 steps of the after balance, which the
     * rule reports as AMBIGUOUS (held for staff), never as confirmed. See {@link #exactBinaryReportedDownCanBeHeld}.
     */
    enum Conversion {
        EXACT_BINARY(BigDecimal::new, false),
        SHORTEST_DECIMAL(BigDecimal::valueOf, false),
        SHORTEST_DECIMAL_REPORTED_DOWN(BigDecimal::valueOf, true),
        EXACT_BINARY_REPORTED_DOWN(BigDecimal::new, true);
        final DoubleFunction<BigDecimal> convert;
        final boolean reportsDown;
        Conversion(DoubleFunction<BigDecimal> convert, boolean reportsDown) { this.convert = convert; this.reportsDown = reportsDown; }
    }

    private record Case(Leg leg, long cents, BigDecimal before, BigDecimal applied, boolean reportsDown) {
        BigDecimal after(BigDecimal move) {
            return leg == Leg.DEBIT ? before.subtract(move) : before.add(move);
        }
    }

    @ParameterizedTest
    @EnumSource(Conversion.class)
    void exactTransfersAreAlwaysConfirmed(Conversion conversion) {
        // Completeness is not claimed for this model; the safety properties below still run on it.
        Assumptions.assumeFalse(conversion == Conversion.EXACT_BINARY_REPORTED_DOWN);
        SplittableRandom random = new SplittableRandom(6301);
        int fractional = 0;
        for (int i = 0; i < CASES; i++) {
            Case c = next(random, conversion);
            BigDecimal after = c.after(c.applied());
            Observation o = observation(c, true, after);
            if (TransferProof.toCents(o.beforeDollars()).isEmpty() || TransferProof.toCents(o.afterDollars()).isEmpty()) fractional++;
            assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(c.cents(), c.leg(), o), describe(c, after));
        }
        assertTrue(fractional > CASES / 4, "positive control: the exact rule decided many cases, saw " + fractional);
    }

    @ParameterizedTest
    @EnumSource(Conversion.class)
    void oneCentOffIsNeverConfirmed(Conversion conversion) {
        SplittableRandom random = new SplittableRandom(6302);
        for (int i = 0; i < CASES; i++) {
            Case c = next(random, conversion);
            for (BigDecimal cent : new BigDecimal[]{new BigDecimal("0.01"), new BigDecimal("-0.01")}) {
                BigDecimal after = c.after(c.applied().add(cent));
                if (after.signum() < 0 || after.compareTo(CEILING) > 0) continue;
                assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(c.cents(), c.leg(), observation(c, true, after)),
                        describe(c, after));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Conversion.class)
    void movesClearlyAbovePrecisionAreNeverConfirmed(Conversion conversion) {
        SplittableRandom random = new SplittableRandom(6303);
        int checked = 0;
        for (int i = 0; i < CASES; i++) {
            Case c = next(random, conversion);
            BigDecimal largest = c.before().max(c.after(c.applied())).max(BigDecimal.ONE);
            BigDecimal d = offset(random, largest);
            for (BigDecimal signed : new BigDecimal[]{d, d.negate()}) {
                BigDecimal after = c.after(c.applied().add(signed));
                if (after.signum() < 0 || after.compareTo(CEILING) > 0) continue;
                Observation o = observation(c, true, after);
                // The whole-cent rule keeps its documented noise allowance of 1e-9 per value.
                if (wholeCents(o) && d.compareTo(WHOLE_CENT_NOISE) <= 0) continue;
                checked++;
                assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(c.cents(), c.leg(), o), describe(c, after) + " d=" + signed);
            }
        }
        assertTrue(checked > CASES, "positive control: offsets were checked, saw " + checked);
    }

    @ParameterizedTest
    @EnumSource(Conversion.class)
    void failedResponseWithRealMovementIsNeverNoMovement(Conversion conversion) {
        SplittableRandom random = new SplittableRandom(6304);
        int checked = 0, noMovement = 0;
        for (int i = 0; i < CASES; i++) {
            Case c = next(random, conversion);
            Observation still = observation(c, false, c.before());
            assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(c.cents(), c.leg(), still), describe(c, c.before()));
            noMovement++;
            BigDecimal d = offset(random, c.before().max(BigDecimal.ONE));
            for (BigDecimal signed : new BigDecimal[]{d, d.negate()}) {
                BigDecimal after = c.before().add(signed);
                if (after.signum() < 0 || after.compareTo(CEILING) > 0) continue;
                Observation o = observation(c, false, after);
                if (wholeCents(o) && d.compareTo(WHOLE_CENT_NOISE) <= 0) continue;
                checked++;
                assertNotEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(c.cents(), c.leg(), o), describe(c, after));
            }
        }
        assertTrue(checked > CASES && noMovement == CASES, "positive control");
    }

    /** The case that rules out completeness for EXACT_BINARY_REPORTED_DOWN: it is held for staff, never confirmed. */
    @Test
    void exactBinaryReportedDownCanBeHeld() {
        BigDecimal before = new BigDecimal("755.239141068054");
        Case c = new Case(Leg.CREDIT, 729_991_835L, before, new BigDecimal(7_299_918.35), true);
        BigDecimal after = c.after(c.applied());
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(c.cents(), c.leg(), observation(c, true, after)), describe(c, after));
        Case nearest = new Case(Leg.CREDIT, 729_991_835L, before, new BigDecimal(7_299_918.35), false);
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(nearest.cents(), nearest.leg(), observation(nearest, true, after)),
                "positive control: the same credit reported at the nearest doubles is confirmed");
    }

    /**
     * An offset that no correct rule may hide: more than four gaps of the largest double involved. Two gaps
     * come from the allowance and up to one and a half from rounding the three values the provider reports.
     */
    private static BigDecimal offset(SplittableRandom random, BigDecimal largest) {
        BigDecimal gap = new BigDecimal(Math.ulp(largest.doubleValue()));
        BigDecimal floor = gap.multiply(BigDecimal.valueOf(4)).add(TWELVE_DIGITS);
        // Spread the offsets from just above the floor up to a few dollars, on a log scale.
        double scale = Math.pow(10, random.nextDouble() * 12);
        BigDecimal d = floor.multiply(BigDecimal.valueOf(scale), MathContext.DECIMAL64).setScale(12, java.math.RoundingMode.CEILING);
        return d.compareTo(floor) > 0 ? d : floor.setScale(12, java.math.RoundingMode.CEILING);
    }

    private static Case next(SplittableRandom random, Conversion conversion) {
        Leg leg = random.nextBoolean() ? Leg.DEBIT : Leg.CREDIT;
        long cents = random.nextInt(4) == 0 ? random.nextLong(1, 838_860_800L) : random.nextLong(1, 1_000_000L);
        BigDecimal amount = BigDecimal.valueOf(cents, 2);
        BigDecimal applied = conversion.convert.apply(cents / 100.0);
        BigDecimal before;
        do {
            before = balance(random);
            if (leg == Leg.DEBIT && before.compareTo(amount) < 0) before = before.add(amount);
        } while (leg == Leg.CREDIT ? before.add(applied).compareTo(CEILING) > 0 : before.compareTo(CEILING) > 0);
        return new Case(leg, cents, before, applied, conversion.reportsDown);
    }

    /** Up to twelve fractional digits below the ceiling, sometimes whole cents, small or huge. */
    private static BigDecimal balance(SplittableRandom random) {
        long dollars = switch (random.nextInt(4)) {
            case 0 -> random.nextLong(0, 1_000);
            case 1 -> random.nextLong(0, 100_000);
            case 2 -> random.nextLong(0, 10_000_000);
            default -> random.nextLong(0, 999_999_999);
        };
        BigDecimal whole = BigDecimal.valueOf(dollars);
        if (random.nextInt(3) == 0) return whole.add(BigDecimal.valueOf(random.nextLong(0, 100), 2));
        return whole.add(BigDecimal.valueOf(random.nextLong(0, 1_000_000_000_000L), 12));
    }

    private static Observation observation(Case c, boolean success, BigDecimal after) {
        return new Observation(true, success, success ? c.cents() / 100.0 : 0.0, reported(c, c.before()), reported(c, after), false);
    }

    /** The double the provider reports for a balance: the nearest, or one step lower when that is above the balance. */
    private static double reported(Case c, BigDecimal balance) {
        double nearest = balance.doubleValue();
        return c.reportsDown() && new BigDecimal(nearest).compareTo(balance) > 0 ? Math.nextDown(nearest) : nearest;
    }

    private static boolean wholeCents(Observation o) {
        return TransferProof.toCents(o.beforeDollars()).isPresent() && TransferProof.toCents(o.afterDollars()).isPresent();
    }

    private static String describe(Case c, BigDecimal after) {
        return c.leg() + " " + c.cents() + "c from " + c.before().toPlainString() + " to " + after.toPlainString();
    }
}
