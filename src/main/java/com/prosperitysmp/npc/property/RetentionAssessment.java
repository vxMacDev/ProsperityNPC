package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/** Recorded tenancy episodes, not occupancy, satisfaction, landlord fault or premium authority. */
public record RetentionAssessment(UUID ownerId, Instant capturedAt, Instant admittedFrom,
                                  int horizonDays, List<Case> cases) {
    public static final int MAX_CASES = 4096;
    public enum Outcome { RETAINED, DEPARTED, NOT_MATURED, HANDOVER_REVIEW, EVIDENCE_GAP }
    public record Occupancy(UUID propertyId, int slot, long revision) {
        public Occupancy {
            Objects.requireNonNull(propertyId);
            if (slot < 0 || slot > 3 || revision < 0) throw new IllegalArgumentException("Invalid retention occupancy");
        }
    }
    public record Case(Receipt admission, Outcome outcome, TenancyDeparture departure, Instant handoverAt,
                       Occupancy occupancy) {
        public Case {
            Objects.requireNonNull(admission);
            Objects.requireNonNull(outcome);
        }
    }

    public RetentionAssessment {
        Objects.requireNonNull(ownerId);
        time(capturedAt);
        time(admittedFrom);
        if (!admittedFrom.isBefore(capturedAt)
                || Duration.between(admittedFrom, capturedAt).compareTo(Duration.ofDays(30)) > 0
                || horizonDays < 1 || horizonDays > 30)
            throw new IllegalArgumentException("Retention requires a <=30-day window and explicit 1..30-day horizon");
        cases = List.copyOf(cases);
        if (cases.size() > MAX_CASES) throw new IllegalArgumentException("Retention cohort exceeds bound");
        var ids = new HashSet<UUID>();
        var episodes = new HashSet<Episode>();
        for (Case c : cases) {
            Receipt a = c.admission();
            var r = a.request();
            time(a.appliedAt());
            if (!r.ownerId().equals(ownerId) || a.appliedAt().isBefore(admittedFrom)
                    || a.appliedAt().isAfter(capturedAt) || !ids.add(r.requestId())
                    || !episodes.add(new Episode(r.residentId(), a.tenancyRevisionAfter()))
                    || a.tenancyRevisionAfter() != r.tenancyRevision() + (r.origin() == null ? 1 : 2))
                throw new IllegalArgumentException("Invalid retention admission cohort");
            Instant deadline = a.appliedAt().plus(Duration.ofDays(horizonDays));
            time(deadline);
            boolean mature = !deadline.isAfter(capturedAt);
            TenancyDeparture d = c.departure();
            if (d != null && (!d.residentId().equals(r.residentId()) || !d.propertyId().equals(r.propertyId())
                    || d.slot() != r.slot() || d.occupancyRevisionBefore() != a.tenancyRevisionAfter()
                    || d.departedAt().isBefore(a.appliedAt()) || d.departedAt().isAfter(capturedAt)))
                throw new IllegalArgumentException("Departure does not bind to retention admission");
            Instant end = d != null && d.departedAt().isBefore(deadline) ? d.departedAt() : deadline;
            if (c.handoverAt() != null) {
                time(c.handoverAt());
                if (c.handoverAt().isBefore(a.appliedAt()) || c.handoverAt().isAfter(capturedAt))
                    throw new IllegalArgumentException("Invalid retention handover time");
            }
            boolean crossedOwner = c.handoverAt() != null && !c.handoverAt().isAfter(end);
            if (d != null && c.outcome() != Outcome.EVIDENCE_GAP && (d.ownershipEpoch() < r.ownershipEpoch()
                    || d.ownershipEpoch() == r.ownershipEpoch() && !Objects.equals(d.ownerAtDeparture(), r.ownerId())
                    || (c.handoverAt() == null || c.handoverAt().isAfter(d.departedAt()))
                    && (!Objects.equals(d.ownerAtDeparture(), r.ownerId()) || d.ownershipEpoch() != r.ownershipEpoch())))
                throw new IllegalArgumentException("Departure ownership requires a handover record");
            boolean active = c.occupancy() != null && c.occupancy().propertyId().equals(r.propertyId())
                    && c.occupancy().slot() == r.slot() && c.occupancy().revision() == a.tenancyRevisionAfter();
            boolean valid = switch (c.outcome()) {
                case NOT_MATURED -> !mature;
                case HANDOVER_REVIEW -> mature && crossedOwner;
                case EVIDENCE_GAP -> true;
                case RETAINED -> mature && !crossedOwner && (d == null ? active : !d.departedAt().isBefore(deadline));
                case DEPARTED -> mature && !crossedOwner && d != null && d.departedAt().isBefore(deadline);
            };
            if (!valid) throw new IllegalArgumentException("Retention outcome contradicts its observation window");
        }
        cases = cases.stream().sorted(Comparator.comparing((Case c) -> c.admission().appliedAt())
                .thenComparing(c -> c.admission().request().requestId())).toList();
    }

    public int count(Outcome outcome) {
        Objects.requireNonNull(outcome);
        return (int) cases.stream().filter(c -> c.outcome() == outcome).count();
    }

    /** Immature episodes, including known early departures, wait for the same horizon. */
    public int maturedCount() {
        return (int) cases.stream().filter(c -> !c.admission().appliedAt()
                .plus(Duration.ofDays(horizonDays)).isAfter(capturedAt)).count();
    }

    /** No selective denominator: any unresolved matured case makes the rate unknown. */
    public OptionalDouble retentionRate() {
        int mature = maturedCount();
        if (mature == 0 || cases.stream().anyMatch(c ->
                !c.admission().appliedAt().plus(Duration.ofDays(horizonDays)).isAfter(capturedAt)
                && (c.outcome() == Outcome.EVIDENCE_GAP || c.outcome() == Outcome.HANDOVER_REVIEW)))
            return OptionalDouble.empty();
        return OptionalDouble.of((double) count(Outcome.RETAINED) / mature);
    }

    static void time(Instant at) {
        Objects.requireNonNull(at);
        if (at.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || !at.isBefore(Instant.parse("+10000-01-01T00:00:00Z")) || at.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("Retention time requires exact UTC milliseconds in years 1..9999");
    }
    private record Episode(UUID resident, long revision) { }
}
