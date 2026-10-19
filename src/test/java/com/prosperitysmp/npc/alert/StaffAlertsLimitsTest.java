package com.prosperitysmp.npc.alert;

import com.prosperitysmp.npc.alert.StaffAlerts.Kind;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffAlertsLimitsTest {
    private boolean on = true;
    private LocalDate day = LocalDate.of(2026, 10, 18);
    private final List<String> logged = new ArrayList<>();
    private final StaffAlerts alerts = new StaffAlerts(() -> on, () -> day, logged::add);

    @Test
    void theLimitsAreSixtyFourQueuedAndTwentyPerDrain() {
        assertEquals(64, StaffAlerts.MAX_QUEUED);
        assertEquals(20, StaffAlerts.MAX_DRAIN);
    }

    @Test
    void thereAreEighteenKinds() {
        assertEquals(18, Kind.values().length);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void everyKindRaisesOnceAndLogsItsOwnName(Kind kind) {
        assertTrue(alerts.raise(kind, "s", "text"));
        assertFalse(alerts.raise(kind, "s", "text"));
        assertEquals(List.of("Staff alert " + kind.name() + ": text"), logged);
        assertEquals(List.of("text"), alerts.drain(5));
    }

    @Test
    void aNullSubjectAndAnEmptySubjectAreTheSameSubject() {
        assertTrue(alerts.raise(Kind.SERVER_CAP_NEAR, null, "a"));
        assertFalse(alerts.raise(Kind.SERVER_CAP_NEAR, "", "b"));
        assertEquals(List.of("a"), alerts.drain(5));
    }

    @Test
    void subjectsDifferingOnlyInCaseAreDifferentSubjects() {
        assertTrue(alerts.raise(Kind.SERVER_CAP_NEAR, "Home", "a"));
        assertTrue(alerts.raise(Kind.SERVER_CAP_NEAR, "home", "b"));
    }

    @Test
    void aSubjectWithALineBreakMatchesTheSameSubjectOnOneLine() {
        assertTrue(alerts.raise(Kind.PAYOUT_REFUSED, "a\nb", "x"));
        assertFalse(alerts.raise(Kind.PAYOUT_REFUSED, "a b", "x"));
        assertFalse(alerts.raise(Kind.PAYOUT_REFUSED, "a\r\nb", "x"));
    }

    @Test
    void aNullTextIsQueuedAsAnEmptyLine() {
        assertTrue(alerts.raise(Kind.BILL_CAP_REACHED, "s", null));
        assertEquals(List.of(""), alerts.drain(5));
        assertEquals(List.of("Staff alert BILL_CAP_REACHED: "), logged);
    }

    @Test
    void textLongerThanTwoHundredCharactersIsCutToTwoHundred() {
        alerts.raise(Kind.BILL_CAP_REACHED, "s", "x".repeat(300));
        String line = alerts.drain(1).get(0);
        assertEquals(200, line.length());
        assertEquals("x".repeat(200), line);
        assertEquals("Staff alert BILL_CAP_REACHED: " + "x".repeat(200), logged.get(0));
    }

    @Test
    void textOfExactlyTwoHundredCharactersIsKeptWhole() {
        alerts.raise(Kind.BILL_CAP_REACHED, "s", "y".repeat(200));
        assertEquals("y".repeat(200), alerts.drain(1).get(0));
    }

    @Test
    void lineBreaksAndDashesBecomeSingleSpaces() {
        alerts.raise(Kind.BILL_CAP_REACHED, "1", "a\r\nb");
        alerts.raise(Kind.BILL_CAP_REACHED, "2", "a—b");
        alerts.raise(Kind.BILL_CAP_REACHED, "3", "a – b");
        alerts.raise(Kind.BILL_CAP_REACHED, "4", "a\n\n\nb");
        assertEquals(List.of("a b", "a b", "a b", "a b"), alerts.drain(10));
    }

    @Test
    void runsOfWhitespaceCollapseAndTheEndsAreTrimmed() {
        alerts.raise(Kind.BILL_CAP_REACHED, "1", "   a     b   ");
        alerts.raise(Kind.BILL_CAP_REACHED, "2", "a\t\tb");
        assertEquals(List.of("a b", "a b"), alerts.drain(10));
    }

    @Test
    void aRepeatIsRefusedUntilTheEconomyDayChanges() {
        assertTrue(alerts.raise(Kind.RENT_PROPERTY_HELD, "p", "t"));
        assertFalse(alerts.raise(Kind.RENT_PROPERTY_HELD, "p", "t"));
        day = day.plusDays(1);
        assertTrue(alerts.raise(Kind.RENT_PROPERTY_HELD, "p", "t"));
        assertFalse(alerts.raise(Kind.RENT_PROPERTY_HELD, "p", "t"));
        day = day.minusDays(1);
        assertTrue(alerts.raise(Kind.RENT_PROPERTY_HELD, "p", "t"), "a different day forgets every earlier key");
    }

    @Test
    void aRefusedRepeatIsNotLoggedOrQueuedAgain() {
        alerts.raise(Kind.RENT_PROPERTY_HELD, "p", "t");
        alerts.raise(Kind.RENT_PROPERTY_HELD, "p", "t");
        assertEquals(1, logged.size());
        assertEquals(1, alerts.size());
    }

    @Test
    void aFlagThatCannotBeReadMeansNoAlert() {
        var broken = new StaffAlerts(() -> { throw new IllegalStateException("config gone"); }, () -> day, logged::add);
        assertFalse(broken.raise(Kind.SERVER_CAP_NEAR, "s", "t"));
        assertEquals(0, broken.size());
        assertTrue(logged.isEmpty());
    }

    @Test
    void aDayThatCannotBeReadMeansNoAlert() {
        var broken = new StaffAlerts(() -> true, () -> { throw new IllegalStateException("clock gone"); }, logged::add);
        assertFalse(broken.raise(Kind.SERVER_CAP_NEAR, "s", "t"));
        assertEquals(0, broken.size());
    }

    @Test
    void aFailingLogStillLeavesTheAlertQueued() {
        var noisy = new StaffAlerts(() -> true, () -> day, line -> { throw new IllegalStateException("log closed"); });
        assertTrue(noisy.raise(Kind.SERVER_CAP_NEAR, "s", "t"));
        assertEquals(List.of("t"), noisy.drain(5));
    }

    @Test
    void aNullKindIsRefused() {
        assertThrows(NullPointerException.class, () -> alerts.raise(null, "s", "t"));
    }

    @Test
    void everyConstructorArgumentIsRequired() {
        assertThrows(NullPointerException.class, () -> new StaffAlerts(null, () -> day, logged::add));
        assertThrows(NullPointerException.class, () -> new StaffAlerts(() -> true, null, logged::add));
        assertThrows(NullPointerException.class, () -> new StaffAlerts(() -> true, () -> day, null));
    }

    @Test
    void theSixtyFifthAlertDropsTheOldestAndDrainSaysSoInTheSingular() {
        for (int i = 0; i < 65; i++) alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "s" + i, "alert " + i);
        assertEquals(64, alerts.size());
        assertEquals(1, alerts.droppedCount());
        List<String> first = alerts.drain(2);
        assertEquals(List.of("1 older staff alert was dropped.", "alert 1"), first);
        assertEquals(0, alerts.droppedCount());
    }

    @Test
    void theDroppedNoticeCountsAgainstTheDrainLimit() {
        for (int i = 0; i < 70; i++) alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "s" + i, "alert " + i);
        List<String> page = alerts.drain(20);
        assertEquals(20, page.size());
        assertEquals("6 older staff alerts were dropped.", page.get(0));
        assertEquals("alert 6", page.get(1));
        assertEquals("alert 24", page.get(19));
        assertEquals(64 - 19, alerts.size());
    }

    @Test
    void aDrainOfOneWithADroppedNoticeReturnsOnlyTheNoticeAndKeepsTheQueue() {
        for (int i = 0; i < 66; i++) alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "s" + i, "alert " + i);
        assertEquals(List.of("2 older staff alerts were dropped."), alerts.drain(1));
        assertEquals(64, alerts.size());
        assertEquals("alert 2", alerts.drain(1).get(0));
    }

    @Test
    void aDrainLimitOfZeroOrLessStillReturnsOneLine() {
        alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "a", "one");
        alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "b", "two");
        assertEquals(List.of("one"), alerts.drain(0));
        assertEquals(List.of("two"), alerts.drain(Integer.MIN_VALUE));
    }

    @Test
    void aDrainOfAnEmptyQueueIsEmptyAndTheResultCannotBeChanged() {
        List<String> empty = alerts.drain(5);
        assertEquals(List.of(), empty);
        assertThrows(UnsupportedOperationException.class, () -> empty.add("x"));
    }

    @Test
    void drainedAlertsAreGoneAndKeepTheirDayLongDedupe() {
        alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "a", "one");
        assertEquals(List.of("one"), alerts.drain(5));
        assertEquals(0, alerts.size());
        assertFalse(alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, "a", "one"));
    }

    @Test
    void afterAThousandAndTwentyFourDistinctKeysTheMemoryIsClearedAndARepeatIsRaisedAgain() {
        for (int i = 0; i < 1023; i++) alerts.raise(Kind.SERVER_CAP_NEAR, "s" + i, "t");
        assertFalse(alerts.raise(Kind.SERVER_CAP_NEAR, "s0", "t"), "still remembered with 1023 keys");
        assertTrue(alerts.raise(Kind.SERVER_CAP_NEAR, "s1023", "t"));
        assertTrue(alerts.raise(Kind.SERVER_CAP_NEAR, "s0", "t"), "the 1024 key memory was cleared");
        assertFalse(alerts.raise(Kind.SERVER_CAP_NEAR, "s0", "t"));
    }

    @Test
    void manyThreadsRaisingDistinctAlertsLeaveASizeAndDropCountThatAddUp() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int thread = t;
            results.add(pool.submit(() -> {
                start.await();
                int accepted = 0;
                for (int i = 0; i < 100; i++) if (alerts.raise(Kind.PAYMENT_HELD_FOR_REVIEW, thread + "-" + i, "t")) accepted++;
                return accepted;
            }));
        }
        start.countDown();
        int accepted = 0;
        for (var result : results) accepted += result.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(400, accepted);
        assertEquals(64, alerts.size());
        assertEquals(336L, alerts.droppedCount());
    }

    @ParameterizedTest
    @CsvSource({"0,100,false", "89,100,false", "90,100,true", "91,100,true", "100,100,true", "500,100,true", "0,1,false",
            "1,1,true", "8,10,false", "9,10,true", "9,11,false", "10,11,true", "13499,15000,false", "13500,15000,true",
            "-1,100,false", "-100,100,false", "50,0,false", "50,-100,false", "0,0,false"})
    void capNearIsNinetyPercentRoundedUpOfAPositiveCap(long spent, long cap, boolean expected) {
        assertEquals(expected, StaffAlerts.capNear(spent, cap));
    }

    @Test
    void capNearHoldsForLargeButRealisticCaps() {
        long cap = 1_000_000_000_000_000_000L;
        assertFalse(StaffAlerts.capNear(899_999_999_999_999_999L, cap));
        assertTrue(StaffAlerts.capNear(900_000_000_000_000_000L, cap));
    }

    @Test
    void capNearDoesNotThrowForTheLargestCap() {
        assertTrue(StaffAlerts.capNear(Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(StaffAlerts.capNear(0, Long.MAX_VALUE));
    }
}
