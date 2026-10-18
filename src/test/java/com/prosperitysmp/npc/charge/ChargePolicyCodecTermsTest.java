package com.prosperitysmp.npc.charge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChargePolicyCodecTermsTest {
    private static ChargeCatalogue.Policy policy(Map<String, Object> definition) throws Exception {
        return ChargeCatalogue.load(Map.of("policies", Map.of("power", definition)), Map.of()).policy("power").orElseThrow();
    }

    private static Map<String, Object> definition() {
        return new LinkedHashMap<>(Map.of("version", "3", "title", "Power", "meter", "derived:tenants",
                "amount", "ceil(rate * units) + base + hundred", "classes", List.of("room"),
                "vars", Map.of("rate", "0.025", "base", "1.000000", "hundred", "100"), "max-cents", "1000"));
    }

    @Test void theStoredTermsAreAFixedJsonObject() throws Exception {
        assertEquals("{\"version\":\"3\",\"title\":\"Power\",\"applies-to\":\"all\",\"classes\":[\"room\"],\"markets\":[],"
                + "\"properties\":[],\"meter\":\"derived:tenants\",\"amount\":\"ceil(rate * units) + base + hundred\","
                + "\"vars\":{\"base\":\"1\",\"hundred\":\"100\",\"rate\":\"0.025\"},\"rounding\":\"floor\",\"min-cents\":\"1\","
                + "\"max-cents\":\"1000\"}", ChargePolicyCodec.encode(policy(definition())));
    }

    @Test void theStoredTermsKeepTheSelectorsAndRoundingAsConfigured() throws Exception {
        var d = definition();
        d.put("applies-to", "vacant");
        d.put("rounding", "ceil");
        d.put("min-cents", "5");
        d.put("markets", List.of("east", "west"));
        String json = ChargePolicyCodec.encode(policy(d));
        assertTrue(json.contains("\"applies-to\":\"vacant\""), json);
        assertTrue(json.contains("\"rounding\":\"ceil\""), json);
        assertTrue(json.contains("\"min-cents\":\"5\""), json);
        assertTrue(json.contains("\"markets\":[\"east\",\"west\"]"), json);
    }

    @Test void aHalfUpPolicyRoundTripsThroughItsStoredTerms() throws Exception {
        var d = definition();
        d.put("rounding", "half-up");
        var original = policy(d);
        var restored = ChargePolicyCodec.decode("power", 3, original.sha256(), ChargePolicyCodec.encode(original));
        assertEquals(original.sha256(), restored.sha256());
        assertEquals(original.amountCents(Map.of("units", 4L)), restored.amountCents(Map.of("units", 4L)));
    }

    @Test void decodingTheStoredTermsGivesBackTheSamePolicy() throws Exception {
        var original = policy(definition());
        var restored = ChargePolicyCodec.decode("power", 3, original.sha256(), ChargePolicyCodec.encode(original));
        assertEquals(original.sha256(), restored.sha256());
        assertEquals(original.vars(), restored.vars());
        assertEquals(original.amountCents(Map.of("units", 4L)), restored.amountCents(Map.of("units", 4L)));
        assertEquals(1 + 1 + 100, restored.amountCents(Map.of("units", 4L)));
    }

    @Test void refusalsNameThePolicyAndVersionOnly() throws Exception {
        var original = policy(definition());
        String json = ChargePolicyCodec.encode(original);
        assertEquals("Stored terms are missing for power@3",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("power", 3, original.sha256(), null)).getMessage());
        assertEquals("Stored terms are invalid for other@3",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("other", 3, original.sha256(), json)).getMessage());
        assertEquals("Stored terms are invalid for power@4",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("power", 4, original.sha256(), json)).getMessage());
        assertEquals("Stored terms are too large for power@3", assertThrows(ChargeCatalogue.Rejected.class,
                () -> ChargePolicyCodec.decode("power", 3, original.sha256(), "x".repeat(ChargePolicyCodec.MAX_BYTES + 1))).getMessage());
    }

    @Test void theSizeLimitCountsUtf8Bytes() throws Exception {
        var original = policy(definition());
        String multiByte = "é".repeat(ChargePolicyCodec.MAX_BYTES / 2 + 1);
        assertEquals("Stored terms are too large for power@3",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("power", 3, original.sha256(), multiByte)).getMessage());
    }

    @Test void storedTermsThatAreNotStrictJsonAreRefused() throws Exception {
        var original = policy(definition());
        String json = ChargePolicyCodec.encode(original);
        for (String bad : List.of(json.replace("\"version\":\"3\"", "version:\"3\""), json.replace("\"", "'"),
                json.substring(0, json.length() - 1), json + "\n{}"))
            assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("power", 3, original.sha256(), bad), bad);
    }

    @Test void aPolicyTooLargeToStoreIsRefusedWhenEncoded() throws Exception {
        List<String> classes = new ArrayList<>();
        for (int i = 0; i < 8_300; i++) classes.add(String.format("%0128d", i));
        var d = definition();
        d.put("classes", classes);
        var big = policy(d);
        var failure = assertThrows(IllegalArgumentException.class, () -> ChargePolicyCodec.encode(big));
        assertEquals("Stored bill policy exceeds one MiB", failure.getMessage());
    }

    @Test void theSizeLimitIsOneMebibyte() {
        assertEquals(1_048_576, ChargePolicyCodec.MAX_BYTES);
    }
}
