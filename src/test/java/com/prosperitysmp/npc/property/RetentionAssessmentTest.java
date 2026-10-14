package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.property.RetentionAssessment.Case;
import com.prosperitysmp.npc.property.RetentionAssessment.Outcome;
import com.prosperitysmp.npc.property.RetentionAssessment.Occupancy;
import com.prosperitysmp.npc.property.TenancyDeparture.Cause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RetentionAssessmentTest {
    private static final UUID OWNER = new UUID(3, 1);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T12:34:56.789Z");
    private static final Instant FROM = CAPTURED.minus(Duration.ofDays(30));
    private static final Instant ADMITTED = CAPTURED.minus(Duration.ofDays(10));
    private static final Policy POLICY = new Policy(128, 32, 4, 7);

    @Test
    void maturedMixtureUsesAFractionalRateUnaffectedByImmatureGaps() {
        Receipt a = admission(1, ADMITTED), b = admission(2, ADMITTED), c = admission(3, ADMITTED);
        var assessment = assessment(List.of(observed(a, Outcome.RETAINED, null, null),
                observed(b, Outcome.RETAINED, departure(b, CAPTURED), null),
                observed(c, Outcome.DEPARTED, departure(c, ADMITTED), null),
                observed(admission(4, CAPTURED), Outcome.EVIDENCE_GAP, null, null)));
        assertEquals(3, assessment.maturedCount());
        assertEquals(2, assessment.count(Outcome.RETAINED));
        assertEquals(1, assessment.count(Outcome.DEPARTED));
        assertEquals(2.0 / 3, assessment.retentionRate().orElseThrow(), 1e-12);
        assertEquals(0.0, assessment(List.of(assessment.cases().get(2))).retentionRate().orElseThrow());
    }

    @Test
    void earlyDepartureWaitsForTheSameHorizonBeforeEnteringTheDenominator() {
        assertEquals(0, assessment(List.of()).maturedCount());
        assertTrue(assessment(List.of()).retentionRate().isEmpty());
        Receipt old = admission(1, ADMITTED), young = admission(2, CAPTURED.minus(Duration.ofDays(2)));
        Case retained = observed(old, Outcome.RETAINED, null, null);
        TenancyDeparture left = departure(young, young.appliedAt().plusMillis(1));
        Case waiting = observed(young, Outcome.NOT_MATURED, left, null);
        assertTrue(assessment(List.of(waiting)).retentionRate().isEmpty());
        assertEquals(1.0, assessment(List.of(retained, waiting)).retentionRate().orElseThrow());
        Case departed = observed(young, Outcome.DEPARTED, left, null);
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(retained, departed)));
        Instant deadline = young.appliedAt().plus(Duration.ofDays(7));
        var mature = new RetentionAssessment(OWNER, deadline, ADMITTED, 7, List.of(retained, departed));
        assertEquals(2, mature.maturedCount());
        assertEquals(0.5, mature.retentionRate().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionAssessment(OWNER, deadline, ADMITTED, 7, List.of(waiting)));
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"EVIDENCE_GAP", "HANDOVER_REVIEW"})
    void unresolvedMaturedEvidenceSuppressesTheWholeRate(Outcome outcome) {
        Case unknown = observed(admission(2, ADMITTED), outcome, null,
                outcome == Outcome.HANDOVER_REVIEW ? ADMITTED : null);
        var assessment = assessment(List.of(observed(admission(1, ADMITTED), Outcome.RETAINED, null, null), unknown));
        assertEquals(2, assessment.maturedCount());
        assertEquals(1, assessment.count(outcome));
        assertTrue(assessment.cases().contains(unknown));
        assertTrue(assessment.retentionRate().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 30})
    void exactHorizonIsMatureButOneMillisecondEarlierIsNot(int horizon) {
        Receipt a = admission(1, CAPTURED.minus(Duration.ofDays(horizon)));
        Case retained = observed(a, Outcome.RETAINED, null, null);
        Case waiting = observed(a, Outcome.NOT_MATURED, null, null);
        assertEquals(1.0, new RetentionAssessment(OWNER, CAPTURED, FROM, horizon, List.of(retained))
                .retentionRate().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionAssessment(OWNER, CAPTURED, FROM, horizon, List.of(waiting)));
        Instant before = CAPTURED.minusMillis(1);
        assertTrue(new RetentionAssessment(OWNER, before, FROM, horizon, List.of(waiting)).retentionRate().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionAssessment(OWNER, before, FROM, horizon, List.of(retained)));
    }

    @ParameterizedTest
    @EnumSource(Cause.class)
    void departureAtOrAfterDeadlineIsRetainedButOneMillisecondBeforeIsDeparted(Cause cause) {
        Receipt a = admission(1, ADMITTED);
        for (int offset : new int[]{-1, 0, 1}) {
            TenancyDeparture d = departure(a, ADMITTED.plus(Duration.ofDays(7)).plusMillis(offset), cause);
            Outcome expected = offset < 0 ? Outcome.DEPARTED : Outcome.RETAINED;
            Outcome wrong = offset < 0 ? Outcome.RETAINED : Outcome.DEPARTED;
            assertEquals(1, assessment(List.of(observed(a, expected, d, null))).count(expected));
            assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(a, wrong, d, null))));
        }
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(a, Outcome.DEPARTED, null, null))));
    }

    @Test
    void handoverAtEndpointRequiresReviewButAfterEndpointCannotEraseTheOutcome() {
        Receipt a = admission(1, ADMITTED);
        for (int departureDay : new int[]{0, 2, 7}) {
            Instant end = ADMITTED.plus(Duration.ofDays(departureDay == 0 ? 7 : departureDay));
            TenancyDeparture d = departureDay == 0 ? null : departure(a, end);
            Outcome known = departureDay == 2 ? Outcome.DEPARTED : Outcome.RETAINED;
            for (Instant handover : List.of(ADMITTED, end.minusMillis(1), end, end.plusMillis(1))) {
                Outcome expected = handover.isAfter(end) ? known : Outcome.HANDOVER_REVIEW;
                Outcome wrong = handover.isAfter(end) ? Outcome.HANDOVER_REVIEW : known;
                var assessment = assessment(List.of(observed(a, expected, d, handover)));
                assertEquals(expected == Outcome.HANDOVER_REVIEW, assessment.retentionRate().isEmpty());
                assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(a, wrong, d, handover))));
            }
        }
        for (Instant bad : List.of(ADMITTED.minusMillis(1), CAPTURED.plusMillis(1)))
            assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(a, Outcome.EVIDENCE_GAP, null, bad))));
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(a, Outcome.HANDOVER_REVIEW, null, null))));
    }

    @Test
    void departureBindingRejectsForeignIdentityRevisionSlotAndChronologyEvenForGaps() {
        Receipt a = admission(1, ADMITTED);
        var r = a.request();
        for (String fault : List.of("resident", "property", "revision", "slot", "before", "future", "owner", "unowned", "epoch")) {
            var d = new TenancyDeparture(fault.equals("resident") ? new UUID(1, 99) : r.residentId(),
                    fault.equals("property") ? new UUID(2, 99) : r.propertyId(),
                    fault.equals("unowned") ? null : fault.equals("owner") ? new UUID(3, 99) : OWNER,
                    r.ownershipEpoch() + (fault.equals("epoch") ? 1 : 0),
                    a.tenancyRevisionAfter() + (fault.equals("revision") ? 1 : 0), fault.equals("slot") ? 2 : r.slot(),
                    fault.equals("before") ? ADMITTED.minusMillis(1) : fault.equals("future") ? CAPTURED.plusMillis(1) : CAPTURED,
                    Cause.DEPARTURE, null);
            Outcome outcome = List.of("owner", "unowned", "epoch").contains(fault) ? Outcome.RETAINED : Outcome.EVIDENCE_GAP;
            assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(a, outcome, d, null))), fault);
        }
        for (Instant boundary : List.of(ADMITTED, CAPTURED))
            assertDoesNotThrow(() -> assessment(List.of(observed(a, Outcome.EVIDENCE_GAP, departure(a, boundary), null))));
    }

    @Test
    void retainedWithoutDepartureRequiresExactlyMatchingActiveOccupancy() {
        Receipt a = admission(1, ADMITTED);
        var r = a.request();
        for (Occupancy bad : new Occupancy[]{null, new Occupancy(new UUID(2, 99), r.slot(), a.tenancyRevisionAfter()),
                new Occupancy(r.propertyId(), 2, a.tenancyRevisionAfter()), new Occupancy(r.propertyId(), r.slot(), 6)})
            assertThrows(IllegalArgumentException.class,
                    () -> assessment(List.of(new Case(a, Outcome.RETAINED, null, null, bad))));
        assertEquals(1.0, assessment(List.of(observed(a, Outcome.RETAINED, null, null))).retentionRate().orElseThrow());
    }

    @Test
    void laterHandoverCannotExplainAnEarlierDepartureByAnotherOwner() {
        Receipt a = admission(1, ADMITTED);
        var d = new TenancyDeparture(a.request().residentId(), a.request().propertyId(), new UUID(3, 77),
                a.request().ownershipEpoch() + 1, a.tenancyRevisionAfter(), a.request().slot(),
                ADMITTED.plus(Duration.ofDays(2)), Cause.DEPARTURE, null);
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(
                observed(a, Outcome.DEPARTED, d, d.departedAt().plusMillis(1)))));
        assertTrue(assessment(List.of(observed(a, Outcome.EVIDENCE_GAP, d, null))).retentionRate().isEmpty());
    }

    @Test
    void admissionOwnerRevisionAndInclusiveWindowAreValidatedWithoutFiltering() {
        Receipt a = admission(1, ADMITTED);
        Receipt badRevision = new Receipt(a.request(), POLICY, a.tenancyRevisionAfter() + 1, a.cooldownUntil(), ADMITTED);
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(badRevision, Outcome.EVIDENCE_GAP, null, null))));
        assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(new UUID(3, 99), CAPTURED, FROM, 7,
                List.of(observed(a, Outcome.EVIDENCE_GAP, null, null))));
        for (Instant outside : List.of(FROM.minusMillis(1), CAPTURED.plusMillis(1)))
            assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(admission(1, outside), Outcome.EVIDENCE_GAP, null, null))));
        for (Instant boundary : List.of(FROM, CAPTURED))
            assertDoesNotThrow(() -> assessment(List.of(observed(admission(1, 1, -1, boundary), Outcome.EVIDENCE_GAP, null, null))));
    }

    @Test
    void copiesSortsAndRejectsDuplicateRequestsOrEpisodesButAllowsReentry() {
        Case early = observed(admission(9, ADMITTED.minusMillis(1)), Outcome.EVIDENCE_GAP, null, null);
        Case low = observed(admission(1, ADMITTED), Outcome.EVIDENCE_GAP, null, null);
        Case high = observed(admission(2, ADMITTED), Outcome.EVIDENCE_GAP, null, null);
        var input = new ArrayList<>(List.of(high, early, low));
        var assessment = assessment(input);
        assertEquals(List.of(high, early, low), input);
        assertEquals(List.of(early, low, high), assessment.cases());
        input.clear();
        assertEquals(3, assessment.cases().size());
        assertThrows(UnsupportedOperationException.class, () -> assessment.cases().clear());
        for (Receipt duplicate : List.of(admission(1, 99, 4, ADMITTED), admission(99, 1, 4, ADMITTED)))
            assertThrows(IllegalArgumentException.class,
                    () -> assessment(List.of(low, observed(duplicate, Outcome.EVIDENCE_GAP, null, null))));
        assertEquals(2, assessment(List.of(low, observed(admission(99, 1, 6, CAPTURED),
                Outcome.EVIDENCE_GAP, null, null))).cases().size());
    }

    @Test
    void requiresExplicitOneThroughThirtyDayHorizonAndPositiveAtMostThirtyDayWindow() {
        for (int horizon : new int[]{Integer.MIN_VALUE, -1, 0, 31, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(OWNER, CAPTURED, FROM, horizon, List.of()));
        for (Instant bad : List.of(CAPTURED, CAPTURED.plusMillis(1), FROM.minusMillis(1)))
            assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(OWNER, CAPTURED, bad, 7, List.of()));
        assertDoesNotThrow(() -> new RetentionAssessment(OWNER, CAPTURED, CAPTURED.minusMillis(1), 30, List.of()));
        assertDoesNotThrow(() -> new RetentionAssessment(OWNER, CAPTURED, FROM, 30, List.of()));
    }

    @Test
    void rejectsSubMillisecondTimesWithoutRoundingAndBoundsUtcYearsAndDeadlines() {
        assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(OWNER, CAPTURED.plusNanos(1), FROM, 7, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(OWNER, CAPTURED, FROM.plusNanos(1), 7, List.of()));
        Receipt a = admission(1, ADMITTED.plusNanos(1));
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(a, Outcome.EVIDENCE_GAP, null, null))));
        assertThrows(IllegalArgumentException.class, () -> assessment(List.of(observed(admission(1, ADMITTED),
                Outcome.EVIDENCE_GAP, null, ADMITTED.plusNanos(1)))));
        Instant first = Instant.parse("0001-01-01T00:00:00Z"), last = Instant.parse("9999-12-31T23:59:59.999Z");
        for (Instant from : List.of(first, last.minusMillis(1)))
            assertDoesNotThrow(() -> new RetentionAssessment(OWNER, from.plusMillis(1), from, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(OWNER, first, first.minusMillis(1), 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(OWNER, last.plusMillis(1), last, 1, List.of()));
        Receipt overflow = admission(1, last.minus(Duration.ofDays(10)));
        assertThrows(IllegalArgumentException.class, () -> new RetentionAssessment(OWNER, last, overflow.appliedAt(), 30,
                List.of(observed(overflow, Outcome.NOT_MATURED, null, null))));
    }

    @Test
    void boundIncludesUnknownAndImmatureCasesWithoutTruncatingThem() {
        var cases = new ArrayList<>(IntStream.rangeClosed(1, 4096)
                .mapToObj(id -> observed(admission(id, CAPTURED), Outcome.EVIDENCE_GAP, null, null)).toList());
        assertEquals(4096, assessment(cases).cases().size());
        cases.add(observed(admission(4097, CAPTURED), Outcome.NOT_MATURED, null, null));
        assertThrows(IllegalArgumentException.class, () -> assessment(cases));
    }

    private static Case observed(Receipt a, Outcome outcome, TenancyDeparture d, Instant handover) {
        var r = a.request();
        return new Case(a, outcome, d, handover, outcome == Outcome.RETAINED && d == null
                ? new Occupancy(r.propertyId(), r.slot(), a.tenancyRevisionAfter()) : null);
    }

    private static RetentionAssessment assessment(List<Case> cases) { return new RetentionAssessment(OWNER, CAPTURED, FROM, 7, cases); }

    private static Receipt admission(long id, Instant at) { return admission(id, id, 4, at); }

    private static Receipt admission(long id, long resident, long revision, Instant at) {
        var request = new HousingMoveRequest(new UUID(0, id), new UUID(1, resident), new UUID(2, id), OWNER,
                new UUID(4, 1), "central", "standard", 2, 10, 3, revision, 1, at.atOffset(ZoneOffset.UTC).toLocalDate());
        return new Receipt(request, POLICY, revision + 1, request.day().plusDays(POLICY.cooldownDays()), at);
    }

    private static TenancyDeparture departure(Receipt a, Instant at) { return departure(a, at, Cause.DEPARTURE); }

    private static TenancyDeparture departure(Receipt a, Instant at, Cause cause) {
        var r = a.request();
        return new TenancyDeparture(r.residentId(), r.propertyId(), r.ownerId(), r.ownershipEpoch(),
                a.tenancyRevisionAfter(), r.slot(), at, cause, cause == Cause.PAID_EVICTION ? Long.valueOf(77) : null);
    }
}
