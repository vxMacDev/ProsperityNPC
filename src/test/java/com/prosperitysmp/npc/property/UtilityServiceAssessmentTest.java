package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.property.UtilityServiceAssessment.Case;
import com.prosperitysmp.npc.property.UtilityServiceAssessment.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityServiceAssessmentTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T12:00:00Z");
    private static final Instant FROM = CAPTURED.minus(Duration.ofDays(30));

    @Test
    void emptyHistoryIsUnknownNotPerfectService() {
        var snapshot = assessment(List.of());
        for (Outcome outcome : Outcome.values()) assertEquals(0, snapshot.count(outcome));
        assertEquals(0, snapshot.assessedCount());
        assertEquals(0, snapshot.distinctBillDays());
        assertTrue(snapshot.onTimeRate().isEmpty());
    }

    @Test
    void countsAssessedOutcomesAndComputesFractionalRate() {
        var snapshot = assessment(List.of(at(bill(1, DAY), 11L, Outcome.ON_TIME),
                at(bill(2, DAY), 12L, Outcome.ON_TIME), at(bill(3, DAY.minusDays(1)), 13L, Outcome.LATE),
                at(bill(4, DAY), null, Outcome.OVERDUE)));
        assertEquals(2, snapshot.count(Outcome.ON_TIME));
        assertEquals(1, snapshot.count(Outcome.LATE));
        assertEquals(1, snapshot.count(Outcome.OVERDUE));
        assertEquals(0, snapshot.count(Outcome.HANDOVER_REVIEW));
        assertEquals(0, snapshot.count(Outcome.UNVERIFIED_PAYMENT));
        assertEquals(4, snapshot.assessedCount());
        assertEquals(2, snapshot.distinctBillDays());
        assertEquals(0.5, snapshot.onTimeRate().orElseThrow());
    }

    @Test
    void knownFailuresYieldZeroAndKnownSuccessYieldsOne() {
        assertEquals(0.0, assessment(List.of(at(bill(1, DAY), 11L, Outcome.LATE),
                at(bill(2, DAY), 12L, Outcome.OVERDUE))).onTimeRate().orElseThrow());
        assertEquals(1.0, assessment(List.of(at(bill(1, DAY), 11L, Outcome.ON_TIME)))
                .onTimeRate().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"HANDOVER_REVIEW", "UNVERIFIED_PAYMENT"})
    void unknownEvidenceRemainsVisibleAndSuppressesAnOtherwisePerfectRate(Outcome unknown) {
        Case review = at(bill(2, DAY.plusDays(1)), 12L, unknown);
        var snapshot = assessment(List.of(at(bill(1, DAY), 11L, Outcome.ON_TIME), review));
        assertEquals(2, snapshot.cases().size());
        assertTrue(snapshot.cases().contains(review));
        assertEquals(1, snapshot.count(unknown));
        assertEquals(1, snapshot.assessedCount());
        assertEquals(1, snapshot.distinctBillDays());
        assertTrue(snapshot.onTimeRate().isEmpty());
    }

    @Test
    void exclusivelyUnknownCasesHaveNoAssessedBillLabels() {
        var snapshot = assessment(List.of(at(bill(1, DAY), null, Outcome.HANDOVER_REVIEW),
                at(bill(2, DAY.plusDays(1)), 12L, Outcome.UNVERIFIED_PAYMENT)));
        assertEquals(1, snapshot.count(Outcome.HANDOVER_REVIEW));
        assertEquals(1, snapshot.count(Outcome.UNVERIFIED_PAYMENT));
        assertEquals(0, snapshot.assessedCount());
        assertEquals(0, snapshot.distinctBillDays());
        assertTrue(snapshot.onTimeRate().isEmpty());
    }

    @Test
    void copiesWithoutMutatingInputAndOrdersByDuePropertyThenDay() {
        Case earlier = at(bill(9, DAY.minusDays(1)), null, Outcome.OVERDUE);
        Case earlierLabel = at(bill(2, DAY.minusDays(1), "-12:00", 12), null, Outcome.OVERDUE);
        Case lowerProperty = at(bill(2, DAY), null, Outcome.OVERDUE);
        Case higherProperty = at(bill(3, DAY), null, Outcome.OVERDUE);
        Case later = at(bill(1, DAY.plusDays(1)), null, Outcome.HANDOVER_REVIEW);
        assertEquals(earlierLabel.bill().dueAt(), lowerProperty.bill().dueAt());
        var input = new ArrayList<>(List.of(later, higherProperty, lowerProperty, earlierLabel, earlier));
        var snapshot = assessment(input);
        assertEquals(List.of(later, higherProperty, lowerProperty, earlierLabel, earlier), input);
        assertEquals(List.of(earlier, earlierLabel, lowerProperty, higherProperty, later), snapshot.cases());
        input.clear();
        assertEquals(5, snapshot.cases().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cases().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cases().add(earlier));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cases().set(0, later));
        var reordered = assessment(List.of(earlier, earlierLabel, lowerProperty, higherProperty, later));
        assertEquals(snapshot, reordered);
        assertEquals(snapshot.hashCode(), reordered.hashCode());
        assertEquals(OWNER, snapshot.ownerId());
        assertEquals(CAPTURED, snapshot.capturedAt());
        assertEquals(FROM, snapshot.dueFrom());
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void propertyDayDuplicatesCannotHideBehindDifferentTermsOrOutcomes(Outcome outcome) {
        Case original = at(bill(1, DAY), 11L, Outcome.ON_TIME);
        UtilityBill changed = new UtilityBill(new UUID(0, 1), OWNER, 1, 2, DAY, 3, 1, 0,
                Instant.parse("2026-09-05T12:00:00Z"), "America/Chicago", 4);
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(original, original)));
        assertThrows(IllegalArgumentException.class,
                () -> assessment(List.of(original, at(changed, 12L, outcome))));
    }

    @Test
    void differentPropertiesOnSameDayAndSamePropertyOnDifferentDaysAreDistinctBills() {
        var snapshot = assessment(List.of(at(bill(1, DAY), null, Outcome.OVERDUE),
                at(bill(2, DAY), null, Outcome.OVERDUE),
                at(bill(1, DAY.plusDays(1)), null, Outcome.OVERDUE)));
        assertEquals(3, snapshot.assessedCount());
        assertEquals(2, snapshot.distinctBillDays());
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void paymentIdsAreUniqueAcrossPropertiesDaysAndAllOutcomes(Outcome outcome) {
        Case original = at(bill(1, DAY), 11L, Outcome.ON_TIME);
        Case reused = at(bill(2, DAY.plusDays(1)), 11L, outcome);
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(original, reused)));
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void everyBillMustBelongToTheAssessmentOwnerIncludingUnknownCases(Outcome outcome) {
        Case utility = at(bill(1, DAY), 11L, outcome);
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityServiceAssessment(new UUID(0, 99), CAPTURED, FROM, List.of(utility)));
        assertDoesNotThrow(() -> assessment(List.of(utility)));
        UtilityBill foreign = new UtilityBill(new UUID(0, 2), new UUID(0, 99), 0, 0, DAY, 4, 2, 3,
                utility.bill().issuedAt(), "UTC", 0);
        assertThrows(IllegalArgumentException.class,
                () -> assessment(List.of(utility, at(foreign, 12L, outcome))));
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void eachOutcomeRequiresExactlyItsPermittedEvidenceShape(Outcome outcome) {
        UtilityBill bill = bill(1, DAY);
        Instant payment = outcome == Outcome.LATE ? bill.dueAt().plusMillis(1) : bill.dueAt();
        for (boolean hasPayment : new boolean[]{false, true}) {
            for (boolean hasEvent : new boolean[]{false, true}) {
                Instant paidAt = hasPayment ? payment : null;
                Long event = hasEvent ? Long.valueOf(11) : null;
                boolean valid = switch (outcome) {
                    case ON_TIME, LATE -> hasPayment && hasEvent;
                    case OVERDUE, HANDOVER_REVIEW, UNVERIFIED_PAYMENT -> !hasPayment;
                };
                String context = outcome + " payment=" + hasPayment + " event=" + hasEvent;
                if (valid) assertDoesNotThrow(() -> new Case(bill, event, paidAt, outcome), context);
                else assertThrows(IllegalArgumentException.class, () -> new Case(bill, event, paidAt, outcome), context);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void optionalEventIdsMustStillBePositiveAndMayUseLongMaxValue(Outcome outcome) {
        UtilityBill bill = bill(1, DAY);
        for (long invalid : new long[]{Long.MIN_VALUE, -1, 0})
            assertThrows(IllegalArgumentException.class, () -> at(bill, invalid, outcome));
        assertEquals(Long.MAX_VALUE, at(bill, Long.MAX_VALUE, outcome).paymentEventId().longValue());
    }

    @Test
    void paymentMayEqualIssuanceButCannotPrecedeIt() {
        UtilityBill bill = bill(1, DAY);
        Case firstInstant = new Case(bill, 11L, bill.issuedAt(), Outcome.ON_TIME);
        assertEquals(1.0, assessment(List.of(firstInstant)).onTimeRate().orElseThrow());
        for (Outcome outcome : List.of(Outcome.ON_TIME, Outcome.LATE))
            assertThrows(IllegalArgumentException.class,
                    () -> new Case(bill, 11L, bill.issuedAt().minusMillis(1), outcome));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void exactDeadlineAndAdjacentMillisecondsSeparateOnTimeFromLate(long offsetMillis) {
        UtilityBill bill = bill(1, DAY);
        Instant paid = bill.dueAt().plusMillis(offsetMillis);
        Outcome expected = offsetMillis <= 0 ? Outcome.ON_TIME : Outcome.LATE;
        Outcome wrong = expected == Outcome.ON_TIME ? Outcome.LATE : Outcome.ON_TIME;
        assertEquals(1, assessment(List.of(new Case(bill, 11L, paid, expected))).count(expected));
        assertThrows(IllegalArgumentException.class, () -> new Case(bill, 11L, paid, wrong));
    }

    @Test
    void dueCohortIncludesBothEndsEvenWhenIssuanceAndPaymentPrecedeWindow() {
        UtilityBill first = bill(1, DAY);
        UtilityBill last = bill(2, DAY.plusDays(1));
        Case earlyPayment = new Case(first, 11L, first.issuedAt(), Outcome.ON_TIME);
        Case finalPayment = new Case(last, 12L, last.dueAt(), Outcome.ON_TIME);
        var snapshot = new UtilityServiceAssessment(OWNER, last.dueAt(), first.dueAt(),
                List.of(finalPayment, earlyPayment));
        assertEquals(List.of(earlyPayment, finalPayment), snapshot.cases());
        assertEquals(2, snapshot.assessedCount());
        assertEquals(1.0, snapshot.onTimeRate().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void rejectsDeadlinesOutsideWindowWithoutSilentlyFilteringAnyOutcome(Outcome outcome) {
        UtilityBill bill = bill(1, DAY);
        Case utility = at(bill, 11L, outcome);
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityServiceAssessment(OWNER, CAPTURED, bill.dueAt().plusNanos(1), List.of(utility)));
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityServiceAssessment(OWNER, bill.dueAt().minusNanos(1), FROM, List.of(utility)));
    }

    @Test
    void paymentMayEqualCaptureButCannotBeEvenOneMillisecondLater() {
        UtilityBill bill = bill(1, DAY);
        Case boundary = new Case(bill, 11L, CAPTURED, Outcome.LATE);
        Case future = new Case(bill, 11L, CAPTURED.plusMillis(1), Outcome.LATE);
        assertDoesNotThrow(() -> assessment(List.of(boundary)));
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(future)));
    }

    @Test
    void futureIssuanceCannotEnterACapturedCohort() {
        UtilityBill future = bill(1, DAY.plusDays(3));
        assertTrue(future.issuedAt().isAfter(CAPTURED));
        assertThrows(IllegalArgumentException.class,
                () -> assessment(List.of(at(future, null, Outcome.OVERDUE))));
    }

    @Test
    void windowEndpointsPreserveNanosecondsAndAllowOneNanosecondDuration() {
        UtilityBill bill = bill(1, DAY);
        Case utility = at(bill, 11L, Outcome.ON_TIME);
        for (Instant from : List.of(bill.dueAt().minusNanos(1), bill.dueAt())) {
            Instant capture = from.plusNanos(1);
            var snapshot = new UtilityServiceAssessment(OWNER, capture, from, List.of(utility));
            assertEquals(from, snapshot.dueFrom());
            assertEquals(capture, snapshot.capturedAt());
            assertEquals(1, snapshot.assessedCount());
        }
    }

    @Test
    void rejectsZeroReverseAndOverlongWindowsEvenForEmptyHistory() {
        assertDoesNotThrow(() -> assessment(List.of()));
        for (Instant badFrom : List.of(CAPTURED, CAPTURED.plusNanos(1), FROM.minusNanos(1)))
            assertThrows(IllegalArgumentException.class,
                    () -> new UtilityServiceAssessment(OWNER, CAPTURED, badFrom, List.of()));
    }

    @ParameterizedTest
    @CsvSource({"2026-03-01,719,true", "2026-10-15,721,false"})
    void windowLimitUsesElapsedUtcDaysNotThirtyLocalResetDates(String date, long hours, boolean valid) {
        LocalDate day = LocalDate.parse(date);
        Instant from = bill(1, day, "America/Chicago", 4).periodStart();
        Instant capture = bill(1, day.plusDays(30), "America/Chicago", 4).periodStart();
        assertEquals(Duration.ofHours(hours), Duration.between(from, capture));
        if (valid) assertDoesNotThrow(() -> new UtilityServiceAssessment(OWNER, capture, from, List.of()));
        else assertThrows(IllegalArgumentException.class,
                () -> new UtilityServiceAssessment(OWNER, capture, from, List.of()));
    }

    @Test
    void thirtyElapsedDaysCanContainThirtyTwoBillLabelsAcrossZones() {
        LocalDate firstDay = DAY.minusDays(30);
        var cases = new ArrayList<>(IntStream.rangeClosed(0, 30)
                .mapToObj(index -> at(bill(index + 1, firstDay.plusDays(index)), null, Outcome.OVERDUE)).toList());
        Instant from = cases.getFirst().bill().dueAt();
        Instant capture = cases.getLast().bill().dueAt();
        assertEquals(Duration.ofDays(30), Duration.between(from, capture));
        cases.add(at(bill(1, firstDay.minusDays(1), "-12:00", 12), null, Outcome.OVERDUE));
        assertEquals(from, cases.getLast().bill().dueAt());
        var snapshot = new UtilityServiceAssessment(OWNER, capture, from, cases);
        assertEquals(32, snapshot.assessedCount());
        assertEquals(32, snapshot.distinctBillDays());
    }

    @Test
    void distinctBillLabelsAreNotUtcDeadlineDatesOrZoneQualifiedLabels() {
        Case utc = at(bill(1, DAY), null, Outcome.OVERDUE);
        Case western = at(bill(2, DAY, "-12:00", 12), null, Outcome.OVERDUE);
        assertEquals(Duration.ofDays(1), Duration.between(utc.bill().dueAt(), western.bill().dueAt()));
        assertEquals(1, assessment(List.of(utc, western)).distinctBillDays());
        Case earlierLabel = at(bill(2, DAY.minusDays(1), "-12:00", 12), null, Outcome.OVERDUE);
        assertEquals(utc.bill().dueAt(), earlierLabel.bill().dueAt());
        assertEquals(2, assessment(List.of(utc, earlierLabel)).distinctBillDays());
    }

    @Test
    void accepts4096CasesAndRejects4097IncludingUnknownCases() {
        var cases = IntStream.rangeClosed(1, 4096)
                .mapToObj(id -> at(bill(id, DAY), null, Outcome.OVERDUE)).toList();
        assertEquals(4096, assessment(cases).assessedCount());
        var tooMany = new ArrayList<>(cases);
        tooMany.add(at(bill(4097, DAY), null, Outcome.UNVERIFIED_PAYMENT));
        assertThrows(IllegalArgumentException.class, () -> assessment(tooMany));
    }

    @Test
    void rejectsMissingAssessmentFieldsCasesAndCountOutcome() {
        assertThrows(NullPointerException.class, () -> new UtilityServiceAssessment(null, CAPTURED, FROM, List.of()));
        assertThrows(NullPointerException.class, () -> new UtilityServiceAssessment(OWNER, null, FROM, List.of()));
        assertThrows(NullPointerException.class, () -> new UtilityServiceAssessment(OWNER, CAPTURED, null, List.of()));
        assertThrows(NullPointerException.class, () -> assessment(null));
        assertThrows(NullPointerException.class,
                () -> assessment(Arrays.asList(at(bill(1, DAY), null, Outcome.OVERDUE), null)));
        assertThrows(NullPointerException.class, () -> assessment(List.of()).count(null));
    }

    @Test
    void rejectsMissingBillAndOutcomeWithoutDefaultingThem() {
        assertThrows(NullPointerException.class, () -> new Case(null, null, null, Outcome.OVERDUE));
        assertThrows(NullPointerException.class, () -> new Case(bill(1, DAY), null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1000000000-01-01T00:00:00Z", "0000-12-31T23:59:59.999999999Z",
            "+10000-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z"})
    void rejectsTimesOutsideUtcYearsOneThrough9999(String value) {
        Instant invalid = Instant.parse(value);
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityServiceAssessment(OWNER, invalid, FROM, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityServiceAssessment(OWNER, CAPTURED, invalid, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Case(bill(1, DAY), 11L, invalid, Outcome.LATE));
    }

    @Test
    void acceptsUtcRangeEdgesWithoutNarrowingUtilityBillRules() {
        Instant earliest = Instant.parse("0001-01-01T00:00:00Z");
        Instant latest = Instant.parse("9999-12-31T23:59:59.999999999Z");
        for (Instant from : List.of(earliest, latest.minusNanos(1)))
            assertDoesNotThrow(() -> new UtilityServiceAssessment(OWNER, from.plusNanos(1), from, List.of()));
        UtilityBill first = new UtilityBill(new UUID(0, 1), OWNER, 0, 0, LocalDate.of(1, 1, 1),
                4, 2, 3, earliest, "UTC", 0);
        Case firstPayment = new Case(first, 11L, earliest, Outcome.ON_TIME);
        assertDoesNotThrow(() -> new UtilityServiceAssessment(OWNER, first.dueAt(), earliest, List.of(firstPayment)));
        UtilityBill last = bill(1, LocalDate.of(9998, 12, 31));
        assertDoesNotThrow(() -> new Case(last, 11L, latest, Outcome.LATE));
    }

    private static UtilityServiceAssessment assessment(List<Case> cases) {
        return new UtilityServiceAssessment(OWNER, CAPTURED, FROM, cases);
    }

    private static UtilityBill bill(long property, LocalDate day) {
        return bill(property, day, "UTC", 0);
    }

    private static UtilityBill bill(long property, LocalDate day, String zone, int reset) {
        Instant issued = day.atTime(reset, 0).atZone(ZoneId.of(zone)).toInstant().plusSeconds(60);
        return new UtilityBill(new UUID(0, property), OWNER, 0, 0, day, 4, 2, 3, issued, zone, reset);
    }

    private static Case at(UtilityBill bill, Long event, Outcome outcome) {
        Instant paid = switch (outcome) {
            case ON_TIME -> bill.dueAt();
            case LATE -> bill.dueAt().plusMillis(1);
            case OVERDUE, HANDOVER_REVIEW, UNVERIFIED_PAYMENT -> null;
        };
        return new Case(bill, event, paid, outcome);
    }
}
