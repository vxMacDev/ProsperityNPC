package com.prosperitysmp.npc.charge.meter;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class MeterBaselinesTest {
    private static MeterBaselines.Delta delta(long previous, long current) {
        return MeterBaselines.delta(OptionalLong.of(previous), current);
    }

    @Test void theFirstReadingOnlySetsTheBaseline() {
        assertEquals(new MeterBaselines.Delta(MeterBaselines.Kind.FIRST, 0), MeterBaselines.delta(OptionalLong.empty(), 0));
        assertEquals(new MeterBaselines.Delta(MeterBaselines.Kind.FIRST, 0), MeterBaselines.delta(OptionalLong.empty(), 25));
        assertEquals(new MeterBaselines.Delta(MeterBaselines.Kind.FIRST, 0),
                MeterBaselines.delta(OptionalLong.empty(), Long.MAX_VALUE));
    }

    @ParameterizedTest(name = "{0} then {1}: {2} {3}")
    @CsvSource({
            "10, 10, INCREASE, 0",
            "10, 25, INCREASE, 15",
            "0, 1, INCREASE, 1",
            "25, 10, RESET, 0",
            "25, 24, RESET, 0",
            "25, 0, RESET, 0",
            "0, 0, INCREASE, 0",
            "0, 9223372036854775807, INCREASE, 9223372036854775807",
            "9223372036854775807, 9223372036854775807, INCREASE, 0",
            "9223372036854775807, 0, RESET, 0"})
    void aLaterReadingBillsOnlyAnIncrease(long previous, long current, MeterBaselines.Kind kind, long units) {
        assertEquals(new MeterBaselines.Delta(kind, units), delta(previous, current));
    }

    @Test void aNegativeReadingIsRefusedEvenAsTheFirst() {
        assertEquals("negative reading", assertThrows(IllegalArgumentException.class,
                () -> MeterBaselines.delta(OptionalLong.empty(), -1)).getMessage());
        assertEquals("negative reading", assertThrows(IllegalArgumentException.class, () -> delta(10, -1)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> delta(10, Long.MIN_VALUE));
    }

    @Test void onlyThreeKindsExist() {
        assertEquals(List.of(MeterBaselines.Kind.FIRST, MeterBaselines.Kind.INCREASE, MeterBaselines.Kind.RESET),
                List.of(MeterBaselines.Kind.values()));
    }
}
