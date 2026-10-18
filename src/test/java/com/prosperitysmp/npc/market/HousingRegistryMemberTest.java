package com.prosperitysmp.npc.market;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** A roster member keeps a representable revision and an availability day inside years 1 to 9999. */
class HousingRegistryMemberTest {
    private static final HousingPolicy POLICY = new HousingPolicy(new UUID(1, 1), "town", "flat",
            HousingPolicy.Tier.STANDARD, 2_500, 40);

    @ParameterizedTest
    @CsvSource({"0, true", "1, true", "9223372036854775806, true", "-1, false", "9223372036854775807, false",
            "-9223372036854775808, false"})
    void revisionsLeaveRoomForOneMoreWrite(long revision, boolean accepted) {
        if (accepted) assertEquals(revision, new HousingRegistry.Member(new UUID(2, 1), POLICY,
                LocalDate.of(2026, 1, 1), revision, true).revision());
        else assertEquals("Invalid housing revision", assertThrows(IllegalArgumentException.class,
                () -> new HousingRegistry.Member(new UUID(2, 1), POLICY, LocalDate.of(2026, 1, 1), revision, true)).getMessage());
    }

    @ParameterizedTest
    @CsvSource({"0001-01-01, true", "9999-12-31, true", "0000-12-31, false", "+10000-01-01, false", "-0001-06-01, false"})
    void availabilityDaysAreInsideYearsOneToNineNineNineNine(String day, boolean accepted) {
        LocalDate date = LocalDate.parse(day);
        if (accepted) assertEquals(date, new HousingRegistry.Member(new UUID(2, 1), POLICY, date, 0L, false).availableSince());
        else assertEquals("Housing date out of range", assertThrows(IllegalArgumentException.class,
                () -> new HousingRegistry.Member(new UUID(2, 1), POLICY, date, 0L, false)).getMessage());
    }

    @Test void aMemberNeedsAnIdentityAPolicyAndADay() {
        assertThrows(NullPointerException.class,
                () -> new HousingRegistry.Member(null, POLICY, LocalDate.of(2026, 1, 1), 0L, true));
        assertThrows(NullPointerException.class,
                () -> new HousingRegistry.Member(new UUID(2, 1), null, LocalDate.of(2026, 1, 1), 0L, true));
        assertThrows(NullPointerException.class, () -> new HousingRegistry.Member(new UUID(2, 1), POLICY, null, 0L, true));
    }
}
