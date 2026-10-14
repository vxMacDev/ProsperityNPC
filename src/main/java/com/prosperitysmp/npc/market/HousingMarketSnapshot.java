package com.prosperitysmp.npc.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Current SQL facts only. No invented operator score, ARM authority, funding or reservation. */
public record HousingMarketSnapshot(LocalDate day, Instant capturedAt, HousingMoveRequest.Policy policy,
                                    List<Home> homes, List<Participant> participants,
                                    List<HousingMoveRepository.Receipt> admissions) {
    public enum Status { READY, DISABLED, OCCUPIED, ADMITTED_TODAY, NOT_YET_AVAILABLE, COOLDOWN, PREMIUM_HELD }
    public record Home(UUID propertyId, UUID ownerId, UUID worldId, String regionId, String marketId,
                       String classKey, int ownershipEpoch, long revision, int capacity,
                       long referenceRentCents, long askingRentCents, int condition,
                       int installedFacilities, int effectiveFacilities, List<Integer> freeSlots,
                       int advertisingLevel, int partnerBonusPoints) {
        /**
         * advertisingLevel is the effective level 0-3 (0 when absent or disabled); it orders matches only.
         * partnerBonusPoints (0-4) is the owner's partnership rank perk on the snapshot day (town.partnerships), read
         * only while the rank bonus is live; it follows this cached owner, so a sold home loses it.
         */
        public Home(UUID propertyId, UUID ownerId, UUID worldId, String regionId, String marketId,
                    String classKey, int ownershipEpoch, long revision, int capacity,
                    long referenceRentCents, long askingRentCents, int condition,
                    int installedFacilities, int effectiveFacilities, List<Integer> freeSlots, int advertisingLevel) {
            this(propertyId, ownerId, worldId, regionId, marketId, classKey, ownershipEpoch, revision, capacity,
                    referenceRentCents, askingRentCents, condition, installedFacilities, effectiveFacilities, freeSlots,
                    advertisingLevel, 0);
        }
        public Home(UUID propertyId, UUID ownerId, UUID worldId, String regionId, String marketId,
                    String classKey, int ownershipEpoch, long revision, int capacity,
                    long referenceRentCents, long askingRentCents, int condition,
                    int installedFacilities, int effectiveFacilities, List<Integer> freeSlots) {
            this(propertyId, ownerId, worldId, regionId, marketId, classKey, ownershipEpoch, revision, capacity,
                    referenceRentCents, askingRentCents, condition, installedFacilities, effectiveFacilities, freeSlots, 0);
        }
        public Home {
            Objects.requireNonNull(propertyId); Objects.requireNonNull(ownerId); Objects.requireNonNull(worldId);
            HousingPolicy.key(marketId); HousingPolicy.key(classKey);
            if (regionId == null || regionId.isBlank() || regionId.length() > 128 || ownershipEpoch < 0
                    || revision < 0 || revision == Long.MAX_VALUE || capacity < 1 || capacity > 4
                    || referenceRentCents < 1 || referenceRentCents > Integer.MAX_VALUE
                    || askingRentCents < 1 || askingRentCents > Integer.MAX_VALUE || condition < 0 || condition > 100
                    || installedFacilities < 0 || installedFacilities > 3 || advertisingLevel < 0 || advertisingLevel > 3
                    || partnerBonusPoints < 0 || partnerBonusPoints > 4
                    || (effectiveFacilities != 0 && effectiveFacilities != installedFacilities))
                throw new IllegalArgumentException("Invalid housing snapshot home");
            freeSlots = List.copyOf(freeSlots);
            if (freeSlots.size() > capacity || new HashSet<>(freeSlots).size() != freeSlots.size()
                    || freeSlots.stream().anyMatch(s -> s < 0 || s >= capacity))
                throw new IllegalArgumentException("Invalid housing snapshot slots");
        }
    }
    public record Participant(HousingRegistry.Member member, long tenancyRevision,
                              LocalDate cooldownUntil, Status status, HousingMoveRequest.Origin origin) {
        public Participant(HousingRegistry.Member member, long tenancyRevision, LocalDate cooldownUntil, Status status) {
            this(member, tenancyRevision, cooldownUntil, status, null);
        }
        public Participant {
            Objects.requireNonNull(member); Objects.requireNonNull(cooldownUntil); Objects.requireNonNull(status);
            if (tenancyRevision < -1 || tenancyRevision == Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid housing snapshot tenancy");
        }
    }
    public HousingMarketSnapshot {
        Objects.requireNonNull(day); Objects.requireNonNull(capturedAt); Objects.requireNonNull(policy);
        if (day.getYear() < 1 || day.getYear() > 9999) throw new IllegalArgumentException("Invalid snapshot day");
        homes = List.copyOf(homes); participants = List.copyOf(participants); admissions = List.copyOf(admissions);
        if (homes.size() > 512 || participants.size() > 512 || admissions.size() > 512)
            throw new IllegalArgumentException("Housing snapshot exceeds bounds");
        var ids = new HashSet<UUID>();
        for (Home h : homes) if (!ids.add(h.propertyId())) throw new IllegalArgumentException("Duplicate snapshot home");
        ids.clear();
        for (Participant p : participants)
            if (!ids.add(p.member().residentId())) throw new IllegalArgumentException("Duplicate snapshot participant");
        ids.clear();
        var markets = new HashMap<FiniteHousingPlanner.Market, Integer>();
        var properties = new HashMap<UUID, Integer>();
        for (var a : admissions) {
            var r = a.request();
            if (!r.day().equals(day) || !a.policy().equals(policy) || !ids.add(r.residentId()))
                throw new IllegalArgumentException("Invalid snapshot admission scope");
            markets.merge(new FiniteHousingPlanner.Market(r.worldId(), r.marketId()), 1, Integer::sum);
            properties.merge(r.propertyId(), 1, Integer::sum);
        }
        if (admissions.size() > policy.serverLimit() || markets.values().stream().anyMatch(n -> n > policy.marketLimit())
                || properties.values().stream().anyMatch(n -> n > policy.propertyLimit()))
            throw new IllegalArgumentException("Recorded housing quotas exceeded");
    }

    /** Every current home has an explicit allowance; historical admissions retain their original scope. */
    public FiniteHousingPlanner.Remaining remaining() {
        var markets = new HashMap<FiniteHousingPlanner.Market, Integer>();
        var properties = new HashMap<UUID, Integer>();
        for (Home h : homes) {
            markets.put(new FiniteHousingPlanner.Market(h.worldId(), h.marketId()), policy.marketLimit());
            properties.put(h.propertyId(), policy.propertyLimit());
        }
        for (var a : admissions) {
            var r = a.request();
            markets.computeIfPresent(new FiniteHousingPlanner.Market(r.worldId(), r.marketId()), (key, n) -> n - 1);
            properties.computeIfPresent(r.propertyId(), (key, n) -> n - 1);
        }
        return new FiniteHousingPlanner.Remaining(policy.serverLimit() - admissions.size(), markets, properties);
    }

    public List<FiniteHousingPlanner.Seeker> seekers() {
        return seekers(false);
    }

    public List<FiniteHousingPlanner.Seeker> seekers(boolean premiumEnabled) {
        var result = new ArrayList<FiniteHousingPlanner.Seeker>();
        for (Participant p : participants) if (p.status() == Status.READY
                || premiumEnabled && p.status() == Status.PREMIUM_HELD) {
            HousingRegistry.Member m = p.member();
            result.add(new FiniteHousingPlanner.Seeker(m.residentId(), m.policy(), m.revision(),
                    p.tenancyRevision(), m.availableSince(), p.cooldownUntil()));
        }
        return List.copyOf(result);
    }

    /** Binds an assignment to these facts, not a freshly re-quoted replacement for a stale plan. */
    public HousingMoveRequest quote(UUID requestId, FiniteHousingPlanner.Assignment assignment) {
        return quote(requestId, assignment, false);
    }

    public HousingMoveRequest quote(UUID requestId, FiniteHousingPlanner.Assignment assignment, boolean premiumEnabled) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(assignment);
        Participant p = participants.stream().filter(x -> x.member().residentId().equals(assignment.residentId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Resident absent from snapshot"));
        Home h = homes.stream().filter(x -> x.propertyId().equals(assignment.propertyId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Home absent from snapshot"));
        HousingPolicy eligibility = p.member().policy();
        var remaining = remaining();
        boolean relocating = premiumEnabled && p.status() == Status.OCCUPIED && p.origin() != null
                && p.member().policy().tier() == HousingPolicy.Tier.PREMIUM;
        if (!(p.status() == Status.READY || premiumEnabled && p.status() == Status.PREMIUM_HELD || relocating)
                || !p.member().enabled() || day.isBefore(p.member().availableSince())
                || admissions.stream().anyMatch(a -> a.request().residentId().equals(p.member().residentId()))
                || day.isBefore(p.cooldownUntil()) || assignment.membershipRevision() != p.member().revision()
                || assignment.tenancyRevision() != p.tenancyRevision() || !h.freeSlots().contains(assignment.slot())
                || !h.worldId().equals(eligibility.worldId()) || !h.marketId().equals(eligibility.marketId())
                || !h.classKey().equals(eligibility.classKey()) || h.askingRentCents() > eligibility.maxDailyRentCents()
                || h.condition() < eligibility.minimumCondition()
                || eligibility.tier() == HousingPolicy.Tier.PREMIUM && !premiumEnabled
                || remaining.server() == 0 || remaining.properties().get(h.propertyId()) == 0
                || remaining.markets().get(new FiniteHousingPlanner.Market(h.worldId(), h.marketId())) == 0)
            throw new IllegalArgumentException("Assignment does not match snapshot eligibility");
        return new HousingMoveRequest(requestId, p.member().residentId(), h.propertyId(), h.ownerId(), h.worldId(),
                h.marketId(), h.classKey(), h.ownershipEpoch(), h.revision(), p.member().revision(),
                p.tenancyRevision(), assignment.slot(), day, eligibility.tier(), relocating ? p.origin() : null);
    }
}
