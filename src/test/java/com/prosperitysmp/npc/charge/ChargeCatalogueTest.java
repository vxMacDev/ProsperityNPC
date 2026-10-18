package com.prosperitysmp.npc.charge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChargeCatalogueTest {
    private static Map<String, Object> policy() {
        return new LinkedHashMap<>(Map.of("version", "1", "title", "Electricity", "amount", "250", "max-cents", "1000"));
    }

    private static Map<String, Object> block(Map<String, Object> policy) {
        return new LinkedHashMap<>(Map.of("enabled", true, "auto-collect-enabled", false,
                "limits", Map.of("per-owner-daily-max-cents", "5000", "server-daily-max-cents", "10000"),
                "policies", Map.of("electricity", policy)));
    }

    @Test void anAbsentBlockIsDisabledAndAConfiguredBillKeepsExplicitLimits() throws Exception {
        assertSame(ChargeCatalogue.Catalogue.DISABLED, ChargeCatalogue.load(Map.of(), Map.of()));
        var catalogue = ChargeCatalogue.load(block(policy()), Map.of());
        assertTrue(catalogue.active());
        assertFalse(catalogue.autoCollect());
        assertEquals(ChargeCatalogue.OnUnpaid.NONE, catalogue.onUnpaid());
        assertEquals(5000, catalogue.ownerDailyMaxCents());
        assertEquals(10000, catalogue.serverDailyMaxCents());
        assertEquals(250, catalogue.policy("electricity").orElseThrow().amountCents(Map.of()));
        var missingLimits = block(policy());
        missingLimits.remove("limits");
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(missingLimits, Map.of()));
    }

    @Test void actualYamlNumericScalarsAreRejectedWithTheKeyNamed() throws Exception {
        for (String scalar : List.of("050", "0x10", "1:30", "0.02")) {
            var yaml = new YamlConfiguration();
            yaml.loadFromString("vars:\n  rate: " + scalar + "\n");
            var p = policy();
            p.put("amount", "rate");
            p.put("vars", ChargeCatalogue.plain(yaml.getConfigurationSection("vars")));
            var failure = assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(block(p), Map.of()), scalar);
            assertTrue(failure.getMessage().contains("bills.policies.electricity.vars.rate"));
        }
    }

    @Test void anExistingVersionCannotAcquireDifferentTerms() throws Exception {
        var first = ChargeCatalogue.load(block(policy()), Map.of()).policy("electricity").orElseThrow();
        var known = Map.of("electricity@1", first.sha256());
        var changed = policy();
        changed.put("amount", "251");
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(block(changed), known));
        changed.put("version", "2");
        assertEquals(2, ChargeCatalogue.load(block(changed), known).policy("electricity").orElseThrow().version());
        var spaced = policy();
        spaced.put("amount", " 250 ");
        assertEquals(first.sha256(), ChargeCatalogue.load(block(spaced), known).policy("electricity").orElseThrow().sha256());
    }

    @Test void aCommaInsideOneSelectorCannotMasqueradeAsTwoSelectors() throws Exception {
        var original = policy();
        original.put("classes", List.of("house,room"));
        var first = ChargeCatalogue.load(block(original), Map.of()).policy("electricity").orElseThrow();
        var changed = policy();
        changed.put("classes", List.of("room", "house"));
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(block(changed),
                Map.of("electricity@1", first.sha256())));
    }

    @Test void selectorsCannotMoveBetweenFieldsWithoutChangingTheVersion() throws Exception {
        var original = policy();
        original.put("classes", List.of("room|town"));
        original.put("markets", List.of("east"));
        var first = ChargeCatalogue.load(block(original), Map.of()).policy("electricity").orElseThrow();
        var changed = policy();
        changed.put("classes", List.of("room"));
        changed.put("markets", List.of("town|east"));
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(block(changed),
                Map.of("electricity@1", first.sha256())));
    }

    @Test void selectorsAndRoundingUseTheCapturedFacts() throws Exception {
        UUID home = new UUID(0, 6501);
        var p = policy();
        p.put("classes", List.of("room"));
        p.put("markets", List.of("town"));
        p.put("properties", List.of(home.toString()));
        p.put("applies-to", "occupied");
        p.put("meter", "derived:tenants");
        p.put("vars", Map.of("rate", "1.25"));
        p.put("amount", "rate * units");
        for (String rounding : List.of("floor", "ceil", "half-up")) {
            p.put("rounding", rounding);
            var loaded = ChargeCatalogue.load(block(p), Map.of()).policy("electricity").orElseThrow();
            assertTrue(loaded.appliesTo(home, "room", "town", 1));
            assertFalse(loaded.appliesTo(home, "room", "town", 0));
            assertFalse(loaded.appliesTo(new UUID(0, 6502), "room", "town", 1));
            assertFalse(loaded.appliesTo(home, "house", "town", 1));
            assertFalse(loaded.appliesTo(home, "room", "elsewhere", 1));
            assertEquals(rounding.equals("floor") ? 3 : 4, loaded.amountCents(Map.of("units", 3L)));
            assertThrows(FormulaException.class, () -> loaded.amountCents(Map.of("units", 5L)));
        }
    }

    @Test void disabledDefinitionsAreStillValidatedAndCannotReplaceExistingCharges() throws Exception {
        for (String badKey : List.of("payee", "funds-rent", "late-fee", "unknown")) {
            var p = policy();
            p.put(badKey, "anything");
            var b = block(p);
            b.put("enabled", false);
            assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(b, Map.of()), badKey);
        }
        var b = block(policy());
        b.put("policies", Map.of("utility", policy()));
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(b, Map.of()));
        var invalid = policy();
        invalid.put("min-cents", "1001");
        assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(block(invalid), Map.of()));
    }
}
