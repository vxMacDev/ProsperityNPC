package com.prosperitysmp.npc.api.charge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** A meter reading is checked when it is built: units inside the range, evidence a lowercase SHA-256 in hex. */
class MeterReadingTest {
    private static final String SHA = "0123456789abcdef".repeat(4);

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 999_999_999, 1_000_000_000})
    void unitsInsideTheRangeAreKept(long units) {
        assertEquals(units, MeterReading.builder().units(units).detailSha(SHA).build().units());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -1_000_000_000, Long.MIN_VALUE, 1_000_000_001, 2_000_000_000L, Long.MAX_VALUE})
    void unitsOutsideTheRangeAreRefused(long units) {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> MeterReading.builder().units(units).detailSha(SHA).build());
        assertEquals("units outside 0..1000000000", thrown.getMessage());
    }

    @Test void unitsDefaultToZeroWhenNeverSet() {
        assertEquals(0, MeterReading.builder().detailSha(SHA).build().units());
    }

    @Test void theLastUnitsSetWins() {
        assertEquals(7, MeterReading.builder().units(900).units(7).detailSha(SHA).build().units());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0000000000000000000000000000000000000000000000000000000000000000",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"})
    void aSixtyFourCharacterLowercaseHexHashIsKeptVerbatim(String sha) {
        assertEquals(sha, MeterReading.builder().detailSha(sha).build().detailSha());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
            "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef",
            "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n",
            " 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            "0x23456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde٠",
            "0123456789abcdef-0123456789abcdef-0123456789abcdef-0123456789abcdef"})
    void anythingElseIsRefusedAsEvidence(String sha) {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> MeterReading.builder().units(5).detailSha(sha).build());
        assertEquals("detailSha must be 64 lowercase hex characters", thrown.getMessage());
    }

    @Test void aReadingWithNoEvidenceAtAllIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MeterReading.builder().units(5).build());
    }

    @Test void whenBothAreWrongTheUnitsAreReportedFirst() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> MeterReading.builder().units(-1).detailSha("x").build());
        assertEquals("units outside 0..1000000000", thrown.getMessage());
    }

    @Test void poweredIsEmptyUntilSet() {
        assertEquals(Optional.empty(), MeterReading.builder().detailSha(SHA).build().powered());
    }

    @Test void poweredFalseIsPresentAndNotTheSameAsUnknown() {
        var reading = MeterReading.builder().powered(false).detailSha(SHA).build();
        assertEquals(Optional.of(false), reading.powered());
        assertTrue(reading.powered().isPresent());
    }

    @Test void poweredTrueIsPresent() {
        assertEquals(Optional.of(true), MeterReading.builder().powered(true).detailSha(SHA).build().powered());
    }

    @Test void theLastPoweredSetWins() {
        assertEquals(Optional.of(false), MeterReading.builder().powered(true).powered(false).detailSha(SHA).build().powered());
    }

    @Test void everyBuilderCallReturnsTheBuilderItself() {
        var builder = MeterReading.builder();
        assertSame(builder, builder.units(3));
        assertSame(builder, builder.powered(true));
        assertSame(builder, builder.detailSha(SHA));
    }

    @Test void changingTheBuilderAfterBuildLeavesTheEarlierReadingAlone() {
        var builder = MeterReading.builder().units(10).powered(true).detailSha(SHA);
        var first = builder.build();
        var second = builder.units(20).powered(false).detailSha("f".repeat(64)).build();
        assertEquals(10, first.units());
        assertEquals(Optional.of(true), first.powered());
        assertEquals(SHA, first.detailSha());
        assertEquals(20, second.units());
        assertEquals(Optional.of(false), second.powered());
        assertEquals("f".repeat(64), second.detailSha());
    }

    @Test void aRefusedBuildLeavesTheBuilderUsable() {
        var builder = MeterReading.builder().units(-5).detailSha(SHA);
        assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals(5, builder.units(5).build().units());
    }

    @Test void eachBuilderStartsFresh() {
        MeterReading.builder().units(99).powered(true).detailSha(SHA);
        var fresh = MeterReading.builder().detailSha(SHA).build();
        assertEquals(0, fresh.units());
        assertEquals(Optional.empty(), fresh.powered());
    }
}
