package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.RetentionAssessment;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.UtilityServiceAssessment;
import java.time.ZoneId;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Proposed DEV sample policy only; no production approval, admission or current-state authority. */
public final class PremiumHousingEligibility {
    public static final String POLICY_VERSION = "premium-housing-dev-v1";

    public enum Reason {
        ELIGIBLE, OWNER_MISMATCH, CLOCK_POLICY_MISMATCH, SERVICE_REVIEW,
        OVERDUE_UTILITIES, OVERDUE_INCIDENTS, INSUFFICIENT_UTILITY_DAYS,
        INSUFFICIENT_INCIDENTS, INSUFFICIENT_RETENTION, UNKNOWN_RATES,
        PREMIUM_THRESHOLDS_NOT_MET
    }

    /** Counts retain separate denominators; scores are absent for invalid, review or overdue evidence. */
    public record Result(boolean eligible, Reason reason, int utilityAssessedCount,
                         int distinctUtilityBillDays, int incidentAssessedCount, int retentionAssessedCount,
                         OptionalInt operatorScore, OptionalDouble propertyScore) {
        public Result {
            Objects.requireNonNull(reason); Objects.requireNonNull(operatorScore); Objects.requireNonNull(propertyScore);
            if (eligible != (reason == Reason.ELIGIBLE) || utilityAssessedCount < 0 || distinctUtilityBillDays < 0
                    || distinctUtilityBillDays > utilityAssessedCount || incidentAssessedCount < 0 || retentionAssessedCount < 0
                    || operatorScore.isPresent() != propertyScore.isPresent()
                    || operatorScore.isPresent() && (operatorScore.getAsInt() < 25 || operatorScore.getAsInt() > 75)
                    || propertyScore.isPresent() && (!Double.isFinite(propertyScore.getAsDouble())
                    || propertyScore.getAsDouble() < 0 || propertyScore.getAsDouble() > 100)
                    || eligible && (operatorScore.isEmpty() || operatorScore.getAsInt() < PropertyFormulas.PREMIUM_MIN_OP_SCORE
                    || propertyScore.orElse(-1) < PropertyFormulas.PREMIUM_MIN_SCORE
                    || distinctUtilityBillDays < PropertyFormulas.PREMIUM_MIN_OBSERVATIONS
                    || incidentAssessedCount == 0 || retentionAssessedCount == 0))
                throw new IllegalArgumentException("Invalid premium housing eligibility result");
        }
    }

    private PremiumHousingEligibility() { }

    /** Uses actual rates only, with seven distinct utility labels under one exact zone/reset policy. */
    public static Result evaluate(HousingMarketSnapshot.Home home, ServiceAssessment assessment,
                                  ZoneId zone, int resetHour) {
        Objects.requireNonNull(home); Objects.requireNonNull(assessment); Objects.requireNonNull(zone);
        if (resetHour < 0 || resetHour > 23) throw new IllegalArgumentException("Invalid premium reset hour");
        var utilities = assessment.utilities();
        var incidents = assessment.incidents();
        var retention = assessment.retention();
        int utilityCount = utilities.assessedCount();
        int utilityDays = utilities.distinctBillDays();
        int incidentCount = incidents.assessedCount();
        int retentionCount = retention.count(RetentionAssessment.Outcome.RETAINED)
                + retention.count(RetentionAssessment.Outcome.DEPARTED);
        OptionalInt operator = OptionalInt.empty();
        OptionalDouble property = OptionalDouble.empty();
        Reason reason;
        if (!home.ownerId().equals(assessment.ownerId())) reason = Reason.OWNER_MISMATCH;
        else if (utilities.cases().stream().anyMatch(c -> !c.bill().zoneId().equals(zone.getId())
                || c.bill().resetHour() != resetHour)) reason = Reason.CLOCK_POLICY_MISMATCH;
        else if (utilities.count(UtilityServiceAssessment.Outcome.HANDOVER_REVIEW) != 0
                || utilities.count(UtilityServiceAssessment.Outcome.UNVERIFIED_PAYMENT) != 0
                || incidents.count(IncidentServiceAssessment.Outcome.HANDOVER_REVIEW) != 0
                || incidents.count(IncidentServiceAssessment.Outcome.UNVERIFIED_RESOLUTION) != 0
                || retentionReview(retention)) reason = Reason.SERVICE_REVIEW;
        else if (utilities.count(UtilityServiceAssessment.Outcome.OVERDUE) != 0) reason = Reason.OVERDUE_UTILITIES;
        else if (incidents.count(IncidentServiceAssessment.Outcome.OVERDUE) != 0) reason = Reason.OVERDUE_INCIDENTS;
        else {
            operator = assessment.operatorScore();
            if (operator.isPresent()) property = OptionalDouble.of(PropertyFormulas.propertyScore(home.condition(),
                    PropertyFormulas.facilitiesScore(home.effectiveFacilities()),
                    PropertyFormulas.rentFairness(home.askingRentCents(), home.referenceRentCents()), operator.getAsInt()));
            if (utilityDays < PropertyFormulas.PREMIUM_MIN_OBSERVATIONS) reason = Reason.INSUFFICIENT_UTILITY_DAYS;
            else if (incidentCount == 0) reason = Reason.INSUFFICIENT_INCIDENTS;
            else if (retentionCount == 0) reason = Reason.INSUFFICIENT_RETENTION;
            else if (operator.isEmpty()) reason = Reason.UNKNOWN_RATES;
            else reason = PropertyFormulas.premiumEligible(property.orElseThrow(), home.condition(), operator.getAsInt(),
                    utilityDays, home.askingRentCents(), home.referenceRentCents(), false, false)
                    ? Reason.ELIGIBLE : Reason.PREMIUM_THRESHOLDS_NOT_MET;
        }
        return new Result(reason == Reason.ELIGIBLE, reason, utilityCount, utilityDays, incidentCount, retentionCount,
                operator, property);
    }

    private static boolean retentionReview(RetentionAssessment retention) {
        return retention.cases().stream().anyMatch(c -> c.outcome() == RetentionAssessment.Outcome.EVIDENCE_GAP
                || c.outcome() == RetentionAssessment.Outcome.HANDOVER_REVIEW
                // Immature handovers remain NOT_MATURED until the assessment horizon closes.
                || c.outcome() == RetentionAssessment.Outcome.NOT_MATURED && c.handoverAt() != null
                && (c.departure() == null || !c.handoverAt().isAfter(c.departure().departedAt())));
    }
}
