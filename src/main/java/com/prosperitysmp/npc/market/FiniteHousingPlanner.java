package com.prosperitysmp.npc.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure snapshot heuristic; no identities, vacancies, preferences or money are created. */
public final class FiniteHousingPlanner {
    public static final int MAX_SEEKERS = 512;
    public static final int MAX_HOMES = 512;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Comparator<UUID> UUID_LEX = Comparator.comparing(UUID::toString);
    private static final Comparator<Home> HOME_ORDER = Comparator.comparingDouble((Home h) -> h.score() + h.matchBonus()).reversed()
            .thenComparingLong(Home::dailyRentCents)
            .thenComparing(Home::propertyId, UUID_LEX);

    public enum Reason { NOT_YET_AVAILABLE, COOLDOWN, NO_COMPATIBLE_HOME, DAILY_LIMIT, NO_VACANCY }

    public record Seeker(UUID residentId, HousingPolicy policy, long membershipRevision,
                         long tenancyRevision, LocalDate availableSince, LocalDate cooldownUntil,
                         UUID currentProperty, double minimumScore) {
        public Seeker(UUID residentId, HousingPolicy policy, long membershipRevision, long tenancyRevision,
                      LocalDate availableSince, LocalDate cooldownUntil) {
            this(residentId, policy, membershipRevision, tenancyRevision, availableSince, cooldownUntil, null, 0);
        }
        public Seeker {
            Objects.requireNonNull(residentId, "residentId");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(availableSince, "availableSince");
            Objects.requireNonNull(cooldownUntil, "cooldownUntil");
            validateRevisions(membershipRevision, tenancyRevision);
            if (!Double.isFinite(minimumScore) || minimumScore < 0 || minimumScore > 110
                    || currentProperty != null && (policy.tier() != HousingPolicy.Tier.PREMIUM || tenancyRevision < 0))
                throw new IllegalArgumentException("Invalid relocation seeker");
        }
    }

    public record Home(UUID propertyId, UUID worldId, String marketId, String classKey,
                       int condition, long dailyRentCents, double score, boolean premiumEligible,
                       List<Integer> freeSlots, double matchBonus) {
        /** matchBonus only orders compatible vacancies; score alone gates minimumScore. */
        public Home(UUID propertyId, UUID worldId, String marketId, String classKey,
                    int condition, long dailyRentCents, double score, boolean premiumEligible, List<Integer> freeSlots) {
            this(propertyId, worldId, marketId, classKey, condition, dailyRentCents, score, premiumEligible, freeSlots, 0.0);
        }
        public Home {
            Objects.requireNonNull(propertyId, "propertyId");
            Objects.requireNonNull(worldId, "worldId");
            validateKey(marketId);
            validateKey(classKey);
            if (condition < 0 || condition > 100 || dailyRentCents < 1
                    || dailyRentCents > Integer.MAX_VALUE || !Double.isFinite(score)
                    || score < 0 || score > 100) {
                throw new IllegalArgumentException("Invalid home condition, rent or score");
            }
            if (!Double.isFinite(matchBonus) || matchBonus < 0 || matchBonus > 12) {
                throw new IllegalArgumentException("Invalid match bonus");
            }
            score = score == 0.0 ? 0.0 : score;
            matchBonus = matchBonus == 0.0 ? 0.0 : matchBonus;
            Objects.requireNonNull(freeSlots, "freeSlots");
            if (freeSlots.size() > 4) throw new IllegalArgumentException("Too many free slots");
            freeSlots = List.copyOf(freeSlots);
            int seen = 0;
            for (int slot : freeSlots) {
                if (slot < 0 || slot > 3 || (seen & (1 << slot)) != 0) {
                    throw new IllegalArgumentException("Invalid or duplicate free slot");
                }
                seen |= 1 << slot;
            }
        }
    }

    /** Uniform caps for this plan only; persisted per-key daily quotas are not enforced here. */
    public record Limits(int server, int perMarket, int perProperty) {
        public Limits {
            if (server < 0 || server > 2048 || perMarket < 0 || perMarket > 2048
                    || perProperty < 0 || perProperty > 4) {
                throw new IllegalArgumentException("Invalid housing limits");
            }
        }
    }

    public record Market(UUID worldId, String marketId) {
        public Market {
            Objects.requireNonNull(worldId, "worldId");
            validateKey(marketId);
        }
    }

    /** Explicit snapshot allowances; historical keys may remain after homes leave the snapshot. */
    public record Remaining(int server, Map<Market, Integer> markets, Map<UUID, Integer> properties) {
        public Remaining {
            Objects.requireNonNull(markets, "markets");
            Objects.requireNonNull(properties, "properties");
            if (server < 0 || server > 2048 || markets.size() > MAX_HOMES || properties.size() > MAX_HOMES) {
                throw new IllegalArgumentException("Invalid remaining housing bounds");
            }
            markets = Map.copyOf(markets);
            properties = Map.copyOf(properties);
            for (int count : markets.values()) {
                if (count < 0 || count > 2048) throw new IllegalArgumentException("Invalid remaining market quota");
            }
            for (int count : properties.values()) {
                if (count < 0 || count > 4) throw new IllegalArgumentException("Invalid remaining property quota");
            }
        }
    }

    public record Assignment(UUID residentId, UUID propertyId, int slot,
                             long membershipRevision, long tenancyRevision) {
        public Assignment {
            Objects.requireNonNull(residentId, "residentId");
            Objects.requireNonNull(propertyId, "propertyId");
            if (slot < 0 || slot > 3) throw new IllegalArgumentException("Invalid assigned slot");
            validateRevisions(membershipRevision, tenancyRevision);
        }
    }

    public record Unmatched(UUID residentId, Reason reason) {
        public Unmatched {
            Objects.requireNonNull(residentId, "residentId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Plan(List<Assignment> assignments, List<Unmatched> unmatched) {
        public Plan {
            assignments = List.copyOf(assignments);
            unmatched = List.copyOf(unmatched);
        }
    }

    private record Candidate(Seeker seeker, List<HomeState> edges, int initialSlots) { }

    private static final class HomeState {
        private final Home home;
        private final Market market;
        private int remainingSlots;
        private int assignments;

        private HomeState(Home home) {
            this.home = home;
            this.market = new Market(home.worldId(), home.marketId());
            for (int slot : home.freeSlots()) remainingSlots |= 1 << slot;
        }
    }

    private FiniteHousingPlanner() { }

    public static Plan plan(LocalDate day, List<Seeker> seekers, List<Home> homes, Limits limits) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(seekers, "seekers");
        Objects.requireNonNull(homes, "homes");
        if (seekers.size() > MAX_SEEKERS || homes.size() > MAX_HOMES) {
            throw new IllegalArgumentException("Housing snapshot exceeds bounds");
        }
        seekers = List.copyOf(seekers);
        homes = List.copyOf(homes);
        Map<Market, Integer> markets = new HashMap<>();
        Map<UUID, Integer> properties = new HashMap<>();
        for (Home home : homes) {
            markets.put(new Market(home.worldId(), home.marketId()), limits.perMarket());
            properties.put(home.propertyId(), limits.perProperty());
        }
        return planWithRemaining(day, seekers, homes, new Remaining(limits.server(), markets, properties));
    }

    /**
     * Fewest initially compatible free slots first, then oldest availability, then UUID text.
     * Each seeker takes the highest score, lowest rent, lexically first property and lowest
     * supplied slot allowed by the caps. This deterministic greedy heuristic is not globally
     * optimal. Compatibility is precomputed once; allocation scans each edge once (bounded
     * O(seekers * homes * slots) matching work, plus O(seekers log seekers) ordering).
     * Assignments retain decision order; unmatched residents are ordered by UUID text.
     * Future availability precedes cooldown. A metadata match without initial slots is
     * NO_VACANCY; DAILY_LIMIT requires a still-free compatible slot blocked by caps.
     * No departure can add slots. Committers must revalidate the snapshot and revisions.
     * Every supplied home requires explicit market and property allowances, even at zero.
     */
    public static Plan planWithRemaining(LocalDate day, List<Seeker> seekers, List<Home> homes,
                                         Remaining remaining) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(seekers, "seekers");
        Objects.requireNonNull(homes, "homes");
        if (seekers.size() > MAX_SEEKERS || homes.size() > MAX_HOMES) {
            throw new IllegalArgumentException("Housing snapshot exceeds bounds");
        }
        seekers = List.copyOf(seekers);
        homes = List.copyOf(homes);
        Set<UUID> residentIds = new HashSet<>();
        for (Seeker seeker : seekers) {
            if (!residentIds.add(seeker.residentId())) {
                throw new IllegalArgumentException("Duplicate resident UUID");
            }
        }
        Set<UUID> propertyIds = new HashSet<>();
        List<HomeState> states = new ArrayList<>(homes.size());
        for (Home home : homes) {
            if (!propertyIds.add(home.propertyId())) {
                throw new IllegalArgumentException("Duplicate property UUID");
            }
            HomeState state = new HomeState(home);
            if (!remaining.markets().containsKey(state.market)
                    || !remaining.properties().containsKey(home.propertyId())) {
                throw new IllegalArgumentException("Missing remaining allowance for home");
            }
            states.add(state);
        }

        List<Unmatched> unmatched = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Seeker seeker : seekers) {
            if (day.isBefore(seeker.availableSince())) {
                unmatched.add(new Unmatched(seeker.residentId(), Reason.NOT_YET_AVAILABLE));
                continue;
            }
            if (day.isBefore(seeker.cooldownUntil())) {
                unmatched.add(new Unmatched(seeker.residentId(), Reason.COOLDOWN));
                continue;
            }
            List<HomeState> edges = new ArrayList<>();
            boolean metadataMatch = false;
            int initialSlots = 0;
            for (HomeState state : states) {
                if (!compatible(seeker.policy(), state.home) || state.home.propertyId().equals(seeker.currentProperty())
                        || state.home.score() < seeker.minimumScore()) continue;
                metadataMatch = true;
                if (state.remainingSlots != 0) {
                    edges.add(state);
                    initialSlots += Integer.bitCount(state.remainingSlots);
                }
            }
            if (edges.isEmpty()) {
                unmatched.add(new Unmatched(seeker.residentId(),
                        metadataMatch ? Reason.NO_VACANCY : Reason.NO_COMPATIBLE_HOME));
            } else {
                candidates.add(new Candidate(seeker, edges, initialSlots));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::initialSlots)
                .thenComparing(candidate -> candidate.seeker().availableSince())
                .thenComparing(candidate -> candidate.seeker().residentId(), UUID_LEX));

        Map<Market, Integer> marketAssignments = new HashMap<>();
        List<Assignment> assignments = new ArrayList<>();
        for (Candidate candidate : candidates) {
            HomeState best = null;
            boolean freeMatch = false;
            for (HomeState state : candidate.edges()) {
                if (state.remainingSlots == 0) continue;
                freeMatch = true;
                if (assignments.size() >= remaining.server()
                        || state.assignments >= remaining.properties().get(state.home.propertyId())
                        || marketAssignments.getOrDefault(state.market, 0) >= remaining.markets().get(state.market)) continue;
                if (best == null || HOME_ORDER.compare(state.home, best.home) < 0) best = state;
            }
            Seeker seeker = candidate.seeker();
            if (best == null) {
                unmatched.add(new Unmatched(seeker.residentId(),
                        freeMatch ? Reason.DAILY_LIMIT : Reason.NO_VACANCY));
                continue;
            }
            int slot = Integer.numberOfTrailingZeros(best.remainingSlots);
            best.remainingSlots &= ~(1 << slot);
            best.assignments++;
            marketAssignments.merge(best.market, 1, Integer::sum);
            assignments.add(new Assignment(seeker.residentId(), best.home.propertyId(), slot,
                    seeker.membershipRevision(), seeker.tenancyRevision()));
        }
        unmatched.sort(Comparator.comparing(Unmatched::residentId, UUID_LEX));
        return new Plan(assignments, unmatched);
    }

    private static boolean compatible(HousingPolicy policy, Home home) {
        return policy.worldId().equals(home.worldId()) && policy.marketId().equals(home.marketId())
                && policy.classKey().equals(home.classKey()) && home.dailyRentCents() <= policy.maxDailyRentCents()
                && home.condition() >= policy.minimumCondition()
                && (policy.tier() != HousingPolicy.Tier.PREMIUM || home.premiumEligible());
    }

    private static void validateKey(String key) {
        if (!KEY.matcher(Objects.requireNonNull(key, "key")).matches()) {
            throw new IllegalArgumentException("Invalid housing key");
        }
    }

    private static void validateRevisions(long membershipRevision, long tenancyRevision) {
        if (membershipRevision < 0 || membershipRevision == Long.MAX_VALUE
                || tenancyRevision < -1 || tenancyRevision == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid housing revisions");
        }
    }
}
