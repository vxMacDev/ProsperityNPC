package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.economy.UtilityBill;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Immutable utility deadline cohort, inclusive of dueFrom and capturedAt.
 * The producer must verify actual bill and payment receipts and ownership; an event ID alone
 * is not proof. No missing evidence or clock policy is defaulted. This is not financial
 * authorization, an overall operator score, a retention assessment, or premium authority.
 * Times use UTC years 1..9999; UtilityBill validates its own exact-millisecond terms.
 */
public record UtilityServiceAssessment(UUID ownerId, Instant capturedAt, Instant dueFrom, List<Case> cases) {
    private static final int MAX_CASES = 4096;
    private static final Duration MAX_WINDOW = Duration.ofDays(30);
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant TIME_LIMIT = Instant.parse("+10000-01-01T00:00:00Z");

    public enum Outcome { ON_TIME, LATE, OVERDUE, HANDOVER_REVIEW, UNVERIFIED_PAYMENT }

    /** Only ON_TIME and LATE carry a payment time; an event ID alone is not payment proof. */
    public record Case(UtilityBill bill, Long paymentEventId, Instant paidAt, Outcome outcome) {
        public Case {
            Objects.requireNonNull(bill, "bill");
            Objects.requireNonNull(outcome, "outcome");
            if (paidAt != null) requireTime(paidAt);
            if (paymentEventId != null && paymentEventId <= 0)
                throw new IllegalArgumentException("Invalid utility payment event ID");
            if (paidAt != null && paidAt.isBefore(bill.issuedAt()))
                throw new IllegalArgumentException("Utility payment precedes issuance");
            boolean consistent = switch (outcome) {
                case ON_TIME -> paymentEventId != null && paidAt != null && !paidAt.isAfter(bill.dueAt());
                case LATE -> paymentEventId != null && paidAt != null && paidAt.isAfter(bill.dueAt());
                case OVERDUE, HANDOVER_REVIEW, UNVERIFIED_PAYMENT -> paidAt == null;
            };
            if (!consistent) throw new IllegalArgumentException("Utility outcome contradicts payment evidence");
        }
    }

    public UtilityServiceAssessment {
        Objects.requireNonNull(ownerId, "ownerId");
        requireTime(capturedAt);
        requireTime(dueFrom);
        if (!dueFrom.isBefore(capturedAt) || Duration.between(dueFrom, capturedAt).compareTo(MAX_WINDOW) > 0)
            throw new IllegalArgumentException("Utility assessment requires a positive window of at most 30 elapsed days");
        cases = List.copyOf(cases);
        if (cases.size() > MAX_CASES) throw new IllegalArgumentException("Utility assessment exceeds 4096 cases");
        var billKeys = new HashSet<BillKey>();
        var paymentIds = new HashSet<Long>();
        for (Case utility : cases) {
            UtilityBill bill = utility.bill();
            if (!ownerId.equals(bill.ownerId()))
                throw new IllegalArgumentException("Utility bill owner does not match assessment owner");
            if (!billKeys.add(new BillKey(bill.propertyId(), bill.day())))
                throw new IllegalArgumentException("Duplicate assessment utility bill");
            if (utility.paymentEventId() != null && !paymentIds.add(utility.paymentEventId()))
                throw new IllegalArgumentException("Duplicate assessment utility payment event");
            Instant due = bill.dueAt();
            if (due.isBefore(dueFrom) || due.isAfter(capturedAt) || bill.issuedAt().isAfter(capturedAt)
                    || (utility.paidAt() != null && utility.paidAt().isAfter(capturedAt)))
                throw new IllegalArgumentException("Utility bill is outside the captured deadline cohort");
        }
        cases = cases.stream().sorted(Comparator.comparing((Case utility) -> utility.bill().dueAt())
                .thenComparing(utility -> utility.bill().propertyId())
                .thenComparing(utility -> utility.bill().day())).toList();
    }

    public int count(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return (int) cases.stream().filter(utility -> utility.outcome() == outcome).count();
    }

    public int assessedCount() {
        return (int) cases.stream().filter(utility -> assessed(utility.outcome())).count();
    }

    /**
     * Distinct bill.day labels of assessed cases, not UTC deadline dates or premium observations.
     * Bills may use different zones and reset hours; labels do not establish shared policy or coverage.
     */
    public int distinctBillDays() {
        return (int) cases.stream().filter(utility -> assessed(utility.outcome()))
                .map(utility -> utility.bill().day()).distinct().count();
    }

    /** Missing or review-required evidence is unknown, never an assumed success or usable partial rate. */
    public OptionalDouble onTimeRate() {
        int assessed = assessedCount();
        if (assessed == 0 || count(Outcome.HANDOVER_REVIEW) != 0 || count(Outcome.UNVERIFIED_PAYMENT) != 0)
            return OptionalDouble.empty();
        return OptionalDouble.of((double) count(Outcome.ON_TIME) / assessed);
    }

    private static boolean assessed(Outcome outcome) {
        return switch (outcome) {
            case ON_TIME, LATE, OVERDUE -> true;
            case HANDOVER_REVIEW, UNVERIFIED_PAYMENT -> false;
        };
    }

    private static void requireTime(Instant time) {
        Objects.requireNonNull(time, "time");
        if (time.isBefore(MIN_TIME) || !time.isBefore(TIME_LIMIT))
            throw new IllegalArgumentException("Utility time outside UTC years 1..9999");
    }

    private record BillKey(UUID propertyId, LocalDate day) { }
}
