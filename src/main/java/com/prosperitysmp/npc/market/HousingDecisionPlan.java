package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.RetentionAssessment;
import com.prosperitysmp.npc.property.ServiceAssessment.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Immutable snapshot-bound choices; not reservations, public ratings or ARM authorization. */
public final class HousingDecisionPlan {
    public enum ColdStartPolicy { HOLD_UNRATED, PROVISIONAL_50_V1 }
    public enum RankStatus { RATED, PROVISIONAL, UNRATED_HELD, SERVICE_REVIEW }
    public record HomeRank(RankStatus status, OptionalInt planningOperatorScore, OptionalDouble rankingScore,
                           Set<Component> provisionalComponents) {
        public HomeRank {
            Objects.requireNonNull(status);
            Objects.requireNonNull(planningOperatorScore);
            Objects.requireNonNull(rankingScore);
            provisionalComponents = Set.copyOf(provisionalComponents);
            boolean ranked = status == RankStatus.RATED || status == RankStatus.PROVISIONAL;
            if (ranked != planningOperatorScore.isPresent() || ranked != rankingScore.isPresent()
                    || (status == RankStatus.PROVISIONAL) != !provisionalComponents.isEmpty()
                    || planningOperatorScore.isPresent() && (planningOperatorScore.getAsInt() < 25 || planningOperatorScore.getAsInt() > 75)
                    || rankingScore.isPresent() && (!Double.isFinite(rankingScore.getAsDouble()) || rankingScore.getAsDouble() < 0
                    || rankingScore.getAsDouble() > 100))
                throw new IllegalArgumentException("Invalid planning rank");
        }
    }

    private final HousingMarketSnapshot snapshot;
    private final ColdStartPolicy coldStartPolicy;
    private final Map<UUID, ServiceAssessment> evidence;
    private final Map<UUID, HomeRank> ranks;
    private final FiniteHousingPlanner.Plan matching;
    private final List<HousingMoveRequest> requests;

    private HousingDecisionPlan(HousingMarketSnapshot snapshot, ColdStartPolicy coldStartPolicy,
                                Map<UUID, ServiceAssessment> evidence, Map<UUID, HomeRank> ranks,
                                FiniteHousingPlanner.Plan matching, List<HousingMoveRequest> requests) {
        this.snapshot = snapshot;
        this.coldStartPolicy = coldStartPolicy;
        this.evidence = Map.copyOf(evidence);
        this.ranks = Map.copyOf(ranks);
        this.matching = matching;
        this.requests = List.copyOf(requests);
    }

    /** Advertising points, plus partner points while both flags are on, capped at 12. Zero with advertising rank off. */
    static double matchBonus(HousingMarketSnapshot.Home home, boolean advertisingRankEnabled, boolean partnerRankEnabled) {
        if (!advertisingRankEnabled) return 0.0;
        double advertising = PropertyFormulas.advertisingRankBonus(home.advertisingLevel());
        return partnerRankEnabled ? Math.min(12.0, advertising + home.partnerBonusPoints()) : advertising;
    }

    /** The repository supplies one coherent read view; DTO metadata cannot prove that provenance. */
    public static HousingDecisionPlan prepare(HousingMarketSnapshot snapshot, Map<UUID, ServiceAssessment> assessments,
                                              ColdStartPolicy coldStartPolicy) {
        return prepare(snapshot, assessments, coldStartPolicy, false, ZoneId.of("UTC"), 0);
    }

    public static HousingDecisionPlan prepare(HousingMarketSnapshot snapshot, Map<UUID, ServiceAssessment> assessments,
                                              ColdStartPolicy coldStartPolicy, boolean premiumEnabled,
                                              ZoneId zone, int resetHour) {
        return prepare(snapshot, assessments, coldStartPolicy, premiumEnabled, zone, resetHour, false);
    }

    public static HousingDecisionPlan prepare(HousingMarketSnapshot snapshot, Map<UUID, ServiceAssessment> assessments,
                                              ColdStartPolicy coldStartPolicy, boolean premiumEnabled,
                                              ZoneId zone, int resetHour, boolean advertisingRankEnabled) {
        return prepare(snapshot, assessments, coldStartPolicy, premiumEnabled, zone, resetHour, advertisingRankEnabled, false);
    }

    /**
     * partnerRankEnabled (town.partnerships rank perk): each home's partner points are added to its advertising bonus,
     * capped at 12 in total, only while advertisingRankEnabled is also on. Score, premium eligibility, rent and caps
     * are untouched; the bonus only orders compatible vacancies.
     */
    public static HousingDecisionPlan prepare(HousingMarketSnapshot snapshot, Map<UUID, ServiceAssessment> assessments,
                                              ColdStartPolicy coldStartPolicy, boolean premiumEnabled,
                                              ZoneId zone, int resetHour, boolean advertisingRankEnabled,
                                              boolean partnerRankEnabled) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(coldStartPolicy);
        Map<UUID, ServiceAssessment> evidence = Map.copyOf(assessments);
        var owners = new HashSet<UUID>();
        for (var home : snapshot.homes()) owners.add(home.ownerId());
        if (!owners.equals(evidence.keySet())) throw new IllegalArgumentException("Housing service owners must match exactly");
        Instant from = null;
        Integer horizon = null;
        for (var entry : evidence.entrySet()) {
            ServiceAssessment assessment = entry.getValue();
            if (!entry.getKey().equals(assessment.ownerId()) || !snapshot.capturedAt().equals(assessment.capturedAt())
                    || from != null && !from.equals(assessment.windowFrom())
                    || horizon != null && horizon != assessment.retention().horizonDays())
                throw new IllegalArgumentException("Housing service scope or calendar mismatch");
            from = assessment.windowFrom();
            horizon = assessment.retention().horizonDays();
        }
        var ranks = new HashMap<UUID, HomeRank>();
        var homes = new ArrayList<FiniteHousingPlanner.Home>();
        for (var home : snapshot.homes()) {
            HomeRank rank = rank(home, evidence.get(home.ownerId()), coldStartPolicy);
            ranks.put(home.propertyId(), rank);
            if (rank.rankingScore().isPresent()) homes.add(new FiniteHousingPlanner.Home(home.propertyId(), home.worldId(),
                    home.marketId(), home.classKey(), home.condition(), home.askingRentCents(),
                    rank.rankingScore().getAsDouble(), premiumEnabled
                        && PremiumHousingEligibility.evaluate(home, evidence.get(home.ownerId()), zone, resetHour).eligible(),
                    home.freeSlots(), matchBonus(home, advertisingRankEnabled, partnerRankEnabled)));
        }
        var seekers = new ArrayList<>(snapshot.seekers(premiumEnabled));
        if (premiumEnabled) for (var participant : snapshot.participants()) {
            var origin = participant.origin();
            if (participant.status() != HousingMarketSnapshot.Status.OCCUPIED || origin == null
                    || participant.member().policy().tier() != HousingPolicy.Tier.PREMIUM
                    || snapshot.admissions().stream()
                            .anyMatch(a -> a.request().residentId().equals(participant.member().residentId()))) continue;
            HomeRank source = ranks.get(origin.propertyId());
            // Missing/review evidence cannot be used to manufacture a favourable score difference.
            if (source == null || source.status() != RankStatus.RATED) continue;
            var m = participant.member();
            seekers.add(new FiniteHousingPlanner.Seeker(m.residentId(), m.policy(), m.revision(), participant.tenancyRevision(),
                    m.availableSince(), participant.cooldownUntil(), origin.propertyId(),
                    source.rankingScore().orElseThrow() + PropertyFormulas.PREMIUM_MOVE_SCORE_GAP));
        }
        FiniteHousingPlanner.Plan matching = FiniteHousingPlanner.planWithRemaining(snapshot.day(), seekers, homes, snapshot.remaining());
        var requests = new ArrayList<HousingMoveRequest>();
        for (var assignment : matching.assignments())
            requests.add(snapshot.quote(requestId(snapshot.day(), assignment.residentId()), assignment, premiumEnabled));
        return new HousingDecisionPlan(snapshot, coldStartPolicy, evidence, ranks, matching, requests);
    }

    static HomeRank rank(HousingMarketSnapshot.Home home, ServiceAssessment assessment, ColdStartPolicy policy) {
        Set<Component> missing = assessment.unknownComponents();
        // Clean immature episodes are cold start; an explicit evidence gap is never an allowance.
        boolean review = missing.contains(Component.UTILITIES) && !assessment.utilities().cases().isEmpty()
                || missing.contains(Component.INCIDENTS) && !assessment.incidents().cases().isEmpty()
                || missing.contains(Component.RETENTION) && assessment.retention().maturedCount() > 0
                || assessment.retention().count(RetentionAssessment.Outcome.EVIDENCE_GAP) > 0
                || assessment.retention().count(RetentionAssessment.Outcome.HANDOVER_REVIEW) > 0;
        if (review) return new HomeRank(RankStatus.SERVICE_REVIEW, OptionalInt.empty(), OptionalDouble.empty(), Set.of());
        if (!missing.isEmpty() && policy == ColdStartPolicy.HOLD_UNRATED)
            return new HomeRank(RankStatus.UNRATED_HELD, OptionalInt.empty(), OptionalDouble.empty(), Set.of());
        // Owner-approved decision prior only: a missing component rate counts as 0.5, known rates still count.
        // Public evidence stays unchanged.
        int operator = PropertyFormulas.operatorScore(assessment.utilities().onTimeRate().orElse(0.5),
                assessment.incidents().onTimeRate().orElse(0.5), assessment.retention().retentionRate().orElse(0.5));
        double score = PropertyFormulas.propertyScore(home.condition(), PropertyFormulas.facilitiesScore(home.effectiveFacilities()),
                PropertyFormulas.rentFairness(home.askingRentCents(), home.referenceRentCents()), operator);
        return new HomeRank(missing.isEmpty() ? RankStatus.RATED : RankStatus.PROVISIONAL,
                OptionalInt.of(operator), OptionalDouble.of(score), missing);
    }

    /** One identity per resident/day, independent of process, iteration order, selected home or retry. */
    public static UUID requestId(LocalDate day, UUID resident) {
        Objects.requireNonNull(day);
        Objects.requireNonNull(resident);
        if (day.getYear() < 1 || day.getYear() > 9999) throw new IllegalArgumentException("Housing request day out of range");
        return UUID.nameUUIDFromBytes(("prosperitynpc:standard-admission:v1|" + day + "|" + resident).getBytes(StandardCharsets.UTF_8));
    }

    public HousingMarketSnapshot snapshot() { return snapshot; }
    public ColdStartPolicy coldStartPolicy() { return coldStartPolicy; }
    public Map<UUID, ServiceAssessment> evidence() { return evidence; }
    public Map<UUID, HomeRank> ranks() { return ranks; }
    public FiniteHousingPlanner.Plan matching() { return matching; }
    public List<HousingMoveRequest> requests() { return requests; }
}
