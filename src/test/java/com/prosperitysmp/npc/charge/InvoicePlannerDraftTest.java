package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.charge.meter.MeterRead;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoicePlannerDraftTest {
    private static final String SHA = "b".repeat(64);

    private static ChargeCatalogue.Policy policy(String id, String meter, String amount, Map<String, Object> extra) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>(Map.of("version", "1", "title", "Bill", "amount", amount,
                "max-cents", "5000", "meter", meter));
        p.putAll(extra);
        return ChargeCatalogue.load(Map.of("policies", Map.of(id, p)), Map.of()).policy(id).orElseThrow();
    }

    private static ChargeCatalogue.Policy policy(String id, String meter, String amount) throws Exception {
        return policy(id, meter, amount, Map.of());
    }

    private static InvoicePlanner.Plan plan(List<ChargeCatalogue.Policy> policies, Map<String, Long> facts,
                                            Map<String, MeterRead.Frozen> readings, Map<String, Long> cumulative) {
        return InvoicePlanner.plan(policies, facts, readings, Map.of(), cumulative);
    }

    @Test void aDerivedMeterCountsTheHomeFact() throws Exception {
        var p = policy("tenant-tax", "derived:tenants", "units * 10");
        var result = plan(List.of(p), Map.of("tenants", 3L), Map.of(), Map.of());
        assertEquals(List.of(), result.skipped());
        var draft = result.drafts().getFirst();
        assertEquals(p, draft.policy());
        assertEquals("derived:tenants", draft.meterId());
        assertEquals(3L, draft.meterUnits());
        assertNull(draft.meterPowered());
        assertNull(draft.meterDetailSha());
        assertEquals("{\"units\":3}", draft.inputsJson());
        assertEquals(30, draft.rawCents());
    }

    @Test void aDerivedMeterWithoutItsFactIsSkippedAsAFormulaProblem() throws Exception {
        var p = policy("tenant-tax", "derived:tenants", "units * 10");
        var result = plan(List.of(p), Map.of(), Map.of(), Map.of());
        assertEquals(List.of(new InvoicePlanner.Skipped("tenant-tax", false, "no value for 'tenants'")), result.skipped());
        assertEquals(List.of(), result.drafts());
    }

    @Test void aFlatMeterUsesOneUnit() throws Exception {
        var result = plan(List.of(policy("fee", "flat", "units * 125")), Map.of(), Map.of(), Map.of());
        var draft = result.drafts().getFirst();
        assertEquals("flat", draft.meterId());
        assertEquals(1L, draft.meterUnits());
        assertEquals(125, draft.rawCents());
        assertEquals("{\"units\":1}", draft.inputsJson());
    }

    @Test void theInputsRecordEveryFactTheFormulaReadsSortedAndWithoutVars() throws Exception {
        var p = policy("mixed", "derived:capacity", "rate * units + tenants + condition", Map.of("vars", Map.of("rate", "2")));
        var facts = Map.of("tenants", 3L, "capacity", 4L, "condition", 90L, "owner_homes", 7L);
        var draft = plan(List.of(p), facts, Map.of(), Map.of()).drafts().getFirst();
        assertEquals("{\"condition\":90,\"tenants\":3,\"units\":4}", draft.inputsJson());
        assertEquals(2 * 4 + 3 + 90, draft.rawCents());
    }

    @Test void inputsJsonWritesNullForAValueThatWasNotRead() throws Exception {
        var p = policy("fee", "flat", "units + tenants");
        assertEquals("{\"tenants\":null,\"units\":2}", InvoicePlanner.inputsJson(p, Map.of("units", 2L)));
        assertEquals("{}", InvoicePlanner.inputsJson(policy("fixed", "flat", "250"), Map.of("units", 1L)));
    }

    @Test void anExternalReadingIsFrozenOntoTheDraft() throws Exception {
        var p = policy("power", "pw", "units * 2");
        var reading = new MeterRead.Frozen("pw", 40, true, SHA);
        var draft = plan(List.of(p), Map.of(), Map.of("power", reading), Map.of()).drafts().getFirst();
        assertEquals("pw", draft.meterId());
        assertEquals(40L, draft.meterUnits());
        assertEquals(true, draft.meterPowered());
        assertEquals(SHA, draft.meterDetailSha());
        assertEquals(80, draft.rawCents());
        assertEquals("{\"units\":40}", draft.inputsJson());
    }

    @Test void aCumulativeMeterBillsTheIncreaseNotTheRunningTotal() throws Exception {
        var p = policy("power", "pw", "units * 2");
        var reading = new MeterRead.Frozen("pw", 1_000, null, SHA);
        var draft = plan(List.of(p), Map.of(), Map.of("power", reading), Map.of("power", 15L)).drafts().getFirst();
        assertEquals(15L, draft.meterUnits());
        assertEquals(30, draft.rawCents());
        assertEquals(SHA, draft.meterDetailSha());
    }

    @Test void anExternalMeterWithNoReadingAndNoReasonSaysNoReading() throws Exception {
        var result = plan(List.of(policy("power", "pw", "units")), Map.of(), Map.of(), Map.of());
        assertEquals(List.of(new InvoicePlanner.Skipped("power", true, "no reading")), result.skipped());
    }

    @Test void aFormulaThatReadsPoweredNeedsTheMeterToReportIt() throws Exception {
        var p = policy("standing", "pw", "powered * 500");
        var unknown = plan(List.of(p), Map.of(), Map.of("standing", new MeterRead.Frozen("pw", 1, null, SHA)), Map.of());
        assertEquals(List.of(new InvoicePlanner.Skipped("standing", true, "meter pw does not report powered")), unknown.skipped());
        var on = plan(List.of(p), Map.of(), Map.of("standing", new MeterRead.Frozen("pw", 1, true, SHA)), Map.of()).drafts().getFirst();
        assertEquals(500, on.rawCents());
        assertEquals("{\"powered\":1}", on.inputsJson());
        var off = plan(List.of(p), Map.of(), Map.of("standing", new MeterRead.Frozen("pw", 1, false, SHA)), Map.of()).drafts().getFirst();
        assertEquals(0, off.rawCents());
        assertEquals("{\"powered\":0}", off.inputsJson());
    }

    @Test void aFormulaThatIgnoresPoweredDoesNotNeedIt() throws Exception {
        var p = policy("power", "pw", "units");
        var draft = plan(List.of(p), Map.of(), Map.of("power", new MeterRead.Frozen("pw", 9, null, SHA)), Map.of()).drafts().getFirst();
        assertEquals(9, draft.rawCents());
        assertNull(draft.meterPowered());
    }

    @Test void aFactOutsideItsRangeSkipsOnlyThatLine() throws Exception {
        var bad = policy("a-tax", "flat", "tenants * 10");
        var good = policy("b-fee", "flat", "250");
        var result = plan(List.of(bad, good), Map.of("tenants", 9L), Map.of(), Map.of());
        assertEquals(List.of(new InvoicePlanner.Skipped("a-tax", false, "'tenants' is outside its range")), result.skipped());
        assertEquals(List.of("b-fee"), result.drafts().stream().map(d -> d.policy().id()).toList());
    }

    @Test void draftsKeepThePolicyOrderGiven() throws Exception {
        var z = policy("zeta", "flat", "1");
        var a = policy("alpha", "flat", "2");
        var result = plan(List.of(z, a), Map.of(), Map.of(), Map.of());
        assertEquals(List.of("zeta", "alpha"), result.drafts().stream().map(d -> d.policy().id()).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.drafts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.skipped().clear());
    }

    @Test void aDraftBecomesACandidateWithThePolicyLimits() throws Exception {
        var external = policy("power", "pw", "units", Map.of("min-cents", "3", "max-cents", "40"));
        var draft = plan(List.of(external), Map.of(), Map.of("power", new MeterRead.Frozen("pw", 100, null, SHA)), Map.of())
                .drafts().getFirst();
        assertEquals(new ChargeLimits.Candidate("power", 100, 40, 3, true), draft.candidate());
        var flat = plan(List.of(policy("fee", "flat", "7")), Map.of(), Map.of(), Map.of()).drafts().getFirst();
        assertEquals(new ChargeLimits.Candidate("fee", 7, 5000, 1, false), flat.candidate());
    }

    @Test void theRoundingRuleIsAppliedToTheRawAmount() throws Exception {
        var p = policy("half", "flat", "units * 2.5", Map.of("rounding", "half-up"));
        assertEquals(3, plan(List.of(p), Map.of(), Map.of(), Map.of()).drafts().getFirst().rawCents());
        var floor = policy("half", "flat", "units * 2.5");
        assertEquals(2, plan(List.of(floor), Map.of(), Map.of(), Map.of()).drafts().getFirst().rawCents());
    }
}
