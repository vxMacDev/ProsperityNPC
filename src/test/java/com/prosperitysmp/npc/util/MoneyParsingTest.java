package com.prosperitysmp.npc.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyParsingTest {
    @ParameterizedTest
    @CsvSource({"2,200", "2.5,250", "2.50,250", "$3.75,375", "0.01,1", "0.1,10", "0.10,10", "007,700", "00.5,50", "$10,1000",
            "1000000,100000000", "0.99,99", "123.45,12345", "1.00,100", "$0.05,5"})
    void aPositiveDollarAmountBecomesExactCents(String text, long cents) {
        assertEquals(cents, Money.parseDollarsToCents(text));
    }

    @Test
    void surroundingWhitespaceIsTrimmedBeforeParsing() {
        assertEquals(700L, Money.parseDollarsToCents(" 7 "));
        assertEquals(500L, Money.parseDollarsToCents("\t5\n"));
        assertEquals(375L, Money.parseDollarsToCents("  $3.75  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "0.0", "00", "$0", "$0.00"})
    void zeroIsNotAPositiveAmount(String text) {
        assertEquals(-1L, Money.parseDollarsToCents(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {".5", "5.", "1.234", "-1", "-0.01", "+5", "1,000", "1e2", "1E2", "0x10", "5 .00", "5. 00", "$ 5",
            "$$5", "5$", "$", "", " ", "abc", "5 dollars", "NaN", "Infinity", "1_000", "1/2"})
    void textThatIsNotADollarAmountIsRefused(String text) {
        assertEquals(-1L, Money.parseDollarsToCents(text));
    }

    @Test
    void aNullAmountIsRefused() {
        assertEquals(-1L, Money.parseDollarsToCents(null));
    }

    @Test
    void digitsOfOtherScriptsAreNotDollarAmounts() {
        assertEquals(-1L, Money.parseDollarsToCents("１"));
        assertEquals(-1L, Money.parseDollarsToCents("１０"));
        assertEquals(-1L, Money.parseDollarsToCents("٣"));
        assertEquals(-1L, Money.parseDollarsToCents("1.٥"));
    }

    @Test
    void theLargestAmountThatFitsInALongIsAccepted() {
        assertEquals(Long.MAX_VALUE, Money.parseDollarsToCents("92233720368547758.07"));
        assertEquals(Long.MAX_VALUE - 1, Money.parseDollarsToCents("$92233720368547758.06"));
        assertEquals(9_223_372_036_854_775_800L, Money.parseDollarsToCents("92233720368547758"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"92233720368547758.08", "92233720368547758.99", "92233720368547759", "99999999999999999999",
            "9223372036854775807", "9223372036854775808", "184467440737095517", "1000000000000000000000.00"})
    void anAmountThatOverflowsALongIsRefusedNotWrapped(String text) {
        assertEquals(-1L, Money.parseDollarsToCents(text));
    }

    @ParameterizedTest
    @CsvSource({"0,$0.00", "1,$0.01", "5,$0.05", "9,$0.09", "10,$0.10", "99,$0.99", "100,$1.00", "101,$1.01", "12345,$123.45",
            "100000000,$1000000.00", "-5,-$0.05", "-100,-$1.00", "-12345,-$123.45", "-1,-$0.01"})
    void centsAreFormattedWithTwoDecimalsAndNoSeparators(long cents, String text) {
        assertEquals(text, Money.format(cents));
    }

    @Test
    void theLargestAndSmallestRepresentableCentsFormatWithoutOverflow() {
        assertEquals("$92233720368547758.07", Money.format(Long.MAX_VALUE));
        assertEquals("-$92233720368547758.07", Money.format(Long.MIN_VALUE + 1));
    }

    @Test
    void theMostNegativeCentsFormatAsAWellFormedAmount() {
        assertEquals("-$92233720368547758.08", Money.format(Long.MIN_VALUE));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 5, 99, 100, 101, 999, 12345, 123456789, 100000000000L, Long.MAX_VALUE})
    void formattedPositiveCentsParseBackToTheSameCents(long cents) {
        assertEquals(cents, Money.parseDollarsToCents(Money.format(cents)));
    }
}
