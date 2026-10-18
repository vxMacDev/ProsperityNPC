package com.prosperitysmp.npc.market;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Housing eligibility is persisted authoring data, so every bound is exact. */
class HousingPolicyTest {
    private static final UUID WORLD = new UUID(7, 7);

    private static HousingPolicy policy(String market, String classKey, long rent, int condition) {
        return new HousingPolicy(WORLD, market, classKey, HousingPolicy.Tier.STANDARD, rent, condition);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "z9", "old_harbor", "old-harbor", "a0_-", "abcdefghijklmnopqrstuvwxyz012345"})
    void keysThatAreLowercaseAndAtMostThirtyTwoCharactersAreAccepted(String key) {
        assertDoesNotThrow(() -> HousingPolicy.key(key));
        HousingPolicy policy = policy(key, key, 100, 50);
        assertEquals(key, policy.marketId());
        assertEquals(key, policy.classKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "Town", "1a", "_a", "-a", "a b", "a.b", "a:b", "a\n", "\na", " a", "a ",
            "abcdefghijklmnopqrstuvwxyz0123456", "café", "a/b", "a\u0000"})
    void keysOutsideTheGrammarAreRefused(String key) {
        var refusal = assertThrows(IllegalArgumentException.class, () -> HousingPolicy.key(key));
        assertEquals("Invalid housing market/class key", refusal.getMessage());
        assertThrows(IllegalArgumentException.class, () -> policy(key, "flat", 100, 50));
        assertThrows(IllegalArgumentException.class, () -> policy("town", key, 100, 50));
    }

    @Test void aNullKeyIsRefusedAsInvalidNotAsANullPointer() {
        assertThrows(IllegalArgumentException.class, () -> HousingPolicy.key(null));
        assertThrows(IllegalArgumentException.class, () -> policy(null, "flat", 100, 50));
        assertThrows(IllegalArgumentException.class, () -> policy("town", null, 100, 50));
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 10_000L, Integer.MAX_VALUE - 1L, Integer.MAX_VALUE})
    void dailyRentCapsFromOneCentToTheIntegerLimitAreAccepted(long rent) {
        assertEquals(rent, policy("town", "flat", rent, 0).maxDailyRentCents());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Integer.MAX_VALUE + 1L, Long.MAX_VALUE, Long.MIN_VALUE})
    void dailyRentCapsOutsideOneCentToTheIntegerLimitAreRefused(long rent) {
        var refusal = assertThrows(IllegalArgumentException.class, () -> policy("town", "flat", rent, 0));
        assertEquals("Invalid housing eligibility", refusal.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 50, 99, 100})
    void minimumConditionsFromZeroToOneHundredAreAccepted(int condition) {
        assertEquals(condition, policy("town", "flat", 100, condition).minimumCondition());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void minimumConditionsOutsideZeroToOneHundredAreRefused(int condition) {
        assertThrows(IllegalArgumentException.class, () -> policy("town", "flat", 100, condition));
    }

    @Test void worldAndTierAreRequired() {
        assertThrows(NullPointerException.class,
                () -> new HousingPolicy(null, "town", "flat", HousingPolicy.Tier.STANDARD, 100, 0));
        assertThrows(NullPointerException.class, () -> new HousingPolicy(WORLD, "town", "flat", null, 100, 0));
    }

    @Test void theTiersAreStandardThenPremium() {
        assertEquals(2, HousingPolicy.Tier.values().length);
        assertEquals(0, HousingPolicy.Tier.STANDARD.ordinal());
        assertEquals(1, HousingPolicy.Tier.PREMIUM.ordinal());
    }
}
