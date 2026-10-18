package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.charge.meter.MeterRead;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoicePlannerTest {
    private static ChargeCatalogue.Catalogue catalogue() throws ChargeCatalogue.Rejected {
        return ChargeCatalogue.load(Map.of("enabled", true,
                "limits", Map.of("per-owner-daily-max-cents", "1000", "server-daily-max-cents", "10000"),
                "policies", Map.of(
                        "base-fee", Map.of("version", "1", "title", "Base fee", "amount", "25", "max-cents", "1000"),
                        "water", Map.of("version", "1", "title", "Water", "amount", "units", "max-cents", "1000", "meter", "water"))),
                Map.of());
    }

    @Test void aFailedMeterSkipsOnlyItsLineAndKeepsTheFailureReason() throws Exception {
        String reason = "meter water is not available";
        var plan = InvoicePlanner.plan(catalogue().policies(), Map.of(), Map.of(), Map.of("water", reason), Map.of());

        assertEquals(List.of("base-fee"), plan.drafts().stream().map(d -> d.policy().id()).toList());
        assertEquals(25, plan.drafts().getFirst().rawCents());
        assertEquals(List.of(new InvoicePlanner.Skipped("water", true, reason)), plan.skipped());
    }

    @Test void aRealZeroReadingIsNotAMeterFailure() throws Exception {
        var policy = catalogue().policy("water").orElseThrow();
        var reading = new MeterRead.Frozen("water", 0, null, "a".repeat(64));
        var plan = InvoicePlanner.plan(List.of(policy), Map.of(), Map.of("water", reading), Map.of(), Map.of());

        assertTrue(plan.skipped().isEmpty());
        assertEquals(1, plan.drafts().size());
        assertEquals(0L, plan.drafts().getFirst().meterUnits());
        assertEquals(0, plan.drafts().getFirst().rawCents());
    }
}
