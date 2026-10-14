package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Home;
import com.prosperitysmp.npc.market.PremiumHousingEligibility.Reason;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.RetentionAssessment;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.TenancyDeparture;
import com.prosperitysmp.npc.property.UtilityServiceAssessment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PremiumHousingEligibilityTest {
    private static final UUID OWNER = id(100);
    private static final UUID WORLD = id(200);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final int HORIZON = 7;
    private static final Instant CAPTURED = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant FROM = CAPTURED.minus(Duration.ofDays(30));
    private static final LocalDate FIRST_BILL_DAY = LocalDate.of(2026, 9, 20);
    private static final HousingMoveRequest.Policy MOVE_POLICY = new HousingMoveRequest.Policy(128, 32, 4, 7);

    @Test
    void actualSeparateCohortsQualifyWithoutPoolingObservations() {
        var result = evaluate(home(), positive());
        assertTrue(result.eligible());
        assertEquals(Reason.ELIGIBLE, result.reason());
        assertEquals(7, result.utilityAssessedCount());
        assertEquals(7, result.distinctUtilityBillDays());
        assertEquals(1, result.incidentAssessedCount());
        assertEquals(1, result.retentionAssessedCount());
        assertEquals(75, result.operatorScore().orElseThrow());
        assertEquals(96.25, result.propertyScore().orElseThrow(), 1e-12);
        assertEquals("premium-housing-dev-v1", PremiumHousingEligibility.POLICY_VERSION);
    }

    @Test
    void coldStartHasNoProvisionalFiftyOrInventedPropertyScore() {
        var result = evaluate(home(), assessment(List.of(), List.of(), List.of()));
        assertFalse(result.eligible());
        assertEquals(Reason.INSUFFICIENT_UTILITY_DAYS, result.reason());
        assertEquals(0, result.utilityAssessedCount());
        assertEquals(0, result.distinctUtilityBillDays());
        assertEquals(0, result.incidentAssessedCount());
        assertEquals(0, result.retentionAssessedCount());
        assertNoScores(result);
    }

    @ParameterizedTest
    @CsvSource({"UTILITIES,INSUFFICIENT_UTILITY_DAYS", "INCIDENTS,INSUFFICIENT_INCIDENTS",
            "RETENTION,INSUFFICIENT_RETENTION"})
    void everyActualRateIsRequired(String missing, Reason reason) {
        var result = evaluate(home(), assessment(missing.equals("UTILITIES") ? List.of() : utilities(7, false),
                missing.equals("INCIDENTS") ? List.of() : incidents(1, 1),
                missing.equals("RETENTION") ? List.of() : retained()));
        assertFalse(result.eligible());
        assertEquals(reason, result.reason());
        assertNoScores(result);
    }

    @Test
    void sevenSameDayPropertiesAreOneBillDayNotSevenObservations() {
        var result = evaluate(home(), assessment(utilities(7, true), incidents(1, 1), retained()));
        assertFalse(result.eligible());
        assertEquals(Reason.INSUFFICIENT_UTILITY_DAYS, result.reason());
        assertEquals(7, result.utilityAssessedCount());
        assertEquals(1, result.distinctUtilityBillDays());
        assertEquals(75, result.operatorScore().orElseThrow());
    }

    @Test
    void sixUtilityDaysCannotBorrowIncidentOrRetentionObservations() {
        var result = evaluate(home(), assessment(utilities(6, false), incidents(4, 4), retained()));
        assertFalse(result.eligible());
        assertEquals(Reason.INSUFFICIENT_UTILITY_DAYS, result.reason());
        assertEquals(6, result.distinctUtilityBillDays());
        assertEquals(4, result.incidentAssessedCount());
        assertEquals(1, result.retentionAssessedCount());
    }

    @Test
    void multipleBillsPerDayKeepSeparateBillAndDayCountsWithoutChangingRates() {
        var cases = new ArrayList<>(utilities(7, false));
        for (int number = 8; number <= 14; number++) cases.add(utility(number,
                FIRST_BILL_DAY.plusDays((number - 1) % 7), ZONE, RESET, UtilityServiceAssessment.Outcome.ON_TIME));
        var result = evaluate(home(), assessment(cases, incidents(1, 1), retained()));
        assertTrue(result.eligible());
        assertEquals(14, result.utilityAssessedCount());
        assertEquals(7, result.distinctUtilityBillDays());
        assertEquals(1, result.incidentAssessedCount());
        assertEquals(1, result.retentionAssessedCount());
        assertEquals(evaluate(home(), positive()).operatorScore(), result.operatorScore());
        assertEquals(evaluate(home(), positive()).propertyScore(), result.propertyScore());
    }

    @ParameterizedTest
    @CsvSource({"UTC,4", "America/Chicago,3", "US/Central,4"})
    void mixedBillClockPoliciesCannotAuthorizePremium(String zone, int reset) {
        var cases = new ArrayList<>(utilities(7, false));
        cases.set(0, utility(1, FIRST_BILL_DAY, ZoneId.of(zone), reset, UtilityServiceAssessment.Outcome.ON_TIME));
        var result = evaluate(home(), assessment(cases, incidents(1, 1), retained()));
        assertFalse(result.eligible());
        assertEquals(Reason.CLOCK_POLICY_MISMATCH, result.reason());
        assertEquals(7, result.distinctUtilityBillDays());
        assertNoScores(result);
    }

    @Test
    void unassessedBillsAlsoRequireTheSuppliedClockPolicy() {
        var cases = new ArrayList<>(utilities(7, false));
        cases.add(utility(99, FIRST_BILL_DAY, ZoneId.of("UTC"), RESET,
                UtilityServiceAssessment.Outcome.HANDOVER_REVIEW));
        var result = evaluate(home(), assessment(cases, incidents(1, 1), retained()));
        assertFalse(result.eligible());
        assertEquals(Reason.CLOCK_POLICY_MISMATCH, result.reason());
        assertEquals(7, result.utilityAssessedCount());
    }

    @ParameterizedTest
    @EnumSource(value = UtilityServiceAssessment.Outcome.class, names = {"HANDOVER_REVIEW", "UNVERIFIED_PAYMENT"})
    void anyUtilityReviewBlocksEvenWithSevenAssessedDays(UtilityServiceAssessment.Outcome outcome) {
        var cases = new ArrayList<>(utilities(7, false));
        cases.add(utility(99, FIRST_BILL_DAY, ZONE, RESET, outcome));
        var result = evaluate(home(), assessment(cases, incidents(1, 1), retained()));
        assertFalse(result.eligible());
        assertEquals(Reason.SERVICE_REVIEW, result.reason());
        assertEquals(7, result.utilityAssessedCount());
        assertEquals(7, result.distinctUtilityBillDays());
        assertNoScores(result);
    }

    @ParameterizedTest
    @EnumSource(value = IncidentServiceAssessment.Outcome.class, names = {"HANDOVER_REVIEW", "UNVERIFIED_RESOLUTION"})
    void anyIncidentReviewBlocksEvenWithAnAssessedIncident(IncidentServiceAssessment.Outcome outcome) {
        var cases = new ArrayList<>(incidents(1, 1));
        cases.add(incident(99, outcome));
        var result = evaluate(home(), assessment(utilities(7, false), cases, retained()));
        assertFalse(result.eligible());
        assertEquals(Reason.SERVICE_REVIEW, result.reason());
        assertEquals(1, result.incidentAssessedCount());
        assertNoScores(result);
    }

    @ParameterizedTest
    @EnumSource(value = RetentionAssessment.Outcome.class, names = {"EVIDENCE_GAP", "HANDOVER_REVIEW"})
    void anyMatureRetentionReviewBlocks(RetentionAssessment.Outcome outcome) {
        var cases = new ArrayList<>(retained());
        cases.add(episode(99, outcome, false));
        var result = evaluate(home(), assessment(utilities(7, false), incidents(1, 1), cases));
        assertFalse(result.eligible());
        assertEquals(Reason.SERVICE_REVIEW, result.reason());
        assertEquals(1, result.retentionAssessedCount());
        assertNoScores(result);
    }

    @Test
    void immatureEvidenceGapBlocksEvenWhenTheUnderlyingRetentionRateIsKnown() {
        var cases = new ArrayList<>(retained());
        cases.add(episode(99, RetentionAssessment.Outcome.EVIDENCE_GAP, true));
        var evidence = assessment(utilities(7, false), incidents(1, 1), cases);
        assertEquals(1.0, evidence.retention().retentionRate().orElseThrow());
        assertEquals(75, evidence.operatorScore().orElseThrow());
        var result = evaluate(home(), evidence);
        assertFalse(result.eligible());
        assertEquals(Reason.SERVICE_REVIEW, result.reason());
        assertEquals(1, result.retentionAssessedCount());
        assertNoScores(result);
    }

    @Test
    void immatureHandoverIsReviewBeforeItsOutcomeCanBecomeHandoverReview() {
        var young = episode(99, RetentionAssessment.Outcome.NOT_MATURED, true);
        var cases = new ArrayList<>(retained());
        cases.add(new RetentionAssessment.Case(young.admission(), young.outcome(), null, CAPTURED, null));
        var evidence = assessment(utilities(7, false), incidents(1, 1), cases);
        assertEquals(1.0, evidence.retention().retentionRate().orElseThrow());
        var result = evaluate(home(), evidence);
        assertFalse(result.eligible());
        assertEquals(Reason.SERVICE_REVIEW, result.reason());
        assertNoScores(result);
    }

    @Test
    void cleanImmatureEpisodeDoesNotInflateTheMatureRetentionDenominator() {
        var young = episode(99, RetentionAssessment.Outcome.NOT_MATURED, true);
        var cases = new ArrayList<>(retained());
        cases.add(young);
        var result = evaluate(home(), assessment(utilities(7, false), incidents(1, 1), cases));
        assertTrue(result.eligible());
        assertEquals(1, result.retentionAssessedCount());
        assertEquals(75, result.operatorScore().orElseThrow());
        var immatureOnly = evaluate(home(), assessment(utilities(7, false), incidents(1, 1), List.of(young)));
        assertFalse(immatureOnly.eligible());
        assertEquals(Reason.INSUFFICIENT_RETENTION, immatureOnly.reason());
        assertEquals(0, immatureOnly.retentionAssessedCount());
        assertNoScores(immatureOnly);
    }

    @Test
    void earlyDepartureStillWaitsForMaturityAndLaterHandoverDoesNotReopenIt() {
        var young = episode(99, RetentionAssessment.Outcome.NOT_MATURED, true);
        var request = young.admission().request();
        var departure = new TenancyDeparture(request.residentId(), request.propertyId(), OWNER, 6, 5, 1,
                young.admission().appliedAt().plusMillis(1), TenancyDeparture.Cause.DEPARTURE, null);
        var waiting = new RetentionAssessment.Case(young.admission(), young.outcome(), departure, CAPTURED, null);
        var immatureOnly = evaluate(home(), assessment(utilities(7, false), incidents(1, 1), List.of(waiting)));
        assertFalse(immatureOnly.eligible());
        assertEquals(Reason.INSUFFICIENT_RETENTION, immatureOnly.reason());
        assertEquals(0, immatureOnly.retentionAssessedCount());
        assertNoScores(immatureOnly);
        var cases = new ArrayList<>(retained());
        cases.add(waiting);
        var matureCohort = evaluate(home(), assessment(utilities(7, false), incidents(1, 1), cases));
        assertTrue(matureCohort.eligible());
        assertEquals(1, matureCohort.retentionAssessedCount());
    }

    @Test
    void maturedDepartureIsAnAssessedEpisodeButNotRetentionSuccess() {
        var result = evaluate(home(), assessment(utilities(7, false), incidents(1, 1),
                List.of(episode(1, RetentionAssessment.Outcome.DEPARTED, false))));
        assertFalse(result.eligible());
        assertEquals(Reason.PREMIUM_THRESHOLDS_NOT_MET, result.reason());
        assertEquals(1, result.retentionAssessedCount());
        assertEquals(65, result.operatorScore().orElseThrow());
    }

    @Test
    void overdueUtilityBlocksEvenWhenTheActualOperatorScoreWouldPass() {
        var cases = new ArrayList<>(utilities(7, false));
        cases.set(0, utility(1, FIRST_BILL_DAY, ZONE, RESET, UtilityServiceAssessment.Outcome.OVERDUE));
        var evidence = assessment(cases, incidents(1, 1), retained());
        assertTrue(evidence.operatorScore().orElseThrow() >= PropertyFormulas.PREMIUM_MIN_OP_SCORE);
        var result = evaluate(home(), evidence);
        assertFalse(result.eligible());
        assertEquals(Reason.OVERDUE_UTILITIES, result.reason());
        assertEquals(7, result.utilityAssessedCount());
        assertNoScores(result);
    }

    @Test
    void overdueIncidentBlocksEvenWhenTheActualOperatorScoreWouldPass() {
        var cases = new ArrayList<>(incidents(4, 4));
        cases.add(incident(5, IncidentServiceAssessment.Outcome.OVERDUE));
        var evidence = assessment(utilities(7, false), cases, retained());
        assertTrue(evidence.operatorScore().orElseThrow() >= PropertyFormulas.PREMIUM_MIN_OP_SCORE);
        var result = evaluate(home(), evidence);
        assertFalse(result.eligible());
        assertEquals(Reason.OVERDUE_INCIDENTS, result.reason());
        assertEquals(5, result.incidentAssessedCount());
        assertNoScores(result);
    }

    @ParameterizedTest
    @CsvSource({"3,4,70,true", "2,3,68,false"})
    void realIncidentRatesPinTheOperatorThreshold(int onTime, int total, int score, boolean eligible) {
        var result = evaluate(home(), assessment(utilities(7, false), incidents(onTime, total), retained()));
        assertEquals(eligible, result.eligible());
        assertEquals(eligible ? Reason.ELIGIBLE : Reason.PREMIUM_THRESHOLDS_NOT_MET, result.reason());
        assertEquals(score, result.operatorScore().orElseThrow());
        assertEquals(total, result.incidentAssessedCount());
    }

    @ParameterizedTest
    @CsvSource({"1185,80.0,true", "1188,79.8,false"})
    void calculatedPropertyScorePinsTheExistingThreshold(long asking, double score, boolean eligible) {
        var result = evaluate(home(OWNER, 82, 3, 3, asking),
                assessment(utilities(7, false), incidents(3, 4), retained()));
        assertEquals(eligible, result.eligible());
        assertEquals(eligible ? Reason.ELIGIBLE : Reason.PREMIUM_THRESHOLDS_NOT_MET, result.reason());
        assertEquals(70, result.operatorScore().orElseThrow());
        assertEquals(score, result.propertyScore().orElseThrow(), 1e-12);
    }

    @ParameterizedTest
    @CsvSource({"80,true", "79,false"})
    void conditionMinimumAppliesEvenWithAnOtherwiseHighScore(int condition, boolean eligible) {
        var result = evaluate(home(OWNER, condition, 3, 3, 1000), positive());
        assertEquals(eligible, result.eligible());
        assertTrue(result.propertyScore().orElseThrow() >= PropertyFormulas.PREMIUM_MIN_SCORE);
    }

    @ParameterizedTest
    @CsvSource({"1250,true", "1251,false"})
    void askingCapIsInclusiveAndRejectsOneCentAboveIt(long asking, boolean eligible) {
        var result = evaluate(home(OWNER, 100, 3, 3, asking), positive());
        assertEquals(eligible, result.eligible());
        assertEquals(eligible ? Reason.ELIGIBLE : Reason.PREMIUM_THRESHOLDS_NOT_MET, result.reason());
        assertTrue(result.propertyScore().orElseThrow() >= PropertyFormulas.PREMIUM_MIN_SCORE);
    }

    @Test
    void disabledFacilitiesCannotContributeInstalledLevelToTheScore() {
        var result = evaluate(home(OWNER, 100, 3, 0, 1000), positive());
        assertFalse(result.eligible());
        assertEquals(Reason.PREMIUM_THRESHOLDS_NOT_MET, result.reason());
        assertEquals(76.25, result.propertyScore().orElseThrow(), 1e-12);
    }

    @Test
    void unrelatedOwnersEvidenceCannotQualifyAHome() {
        var result = evaluate(home(id(101), 100, 3, 3, 1000), positive());
        assertFalse(result.eligible());
        assertEquals(Reason.OWNER_MISMATCH, result.reason());
        assertNoScores(result);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 24})
    void invalidResetHoursAreRejected(int reset) {
        assertThrows(IllegalArgumentException.class, () -> PremiumHousingEligibility.evaluate(home(), positive(), ZONE, reset));
    }

    @Test
    void resultCannotClaimEligibilityWithUnknownScoresOrInsufficientIndependentSamples() {
        assertThrows(IllegalArgumentException.class, () -> new PremiumHousingEligibility.Result(true, Reason.SERVICE_REVIEW,
                7, 7, 1, 1, OptionalInt.of(75), OptionalDouble.of(96.25)));
        assertThrows(IllegalArgumentException.class, () -> new PremiumHousingEligibility.Result(true, Reason.ELIGIBLE,
                7, 7, 1, 1, OptionalInt.empty(), OptionalDouble.empty()));
        assertThrows(IllegalArgumentException.class, () -> new PremiumHousingEligibility.Result(true, Reason.ELIGIBLE,
                7, 1, 1, 1, OptionalInt.of(75), OptionalDouble.of(96.25)));
        assertThrows(IllegalArgumentException.class, () -> new PremiumHousingEligibility.Result(true, Reason.ELIGIBLE,
                7, 7, 0, 1, OptionalInt.of(75), OptionalDouble.of(96.25)));
        assertThrows(IllegalArgumentException.class, () -> new PremiumHousingEligibility.Result(true, Reason.ELIGIBLE,
                7, 7, 1, 0, OptionalInt.of(75), OptionalDouble.of(96.25)));
        assertThrows(IllegalArgumentException.class, () -> new PremiumHousingEligibility.Result(true, Reason.ELIGIBLE,
                7, 7, 1, 1, OptionalInt.of(69), OptionalDouble.of(95.35)));
        assertThrows(IllegalArgumentException.class, () -> new PremiumHousingEligibility.Result(true, Reason.ELIGIBLE,
                7, 7, 1, 1, OptionalInt.of(75), OptionalDouble.of(79.9)));
    }

    private static PremiumHousingEligibility.Result evaluate(Home home, ServiceAssessment evidence) {
        return PremiumHousingEligibility.evaluate(home, evidence, ZONE, RESET);
    }

    private static void assertNoScores(PremiumHousingEligibility.Result result) {
        assertTrue(result.operatorScore().isEmpty());
        assertTrue(result.propertyScore().isEmpty());
    }

    private static Home home() { return home(OWNER, 100, 3, 3, 1000); }

    private static Home home(UUID owner, int condition, int installed, int effective, long asking) {
        return new Home(id(500), owner, WORLD, "region-500", "town", "flat",
                6, 7, 4, 1000, asking, condition, installed, effective, List.of(0));
    }

    private static ServiceAssessment positive() {
        return assessment(utilities(7, false), incidents(1, 1), retained());
    }

    private static ServiceAssessment assessment(List<UtilityServiceAssessment.Case> utilities,
                                                List<IncidentServiceAssessment.Case> incidents,
                                                List<RetentionAssessment.Case> retention) {
        return new ServiceAssessment(new UtilityServiceAssessment(OWNER, CAPTURED, FROM, utilities),
                new IncidentServiceAssessment(OWNER, CAPTURED, FROM, incidents),
                new RetentionAssessment(OWNER, CAPTURED, FROM, HORIZON, retention));
    }

    private static List<UtilityServiceAssessment.Case> utilities(int total, boolean sameDay) {
        return IntStream.rangeClosed(1, total).mapToObj(number -> utility(number,
                FIRST_BILL_DAY.plusDays(sameDay ? 0 : number - 1), ZONE, RESET,
                UtilityServiceAssessment.Outcome.ON_TIME)).toList();
    }

    private static UtilityServiceAssessment.Case utility(int number, LocalDate day, ZoneId zone, int reset,
                                                         UtilityServiceAssessment.Outcome outcome) {
        Instant issued = day.atTime(reset, 0).atZone(zone).toInstant().plusSeconds(60);
        var bill = new UtilityBill(new UUID(2, number), OWNER, 6, 7, day, 4, 2, 3, issued, zone.getId(), reset);
        Instant paid = switch (outcome) {
            case ON_TIME -> bill.dueAt();
            case LATE -> bill.dueAt().plusMillis(1);
            default -> null;
        };
        return new UtilityServiceAssessment.Case(bill, paid == null ? null : Long.valueOf(number), paid, outcome);
    }

    private static List<IncidentServiceAssessment.Case> incidents(int onTime, int total) {
        return IntStream.rangeClosed(1, total).mapToObj(number -> incident(number,
                number <= onTime ? IncidentServiceAssessment.Outcome.ON_TIME : IncidentServiceAssessment.Outcome.LATE)).toList();
    }

    private static IncidentServiceAssessment.Case incident(int number, IncidentServiceAssessment.Outcome outcome) {
        Instant due = CAPTURED.minusSeconds(60);
        Instant resolved = switch (outcome) {
            case ON_TIME, UNVERIFIED_RESOLUTION -> due;
            case LATE -> due.plusMillis(1);
            default -> null;
        };
        Long event = outcome == IncidentServiceAssessment.Outcome.ON_TIME || outcome == IncidentServiceAssessment.Outcome.LATE
                ? Long.valueOf(number) : null;
        return new IncidentServiceAssessment.Case(number, new UUID(2, number), 6,
                due.minus(Duration.ofHours(24)), due, resolved, event, outcome);
    }

    private static List<RetentionAssessment.Case> retained() {
        return List.of(episode(1, RetentionAssessment.Outcome.RETAINED, false));
    }

    private static RetentionAssessment.Case episode(int number, RetentionAssessment.Outcome outcome, boolean immature) {
        Instant admitted = CAPTURED.minus(Duration.ofDays(HORIZON)).plusMillis(immature ? 1 : 0);
        LocalDate day = admitted.atZone(ZONE).toLocalDate();
        var request = new HousingMoveRequest(new UUID(3, number), new UUID(4, number), new UUID(2, number), OWNER,
                WORLD, "town", "flat", 6, 7, 8, 4, 1, day);
        var receipt = new HousingMoveRepository.Receipt(request, MOVE_POLICY, 5, day.plusDays(7), admitted);
        var departure = outcome == RetentionAssessment.Outcome.DEPARTED
                ? new TenancyDeparture(request.residentId(), request.propertyId(), OWNER, 6, 5, 1,
                        CAPTURED.minusMillis(1), TenancyDeparture.Cause.DEPARTURE, null) : null;
        var occupancy = outcome == RetentionAssessment.Outcome.RETAINED
                ? new RetentionAssessment.Occupancy(request.propertyId(), 1, 5) : null;
        return new RetentionAssessment.Case(receipt, outcome, departure,
                outcome == RetentionAssessment.Outcome.HANDOVER_REVIEW ? CAPTURED : null, occupancy);
    }

    private static UUID id(long number) { return new UUID(0, number); }
}
