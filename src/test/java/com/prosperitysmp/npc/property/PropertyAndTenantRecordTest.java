package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PropertyAndTenantRecordTest {
    private static final UUID ID = new UUID(5, 1), WORLD = new UUID(5, 2), OWNER = new UUID(5, 3), BUYER = new UUID(5, 4);
    private static final Property HOME = new Property(ID, WORLD, "home-1", "town", "small", 3, 10_000, 9_500, OWNER, 4, 77, true);

    @Test void withOwnerChangesOnlyTheOwnerAndEpoch() {
        var sold = HOME.withOwner(BUYER, 5);
        assertEquals(new Property(ID, WORLD, "home-1", "town", "small", 3, 10_000, 9_500, BUYER, 5, 77, true), sold);
        assertEquals(HOME, new Property(ID, WORLD, "home-1", "town", "small", 3, 10_000, 9_500, OWNER, 4, 77, true),
                "the original is untouched");
    }

    @Test void withOwnerCanClearTheOwner() {
        var abandoned = HOME.withOwner(null, 5);
        assertNull(abandoned.ownerUuid());
        assertEquals(Optional.empty(), abandoned.owner());
        assertEquals(5, abandoned.ownershipEpoch());
    }

    @Test void withAskingRentChangesOnlyTheAskingRent() {
        assertEquals(new Property(ID, WORLD, "home-1", "town", "small", 3, 10_000, 12_345, OWNER, 4, 77, true),
                HOME.withAskingRent(12_345));
        assertEquals(10_000, HOME.withAskingRent(12_345).referenceRentCents());
    }

    @Test void ownerIsPresentOnlyWhenSet() {
        assertEquals(Optional.of(OWNER), HOME.owner());
    }

    @ParameterizedTest
    @CsvSource({"PREMIUM,true,125", "premium,true,125", "Premium,true,125", "STANDARD,false,100", "'',false,100",
            "'PREMIUM ',false,100", "PREMIUMS,false,100"})
    void onlyThePremiumTierEarnsTheMultiplier(String tier, boolean premium, int pct) {
        var tenant = new Tenant(new UUID(6, 1), "Ada", ID, 0, tier, "COOK", 50);
        assertEquals(premium, tenant.isPremium());
        assertEquals(pct, tenant.tierMultiplierPct());
    }

    @Test void aMissingTierIsStandard() {
        var tenant = new Tenant(new UUID(6, 1), "Ada", ID, 0, null, "COOK", 50);
        assertFalse(tenant.isPremium());
        assertEquals(100, tenant.tierMultiplierPct());
    }
}
