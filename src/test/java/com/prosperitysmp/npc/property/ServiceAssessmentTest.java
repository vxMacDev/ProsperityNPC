package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.property.ServiceAssessment.Component;
import com.prosperitysmp.npc.property.ServiceAssessment.ScoreBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ServiceAssessmentTest {
    private static final UUID OWNER = new UUID(3, 1);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T12:34:56.789Z");
    private static final Instant FROM = CAPTURED.minus(Duration.ofDays(30));
    private static final int HORIZON = 7;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);

    @Test
    void emptyHistoryIsEntirelyUnknownWithMathematicalExtremaNotConfidence() {
        var assessment = new ServiceAssessment(utilities(0, 0), incidents(0, 0), retention(0, 0));
        assertEquals(EnumSet.allOf(Component.class), assessment.unknownComponents());
        assertTrue(assessment.operatorScore().isEmpty());
        assertEquals(new ScoreBounds(25, 75), assessment.scoreBounds());
        assertEquals(OWNER, assessment.ownerId());
        assertEquals(CAPTURED, assessment.capturedAt());
        assertEquals(FROM, assessment.windowFrom());
        assertThrows(NullPointerException.class, () -> new ServiceAssessment(null, incidents(0, 0), retention(0, 0)));
        assertThrows(NullPointerException.class, () -> new ServiceAssessment(utilities(0, 0), null, retention(0, 0)));
        assertThrows(NullPointerException.class, () -> new ServiceAssessment(utilities(0, 0), incidents(0, 0), null));
    }

    @ParameterizedTest
    @CsvSource({"0,25,75", "1,30,60", "2,38,68", "3,43,53",
            "4,30,70", "5,35,55", "6,43,63", "7,48,48"})
    void everyMissingCombinationUsesZeroToOneBoundsWithoutPartialScores(int known, int minimum, int maximum) {
        // Known rates are utilities 1/4, incidents 2/3, retention 1/2, regardless of sample counts.
        for (int[] scale : new int[][]{{1, 1, 1}, {50, 1, 1}, {1, 50, 1}, {1, 1, 50}}) {
            var u = (known & 1) != 0 ? utilities(scale[0], 4 * scale[0]) : utilities(0, 0);
            var i = (known & 2) != 0 ? incidents(2 * scale[1], 3 * scale[1]) : incidents(0, 0);
            var r = (known & 4) != 0 ? retention(scale[2], 2 * scale[2]) : retention(0, 0);
            var assessment = new ServiceAssessment(u, i, r);
            var unknown = EnumSet.noneOf(Component.class);
            if ((known & 1) == 0) unknown.add(Component.UTILITIES);
            if ((known & 2) == 0) unknown.add(Component.INCIDENTS);
            if ((known & 4) == 0) unknown.add(Component.RETENTION);
            assertEquals(unknown, assessment.unknownComponents());
            assertEquals(new ScoreBounds(minimum, maximum), assessment.scoreBounds());
            assertEquals(known == 7, assessment.operatorScore().isPresent());
            if (known == 7) assertEquals(48, assessment.operatorScore().orElseThrow());
        }
    }

    @ParameterizedTest
    @CsvSource({"0,1,0,1,0,1,25", "1,1,1,1,1,1,75", "1,4,2,3,1,2,48",
            "1,3,1,2,1,4,44", "1,8,0,1,0,1,28", "0,1,2,3,5,12,43"})
    void completeRatesUseExistingFormulaAndRounding(int u, int un, int i, int in, int r, int rn, int expected) {
        var assessment = new ServiceAssessment(utilities(u, un), incidents(i, in), retention(r, rn));
        assertEquals(expected, PropertyFormulas.operatorScore((double) u / un, (double) i / in, (double) r / rn));
        assertEquals(expected, assessment.operatorScore().orElseThrow());
        assertEquals(new ScoreBounds(expected, expected), assessment.scoreBounds());
        assertTrue(assessment.unknownComponents().isEmpty());
    }

    @Test
    void unknownUtilitiesPreserveExistingFormulaRoundingAtBothBounds() {
        var assessment = new ServiceAssessment(utilities(0, 0), incidents(2, 3), retention(5, 12));
        assertEquals(Set.of(Component.UTILITIES), assessment.unknownComponents());
        assertTrue(assessment.operatorScore().isEmpty());
        assertEquals(new ScoreBounds(43, 63), assessment.scoreBounds());
    }

    @ParameterizedTest
    @CsvSource({"UTILITIES,HANDOVER_REVIEW,38,58", "UTILITIES,UNVERIFIED_PAYMENT,38,58",
            "INCIDENTS,HANDOVER_REVIEW,48,68", "INCIDENTS,UNVERIFIED_RESOLUTION,48,68",
            "RETENTION,HANDOVER_REVIEW,55,65", "RETENTION,EVIDENCE_GAP,55,65"})
    void reviewEvidenceMakesWholeComponentUnknownJustLikeNoHistory(Component component, String outcome,
                                                                  int minimum, int maximum) {
        var u = utilities(1, 1);
        var i = incidents(1, 2);
        var r = retention(1, 4);
        var uc = new ArrayList<>(u.cases());
        var ic = new ArrayList<>(i.cases());
        var rc = new ArrayList<>(r.cases());
        switch (component) {
            case UTILITIES -> uc.add(utility(99, UtilityServiceAssessment.Outcome.valueOf(outcome)));
            case INCIDENTS -> ic.add(incident(99, IncidentServiceAssessment.Outcome.valueOf(outcome)));
            case RETENTION -> rc.add(episode(99, RetentionAssessment.Outcome.valueOf(outcome)));
        }
        var review = new ServiceAssessment(new UtilityServiceAssessment(OWNER, CAPTURED, FROM, uc),
                new IncidentServiceAssessment(OWNER, CAPTURED, FROM, ic),
                new RetentionAssessment(OWNER, CAPTURED, FROM, HORIZON, rc));
        var missing = new ServiceAssessment(component == Component.UTILITIES ? utilities(0, 0) : u,
                component == Component.INCIDENTS ? incidents(0, 0) : i,
                component == Component.RETENTION ? retention(0, 0) : r);
        assertEquals(Set.of(component), review.unknownComponents());
        assertTrue(review.operatorScore().isEmpty());
        assertEquals(missing.operatorScore(), review.operatorScore());
        assertEquals(new ScoreBounds(minimum, maximum), review.scoreBounds());
        assertEquals(missing.scoreBounds(), review.scoreBounds());
    }

    @ParameterizedTest
    @CsvSource({"UTILITIES,owner", "INCIDENTS,owner", "RETENTION,owner",
            "UTILITIES,capture", "INCIDENTS,capture", "RETENTION,capture",
            "UTILITIES,window", "INCIDENTS,window", "RETENTION,window"})
    void rejectsEachIndividuallyValidButIncoherentComponent(Component component, String mismatch) {
        UUID owner = mismatch.equals("owner") ? new UUID(3, 99) : OWNER;
        Instant capture = mismatch.equals("capture") ? CAPTURED.minusMillis(1) : CAPTURED;
        Instant from = mismatch.equals("window") ? FROM.plusMillis(1) : FROM;
        var u = component == Component.UTILITIES
                ? new UtilityServiceAssessment(owner, capture, from, List.of()) : utilities(0, 0);
        var i = component == Component.INCIDENTS
                ? new IncidentServiceAssessment(owner, capture, from, List.of()) : incidents(0, 0);
        var r = component == Component.RETENTION
                ? new RetentionAssessment(owner, capture, from, HORIZON, List.of()) : retention(0, 0);
        assertThrows(IllegalArgumentException.class, () -> new ServiceAssessment(u, i, r));
    }

    @Test
    void longRichCohortKeepsBillDaysAndDifferentObservationUnitsSeparateFromScoring() {
        var assessment = new ServiceAssessment(utilities(800, 3200), incidents(200, 300), retention(100, 200));
        assertEquals(Duration.ofDays(30), Duration.between(assessment.windowFrom(), assessment.capturedAt()));
        assertEquals(3200, assessment.utilities().assessedCount());
        assertEquals(16, assessment.utilities().distinctBillDays());
        assertEquals(300, assessment.incidents().assessedCount());
        assertEquals(1, assessment.incidents().distinctDueDays());
        assertEquals(200, assessment.retention().maturedCount());
        assertEquals(0.25, assessment.utilities().onTimeRate().orElseThrow());
        assertEquals(2.0 / 3, assessment.incidents().onTimeRate().orElseThrow(), 1e-12);
        assertEquals(0.5, assessment.retention().retentionRate().orElseThrow());
        assertEquals(48, assessment.operatorScore().orElseThrow());
        assertEquals(new ScoreBounds(48, 48), assessment.scoreBounds());
    }

    @Test
    void immatureEpisodeOneMillisecondShortOfHorizonCannotDiluteKnownRetention() {
        var cases = new ArrayList<>(retention(1, 1).cases());
        cases.add(episode(2, RetentionAssessment.Outcome.NOT_MATURED));
        var assessment = new ServiceAssessment(utilities(1, 1), incidents(1, 1),
                new RetentionAssessment(OWNER, CAPTURED, FROM, HORIZON, cases));
        assertEquals(1, assessment.retention().maturedCount());
        assertEquals(75, assessment.operatorScore().orElseThrow());
    }

    @Test
    void inputsAndReturnedCollectionsCannotMutateTheCombinedSnapshot() {
        var uc = new ArrayList<>(utilities(1, 1).cases());
        var ic = new ArrayList<>(incidents(0, 1).cases());
        var rc = new ArrayList<>(List.of(episode(1, RetentionAssessment.Outcome.EVIDENCE_GAP)));
        var assessment = new ServiceAssessment(new UtilityServiceAssessment(OWNER, CAPTURED, FROM, uc),
                new IncidentServiceAssessment(OWNER, CAPTURED, FROM, ic),
                new RetentionAssessment(OWNER, CAPTURED, FROM, HORIZON, rc));
        uc.clear(); ic.clear(); rc.clear();
        for (List<?> cases : List.of(assessment.utilities().cases(), assessment.incidents().cases(), assessment.retention().cases())) {
            assertEquals(1, cases.size());
            assertThrows(UnsupportedOperationException.class, cases::clear);
        }
        assertThrows(UnsupportedOperationException.class, () -> assessment.unknownComponents().clear());
        assertEquals(Set.of(Component.RETENTION), assessment.unknownComponents());
        assertTrue(assessment.operatorScore().isEmpty());
        assertEquals(new ScoreBounds(45, 55), assessment.scoreBounds());
    }

    private static UtilityServiceAssessment utilities(int onTime, int total) {
        return new UtilityServiceAssessment(OWNER, CAPTURED, FROM, IntStream.rangeClosed(1, total)
                .mapToObj(id -> utility(id, id <= onTime ? UtilityServiceAssessment.Outcome.ON_TIME
                        : id % 2 == 0 ? UtilityServiceAssessment.Outcome.OVERDUE : UtilityServiceAssessment.Outcome.LATE)).toList());
    }

    private static UtilityServiceAssessment.Case utility(int id, UtilityServiceAssessment.Outcome outcome) {
        LocalDate day = DAY.minusDays((id - 1) % 16);
        var bill = new UtilityBill(new UUID(2, id), OWNER, 0, 0, day, 4, 2, 3,
                day.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis(60_001), "UTC", 0);
        Instant paidAt = switch (outcome) {
            case ON_TIME -> bill.dueAt();
            case LATE -> bill.dueAt().plusMillis(1);
            default -> null;
        };
        return new UtilityServiceAssessment.Case(bill, paidAt != null ? Long.valueOf(id) : null, paidAt, outcome);
    }

    private static IncidentServiceAssessment incidents(int onTime, int total) {
        return new IncidentServiceAssessment(OWNER, CAPTURED, FROM, IntStream.rangeClosed(1, total)
                .mapToObj(id -> incident(id, id <= onTime ? IncidentServiceAssessment.Outcome.ON_TIME
                        : id % 2 == 0 ? IncidentServiceAssessment.Outcome.OVERDUE : IncidentServiceAssessment.Outcome.LATE)).toList());
    }

    private static IncidentServiceAssessment.Case incident(int id, IncidentServiceAssessment.Outcome outcome) {
        Instant due = CAPTURED.minusMillis(1);
        boolean repaired = outcome == IncidentServiceAssessment.Outcome.ON_TIME || outcome == IncidentServiceAssessment.Outcome.LATE;
        Instant resolvedAt = switch (outcome) {
            case ON_TIME, UNVERIFIED_RESOLUTION -> due;
            case LATE -> due.plusMillis(1);
            default -> null;
        };
        return new IncidentServiceAssessment.Case(id, new UUID(2, id), 0, due.minusSeconds(60), due,
                resolvedAt, repaired ? Long.valueOf(id) : null, outcome);
    }

    private static RetentionAssessment retention(int retained, int total) {
        return new RetentionAssessment(OWNER, CAPTURED, FROM, HORIZON, IntStream.rangeClosed(1, total)
                .mapToObj(id -> episode(id, id <= retained ? RetentionAssessment.Outcome.RETAINED
                        : RetentionAssessment.Outcome.DEPARTED)).toList());
    }

    private static RetentionAssessment.Case episode(int id, RetentionAssessment.Outcome outcome) {
        Instant at = CAPTURED.minus(Duration.ofDays(HORIZON))
                .plusMillis(outcome == RetentionAssessment.Outcome.NOT_MATURED ? 1 : 0);
        var request = new HousingMoveRequest(new UUID(0, id), new UUID(1, id), new UUID(2, id), OWNER,
                new UUID(4, 1), "central", "standard", 2, 10, 3, 4, 1, at.atOffset(ZoneOffset.UTC).toLocalDate());
        var policy = new HousingMoveRequest.Policy(128, 32, 4, 7);
        var admission = new Receipt(request, policy, 5, request.day().plusDays(policy.cooldownDays()), at);
        var departure = outcome == RetentionAssessment.Outcome.DEPARTED
                ? new TenancyDeparture(request.residentId(), request.propertyId(), OWNER, 2, 5, 1,
                        CAPTURED.minusMillis(1), TenancyDeparture.Cause.DEPARTURE, null) : null;
        return new RetentionAssessment.Case(admission, outcome, departure,
                outcome == RetentionAssessment.Outcome.HANDOVER_REVIEW ? CAPTURED : null,
                outcome == RetentionAssessment.Outcome.RETAINED ? new RetentionAssessment.Occupancy(request.propertyId(), 1, 5) : null);
    }
}
