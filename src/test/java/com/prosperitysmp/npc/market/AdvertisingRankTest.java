package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Home;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Participant;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Status;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.RetentionAssessment;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.UtilityServiceAssessment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdvertisingRankTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T12:34:56.789Z");
    private static final Instant FROM = CAPTURED.minus(Duration.ofDays(30));
    private static final UUID OWNER = id(100), WORLD = id(200);
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(8, 8, 4, 7);
    private static final HousingDecisionPlan.ColdStartPolicy COLD = HousingDecisionPlan.ColdStartPolicy.PROVISIONAL_50_V1;

    @Test
    void bonusIsFourPointsPerLevelAndClamped() {
        assertEquals(0.0, PropertyFormulas.advertisingRankBonus(0));
        assertEquals(4.0, PropertyFormulas.advertisingRankBonus(1));
        assertEquals(12.0, PropertyFormulas.advertisingRankBonus(3));
        assertEquals(0.0, PropertyFormulas.advertisingRankBonus(-2));
        assertEquals(12.0, PropertyFormulas.advertisingRankBonus(9));
    }

    @Test
    void flagOffIgnoresAdvertisingExactly() {
        var plan = plan(false, home(1, 0), home(2, 3));
        assertEquals(id(1), plan.matching().assignments().get(0).propertyId());
    }

    @Test
    void flagOnMatchesAdvertisedHomeFirstAndHigherLevelBeatsLower() {
        assertEquals(id(2), plan(true, home(1, 0), home(2, 1)).matching().assignments().get(0).propertyId());
        assertEquals(id(3), plan(true, home(1, 0), home(2, 1), home(3, 3)).matching().assignments().get(0).propertyId());
    }

    @Test
    void disabledAdvertisingGivesNoBonus() {
        // The repository reports an incident-disabled upgrade as effective level 0.
        assertEquals(id(1), plan(true, home(1, 0), home(2, 0)).matching().assignments().get(0).propertyId());
    }

    @Test
    void advertisingNeverChangesPublicRankRentOrRequest() {
        var off = plan(false, home(1, 0), home(2, 3));
        var on = plan(true, home(1, 0), home(2, 3));
        assertEquals(off.ranks(), on.ranks());
        assertEquals(on.ranks().get(id(1)), on.ranks().get(id(2)));
        var quoted = on.requests().get(0);
        assertEquals(id(2), quoted.propertyId());
    }

    @Test
    void plannerBonusOrdersOnlyAndDoesNotRaiseMinimumScoreEligibility() {
        var seeker = new FiniteHousingPlanner.Seeker(id(10), new HousingPolicy(WORLD, "town", "flat", HousingPolicy.Tier.STANDARD, 2000, 0),
                8, -1, DAY.minusDays(1), DAY);
        var plain = new FiniteHousingPlanner.Home(id(1), WORLD, "town", "flat", 70, 100, 60, false, List.of(0));
        var boosted = new FiniteHousingPlanner.Home(id(2), WORLD, "town", "flat", 70, 100, 60, false, List.of(0), 8.0);
        var result = FiniteHousingPlanner.plan(DAY, List.of(seeker), List.of(plain, boosted),
                new FiniteHousingPlanner.Limits(8, 8, 4));
        assertEquals(id(2), result.assignments().get(0).propertyId());
        assertEquals(60.0, boosted.score());
        assertThrows(IllegalArgumentException.class,
                () -> new FiniteHousingPlanner.Home(id(3), WORLD, "town", "flat", 70, 100, 60, false, List.of(0), 13.0));
    }

    private static HousingDecisionPlan plan(boolean enabled, Home... homes) {
        var member = new HousingRegistry.Member(id(10), new HousingPolicy(WORLD, "town", "flat", HousingPolicy.Tier.STANDARD, 2000, 0),
                DAY.minusDays(1), 8, true);
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(homes),
                List.of(new Participant(member, -1, DAY, Status.READY)), List.of());
        var evidence = new ServiceAssessment(new UtilityServiceAssessment(OWNER, CAPTURED, FROM, List.of()),
                new IncidentServiceAssessment(OWNER, CAPTURED, FROM, List.of()),
                new RetentionAssessment(OWNER, CAPTURED, FROM, 7, List.of()));
        return HousingDecisionPlan.prepare(snapshot, Map.of(OWNER, evidence), COLD, false, ZoneId.of("UTC"), 0, enabled);
    }

    private static Home home(long n, int advertising) {
        return new Home(id(n), OWNER, WORLD, "region-" + n, "town", "flat", 6, 7, 4, 1000, 1000, 80, 0, 0, List.of(0), advertising);
    }

    private static UUID id(long n) { return new UUID(0, n); }
}
