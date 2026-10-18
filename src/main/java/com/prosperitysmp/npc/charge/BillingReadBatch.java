package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.api.charge.MeterQuery;
import com.prosperitysmp.npc.api.charge.UsageMeter;
import com.prosperitysmp.npc.charge.meter.MeterRead;
import com.prosperitysmp.npc.property.Property;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Captures bill inputs on the owner thread, a small amount per tick. */
final class BillingReadBatch {
    static final int HOMES_PER_TICK = 16;
    static final int METER_CALLS_PER_TICK = 4;
    static final long TICK_BUDGET_NANOS = 2_000_000L;

    record Read(ChargeInvoiceRepository.Home home, Map<String, MeterRead.Frozen> readings, Map<String, String> failures) { }

    private final Iterator<Property> homes;
    private final List<ChargeCatalogue.Policy> policies;
    private final Map<String, UsageMeter> meters;
    private final Map<String, String> problems;
    private final Instant from, until;
    private final LongSupplier nanos;
    private final Consumer<String> warn;
    private final List<Read> reads = new ArrayList<>();
    private final Map<String, MeterRead.Result> perHome = new LinkedHashMap<>();
    private final Map<String, MeterRead.Frozen> readings = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private Property current;
    private int policyIndex;
    private boolean complete;

    BillingReadBatch(List<Property> homes, List<ChargeCatalogue.Policy> policies, Map<String, UsageMeter> meters,
                     Map<String, String> problems, Instant from, Instant until, LongSupplier nanos, Consumer<String> warn) {
        this.homes = homes.iterator();
        this.policies = List.copyOf(policies);
        this.meters = Map.copyOf(meters);
        this.problems = new LinkedHashMap<>(problems);
        this.from = from;
        this.until = until;
        this.nanos = nanos;
        this.warn = warn;
    }

    boolean advance() {
        if (complete) return true;
        long started = nanos.getAsLong();
        int finishedHomes = 0, calls = 0;
        while (finishedHomes < HOMES_PER_TICK) {
            if (current == null) {
                if (!homes.hasNext()) return complete = true;
                current = homes.next();
                policyIndex = 0;
                perHome.clear();
                readings.clear();
                failures.clear();
            }
            while (policyIndex < policies.size()) {
                var policy = policies.get(policyIndex);
                if (!policy.meter().external() || !matches(policy, current)) {
                    policyIndex++;
                    continue;
                }
                String meterId = policy.meter().id();
                MeterRead.Result result = perHome.get(meterId);
                if (result == null) {
                    String problem = problems.get(meterId);
                    if (problem != null) {
                        failures.put(policy.id(), problem);
                        policyIndex++;
                        continue;
                    }
                    if (calls >= METER_CALLS_PER_TICK || calls > 0 && nanos.getAsLong() - started >= TICK_BUDGET_NANOS)
                        return false;
                    calls++;
                    result = MeterRead.read(meterId, meters.get(meterId),
                            MeterQuery.of(current.worldUuid(), current.regionId(), current.ownerUuid(), from, until),
                            nanos, message -> {
                                problems.put(meterId, "meter " + meterId + " was too slow; remaining readings skipped for this run");
                                warn.accept(message);
                            });
                    perHome.put(meterId, result);
                }
                if (result.ok()) readings.put(policy.id(), result.reading());
                else failures.put(policy.id(), result.failure());
                policyIndex++;
            }
            reads.add(new Read(new ChargeInvoiceRepository.Home(current.propertyId(), current.ownerUuid(),
                    current.ownershipEpoch(), current.classKey(), current.marketId()), Map.copyOf(readings), Map.copyOf(failures)));
            current = null;
            finishedHomes++;
            if (nanos.getAsLong() - started >= TICK_BUDGET_NANOS) return false;
        }
        return false;
    }

    List<Read> result() {
        if (!complete) throw new IllegalStateException("Bill readings are not complete");
        return List.copyOf(reads);
    }

    void retainMeters(Set<UsageMeter> available) {
        meters.forEach((id, meter) -> {
            if (!available.contains(meter)) problems.put(id, "meter " + id + " is no longer available");
        });
    }

    private static boolean matches(ChargeCatalogue.Policy policy, Property home) {
        return (policy.classes().isEmpty() || policy.classes().contains(home.classKey()))
                && (policy.markets().isEmpty() || policy.markets().contains(home.marketId()))
                && (policy.properties().isEmpty() || policy.properties().contains(home.propertyId()));
    }
}
