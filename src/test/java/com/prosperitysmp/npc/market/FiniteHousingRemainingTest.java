package com.prosperitysmp.npc.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.prosperitysmp.npc.market.FiniteHousingPlanner.*;
import static com.prosperitysmp.npc.market.FiniteHousingPlanner.Reason.*;
import static com.prosperitysmp.npc.market.HousingPolicy.Tier.*;
import static org.junit.jupiter.api.Assertions.*;

class FiniteHousingRemainingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Market TOWN = new Market(id(1000), "town");
    private static final Market OTHER_WORLD = new Market(id(2000), "town");
    private static final Market VILLAGE = new Market(TOWN.worldId(), "village");

    @Test
    void unequalMarketAllowancesAreWorldScopedAndSharedAcrossClasses() {
        List<Seeker> seekers = List.of(seeker(1, TOWN, "flat"), seeker(2, TOWN, "house"),
                seeker(3, OTHER_WORLD, "flat"), seeker(4, OTHER_WORLD, "flat"),
                seeker(5, OTHER_WORLD, "flat"), seeker(6, VILLAGE, "flat"));
        List<Home> homes = List.of(home(10, TOWN, "flat", 90, 0, 1), home(11, TOWN, "house", 90, 0, 1),
                home(12, OTHER_WORLD, "flat", 90, 0, 1, 2), home(13, VILLAGE, "flat", 90, 0));
        Remaining remaining = new Remaining(10, Map.of(TOWN, 1, OTHER_WORLD, 2, VILLAGE, 0),
                Map.of(id(10), 4, id(11), 4, id(12), 4, id(13), 4));

        assertEquals(new Plan(List.of(assignment(1, 10, 0), assignment(3, 12, 0), assignment(4, 12, 1)),
                List.of(unmatched(2, DAILY_LIMIT), unmatched(5, DAILY_LIMIT), unmatched(6, DAILY_LIMIT))),
                planWithRemaining(DAY, seekers, homes, remaining));
    }

    @Test
    void classesConsumeTheSharedMarketAllowanceOncePerAssignment() {
        List<Home> homes = List.of(home(10, TOWN, "flat", 90, 0, 1), home(11, TOWN, "house", 90, 0, 1));
        Remaining remaining = new Remaining(10, Map.of(TOWN, 2), Map.of(id(10), 4, id(11), 4));
        Plan expected = new Plan(List.of(assignment(1, 10, 0), assignment(2, 11, 0)),
                List.of(unmatched(3, DAILY_LIMIT)));
        assertEquals(expected, planWithRemaining(DAY, List.of(seeker(1, TOWN, "flat"), seeker(2, TOWN, "house"),
                seeker(3, TOWN, "flat")), homes, remaining));
    }

    @Test
    void unequalPropertyAllowancesSkipExhaustedBestAndThenFallBackAgain() {
        List<Home> homes = List.of(home(10, TOWN, "flat", 100, 3, 2),
                home(11, TOWN, "flat", 90, 3, 1), home(12, TOWN, "flat", 80, 3, 2, 0));
        Remaining remaining = new Remaining(10, Map.of(TOWN, 10), Map.of(id(10), 0, id(11), 1, id(12), 2));
        assertEquals(new Plan(List.of(assignment(1, 11, 1), assignment(2, 12, 0), assignment(3, 12, 2)),
                List.of(unmatched(4, DAILY_LIMIT))), planWithRemaining(DAY, List.of(seeker(1, TOWN, "flat"),
                seeker(2, TOWN, "flat"), seeker(3, TOWN, "flat"), seeker(4, TOWN, "flat")), homes, remaining));
    }

    @Test
    void remainingServerAllowanceIsSharedAcrossMarkets() {
        List<Home> homes = List.of(home(10, TOWN, "flat", 90, 0, 1),
                home(11, OTHER_WORLD, "flat", 90, 0, 1));
        Remaining remaining = new Remaining(1, Map.of(TOWN, 2, OTHER_WORLD, 2), Map.of(id(10), 4, id(11), 4));
        assertEquals(new Plan(List.of(assignment(1, 10, 0)), List.of(unmatched(2, DAILY_LIMIT))),
                planWithRemaining(DAY, List.of(seeker(1, TOWN, "flat"), seeker(2, OTHER_WORLD, "flat")), homes, remaining));
    }

    @Test
    void carriedSnapshotsNeverRefreshQuotaWhenHomesDisappearOrBecomeVacantAgain() {
        Home best = home(10, TOWN, "flat", 100, 0, 1, 2, 3);
        Home fallback = home(11, TOWN, "flat", 80, 0, 1, 2, 3);
        Home other = home(12, OTHER_WORLD, "flat", 90, 0, 1, 2, 3);
        List<Home> homes = List.of(best, fallback, other);
        Remaining initial = new Remaining(4, Map.of(TOWN, 3, OTHER_WORLD, 1),
                Map.of(id(10), 1, id(11), 2, id(12), 1));
        assertEquals(new Plan(List.of(assignment(2, 12, 0), assignment(1, 10, 0)), List.of()),
                planWithRemaining(DAY, List.of(seeker(1, TOWN, "flat"), seeker(2, OTHER_WORLD, "flat")), homes, initial));

        // The caller carries receipt-derived counts; vacating a slot does not refund intake.
        Remaining carried = new Remaining(2, Map.of(TOWN, 2, OTHER_WORLD, 0),
                Map.of(id(10), 0, id(11), 2, id(12), 0));
        Plan second = planWithRemaining(DAY, List.of(seeker(3, TOWN, "flat"), seeker(4, TOWN, "flat"),
                seeker(5, TOWN, "flat")), List.of(best, fallback), carried);
        assertEquals(new Plan(List.of(assignment(3, 11, 0), assignment(4, 11, 1)),
                List.of(unmatched(5, DAILY_LIMIT))), second);
        Remaining exhausted = new Remaining(0, Map.of(TOWN, 0, OTHER_WORLD, 0),
                Map.of(id(10), 0, id(11), 0, id(12), 0));
        assertEquals(new Plan(List.of(), List.of(unmatched(6, DAILY_LIMIT), unmatched(7, DAILY_LIMIT))),
                planWithRemaining(DAY, List.of(seeker(6, TOWN, "flat"), seeker(7, OTHER_WORLD, "flat")), homes, exhausted));
        assertEquals(2, carried.server());
        assertEquals(Map.of(id(10), 0, id(11), 2, id(12), 0), carried.properties());
    }

    @Test
    void scarcityStillCountsInitialCompatibleSlotsIncludingQuotaBlockedHomes() {
        HousingPolicy standard = policy(TOWN, "flat");
        HousingPolicy premium = new HousingPolicy(TOWN.worldId(), TOWN.marketId(), "flat", PREMIUM, 100, 0);
        Seeker older = new Seeker(id(1), standard, 3, 7, DAY.minusDays(10), DAY);
        Seeker constrained = new Seeker(id(2), premium, 3, 7, DAY, DAY);
        Home eligible = new Home(id(10), TOWN.worldId(), TOWN.marketId(), "flat", 70, 100, 90, true, List.of(0));
        Home exhausted = home(11, TOWN, "flat", 100, 0, 1, 2);
        Remaining remaining = new Remaining(1, Map.of(TOWN, 1), Map.of(id(10), 1, id(11), 0));
        assertEquals(new Plan(List.of(assignment(2, 10, 0)), List.of(unmatched(1, DAILY_LIMIT))),
                planWithRemaining(DAY, List.of(older, constrained), List.of(eligible, exhausted), remaining));
    }

    @Test
    void zeroAllowancesPreserveEligibilityAndVacancyReasonPrecedence() {
        List<Home> homes = List.of(home(10, TOWN, "flat", 90, 0), home(11, TOWN, "house", 90));
        List<Seeker> seekers = List.of(seeker(1, TOWN, "flat"),
                new Seeker(id(2), new HousingPolicy(TOWN.worldId(), "town", "flat", STANDARD, 99, 0), 3, 7, DAY, DAY),
                new Seeker(id(3), policy(TOWN, "flat"), 3, 7, DAY.plusDays(1), DAY.plusDays(2)),
                new Seeker(id(4), policy(TOWN, "flat"), 3, 7, DAY, DAY.plusDays(1)), seeker(5, TOWN, "house"));
        Plan expected = new Plan(List.of(), List.of(unmatched(1, DAILY_LIMIT), unmatched(2, NO_COMPATIBLE_HOME),
                unmatched(3, NOT_YET_AVAILABLE), unmatched(4, COOLDOWN), unmatched(5, NO_VACANCY)));
        for (Remaining remaining : List.of(
                new Remaining(0, Map.of(TOWN, 10), Map.of(id(10), 4, id(11), 4)),
                new Remaining(10, Map.of(TOWN, 0), Map.of(id(10), 4, id(11), 4)),
                new Remaining(10, Map.of(TOWN, 10), Map.of(id(10), 0, id(11), 0)))) {
            assertEquals(expected, planWithRemaining(DAY, seekers, homes, remaining));
        }
        assertEquals(new Plan(List.of(assignment(1, 10, 0)), List.of(unmatched(2, NO_VACANCY))),
                planWithRemaining(DAY, List.of(seeker(1, TOWN, "flat"), seeker(2, TOWN, "flat")), List.of(homes.getFirst()),
                        new Remaining(1, Map.of(TOWN, 1), Map.of(id(10), 1))));
    }

    @Test
    void everyHomeNeedsBothExplicitKeysEvenWithoutSeekersSlotsOrServerQuota() {
        for (Home home : List.of(home(10, TOWN, "flat", 90), home(10, TOWN, "flat", 90, 0))) {
            for (List<Seeker> seekers : List.of(List.<Seeker>of(), List.of(seeker(1, OTHER_WORLD, "house")))) {
                assertThrows(IllegalArgumentException.class, () -> planWithRemaining(DAY, seekers, List.of(home),
                        new Remaining(0, Map.of(), Map.of(id(10), 0))));
                assertThrows(IllegalArgumentException.class, () -> planWithRemaining(DAY, seekers, List.of(home),
                        new Remaining(0, Map.of(TOWN, 0), Map.of())));
                assertThrows(IllegalArgumentException.class, () -> planWithRemaining(DAY, seekers, List.of(home),
                        new Remaining(0, Map.of(OTHER_WORLD, 4), Map.of(id(10), 4))));
                assertThrows(IllegalArgumentException.class, () -> planWithRemaining(DAY, seekers, List.of(home),
                        new Remaining(0, Map.of(TOWN, 4), Map.of(id(11), 4))));
                assertDoesNotThrow(() -> planWithRemaining(DAY, seekers, List.of(home),
                        new Remaining(0, Map.of(TOWN, 0), Map.of(id(10), 0))));
            }
        }
    }

    @Test
    void marketUsesTheSameExactWorldAndKeyValidationAsHomes() {
        assertThrows(NullPointerException.class, () -> new Market(null, "town"));
        assertThrows(NullPointerException.class, () -> new Market(TOWN.worldId(), null));
        for (String key : new String[]{"", "Town", "two words", "1town", "a.b", "a\n", "\u00e9", "a".repeat(33)}) {
            assertThrows(IllegalArgumentException.class, () -> new Market(TOWN.worldId(), key));
        }
        for (String key : List.of("a", "a_0-b", "a".repeat(32))) {
            assertDoesNotThrow(() -> new Market(TOWN.worldId(), key));
        }
        assertEquals(TOWN, new Market(TOWN.worldId(), "town"));
        assertNotEquals(TOWN, OTHER_WORLD);
        assertNotEquals(TOWN, VILLAGE);
    }

    @Test
    void remainingCountsAreBoundedIncludingUnusedHistoricalKeys() {
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 2049, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Remaining(invalid, Map.of(), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> new Remaining(0, Map.of(TOWN, invalid), Map.of()));
        }
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 5, 2048, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Remaining(0, Map.of(), Map.of(id(10), invalid)));
        }
        assertDoesNotThrow(() -> new Remaining(2048, Map.of(TOWN, 2048), Map.of(id(10), 4)));
        assertDoesNotThrow(() -> new Remaining(0, Map.of(TOWN, 0), Map.of(id(10), 0)));
    }

    @Test
    void eachMapAllows512HistoricalKeysButRejects513Independently() {
        Map<Market, Integer> markets = new HashMap<>();
        Map<UUID, Integer> properties = new HashMap<>();
        for (int i = 0; i < 512; i++) {
            markets.put(new Market(id(i), "town"), i % 2 == 0 ? 0 : 2048);
            properties.put(id(i), i % 2 == 0 ? 0 : 4);
        }
        Remaining maximum = new Remaining(2048, markets, properties);
        assertEquals(512, maximum.markets().size());
        assertEquals(512, maximum.properties().size());
        assertEquals(new Plan(List.of(), List.of()), planWithRemaining(DAY, List.of(), List.of(), maximum));
        markets.put(new Market(id(512), "town"), 0);
        assertThrows(IllegalArgumentException.class, () -> new Remaining(0, markets, Map.of()));
        properties.put(id(512), 0);
        assertThrows(IllegalArgumentException.class, () -> new Remaining(0, Map.of(), properties));
    }

    @Test
    void nullMapsKeysCountsAndPlannerInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> new Remaining(0, null, Map.of()));
        assertThrows(NullPointerException.class, () -> new Remaining(0, Map.of(), null));
        assertThrows(NullPointerException.class, () -> new Remaining(0, Collections.singletonMap(null, 0), Map.of()));
        assertThrows(NullPointerException.class, () -> new Remaining(0, Collections.singletonMap(TOWN, null), Map.of()));
        assertThrows(NullPointerException.class, () -> new Remaining(0, Map.of(), Collections.singletonMap(null, 0)));
        assertThrows(NullPointerException.class, () -> new Remaining(0, Map.of(), Collections.singletonMap(id(10), null)));
        Remaining zero = new Remaining(0, Map.of(), Map.of());
        assertThrows(NullPointerException.class, () -> planWithRemaining(null, List.of(), List.of(), zero));
        assertThrows(NullPointerException.class, () -> planWithRemaining(DAY, null, List.of(), zero));
        assertThrows(NullPointerException.class, () -> planWithRemaining(DAY, List.of(), null, zero));
        assertThrows(NullPointerException.class, () -> planWithRemaining(DAY, List.of(), List.of(), null));
        assertThrows(NullPointerException.class, () -> planWithRemaining(DAY, Arrays.asList((Seeker) null), List.of(), zero));
        assertThrows(NullPointerException.class, () -> planWithRemaining(DAY, List.of(), Arrays.asList((Home) null), zero));
        assertThrows(NullPointerException.class, () -> plan(DAY, List.of(), List.of(), null));
    }

    @Test
    void remainingMapsAreDefensiveImmutableCopiesAndPlanningDoesNotSpendTheInput() {
        Map<Market, Integer> markets = new HashMap<>(Map.of(TOWN, 2));
        Map<UUID, Integer> properties = new HashMap<>(Map.of(id(10), 2));
        Remaining remaining = new Remaining(2, markets, properties);
        markets.put(TOWN, 0);
        properties.clear();
        assertEquals(Map.of(TOWN, 2), remaining.markets());
        assertEquals(Map.of(id(10), 2), remaining.properties());
        assertThrows(UnsupportedOperationException.class, () -> remaining.markets().put(TOWN, 0));
        assertThrows(UnsupportedOperationException.class, () -> remaining.properties().clear());
        assertThrows(UnsupportedOperationException.class, () -> remaining.markets().entrySet().iterator().next().setValue(0));
        assertThrows(UnsupportedOperationException.class, () -> remaining.properties().entrySet().iterator().next().setValue(0));
        List<Home> homes = List.of(home(10, TOWN, "flat", 90, 3, 0));
        List<Seeker> seekers = List.of(seeker(1, TOWN, "flat"), seeker(2, TOWN, "flat"));
        Plan expected = new Plan(List.of(assignment(1, 10, 0), assignment(2, 10, 3)), List.of());
        assertEquals(expected, planWithRemaining(DAY, seekers, homes, remaining));
        assertEquals(expected, planWithRemaining(DAY, seekers, homes, remaining));
        assertEquals(new Remaining(2, Map.of(TOWN, 2), Map.of(id(10), 2)), remaining);
    }

    @Test
    void duplicateResidentsOrPropertiesCannotMultiplyAllowancesAcrossClasses() {
        Seeker seeker = seeker(1, TOWN, "flat");
        Home flat = home(10, TOWN, "flat", 90, 0);
        Home house = home(10, TOWN, "house", 90, 1);
        Remaining zero = new Remaining(0, Map.of(TOWN, 0), Map.of(id(10), 0));
        assertThrows(IllegalArgumentException.class, () -> planWithRemaining(DAY, List.of(seeker, seeker), List.of(flat), zero));
        assertThrows(IllegalArgumentException.class, () -> planWithRemaining(DAY, List.of(), List.of(flat, house), zero));
    }

    @Test
    void uniformLimitsAndExplicitUniformRemainingProduceTheSamePlans() {
        List<Seeker> seekers = List.of(seeker(1, TOWN, "flat"), seeker(2, TOWN, "flat"),
                seeker(3, TOWN, "house"), seeker(4, OTHER_WORLD, "flat"));
        List<Home> homes = List.of(home(10, TOWN, "flat", 90, 3, 0), home(11, TOWN, "house", 90, 2, 1),
                home(12, OTHER_WORLD, "flat", 90, 3, 0));
        for (int server : new int[]{0, 1, 3, 2048}) {
            for (int market : new int[]{0, 1, 2, 2048}) {
                for (int property : new int[]{0, 1, 2, 4}) {
                    assertEquals(plan(DAY, seekers, homes, new Limits(server, market, property)),
                            planWithRemaining(DAY, seekers, homes, new Remaining(server, Map.of(TOWN, market, OTHER_WORLD, market),
                                    Map.of(id(10), property, id(11), property, id(12), property))));
                }
            }
        }
    }

    @Test
    void seekerHomeSlotAndQuotaMapPermutationsProduceTheSameUnequalPlan() {
        List<Seeker> seekers = List.of(seeker(1, TOWN, "flat"), seeker(2, TOWN, "flat"),
                seeker(3, TOWN, "house"), seeker(4, OTHER_WORLD, "flat"));
        List<Home> homes = List.of(home(10, TOWN, "flat", 90, 3, 0), home(11, TOWN, "house", 90, 2, 1),
                home(12, OTHER_WORLD, "flat", 90, 3, 0));
        Plan expected = new Plan(List.of(assignment(1, 10, 0), assignment(3, 11, 1), assignment(4, 12, 0)),
                List.of(unmatched(2, DAILY_LIMIT)));
        for (List<Seeker> seekerOrder : permutations(seekers)) {
            for (List<Home> homeOrder : permutations(homes)) {
                for (List<Market> marketOrder : permutations(List.of(TOWN, OTHER_WORLD))) {
                    for (List<UUID> propertyOrder : permutations(List.of(id(10), id(11), id(12)))) {
                        Map<Market, Integer> markets = new LinkedHashMap<>();
                        marketOrder.forEach(market -> markets.put(market, market.equals(TOWN) ? 2 : 1));
                        Map<UUID, Integer> properties = new LinkedHashMap<>();
                        propertyOrder.forEach(property -> properties.put(property, property.equals(id(12)) ? 2 : 1));
                        Remaining remaining = new Remaining(3, markets, properties);
                        assertEquals(expected, planWithRemaining(DAY, seekerOrder, homeOrder, remaining));
                        List<Home> reversedSlots = homeOrder.stream().map(home -> {
                            List<Integer> slots = new ArrayList<>(home.freeSlots());
                            Collections.reverse(slots);
                            return new Home(home.propertyId(), home.worldId(), home.marketId(), home.classKey(),
                                    home.condition(), home.dailyRentCents(), home.score(), home.premiumEligible(), slots);
                        }).toList();
                        assertEquals(expected, planWithRemaining(DAY, seekerOrder, reversedSlots, remaining));
                    }
                }
            }
        }
    }

    private static UUID id(long value) { return new UUID(0, value); }

    private static HousingPolicy policy(Market market, String classKey) {
        return new HousingPolicy(market.worldId(), market.marketId(), classKey, STANDARD, 100, 0);
    }

    private static Seeker seeker(long resident, Market market, String classKey) {
        return new Seeker(id(resident), policy(market, classKey), 3, 7, DAY, DAY);
    }

    private static Home home(long property, Market market, String classKey, double score, Integer... slots) {
        return new Home(id(property), market.worldId(), market.marketId(), classKey, 70, 100, score, false,
                Arrays.asList(slots));
    }

    private static Assignment assignment(long resident, long property, int slot) {
        return new Assignment(id(resident), id(property), slot, 3, 7);
    }

    private static Unmatched unmatched(long resident, Reason reason) { return new Unmatched(id(resident), reason); }

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
}
