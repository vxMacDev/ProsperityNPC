package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report writer's value rules, string escaping and file names, one case at a time. */
class NavReportJsonFormatTest {

    // ---- scalars ----

    @Test
    void nullBooleansAndWholeNumbersAreWrittenBare() {
        assertEquals("null", NavReportJson.toJson(null));
        assertEquals("true", NavReportJson.toJson(true));
        assertEquals("false", NavReportJson.toJson(false));
        assertEquals("0", NavReportJson.toJson(0));
        assertEquals("-7", NavReportJson.toJson(-7));
        assertEquals("9223372036854775807", NavReportJson.toJson(Long.MAX_VALUE));
        assertEquals("-2147483648", NavReportJson.toJson(Integer.MIN_VALUE));
    }

    @Test
    void finiteFractionsAreWrittenAsTheirJavaText() {
        assertEquals("0.5", NavReportJson.toJson(0.5));
        assertEquals("-1.25", NavReportJson.toJson(-1.25));
        assertEquals("3.0", NavReportJson.toJson(3.0));
        assertEquals("1.5", NavReportJson.toJson(1.5f));
    }

    @Test
    void notANumberAndTheInfinitiesBecomeNullBecauseJsonHasNoSuchValues() {
        assertEquals("null", NavReportJson.toJson(Double.NaN));
        assertEquals("null", NavReportJson.toJson(Double.POSITIVE_INFINITY));
        assertEquals("null", NavReportJson.toJson(Double.NEGATIVE_INFINITY));
        assertEquals("null", NavReportJson.toJson(Float.NaN));
        assertEquals("[null,1.0]", NavReportJson.toJson(List.of(Double.NaN, 1.0)));
    }

    @Test
    void otherNumberTypesUseTheirOwnText() {
        assertEquals("123456789012345678901234567890", NavReportJson.toJson(new BigInteger("123456789012345678901234567890")));
        assertEquals("12.50", NavReportJson.toJson(new BigDecimal("12.50")));
        assertEquals("7", NavReportJson.toJson((short) 7));
        assertEquals("5", NavReportJson.toJson((byte) 5));
    }

    // ---- strings ----

    @ParameterizedTest
    @CsvSource(value = {"plain,\"plain\"", "'',\"\"", "'a b',\"a b\"", "caf\u00E9,\"caf\u00E9\"", "\u20AC,\"\u20AC\""})
    void plainAndNonAsciiTextIsQuotedAsIs(String text, String expected) {
        assertEquals(expected, NavReportJson.toJson(text));
    }

    @Test
    void quoteBackslashAndTheNamedControlCharactersAreEscaped() {
        assertEquals("\"\\\"\"", NavReportJson.toJson("\""));
        assertEquals("\"\\\\\"", NavReportJson.toJson("\\"));
        assertEquals("\"\\n\"", NavReportJson.toJson("\n"));
        assertEquals("\"\\r\"", NavReportJson.toJson("\r"));
        assertEquals("\"\\t\"", NavReportJson.toJson("\t"));
        assertEquals("\"a\\r\\nb\"", NavReportJson.toJson("a\r\nb"));
    }

    @ParameterizedTest
    @CsvSource({"0,\\u0000", "1,\\u0001", "8,\\u0008", "11,\\u000b", "12,\\u000c", "27,\\u001b", "31,\\u001f"})
    void everyOtherControlCharacterBelowSpaceIsAFourDigitLowercaseEscape(int code, String expected) {
        assertEquals("\"" + expected + "\"", NavReportJson.toJson(String.valueOf((char) code)));
    }

    @Test
    void spaceAndDeleteAreNotEscaped() {
        assertEquals("\" \"", NavReportJson.toJson(" "));
        assertEquals("\"\u007f\"", NavReportJson.toJson("\u007f"));
    }

    @Test
    void slashIsNotEscaped() {
        assertEquals("\"a/b\"", NavReportJson.toJson("a/b"));
    }

    @Test
    void anObjectOfNoParticularTypeIsWrittenAsItsToStringInQuotes() {
        assertEquals("\"FAIL\"", NavReportJson.toJson(StreetAudit.Verdict.FAIL));
        assertEquals("\"" + new java.util.UUID(0, 5) + "\"", NavReportJson.toJson(new java.util.UUID(0, 5)));
        assertEquals("\"a\\\"b\"", NavReportJson.toJson(new StringBuilder("a\"b")));
    }

    // ---- containers ----

    @Test
    void emptyContainersAreEmptyBrackets() {
        assertEquals("{}", NavReportJson.toJson(Map.of()));
        assertEquals("[]", NavReportJson.toJson(List.of()));
        assertEquals("[]", NavReportJson.toJson(new int[0]));
        assertEquals("[]", NavReportJson.toJson(Set.of()));
    }

    @Test
    void aMapKeepsItsIterationOrderAndQuotesItsKeys() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("z", 1);
        map.put("a", 2);
        map.put("m n", 3);
        assertEquals("{\"z\":1,\"a\":2,\"m n\":3}", NavReportJson.toJson(map));
    }

    @Test
    void aSortedMapIsWrittenInKeyOrder() {
        Map<String, Integer> map = new TreeMap<>(Map.of("b", 2, "a", 1, "c", 3));
        assertEquals("{\"a\":1,\"b\":2,\"c\":3}", NavReportJson.toJson(map));
    }

    @Test
    void aKeyThatIsNotATextIsWrittenAsItsToString() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put(5, "five");
        map.put(StreetAudit.Code.LATERAL, 2);
        map.put(null, "none");
        assertEquals("{\"5\":\"five\",\"LATERAL\":2,\"null\":\"none\"}", NavReportJson.toJson(map));
    }

    @Test
    void keysAreEscapedLikeValues() {
        assertEquals("{\"a\\\"b\":1}", NavReportJson.toJson(Map.of("a\"b", 1)));
    }

    @Test
    void listsAndSetsAreArraysInIterationOrder() {
        assertEquals("[1,\"two\",null,true]", NavReportJson.toJson(java.util.Arrays.asList(1, "two", null, true)));
        assertEquals("[\"only\"]", NavReportJson.toJson(Set.of("only")));
    }

    @Test
    void anIntArrayIsAnArrayOfNumbers() {
        assertEquals("[3,-4,0]", NavReportJson.toJson(new int[]{3, -4, 0}));
    }

    @Test
    void nestedStructuresKeepTheirShape() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("box", new int[]{1, 2, 3, 4, 5, 6});
        inner.put("tags", List.of("home", "work"));
        List<Object> outer = new ArrayList<>();
        outer.add(inner);
        outer.add(List.of(List.of(), Map.of()));
        assertEquals("[{\"box\":[1,2,3,4,5,6],\"tags\":[\"home\",\"work\"]},[[],{}]]", NavReportJson.toJson(outer));
    }

    // ---- file names ----

    @Test
    void theStampIsUtcWithFourteenDigits() {
        assertEquals("audit-g-19700101000000.json", NavReportJson.fileName("audit", "g", Instant.EPOCH));
        assertEquals("audit-g-20261231235959.json", NavReportJson.fileName("audit", "g", Instant.parse("2026-12-31T23:59:59.999Z")));
    }

    @Test
    void aTimeWithAnOffsetIsStampedInUtc() {
        Instant at = java.time.OffsetDateTime.parse("2026-03-01T01:30:00+05:00").toInstant();
        assertEquals("sweep-g-20260228203000.json", NavReportJson.fileName("sweep", "g", at));
    }

    @ParameterizedTest
    @CsvSource({"a b,a_b", "a/b,a_b", "a\\b,a_b", "a:b,a_b", "a.b,a_b", "é,_", "a*b,a_b", "a?b,a_b", "x\ty,x_y"})
    void anyCharacterOutsideLettersDigitsUnderscoreAndDashBecomesAnUnderscore(String name, String safe) {
        assertEquals(safe + "-" + safe + "-19700101000000.json", NavReportJson.fileName(name, name, Instant.EPOCH));
    }

    @Test
    void dashesAndUnderscoresAndDigitsSurviveInNames() {
        assertEquals("a-1_B-x_y-19700101000000.json", NavReportJson.fileName("a-1_B", "x_y", Instant.EPOCH));
    }

    @Test
    void aNameOfDotsCannotClimbOutOfTheFolder() {
        String file = NavReportJson.fileName("..", "../..", Instant.EPOCH);
        assertEquals("__-_____-19700101000000.json", file);
    }
}
