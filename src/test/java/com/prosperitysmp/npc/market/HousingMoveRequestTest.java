package com.prosperitysmp.npc.market;

import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HousingMoveRequestTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(10, 5, 2, 7);
    private static final Object[] BASE = {id(1), id(2), id(3), id(4), id(5), "town", "flat", 6, 7L, 8L, -1L, 2, DAY};

    @Test void fingerprintHasIndependentGoldenAndIsStableAcrossReconstruction() {
        assertEquals("95dd860b53dd4089b8e12065ddd403a520edeed2923e13b6d4420c069c009b9c", make(BASE).fingerprint(POLICY));
        assertEquals(make(BASE), make(BASE.clone()));
        assertEquals(make(BASE).fingerprint(POLICY), make(BASE.clone()).fingerprint(new HousingMoveRequest.Policy(10, 5, 2, 7)));
    }
    @Test void everyQuoteFieldBindsTheFingerprintIncludingWorldAndMarket() {
        Object[] changed = {id(11), id(12), id(13), id(14), id(15), "other", "house", 7, 8L, 9L, 0L, 3, DAY.plusDays(1)};
        HashSet<String> hashes = new HashSet<>();
        hashes.add(make(BASE).fingerprint(POLICY));
        for (int i = 0; i < changed.length; i++) assertTrue(hashes.add(with(i, changed[i]).fingerprint(POLICY)), "field " + i);
        assertEquals(14, hashes.size());
    }
    @Test void everyPolicyFieldBindsTheFingerprint() {
        HashSet<String> hashes = new HashSet<>();
        hashes.add(make(BASE).fingerprint(POLICY));
        for (var policy : List.of(new HousingMoveRequest.Policy(11, 5, 2, 7), new HousingMoveRequest.Policy(10, 6, 2, 7),
                new HousingMoveRequest.Policy(10, 5, 3, 7), new HousingMoveRequest.Policy(10, 5, 2, 8)))
            assertTrue(hashes.add(make(BASE).fingerprint(policy)));
        assertThrows(NullPointerException.class, () -> make(BASE).fingerprint(null));
    }
    @Test void missingIdentityAndDayReject() {
        for (int field : new int[]{0, 1, 2, 3, 4, 12}) {
            assertThrows(NullPointerException.class, () -> with(field, null));
        }
    }
    @Test void keysCannotContainAmbiguousOrNoncanonicalText() {
        for (int field : new int[]{5, 6}) {
            for (String bad : List.of("", "Town", "a|b", "a/b", "two words", "a\n", "a".repeat(33), "1town"))
                assertThrows(IllegalArgumentException.class, () -> with(field, bad));
            assertThrows(IllegalArgumentException.class, () -> with(field, null));
            assertDoesNotThrow(() -> with(field, "a_0-b"));
        }
    }
    @Test void quoteRevisionBoundsPreserveTheNeverHousedSentinel() {
        for (int field : new int[]{8, 9}) {
            for (long bad : new long[]{Long.MIN_VALUE, -1, Long.MAX_VALUE})
                assertThrows(IllegalArgumentException.class, () -> with(field, bad));
            assertDoesNotThrow(() -> with(field, 0L));
            assertDoesNotThrow(() -> with(field, Long.MAX_VALUE - 1));
        }
        for (long bad : new long[]{Long.MIN_VALUE, -2, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> with(10, bad));
        for (long valid : new long[]{-1, 0, Long.MAX_VALUE - 1}) assertDoesNotThrow(() -> with(10, valid));
    }
    @Test void epochSlotAndDateBoundsRejectUnrepresentableQuotes() {
        assertThrows(IllegalArgumentException.class, () -> with(7, -1));
        assertDoesNotThrow(() -> with(7, Integer.MAX_VALUE));
        for (int bad : new int[]{-1, 4, Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class, () -> with(11, bad));
        for (LocalDate bad : List.of(LocalDate.MIN, LocalDate.of(0, 1, 1), LocalDate.of(10000, 1, 1), LocalDate.MAX))
            assertThrows(IllegalArgumentException.class, () -> with(12, bad));
        assertDoesNotThrow(() -> with(12, LocalDate.of(1, 1, 1)));
        assertDoesNotThrow(() -> with(12, LocalDate.of(9999, 12, 31)));
    }
    @Test void policyBoundsAreExplicitAndHaveNoPositiveDefault() {
        assertDoesNotThrow(() -> new HousingMoveRequest.Policy(0, 0, 0, 1));
        assertDoesNotThrow(() -> new HousingMoveRequest.Policy(512, 512, 4, 30));
        for (int bad : new int[]{-1, 513, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new HousingMoveRequest.Policy(bad, 5, 2, 7));
            assertThrows(IllegalArgumentException.class, () -> new HousingMoveRequest.Policy(10, bad, 2, 7));
        }
        for (int bad : new int[]{-1, 5}) assertThrows(IllegalArgumentException.class, () -> new HousingMoveRequest.Policy(10, 5, bad, 7));
        for (int bad : new int[]{0, 31}) assertThrows(IllegalArgumentException.class, () -> new HousingMoveRequest.Policy(10, 5, 2, bad));
    }

    private static UUID id(long id) { return new UUID(0, id); }
    private static HousingMoveRequest with(int index, Object value) {
        Object[] fields = BASE.clone(); fields[index] = value; return make(fields);
    }
    private static HousingMoveRequest make(Object[] fields) {
        try {
            return HousingMoveRequest.class.getDeclaredConstructor(UUID.class, UUID.class, UUID.class, UUID.class,
                    UUID.class, String.class, String.class, int.class, long.class, long.class, long.class, int.class,
                    LocalDate.class).newInstance(fields);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
