package com.prosperitysmp.npc.market;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class HousingDecisionOptionsTest {
    private static final Instant FROM = Instant.parse("2026-08-20T00:00:00Z");
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(10, 5, 2, 7);

    private static HousingDecisionRepository.Options options(Instant from, int horizon, int owners, int cases, Duration time) {
        return new HousingDecisionRepository.Options(from, horizon, POLICY,
                HousingDecisionPlan.ColdStartPolicy.PROVISIONAL_50_V1, owners, cases, time);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 31, Integer.MAX_VALUE})
    void rejectsHorizon(int value) {
        assertThrows(IllegalArgumentException.class, () -> options(FROM, value, 1, 100, Duration.ofSeconds(1)));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 513, Integer.MAX_VALUE})
    void rejectsOwnerBudget(int value) {
        assertThrows(IllegalArgumentException.class, () -> options(FROM, 7, value, 100, Duration.ofSeconds(1)));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 65537, Integer.MAX_VALUE})
    void rejectsAggregateBudget(int value) {
        assertThrows(IllegalArgumentException.class, () -> options(FROM, 7, 1, value, Duration.ofSeconds(1)));
    }
    @ParameterizedTest @ValueSource(longs = {-1, 0, 60001, Long.MAX_VALUE})
    void rejectsElapsedBudget(long millis) {
        assertThrows(IllegalArgumentException.class, () -> options(FROM, 7, 1, 100, Duration.ofMillis(millis)));
    }
    @Test void preservesExplicitBoundaryPoliciesWithoutInventingDefaults() {
        var minimum = options(FROM, 1, 1, 0, Duration.ofNanos(1));
        var maximum = options(FROM, 30, 512, 65536, Duration.ofMinutes(1));
        assertEquals(0, minimum.maxCases());
        assertEquals(Duration.ofNanos(1), minimum.maxReadTime());
        assertEquals(512, maximum.maxOwners());
        assertEquals(65536, maximum.maxCases());
        assertSame(POLICY, maximum.admissionPolicy());
    }
    @Test void rejectsNonCanonicalOrOutOfRangeEvidenceTime() {
        for (Instant from : new Instant[]{FROM.plusNanos(1), Instant.parse("0000-12-31T23:59:59Z"),
                Instant.parse("+10000-01-01T00:00:00Z")})
            assertThrows(IllegalArgumentException.class, () -> options(from, 7, 1, 100, Duration.ofSeconds(1)));
    }
    @Test void requiresExplicitPolicies() {
        assertThrows(NullPointerException.class, () -> new HousingDecisionRepository.Options(FROM, 7, null,
                HousingDecisionPlan.ColdStartPolicy.HOLD_UNRATED, 1, 0, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new HousingDecisionRepository.Options(FROM, 7, POLICY,
                null, 1, 0, Duration.ofSeconds(1)));
    }
}
