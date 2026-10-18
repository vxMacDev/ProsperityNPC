package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.charge.meter.DerivedMeter;
import com.prosperitysmp.npc.charge.meter.FlatMeter;
import com.prosperitysmp.npc.charge.meter.MeterRead;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Works out one home's invoice lines from its facts and meter readings, without SQL or Bukkit. The issue step and
 * the staff preview both use it, so a preview always matches what issuing would write.
 *
 * <p>A line is left off, never guessed, when its meter has no reading or its formula cannot be worked out. Those
 * are reported so the caller can alert staff once per policy per day.
 */
public final class InvoicePlanner {
    /** One line before caps: the frozen inputs, the rounded raw amount and the policy limits. */
    public record Draft(ChargeCatalogue.Policy policy, String meterId, Long meterUnits, Boolean meterPowered,
                        String meterDetailSha, String inputsJson, long rawCents) {
        public ChargeLimits.Candidate candidate() {
            return new ChargeLimits.Candidate(policy.id(), rawCents, policy.maxCents(), policy.minCents(),
                    policy.meter().external());
        }
    }

    /** A line that was left off before caps, and why. */
    public record Skipped(String policyId, boolean meterFault, String reason) { }

    public record Plan(List<Draft> drafts, List<Skipped> skipped) { }

    private InvoicePlanner() { }

    /**
     * @param facts the home's variables (section 4.3), read at issue time
     * @param readings frozen outside-meter readings by policy id; a missing entry means "no reading"
     * @param meterFailures why an outside meter has no reading, by policy id
     * @param cumulativeUnits for cumulative meters, the billable increase by policy id (already worked out against
     *                        the stored baseline); a policy with a cumulative meter and no entry bills nothing
     */
    public static Plan plan(List<ChargeCatalogue.Policy> policies, Map<String, Long> facts,
                            Map<String, MeterRead.Frozen> readings, Map<String, String> meterFailures,
                            Map<String, Long> cumulativeUnits) {
        List<Draft> drafts = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (ChargeCatalogue.Policy policy : policies) {
            Map<String, Long> values = new TreeMap<>(facts);
            String meterId = policy.meter().id();
            Long units = null;
            Boolean powered = null;
            String detail = null;
            try {
                switch (policy.meter().kind()) {
                    case ChargeCatalogue.MeterSpec.FLAT -> units = FlatMeter.units();
                    case ChargeCatalogue.MeterSpec.DERIVED -> units = DerivedMeter.units(policy.meter().target(), facts);
                    default -> {
                        MeterRead.Frozen reading = readings.get(policy.id());
                        if (reading == null) {
                            skipped.add(new Skipped(policy.id(), true, meterFailures.getOrDefault(policy.id(), "no reading")));
                            continue;
                        }
                        units = cumulativeUnits.containsKey(policy.id()) ? cumulativeUnits.get(policy.id()) : reading.units();
                        powered = reading.powered();
                        detail = reading.detailSha();
                        if (policy.formula().names().contains(ChargeVariables.POWERED) && powered == null) {
                            skipped.add(new Skipped(policy.id(), true, "meter " + meterId + " does not report powered"));
                            continue;
                        }
                    }
                }
                values.put(ChargeVariables.UNITS, units);
                if (powered != null) values.put(ChargeVariables.POWERED, powered ? 1L : 0L);
                long raw = policy.amountCents(values);
                drafts.add(new Draft(policy, meterId, units, powered, detail, inputsJson(policy, values), raw));
            } catch (FormulaException failure) {
                skipped.add(new Skipped(policy.id(), false, failure.getMessage()));
            }
        }
        return new Plan(List.copyOf(drafts), List.copyOf(skipped));
    }

    /** The values the formula read, sorted, as compact ASCII JSON. Names are [a-z0-9_] so nothing needs escaping. */
    static String inputsJson(ChargeCatalogue.Policy policy, Map<String, Long> values) {
        StringBuilder out = new StringBuilder("{");
        for (String name : policy.formula().names()) {
            if (policy.vars().containsKey(name)) continue;
            if (out.length() > 1) out.append(',');
            out.append('"').append(name).append("\":").append(values.get(name));
        }
        return out.append('}').toString();
    }
}
