package com.prosperitysmp.npc.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.prosperitysmp.npc.market.FiniteHousingPlanner.*;
import static com.prosperitysmp.npc.market.FiniteHousingPlanner.Reason.*;
import static com.prosperitysmp.npc.market.HousingPolicy.Tier.*;
import static org.junit.jupiter.api.Assertions.*;

class FiniteHousingPlannerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final UUID WORLD = id(1000);
    private static final Limits OPEN = new Limits(2048, 2048, 4);
    private static final Limits ZERO = new Limits(0, 0, 0);

    @Test
    void emptyPopulationAndEmptySupplyNeverFabricateAssignments() {
        assertEquals(new Plan(List.of(), List.of()), plan(DAY, List.of(), List.of(), OPEN));
        assertEquals(new Plan(List.of(), List.of()), plan(DAY, List.of(), List.of(home(10, 1, 100, 0)), OPEN));
        assertReason(plan(DAY, List.of(seeker(1, 100)), List.of(), OPEN), 1, NO_COMPATIBLE_HOME);
    }

    @Test
    void preservesNeverHousedAndReturningRevisionTokensAndOnlySuppliedSlots() {
        Seeker first = new Seeker(id(1), policy(100), 0, -1, DAY, DAY);
        Seeker returning = new Seeker(id(2), policy(100), Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, DAY, DAY);
        Plan result = plan(DAY, List.of(returning, first), List.of(home(10, 100, 60, 3, 1)), OPEN);
        assertEquals(List.of(new Assignment(id(1), id(10), 1, 0, -1),
                new Assignment(id(2), id(10), 3, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1)), result.assignments());
        assertTrue(result.unmatched().isEmpty());
    }

    @Test
    void futureAvailabilityPrecedesCooldownAndBothDatesAreInclusive() {
        List<Seeker> seekers = List.of(new Seeker(id(1), policy(100), 0, -1, DAY.plusDays(1), DAY.plusDays(2)),
                new Seeker(id(2), policy(100), 0, -1, DAY.minusDays(1), DAY.plusDays(1)),
                new Seeker(id(3), policy(100), 0, -1, DAY, DAY));
        Plan result = plan(DAY, seekers, List.of(home(10, 100, 60, 0)), OPEN);
        assertReason(result, 1, NOT_YET_AVAILABLE);
        assertReason(result, 2, COOLDOWN);
        assertEquals(id(3), result.assignments().getFirst().residentId());
    }

    @Test
    void worldMarketAndClassMustAllMatchExactly() {
        List<Home> wrong = List.of(new Home(id(10), id(2000), "town", "flat", 100, 1, 100, true, List.of(0)),
                new Home(id(11), WORLD, "town2", "flat", 100, 1, 100, true, List.of(0)),
                new Home(id(12), WORLD, "town", "house", 100, 1, 100, true, List.of(0)));
        for (Home home : wrong) {
            assertReason(plan(DAY, List.of(seeker(1, 100)), List.of(home), OPEN), 1, NO_COMPATIBLE_HOME);
        }
    }

    @Test
    void budgetAndConditionAreHardInclusiveConstraints() {
        Seeker seeker = seeker(1, new HousingPolicy(WORLD, "town", "flat", STANDARD, 100, 70), DAY);
        Home expensive = home(10, 101, 100, 0);
        Home damaged = new Home(id(11), WORLD, "town", "flat", 69, 99, 100, false, List.of(0));
        Home exact = home(12, 100, 0, 0);
        assertReason(plan(DAY, List.of(seeker), List.of(expensive, damaged), OPEN), 1, NO_COMPATIBLE_HOME);
        assertEquals(id(12), plan(DAY, List.of(seeker), List.of(expensive, damaged, exact), OPEN)
                .assignments().getFirst().propertyId());
    }

    @Test
    void premiumRequiresActualEligibilityAndStandardMayUseEither() {
        Seeker premium = seeker(1, new HousingPolicy(WORLD, "town", "flat", PREMIUM, 100, 0), DAY);
        Home ordinary = home(10, 100, 100, 0);
        Home eligible = new Home(id(11), WORLD, "town", "flat", 70, 100, 1, true, List.of(0));
        assertReason(plan(DAY, List.of(premium), List.of(ordinary), OPEN), 1, NO_COMPATIBLE_HOME);
        Plan result = plan(DAY, List.of(seeker(2, 100), premium), List.of(ordinary, eligible), OPEN);
        assertEquals(id(11), result.assignments().getFirst().propertyId());
        assertEquals(id(10), result.assignments().get(1).propertyId());
        assertEquals(1, plan(DAY, List.of(seeker(2, 100)), List.of(eligible), OPEN).assignments().size());
    }

    @Test
    void homeRankingUsesScoreThenPriceThenLexicalUuidThenLowestSlot() {
        UUID lexicalFirst = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID signedFirst = UUID.fromString("80000000-0000-0000-0000-000000000000");
        Home best = new Home(lexicalFirst, WORLD, "town", "flat", 70, 99, 80, false, List.of(3, 1));
        Home signed = new Home(signedFirst, WORLD, "town", "flat", 70, 99, 80, false, List.of(0));
        List<Home> homes = List.of(home(20, 1, 79, 0), home(21, 100, 80, 0), signed, best);
        assertEquals(new Assignment(id(1), lexicalFirst, 1, 3, 7),
                plan(DAY, List.of(seeker(1, 100)), homes, OPEN).assignments().getFirst());
    }

    @Test
    void scarceBudgetWinsCompetitionBeforeAnOlderFlexibleSeeker() {
        Seeker flexible = seeker(1, policy(200), DAY.minusDays(30));
        Seeker constrained = seeker(2, 100);
        Plan result = plan(DAY, List.of(flexible, constrained),
                List.of(home(10, 100, 100, 0), home(11, 200, 50, 0)), OPEN);
        assertEquals(List.of(new Assignment(id(2), id(10), 0, 3, 7),
                new Assignment(id(1), id(11), 0, 3, 7)), result.assignments());
    }

    @Test
    void scarcityCountsFreeSlotsRatherThanProperties() {
        Seeker fourSlots = seeker(1, policy(100), DAY.minusDays(20));
        Seeker twoSlots = seeker(2, new HousingPolicy(WORLD, "town", "house", STANDARD, 100, 0), DAY);
        List<Home> homes = List.of(home(10, 100, 100, 0, 1, 2, 3),
                new Home(id(11), WORLD, "town", "house", 70, 100, 50, false, List.of(0)),
                new Home(id(12), WORLD, "town", "house", 70, 100, 50, false, List.of(0)));
        Plan result = plan(DAY, List.of(fourSlots, twoSlots), homes, new Limits(1, 10, 4));
        assertEquals(id(2), result.assignments().getFirst().residentId());
        assertReason(result, 1, DAILY_LIMIT);
    }

    @Test
    void equalScarcityUsesOldestAvailabilityThenUuidTextNotSignedUuidOrder() {
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        Seeker signed = new Seeker(high, policy(100), 3, 7, DAY, DAY);
        Seeker older = seeker(3, policy(100), DAY.minusDays(1));
        Plan result = plan(DAY, List.of(signed, seeker(2, 100), older, seeker(1, 100)),
                List.of(home(10, 100, 50, 3, 2, 1, 0)), OPEN);
        assertEquals(List.of(id(3), id(1), id(2), high),
                result.assignments().stream().map(Assignment::residentId).toList());
    }

    @Test
    void serverCapLeavesRealVacanciesUnassigned() {
        Plan result = plan(DAY, List.of(seeker(1, 100), seeker(2, 100)),
                List.of(home(10, 100, 50, 0, 1)), new Limits(1, 10, 4));
        assertEquals(1, result.assignments().size());
        assertReason(result, 2, DAILY_LIMIT);
    }

    @Test
    void marketCapIsSharedAcrossClassesButIsolatedAcrossWorldsAndMarketNames() {
        HousingPolicy house = new HousingPolicy(WORLD, "town", "house", STANDARD, 100, 0);
        HousingPolicy otherWorld = new HousingPolicy(id(2000), "town", "flat", STANDARD, 100, 0);
        HousingPolicy otherMarket = new HousingPolicy(WORLD, "village", "flat", STANDARD, 100, 0);
        List<Seeker> seekers = List.of(seeker(1, 100), seeker(2, house, DAY),
                seeker(3, otherWorld, DAY), seeker(4, otherMarket, DAY));
        List<Home> homes = List.of(home(10, 100, 60, 0),
                new Home(id(11), WORLD, "town", "house", 70, 100, 60, false, List.of(0)),
                new Home(id(12), id(2000), "town", "flat", 70, 100, 60, false, List.of(0)),
                new Home(id(13), WORLD, "village", "flat", 70, 100, 60, false, List.of(0)));
        Plan result = plan(DAY, seekers, homes, new Limits(10, 1, 4));
        assertEquals(List.of(id(1), id(3), id(4)), result.assignments().stream().map(Assignment::residentId).toList());
        assertReason(result, 2, DAILY_LIMIT);
        assertInvariants(result, seekers, homes, new Limits(10, 1, 4));
    }

    @Test
    void propertyCapAllowsFallbackToLowerRankedHome() {
        Plan result = plan(DAY, List.of(seeker(1, 100), seeker(2, 100), seeker(3, 100)),
                List.of(home(10, 100, 100, 0, 1), home(11, 100, 50, 0, 1)), new Limits(10, 10, 1));
        assertEquals(List.of(id(10), id(11)), result.assignments().stream().map(Assignment::propertyId).toList());
        assertReason(result, 3, DAILY_LIMIT);
    }

    @Test
    void exhaustedSlotsAreNoVacancyEvenWhenCapsAreAlsoReached() {
        List<Seeker> seekers = List.of(seeker(1, 100), seeker(2, 100));
        assertReason(plan(DAY, seekers, List.of(home(10, 100, 50, 0)), new Limits(1, 1, 1)), 2, NO_VACANCY);
        assertReason(plan(DAY, seekers, List.of(home(10, 100, 50, 0), home(11, 100, 20, 0)),
                new Limits(1, 1, 1)), 2, DAILY_LIMIT);
    }

    @Test
    void metadataMatchWithNoInitialSlotsDiffersFromIncompatibility() {
        Plan empty = plan(DAY, List.of(seeker(1, 100)), List.of(home(10, 100, 100)), OPEN);
        assertTrue(empty.assignments().isEmpty());
        assertReason(empty, 1, NO_VACANCY);
        assertReason(plan(DAY, List.of(seeker(1, 100)), List.of(home(10, 101, 100)), OPEN),
                1, NO_COMPATIBLE_HOME);
    }

    @Test
    void eachZeroCapPreventsPositiveOutputWithoutOverridingEligibilityReasons() {
        for (Limits limits : List.of(ZERO, new Limits(0, 10, 4), new Limits(10, 0, 4), new Limits(10, 10, 0))) {
            Plan result = plan(DAY, List.of(seeker(1, 100), seeker(2, 99),
                    new Seeker(id(3), policy(100), 0, -1, DAY.plusDays(1), DAY),
                    new Seeker(id(4), policy(100), 0, -1, DAY, DAY.plusDays(1))),
                    List.of(home(10, 100, 100, 0)), limits);
            assertTrue(result.assignments().isEmpty());
            assertReason(result, 1, DAILY_LIMIT);
            assertReason(result, 2, NO_COMPATIBLE_HOME);
            assertReason(result, 3, NOT_YET_AVAILABLE);
            assertReason(result, 4, COOLDOWN);
        }
    }

    @Test
    void allSmallInputPermutationsIncludingSlotOrderProduceIdenticalPlans() {
        List<Seeker> seekers = List.of(seeker(1, 100), seeker(2, 200), seeker(3, 50),
                new Seeker(id(4), policy(200), 0, -1, DAY.plusDays(1), DAY));
        List<Home> homes = List.of(home(10, 100, 80, 3, 0), home(11, 200, 90, 2), home(12, 300, 100, 1));
        Limits limits = new Limits(2, 2, 1);
        Plan expected = plan(DAY, seekers, homes, limits);
        for (List<Seeker> seekerOrder : permutations(seekers)) {
            for (List<Home> homeOrder : permutations(homes)) {
                assertEquals(expected, plan(DAY, seekerOrder, homeOrder, limits));
                List<Home> reversedSlots = homeOrder.stream().map(home -> {
                    List<Integer> slots = new ArrayList<>(home.freeSlots());
                    Collections.reverse(slots);
                    return new Home(home.propertyId(), home.worldId(), home.marketId(), home.classKey(),
                            home.condition(), home.dailyRentCents(), home.score(), home.premiumEligible(), slots);
                }).toList();
                assertEquals(expected, plan(DAY, seekerOrder, reversedSlots, limits));
                assertInvariants(expected, seekerOrder, homeOrder, limits);
            }
        }
    }

    @Test
    void maximumDenseSnapshotConservesResidentsSlotsAndAllCaps() {
        List<Seeker> seekers = new ArrayList<>();
        List<Home> homes = new ArrayList<>();
        for (int i = 1; i <= 512; i++) {
            seekers.add(seeker(i, Integer.MAX_VALUE));
            homes.add(home(10000 + i, i, i % 101, 3, 1, 0, 2));
        }
        Plan result = plan(DAY, seekers, homes, OPEN);
        assertEquals(512, result.assignments().size());
        assertTrue(result.unmatched().isEmpty());
        assertInvariants(result, seekers, homes, OPEN);
        Limits limited = new Limits(300, 257, 1);
        Plan capped = plan(DAY, seekers, homes, limited);
        assertEquals(257, capped.assignments().size());
        assertEquals(255, capped.unmatched().size());
        assertTrue(capped.unmatched().stream().allMatch(row -> row.reason() == DAILY_LIMIT));
        assertInvariants(capped, seekers, homes, limited);
    }

    @Test
    void oversizeAndDuplicateSnapshotsAreRejectedEvenWithZeroLimits() {
        List<Seeker> seekers = new ArrayList<>();
        List<Home> homes = new ArrayList<>();
        for (int i = 1; i <= 513; i++) {
            seekers.add(seeker(i, 100));
            homes.add(home(10000 + i, 100, 50, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> plan(DAY, seekers, List.of(), ZERO));
        assertThrows(IllegalArgumentException.class, () -> plan(DAY, List.of(), homes, ZERO));
        assertThrows(IllegalArgumentException.class, () -> plan(DAY,
                List.of(seeker(1, 100), seeker(1, 200)), List.of(), ZERO));
        assertThrows(IllegalArgumentException.class, () -> plan(DAY, List.of(),
                List.of(home(10, 100, 50, 0), home(10, 200, 100, 1)), ZERO));
    }

    @Test
    void nullInputsAndRecordFieldsAreRejectedEvenWithoutWork() {
        assertThrows(NullPointerException.class, () -> plan(null, List.of(), List.of(), ZERO));
        assertThrows(NullPointerException.class, () -> plan(DAY, null, List.of(), ZERO));
        assertThrows(NullPointerException.class, () -> plan(DAY, List.of(), null, ZERO));
        assertThrows(NullPointerException.class, () -> plan(DAY, List.of(), List.of(), null));
        assertThrows(NullPointerException.class, () -> plan(DAY, Arrays.asList((Seeker) null), List.of(), ZERO));
        assertThrows(NullPointerException.class, () -> plan(DAY, List.of(), Arrays.asList((Home) null), ZERO));
        assertThrows(NullPointerException.class, () -> new Seeker(null, policy(100), 0, -1, DAY, DAY));
        assertThrows(NullPointerException.class, () -> new Seeker(id(1), null, 0, -1, DAY, DAY));
        assertThrows(NullPointerException.class, () -> new Seeker(id(1), policy(100), 0, -1, null, DAY));
        assertThrows(NullPointerException.class, () -> new Seeker(id(1), policy(100), 0, -1, DAY, null));
        assertThrows(NullPointerException.class, () -> new Home(null, WORLD, "town", "flat", 70, 100, 50, false, List.of()));
        assertThrows(NullPointerException.class, () -> new Home(id(10), null, "town", "flat", 70, 100, 50, false, List.of()));
        assertThrows(NullPointerException.class, () -> new Home(id(10), WORLD, null, "flat", 70, 100, 50, false, List.of()));
        assertThrows(NullPointerException.class, () -> new Home(id(10), WORLD, "town", null, 70, 100, 50, false, List.of()));
        assertThrows(NullPointerException.class, () -> home(10, 100, 50, (Integer[]) null));
    }

    @Test
    void homeRejectsCorruptNumericAndKeyFieldsIncludingNanAndInfinity() {
        for (double score : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 100.01}) {
            assertThrows(IllegalArgumentException.class, () -> home(10, 100, score, 0));
        }
        for (long price : new long[]{Long.MIN_VALUE, -1, 0, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> home(10, price, 50, 0));
        }
        for (int condition : new int[]{-1, 101}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Home(id(10), WORLD, "town", "flat", condition, 100, 50, false, List.of(0)));
        }
        for (String key : new String[]{"", "Town", "two words", "1town", "a.b", "a\n", "\u00e9", "a".repeat(33)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Home(id(10), WORLD, key, "flat", 70, 100, 50, false, List.of(0)));
            assertThrows(IllegalArgumentException.class,
                    () -> new Home(id(10), WORLD, "town", key, 70, 100, 50, false, List.of(0)));
        }
        assertDoesNotThrow(() -> new Home(id(10), WORLD, "a".repeat(32), "a_0-b", 0, 1, -0.0, false, List.of()));
        assertDoesNotThrow(() -> new Home(id(10), WORLD, "town", "flat", 100, Integer.MAX_VALUE, 100, true, List.of()));
        assertEquals(home(10, 1, 0.0), home(10, 1, -0.0));
    }

    @Test
    void freeSlotsMustBeUniqueBoundedAndNonNullAndAreDefensivelyCopied() {
        for (List<Integer> slots : List.of(List.of(-1), List.of(4), List.of(0, 0), List.of(0, 1, 2, 3, 0))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Home(id(10), WORLD, "town", "flat", 70, 100, 50, false, slots));
        }
        assertThrows(NullPointerException.class,
                () -> new Home(id(10), WORLD, "town", "flat", 70, 100, 50, false, Arrays.asList(0, null)));
        assertThrows(NullPointerException.class,
                () -> new Home(id(10), WORLD, "town", "flat", 70, 100, 50, false, null));
        List<Integer> slots = new ArrayList<>(List.of(3, 0));
        Home home = new Home(id(10), WORLD, "town", "flat", 70, 100, 50, false, slots);
        slots.clear();
        assertEquals(List.of(3, 0), home.freeSlots());
        assertThrows(UnsupportedOperationException.class, () -> home.freeSlots().add(1));
    }

    @Test
    void revisionsRejectOverflowAndInvalidNegativeValues() {
        for (long revision : new long[]{Long.MIN_VALUE, -1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Seeker(id(1), policy(100), revision, -1, DAY, DAY));
            assertThrows(IllegalArgumentException.class, () -> new Assignment(id(1), id(10), 0, revision, -1));
        }
        for (long revision : new long[]{Long.MIN_VALUE, -2, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Seeker(id(1), policy(100), 0, revision, DAY, DAY));
            assertThrows(IllegalArgumentException.class, () -> new Assignment(id(1), id(10), 0, 0, revision));
        }
        assertDoesNotThrow(() -> new Seeker(id(1), policy(100), 0, 0, DAY, DAY));
        assertThrows(IllegalArgumentException.class, () -> new Assignment(id(1), id(10), -1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Assignment(id(1), id(10), 4, 0, -1));
    }

    @Test
    void limitsRejectNegativeAndOutOfRangeValues() {
        for (int invalid : new int[]{-1, 2049, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Limits(invalid, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Limits(0, invalid, 0));
        }
        for (int invalid : new int[]{-1, 5, 2048}) {
            assertThrows(IllegalArgumentException.class, () -> new Limits(0, 0, invalid));
        }
        assertDoesNotThrow(() -> new Limits(2048, 2048, 4));
        assertDoesNotThrow(() -> new Limits(0, 0, 0));
    }

    @Test
    void repeatedPlansDoNotMutateInputsAndOutputListsAreImmutableCopies() {
        List<Seeker> seekers = new ArrayList<>(List.of(seeker(2, 100), seeker(1, 100)));
        List<Home> homes = new ArrayList<>(List.of(home(10, 100, 50, 3)));
        List<Seeker> beforeSeekers = List.copyOf(seekers);
        List<Home> beforeHomes = List.copyOf(homes);
        Plan result = plan(DAY, seekers, homes, OPEN);
        assertEquals(result, plan(DAY, seekers, homes, OPEN));
        assertEquals(beforeSeekers, seekers);
        assertEquals(beforeHomes, homes);
        assertThrows(UnsupportedOperationException.class, () -> result.assignments().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.unmatched().clear());
        List<Assignment> assignments = new ArrayList<>(result.assignments());
        List<Unmatched> unmatched = new ArrayList<>(result.unmatched());
        Plan copy = new Plan(assignments, unmatched);
        assignments.clear();
        unmatched.clear();
        assertEquals(result, copy);
    }

    private static UUID id(long value) { return new UUID(0, value); }

    private static HousingPolicy policy(long budget) {
        return new HousingPolicy(WORLD, "town", "flat", STANDARD, budget, 0);
    }

    private static Seeker seeker(long id, long budget) { return seeker(id, policy(budget), DAY); }

    private static Seeker seeker(long id, HousingPolicy policy, LocalDate since) {
        return new Seeker(id(id), policy, 3, 7, since, DAY);
    }

    private static Home home(long id, long price, double score, Integer... slots) {
        return new Home(id(id), WORLD, "town", "flat", 70, price, score, false, Arrays.asList(slots));
    }

    private static void assertReason(Plan result, long resident, Reason reason) {
        assertEquals(List.of(new Unmatched(id(resident), reason)), result.unmatched().stream()
                .filter(row -> row.residentId().equals(id(resident))).toList());
        assertTrue(result.assignments().stream().noneMatch(row -> row.residentId().equals(id(resident))));
    }

    private static <T> List<List<T>> permutations(List<T> input) {
        if (input.isEmpty()) return List.of(List.of());
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            List<T> rest = new ArrayList<>(input);
            T first = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> order = new ArrayList<>();
                order.add(first);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }

    private record Slot(UUID propertyId, int slot) { }
    private record Market(UUID worldId, String marketId) { }

    private static void assertInvariants(Plan result, List<Seeker> seekers, List<Home> homes, Limits limits) {
        Map<UUID, Seeker> people = new HashMap<>();
        seekers.forEach(seeker -> people.put(seeker.residentId(), seeker));
        Map<UUID, Home> properties = new HashMap<>();
        homes.forEach(home -> properties.put(home.propertyId(), home));
        Set<UUID> accounted = new HashSet<>();
        Set<Slot> slots = new HashSet<>();
        Map<UUID, Integer> propertyCounts = new HashMap<>();
        Map<Market, Integer> marketCounts = new HashMap<>();
        assertTrue(result.assignments().size() <= limits.server());
        for (Assignment row : result.assignments()) {
            assertTrue(accounted.add(row.residentId()));
            assertTrue(slots.add(new Slot(row.propertyId(), row.slot())));
            Seeker seeker = people.get(row.residentId());
            Home home = properties.get(row.propertyId());
            assertNotNull(seeker);
            assertNotNull(home);
            assertTrue(home.freeSlots().contains(row.slot()));
            assertFalse(DAY.isBefore(seeker.availableSince()));
            assertFalse(DAY.isBefore(seeker.cooldownUntil()));
            assertEquals(seeker.membershipRevision(), row.membershipRevision());
            assertEquals(seeker.tenancyRevision(), row.tenancyRevision());
            HousingPolicy policy = seeker.policy();
            assertEquals(policy.worldId(), home.worldId());
            assertEquals(policy.marketId(), home.marketId());
            assertEquals(policy.classKey(), home.classKey());
            assertTrue(home.dailyRentCents() <= policy.maxDailyRentCents());
            assertTrue(home.condition() >= policy.minimumCondition());
            assertTrue(policy.tier() != PREMIUM || home.premiumEligible());
            assertTrue(propertyCounts.merge(home.propertyId(), 1, Integer::sum) <= limits.perProperty());
            assertTrue(marketCounts.merge(new Market(home.worldId(), home.marketId()), 1, Integer::sum) <= limits.perMarket());
        }
        for (Unmatched row : result.unmatched()) {
            assertTrue(people.containsKey(row.residentId()));
            assertTrue(accounted.add(row.residentId()));
        }
        assertEquals(people.keySet(), accounted);
    }
}
