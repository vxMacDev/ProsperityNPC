package com.prosperitysmp.npc.charge;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChargePolicyCodecTest {
    private static final UUID HOME = new UUID(0, 46);

    private ChargeCatalogue.Policy policy() throws Exception {
        var definition = Map.of("version", "3", "title", "Power & tax", "meter", "derived:tenants",
                "amount", "ceil(rate * units) + base", "vars", Map.of("rate", "0.025", "base", "1.000000",
                        "unused", "999999999999.999999"),
                "max-cents", "1000", "classes", List.of("room|town", "house,room", "quoted\"room"),
                "markets", List.of("east,west", "up|down"), "properties", List.of(HOME.toString()));
        return ChargeCatalogue.load(Map.of("policies", Map.of("power", definition)), Map.of()).policy("power").orElseThrow();
    }

    @Test void storedTermsRestoreTheSameHashExactVarsAndAmounts() throws Exception {
        var original = policy();
        String json = ChargePolicyCodec.encode(original);
        var restored = ChargePolicyCodec.decode(original.id(), original.version(), original.sha256(), json);
        assertEquals(original.sha256(), restored.sha256());
        assertEquals(original.vars(), restored.vars());
        assertEquals(original.formula().toString(), restored.formula().toString());
        assertEquals(2, restored.amountCents(Map.of("units", 3L)));
        assertTrue(restored.appliesTo(HOME, "room|town", "east,west", 1));
        assertFalse(restored.appliesTo(HOME, "room", "east,west", 1));
        assertEquals(json, ChargePolicyCodec.encode(restored));
    }

    @Test void aDifferentVersionOrHashCannotRestoreTheseTerms() throws Exception {
        var original = policy();
        String json = ChargePolicyCodec.encode(original);
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("power", 2, original.sha256(), json));
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("power", 3, "a".repeat(64), json));
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("other", 3, original.sha256(), json));
    }

    @Test void missingMalformedDeepAndOversizedDocumentsStayUnknown() throws Exception {
        var original = policy();
        String json = ChargePolicyCodec.encode(original);
        for (String bad : List.of("null", "{}", "[]", "not json", json + " {}",
                "{\"x\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}",
                " ".repeat(ChargePolicyCodec.MAX_BYTES + 1))) {
            assertThrows(ChargeCatalogue.Rejected.class, () -> ChargePolicyCodec.decode("power", 3, original.sha256(), bad));
        }
        var missing = assertThrows(ChargeCatalogue.Rejected.class,
                () -> ChargePolicyCodec.decode("power", 3, original.sha256(), null));
        assertTrue(missing.getMessage().contains("missing"));
    }
}
