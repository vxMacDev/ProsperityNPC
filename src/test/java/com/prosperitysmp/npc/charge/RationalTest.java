package com.prosperitysmp.npc.charge;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RationalTest {
    private static final BigInteger TWO_TO_127 = BigInteger.ONE.shiftLeft(127);
    private static final BigInteger TWO_TO_128 = BigInteger.ONE.shiftLeft(128);

    private static Rational of(long num, long den) {
        return Rational.of(BigInteger.valueOf(num), BigInteger.valueOf(den));
    }

    @Test void aWholeNumberParsesToItself() {
        Rational twelve = Rational.parse("12");
        assertEquals(Rational.of(12), twelve);
        assertEquals("12", twelve.toString());
        assertTrue(twelve.isInteger());
    }

    @Test void aDecimalParsesToAnExactFractionInLowestTerms() {
        Rational half = Rational.parse("0.5");
        assertEquals(BigInteger.ONE, half.numerator());
        assertEquals(BigInteger.TWO, half.denominator());
        assertEquals("1/2", half.toString());
        assertFalse(half.isInteger());
    }

    @Test void theLongestValidNumberIsNineteenCharacters() {
        Rational r = Rational.parse("123456789012.123456");
        assertEquals(new BigInteger("1929012328314429"), r.numerator());
        assertEquals(BigInteger.valueOf(15625), r.denominator());
        assertEquals(Rational.of(new BigInteger("123456789012123456"), BigInteger.valueOf(1_000_000)), r);
    }

    @Test void theLargestNumberAndTheSmallestFractionParseExactly() {
        assertEquals(of(999_999_999_999_999_999L, 1_000_000), Rational.parse("999999999999.999999"));
        assertEquals(of(1, 1_000_000), Rational.parse("0.000001"));
        assertEquals(Rational.of(7), Rational.parse("007"));
        assertEquals(Rational.of(7), Rational.parse("7.000000"));
        assertEquals(Rational.ZERO, Rational.parse("0"));
        assertEquals(Rational.ZERO, Rational.parse("0.0"));
    }

    @Test void twentyCharactersAreRefusedBeforeTheDigitsAreRead() {
        var failure = assertThrows(FormulaException.class, () -> Rational.parse("12345678901234567890"));
        assertEquals("not a number", failure.getMessage());
        var thirteen = assertThrows(FormulaException.class, () -> Rational.parse("1234567890123"));
        assertEquals("not a number: 1234567890123", thirteen.getMessage());
    }

    @Test void nullIsNotANumber() {
        assertEquals("not a number", assertThrows(FormulaException.class, () -> Rational.parse(null)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".5", "5.", "-1", "+1", "1e3", "1,5", "1234567890123", "1.1234567", "１",
            " 1", "1 ", "1..2", "1.2.3", "0x10", "1_000", "١"})
    void textOutsideTheNumberGrammarIsRefused(String text) {
        assertThrows(FormulaException.class, () -> Rational.parse(text));
    }

    @Test void onlyAsciiDigitsCountAsDigits() {
        assertTrue(Rational.digits("0123456789"));
        assertTrue(Rational.digits(""));
        assertFalse(Rational.digits("12a"));
        assertFalse(Rational.digits("１"));
        assertFalse(Rational.digits("/"));
        assertFalse(Rational.digits(":"));
    }

    @Test void valuesAreKeptInLowestTermsWithAPositiveDenominator() {
        assertEquals(of(1, 2), of(2, 4));
        assertEquals("1/2", of(2, 4).toString());
        Rational negative = of(3, -6);
        assertEquals(BigInteger.valueOf(-1), negative.numerator());
        assertEquals(BigInteger.TWO, negative.denominator());
        assertEquals("-1/2", negative.toString());
        assertEquals(-1, negative.signum());
        assertEquals(of(1, 2), of(-3, -6));
        assertTrue(of(4, 2).isInteger());
        assertEquals("2", of(4, 2).toString());
    }

    @Test void zeroAlwaysHasDenominatorOne() {
        assertEquals(BigInteger.ONE, of(0, 7).denominator());
        assertEquals(Rational.ZERO, of(0, 7));
        assertEquals(Rational.ZERO, of(0, -7));
        assertEquals(0, of(0, -7).signum());
        assertEquals(Rational.ZERO.hashCode(), of(0, 99).hashCode());
    }

    @Test void aZeroDenominatorOrDivisorIsADivisionByZero() {
        assertEquals("division by zero", assertThrows(FormulaException.class, () -> of(1, 0)).getMessage());
        assertEquals("division by zero", assertThrows(FormulaException.class, () -> Rational.ONE.divide(Rational.ZERO)).getMessage());
        assertEquals("division by zero", assertThrows(FormulaException.class, () -> of(0, 0)).getMessage());
    }

    @Test void nullPartsAreRefused() {
        assertThrows(NullPointerException.class, () -> Rational.of(null, BigInteger.ONE));
        assertThrows(NullPointerException.class, () -> Rational.of(BigInteger.ONE, null));
    }

    @Test void aNumeratorOfExactlyOneHundredTwentyEightBitsFits() {
        Rational big = Rational.of(TWO_TO_127, BigInteger.ONE);
        assertEquals(128, big.numerator().bitLength());
        assertEquals(TWO_TO_127, big.numerator());
        Rational smallest = Rational.of(BigInteger.ONE, TWO_TO_127);
        assertEquals(TWO_TO_127, smallest.denominator());
    }

    @Test void aNumeratorOrDenominatorPastOneHundredTwentyEightBitsStops() {
        var num = assertThrows(FormulaException.class, () -> Rational.of(TWO_TO_128, BigInteger.ONE));
        assertEquals("a value grew past 128 bits", num.getMessage());
        var den = assertThrows(FormulaException.class, () -> Rational.of(BigInteger.ONE, TWO_TO_128));
        assertEquals("a value grew past 128 bits", den.getMessage());
        assertThrows(FormulaException.class, () -> Rational.of(TWO_TO_128.add(BigInteger.ONE).negate(), BigInteger.ONE));
    }

    @Test void aReducibleOversizedFractionFitsOnceReduced() {
        Rational r = Rational.of(TWO_TO_128, BigInteger.TWO);
        assertEquals(TWO_TO_127, r.numerator());
        assertEquals(BigInteger.ONE, r.denominator());
    }

    @Test void multiplyingTwoSixtyFourBitPowersStops() {
        Rational a = Rational.of(BigInteger.ONE.shiftLeft(64), BigInteger.ONE);
        assertEquals(Rational.of(TWO_TO_127, BigInteger.ONE), a.multiply(Rational.of(BigInteger.ONE.shiftLeft(63), BigInteger.ONE)));
        assertThrows(FormulaException.class, () -> a.multiply(a));
        assertThrows(FormulaException.class, () -> Rational.of(TWO_TO_127, BigInteger.ONE).add(Rational.of(TWO_TO_127, BigInteger.ONE)));
    }

    @Test void arithmeticIsExact() {
        assertEquals(of(1, 2), of(1, 3).add(of(1, 6)));
        assertEquals(of(-1, 6), of(1, 3).subtract(of(1, 2)));
        assertEquals(of(1, 2), of(2, 3).multiply(of(3, 4)));
        assertEquals(Rational.of(-2), of(1, 2).divide(of(-1, 4)));
        assertEquals(of(-1, 2), of(1, 2).negate());
        assertEquals(of(1, 2), of(-1, 2).negate());
        assertEquals(Rational.ZERO, Rational.ZERO.negate());
    }

    @Test void dividingByANegativeKeepsTheDenominatorPositive() {
        Rational r = of(1, 2).divide(of(-1, 3));
        assertEquals(BigInteger.valueOf(-3), r.numerator());
        assertEquals(BigInteger.TWO, r.denominator());
    }

    @ParameterizedTest(name = "{0}/{1}: floor {2}, ceil {3}, half-up {4}")
    @CsvSource({
            "5, 2, 2, 3, 3",
            "-5, 2, -3, -2, -3",
            "7, 3, 2, 3, 2",
            "-7, 3, -3, -2, -2",
            "1, 2, 0, 1, 1",
            "-1, 2, -1, 0, -1",
            "5, 3, 1, 2, 2",
            "-5, 3, -2, -1, -2",
            "999999, 1000000, 0, 1, 1",
            "499999, 1000000, 0, 1, 0",
            "4, 1, 4, 4, 4",
            "-4, 1, -4, -4, -4",
            "0, 1, 0, 0, 0"})
    void roundingFollowsItsRule(long num, long den, long floor, long ceil, long halfUp) {
        Rational r = of(num, den);
        assertEquals(Rational.of(floor), r.floor());
        assertEquals(Rational.of(ceil), r.ceil());
        assertEquals(Rational.of(halfUp), r.halfUp());
    }

    @Test void longValueExactCoversTheWholeLongRange() {
        assertEquals(Long.MAX_VALUE, Rational.of(Long.MAX_VALUE).longValueExact());
        assertEquals(Long.MIN_VALUE, Rational.of(Long.MIN_VALUE).longValueExact());
        assertEquals(-7, of(-14, 2).longValueExact());
    }

    @Test void longValueExactRefusesFractionsAndOversizedWholes() {
        var tooLarge = assertThrows(FormulaException.class, () -> Rational.of(Long.MAX_VALUE).add(Rational.ONE).longValueExact());
        assertEquals("whole number too large", tooLarge.getMessage());
        var tooSmall = assertThrows(FormulaException.class, () -> Rational.of(Long.MIN_VALUE).subtract(Rational.ONE).longValueExact());
        assertEquals("whole number too large", tooSmall.getMessage());
        var half = assertThrows(FormulaException.class, () -> of(1, 2).longValueExact());
        assertEquals("not a whole number", half.getMessage());
    }

    @Test void compareToAgreesWithEquals() {
        assertEquals(0, of(1, 2).compareTo(of(2, 4)));
        assertEquals(of(1, 2), of(2, 4));
        assertEquals(of(1, 2).hashCode(), of(2, 4).hashCode());
        assertTrue(of(-1, 2).compareTo(of(1, 3)) < 0);
        assertTrue(of(1, 3).compareTo(of(-1, 2)) > 0);
        assertTrue(of(1, 3).compareTo(of(1, 2)) < 0);
        assertTrue(of(-1, 3).compareTo(of(-1, 2)) > 0);
    }

    @Test void equalsIsFalseForOtherTypesAndDifferentValues() {
        assertNotEquals(Rational.ONE, (Object) 1L);
        assertNotEquals(Rational.ONE, (Object) BigInteger.ONE);
        assertNotEquals(of(1, 2), of(1, 3));
        assertNotEquals(of(1, 2), of(-1, 2));
        assertFalse(Rational.ONE.equals(null));
    }

    @Test void minAndMaxReturnTheFirstValueOnATie() {
        Rational a = of(1, 2), b = of(2, 4);
        assertSame(a, Rational.min(a, b));
        assertSame(a, Rational.max(a, b));
        assertSame(b, Rational.min(b, a));
        assertEquals(of(1, 3), Rational.min(of(1, 2), of(1, 3)));
        assertEquals(of(1, 2), Rational.max(of(1, 2), of(1, 3)));
        assertEquals(of(-1, 2), Rational.min(of(-1, 2), of(-1, 3)));
    }

    @Test void theConstantsAreZeroAndOne() {
        assertEquals("0", Rational.ZERO.toString());
        assertEquals("1", Rational.ONE.toString());
        assertEquals(128, Rational.MAX_BITS);
        assertEquals(Rational.ONE, Rational.of(1));
        assertEquals(0, Rational.ZERO.signum());
        assertEquals(1, Rational.ONE.signum());
    }
}
