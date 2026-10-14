package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.market.HousingDecisionPlan.ColdStartPolicy;
import com.prosperitysmp.npc.market.HousingDecisionPlan.RankStatus;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Home;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Participant;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Status;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.RetentionAssessment;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.ServiceAssessment.Component;
import com.prosperitysmp.npc.property.TenancyDeparture;
import com.prosperitysmp.npc.property.UtilityServiceAssessment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class HousingDecisionPlanTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T12:34:56.789Z");
    private static final Instant FROM = CAPTURED.minus(Duration.ofDays(30));
    private static final UUID OWNER = id(100);
    private static final UUID OTHER_OWNER = id(101);
    private static final UUID WORLD = id(200);
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(8, 8, 4, 7);
    private static final ColdStartPolicy PROVISIONAL = ColdStartPolicy.PROVISIONAL_50_V1;

    @Test
    void freshLandlordPlansStandardAtFiftyWithoutPublishingAnOperatorScore() {
        var evidence = empty(OWNER);
        var plan = prepare(List.of(home(1)), Map.of(OWNER, evidence));
        var rank = plan.ranks().get(id(1));
        assertEquals(PROVISIONAL, plan.coldStartPolicy());
        assertEquals(RankStatus.PROVISIONAL, rank.status());
        assertEquals(Set.of(Component.UTILITIES, Component.INCIDENTS, Component.RETENTION), rank.provisionalComponents());
        assertEquals(50, rank.planningOperatorScore().orElseThrow());
        assertEquals(63.5, rank.rankingScore().orElseThrow(), 1e-12);
        assertSame(evidence, plan.evidence().get(OWNER));
        assertTrue(plan.evidence().get(OWNER).operatorScore().isEmpty());
        assertEquals(List.of(new FiniteHousingPlanner.Assignment(id(10), id(1), 0, 8, -1)), plan.matching().assignments());
        assertEquals(1, plan.requests().size());
        assertTrue(plan.matching().unmatched().isEmpty());
    }

    @Test
    void holdUnratedHasNeitherRanksNorAssignmentsForFreshOwners() {
        var plan = HousingDecisionPlan.prepare(snapshot(List.of(home(1))), Map.of(OWNER, empty(OWNER)),
                ColdStartPolicy.HOLD_UNRATED);
        var rank = plan.ranks().get(id(1));
        assertEquals(ColdStartPolicy.HOLD_UNRATED, plan.coldStartPolicy());
        assertEquals(RankStatus.UNRATED_HELD, rank.status());
        assertTrue(rank.planningOperatorScore().isEmpty());
        assertTrue(rank.rankingScore().isEmpty());
        assertTrue(rank.provisionalComponents().isEmpty());
        assertTrue(plan.requests().isEmpty());
        assertTrue(plan.matching().assignments().isEmpty());
        assertEquals(List.of(new FiniteHousingPlanner.Unmatched(id(10), FiniteHousingPlanner.Reason.NO_COMPATIBLE_HOME)),
                plan.matching().unmatched());
    }

    @ParameterizedTest
    @CsvSource({"UTILITIES,40,62.0", "INCIDENTS,40,62.0", "RETENTION,45,62.75"})
    void knownBadComponentStillReducesTheProvisionalRank(Component component, int operator, double score) {
        var evidence = assessment(OWNER,
                component == Component.UTILITIES ? "OVERDUE" : null,
                component == Component.INCIDENTS ? "OVERDUE" : null,
                component == Component.RETENTION ? "DEPARTED" : null);
        var plan = prepare(List.of(home(1)), Map.of(OWNER, evidence));
        var rank = plan.ranks().get(id(1));
        assertEquals(RankStatus.PROVISIONAL, rank.status());
        assertEquals(operator, rank.planningOperatorScore().orElseThrow());
        assertEquals(score, rank.rankingScore().orElseThrow(), 1e-12);
        assertEquals(2, rank.provisionalComponents().size());
        assertFalse(rank.provisionalComponents().contains(component));
        assertTrue(plan.evidence().get(OWNER).operatorScore().isEmpty());
        assertEquals(1, plan.requests().size());
    }

    @ParameterizedTest
    @CsvSource({"UTILITIES,HANDOVER_REVIEW", "UTILITIES,UNVERIFIED_PAYMENT",
            "INCIDENTS,HANDOVER_REVIEW", "INCIDENTS,UNVERIFIED_RESOLUTION",
            "RETENTION,HANDOVER_REVIEW", "RETENTION,EVIDENCE_GAP"})
    void everyMatureReviewExcludesTheHomeInsteadOfGivingItANeutralPrior(Component component, String outcome) {
        var review = assessment(OWNER, component == Component.UTILITIES ? outcome : "ON_TIME",
                component == Component.INCIDENTS ? outcome : "ON_TIME",
                component == Component.RETENTION ? outcome : "RETAINED");
        var alternative = home(2, OTHER_OWNER, 60, 0, 0, 1000, List.of(0));
        var plan = prepare(List.of(home(1), alternative), Map.of(OWNER, review, OTHER_OWNER, empty(OTHER_OWNER)));
        var rank = plan.ranks().get(id(1));
        assertEquals(RankStatus.SERVICE_REVIEW, rank.status());
        assertTrue(rank.planningOperatorScore().isEmpty());
        assertTrue(rank.rankingScore().isEmpty());
        assertTrue(rank.provisionalComponents().isEmpty());
        assertTrue(plan.evidence().get(OWNER).operatorScore().isEmpty());
        assertEquals(1, plan.requests().size());
        assertEquals(id(2), plan.requests().getFirst().propertyId());
    }

    @Test
    void cleanImmatureRetentionRemainsColdStart() {
        var evidence = new ServiceAssessment(new UtilityServiceAssessment(OWNER, CAPTURED, FROM, List.of()),
                new IncidentServiceAssessment(OWNER, CAPTURED, FROM, List.of()),
                new RetentionAssessment(OWNER, CAPTURED, FROM, 7,
                        List.of(episode(OWNER, RetentionAssessment.Outcome.NOT_MATURED, true))));
        var plan = prepare(List.of(home(1)), Map.of(OWNER, evidence));
        assertEquals(0, evidence.retention().maturedCount());
        assertEquals(RankStatus.PROVISIONAL, plan.ranks().get(id(1)).status());
        assertEquals(50, plan.ranks().get(id(1)).planningOperatorScore().orElseThrow());
        assertEquals(Set.of(Component.UTILITIES, Component.INCIDENTS, Component.RETENTION),
                plan.ranks().get(id(1)).provisionalComponents());
        assertEquals(1, plan.requests().size());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void immatureEvidenceGapNeverQualifiesForAllowanceEvenBesideKnownMatureHistory(boolean knownMature) {
        var episodes = new ArrayList<RetentionAssessment.Case>();
        episodes.add(episode(OWNER, RetentionAssessment.Outcome.EVIDENCE_GAP, true));
        if (knownMature) episodes.add(episode(OWNER, RetentionAssessment.Outcome.RETAINED, false));
        var evidence = new ServiceAssessment(new UtilityServiceAssessment(OWNER, CAPTURED, FROM, List.of()),
                new IncidentServiceAssessment(OWNER, CAPTURED, FROM, List.of()),
                new RetentionAssessment(OWNER, CAPTURED, FROM, 7, episodes));
        var plan = prepare(List.of(home(1)), Map.of(OWNER, evidence));
        assertEquals(knownMature ? 1 : 0, evidence.retention().maturedCount());
        assertEquals(RankStatus.SERVICE_REVIEW, plan.ranks().get(id(1)).status());
        assertTrue(plan.ranks().get(id(1)).planningOperatorScore().isEmpty());
        assertTrue(plan.requests().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"owner", "cutoff", "window", "horizon", "extra", "missing"})
    void rejectsIndividuallyValidEvidenceWithIncoherentSnapshotScope(String mismatch) {
        var snapshot = snapshot(List.of(home(1), home(2, OTHER_OWNER, 80, 0, 0, 1000, List.of(0))));
        var evidence = new HashMap<UUID, ServiceAssessment>();
        evidence.put(OWNER, empty(OWNER));
        evidence.put(OTHER_OWNER, empty(OTHER_OWNER));
        switch (mismatch) {
            case "owner" -> evidence.put(OWNER, empty(OTHER_OWNER));
            case "cutoff" -> evidence.put(OTHER_OWNER, empty(OTHER_OWNER, CAPTURED.minusMillis(1), FROM, 7));
            case "window" -> evidence.put(OTHER_OWNER, empty(OTHER_OWNER, CAPTURED, FROM.plusMillis(1), 7));
            case "horizon" -> evidence.put(OTHER_OWNER, empty(OTHER_OWNER, CAPTURED, FROM, 8));
            case "extra" -> evidence.put(id(102), empty(id(102)));
            case "missing" -> evidence.remove(OTHER_OWNER);
            default -> fail("Unexpected mismatch fixture");
        }
        assertThrows(IllegalArgumentException.class, () -> HousingDecisionPlan.prepare(snapshot, evidence, PROVISIONAL));
    }

    @ParameterizedTest
    @CsvSource({"ON_TIME,ON_TIME,RETAINED,75,67.25", "OVERDUE,OVERDUE,DEPARTED,25,59.75"})
    void fullyKnownEvidenceUsesNoPriorUnderEitherPolicy(String utility, String incident, String retention,
                                                        int operator, double score) {
        var evidence = assessment(OWNER, utility, incident, retention);
        for (var policy : ColdStartPolicy.values()) {
            var plan = HousingDecisionPlan.prepare(snapshot(List.of(home(1))), Map.of(OWNER, evidence), policy);
            var rank = plan.ranks().get(id(1));
            assertEquals(RankStatus.RATED, rank.status());
            assertEquals(operator, rank.planningOperatorScore().orElseThrow());
            assertEquals(operator, plan.evidence().get(OWNER).operatorScore().orElseThrow());
            assertEquals(score, rank.rankingScore().orElseThrow(), 1e-12);
            assertTrue(rank.provisionalComponents().isEmpty());
            assertEquals(1, plan.requests().size());
        }
    }

    @ParameterizedTest
    @CsvSource({"condition,54.5,63.5", "fairness,43.5,63.5", "effectiveFacilities,63.5,70.16666666666667"})
    void physicalAndFairnessInputsChooseTheActualBetterHome(String dimension, double firstScore, double secondScore) {
        Home first = switch (dimension) {
            case "condition" -> home(1, OWNER, 60, 0, 0, 1000, List.of(0));
            case "fairness" -> home(1, OWNER, 80, 0, 0, 1500, List.of(0));
            case "effectiveFacilities" -> home(1, OWNER, 80, 3, 0, 1000, List.of(0));
            default -> throw new AssertionError(dimension);
        };
        Home second = home(2, OWNER, 80, dimension.equals("effectiveFacilities") ? 1 : 0,
                dimension.equals("effectiveFacilities") ? 1 : 0, 1000, List.of(0));
        var plan = prepare(List.of(first, second), Map.of(OWNER, empty(OWNER)));
        assertEquals(firstScore, plan.ranks().get(id(1)).rankingScore().orElseThrow(), 1e-12);
        assertEquals(secondScore, plan.ranks().get(id(2)).rankingScore().orElseThrow(), 1e-12);
        assertEquals(id(2), plan.matching().assignments().getFirst().propertyId());
        assertEquals(id(2), plan.requests().getFirst().propertyId());
    }

    @Test
    void serviceRankSelectsTheBetterOwnerDespitePropertyIdTieBreaker() {
        var plan = prepare(List.of(home(1), home(2, OTHER_OWNER, 80, 0, 0, 1000, List.of(0))),
                Map.of(OWNER, assessment(OWNER, "OVERDUE", null, null), OTHER_OWNER, empty(OTHER_OWNER)));
        assertEquals(62.0, plan.ranks().get(id(1)).rankingScore().orElseThrow(), 1e-12);
        assertEquals(63.5, plan.ranks().get(id(2)).rankingScore().orElseThrow(), 1e-12);
        assertEquals(id(2), plan.requests().getFirst().propertyId());
    }

    @ParameterizedTest
    @CsvSource({"1,4,4,0", "4,1,4,0", "4,4,1,0", "4,4,2,1"})
    void recordedAdmissionsConsumeServerMarketAndPropertyAllowances(int server, int market, int property, int expected) {
        var policy = new HousingMoveRequest.Policy(server, market, property, 7);
        var prior = new HousingMoveRequest(id(900), id(99), id(1), OWNER, WORLD, "town", "flat", 6, 6, 8, -1, 0, DAY);
        var receipt = new HousingMoveRepository.Receipt(prior, policy, 0, DAY.plusDays(7), CAPTURED.minusSeconds(60));
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, policy,
                List.of(home(1, OWNER, 80, 0, 0, 1000, List.of(1, 2, 3))),
                List.of(participant(10, Status.READY), participant(11, Status.READY)), List.of(receipt));
        var plan = HousingDecisionPlan.prepare(snapshot, Map.of(OWNER, empty(OWNER)), PROVISIONAL);
        assertEquals(expected, plan.requests().size());
        assertEquals(expected, plan.matching().assignments().size());
        assertEquals(2 - expected, plan.matching().unmatched().size());
        assertTrue(plan.matching().unmatched().stream().allMatch(u -> u.reason() == FiniteHousingPlanner.Reason.DAILY_LIMIT));
        if (expected == 1) {
            assertEquals(id(10), plan.requests().getFirst().residentId());
            assertEquals(1, plan.requests().getFirst().slot());
        }
        assertEquals(List.of(receipt), snapshot.admissions());
        assertEquals(List.of(1, 2, 3), snapshot.homes().getFirst().freeSlots());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"DISABLED", "OCCUPIED", "PREMIUM_HELD"})
    void excludedParticipantsArePreservedButNeverAssigned(Status status) {
        var excluded = participant(10, status);
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(home(1)), List.of(excluded), List.of());
        var plan = HousingDecisionPlan.prepare(snapshot, Map.of(OWNER, empty(OWNER)), PROVISIONAL);
        assertTrue(plan.requests().isEmpty());
        assertTrue(plan.matching().assignments().isEmpty());
        assertTrue(plan.matching().unmatched().isEmpty());
        assertEquals(List.of(excluded), plan.snapshot().participants());
        assertEquals(1, plan.ranks().size());
    }

    @Test
    void evenFullyRatedHomesDoNotGrantPremiumAdmissionToAReadyPremiumSeeker() {
        var premium = new HousingRegistry.Member(id(10), new HousingPolicy(WORLD, "town", "flat",
                HousingPolicy.Tier.PREMIUM, 2000, 0), DAY.minusDays(1), 8, true);
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, POLICY,
                List.of(home(1, OWNER, 100, 3, 3, 1000, List.of(0))),
                List.of(new Participant(premium, -1, DAY, Status.READY)), List.of());
        var plan = HousingDecisionPlan.prepare(snapshot,
                Map.of(OWNER, assessment(OWNER, "ON_TIME", "ON_TIME", "RETAINED")), PROVISIONAL);
        assertEquals(96.25, plan.ranks().get(id(1)).rankingScore().orElseThrow(), 1e-12);
        assertEquals(RankStatus.RATED, plan.ranks().get(id(1)).status());
        assertTrue(plan.requests().isEmpty());
        assertEquals(List.of(new FiniteHousingPlanner.Unmatched(id(10), FiniteHousingPlanner.Reason.NO_COMPATIBLE_HOME)),
                plan.matching().unmatched());
    }

    @Test
    void requestIdsAndAssignmentsAreStableAcrossAllInputOrderings() {
        var first = home(1);
        var second = home(2, OTHER_OWNER, 80, 0, 0, 1000, List.of(0));
        var evidence = new java.util.LinkedHashMap<UUID, ServiceAssessment>();
        evidence.put(OWNER, empty(OWNER));
        evidence.put(OTHER_OWNER, empty(OTHER_OWNER));
        var forward = HousingDecisionPlan.prepare(new HousingMarketSnapshot(DAY, CAPTURED, POLICY,
                List.of(first, second), List.of(participant(10, Status.READY), participant(11, Status.READY)), List.of()),
                evidence, PROVISIONAL);
        var reverse = HousingDecisionPlan.prepare(new HousingMarketSnapshot(DAY, CAPTURED, POLICY,
                List.of(second, first), List.of(participant(11, Status.READY), participant(10, Status.READY)), List.of()),
                evidence.reversed(), PROVISIONAL);
        assertEquals(2, forward.requests().size());
        assertEquals(forward.requests(), reverse.requests());
        assertEquals(forward.matching(), reverse.matching());
        assertEquals(id(10), forward.requests().getFirst().residentId());
        assertEquals(id(1), forward.requests().getFirst().propertyId());
    }

    @Test
    void addingABetterHomeChangesTheQuoteButNotTheResidentDayRequestId() {
        var original = prepare(List.of(home(1)), Map.of(OWNER, empty(OWNER))).requests().getFirst();
        var expanded = prepare(List.of(home(1), home(2, OWNER, 100, 3, 3, 1000, List.of(0))),
                Map.of(OWNER, empty(OWNER))).requests().getFirst();
        assertEquals(id(1), original.propertyId());
        assertEquals(id(2), expanded.propertyId());
        assertEquals(original.requestId(), expanded.requestId());
        assertNotEquals(original.fingerprint(POLICY), expanded.fingerprint(POLICY));
    }

    @Test
    void differentDayAndResidentHaveDifferentRequestIds() {
        UUID original = HousingDecisionPlan.requestId(DAY, id(10));
        UUID tomorrow = HousingDecisionPlan.requestId(DAY.plusDays(1), id(10));
        UUID other = HousingDecisionPlan.requestId(DAY, id(11));
        assertEquals(3, Set.of(original, tomorrow, other).size());
        assertEquals(original, HousingDecisionPlan.requestId(LocalDate.of(2026, 9, 7), new UUID(0, 10)));
    }

    @Test
    void requestsRemainBoundToTheOriginalSnapshotFacts() {
        var snapshot = snapshot(List.of(home(1, OWNER, 80, 0, 0, 1000, List.of(2))));
        var plan = HousingDecisionPlan.prepare(snapshot, Map.of(OWNER, empty(OWNER)), PROVISIONAL);
        var request = plan.requests().getFirst();
        assertSame(snapshot, plan.snapshot());
        assertEquals(id(10), request.residentId());
        assertEquals(id(1), request.propertyId());
        assertEquals(OWNER, request.ownerId());
        assertEquals(WORLD, request.worldId());
        assertEquals("town", request.marketId());
        assertEquals("flat", request.classKey());
        assertEquals(6, request.ownershipEpoch());
        assertEquals(7, request.propertyRevision());
        assertEquals(8, request.membershipRevision());
        assertEquals(-1, request.tenancyRevision());
        assertEquals(2, request.slot());
        assertEquals(DAY, request.day());
        var replacement = new Home(id(1), OTHER_OWNER, WORLD, "replacement", "town", "flat",
                9, 10, 4, 1000, 1200, 90, 3, 3, List.of(3));
        var later = prepare(List.of(replacement), Map.of(OTHER_OWNER, empty(OTHER_OWNER))).requests().getFirst();
        assertEquals(request.requestId(), later.requestId());
        assertNotEquals(request.fingerprint(POLICY), later.fingerprint(POLICY));
        assertEquals(OWNER, plan.requests().getFirst().ownerId());
        assertEquals(7, plan.requests().getFirst().propertyRevision());
        assertEquals(2, plan.requests().getFirst().slot());
    }

    @Test
    void inputMutationAndReturnedCollectionsCannotChangeThePlan() {
        var homes = new ArrayList<>(List.of(home(1)));
        var participants = new ArrayList<>(List.of(participant(10, Status.READY)));
        var evidence = new HashMap<>(Map.of(OWNER, empty(OWNER)));
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, POLICY, homes, participants, List.of());
        var plan = HousingDecisionPlan.prepare(snapshot, evidence, PROVISIONAL);
        var request = plan.requests().getFirst();
        homes.clear();
        participants.clear();
        evidence.clear();
        assertEquals(1, plan.snapshot().homes().size());
        assertEquals(1, plan.snapshot().participants().size());
        assertEquals(1, plan.evidence().size());
        assertEquals(List.of(request), plan.requests());
        assertThrows(UnsupportedOperationException.class, () -> plan.evidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.ranks().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.ranks().get(id(1)).provisionalComponents().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.requests().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.matching().assignments().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.matching().unmatched().add(
                new FiniteHousingPlanner.Unmatched(id(99), FiniteHousingPlanner.Reason.NO_VACANCY)));
        assertThrows(UnsupportedOperationException.class, () -> plan.snapshot().homes().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.snapshot().participants().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.snapshot().homes().getFirst().freeSlots().clear());
    }

    @Test
    void emptyMarketRequiresNoInventedOwnerEvidence() {
        var plan = prepare(List.of(), Map.of());
        assertTrue(plan.evidence().isEmpty());
        assertTrue(plan.ranks().isEmpty());
        assertTrue(plan.requests().isEmpty());
        assertEquals(List.of(new FiniteHousingPlanner.Unmatched(id(10), FiniteHousingPlanner.Reason.NO_COMPATIBLE_HOME)),
                plan.matching().unmatched());
    }

    private static HousingDecisionPlan prepare(List<Home> homes, Map<UUID, ServiceAssessment> evidence) {
        return HousingDecisionPlan.prepare(snapshot(homes), evidence, PROVISIONAL);
    }

    private static HousingMarketSnapshot snapshot(List<Home> homes) {
        return new HousingMarketSnapshot(DAY, CAPTURED, POLICY, homes, List.of(participant(10, Status.READY)), List.of());
    }

    private static Home home(long number) {
        return home(number, OWNER, 80, 0, 0, 1000, List.of(0));
    }

    private static Home home(long number, UUID owner, int condition, int installed, int effective, long rent, List<Integer> slots) {
        return new Home(id(number), owner, WORLD, "region-" + number, "town", "flat",
                6, 7, 4, 1000, rent, condition, installed, effective, slots);
    }

    private static Participant participant(long number, Status status) {
        var policy = new HousingPolicy(WORLD, "town", "flat",
                status == Status.PREMIUM_HELD ? HousingPolicy.Tier.PREMIUM : HousingPolicy.Tier.STANDARD, 2000, 0);
        var member = new HousingRegistry.Member(id(number), policy, DAY.minusDays(1), 8, status != Status.DISABLED);
        return new Participant(member, status == Status.OCCUPIED ? 4 : -1, DAY, status);
    }

    private static ServiceAssessment empty(UUID owner) {
        return empty(owner, CAPTURED, FROM, 7);
    }

    private static ServiceAssessment empty(UUID owner, Instant captured, Instant from, int horizon) {
        return new ServiceAssessment(new UtilityServiceAssessment(owner, captured, from, List.of()),
                new IncidentServiceAssessment(owner, captured, from, List.of()),
                new RetentionAssessment(owner, captured, from, horizon, List.of()));
    }

    private static ServiceAssessment assessment(UUID owner, String utility, String incident, String retention) {
        return new ServiceAssessment(new UtilityServiceAssessment(owner, CAPTURED, FROM,
                utility == null ? List.of() : List.of(utility(owner, UtilityServiceAssessment.Outcome.valueOf(utility)))),
                new IncidentServiceAssessment(owner, CAPTURED, FROM,
                        incident == null ? List.of() : List.of(incident(IncidentServiceAssessment.Outcome.valueOf(incident)))),
                new RetentionAssessment(owner, CAPTURED, FROM, 7,
                        retention == null ? List.of() : List.of(episode(owner, RetentionAssessment.Outcome.valueOf(retention), false))));
    }

    private static UtilityServiceAssessment.Case utility(UUID owner, UtilityServiceAssessment.Outcome outcome) {
        var day = DAY.minusDays(2);
        var bill = new UtilityBill(id(500), owner, 6, 7, day, 4, 2, 3,
                day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60), "UTC", 0);
        Instant paid = switch (outcome) {
            case ON_TIME -> bill.dueAt();
            case LATE -> bill.dueAt().plusMillis(1);
            default -> null;
        };
        return new UtilityServiceAssessment.Case(bill, paid == null ? null : 1L, paid, outcome);
    }

    private static IncidentServiceAssessment.Case incident(IncidentServiceAssessment.Outcome outcome) {
        Instant due = CAPTURED.minusMillis(1);
        Instant resolved = switch (outcome) {
            case ON_TIME, UNVERIFIED_RESOLUTION -> due;
            case LATE -> CAPTURED;
            default -> null;
        };
        Long repair = outcome == IncidentServiceAssessment.Outcome.ON_TIME || outcome == IncidentServiceAssessment.Outcome.LATE ? 1L : null;
        return new IncidentServiceAssessment.Case(1, id(500), 6, due.minusSeconds(60), due, resolved, repair, outcome);
    }

    private static RetentionAssessment.Case episode(UUID owner, RetentionAssessment.Outcome outcome, boolean immature) {
        // Exact horizon equality is mature; one millisecond later is still cold start.
        Instant admitted = CAPTURED.minus(Duration.ofDays(7)).plusMillis(immature ? 1 : 0);
        UUID resident = id(immature ? 611 : 601);
        var request = new HousingMoveRequest(id(immature ? 610 : 600), resident, id(500), owner, WORLD, "town", "flat",
                6, 7, 8, 4, 1, admitted.atOffset(ZoneOffset.UTC).toLocalDate());
        var receipt = new HousingMoveRepository.Receipt(request, POLICY, 5, request.day().plusDays(7), admitted);
        var departure = outcome == RetentionAssessment.Outcome.DEPARTED
                ? new TenancyDeparture(resident, id(500), owner, 6, 5, 1, CAPTURED.minusMillis(1),
                        TenancyDeparture.Cause.DEPARTURE, null) : null;
        var occupancy = outcome == RetentionAssessment.Outcome.RETAINED ? new RetentionAssessment.Occupancy(id(500), 1, 5) : null;
        return new RetentionAssessment.Case(receipt, outcome, departure,
                outcome == RetentionAssessment.Outcome.HANDOVER_REVIEW ? CAPTURED : null, occupancy);
    }

    private static UUID id(long number) { return new UUID(0, number); }
}
