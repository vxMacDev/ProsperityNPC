package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.market.HousingDecisionPlan.HomeRank;
import com.prosperitysmp.npc.market.HousingDecisionPlan.RankStatus;
import com.prosperitysmp.npc.property.ServiceAssessment.Component;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** The plan's small pure parts: the stable request id, the ordering bonus and the rank record's invariants. */
class HousingDecisionPlanUnitsTest {
    private static final UUID RESIDENT = new UUID(0x5150L, 10);

    @Test void theRequestIdIsTheFixedNameUuidOfDayAndResident() {
        assertEquals(UUID.fromString("3fdb7b93-a198-385c-82c9-3e78c58c7d1b"),
                HousingDecisionPlan.requestId(LocalDate.of(2026, 9, 7), RESIDENT));
        assertNotEquals(HousingDecisionPlan.requestId(LocalDate.of(2026, 9, 7), RESIDENT),
                HousingDecisionPlan.requestId(LocalDate.of(2026, 9, 8), RESIDENT));
        assertNotEquals(HousingDecisionPlan.requestId(LocalDate.of(2026, 9, 7), RESIDENT),
                HousingDecisionPlan.requestId(LocalDate.of(2026, 9, 7), new UUID(0x5150L, 11)));
    }

    @Test void theRequestDayIsInsideYearsOneToNineNineNineNine() {
        assertDoesNotThrow(() -> HousingDecisionPlan.requestId(LocalDate.of(1, 1, 1), RESIDENT));
        assertDoesNotThrow(() -> HousingDecisionPlan.requestId(LocalDate.of(9_999, 12, 31), RESIDENT));
        assertEquals("Housing request day out of range", assertThrows(IllegalArgumentException.class,
                () -> HousingDecisionPlan.requestId(LocalDate.of(0, 1, 1), RESIDENT)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> HousingDecisionPlan.requestId(LocalDate.of(10_000, 1, 1), RESIDENT));
        assertThrows(NullPointerException.class, () -> HousingDecisionPlan.requestId(null, RESIDENT));
        assertThrows(NullPointerException.class, () -> HousingDecisionPlan.requestId(LocalDate.of(2026, 9, 7), null));
    }

    private static HousingMarketSnapshot.Home home(int advertising, int partner) {
        return new HousingMarketSnapshot.Home(new UUID(1, 1), new UUID(1, 2), new UUID(1, 3), "r", "town", "flat", 0, 0L,
                1, 100, 100, 50, 0, 0, List.of(), advertising, partner);
    }

    @ParameterizedTest(name = "advertising {0}, partner {1}: off {2}, advertising only {3}, both {4}")
    @CsvSource({"0, 0, 0.0, 0.0, 0.0", "1, 0, 0.0, 4.0, 4.0", "3, 0, 0.0, 12.0, 12.0", "0, 4, 0.0, 0.0, 4.0",
            "2, 3, 0.0, 8.0, 11.0", "2, 4, 0.0, 8.0, 12.0", "3, 4, 0.0, 12.0, 12.0", "1, 1, 0.0, 4.0, 5.0"})
    void theMatchBonusIsAdvertisingPlusPartnerPointsCappedAtTwelve(int advertising, int partner, double off,
                                                                   double advertisingOnly, double both) {
        var home = home(advertising, partner);
        assertEquals(off, HousingDecisionPlan.matchBonus(home, false, false));
        assertEquals(off, HousingDecisionPlan.matchBonus(home, false, true), "partner points need advertising rank on");
        assertEquals(advertisingOnly, HousingDecisionPlan.matchBonus(home, true, false));
        assertEquals(both, HousingDecisionPlan.matchBonus(home, true, true));
    }

    @Test void rankedStatusesCarryBothScoresAndHeldStatusesNeither() {
        var rated = new HomeRank(RankStatus.RATED, OptionalInt.of(50), OptionalDouble.of(63.5), Set.of());
        assertEquals(63.5, rated.rankingScore().getAsDouble());
        var provisional = new HomeRank(RankStatus.PROVISIONAL, OptionalInt.of(50), OptionalDouble.of(63.5),
                Set.of(Component.UTILITIES));
        assertEquals(Set.of(Component.UTILITIES), provisional.provisionalComponents());
        for (RankStatus held : new RankStatus[] {RankStatus.UNRATED_HELD, RankStatus.SERVICE_REVIEW}) {
            assertEquals(held, new HomeRank(held, OptionalInt.empty(), OptionalDouble.empty(), Set.of()).status());
            assertThrows(IllegalArgumentException.class,
                    () -> new HomeRank(held, OptionalInt.of(50), OptionalDouble.of(63.5), Set.of()), held + " with scores");
            assertThrows(IllegalArgumentException.class,
                    () -> new HomeRank(held, OptionalInt.empty(), OptionalDouble.empty(), Set.of(Component.RETENTION)),
                    held + " with provisional parts");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new HomeRank(RankStatus.RATED, OptionalInt.empty(), OptionalDouble.empty(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new HomeRank(RankStatus.RATED, OptionalInt.of(50), OptionalDouble.empty(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new HomeRank(RankStatus.RATED, OptionalInt.empty(), OptionalDouble.of(50), Set.of()));
    }

    @Test void provisionalMeansSomeComponentIsMissingAndRatedMeansNone() {
        assertThrows(IllegalArgumentException.class,
                () -> new HomeRank(RankStatus.PROVISIONAL, OptionalInt.of(50), OptionalDouble.of(60), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new HomeRank(RankStatus.RATED, OptionalInt.of(50), OptionalDouble.of(60), Set.of(Component.INCIDENTS)));
    }

    @ParameterizedTest
    @CsvSource({"25, 0.0, true", "75, 100.0, true", "24, 50.0, false", "76, 50.0, false", "50, -0.0001, false",
            "50, 100.0001, false", "50, NaN, false", "50, -Infinity, false"})
    void scoresStayInsideTheirRanges(int operator, double score, boolean accepted) {
        if (accepted) assertEquals(operator, new HomeRank(RankStatus.RATED, OptionalInt.of(operator), OptionalDouble.of(score),
                Set.of()).planningOperatorScore().getAsInt());
        else assertEquals("Invalid planning rank", assertThrows(IllegalArgumentException.class,
                () -> new HomeRank(RankStatus.RATED, OptionalInt.of(operator), OptionalDouble.of(score), Set.of())).getMessage());
    }

    @Test void theProvisionalComponentsAreAnImmutableCopy() {
        var parts = new java.util.HashSet<>(Set.of(Component.UTILITIES));
        var rank = new HomeRank(RankStatus.PROVISIONAL, OptionalInt.of(50), OptionalDouble.of(60), parts);
        parts.add(Component.RETENTION);
        assertEquals(Set.of(Component.UTILITIES), rank.provisionalComponents());
        assertThrows(UnsupportedOperationException.class, () -> rank.provisionalComponents().clear());
        assertThrows(NullPointerException.class, () -> new HomeRank(null, OptionalInt.empty(), OptionalDouble.empty(), Set.of()));
    }
}
