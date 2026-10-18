package com.prosperitysmp.npc.market;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Partner rank points are one to four per resident and the shorter option forms add no rank levers. */
class HousingDecisionPartnerCapsTest {
    private static final Instant FROM = Instant.parse("2026-08-20T00:00:00Z");
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(10, 5, 2, 7);
    private static final HousingDecisionPlan.ColdStartPolicy COLD = HousingDecisionPlan.ColdStartPolicy.HOLD_UNRATED;

    private static HousingDecisionRepository.Options withCaps(Map<UUID, Integer> caps) {
        return new HousingDecisionRepository.Options(FROM, 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1), true, true, caps);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void oneToFourPointsAreAccepted(int points) {
        assertEquals(Map.of(new UUID(1, 1), points), withCaps(Map.of(new UUID(1, 1), points)).partnerRankCaps());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 5, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void pointsOutsideOneToFourAreRefused(int points) {
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> withCaps(Map.of(new UUID(1, 1), 2, new UUID(1, 2), points)));
        assertEquals("Invalid partner rank points", refusal.getMessage());
    }

    @Test void theCapsAreAnImmutableCopy() {
        var caps = new HashMap<UUID, Integer>();
        caps.put(new UUID(2, 1), 3);
        var options = withCaps(caps);
        caps.put(new UUID(2, 2), 4);
        assertEquals(Map.of(new UUID(2, 1), 3), options.partnerRankCaps());
        assertThrows(UnsupportedOperationException.class, () -> options.partnerRankCaps().put(new UUID(2, 3), 1));
        assertThrows(NullPointerException.class, () -> withCaps(null));
    }

    @Test void theShorterFormsTurnPremiumAdvertisingAndPartnersOff() {
        var seven = new HousingDecisionRepository.Options(FROM, 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1));
        assertFalse(seven.premiumEnabled());
        assertFalse(seven.advertisingRankEnabled());
        assertEquals(Map.of(), seven.partnerRankCaps());
        var eight = new HousingDecisionRepository.Options(FROM, 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1), true);
        assertTrue(eight.premiumEnabled());
        assertFalse(eight.advertisingRankEnabled());
        var nine = new HousingDecisionRepository.Options(FROM, 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1), false, true);
        assertTrue(nine.advertisingRankEnabled());
        assertEquals(Map.of(), nine.partnerRankCaps());
        assertEquals(new HousingDecisionRepository.Options(FROM, 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1), false, false,
                Map.of()), seven);
    }

    @Test void theEvidenceStartMayBeAnyWholeMillisecondInYearsOneToNineNineNineNine() {
        assertEquals(Instant.parse("0001-01-01T00:00:00Z"), new HousingDecisionRepository.Options(
                Instant.parse("0001-01-01T00:00:00Z"), 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1)).evidenceFrom());
        assertEquals(Instant.parse("9999-12-31T23:59:59.999Z"), new HousingDecisionRepository.Options(
                Instant.parse("9999-12-31T23:59:59.999Z"), 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1)).evidenceFrom());
        assertThrows(IllegalArgumentException.class, () -> new HousingDecisionRepository.Options(
                FROM.plusNanos(999_999), 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new HousingDecisionRepository.Options(
                null, 7, POLICY, COLD, 1, 0, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new HousingDecisionRepository.Options(
                FROM, 7, POLICY, COLD, 1, 0, null));
    }
}
