package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.market.HousingDecisionPlan.HomeRank;
import com.prosperitysmp.npc.market.HousingDecisionPlan.RankStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PublicRatingTest {
    private static HomeRank rated(double score) {
        return new HomeRank(RankStatus.RATED, OptionalInt.of(50), OptionalDouble.of(score), Set.of());
    }

    @Test
    void scoreRoundsToNearestHalfStarAndClamps() {
        assertEquals(0, PublicRating.halfStarUnits(0));
        assertEquals(0, PublicRating.halfStarUnits(4.99));
        assertEquals(1, PublicRating.halfStarUnits(5));
        assertEquals(1, PublicRating.halfStarUnits(14.9));
        assertEquals(2, PublicRating.halfStarUnits(15));
        assertEquals(7, PublicRating.halfStarUnits(70));
        assertEquals(9, PublicRating.halfStarUnits(89.9));
        assertEquals(10, PublicRating.halfStarUnits(95));
        assertEquals(10, PublicRating.halfStarUnits(100));
        assertEquals(10, PublicRating.halfStarUnits(250));
        assertEquals(0, PublicRating.halfStarUnits(-3));
        assertThrows(IllegalArgumentException.class, () -> PublicRating.halfStarUnits(Double.NaN));
    }

    @Test
    void starTextShowsHalvesAndEmptyStars() {
        assertTrue(PublicRating.of(rated(70), 1, 2, 90).stars().endsWith("(3.5 of 5)"));
        assertTrue(PublicRating.of(rated(100), 1, 2, 90).stars().startsWith("★★★★★ "));
        assertTrue(PublicRating.of(rated(0), 0, 2, 90).stars().startsWith("☆☆☆☆☆ "));
        assertEquals("★★★½☆ (3.5 of 5)", PublicRating.of(rated(70), 1, 2, 90).stars());
    }

    @Test
    void anythingButFullyRatedIsUnrated() {
        for (var status : List.of(RankStatus.UNRATED_HELD, RankStatus.SERVICE_REVIEW)) {
            var rank = new HomeRank(status, OptionalInt.empty(), OptionalDouble.empty(), Set.of());
            assertEquals("Unrated", PublicRating.of(rank, 0, 1, 50).stars());
        }
        var provisional = new HomeRank(RankStatus.PROVISIONAL, OptionalInt.of(50), OptionalDouble.of(88),
                Set.of(ServiceAssessment.Component.UTILITIES));
        assertEquals("Unrated", PublicRating.of(provisional, 0, 1, 50).stars());
    }

    @Test
    void occupancyAndConditionBands() {
        assertEquals("2 of 3 rooms let", PublicRating.of(rated(50), 2, 3, 50).lines().get(1));
        assertEquals("1 of 1 room let", PublicRating.of(rated(50), 1, 1, 50).lines().get(1));
        assertEquals("Excellent", PublicRating.conditionBand(80));
        assertEquals("Good", PublicRating.conditionBand(79));
        assertEquals("Good", PublicRating.conditionBand(60));
        assertEquals("Fair", PublicRating.conditionBand(40));
        assertEquals("Poor", PublicRating.conditionBand(39));
        assertThrows(IllegalArgumentException.class, () -> PublicRating.of(rated(50), 4, 3, 50));
    }

    @Test
    void outputLeaksNoPrivateFields() {
        UUID owner = UUID.randomUUID(), tenant = UUID.randomUUID(), world = UUID.randomUUID();
        var property = new Property(UUID.randomUUID(), world, "secret_region_7", "marketx", "room",
                2, 100, 150, owner, 3, 85, true);
        var day = LocalDate.of(2026, 9, 7);
        var data = new OwnerDeskData(property, 1, Instant.EPOCH,
                List.of(new OwnerDeskData.Resident(tenant, "Marlowe Quill", 0, "STANDARD", "HAPPY", 91)),
                List.of(new OwnerDeskData.Issue(9, "PLUMBING", 5000, Instant.EPOCH)), List.of(),
                new OwnerDeskData.Statement(day.minusDays(6), day, 123456, 7890, 2, 1));
        String out = String.join("\n", PublicRating.from(data).lines());
        assertEquals("Property rating: Unrated\n1 of 2 rooms let\nCondition: Excellent", out);
        for (String forbidden : List.of(owner.toString(), tenant.toString(), world.toString(), "Marlowe", "secret_region",
                "marketx", "$", "1234", "7890", "PLUMBING", "receipt", "HAPPY", "91", "150"))
            assertFalse(out.contains(forbidden), forbidden);
        assertFalse(out.contains("—"));
    }
}
