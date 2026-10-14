package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.ServiceAssessment;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Private snapshot explanation only; neither a reservation nor permission to admit a tenant. */
public record OwnerHousingView(UUID propertyId, UUID ownerId, Instant capturedAt, Instant windowFrom,
                               HousingDecisionPlan.HomeRank rating, PremiumHousingEligibility.Result premium) {
    public OwnerHousingView {
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(capturedAt);
        Objects.requireNonNull(windowFrom);
        Objects.requireNonNull(rating);
        Objects.requireNonNull(premium);
        if (!windowFrom.isBefore(capturedAt) || rating.status() == HousingDecisionPlan.RankStatus.PROVISIONAL)
            throw new IllegalArgumentException("Invalid recorded housing view");
    }

    public static OwnerHousingView from(Property property, long revision, List<OwnerDeskData.Upgrade> upgrades,
                                        ServiceAssessment services, ZoneId zone, int resetHour) {
        Objects.requireNonNull(property);
        Objects.requireNonNull(services);
        if (!property.ownerUuid().equals(services.ownerId()))
            throw new IllegalArgumentException("Housing view owner differs from service record");
        int installed = upgrades.stream().filter(u -> u.type().equals("FACILITIES"))
                .mapToInt(OwnerDeskData.Upgrade::level).max().orElse(0);
        boolean disabled = upgrades.stream().anyMatch(u -> u.type().equals("FACILITIES") && u.disabled());
        var home = new HousingMarketSnapshot.Home(property.propertyId(), property.ownerUuid(), property.worldUuid(),
                property.regionId(), property.marketId(), property.classKey(), property.ownershipEpoch(), revision,
                property.capacity(), property.referenceRentCents(), property.askingRentCents(), property.conditionScore(),
                installed, disabled ? 0 : installed, List.of());
        return new OwnerHousingView(property.propertyId(), services.ownerId(), services.capturedAt(), services.windowFrom(),
                HousingDecisionPlan.rank(home, services, HousingDecisionPlan.ColdStartPolicy.HOLD_UNRATED),
                PremiumHousingEligibility.evaluate(home, services, zone, resetHour));
    }
}
