package com.prosperitysmp.npc.render;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationPauseTest {
    @Test void reopeningAndTopicActivityCannotExtendTheReadingWindow() {
        var pause = new ConversationPause();
        var visitor = new UUID(0, 1);
        assertEquals(91_000, pause.acquire(visitor, 1_000));
        assertTrue(pause.release(visitor));
        assertEquals(91_000, pause.acquire(visitor, 45_000));
        assertEquals(0, pause.acquire(visitor, 91_000));
        assertEquals(0, pause.acquire(visitor, 120_999));
        assertEquals(211_000, pause.acquire(visitor, 121_000));
    }

    @Test void oneDepartureCannotReleaseAnotherVisitorsConversation() {
        var pause = new ConversationPause();
        var first = new UUID(0, 1);
        var second = new UUID(0, 2);
        assertEquals(91_000, pause.acquire(first, 1_000));
        assertEquals(91_000, pause.acquire(second, 20_000));
        assertFalse(pause.release(first));
        assertFalse(pause.release(first));
        assertTrue(pause.release(second));
    }

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    private static ConversationPause renewing() { return new ConversationPause(ConversationPause.Policy.of(true, 180, 60)); }

    @Test void renewingPauseExtendsOnEachAcquireUpToTheCap() {
        var pause = renewing();
        assertEquals(91_000, pause.acquire(A, 1_000));
        assertEquals(140_000, pause.acquire(A, 50_000));
        assertEquals(181_000, pause.acquire(A, 100_000));
        assertEquals(181_000, pause.acquire(A, 150_000));
        assertTrue(pause.capReached(150_000));
        assertEquals(0, pause.acquire(A, 181_000));
    }

    @Test void cooldownRunsFromTheFinalDeadlineNotTheWindowStart() {
        var pause = renewing();
        pause.acquire(A, 1_000);
        assertEquals(140_000, pause.acquire(A, 50_000));
        assertEquals(181_000, pause.acquire(A, 100_000));
        assertEquals(0, pause.acquire(A, 240_999));
        assertEquals(331_000, pause.acquire(A, 241_000));

        var shorter = renewing();
        assertEquals(91_000, shorter.acquire(A, 1_000));
        assertEquals(170_000, shorter.acquire(A, 80_000));
        assertEquals(0, shorter.acquire(A, 200_000));
    }

    @Test void sharedCapAcrossViewers() {
        var pause = renewing();
        assertEquals(91_000, pause.acquire(A, 1_000));
        assertEquals(171_000, pause.acquire(A, 81_000));
        assertEquals(181_000, pause.acquire(B, 120_000));
        assertEquals(181_000, pause.acquire(A, 170_000));
        assertFalse(pause.release(A));
        assertTrue(pause.release(B));
    }

    @Test void releaseDoesNotResetWindow() {
        var pause = renewing();
        assertEquals(91_000, pause.acquire(A, 1_000));
        assertTrue(pause.release(A));
        assertEquals(130_000, pause.acquire(A, 40_000));
        assertEquals(181_000, pause.acquire(A, 120_000));
        assertEquals(181_000, pause.acquire(A, 179_000));
    }

    @Test void capReachedIsNeverTrueForLegacyOrBeforeCapOrAfterDeadline() {
        var legacy = new ConversationPause();
        for (long t : new long[] {0, 1_000, 45_000, 90_999, 91_000, 121_000}) {
            legacy.acquire(A, t);
            assertFalse(legacy.capReached(t), "legacy at " + t);
            assertFalse(legacy.capReached(t, false), "legacy, no renewal, at " + t);
        }
        var pause = renewing();
        pause.acquire(A, 1_000);
        assertEquals(140_000, pause.acquire(A, 50_000));
        assertFalse(pause.capReached(50_000));
        assertEquals(181_000, pause.acquire(A, 100_000));
        assertTrue(pause.capReached(180_999));
        assertFalse(pause.capReached(181_000));
    }

    @Test void policyClampsAndIsInertWhenFlagOff() {
        assertEquals(ConversationPause.Policy.LEGACY, ConversationPause.Policy.of(false, 300, 600));
        assertEquals(ConversationPause.Policy.LEGACY, ConversationPause.Policy.of(false, 10, 5));
        var low = ConversationPause.Policy.of(true, 10, 5);
        assertEquals(90_000, low.capMs());
        assertEquals(30_000, low.cooldownMs());
        var high = ConversationPause.Policy.of(true, 9_999, 9_999);
        assertEquals(300_000, high.capMs());
        assertEquals(600_000, high.cooldownMs());
        var defaults = ConversationPause.Policy.of(true, 180, 60);
        assertTrue(defaults.renews());
        assertEquals(90_000, defaults.readMs());
        assertEquals(180_000, defaults.capMs());
        assertEquals(60_000, defaults.cooldownMs());
    }

    /** Premium relocation walk: renewal is off for that avatar, so the deadline never passes windowStart + 90 s. */
    @Test void walkingHomeNeverRenewsPastTheReadingWindow() {
        var pause = renewing();
        assertEquals(91_000, pause.acquire(A, 1_000, false));
        assertEquals(91_000, pause.acquire(A, 50_000, false));
        assertEquals(91_000, pause.acquire(B, 90_999, false));
        assertTrue(pause.capReached(50_000, false));
        assertFalse(pause.capReached(50_000, true));
        assertEquals(0, pause.acquire(A, 91_000, false));
        assertFalse(pause.capReached(91_000, false));
        assertEquals(0, pause.acquire(A, 150_999, false));
        assertEquals(241_000, pause.acquire(A, 151_000, false));
    }

    /** A home walk that starts mid-stop keeps only the first 90 s of it, and the cooldown runs from there. */
    @Test void aHomeWalkAfterARenewedStopWasReleasedStillGetsOnlyItsFirst90Seconds() {
        // Renewed, then everyone left (release keeps the window), then the resident started a premium home walk
        // while no stop was live, so adoptPremiumWalk never clamped it: the next greeting must not get the renewal.
        var pause = renewing();
        assertEquals(91_000, pause.acquire(A, 1_000));
        assertEquals(150_000, pause.acquire(A, 60_000));
        assertTrue(pause.release(A));
        assertEquals(0, pause.acquire(B, 100_000, false), "past the window's first 90 s: no stop on a home walk");
        assertEquals(0, pause.acquire(B, 150_999, false), "cooldown runs from the clamped deadline (91 s + 60 s)");
        assertEquals(241_000, pause.acquire(B, 151_000, false));

        var early = renewing();
        assertEquals(91_000, early.acquire(A, 1_000));
        assertEquals(150_000, early.acquire(A, 60_000));
        assertTrue(early.release(A));
        assertEquals(91_000, early.acquire(B, 70_000, false), "inside the first 90 s the walk keeps only that much");
    }

    @Test void stopRenewingPullsARenewedStopBackToItsReadingWindow() {
        var pause = renewing();
        assertEquals(91_000, pause.acquire(A, 1_000));
        assertEquals(150_000, pause.acquire(A, 60_000));
        assertEquals(91_000, pause.stopRenewing());
        assertTrue(pause.capReached(70_000, false));
        assertEquals(91_000, pause.acquire(B, 80_000, false));
        assertEquals(0, pause.acquire(A, 91_000, false));
        assertEquals(0, pause.acquire(A, 150_999, false));
        assertEquals(241_000, pause.acquire(A, 151_000, false));

        var legacy = new ConversationPause();
        assertEquals(91_000, legacy.acquire(A, 1_000));
        assertEquals(91_000, legacy.stopRenewing());
        assertEquals(0, legacy.acquire(A, 120_999));
        assertEquals(211_000, legacy.acquire(A, 121_000));
    }

    /**
     * "Near the end": a stop never has more than 90 s left after any acquire, so the line that shows once the
     * stop can no longer move only ever shows in its last 90 seconds, home walk or not.
     */
    @Test void aStopNeverHasMoreThanNinetySecondsLeft() {
        var random = new java.util.Random(59);
        int atCap = 0;
        for (int run = 0; run < 300; run++) {
            var pause = renewing();
            long now = 0;
            for (int step = 0; step < 40; step++) {
                now += random.nextInt(30_000);
                boolean renew = random.nextInt(4) != 0;
                long deadline = pause.acquire(random.nextBoolean() ? A : B, now, renew);
                if (deadline == 0) continue;
                assertTrue(deadline > now && deadline - now <= 90_000, "run " + run + " at " + now + ": " + deadline);
                if (pause.capReached(now, renew)) atCap++;
            }
        }
        assertTrue(atCap > 100, "positive control: the cap was reached " + atCap + " times");
    }
}
