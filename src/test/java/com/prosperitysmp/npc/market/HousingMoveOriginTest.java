package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.market.HousingMoveRequest.Origin;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Premium quotes and relocations extend the v1 fingerprint; a standard quote keeps its exact v1 bytes. */
class HousingMoveOriginTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(8, 8, 4, 7);
    private static final Origin ORIGIN = new Origin(new UUID(2, 1), new UUID(2, 2), 1, 3L, 1, new UUID(2, 3));

    private static HousingMoveRequest request(HousingPolicy.Tier tier, long tenancy, Origin origin) {
        return new HousingMoveRequest(new UUID(1, 1), new UUID(1, 2), new UUID(1, 3), new UUID(1, 4), new UUID(1, 5),
                "town", "flat", 2, 5L, 9L, tenancy, 2, DAY, tier, origin);
    }

    @Test void standardPremiumAndRelocationFingerprintsAreFixed() {
        assertEquals("9599c10c832be83b74fa4f7fd32ffb457294b81ac178f4524fd934b5eacfe370",
                request(HousingPolicy.Tier.STANDARD, 4L, null).fingerprint(POLICY));
        assertEquals("9c50ac542091629355ec4e773a2eb5cb423103a3a30a037b46f4736f39a7588f",
                request(HousingPolicy.Tier.PREMIUM, 4L, null).fingerprint(POLICY));
        assertEquals("fc2efdac4be4ec316e57673c1231b4a83bf4ed536081ed13734e675e895f49dc",
                request(HousingPolicy.Tier.PREMIUM, 4L, ORIGIN).fingerprint(POLICY));
    }

    @Test void theShortConstructorsAreStandardWithoutAnOrigin() {
        var thirteen = new HousingMoveRequest(new UUID(1, 1), new UUID(1, 2), new UUID(1, 3), new UUID(1, 4),
                new UUID(1, 5), "town", "flat", 2, 5L, 9L, 4L, 2, DAY);
        var fourteen = new HousingMoveRequest(new UUID(1, 1), new UUID(1, 2), new UUID(1, 3), new UUID(1, 4),
                new UUID(1, 5), "town", "flat", 2, 5L, 9L, 4L, 2, DAY, HousingPolicy.Tier.STANDARD);
        assertEquals(request(HousingPolicy.Tier.STANDARD, 4L, null), thirteen);
        assertEquals(thirteen, fourteen);
        assertNull(thirteen.origin());
        assertEquals(HousingPolicy.Tier.STANDARD, thirteen.tier());
    }

    @Test void everyOriginFieldBindsTheRelocationFingerprint() {
        var seen = new java.util.HashSet<String>();
        seen.add(request(HousingPolicy.Tier.PREMIUM, 4L, ORIGIN).fingerprint(POLICY));
        seen.add(request(HousingPolicy.Tier.PREMIUM, 4L, new Origin(new UUID(2, 9), new UUID(2, 2), 1, 3L, 1, new UUID(2, 3))).fingerprint(POLICY));
        seen.add(request(HousingPolicy.Tier.PREMIUM, 4L, new Origin(new UUID(2, 1), new UUID(2, 9), 1, 3L, 1, new UUID(2, 3))).fingerprint(POLICY));
        seen.add(request(HousingPolicy.Tier.PREMIUM, 4L, new Origin(new UUID(2, 1), new UUID(2, 2), 2, 3L, 1, new UUID(2, 3))).fingerprint(POLICY));
        seen.add(request(HousingPolicy.Tier.PREMIUM, 4L, new Origin(new UUID(2, 1), new UUID(2, 2), 1, 4L, 1, new UUID(2, 3))).fingerprint(POLICY));
        seen.add(request(HousingPolicy.Tier.PREMIUM, 4L, new Origin(new UUID(2, 1), new UUID(2, 2), 1, 3L, 0, new UUID(2, 3))).fingerprint(POLICY));
        seen.add(request(HousingPolicy.Tier.PREMIUM, 4L, new Origin(new UUID(2, 1), new UUID(2, 2), 1, 3L, 1, new UUID(2, 9))).fingerprint(POLICY));
        assertEquals(7, seen.size());
    }

    @Test void aRelocationMustBePremium() {
        var refusal = assertThrows(IllegalArgumentException.class, () -> request(HousingPolicy.Tier.STANDARD, 4L, ORIGIN));
        assertEquals("Invalid premium relocation quote", refusal.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"-1, false", "0, true", "9223372036854775805, true", "9223372036854775806, false"})
    void aRelocatingTenancyIsHousedAndLeavesRoomForTwoRevisions(long tenancy, boolean accepted) {
        if (accepted) assertEquals(tenancy, request(HousingPolicy.Tier.PREMIUM, tenancy, ORIGIN).tenancyRevision());
        else assertThrows(IllegalArgumentException.class, () -> request(HousingPolicy.Tier.PREMIUM, tenancy, ORIGIN));
    }

    @Test void theSameTenancyBoundsAreFineWithoutARelocation() {
        assertEquals(-1L, request(HousingPolicy.Tier.PREMIUM, -1L, null).tenancyRevision());
        assertEquals(Long.MAX_VALUE - 1, request(HousingPolicy.Tier.PREMIUM, Long.MAX_VALUE - 1, null).tenancyRevision());
    }

    @Test void aRelocationNeverStaysInTheSameHome() {
        var sameHome = new Origin(new UUID(1, 3), new UUID(2, 2), 1, 3L, 1, new UUID(2, 3));
        assertThrows(IllegalArgumentException.class, () -> request(HousingPolicy.Tier.PREMIUM, 4L, sameHome));
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 0, true", "2147483647, 9223372036854775806, 3, true", "-1, 0, 0, false", "0, -1, 0, false",
            "0, 9223372036854775807, 0, false", "0, 0, -1, false", "0, 0, 4, false"})
    void originNumbersAreBounded(int epoch, long revision, int slot, boolean accepted) {
        if (accepted) assertEquals(slot, new Origin(new UUID(3, 1), new UUID(3, 2), epoch, revision, slot, new UUID(3, 3)).slot());
        else assertEquals("Invalid housing origin", assertThrows(IllegalArgumentException.class,
                () -> new Origin(new UUID(3, 1), new UUID(3, 2), epoch, revision, slot, new UUID(3, 3))).getMessage());
    }

    @Test void originIdentitiesAreRequired() {
        assertThrows(NullPointerException.class, () -> new Origin(null, new UUID(3, 2), 0, 0L, 0, new UUID(3, 3)));
        assertThrows(NullPointerException.class, () -> new Origin(new UUID(3, 1), null, 0, 0L, 0, new UUID(3, 3)));
        assertThrows(NullPointerException.class, () -> new Origin(new UUID(3, 1), new UUID(3, 2), 0, 0L, 0, null));
    }

    @Test void theTierIsRequiredAndAFingerprintNeedsAPolicy() {
        assertThrows(NullPointerException.class, () -> request(null, 4L, null));
        assertThrows(NullPointerException.class, () -> request(HousingPolicy.Tier.STANDARD, 4L, null).fingerprint(null));
    }
}
