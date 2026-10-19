package com.prosperitysmp.npc.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Millisecond-exact behaviour of the shared conversation pause window and its cooldown. */
class ConversationPauseBoundaryTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    @Test void theLegacyPolicyIsNinetySecondsOfReadingAndThirtyOfCooldownWithNoRenewal() {
        assertEquals(new ConversationPause.Policy(false, 90_000, 90_000, 30_000), ConversationPause.Policy.LEGACY);
    }

    @Test void aFirstVisitorAtTimeZeroOpensAWindowOfNinetySeconds() {
        assertEquals(90_000, new ConversationPause().acquire(A, 0));
    }

    @Test void theWindowDeadlineIsTheOpeningTimePlusNinetySeconds() {
        assertEquals(91_000, new ConversationPause().acquire(A, 1_000));
        assertEquals(1_000_090_000L, new ConversationPause().acquire(A, 1_000_000_000L));
    }

    @Test void everyVisitorInsideTheWindowSharesItsDeadline() {
        ConversationPause pause = new ConversationPause();
        assertEquals(91_000, pause.acquire(A, 1_000));
        assertEquals(91_000, pause.acquire(B, 50_000));
        assertEquals(91_000, pause.acquire(A, 90_999));
    }

    @Test void atTheDeadlineTheWindowIsOverAndNobodyCanPauseDuringTheCooldown() {
        ConversationPause pause = new ConversationPause();
        pause.acquire(A, 1_000);
        assertEquals(0, pause.acquire(A, 91_000));
        assertEquals(0, pause.acquire(B, 91_001));
        assertEquals(0, pause.acquire(B, 120_999));
    }

    @Test void theCooldownEndsThirtySecondsAfterTheDeadlineAndANewWindowOpens() {
        ConversationPause pause = new ConversationPause();
        pause.acquire(A, 1_000);
        assertEquals(211_000, pause.acquire(B, 121_000));
    }

    @Test void aVisitorRefusedDuringTheCooldownIsNotRecorded() {
        ConversationPause pause = new ConversationPause();
        pause.acquire(A, 0);
        assertEquals(0, pause.acquire(B, 95_000));
        assertTrue(pause.release(A), "only the first visitor was ever recorded");
    }

    @Test void releasingTheLastVisitorSaysSoAndReleasingAnUnknownOneIsHarmless() {
        ConversationPause pause = new ConversationPause();
        pause.acquire(A, 0);
        pause.acquire(B, 10);
        assertFalse(pause.release(A));
        assertTrue(pause.release(B));
        assertTrue(pause.release(B));
        assertTrue(pause.release(new UUID(9, 9)));
    }

    @Test void releasingAnUnknownVisitorWhileOthersRemainReportsThatTheyRemain() {
        ConversationPause pause = new ConversationPause();
        pause.acquire(A, 0);
        assertFalse(pause.release(new UUID(9, 9)));
    }

    @Test void aNewWindowForgetsTheVisitorsOfTheOldOne() {
        ConversationPause pause = new ConversationPause();
        pause.acquire(A, 0);
        pause.acquire(B, 121_000);
        assertFalse(pause.release(A), "B is the only visitor of the new window");
        assertTrue(pause.release(B));
    }

    @Test void releasingNeverShortensOrReopensTheWindow() {
        ConversationPause pause = new ConversationPause();
        assertEquals(90_000, pause.acquire(A, 0));
        pause.release(A);
        assertEquals(90_000, pause.acquire(A, 40_000));
        pause.release(A);
        assertEquals(0, pause.acquire(B, 100_000));
    }

    @Test void theLegacyPolicyNeverReportsACapReachedAndStopRenewingLeavesTheDeadlineAlone() {
        ConversationPause pause = new ConversationPause();
        pause.acquire(A, 0);
        for (long now : new long[] {0, 1, 89_999, 90_000, 200_000}) assertFalse(pause.capReached(now), "t=" + now);
        assertEquals(90_000, pause.stopRenewing());
    }

    @Test void aPolicyOffGivesTheLegacyPolicyWhateverTheOtherSettings() {
        assertSame(ConversationPause.Policy.LEGACY, ConversationPause.Policy.of(false, 300, 600));
        assertSame(ConversationPause.Policy.LEGACY, ConversationPause.Policy.of(false, 0, 0));
        assertSame(ConversationPause.Policy.LEGACY, ConversationPause.Policy.of(false, Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @ParameterizedTest
    @CsvSource({"120,60,120000,60000", "90,30,90000,30000", "300,600,300000,600000", "0,0,90000,30000", "89,29,90000,30000",
            "301,601,300000,600000", "-5,-5,90000,30000", "1000000,1000000,300000,600000",
            "2147483647,2147483647,300000,600000", "-2147483648,-2147483648,90000,30000"})
    void aRenewingPolicyClampsTheCapAndTheCooldown(int capSeconds, int cooldownSeconds, long capMs, long cooldownMs) {
        assertEquals(new ConversationPause.Policy(true, 90_000, capMs, cooldownMs),
                ConversationPause.Policy.of(true, capSeconds, cooldownSeconds));
    }

    @Test void aRenewingVisitHalfwayPushesTheDeadlineToTheCap() {
        ConversationPause pause = new ConversationPause(ConversationPause.Policy.of(true, 120, 60));
        assertEquals(90_000, pause.acquire(A, 0));
        assertEquals(120_000, pause.acquire(B, 30_000));
        assertTrue(pause.capReached(30_000));
        assertTrue(pause.capReached(119_999));
        assertFalse(pause.capReached(120_000), "the window is over at the deadline");
    }

    @Test void aRenewingVisitBeforeTheCapOnlyExtendsByWhatItAdds() {
        ConversationPause pause = new ConversationPause(ConversationPause.Policy.of(true, 300, 60));
        assertEquals(90_000, pause.acquire(A, 0));
        assertEquals(100_000, pause.acquire(A, 10_000));
        assertEquals(130_000, pause.acquire(B, 40_000));
        assertFalse(pause.capReached(40_000));
    }

    @Test void theCooldownOfARenewedWindowRunsFromItsFinalDeadline() {
        ConversationPause pause = new ConversationPause(ConversationPause.Policy.of(true, 120, 60));
        pause.acquire(A, 0);
        pause.acquire(B, 30_000);
        assertEquals(0, pause.acquire(A, 179_999));
        assertEquals(270_000, pause.acquire(A, 180_000));
    }

    @Test void aVisitThatMayNotRenewNeverExtendsTheDeadlineEvenUnderARenewingPolicy() {
        ConversationPause pause = new ConversationPause(ConversationPause.Policy.of(true, 300, 60));
        assertEquals(90_000, pause.acquire(A, 0, false));
        assertEquals(90_000, pause.acquire(B, 60_000, false));
    }

    @Test void stopRenewingPullsARenewedDeadlineBackToTheReadingWindow() {
        ConversationPause pause = new ConversationPause(ConversationPause.Policy.of(true, 300, 60));
        pause.acquire(A, 0);
        assertEquals(130_000, pause.acquire(B, 40_000));
        assertEquals(90_000, pause.stopRenewing());
        assertEquals(0, pause.acquire(A, 90_000));
    }

    @Test void theCapReachedCheckForAWalkerIsTrueWhileTheWindowIsOpen() {
        ConversationPause pause = new ConversationPause(ConversationPause.Policy.of(true, 300, 60));
        pause.acquire(A, 0);
        assertTrue(pause.capReached(10_000, false));
        assertFalse(pause.capReached(10_000, true));
        assertFalse(pause.capReached(90_000, false));
    }

    @Test void aNullPolicyIsRefused() {
        assertThrows(NullPointerException.class, () -> new ConversationPause(null));
    }
}
