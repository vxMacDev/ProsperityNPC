package com.prosperitysmp.npc.charge;

import java.math.BigInteger;
import java.util.Objects;

/**
 * An exact fraction for bill formulas. The numerator and denominator are whole numbers kept in lowest terms with a
 * positive denominator, so two equal values always compare equal and nothing is ever rounded along the way. Any
 * value whose numerator or denominator would need more than 128 bits stops the calculation instead of growing.
 */
public final class Rational implements Comparable<Rational> {
    /** The size limit for a numerator or a denominator, in bits. */
    public static final int MAX_BITS = 128;

    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den;

    private Rational(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    public static Rational of(long whole) {
        return of(BigInteger.valueOf(whole), BigInteger.ONE);
    }

    /** Lowest terms with a positive denominator. A zero denominator or an oversized result is a formula error. */
    public static Rational of(BigInteger num, BigInteger den) {
        Objects.requireNonNull(num);
        Objects.requireNonNull(den);
        if (den.signum() == 0) throw new FormulaException("division by zero");
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger gcd = num.gcd(den);
        if (!gcd.equals(BigInteger.ONE) && gcd.signum() != 0) {
            num = num.divide(gcd);
            den = den.divide(gcd);
        }
        if (num.signum() == 0) den = BigInteger.ONE;
        if (num.bitLength() > MAX_BITS || den.bitLength() > MAX_BITS)
            throw new FormulaException("a value grew past " + MAX_BITS + " bits");
        return new Rational(num, den);
    }

    /** Parses the NUMBER grammar: 1 to 12 digits, then optionally a dot and 1 to 6 digits. ASCII only. */
    public static Rational parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 19) throw new FormulaException("not a number");
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot + 1);
        if (whole.isEmpty() || whole.length() > 12 || !digits(whole)) throw new FormulaException("not a number: " + text);
        if (dot >= 0 && (fraction.isEmpty() || fraction.length() > 6 || !digits(fraction)))
            throw new FormulaException("not a number: " + text);
        BigInteger num = new BigInteger(whole + fraction);
        BigInteger den = BigInteger.TEN.pow(fraction.length());
        return of(num, den);
    }

    static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public BigInteger numerator() { return num; }
    public BigInteger denominator() { return den; }
    public int signum() { return num.signum(); }
    public boolean isInteger() { return den.equals(BigInteger.ONE); }

    public Rational add(Rational o) { return of(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den)); }
    public Rational subtract(Rational o) { return of(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den)); }
    public Rational multiply(Rational o) { return of(num.multiply(o.num), den.multiply(o.den)); }
    public Rational negate() { return of(num.negate(), den); }

    public Rational divide(Rational o) {
        if (o.num.signum() == 0) throw new FormulaException("division by zero");
        return of(num.multiply(o.den), den.multiply(o.num));
    }

    /** The largest whole number not above this value. */
    public Rational floor() {
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        if (qr[1].signum() < 0) q = q.subtract(BigInteger.ONE);
        return of(q, BigInteger.ONE);
    }

    /** The smallest whole number not below this value. */
    public Rational ceil() {
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        if (qr[1].signum() > 0) q = q.add(BigInteger.ONE);
        return of(q, BigInteger.ONE);
    }

    /** Rounds to the nearest whole number; an exact half goes up (away from zero for the non-negative results bills use). */
    public Rational halfUp() {
        Rational shifted = add(of(BigInteger.ONE, BigInteger.TWO));
        return signum() >= 0 ? shifted.floor() : negate().add(of(BigInteger.ONE, BigInteger.TWO)).floor().negate();
    }

    /** The whole value as a long; only valid for an integer that fits. */
    public long longValueExact() {
        if (!isInteger()) throw new FormulaException("not a whole number");
        try {
            return num.longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new FormulaException("whole number too large");
        }
    }

    public static Rational min(Rational a, Rational b) { return a.compareTo(b) <= 0 ? a : b; }
    public static Rational max(Rational a, Rational b) { return a.compareTo(b) >= 0 ? a : b; }

    @Override public int compareTo(Rational o) { return num.multiply(o.den).compareTo(o.num.multiply(den)); }

    @Override public boolean equals(Object o) {
        return o instanceof Rational r && num.equals(r.num) && den.equals(r.den);
    }

    @Override public int hashCode() { return 31 * num.hashCode() + den.hashCode(); }

    @Override public String toString() { return isInteger() ? num.toString() : num + "/" + den; }
}
