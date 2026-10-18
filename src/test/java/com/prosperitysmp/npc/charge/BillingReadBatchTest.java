package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.charge.MeterQuery;
import com.prosperitysmp.npc.api.charge.MeterReading;
import com.prosperitysmp.npc.api.charge.UsageMeter;
import com.prosperitysmp.npc.property.Property;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BillingReadBatchTest {
    private static final Instant START = Instant.parse("2026-10-17T04:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    private static List<Property> homes(int count) {
        return IntStream.range(0, count).mapToObj(i -> new Property(new UUID(0, i + 1), new UUID(1, 1),
                "home-" + i, "town", "room", 2, 1000, 1000, new UUID(2, i + 1), 0, 80, true)).toList();
    }

    private static List<ChargeCatalogue.Policy> policies() throws Exception {
        var bill = Map.of("version", "1", "title", "Power", "amount", "units", "max-cents", "1000", "meter", "power");
        return ChargeCatalogue.load(Map.of("enabled", true,
                "limits", Map.of("per-owner-daily-max-cents", "1000", "server-daily-max-cents", "10000"),
                "policies", Map.of("power-charge", bill, "power-tax", bill)), Map.of()).policies();
    }

    private static final class Meter implements UsageMeter {
        final AtomicLong clock;
        final long cost;
        final List<MeterQuery> queries = new ArrayList<>();
        Meter(AtomicLong clock, long cost) { this.clock = clock; this.cost = cost; }
        @Override public String id() { return "power"; }
        @Override public int apiMajor() { return 1; }
        @Override public int apiMinor() { return 1; }
        @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
        @Override public Optional<MeterReading> read(MeterQuery query) {
            queries.add(query);
            clock.addAndGet(cost);
            return Optional.of(MeterReading.builder().units(7).detailSha("a".repeat(64)).build());
        }
    }

    @Test void aTickBoundsCallsAndPoliciesShareOneReadingPerHome() throws Exception {
        AtomicLong clock = new AtomicLong();
        Meter meter = new Meter(clock, 0);
        var batch = new BillingReadBatch(homes(40), policies(), Map.of("power", meter), Map.of(), START, END, clock::get, fail -> fail(fail));
        int ticks = 0;
        boolean done;
        do {
            int before = meter.queries.size();
            done = batch.advance();
            assertTrue(meter.queries.size() - before <= 4);
            assertTrue(++ticks <= 40);
        } while (!done);
        assertEquals(40, meter.queries.size());
        assertEquals(40, batch.result().size());
        for (var read : batch.result()) {
            assertEquals(2, read.readings().size());
            assertSame(read.readings().get("power-charge"), read.readings().get("power-tax"));
        }
        assertTrue(meter.queries.stream().allMatch(q -> q.periodStart().equals(START) && q.periodEnd().equals(END)));
    }

    @Test void elapsedTimeStopsTheTickBeforeTheCallLimit() throws Exception {
        AtomicLong clock = new AtomicLong();
        Meter meter = new Meter(clock, 2_100_000L);
        var batch = new BillingReadBatch(homes(6), policies(), Map.of("power", meter), Map.of(), START, END, clock::get, fail -> fail(fail));
        assertFalse(batch.advance());
        assertEquals(1, meter.queries.size());
        assertThrows(IllegalStateException.class, batch::result);
        int ticks = 1;
        while (!batch.advance()) assertTrue(++ticks < 10);
        assertEquals(6, meter.queries.size());
        assertEquals(6, batch.result().size());
    }

    @Test void aSlowProviderIsNotCalledAgainForTheRestOfTheRun() throws Exception {
        AtomicLong clock = new AtomicLong();
        Meter meter = new Meter(clock, 6_000_000L);
        List<String> warnings = new ArrayList<>();
        var batch = new BillingReadBatch(homes(8), policies(), Map.of("power", meter), Map.of(), START, END, clock::get, warnings::add);
        int ticks = 0;
        while (!batch.advance()) assertTrue(++ticks < 10);
        assertEquals(1, meter.queries.size());
        assertEquals(1, warnings.size());
        assertEquals(2, batch.result().getFirst().readings().size());
        assertTrue(batch.result().stream().skip(1).allMatch(r -> r.readings().isEmpty() && r.failures().size() == 2));
    }

    @Test void homesWithoutReadableMetersStillYieldBetweenTicks() throws Exception {
        var batch = new BillingReadBatch(homes(40), policies(), Map.of(), Map.of("power", "not available"), START, END, () -> 0, fail -> fail(fail));
        assertFalse(batch.advance());
        assertFalse(batch.advance());
        assertTrue(batch.advance());
        assertEquals(40, batch.result().size());
        assertTrue(batch.result().stream().allMatch(r -> r.readings().isEmpty() && r.failures().size() == 2));
    }

    @Test void aProviderRemovedBetweenTicksIsNotReadAgain() throws Exception {
        AtomicLong clock = new AtomicLong();
        Meter meter = new Meter(clock, 0);
        var batch = new BillingReadBatch(homes(8), policies(), Map.of("power", meter), Map.of(), START, END, clock::get, message -> fail(message));
        assertFalse(batch.advance());
        assertEquals(4, meter.queries.size());
        batch.retainMeters(Set.of());
        assertTrue(batch.advance());
        assertEquals(4, meter.queries.size());
        assertTrue(batch.result().stream().skip(4).allMatch(r -> r.readings().isEmpty() && r.failures().size() == 2));
    }
}
