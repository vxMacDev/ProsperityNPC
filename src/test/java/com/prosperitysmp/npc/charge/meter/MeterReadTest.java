package com.prosperitysmp.npc.charge.meter;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.charge.MeterQuery;
import com.prosperitysmp.npc.api.charge.MeterReading;
import com.prosperitysmp.npc.api.charge.UsageMeter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeterReadTest {
    private static final String SHA = "0123456789abcdef".repeat(4);
    private static final MeterQuery QUERY = MeterQuery.of(new UUID(1, 1), "home-1", new UUID(2, 2),
            Instant.parse("2026-10-17T04:00:00Z"), Instant.parse("2026-10-18T04:00:00Z"));

    private record Meter(Function<MeterQuery, Optional<MeterReading>> answer) implements UsageMeter {
        @Override public String id() { return "power"; }
        @Override public int apiMajor() { return 1; }
        @Override public int apiMinor() { return 1; }
        @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
        @Override public Optional<MeterReading> read(MeterQuery query) { return answer.apply(query); }
    }

    /** Returns the given times in order, one per call. */
    private static LongSupplier ticks(long... times) {
        int[] at = {0};
        return () -> times[at[0]++];
    }

    private static MeterRead.Result read(UsageMeter meter, List<String> warnings) {
        return MeterRead.read("power", meter, QUERY, ticks(0, 1), warnings::add);
    }

    private static MeterReading reading(long units) {
        return MeterReading.builder().units(units).detailSha(SHA).build();
    }

    @Test void aGoodReadingIsFrozenWithItsEvidence() {
        List<String> warnings = new ArrayList<>();
        var result = read(new Meter(q -> Optional.of(MeterReading.builder().units(7).powered(true).detailSha(SHA).build())), warnings);
        assertTrue(result.ok());
        assertNull(result.failure());
        assertEquals(new MeterRead.Frozen("power", 7, true, SHA), result.reading());
        assertEquals(List.of(), warnings);
    }

    @Test void aMeterThatDoesNotReportPowerFreezesPoweredAsUnknown() {
        var result = read(new Meter(q -> Optional.of(reading(0))), new ArrayList<>());
        assertEquals(new MeterRead.Frozen("power", 0, null, SHA), result.reading());
    }

    @Test void theLargestDocumentedReadingIsAccepted() {
        var result = read(new Meter(q -> Optional.of(reading(MeterReading.MAX_UNITS))), new ArrayList<>());
        assertEquals(MeterReading.MAX_UNITS, result.reading().units());
    }

    @Test void theMeterIsAskedAboutTheGivenHomeAndPeriod() {
        List<MeterQuery> asked = new ArrayList<>();
        read(new Meter(q -> {
            asked.add(q);
            return Optional.of(reading(1));
        }), new ArrayList<>());
        assertEquals(List.of(QUERY), asked);
    }

    @Test void aMissingMeterIsAFailureAndNoTimeIsTaken() {
        var result = MeterRead.read("power", null, QUERY, () -> { throw new AssertionError("timed"); }, w -> fail(w));
        assertFalse(result.ok());
        assertNull(result.reading());
        assertEquals("meter power is not available", result.failure());
    }

    @Test void aThrowingMeterIsAFailureNamingOnlyTheErrorType() {
        var result = read(new Meter(q -> { throw new IllegalStateException("secret detail"); }), new ArrayList<>());
        assertEquals(new MeterRead.Result(null, "meter power failed: IllegalStateException"), result);
    }

    @Test void aMeterWithAMissingClassIsAFailure() {
        var result = read(new Meter(q -> { throw new NoClassDefFoundError("org/example/Gone"); }), new ArrayList<>());
        assertEquals("meter power failed: NoClassDefFoundError", result.failure());
    }

    @Test void anEmptyOrNullAnswerIsNoReading() {
        assertEquals("meter power gave no reading", read(new Meter(q -> Optional.empty()), new ArrayList<>()).failure());
        assertEquals("meter power gave no reading", read(new Meter(q -> null), new ArrayList<>()).failure());
    }

    @Test void aSlowCallWarnsWithTheMeterAndWholeMilliseconds() {
        List<String> warnings = new ArrayList<>();
        var result = MeterRead.read("power", new Meter(q -> Optional.of(reading(3))), QUERY, ticks(1_000, 1_000 + 7_999_999), warnings::add);
        assertTrue(result.ok());
        assertEquals(List.of("Slow bill meter power: 7 ms for one reading."), warnings);
    }

    @Test void aCallOfExactlyTheLimitIsNotSlow() {
        List<String> warnings = new ArrayList<>();
        MeterRead.read("power", new Meter(q -> Optional.of(reading(3))), QUERY, ticks(0, MeterRead.SLOW_NANOS), warnings::add);
        assertEquals(List.of(), warnings);
        MeterRead.read("power", new Meter(q -> Optional.of(reading(3))), QUERY, ticks(0, MeterRead.SLOW_NANOS + 1), warnings::add);
        assertEquals(List.of("Slow bill meter power: 5 ms for one reading."), warnings);
    }

    @Test void aSlowThrowingCallStillWarnsAndFails() {
        List<String> warnings = new ArrayList<>();
        var result = MeterRead.read("power", new Meter(q -> { throw new RuntimeException(); }), QUERY,
                ticks(0, 20_000_000), warnings::add);
        assertEquals("meter power failed: RuntimeException", result.failure());
        assertEquals(List.of("Slow bill meter power: 20 ms for one reading."), warnings);
    }

    @Test void theSlowLimitIsFiveMilliseconds() {
        assertEquals(5_000_000L, MeterRead.SLOW_NANOS);
    }
}
