package com.prosperitysmp.npc.charge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class ChargeCatalogueLoadTest {
    private static Map<String, Object> policy() {
        return new LinkedHashMap<>(Map.of("version", "1", "title", "Tax", "amount", "250", "max-cents", "1000"));
    }

    private static Map<String, Object> block(Map<String, Object> policy) {
        Map<String, Object> limits = new LinkedHashMap<>(Map.of("per-owner-daily-max-cents", "5000", "server-daily-max-cents", "10000"));
        Map<String, Object> policies = new LinkedHashMap<>();
        policies.put("tax", policy);
        return new LinkedHashMap<>(Map.of("enabled", true, "limits", limits, "policies", policies));
    }

    private static Map<String, Object> top(Consumer<Map<String, Object>> change) {
        var b = block(policy());
        change.accept(b);
        return b;
    }

    private static Map<String, Object> withPolicy(Consumer<Map<String, Object>> change) {
        var p = policy();
        change.accept(p);
        return block(p);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> limits(Map<String, Object> block) {
        return (Map<String, Object>) block.get("limits");
    }

    private static ChargeCatalogue.Policy loaded(Map<String, Object> policy) throws ChargeCatalogue.Rejected {
        return ChargeCatalogue.load(block(policy), Map.of()).policy("tax").orElseThrow();
    }

    static Stream<Arguments> refusedBlocks() {
        Map<String, Object> seventeen = new TreeMap<>();
        for (int i = 0; i < 17; i++) seventeen.put("p" + i, policy());
        Map<Object, Object> numericKey = new HashMap<>();
        numericKey.put(5, policy());
        String at = "bills.policies.tax";
        Map<String, Object> nullVar = new HashMap<>();
        nullVar.put("rate", null);
        return Stream.of(
                Arguments.of(top(b -> b.put("colour", "x")), "bills.colour: unknown key"),
                Arguments.of(top(b -> b.put("late-fee", "1")), "bills.late-fee: not supported, there are no late fees or debts"),
                Arguments.of(top(b -> b.put("grace-days", "1")), "bills.grace-days: not supported, there is no grace period; use payable-days"),
                Arguments.of(top(b -> b.put("insurance", Map.of())),
                        "bills.insurance: today's charges keep their own prices and cannot be configured here"),
                Arguments.of(top(b -> b.put("enabled", "yes")), "bills.enabled: must be true or false"),
                Arguments.of(top(b -> b.put("auto-collect-enabled", "true")), "bills.auto-collect-enabled: must be true or false"),
                Arguments.of(top(b -> b.put("on-unpaid", "HOLD-RENT")), "bills.on-unpaid: must be none or hold-rent"),
                Arguments.of(top(b -> b.put("on-unpaid", true)), "bills.on-unpaid: must be none or hold-rent"),
                Arguments.of(top(b -> b.put("payable-days", "0")), "bills.payable-days: must be from 1 to 90"),
                Arguments.of(top(b -> b.put("payable-days", "91")), "bills.payable-days: must be from 1 to 90"),
                Arguments.of(top(b -> b.put("payable-days", 7)),
                        "bills.payable-days: numbers must be quoted, for example \"50\" (YAML read this as Integer)"),
                Arguments.of(top(b -> b.put("payable-days", "7.5")), "bills.payable-days: must be a whole number"),
                Arguments.of(top(b -> b.put("limits", "x")), "bills.limits: must be a section"),
                Arguments.of(top(b -> limits(b).put("extra", "1")), "bills.limits.extra: unknown key"),
                Arguments.of(top(b -> limits(b).remove("server-daily-max-cents")),
                        "bills.limits: per-owner-daily-max-cents and server-daily-max-cents are required once bills are enabled"),
                Arguments.of(top(b -> limits(b).put("per-owner-daily-max-cents", "0")),
                        "bills.limits.per-owner-daily-max-cents: must be from 1 to 100000"),
                Arguments.of(top(b -> b.put("policies", "x")), "bills.policies: must be a section"),
                Arguments.of(top(b -> b.put("policies", seventeen)), "bills.policies: at most 16 policies"),
                Arguments.of(top(b -> b.put("policies", numericKey)), "bills.policies: keys must be text"),
                Arguments.of(top(b -> b.put("policies", Map.of("Bad", policy()))),
                        "bills.policies.Bad: a policy id is a lowercase letter then up to 23 lowercase letters, digits or dashes"),
                Arguments.of(top(b -> b.put("policies", Map.of("a".repeat(25), policy()))), "bills.policies." + "a".repeat(25)
                        + ": a policy id is a lowercase letter then up to 23 lowercase letters, digits or dashes"),
                Arguments.of(top(b -> b.put("policies", Map.of("1tax", policy()))),
                        "bills.policies.1tax: a policy id is a lowercase letter then up to 23 lowercase letters, digits or dashes"),
                Arguments.of(top(b -> b.put("policies", Map.of("utility", policy()))),
                        "bills.policies.utility: today's charges keep their own prices and cannot be configured here"),
                Arguments.of(top(b -> b.put("policies", Map.of("repair", policy()))),
                        "bills.policies.repair: today's charges keep their own prices and cannot be configured here"),
                Arguments.of(top(b -> b.put("policies", Map.of("tax", "x"))), at + ": must be a section"),
                Arguments.of(withPolicy(p -> p.put("payee", "x")),
                        at + ".payee: not supported, configured bills are always paid to the server reserve"),
                Arguments.of(withPolicy(p -> p.put("counts-as-service", "x")),
                        at + ".counts-as-service: not supported, configured bills are not a service charge"),
                Arguments.of(withPolicy(p -> p.put("funds-rent", "x")), at + ".funds-rent: not supported, configured bills never add rent funding"),
                Arguments.of(withPolicy(p -> p.put("colour", "x")), at + ".colour: unknown key"),
                Arguments.of(withPolicy(p -> p.remove("version")), at + ".version: required"),
                Arguments.of(withPolicy(p -> p.put("version", "0")), at + ".version: must be from 1 to 1000000"),
                Arguments.of(withPolicy(p -> p.put("version", "1000001")), at + ".version: must be from 1 to 1000000"),
                Arguments.of(withPolicy(p -> p.remove("title")), at + ".title: required"),
                Arguments.of(withPolicy(p -> p.put("title", 5)), at + ".title: must be text"),
                Arguments.of(withPolicy(p -> p.put("title", "Tax!")), at + ".title: 1 to 32 plain characters"),
                Arguments.of(withPolicy(p -> p.put("title", "T".repeat(33))), at + ".title: 1 to 32 plain characters"),
                Arguments.of(withPolicy(p -> p.put("title", "")), at + ".title: 1 to 32 plain characters"),
                Arguments.of(withPolicy(p -> p.put("title", "Tax\u00a7c")), at + ".title: 1 to 32 plain characters"),
                Arguments.of(withPolicy(p -> p.put("applies-to", "some")), at + ".applies-to: must be all, occupied or vacant"),
                Arguments.of(withPolicy(p -> p.put("classes", "room")), at + ".classes: must be a list"),
                Arguments.of(withPolicy(p -> p.put("classes", List.of(""))), at + ".classes: every entry must be text"),
                Arguments.of(withPolicy(p -> p.put("classes", List.of("x".repeat(129)))), at + ".classes: every entry must be text"),
                Arguments.of(withPolicy(p -> p.put("markets", List.of(5))), at + ".markets: every entry must be text"),
                Arguments.of(withPolicy(p -> p.put("properties", List.of("not-a-uuid"))),
                        at + ".properties: 'not-a-uuid' is not a property UUID"),
                Arguments.of(withPolicy(p -> p.put("properties", List.of("00000000-0000-0000-0000-00000000000A"))),
                        at + ".properties: '00000000-0000-0000-0000-00000000000A' is not a property UUID"),
                Arguments.of(withPolicy(p -> p.put("properties", List.of("0-0-0-0-1"))), at + ".properties: '0-0-0-0-1' is not a property UUID"),
                Arguments.of(withPolicy(p -> p.put("meter", "derived")), at + ".meter: must be flat, derived:<variable> or a registered meter id"),
                Arguments.of(withPolicy(p -> p.put("meter", "derived:condition")),
                        at + ".meter: derived meters count tenants, upgrade_levels, advertising_level, facilities_level or capacity"),
                Arguments.of(withPolicy(p -> p.put("meter", "Power")), at + ".meter: must be flat, derived:<variable> or a registered meter id"),
                Arguments.of(withPolicy(p -> p.put("meter", "p")), at + ".meter: must be flat, derived:<variable> or a registered meter id"),
                Arguments.of(withPolicy(p -> p.put("meter", 5)), at + ".meter: must be text"),
                Arguments.of(withPolicy(p -> p.put("vars", Map.of("Rate", "1"))), at + ".vars.Rate: not a usable name"),
                Arguments.of(withPolicy(p -> p.put("vars", Map.of("min", "1"))), at + ".vars.min: not a usable name"),
                Arguments.of(withPolicy(p -> p.put("vars", Map.of("tenants", "1"))), at + ".vars.tenants: this name is a home variable and cannot be set"),
                Arguments.of(withPolicy(p -> p.put("vars", Map.of("units", "1"))), at + ".vars.units: this name is a home variable and cannot be set"),
                Arguments.of(withPolicy(p -> p.put("vars", Map.of("powered", "1"))), at + ".vars.powered: this name is a home variable and cannot be set"),
                Arguments.of(withPolicy(p -> p.put("vars", Map.of("rate", 0.02))),
                        at + ".vars.rate: numbers must be quoted, for example \"50\" (YAML read this as Double)"),
                Arguments.of(withPolicy(p -> p.put("vars", nullVar)),
                        at + ".vars.rate: numbers must be quoted, for example \"50\" (YAML read this as nothing)"),
                Arguments.of(withPolicy(p -> p.put("vars", Map.of("rate", "1e3"))),
                        at + ".vars.rate: '1e3' is not a number (digits, optionally a dot and up to 6 more digits)"),
                Arguments.of(withPolicy(p -> p.put("vars", "x")), at + ".vars: must be a section"),
                Arguments.of(withPolicy(p -> p.put("rounding", "round")), at + ".rounding: must be floor, ceil or half-up"),
                Arguments.of(withPolicy(p -> p.put("rounding", "half_up")), at + ".rounding: must be floor, ceil or half-up"),
                Arguments.of(withPolicy(p -> p.remove("max-cents")), at + ".max-cents: required"),
                Arguments.of(withPolicy(p -> p.put("max-cents", "0")), at + ".max-cents: must be from 1 to 20000"),
                Arguments.of(withPolicy(p -> p.put("min-cents", "0")), at + ".min-cents: must be from 1 to 1000"),
                Arguments.of(withPolicy(p -> p.put("min-cents", "1001")), at + ".min-cents: must be from 1 to 1000"),
                Arguments.of(withPolicy(p -> p.remove("amount")), at + ".amount: required"),
                Arguments.of(withPolicy(p -> p.put("amount", 250)), at + ".amount: must be text"),
                Arguments.of(withPolicy(p -> p.put("amount", "tenants - 5")), at + ".amount: the result can be below zero (lowest -5)"),
                Arguments.of(withPolicy(p -> p.put("amount", "rate * 2")), at + ".amount: unknown name 'rate'"),
                Arguments.of(withPolicy(p -> p.put("amount", "250 / units")),
                        at + ".amount: division is allowed only by a number or a vars entry"),
                Arguments.of(withPolicy(p -> p.put("amount", "powered * 100")), at + ".amount: unknown name 'powered'"),
                Arguments.of(withPolicy(p -> p.put("amount", "pick(tenants, 1, 2)")), at + ".amount: pick index can be outside 1..2"),
                Arguments.of(withPolicy(p -> p.put("amount", "250 +")), at + ".amount: formula ends too early"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("refusedBlocks")
    void aBadBlockIsRefusedNamingTheKeyAndTheReason(Map<String, Object> raw, String message) {
        var failure = assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(raw, Map.of()));
        assertEquals(message, failure.getMessage());
    }

    @Test void anAbsentBlockIsTheDisabledCatalogue() throws Exception {
        assertSame(ChargeCatalogue.Catalogue.DISABLED, ChargeCatalogue.load(null, null));
        assertSame(ChargeCatalogue.Catalogue.DISABLED, ChargeCatalogue.load(Map.of(), Map.of()));
        var off = ChargeCatalogue.Catalogue.DISABLED;
        assertEquals(new ChargeCatalogue.Catalogue(false, false, ChargeCatalogue.OnUnpaid.NONE, 7, 0, 0, List.of()), off);
        assertFalse(off.active());
    }

    @Test void aNullRecordOfKnownVersionsIsTreatedAsEmpty() throws Exception {
        assertEquals(250, ChargeCatalogue.load(block(policy()), null).policy("tax").orElseThrow().amountCents(Map.of()));
    }

    @Test void aDisabledBlockNeedsNoLimitsButStillLoadsItsPolicies() throws Exception {
        var b = block(policy());
        b.put("enabled", false);
        b.remove("limits");
        var catalogue = ChargeCatalogue.load(b, Map.of());
        assertFalse(catalogue.enabled());
        assertFalse(catalogue.active());
        assertEquals(0, catalogue.ownerDailyMaxCents());
        assertEquals(0, catalogue.serverDailyMaxCents());
        assertEquals(1, catalogue.policies().size());
    }

    @Test void anEnabledBlockWithNoPoliciesIsNotActive() throws Exception {
        var b = block(policy());
        b.remove("policies");
        var catalogue = ChargeCatalogue.load(b, Map.of());
        assertTrue(catalogue.enabled());
        assertFalse(catalogue.active());
        assertEquals(List.of(), catalogue.policies());
        assertTrue(catalogue.policy("tax").isEmpty());
    }

    @Test void payableDaysDefaultsToSevenAndAcceptsBothEnds() throws Exception {
        assertEquals(7, ChargeCatalogue.load(block(policy()), Map.of()).payableDays());
        for (String days : List.of("1", "90")) {
            var b = block(policy());
            b.put("payable-days", days);
            assertEquals(Integer.parseInt(days), ChargeCatalogue.load(b, Map.of()).payableDays());
        }
        var b = block(policy());
        b.put("payable-days", "7.000000");
        assertEquals(7, ChargeCatalogue.load(b, Map.of()).payableDays());
    }

    @Test void onUnpaidAndAutoCollectAreRead() throws Exception {
        var b = block(policy());
        b.put("on-unpaid", "hold-rent");
        b.put("auto-collect-enabled", true);
        var catalogue = ChargeCatalogue.load(b, Map.of());
        assertEquals(ChargeCatalogue.OnUnpaid.HOLD_RENT, catalogue.onUnpaid());
        assertTrue(catalogue.autoCollect());
        b.put("on-unpaid", "none");
        assertEquals(ChargeCatalogue.OnUnpaid.NONE, ChargeCatalogue.load(b, Map.of()).onUnpaid());
    }

    @Test void policiesAreOrderedByIdWhateverTheConfigOrder() throws Exception {
        var b = block(policy());
        Map<String, Object> policies = new LinkedHashMap<>();
        for (String id : List.of("zeta", "alpha", "mid-tax")) policies.put(id, policy());
        b.put("policies", policies);
        assertEquals(List.of("alpha", "mid-tax", "zeta"),
                ChargeCatalogue.load(b, Map.of()).policies().stream().map(ChargeCatalogue.Policy::id).toList());
    }

    @Test void sixteenPoliciesAndTheShortestAndLongestIdsLoad() throws Exception {
        var b = block(policy());
        Map<String, Object> policies = new TreeMap<>();
        for (int i = 0; i < 14; i++) policies.put("p" + i, policy());
        policies.put("a", policy());
        policies.put("a" + "-".repeat(22) + "9", policy());
        b.put("policies", policies);
        var catalogue = ChargeCatalogue.load(b, Map.of());
        assertEquals(16, catalogue.policies().size());
        assertEquals("a", catalogue.policies().getFirst().id());
        assertTrue(catalogue.policy("a" + "-".repeat(22) + "9").isPresent());
    }

    @Test void aPolicyLoadsItsDefaults() throws Exception {
        var p = loaded(policy());
        assertEquals("tax", p.id());
        assertEquals(1, p.version());
        assertEquals("Tax", p.title());
        assertEquals(ChargeCatalogue.AppliesTo.ALL, p.appliesTo());
        assertEquals(List.of(), p.classes());
        assertEquals(List.of(), p.markets());
        assertEquals(List.of(), p.properties());
        assertEquals(new ChargeCatalogue.MeterSpec("flat", null), p.meter());
        assertEquals(Map.of(), p.vars());
        assertEquals(ChargeCatalogue.Rounding.FLOOR, p.rounding());
        assertEquals(1, p.minCents());
        assertEquals(1000, p.maxCents());
        assertEquals(64, p.sha256().length());
        assertEquals(Map.of(), p.bindsAt());
    }

    @Test void theWidestTitleAndVersionLoad() throws Exception {
        var p = policy();
        p.put("title", "A-z 0.9,'()&/+ abcdefghijklmnopq");
        p.put("version", "1000000");
        var loaded = loaded(p);
        assertEquals(32, loaded.title().length());
        assertEquals(1_000_000, loaded.version());
    }

    @Test void aVacantPolicyCoversOnlyEmptyHomes() throws Exception {
        var p = policy();
        p.put("applies-to", "vacant");
        var loaded = loaded(p);
        UUID home = new UUID(0, 1);
        assertTrue(loaded.appliesTo(home, "room", "town", 0));
        assertFalse(loaded.appliesTo(home, "room", "town", 1));
        var all = loaded(policy());
        assertTrue(all.appliesTo(home, "room", "town", 0));
        assertTrue(all.appliesTo(home, "anything", "anywhere", 4));
    }

    @Test void aLowercasePropertyUuidLoads() throws Exception {
        var p = policy();
        UUID home = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
        p.put("properties", List.of(home.toString()));
        assertEquals(List.of(home), loaded(p).properties());
    }

    @Test void meterSettingsGiveTheirIdAndKind() throws Exception {
        var external = policy();
        external.put("meter", "pw");
        var e = loaded(external).meter();
        assertEquals(new ChargeCatalogue.MeterSpec("external", "pw"), e);
        assertTrue(e.external());
        assertEquals("pw", e.id());
        var derived = policy();
        derived.put("meter", "derived:capacity");
        var d = loaded(derived).meter();
        assertFalse(d.external());
        assertEquals("derived:capacity", d.id());
        assertEquals("flat", new ChargeCatalogue.MeterSpec("flat", null).id());
        assertEquals("p" + "x".repeat(31), loaded(withMeter("p" + "x".repeat(31))).meter().id());
    }

    private static Map<String, Object> withMeter(String meter) {
        var p = policy();
        p.put("meter", meter);
        return p;
    }

    @Test void variablesDependOnTheMeterKind() {
        var flat = ChargeCatalogue.variablesFor(new ChargeCatalogue.MeterSpec("flat", null));
        assertEquals(11, flat.size());
        assertEquals(new ChargeVariables.Range(1, 1), flat.get("units"));
        assertFalse(flat.containsKey("powered"));
        var derived = ChargeCatalogue.variablesFor(new ChargeCatalogue.MeterSpec("derived", "capacity"));
        assertEquals(new ChargeVariables.Range(1, 4), derived.get("units"));
        assertFalse(derived.containsKey("powered"));
        var external = ChargeCatalogue.variablesFor(new ChargeCatalogue.MeterSpec("external", "pw"));
        assertEquals(12, external.size());
        assertEquals(new ChargeVariables.Range(0, 1_000_000_000), external.get("units"));
        assertEquals(new ChargeVariables.Range(0, 1), external.get("powered"));
        assertThrows(UnsupportedOperationException.class, () -> external.remove("units"));
    }

    @Test void anExternalMeterPolicyMayReadPowered() throws Exception {
        var p = withMeter("power");
        p.put("amount", "powered * 300");
        var loaded = loaded(p);
        assertEquals(300, loaded.amountCents(Map.of("powered", 1L)));
        assertEquals(0, loaded.amountCents(Map.of("powered", 0L)));
        assertEquals("'powered' is outside its range",
                assertThrows(FormulaException.class, () -> loaded.amountCents(Map.of("powered", 2L))).getMessage());
    }

    @Test void theLoadMessageReportsWhereTheCapBinds() throws Exception {
        var p = policy();
        p.put("amount", "75 * tenants");
        p.put("max-cents", "200");
        var loaded = loaded(p);
        assertEquals(Map.of("tenants", 3L), loaded.bindsAt());
        assertThrows(UnsupportedOperationException.class, () -> loaded.bindsAt().clear());
    }

    @Test void amountsReadFactsButNeverOverrideVars() throws Exception {
        var p = withMeter("derived:tenants");
        p.put("vars", Map.of("rate", "2"));
        p.put("amount", "rate * units");
        var loaded = loaded(p);
        assertEquals(6, loaded.amountCents(Map.of("units", 3L, "rate", 100L)));
        assertEquals("no value for 'units'", assertThrows(FormulaException.class, () -> loaded.amountCents(Map.of())).getMessage());
        assertEquals("'units' is outside its range",
                assertThrows(FormulaException.class, () -> loaded.amountCents(Map.of("units", 5L))).getMessage());
    }

    @Test void eachRoundingRuleAppliesToAnExactHalf() throws Exception {
        for (var rule : Map.of("floor", 0L, "ceil", 1L, "half-up", 1L).entrySet()) {
            var p = withMeter("derived:tenants");
            p.put("amount", "units / 2");
            p.put("rounding", rule.getKey());
            assertEquals(rule.getValue(), loaded(p).amountCents(Map.of("units", 1L)), rule.getKey());
        }
    }

    @Test void aRecordedVersionWithDifferentTermsIsRefusedAndTheSameTermsAccepted() throws Exception {
        var first = loaded(policy());
        var same = ChargeCatalogue.load(block(policy()), Map.of("tax@1", first.sha256()));
        assertEquals(first.sha256(), same.policy("tax").orElseThrow().sha256());
        var changed = policy();
        changed.put("title", "Taxes");
        var failure = assertThrows(ChargeCatalogue.Rejected.class,
                () -> ChargeCatalogue.load(block(changed), Map.of("tax@1", first.sha256())));
        assertEquals("bills.policies.tax.version: version 1 was already used for a different definition; raise the version",
                failure.getMessage());
        assertEquals(first.sha256(), ChargeCatalogue.load(block(policy()), Map.of("tax@2", "x")).policy("tax").orElseThrow().sha256());
    }

    @Test void theDefinitionHashCoversEveryTerm() throws Exception {
        String base = loaded(policy()).sha256();
        List<Consumer<Map<String, Object>>> changes = List.of(
                p -> p.put("version", "2"), p -> p.put("title", "Taxes"), p -> p.put("applies-to", "occupied"),
                p -> p.put("classes", List.of("room")), p -> p.put("markets", List.of("town")),
                p -> p.put("properties", List.of(new UUID(0, 1).toString())), p -> p.put("meter", "derived:tenants"),
                p -> p.put("amount", "251"), p -> p.put("vars", Map.of("unused", "1")), p -> p.put("rounding", "ceil"),
                p -> p.put("min-cents", "2"), p -> p.put("max-cents", "999"));
        for (var change : changes) {
            var p = policy();
            change.accept(p);
            assertNotEquals(base, loaded(p).sha256(), p.toString());
        }
        var explicit = policy();
        explicit.put("applies-to", "all");
        explicit.put("rounding", "floor");
        explicit.put("min-cents", "1");
        explicit.put("meter", "flat");
        assertEquals(base, loaded(explicit).sha256());
    }

    @Test void theCanonicalDefinitionIsAFixedJsonList() {
        String text = ChargeCatalogue.canonical("tax", 1, "Tax", ChargeCatalogue.AppliesTo.ALL, List.of(), List.of(), List.of(),
                new ChargeCatalogue.MeterSpec("flat", null), ChargeFormula.parse("100"), new TreeMap<>(),
                ChargeCatalogue.Rounding.FLOOR, 1, 1000);
        assertEquals("[\"bill-policy-v2\",\"tax\",1,\"Tax\",\"all\",[],[],[],\"flat\",\"100\",{},\"floor\",1,1000]", text);
    }

    @Test void theCanonicalDefinitionSortsSelectorsAndWritesExactConstants() {
        var vars = new TreeMap<String, Rational>(Map.of("rate", Rational.parse("0.25")));
        String text = ChargeCatalogue.canonical("power", 3, "Power & tax", ChargeCatalogue.AppliesTo.OCCUPIED,
                List.of("room", "house"), List.of("town"), List.of(new UUID(0, 1)),
                new ChargeCatalogue.MeterSpec("derived", "tenants"), ChargeFormula.parse("rate * units"), vars,
                ChargeCatalogue.Rounding.HALF_UP, 5, 200);
        assertEquals("[\"bill-policy-v2\",\"power\",3,\"Power \\u0026 tax\",\"occupied\",[\"house\",\"room\"],[\"town\"],"
                + "[\"00000000-0000-0000-0000-000000000001\"],\"derived:tenants\",\"rate*units\",{\"rate\":\"1/4\"},\"half_up\",5,200]", text);
    }

    @Test void aLoadedPolicyHashIsTheShaOfItsCanonicalDefinition() throws Exception {
        var p = withMeter("derived:tenants");
        p.put("version", "3");
        p.put("title", "Power & tax");
        p.put("applies-to", "occupied");
        p.put("classes", List.of("room", "house"));
        p.put("markets", List.of("town"));
        p.put("properties", List.of(new UUID(0, 1).toString()));
        p.put("vars", Map.of("rate", "0.25"));
        p.put("amount", "rate * units");
        p.put("rounding", "half-up");
        p.put("min-cents", "5");
        p.put("max-cents", "200");
        String expected = ChargeCatalogue.sha256("[\"bill-policy-v2\",\"power\",3,\"Power \\u0026 tax\",\"occupied\",[\"house\",\"room\"],"
                + "[\"town\"],[\"00000000-0000-0000-0000-000000000001\"],\"derived:tenants\",\"rate*units\",{\"rate\":\"1/4\"},"
                + "\"half_up\",5,200]");
        var b = block(p);
        b.put("policies", Map.of("power", p));
        assertEquals(expected, ChargeCatalogue.load(b, Map.of()).policy("power").orElseThrow().sha256());
    }

    @Test void sha256IsLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ChargeCatalogue.sha256("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ChargeCatalogue.sha256(""));
    }

    @Test void numberAndWholeNameTheKeyAndTheYamlType() throws Exception {
        assertEquals(Rational.parse("1.5"), ChargeCatalogue.number("k", "1.5"));
        assertEquals("k: numbers must be quoted, for example \"50\" (YAML read this as Long)",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.number("k", 5L)).getMessage());
        assertEquals("k: numbers must be quoted, for example \"50\" (YAML read this as Boolean)",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.number("k", true)).getMessage());
        assertEquals(10, ChargeCatalogue.whole("k", "10", 10, 20));
        assertEquals(20, ChargeCatalogue.whole("k", "20", 10, 20));
        assertEquals("k: must be from 10 to 20",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.whole("k", "21", 10, 20)).getMessage());
        assertEquals("k: must be from 10 to 20",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.whole("k", "9", 10, 20)).getMessage());
        assertEquals("k: must be a whole number",
                assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.whole("k", "10.5", 10, 20)).getMessage());
    }

    @Test void plainCopiesNestedSectionsKeepingScalarTypes() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("top:\n  name: '1'\n  inner:\n    flag: true\n    count: 5\n");
        assertEquals(Map.of("top", Map.of("name", "1", "inner", Map.of("flag", true, "count", 5))), ChargeCatalogue.plain(yaml));
    }

    @Test void aBlockMayHoldConfigurationSectionsDirectly() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("policies:\n  tax:\n    version: '1'\n    title: Tax\n    amount: '250'\n    max-cents: '1000'\n");
        var catalogue = ChargeCatalogue.load(Map.of("policies", yaml.getConfigurationSection("policies")), Map.of());
        assertEquals(250, catalogue.policy("tax").orElseThrow().amountCents(Map.of()));
    }

    @Test void theRefusedKeySetsAreFixed() {
        assertEquals(java.util.Set.of("funds-rent", "payee", "counts-as-service", "grace-days", "late-fee"),
                ChargeCatalogue.FORBIDDEN.keySet());
        assertEquals(java.util.Set.of("utility", "utilities", "upgrade", "upgrades", "maintain", "maintenance", "repair",
                "repairs", "insurance", "eviction"), ChargeCatalogue.EXISTING_CHARGES);
        assertEquals(16, ChargeCatalogue.MAX_POLICIES);
        assertEquals(32, ChargeCatalogue.MAX_TITLE);
        assertEquals(90, ChargeCatalogue.MAX_PAYABLE_DAYS);
    }
}
