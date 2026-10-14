package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.ServiceAssessmentRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Worker-only complete plan from one read view. No reservation, authority check or scheduled caller. */
public final class HousingDecisionRepository {
    public record Options(Instant evidenceFrom, int retentionHorizonDays, HousingMoveRequest.Policy admissionPolicy,
                          HousingDecisionPlan.ColdStartPolicy coldStartPolicy, int maxOwners,
                          int maxCases, Duration maxReadTime, boolean premiumEnabled,
                          boolean advertisingRankEnabled, Map<UUID, Integer> partnerRankCaps) {
        /**
         * partnerRankCaps: town.partnerships rank points per resident from the current config, read live like
         * advertisingRankEnabled. Empty (partnerships, the rank bonus or advertising rank off) reads nothing and adds
         * nothing. The partner bonus only counts while advertisingRankEnabled is also on.
         */
        public Options(Instant evidenceFrom, int retentionHorizonDays, HousingMoveRequest.Policy admissionPolicy,
                       HousingDecisionPlan.ColdStartPolicy coldStartPolicy, int maxOwners, int maxCases,
                       Duration maxReadTime, boolean premiumEnabled, boolean advertisingRankEnabled) {
            this(evidenceFrom, retentionHorizonDays, admissionPolicy, coldStartPolicy, maxOwners, maxCases, maxReadTime,
                    premiumEnabled, advertisingRankEnabled, Map.of());
        }
        public Options(Instant evidenceFrom, int retentionHorizonDays, HousingMoveRequest.Policy admissionPolicy,
                       HousingDecisionPlan.ColdStartPolicy coldStartPolicy, int maxOwners, int maxCases,
                       Duration maxReadTime, boolean premiumEnabled) {
            this(evidenceFrom, retentionHorizonDays, admissionPolicy, coldStartPolicy, maxOwners, maxCases, maxReadTime, premiumEnabled,
                    false);
        }
        public Options(Instant evidenceFrom, int retentionHorizonDays, HousingMoveRequest.Policy admissionPolicy,
                       HousingDecisionPlan.ColdStartPolicy coldStartPolicy, int maxOwners, int maxCases, Duration maxReadTime) {
            this(evidenceFrom, retentionHorizonDays, admissionPolicy, coldStartPolicy, maxOwners, maxCases, maxReadTime, false);
        }
        public Options {
            Objects.requireNonNull(evidenceFrom);
            Objects.requireNonNull(admissionPolicy);
            Objects.requireNonNull(coldStartPolicy);
            Objects.requireNonNull(maxReadTime);
            partnerRankCaps = Map.copyOf(partnerRankCaps);
            if (partnerRankCaps.values().stream().anyMatch(points -> points < 1 || points > 4))
                throw new IllegalArgumentException("Invalid partner rank points");
            if (retentionHorizonDays < 1 || retentionHorizonDays > 30 || maxOwners < 1 || maxOwners > 512
                    || maxCases < 0 || maxCases > 65536 || maxReadTime.isZero() || maxReadTime.isNegative()
                    || maxReadTime.compareTo(Duration.ofMinutes(1)) > 0
                    || evidenceFrom.getNano() % 1_000_000 != 0
                    || evidenceFrom.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !evidenceFrom.isBefore(Instant.parse("+10000-01-01T00:00:00Z")))
                throw new IllegalArgumentException("Invalid housing decision read options");
        }
    }

    private final Database database;
    private final Clock clock;
    private final EconomyClock days;
    private final HousingMarketRepository market;
    private final ServiceAssessmentRepository services;
    private final ZoneId zone;
    private final int resetHour;

    public HousingDecisionRepository(Database database, Clock clock, ZoneId zone, int resetHour) {
        this(database, clock, zone, resetHour, 0);
    }

    public HousingDecisionRepository(Database database, Clock clock, ZoneId zone, int resetHour, int evictedSlotLockDays) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
        this.resetHour = resetHour;
        days = new EconomyClock(clock, zone, resetHour);
        market = new HousingMarketRepository(database, clock, zone, resetHour, evictedSlotLockDays);
        services = new ServiceAssessmentRepository(database, clock, zone, resetHour);
    }

    /** Owner budget is a rejection threshold, never permission to rank only an arbitrary prefix. */
    public HousingDecisionPlan read(LocalDate day, Options options) throws SQLException {
        Objects.requireNonNull(day);
        Objects.requireNonNull(options);
        long started = System.nanoTime();
        checkBudget(started, options);
        try (ReadSnapshot shared = ReadSnapshot.open(database, clock)) {
            checkBudget(started, options);
            Duration window = Duration.between(options.evidenceFrom(), shared.capturedAt());
            if (window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(30)) > 0)
                throw new SQLException("Housing evidence requires a positive <=30-day window");
            // The partner bonus needs advertising rank on too, so it can never be the only rank lever.
            var partnerCaps = options.advertisingRankEnabled() ? options.partnerRankCaps() : Map.<UUID, Integer>of();
            HousingMarketSnapshot snapshot = market.read(shared, day, options.admissionPolicy(), partnerCaps);
            checkBudget(started, options);
            var owners = snapshot.homes().stream().map(HousingMarketSnapshot.Home::ownerId).distinct()
                    .sorted(Comparator.comparing(UUID::toString)).toList();
            if (owners.size() > options.maxOwners()) throw new SQLException("Housing service owner budget exceeded");
            var evidence = new LinkedHashMap<UUID, ServiceAssessment>();
            int caseCount = 0;
            for (UUID owner : owners) {
                checkBudget(started, options);
                if (!day.equals(days.today())) throw new SQLException("Housing decision crossed economy day");
                ServiceAssessment assessment = services.read(shared, owner, options.evidenceFrom(), options.retentionHorizonDays());
                checkBudget(started, options);
                caseCount = Math.addExact(caseCount, assessment.utilities().cases().size()
                        + assessment.incidents().cases().size() + assessment.retention().cases().size());
                if (caseCount > options.maxCases()) throw new SQLException("Housing aggregate evidence budget exceeded");
                evidence.put(owner, assessment);
            }
            HousingDecisionPlan plan = HousingDecisionPlan.prepare(snapshot, evidence, options.coldStartPolicy(),
                    options.premiumEnabled(), zone, resetHour, options.advertisingRankEnabled(), !partnerCaps.isEmpty());
            checkBudget(started, options);
            if (!day.equals(days.today())) throw new SQLException("Housing decision crossed economy day");
            return plan;
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid housing decision evidence", failure);
        }
    }

    /** Cooperative budget, not preemption: one in-flight owner read can outlive the deadline. */
    private static void checkBudget(long started, Options options) throws SQLException {
        if (Thread.currentThread().isInterrupted()) throw new SQLException("Housing decision read interrupted");
        if (System.nanoTime() - started >= options.maxReadTime().toNanos())
            throw new SQLException("Housing decision elapsed budget exceeded");
    }
}
