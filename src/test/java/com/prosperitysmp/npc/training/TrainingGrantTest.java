package com.prosperitysmp.npc.training;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The bounded training authorization: the job-name grammar, the half-open window and the benefit ceilings. */
class TrainingGrantTest {
    private static final UUID GRANT = new UUID(0, 1);
    private static final UUID PLAYER = new UUID(0, 2);
    private static final UUID TRAINER = new UUID(0, 3);
    private static final UUID SESSION = new UUID(0, 4);
    private static final Instant AT = Instant.parse("2026-10-14T15:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    private static TrainingGrant grant(String job, Instant accepted, Instant expires, int money, int xp) {
        return new TrainingGrant(GRANT, PLAYER, TRAINER, job, SESSION, accepted, DAY, expires, money, xp);
    }

    private static TrainingGrant standard() {
        return grant("Miner", AT, AT.plusSeconds(1800), 500, 1000);
    }

    @Test void theShippedBenefitConstantsAreFiveAndTenPercentForThirtyMinutes() {
        assertEquals(500, TrainingGrant.MONEY_BASIS_POINTS);
        assertEquals(1000, TrainingGrant.XP_BASIS_POINTS);
        assertEquals(30, TrainingGrant.DURATION_MINUTES);
    }

    @Test void aGrantKeepsEveryFieldAndLowercasesTheJob() {
        TrainingGrant g = standard();
        assertEquals(GRANT, g.grantId());
        assertEquals(PLAYER, g.playerId());
        assertEquals(TRAINER, g.trainerId());
        assertEquals("miner", g.jobName());
        assertEquals(SESSION, g.sessionId());
        assertEquals(AT, g.acceptedAt());
        assertEquals(DAY, g.economyDay());
        assertEquals(AT.plusSeconds(1800), g.expiresAt());
        assertEquals(500, g.moneyBasisPoints());
        assertEquals(1000, g.xpBasisPoints());
    }

    @ParameterizedTest
    @CsvSource({"Miner,miner", "miner,miner", "MINER,miner", "Wood_Cutter,wood_cutter", "fisher-man,fisher-man",
            "a,a", "Z9,z9", "Farmer2,farmer2"})
    void canonicalJobLowercasesAValidName(String raw, String expected) {
        assertEquals(expected, TrainingGrant.canonicalJob(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1abc", "_abc", "-abc", "a b", " miner", "miner ", "miner\n", "mi\tner", "miner;op",
            "mi.ner", "mi/ner", "m\u00fcller", "\u00e9tienne", "\u0130stanbul", "miner\u0000", "\uff2d\uff29\uff2e\uff25\uff32"})
    void canonicalJobRefusesAnythingOutsideTheGrammar(String raw) {
        assertThrows(IllegalArgumentException.class, () -> TrainingGrant.canonicalJob(raw));
    }

    @Test void canonicalJobAcceptsFortyEightCharactersButNotFortyNine() {
        String forty8 = "a" + "b".repeat(47);
        assertEquals(forty8, TrainingGrant.canonicalJob(forty8));
        assertThrows(IllegalArgumentException.class, () -> TrainingGrant.canonicalJob(forty8 + "b"));
    }

    @Test void canonicalJobRefusesNull() {
        assertThrows(NullPointerException.class, () -> TrainingGrant.canonicalJob(null));
    }

    @Test void canonicalJobIsNotAffectedByTheDefaultLocale() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals("fisher", TrainingGrant.canonicalJob("FISHER"));
            assertEquals("iron", TrainingGrant.canonicalJob("IRON"));
        } finally {
            java.util.Locale.setDefault(old);
        }
    }

    @Test void theConstructorStoresTheCanonicalJob() {
        assertEquals("farmer", grant("FARMER", AT, AT.plusSeconds(60), 0, 0).jobName());
        assertThrows(IllegalArgumentException.class, () -> grant("farm er", AT, AT.plusSeconds(60), 0, 0));
    }

    @Test void theWindowIsHalfOpenAcceptedInclusiveExpiryExclusive() {
        TrainingGrant g = standard();
        assertFalse(g.activeAt(AT.minusMillis(1)));
        assertTrue(g.activeAt(AT));
        assertTrue(g.activeAt(AT.plusMillis(1)));
        assertTrue(g.activeAt(AT.plusSeconds(1800).minusMillis(1)));
        assertFalse(g.activeAt(AT.plusSeconds(1800)));
        assertFalse(g.activeAt(AT.plusSeconds(1800).plusMillis(1)));
    }

    @Test void theWindowDoesNotLeakAtTheFarEnds() {
        TrainingGrant g = standard();
        assertFalse(g.activeAt(Instant.EPOCH));
        assertFalse(g.activeAt(Instant.MAX));
        assertFalse(g.activeAt(Instant.MIN));
    }

    @Test void activeAtRefusesNull() {
        assertThrows(NullPointerException.class, () -> standard().activeAt(null));
    }

    @Test void theLongestAllowedSessionIsExactlyThirtyMinutes() {
        assertDoesNotThrow(() -> grant("miner", AT, AT.plusSeconds(1800), 500, 1000));
        assertThrows(IllegalArgumentException.class, () -> grant("miner", AT, AT.plusSeconds(1800).plusMillis(1), 500, 1000));
        assertThrows(IllegalArgumentException.class, () -> grant("miner", AT, AT.plusSeconds(1801), 500, 1000));
    }

    @Test void theShortestAllowedSessionIsOneMillisecond() {
        TrainingGrant g = grant("miner", AT, AT.plusMillis(1), 500, 1000);
        assertTrue(g.activeAt(AT));
        assertFalse(g.activeAt(AT.plusMillis(1)));
    }

    @Test void aSessionThatEndsWhereItStartedOrBeforeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> grant("miner", AT, AT, 500, 1000));
        assertThrows(IllegalArgumentException.class, () -> grant("miner", AT, AT.minusMillis(1), 500, 1000));
        assertThrows(IllegalArgumentException.class, () -> grant("miner", AT, AT.minusSeconds(1800), 500, 1000));
    }

    @Test void timesAreTruncatedToTheMillisecondSoASubMillisecondGapIsNotASession() {
        Instant accepted = Instant.parse("2026-10-14T15:00:00.000100Z");
        assertThrows(IllegalArgumentException.class, () -> grant("miner", accepted, accepted.plusNanos(500_000), 500, 1000));
        TrainingGrant g = grant("miner", accepted, Instant.parse("2026-10-14T15:00:00.001900Z"), 500, 1000);
        assertEquals(Instant.parse("2026-10-14T15:00:00.000Z"), g.acceptedAt());
        assertEquals(Instant.parse("2026-10-14T15:00:00.001Z"), g.expiresAt());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "500,1000", "499,999", "250,500"})
    void benefitsUpToTheCeilingAreKept(int money, int xp) {
        TrainingGrant g = grant("miner", AT, AT.plusSeconds(600), money, xp);
        assertEquals(money, g.moneyBasisPoints());
        assertEquals(xp, g.xpBasisPoints());
    }

    @ParameterizedTest
    @CsvSource({"501,1000", "500,1001", "-1,0", "0,-1", "10000,0", "0,10000", "2147483647,0", "0,2147483647",
            "-2147483648,0", "0,-2147483648"})
    void benefitsAboveTheCeilingOrBelowZeroAreRefused(int money, int xp) {
        assertThrows(IllegalArgumentException.class, () -> grant("miner", AT, AT.plusSeconds(600), money, xp));
    }

    @Test void aMissingIdentityIsRefused() {
        assertThrows(NullPointerException.class, () -> new TrainingGrant(null, PLAYER, TRAINER, "miner", SESSION, AT, DAY, AT.plusSeconds(60), 0, 0));
        assertThrows(NullPointerException.class, () -> new TrainingGrant(GRANT, null, TRAINER, "miner", SESSION, AT, DAY, AT.plusSeconds(60), 0, 0));
        assertThrows(NullPointerException.class, () -> new TrainingGrant(GRANT, PLAYER, null, "miner", SESSION, AT, DAY, AT.plusSeconds(60), 0, 0));
        assertThrows(NullPointerException.class, () -> new TrainingGrant(GRANT, PLAYER, TRAINER, "miner", null, AT, DAY, AT.plusSeconds(60), 0, 0));
        assertThrows(NullPointerException.class, () -> new TrainingGrant(GRANT, PLAYER, TRAINER, "miner", SESSION, AT, null, AT.plusSeconds(60), 0, 0));
        assertThrows(NullPointerException.class, () -> new TrainingGrant(GRANT, PLAYER, TRAINER, null, SESSION, AT, DAY, AT.plusSeconds(60), 0, 0));
        assertThrows(NullPointerException.class, () -> grant("miner", null, AT.plusSeconds(60), 0, 0));
        assertThrows(NullPointerException.class, () -> grant("miner", AT, null, 0, 0));
    }

    @Test void aTimeBeyondTheEpochMillisecondRangeIsNotAccepted() {
        assertThrows(RuntimeException.class, () -> grant("miner", Instant.MAX, Instant.MAX, 0, 0));
        assertThrows(RuntimeException.class, () -> grant("miner", Instant.MIN, Instant.MIN, 0, 0));
    }

    @Test void aTimeBeyondTheEpochMillisecondRangeIsRefusedAsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> grant("miner", Instant.MAX, Instant.MAX, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> grant("miner", Instant.MIN, Instant.MIN, 0, 0));
    }
}
