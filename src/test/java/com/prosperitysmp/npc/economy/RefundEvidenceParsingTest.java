package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The strict readers behind refund evidence: the flat JSON object, decimal balances and the evidence fields. */
class RefundEvidenceParsingTest {
    private static final UUID PLAYER = new UUID(7, 1);
    private static final UUID PAYMENT = new UUID(7, 9);

    private static Map<String, String> fields(String json) {
        return RefundEvidence.fields(json.getBytes(StandardCharsets.UTF_8));
    }

    private static long bits(String decimal) {
        return Double.doubleToRawLongBits(new BigDecimal(decimal).doubleValue());
    }

    @Test
    void anEmptyObjectHasNoFields() {
        assertEquals(Map.of(), fields("{}"));
        assertEquals(Map.of(), fields("  {   }\n"));
    }

    @Test
    void stringsKeepTheirQuotesAndNumbersTheirDigits() {
        assertEquals(Map.of("account", "\"abc\"", "after_bits", "-42", "x", "\"\""),
                fields("{\"account\":\"abc\",\"after_bits\":-42,\"x\":\"\"}"));
    }

    @Test
    void aNineteenDigitNumberIsReadAndATwentyDigitOneIsRefused() {
        assertEquals("9223372036854775807", fields("{\"n\":9223372036854775807}").get("n"));
        assertThrows(IllegalArgumentException.class, () -> fields("{\"n\":12345678901234567890}"));
    }

    @Test
    void aThirtyTwoCharacterNameIsReadAndALongerOneIsRefused() {
        String name = "a".repeat(32);
        assertEquals("1", fields("{\"" + name + "\":1}").get(name));
        assertThrows(IllegalArgumentException.class, () -> fields("{\"" + name + "a\":1}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1,}", "{,\"a\":1}", "{\"A\":1}", "{\"a-b\":1}", "{\"a\":\"x\\\"y\"}", "{\"a\":true}",
            "{\"a\":null}", "{\"a\":+1}", "{\"a\":1 \"b\":2}", "{\"a\" 1}", "\ufeff{\"a\":1}", "{\"a\":1}x", "x{\"a\":1}",
            "{\"a\":[1]}", "{\"\":1}", "{'a':1}"})
    void anythingButAFlatObjectOfNamedStringsAndWholeNumbersIsRefused(String json) {
        assertThrows(IllegalArgumentException.class, () -> fields(json), json);
    }

    @Test
    void aRepeatedFieldIsRefusedWithItsOwnMessage() {
        assertEquals("refund-observed.json repeats a field",
                assertThrows(IllegalArgumentException.class, () -> fields("{\"a\":1,\"a\":1}")).getMessage());
        assertEquals("refund-observed.json must be one JSON object",
                assertThrows(IllegalArgumentException.class, () -> fields("[]")).getMessage());
        assertEquals("refund-observed.json is not a flat object",
                assertThrows(IllegalArgumentException.class, () -> fields("{\"a\":1,}")).getMessage());
    }

    @Test
    void bitsOfReadsTheNearestDoubleOfATrimmedDecimal() {
        assertEquals(bits("10.5"), RefundEvidence.bitsOf(" 10.5 \t"));
        assertEquals(bits("57492.488404927248686"), RefundEvidence.bitsOf("57492.488404927248686"));
        assertEquals(bits("100"), RefundEvidence.bitsOf("1e2"));
        assertEquals(bits("-3.25"), RefundEvidence.bitsOf("-3.25"));
    }

    @Test
    void aDecimalNegativeZeroIsTheSameBalanceAsZero() {
        assertEquals(Double.doubleToRawLongBits(0.0), RefundEvidence.bitsOf("-0.0"));
        assertEquals(Double.doubleToRawLongBits(0.0), RefundEvidence.bitsOf("0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "1,5", "1.2.3", "$5", "NaN", "Infinity", "0x10"})
    void aBalanceThatIsNotADecimalIsRefused(String text) {
        assertEquals("Invalid balance: not a decimal",
                assertThrows(IllegalArgumentException.class, () -> RefundEvidence.bitsOf(text)).getMessage());
    }

    @Test
    void reportedAsAcceptsTheNearestDoubleOrOneStepBelowIt() {
        long nearest = bits("85.00");
        assertTrue(RefundEvidence.reportedAs(nearest, nearest));
        assertTrue(RefundEvidence.reportedAs(nearest, Double.doubleToRawLongBits(Math.nextDown(85.0))));
        assertFalse(RefundEvidence.reportedAs(nearest, Double.doubleToRawLongBits(Math.nextUp(85.0))));
        assertFalse(RefundEvidence.reportedAs(nearest, Double.doubleToRawLongBits(Math.nextDown(Math.nextDown(85.0)))));
        assertFalse(RefundEvidence.reportedAs(nearest, bits("84.99")));
    }

    @Test
    void oneStepBelowZeroIsTheSmallestNegativeDouble() {
        long zero = Double.doubleToRawLongBits(0.0);
        assertTrue(RefundEvidence.reportedAs(zero, Double.doubleToRawLongBits(-Double.MIN_VALUE)));
        assertFalse(RefundEvidence.reportedAs(zero, Double.doubleToRawLongBits(-0.0)));
    }

    @Test
    void evidenceBitsMustBePresentAndWhole() {
        byte[] observed = ("{\"account\":\"" + PLAYER + "\",\"payment\":\"" + PAYMENT + "\",\"before_bits\":1,\"after_bits\":2}")
                .getBytes(StandardCharsets.UTF_8);
        Properties missing = new Properties();
        missing.setProperty("refund_completed", "true");
        assertEquals("Missing evidence field: refund_before_bits", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(missing, PLAYER, PAYMENT, false, observed)).getMessage());
        Properties blank = new Properties();
        blank.setProperty("refund_completed", "true");
        blank.setProperty("refund_before_bits", " ");
        assertEquals("Missing evidence field: refund_before_bits", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(blank, PLAYER, PAYMENT, false, observed)).getMessage());
        Properties text = new Properties();
        text.setProperty("refund_completed", "true");
        text.setProperty("refund_before_bits", "1");
        text.setProperty("refund_after_bits", "2.0");
        assertEquals("Invalid evidence field: refund_after_bits", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(text, PLAYER, PAYMENT, false, observed)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "yes", "1", " true"})
    void onlyTheExactTextTrueMarksARefundCompleted(String flag) {
        Properties p = new Properties();
        p.setProperty("refund_completed", flag);
        assertEquals("Completed refund required", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(p, PLAYER, PAYMENT, false, new byte[0])).getMessage());
    }

    @Test
    void theSavedBalanceLineAllowsTrailingSpacesButNoCommentIndentOrEmptyValue() {
        assertEquals(bits("12.5"), RefundEvidence.savedBalanceBits("money: 12.5   \n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.savedBalanceBits("money: 12.5 # note\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.savedBalanceBits("  money: 12.5\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.savedBalanceBits("money:\n".getBytes(StandardCharsets.UTF_8)));
    }
}
