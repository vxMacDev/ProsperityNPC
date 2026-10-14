package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.property.IncidentServiceAssessment.Case;
import com.prosperitysmp.npc.property.IncidentServiceAssessment.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated("Exercises UTC deadline dates under different JVM default time zones")
class IncidentServiceAssessmentTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID PROPERTY = new UUID(0, 2);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T12:00:00Z");
    private static final Instant FROM = CAPTURED.minus(Duration.ofDays(30));
    private static final Instant DUE = CAPTURED.minus(Duration.ofDays(2));
    private static final Instant CREATED = DUE.minus(Duration.ofDays(1));

    @Test
    void copiesWithoutMutatingInputAndOrdersByExactDeadlineThenIncidentId() {
        Case earlier = at(Long.MAX_VALUE, DUE.minusNanos(1), Outcome.OVERDUE);
        Case lowerId = at(1, DUE, Outcome.OVERDUE);
        Case higherId = at(Long.MAX_VALUE - 1, DUE, Outcome.OVERDUE);
        Case later = at(2, DUE.plusNanos(1), Outcome.HANDOVER_REVIEW);
        var input = new ArrayList<>(List.of(later, higherId, earlier, lowerId));
        var snapshot = assessment(input);
        assertEquals(List.of(later, higherId, earlier, lowerId), input);
        assertEquals(List.of(earlier, lowerId, higherId, later), snapshot.cases());
        input.clear();
        assertEquals(4, snapshot.cases().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cases().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cases().add(lowerId));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cases().set(0, later));
        var reordered = assessment(List.of(higherId, lowerId, later, earlier));
        assertEquals(snapshot, reordered);
        assertEquals(snapshot.hashCode(), reordered.hashCode());
        assertEquals(OWNER, snapshot.ownerId());
        assertEquals(CAPTURED, snapshot.capturedAt());
        assertEquals(FROM, snapshot.dueFrom());
    }

    @Test
    void noIncidentsMeansUnknownNotPerfectService() {
        var snapshot = assessment(List.of());
        for (Outcome outcome : Outcome.values()) assertEquals(0, snapshot.count(outcome));
        assertEquals(0, snapshot.assessedCount());
        assertEquals(0, snapshot.distinctDueDays());
        assertTrue(snapshot.onTimeRate().isEmpty());
    }

    @Test
    void countsEveryAssessedOutcomeAndComputesFractionalRate() {
        var snapshot = assessment(List.of(at(1, DUE, Outcome.ON_TIME), at(2, DUE, Outcome.ON_TIME),
                at(3, DUE, Outcome.LATE), at(4, DUE, Outcome.OVERDUE)));
        assertEquals(2, snapshot.count(Outcome.ON_TIME));
        assertEquals(1, snapshot.count(Outcome.LATE));
        assertEquals(1, snapshot.count(Outcome.OVERDUE));
        assertEquals(0, snapshot.count(Outcome.HANDOVER_REVIEW));
        assertEquals(0, snapshot.count(Outcome.UNVERIFIED_RESOLUTION));
        assertEquals(4, snapshot.assessedCount());
        assertEquals(1, snapshot.distinctDueDays());
        assertEquals(0.5, snapshot.onTimeRate().orElseThrow());
    }

    @Test
    void knownFailuresYieldZeroAndKnownSuccessYieldsOne() {
        assertEquals(0.0, assessment(List.of(at(1, DUE, Outcome.LATE), at(2, DUE, Outcome.OVERDUE)))
                .onTimeRate().orElseThrow());
        assertEquals(1.0, assessment(List.of(at(1, DUE, Outcome.ON_TIME))).onTimeRate().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"HANDOVER_REVIEW", "UNVERIFIED_RESOLUTION"})
    void excludedEvidenceRemainsVisibleAndWithholdsOtherwisePerfectRate(Outcome excluded) {
        Case review = at(2, DUE.plus(Duration.ofDays(1)), excluded);
        var snapshot = assessment(List.of(at(1, DUE, Outcome.ON_TIME), review));
        assertEquals(2, snapshot.cases().size());
        assertTrue(snapshot.cases().contains(review));
        assertEquals(1, snapshot.count(excluded));
        assertEquals(1, snapshot.assessedCount());
        assertEquals(1, snapshot.distinctDueDays());
        assertTrue(snapshot.onTimeRate().isEmpty());
    }

    @Test
    void anotherOwnersProvenRepairIsStillReviewAndNotServiceCredit() {
        Case repairedHandover = new Case(2, PROPERTY, 0, CREATED, DUE, DUE, 21L, Outcome.HANDOVER_REVIEW);
        var snapshot = assessment(List.of(at(1, DUE, Outcome.ON_TIME), repairedHandover));
        assertEquals(1, snapshot.count(Outcome.HANDOVER_REVIEW));
        assertEquals(1, snapshot.assessedCount());
        assertTrue(snapshot.onTimeRate().isEmpty());
        assertEquals(21L, repairedHandover.repairEventId().longValue());
    }

    @Test
    void exclusivelyExcludedCasesHaveNoAssessedDeadlines() {
        var snapshot = assessment(List.of(at(1, DUE, Outcome.HANDOVER_REVIEW),
                at(2, DUE.plus(Duration.ofDays(1)), Outcome.UNVERIFIED_RESOLUTION)));
        assertEquals(1, snapshot.count(Outcome.HANDOVER_REVIEW));
        assertEquals(1, snapshot.count(Outcome.UNVERIFIED_RESOLUTION));
        assertEquals(0, snapshot.assessedCount());
        assertEquals(0, snapshot.distinctDueDays());
        assertTrue(snapshot.onTimeRate().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/Chicago", "Pacific/Kiritimati"})
    void distinctDaysUseUtcDeadlinesNotCreationResolutionOrEconomyReset(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            Instant midnight = Instant.parse("2026-09-06T00:00:00Z");
            var incidents = List.of(
                    new Case(1, PROPERTY, 0, CREATED, midnight.minusNanos(1), CAPTURED, 11L, Outcome.LATE),
                    new Case(2, PROPERTY, 0, CREATED, midnight, CAPTURED, 12L, Outcome.LATE),
                    at(3, midnight.plusSeconds(9 * 3600).minusNanos(1), Outcome.OVERDUE),
                    at(4, midnight.plusSeconds(9 * 3600), Outcome.OVERDUE));
            var snapshot = assessment(incidents);
            assertEquals(2, snapshot.distinctDueDays());
            assertEquals(4, snapshot.assessedCount());
            assertEquals(2, assessment(incidents.subList(0, 2)).distinctDueDays());
            assertEquals(1, assessment(incidents.subList(2, 4)).distinctDueDays());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void thirtyDayElapsedWindowCanCoverThirtyOneUtcDatesIncludingBothEnds() {
        var incidents = LongStream.rangeClosed(0, 30)
                .mapToObj(day -> at(day + 1, FROM.plus(Duration.ofDays(day)), Outcome.OVERDUE)).toList();
        var snapshot = assessment(incidents);
        assertEquals(FROM, snapshot.cases().getFirst().dueAt());
        assertEquals(CAPTURED, snapshot.cases().getLast().dueAt());
        assertEquals(31, snapshot.distinctDueDays());
        assertEquals(31, snapshot.assessedCount());
    }

    @Test
    void allowsOneNanosecondWindowAndResolutionAtCreationOrCapture() {
        Instant from = CAPTURED.minusNanos(1);
        Case early = new Case(1, PROPERTY, 0, CREATED, from, CREATED, 11L, Outcome.ON_TIME);
        Case boundary = new Case(2, PROPERTY, 0, CREATED, CAPTURED, CAPTURED, 12L, Outcome.ON_TIME);
        var snapshot = new IncidentServiceAssessment(OWNER, CAPTURED, from, List.of(early, boundary));
        assertEquals(2, snapshot.assessedCount());
        assertEquals(1.0, snapshot.onTimeRate().orElseThrow());
    }

    @Test
    void rejectsEmptyReverseAndOverlongWindowsEvenWithNoCases() {
        for (Instant badFrom : List.of(CAPTURED, CAPTURED.plusNanos(1), FROM.minusNanos(1)))
            assertThrows(IllegalArgumentException.class,
                    () -> new IncidentServiceAssessment(OWNER, CAPTURED, badFrom, List.of()));
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void rejectsUnmaturedAndBeforeWindowDeadlinesWithoutSilentlyFiltering(Outcome outcome) {
        for (Instant outside : List.of(FROM.minusNanos(1), CAPTURED.plusNanos(1)))
            assertThrows(IllegalArgumentException.class, () -> assessment(List.of(at(1, outside, outcome))));
    }

    @Test
    void cohortIsSelectedByDeadlineEvenWhenCreationAndRepairPrecedeWindow() {
        Instant oldCreation = FROM.minus(Duration.ofDays(10));
        Case incident = new Case(1, PROPERTY, 0, oldCreation, FROM, oldCreation.plusNanos(1), 11L, Outcome.ON_TIME);
        var snapshot = assessment(List.of(incident));
        assertEquals(List.of(incident), snapshot.cases());
        assertEquals(1, snapshot.assessedCount());
        assertEquals(1.0, snapshot.onTimeRate().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"LATE", "HANDOVER_REVIEW", "UNVERIFIED_RESOLUTION"})
    void resolutionMayEqualCaptureButCannotBeEvenOneNanosecondLater(Outcome outcome) {
        Long event = outcome == Outcome.UNVERIFIED_RESOLUTION ? null : Long.valueOf(11);
        Case boundary = new Case(1, PROPERTY, 0, CREATED, DUE, CAPTURED, event, outcome);
        Case future = new Case(1, PROPERTY, 0, CREATED, DUE, CAPTURED.plusNanos(1), event, outcome);
        assertDoesNotThrow(() -> assessment(List.of(boundary)));
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(future)));
    }

    @Test
    void rejectsRepeatedIncidentIdsEvenWithDifferentPropertyEpochDeadlineOrExcludedOutcome() {
        Case original = at(1, DUE, Outcome.ON_TIME);
        for (Case duplicate : List.of(original,
                new Case(1, new UUID(0, 3), 1, CREATED, DUE.plusNanos(1), null, null, Outcome.OVERDUE),
                at(1, DUE, Outcome.HANDOVER_REVIEW), at(1, DUE, Outcome.UNVERIFIED_RESOLUTION)))
            assertThrows(IllegalArgumentException.class, () -> assessment(List.of(original, duplicate)));
    }

    @Test
    void accepts512CasesAndRejects513IncludingExcludedCases() {
        var incidents = LongStream.rangeClosed(1, 512).mapToObj(id -> at(id, DUE, Outcome.OVERDUE)).toList();
        assertEquals(512, assessment(incidents).assessedCount());
        var tooMany = new ArrayList<>(incidents);
        tooMany.add(at(513, DUE, Outcome.HANDOVER_REVIEW));
        assertThrows(IllegalArgumentException.class, () -> assessment(tooMany));
    }

    @Test
    void rejectsMissingAssessmentFieldsCasesAndCountOutcome() {
        assertThrows(NullPointerException.class, () -> new IncidentServiceAssessment(null, CAPTURED, FROM, List.of()));
        assertThrows(NullPointerException.class, () -> new IncidentServiceAssessment(OWNER, null, FROM, List.of()));
        assertThrows(NullPointerException.class, () -> new IncidentServiceAssessment(OWNER, CAPTURED, null, List.of()));
        assertThrows(NullPointerException.class, () -> assessment(null));
        assertThrows(NullPointerException.class, () -> assessment(Arrays.asList(at(1, DUE, Outcome.OVERDUE), null)));
        assertThrows(NullPointerException.class, () -> assessment(List.of()).count(null));
    }

    @Test
    void rejectsMissingCaseIdentityRequiredTimesAndOutcome() {
        assertThrows(NullPointerException.class, () -> new Case(1, null, 0, CREATED, DUE, null, null, Outcome.OVERDUE));
        assertThrows(NullPointerException.class, () -> new Case(1, PROPERTY, 0, null, DUE, null, null, Outcome.OVERDUE));
        assertThrows(NullPointerException.class, () -> new Case(1, PROPERTY, 0, CREATED, null, null, null, Outcome.OVERDUE));
        assertThrows(NullPointerException.class, () -> new Case(1, PROPERTY, 0, CREATED, DUE, null, null, null));
    }

    @Test
    void validatesIdAndEpochBoundsWithoutImposingUuidVersions() {
        for (long bad : new long[]{Long.MIN_VALUE, -1, 0})
            assertThrows(IllegalArgumentException.class, () -> at(bad, DUE, Outcome.OVERDUE));
        for (int bad : new int[]{Integer.MIN_VALUE, -1})
            assertThrows(IllegalArgumentException.class,
                    () -> new Case(1, PROPERTY, bad, CREATED, DUE, null, null, Outcome.OVERDUE));
        assertDoesNotThrow(() -> new Case(Long.MAX_VALUE, PROPERTY, Integer.MAX_VALUE,
                CREATED, DUE, DUE, Long.MAX_VALUE, Outcome.ON_TIME));
        assertDoesNotThrow(() -> new Case(1, UUID.randomUUID(), 0, CREATED, DUE, null, null, Outcome.OVERDUE));
    }

    @Test
    void creationMustStrictlyPrecedeDeadline() {
        for (Instant bad : List.of(DUE, DUE.plusNanos(1)))
            assertThrows(IllegalArgumentException.class,
                    () -> new Case(1, PROPERTY, 0, bad, DUE, null, null, Outcome.OVERDUE));
        assertDoesNotThrow(() -> new Case(1, PROPERTY, 0, DUE.minusNanos(1), DUE, null, null, Outcome.OVERDUE));
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"ON_TIME", "HANDOVER_REVIEW", "UNVERIFIED_RESOLUTION"})
    void resolvedCasesCannotPredateCreation(Outcome outcome) {
        Long event = outcome == Outcome.UNVERIFIED_RESOLUTION ? null : Long.valueOf(11);
        assertThrows(IllegalArgumentException.class,
                () -> new Case(1, PROPERTY, 0, CREATED, DUE, CREATED.minusNanos(1), event, outcome));
        assertDoesNotThrow(() -> new Case(1, PROPERTY, 0, CREATED, DUE, CREATED, event, outcome));
    }

    @Test
    void onTimeAndLateAreSeparatedAtNanosecondPrecision() {
        assertDoesNotThrow(() -> repair(DUE.minusNanos(1), 11L, Outcome.ON_TIME));
        assertDoesNotThrow(() -> repair(DUE, 11L, Outcome.ON_TIME));
        assertThrows(IllegalArgumentException.class, () -> repair(DUE.plusNanos(1), 11L, Outcome.ON_TIME));
        assertThrows(IllegalArgumentException.class, () -> repair(DUE.minusNanos(1), 11L, Outcome.LATE));
        assertThrows(IllegalArgumentException.class, () -> repair(DUE, 11L, Outcome.LATE));
        assertDoesNotThrow(() -> repair(DUE.plusNanos(1), 11L, Outcome.LATE));
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void outcomeRequiresExactlyItsPermittedEvidenceShape(Outcome outcome) {
        Instant resolution = outcome == Outcome.LATE ? DUE.plusNanos(1) : DUE;
        for (boolean hasResolution : new boolean[]{false, true}) {
            for (boolean hasEvent : new boolean[]{false, true}) {
                Instant resolved = hasResolution ? resolution : null;
                Long event = hasEvent ? Long.valueOf(11) : null;
                boolean valid = switch (outcome) {
                    case ON_TIME, LATE -> hasResolution && hasEvent;
                    case OVERDUE -> !hasResolution && !hasEvent;
                    case HANDOVER_REVIEW -> hasResolution == hasEvent;
                    case UNVERIFIED_RESOLUTION -> hasResolution && !hasEvent;
                };
                String context = outcome + " resolved=" + hasResolution + " event=" + hasEvent;
                if (valid) assertDoesNotThrow(() -> repair(resolved, event, outcome), context);
                else assertThrows(IllegalArgumentException.class, () -> repair(resolved, event, outcome), context);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"ON_TIME", "LATE", "HANDOVER_REVIEW"})
    void rejectsNonpositiveRepairIdsEvenForExcludedProvenRepairs(Outcome outcome) {
        Instant resolved = outcome == Outcome.LATE ? DUE.plusNanos(1) : DUE;
        for (long invalid : new long[]{Long.MIN_VALUE, -1, 0})
            assertThrows(IllegalArgumentException.class, () -> repair(resolved, invalid, outcome));
    }

    @Test
    void rejectsTimesOutsideJournalRangeInEveryTimeField() {
        for (Instant invalid : List.of(Instant.MIN, Instant.parse("0000-12-31T23:59:59.999999999Z"),
                Instant.parse("+10000-01-01T00:00:00Z"), Instant.MAX)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new IncidentServiceAssessment(OWNER, invalid, FROM, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new IncidentServiceAssessment(OWNER, CAPTURED, invalid, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new Case(1, PROPERTY, 0, invalid, DUE, null, null, Outcome.OVERDUE));
            assertThrows(IllegalArgumentException.class,
                    () -> new Case(1, PROPERTY, 0, CREATED, invalid, null, null, Outcome.OVERDUE));
            assertThrows(IllegalArgumentException.class, () -> repair(invalid, 11L, Outcome.HANDOVER_REVIEW));
        }
    }

    @Test
    void acceptsInclusiveJournalTimeRangeEdges() {
        Instant earliest = Instant.parse("0001-01-01T00:00:00Z");
        Instant latest = Instant.parse("9999-12-31T23:59:59.999999999Z");
        for (Instant from : List.of(earliest, latest.minusNanos(1))) {
            Instant capture = from.plusNanos(1);
            Case incident = new Case(1, PROPERTY, 0, from, capture, capture, 11L, Outcome.ON_TIME);
            var snapshot = new IncidentServiceAssessment(OWNER, capture, from, List.of(incident));
            assertEquals(1, snapshot.distinctDueDays());
            assertEquals(1.0, snapshot.onTimeRate().orElseThrow());
        }
    }

    private static IncidentServiceAssessment assessment(List<Case> cases) {
        return new IncidentServiceAssessment(OWNER, CAPTURED, FROM, cases);
    }

    private static Case at(long id, Instant due, Outcome outcome) {
        Instant resolved = switch (outcome) {
            case ON_TIME, UNVERIFIED_RESOLUTION -> due;
            case LATE -> due.plusNanos(1);
            case OVERDUE, HANDOVER_REVIEW -> null;
        };
        Long event = outcome == Outcome.ON_TIME || outcome == Outcome.LATE ? Long.valueOf(id) : null;
        return new Case(id, PROPERTY, 0, due.minusSeconds(60), due, resolved, event, outcome);
    }

    private static Case repair(Instant resolved, Long event, Outcome outcome) {
        return new Case(1, PROPERTY, 0, CREATED, DUE, resolved, event, outcome);
    }
}
