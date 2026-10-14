package com.prosperitysmp.npc.property;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Immutable assessment of a journaled incident deadline cohort, inclusive of dueFrom and capturedAt.
 * The producer must verify journal and repair provenance and ownership; an event ID alone is not proof.
 * This does not assess utilities or retention, or prove complete legacy history. It is not an overall
 * operator score, premium authority, or a financial decision. Times use the journal's UTC years 1..9999.
 */
public record IncidentServiceAssessment(UUID ownerId, Instant capturedAt, Instant dueFrom, List<Case> cases) {
    private static final int MAX_CASES = 512;
    private static final Duration MAX_WINDOW = Duration.ofDays(30);
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant TIME_LIMIT = Instant.parse("+10000-01-01T00:00:00Z");

    public enum Outcome { ON_TIME, LATE, OVERDUE, HANDOVER_REVIEW, UNVERIFIED_RESOLUTION }

    /**
     * HANDOVER_REVIEW retains either an open handover or another owner's proven repair. A proven repair is either a
     * paid cash repair (repairEventId) or an item repair (itemRepairId, its hand-in id), never both.
     */
    public record Case(long incidentId, UUID propertyId, int ownershipEpoch, Instant createdAt,
                       Instant dueAt, Instant resolvedAt, Long repairEventId, UUID itemRepairId, Outcome outcome) {
        public Case(long incidentId, UUID propertyId, int ownershipEpoch, Instant createdAt,
                    Instant dueAt, Instant resolvedAt, Long repairEventId, Outcome outcome) {
            this(incidentId, propertyId, ownershipEpoch, createdAt, dueAt, resolvedAt, repairEventId, null, outcome);
        }
        public Case {
            Objects.requireNonNull(propertyId, "propertyId");
            Objects.requireNonNull(outcome, "outcome");
            requireTime(createdAt);
            requireTime(dueAt);
            if (resolvedAt != null) requireTime(resolvedAt);
            if (incidentId <= 0 || ownershipEpoch < 0 || (repairEventId != null && repairEventId <= 0))
                throw new IllegalArgumentException("Invalid incident identity, epoch or repair event ID");
            if (!createdAt.isBefore(dueAt) || (resolvedAt != null && resolvedAt.isBefore(createdAt)))
                throw new IllegalArgumentException("Invalid incident chronology");
            if (repairEventId != null && itemRepairId != null)
                throw new IllegalArgumentException("A repair is paid in cash or in items, never both");
            boolean proven = repairEventId != null || itemRepairId != null;
            boolean consistent = switch (outcome) {
                case ON_TIME -> resolvedAt != null && proven && !resolvedAt.isAfter(dueAt);
                case LATE -> resolvedAt != null && proven && resolvedAt.isAfter(dueAt);
                case OVERDUE -> resolvedAt == null && !proven;
                case HANDOVER_REVIEW -> (resolvedAt == null) == !proven;
                case UNVERIFIED_RESOLUTION -> resolvedAt != null && !proven;
            };
            if (!consistent) throw new IllegalArgumentException("Incident outcome contradicts resolution evidence");
        }
    }

    public IncidentServiceAssessment {
        Objects.requireNonNull(ownerId, "ownerId");
        requireTime(capturedAt);
        requireTime(dueFrom);
        if (!dueFrom.isBefore(capturedAt) || Duration.between(dueFrom, capturedAt).compareTo(MAX_WINDOW) > 0)
            throw new IllegalArgumentException("Incident assessment requires a positive window of at most 30 days");
        cases = List.copyOf(cases);
        if (cases.size() > MAX_CASES) throw new IllegalArgumentException("Incident assessment exceeds 512 cases");
        var incidentIds = new HashSet<Long>();
        for (Case incident : cases) {
            if (!incidentIds.add(incident.incidentId()))
                throw new IllegalArgumentException("Duplicate assessment incident");
            if (incident.dueAt().isBefore(dueFrom) || incident.dueAt().isAfter(capturedAt)
                    || (incident.resolvedAt() != null && incident.resolvedAt().isAfter(capturedAt)))
                throw new IllegalArgumentException("Incident is outside the captured deadline cohort");
        }
        cases = cases.stream().sorted(Comparator.comparing(Case::dueAt).thenComparingLong(Case::incidentId)).toList();
    }

    public int count(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return (int) cases.stream().filter(incident -> incident.outcome() == outcome).count();
    }

    public int assessedCount() {
        return (int) cases.stream().filter(incident -> assessed(incident.outcome())).count();
    }

    /** Distinct UTC calendar dates of assessed deadlines, not economy days or resolution dates. */
    public int distinctDueDays() {
        return (int) cases.stream().filter(incident -> assessed(incident.outcome()))
                .map(incident -> incident.dueAt().atOffset(ZoneOffset.UTC).toLocalDate()).distinct().count();
    }

    /** Missing or review-required evidence is unknown, never an assumed success or a usable partial rate. */
    public OptionalDouble onTimeRate() {
        int assessed = assessedCount();
        if (assessed == 0 || count(Outcome.HANDOVER_REVIEW) != 0 || count(Outcome.UNVERIFIED_RESOLUTION) != 0)
            return OptionalDouble.empty();
        return OptionalDouble.of((double) count(Outcome.ON_TIME) / assessed);
    }

    private static boolean assessed(Outcome outcome) {
        return switch (outcome) {
            case ON_TIME, LATE, OVERDUE -> true;
            case HANDOVER_REVIEW, UNVERIFIED_RESOLUTION -> false;
        };
    }

    private static void requireTime(Instant time) {
        Objects.requireNonNull(time, "time");
        if (time.isBefore(MIN_TIME) || !time.isBefore(TIME_LIMIT))
            throw new IllegalArgumentException("Incident time outside UTC years 1..9999");
    }
}
