package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.property.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure projection fixtures; the real producer/database path is covered by PremiumHousingIT. */
class OwnerHousingViewTest {
    private static final UUID OWNER = new UUID(0, 55001), WORLD = new UUID(0, 55002), HOME = new UUID(0, 55003);
    private static final Instant AT = Instant.parse("2026-09-30T17:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final Property PROPERTY = new Property(HOME, WORLD, "view-fixture", "downtown", "room", 2,
            1000, 1000, OWNER, 0, 100, true);

    @Test void noHistoryRemainsUnratedWithoutAMadeUpNeutralScore() {
        var view = OwnerHousingView.from(PROPERTY, 0, List.of(), empty(OWNER), ZONE, 4);
        assertEquals(HousingDecisionPlan.RankStatus.UNRATED_HELD, view.rating().status());
        assertTrue(view.rating().planningOperatorScore().isEmpty());
        assertTrue(view.rating().rankingScore().isEmpty());
        assertFalse(view.premium().eligible());
        assertEquals(PremiumHousingEligibility.Reason.INSUFFICIENT_UTILITY_DAYS, view.premium().reason());
        assertEquals(0, view.premium().distinctUtilityBillDays());
        assertEquals(AT.minus(Duration.ofDays(29)), view.windowFrom());
    }

    @Test void mismatchedOwnerCannotBecomeAnAttachedPrivateView() {
        assertThrows(IllegalArgumentException.class,
                () -> OwnerHousingView.from(PROPERTY, 0, List.of(), empty(UUID.randomUUID()), ZONE, 4));
        var view = OwnerHousingView.from(PROPERTY, 0, List.of(), empty(OWNER), ZONE, 4);
        assertThrows(IllegalArgumentException.class, () -> attach(new OwnerHousingView(UUID.randomUUID(), OWNER,
                AT, view.windowFrom(), view.rating(), view.premium())));
        assertThrows(IllegalArgumentException.class, () -> attach(new OwnerHousingView(HOME, OWNER,
                AT.plusMillis(1), view.windowFrom(), view.rating(), view.premium())));
        assertEquals(view, attach(view).housing().orElseThrow());
    }

    @Test void aProvisionalPlanningScoreIsNotAllowedInRecordedServiceDisplay() {
        var held = OwnerHousingView.from(PROPERTY, 0, List.of(), empty(OWNER), ZONE, 4);
        var provisional = new HousingDecisionPlan.HomeRank(HousingDecisionPlan.RankStatus.PROVISIONAL,
                OptionalInt.of(50), OptionalDouble.of(60), Set.of(ServiceAssessment.Component.UTILITIES));
        assertThrows(IllegalArgumentException.class, () -> new OwnerHousingView(HOME, OWNER, AT,
                held.windowFrom(), provisional, held.premium()));
    }

    private static ServiceAssessment empty(UUID owner) {
        var from = AT.minus(Duration.ofDays(29));
        return new ServiceAssessment(new UtilityServiceAssessment(owner, AT, from, List.of()),
                new IncidentServiceAssessment(owner, AT, from, List.of()),
                new RetentionAssessment(owner, AT, from, 7, List.of()));
    }
    private static OwnerDeskData attach(OwnerHousingView view) {
        var day = LocalDate.of(2026, 9, 30);
        return new OwnerDeskData(PROPERTY, 0, AT, List.of(), List.of(), List.of(),
                new OwnerDeskData.Statement(day.minusDays(6), day, 0, 0, 0, 0),
                new OwnerDeskData.Insurance(1600, null, null, false, true), Optional.of(view));
    }
}
