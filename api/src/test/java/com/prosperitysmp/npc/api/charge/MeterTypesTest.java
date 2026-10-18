package com.prosperitysmp.npc.api.charge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A meter reading or query outside its documented ranges fails when it is built, not later in a bill. */
class MeterTypesTest {
    private static final String SHA = "0".repeat(63) + "a";

    @Test void aReadingKeepsItsUnitsPowerAndEvidence() {
        var reading = MeterReading.builder().units(MeterReading.MAX_UNITS).powered(true).detailSha(SHA).build();
        assertEquals(1_000_000_000L, reading.units());
        assertEquals(Optional.of(true), reading.powered());
        assertEquals(SHA, reading.detailSha());
        assertEquals(Optional.empty(), MeterReading.builder().detailSha(SHA).build().powered());
    }

    @Test void aReadingRefusesUnitsOutsideTheRange() {
        assertThrows(IllegalArgumentException.class, () -> MeterReading.builder().units(-1).detailSha(SHA).build());
        assertThrows(IllegalArgumentException.class,
                () -> MeterReading.builder().units(MeterReading.MAX_UNITS + 1).detailSha(SHA).build());
    }

    @Test void aReadingNeedsSixtyFourLowercaseHexCharacters() {
        assertThrows(IllegalArgumentException.class, () -> MeterReading.builder().build());
        assertThrows(IllegalArgumentException.class, () -> MeterReading.builder().detailSha(SHA.toUpperCase()).build());
        assertThrows(IllegalArgumentException.class, () -> MeterReading.builder().detailSha(SHA.substring(1)).build());
        assertThrows(IllegalArgumentException.class, () -> MeterReading.builder().detailSha(SHA + "0").build());
    }

    @Test void aQueryRefusesMissingPartsAndABackwardPeriod() {
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        Instant start = Instant.parse("2026-10-18T09:00:00Z"), end = Instant.parse("2026-10-19T09:00:00Z");
        var query = MeterQuery.of(world, "home-1", owner, start, end);
        assertEquals(world, query.world());
        assertEquals("home-1", query.regionId());
        assertEquals(owner, query.owner());
        assertEquals(start, query.periodStart());
        assertEquals(end, query.periodEnd());
        assertEquals(start, MeterQuery.of(world, "home-1", owner, start, start).periodEnd(), "an empty period is allowed");

        assertThrows(IllegalArgumentException.class, () -> MeterQuery.of(world, "home-1", owner, end, start));
        assertThrows(NullPointerException.class, () -> MeterQuery.of(null, "home-1", owner, start, end));
        assertThrows(NullPointerException.class, () -> MeterQuery.of(world, null, owner, start, end));
        assertThrows(NullPointerException.class, () -> MeterQuery.of(world, "home-1", null, start, end));
        assertThrows(NullPointerException.class, () -> MeterQuery.of(world, "home-1", owner, null, end));
        assertThrows(NullPointerException.class, () -> MeterQuery.of(world, "home-1", owner, start, null));
    }
}
