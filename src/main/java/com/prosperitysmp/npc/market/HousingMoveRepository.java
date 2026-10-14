package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.ServiceAssessmentRepository;
import com.prosperitysmp.npc.property.TenancyDeparture;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.List;

/**
 * Transactional admission boundary, not a market scheduler or ARM authorization adapter.
 * No money moves. Premium admission rechecks recorded service in this same transaction.
 */
public final class HousingMoveRepository {
    public record Receipt(HousingMoveRequest request, Policy policy, long tenancyRevisionAfter,
                          LocalDate cooldownUntil, Instant appliedAt) { }
    public record Result(boolean newlyApplied, Receipt receipt) { }
    private record Home(UUID id, UUID owner, UUID world, String market, String propertyClass,
                        int epoch, long revision, int capacity, long rent, int condition, boolean enabled) { }
    private record Person(String name, String occupation) { }
    private record PremiumEvidence(HousingMarketSnapshot.Home home, ServiceAssessment assessment) { }
    private final Database database;
    private final Clock clock;
    private final EconomyClock days;
    private final ZoneId zone;
    private final int resetHour;
    private final boolean premiumEnabled;
    private final int evictedSlotLockDays;
    private final ServiceAssessmentRepository services;

    public HousingMoveRepository(Database database, Clock clock, ZoneId zone, int resetHour) {
        this(database, clock, zone, resetHour, false);
    }

    public HousingMoveRepository(Database database, Clock clock, ZoneId zone, int resetHour, boolean premiumEnabled) {
        this(database, clock, zone, resetHour, premiumEnabled, 0);
    }

    /** evictedSlotLockDays 0 = off; N keeps a paid-eviction slot closed for N economy days. */
    public HousingMoveRepository(Database database, Clock clock, ZoneId zone, int resetHour, boolean premiumEnabled,
                                 int evictedSlotLockDays) {
        if (evictedSlotLockDays < 0 || evictedSlotLockDays > 30) throw new IllegalArgumentException("Invalid evicted slot lock days");
        this.evictedSlotLockDays = evictedSlotLockDays;
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        this.days = new EconomyClock(clock, zone, resetHour);
        this.zone = Objects.requireNonNull(zone);
        this.resetHour = resetHour;
        this.premiumEnabled = premiumEnabled;
        this.services = new ServiceAssessmentRepository(database, clock, zone, resetHour);
    }

    /** Quote only an actual currently admissible vacancy. No receipt, quota or occupancy write. */
    public HousingMoveRequest quote(UUID requestId, UUID residentId, UUID propertyId, int slot,
                                    LocalDate day) throws SQLException {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(residentId);
        Objects.requireNonNull(propertyId);
        validDay(day);
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("Invalid housing slot");
        return transaction(c -> {
            HousingRegistry.lock(c);
            currentDay(day);
            Home home = home(c, propertyId);
            HousingRegistry.Member member = member(c, residentId);
            eligible(home, member, day, slot);
            if (member.policy().tier() == HousingPolicy.Tier.PREMIUM) premiumEvidence(c, home);
            long tenancyRevision = vacancy(c, residentId, member.policy().tier(), day);
            availableSlot(c, home, slot, day);
            person(c, residentId);
            return new HousingMoveRequest(requestId, residentId, propertyId, home.owner(), home.world(),
                    home.market(), home.propertyClass(), home.epoch(), home.revision(), member.revision(),
                    tenancyRevision, slot, day, member.policy().tier());
        });
    }

    /** Receipt, quota consumption, occupancy and cooldown either all commit or all roll back. */
    public Result apply(HousingMoveRequest request, Policy policy) throws SQLException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(policy);
        return transaction(c -> {
            HousingRegistry.lock(c);
            Optional<Receipt> prior = receipt(c, request.requestId());
            if (prior.isPresent()) {
                require(prior.get().request().equals(request) && prior.get().policy().equals(policy), "Housing request payload conflict");
                return new Result(false, prior.get());
            }
            currentDay(request.day());
            require(request.tier() != HousingPolicy.Tier.PREMIUM
                    || policy.cooldownDays() >= PropertyFormulas.PREMIUM_MOVE_COOLDOWN_DAYS, "Premium cooldown below policy minimum");
            Home home = home(c, request.propertyId());
            require(Objects.equals(home.owner(), request.ownerId()) && home.epoch() == request.ownershipEpoch()
                    && home.revision() == request.propertyRevision() && home.world().equals(request.worldId())
                    && home.market().equals(request.marketId()) && home.propertyClass().equals(request.classKey()),
                    "Housing property changed");
            HousingRegistry.Member member = member(c, request.residentId());
            require(member.revision() == request.membershipRevision() && member.policy().tier() == request.tier(),
                    "Housing membership changed");
            eligible(home, member, request.day(), request.slot());
            PremiumEvidence premium = request.tier() == HousingPolicy.Tier.PREMIUM ? premiumEvidence(c, home) : null;
            PremiumEvidence sourceEvidence = null;
            if (request.origin() == null) {
                require(vacancy(c, request.residentId(), member.policy().tier(), request.day()) == request.tenancyRevision(),
                        "Housing tenancy changed");
            } else sourceEvidence = relocation(c, request, premium);
            availableSlot(c, home, request.slot(), request.day());
            Person person = person(c, request.residentId());
            LocalDate cooldown = request.day().plusDays(policy.cooldownDays());
            validDay(cooldown);
            pinPolicy(c, request.day(), policy);
            quota(c, request, policy);
            Instant at = Instant.ofEpochMilli(clock.millis());
            require(days.dayAt(at).equals(request.day()), "Housing economy day changed before commit");

            if (request.origin() != null) {
                var origin = request.origin();
                var departure = new TenancyDeparture(request.residentId(), origin.propertyId(), origin.ownerId(),
                        origin.ownershipEpoch(), request.tenancyRevision(), origin.slot(), at, TenancyDeparture.Cause.DEPARTURE, null);
                try (var ps = prepare(c, """
                        INSERT INTO npc_tenancy_departures(resident_id,occupancy_revision_before,occupancy_revision_after,
                          property_id,owner_at_departure,ownership_epoch,slot,departed_at,cause,purchase_event_id,payload_sha256)
                        VALUES (?,?,?,?,?,?,?,?,?,NULL,?)
                        """, departure.residentId(), departure.occupancyRevisionBefore(), departure.occupancyRevisionAfter(),
                        departure.propertyId(), departure.ownerAtDeparture(), departure.ownershipEpoch(), departure.slot(),
                        LocalDateTime.ofInstant(at, ZoneOffset.UTC), departure.cause().name(), departure.fingerprint())) { one(c, ps); }
                // Two revisions preserve the existing departure-before-successor invariant, without a visible vacant interval.
                try (var ps = prepare(c, """
                        UPDATE npc_tenants SET property_id=?,slot=?,move_cooldown_until_day=?,row_version=row_version+2
                        WHERE tenant_id=? AND row_version=? AND property_id=? AND slot=? AND tier='PREMIUM'
                        """, request.propertyId(), request.slot(), cooldown.toString(), request.residentId(),
                        request.tenancyRevision(), origin.propertyId(), origin.slot())) { one(c, ps); }
            } else if (request.tenancyRevision() == -1) {
                try (PreparedStatement ps = prepare(c, """
                        INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,tier,archetype,
                          move_cooldown_until_day,row_version) VALUES (?,?,?,?,?,?,?,0)
                        """, request.residentId(), person.name(), request.propertyId(), request.slot(),
                        member.policy().tier().name(), person.occupation(), cooldown.toString())) { one(c, ps); }
            } else {
                try (PreparedStatement ps = prepare(c, """
                        UPDATE npc_tenants SET property_id=?,slot=?,move_cooldown_until_day=?,row_version=row_version+1
                        WHERE tenant_id=? AND row_version=? AND property_id IS NULL AND slot IS NULL
                        """, request.propertyId(), request.slot(), cooldown.toString(), request.residentId(),
                        request.tenancyRevision())) { one(c, ps); }
            }
            long after = request.tenancyRevision() + (request.origin() == null ? 1 : 2);
            byte[] evidence = premium == null ? null : evidence(request, policy, premium, sourceEvidence);
            String columns = evidence == null ? "" : ",tier,premium_evidence,premium_evidence_sha256";
            String values = evidence == null ? "" : ",?,?,?";
            if (request.origin() != null) {
                columns += ",origin_property_id,origin_owner_uuid,origin_ownership_epoch,origin_property_revision,"
                        + "origin_slot,origin_admission_id";
                values += ",?,?,?,?,?,?";
            }
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_housing_moves(request_id,payload_hash,economy_day,resident_id,property_id,owner_uuid,
                      ownership_epoch,property_revision,membership_revision,tenancy_revision_before,tenancy_revision_after,
                      slot,world_uuid,market_id,class_key,cooldown_until,applied_at_millis
                    """ + columns + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?" + values + ")", request.requestId(),
                    request.fingerprint(policy), request.day().toString(), request.residentId(), request.propertyId(), request.ownerId(),
                    request.ownershipEpoch(), request.propertyRevision(),
                    request.membershipRevision(), request.tenancyRevision(), after, request.slot(), request.worldId(),
                    request.marketId(), request.classKey(), cooldown.toString(), at.toEpochMilli())) {
                if (evidence != null) {
                    ps.setString(18, request.tier().name());
                    ps.setBytes(19, evidence);
                    ps.setString(20, sha256(evidence));
                }
                if (request.origin() != null) {
                    var origin = request.origin();
                    ps.setBytes(21, Uuids.toBytes(origin.propertyId()));
                    ps.setBytes(22, Uuids.toBytes(origin.ownerId()));
                    ps.setInt(23, origin.ownershipEpoch());
                    ps.setLong(24, origin.propertyRevision());
                    ps.setInt(25, origin.slot());
                    ps.setBytes(26, Uuids.toBytes(origin.admissionId()));
                }
                one(c, ps);
            }
            require(days.today().equals(request.day()), "Housing economy day changed before commit");
            return new Result(true, new Receipt(request, policy, after, cooldown, at));
        });
    }

    /** Historical evidence, not proof that the resident still occupies the recorded slot. */
    public Optional<Receipt> find(UUID requestId) throws SQLException {
        Objects.requireNonNull(requestId);
        return transaction(c -> {
            HousingRegistry.lock(c);
            return receipt(c, requestId);
        });
    }

    private static HousingRegistry.Member member(Connection c, UUID resident) throws SQLException {
        return HousingRegistry.find(c, resident, true).orElseThrow(() -> new SQLException("Resident not admitted to housing"));
    }
    private void eligible(Home home, HousingRegistry.Member member, LocalDate day, int slot) throws SQLException {
        HousingPolicy p = member.policy();
        require(member.enabled() && !day.isBefore(member.availableSince()), "Housing member unavailable");
        require(home.enabled() && home.owner() != null && slot < home.capacity(), "Housing property unavailable");
        require(home.world().equals(p.worldId()) && home.market().equals(p.marketId()) && home.propertyClass().equals(p.classKey()),
                "Housing scope mismatch");
        require(home.rent() <= p.maxDailyRentCents() && home.condition() >= p.minimumCondition(), "Housing terms ineligible");
        require(p.tier() != HousingPolicy.Tier.PREMIUM || premiumEnabled, "Premium housing policy is not enabled");
    }

    private PremiumEvidence premiumEvidence(Connection c, Home home) throws SQLException {
        return serviceEvidence(c, home, true);
    }

    private PremiumEvidence serviceEvidence(Connection c, Home home, boolean premiumEligibility) throws SQLException {
        Instant at = Instant.ofEpochMilli(clock.millis());
        long reference;
        String region;
        try (var ps = prepare(c, "SELECT reference_rent_cents,region_id FROM npc_properties WHERE property_id=?", home.id());
             var rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Premium home missing");
            reference = number(rs, "reference_rent_cents");
            region = rs.getString("region_id");
            require(!next(c, ps, rs), "Duplicate premium home");
        }
        int installed = 0, effective = 0;
        try (var ps = prepare(c,
                "SELECT level,disabled_incident_id FROM npc_upgrades WHERE property_id=? AND upgrade_type='FACILITIES' FOR UPDATE",
                home.id());
             var rs = ps.executeQuery()) {
            if (next(c, ps, rs)) {
                installed = integer(rs, "level");
                effective = rs.getObject("disabled_incident_id") == null ? installed : 0;
                require(!next(c, ps, rs), "Duplicate premium facilities");
            }
        }
        // Old overdue obligations outside the scoring window must not disappear from eligibility.
        if (premiumEligibility) try (var ps = prepare(c, """
                SELECT 1 FROM npc_utility_bills b WHERE b.property_id=? AND b.ownership_epoch=? AND b.due_at<=?
                AND NOT EXISTS (SELECT 1 FROM npc_utility_payments p WHERE p.property_id=b.property_id
                  AND p.economy_day=b.economy_day AND p.state='PAID') LIMIT 1
                """, home.id(), home.epoch(), LocalDateTime.ofInstant(at, ZoneOffset.UTC));
             var rs = ps.executeQuery()) { require(!next(c, ps, rs), "Premium home has unpaid matured utilities"); }
        if (premiumEligibility) try (var ps = prepare(c,
                "SELECT 1 FROM npc_incidents WHERE property_id=? AND prevented=FALSE AND resolved_at IS NULL AND due_at<=? LIMIT 1",
                home.id(), LocalDateTime.ofInstant(at, ZoneOffset.UTC));
             var rs = ps.executeQuery()) { require(!next(c, ps, rs), "Premium home has overdue service incidents"); }
        var facts = new HousingMarketSnapshot.Home(home.id(), home.owner(), home.world(), region, home.market(), home.propertyClass(),
                home.epoch(), home.revision(), home.capacity(), reference, home.rent(), home.condition(), installed, effective, List.of());
        ServiceAssessment assessment = services.readInTransaction(c, at, home.owner(), at.minus(Duration.ofDays(29)), 7);
        if (premiumEligibility) require(PremiumHousingEligibility.evaluate(facts, assessment, zone, resetHour).eligible(),
                "Premium service evidence no longer qualifies");
        return new PremiumEvidence(facts, assessment);
    }

    private PremiumEvidence relocation(Connection c, HousingMoveRequest request, PremiumEvidence destination) throws SQLException {
        var origin = request.origin();
        require(request.tier() == HousingPolicy.Tier.PREMIUM && premiumEnabled && destination != null, "Relocation policy held");
        Home source = home(c, origin.propertyId());
        require(source.enabled() && source.owner().equals(origin.ownerId()) && source.epoch() == origin.ownershipEpoch()
                && source.revision() == origin.propertyRevision() && source.world().equals(request.worldId())
                && source.market().equals(request.marketId()) && source.propertyClass().equals(request.classKey()),
                "Relocation source changed");
        Receipt prior = receipt(c, origin.admissionId()).orElseThrow(() -> new SQLException("Relocation origin receipt missing"));
        require(prior.request().residentId().equals(request.residentId()) && prior.request().tier() == HousingPolicy.Tier.PREMIUM
                && prior.request().propertyId().equals(origin.propertyId()) && prior.request().slot() == origin.slot()
                && prior.tenancyRevisionAfter() == request.tenancyRevision() && !request.day().isBefore(prior.cooldownUntil()),
                "Relocation origin admission changed or cooldown active");
        try (var ps = prepare(c,
                "SELECT property_id,slot,row_version,tier,move_cooldown_until_day FROM npc_tenants WHERE tenant_id=? FOR UPDATE",
                request.residentId());
             var rs = ps.executeQuery()) {
            require(next(c, ps, rs) && origin.propertyId().equals(uuid(rs, "property_id")) && integer(rs, "slot") == origin.slot()
                    && number(rs, "row_version") == request.tenancyRevision() && "PREMIUM".equals(rs.getString("tier"))
                    && !request.day().isBefore(parseDay(rs.getString("move_cooldown_until_day"))) && !next(c, ps, rs),
                    "Relocation occupancy changed");
        }
        try (var ps = prepare(c,
                "SELECT request_id FROM npc_housing_moves WHERE resident_id=? "
                + "ORDER BY tenancy_revision_after DESC,request_id LIMIT 1 FOR UPDATE", request.residentId());
             var rs = ps.executeQuery()) { require(next(c, ps, rs) && origin.admissionId().equals(uuid(rs, "request_id")),
             "Relocation history changed"); }
        PremiumEvidence evidence = serviceEvidence(c, source, false);
        require(HousingDecisionPlan.rank(evidence.home(), evidence.assessment(),
                HousingDecisionPlan.ColdStartPolicy.HOLD_UNRATED).status() == HousingDecisionPlan.RankStatus.RATED,
                "Relocation source service requires review");
        double before = score(evidence), after = score(destination);
        require(after - before >= PropertyFormulas.PREMIUM_MOVE_SCORE_GAP, "Relocation score improvement changed");
        return evidence;
    }

    private static double score(PremiumEvidence evidence) {
        var h = evidence.home();
        return PropertyFormulas.propertyScore(h.condition(), PropertyFormulas.facilitiesScore(h.effectiveFacilities()),
                PropertyFormulas.rentFairness(h.askingRentCents(), h.referenceRentCents()),
                evidence.assessment().operatorScore().orElseThrow());
    }

    private byte[] evidence(HousingMoveRequest request, Policy policy, PremiumEvidence premium,
            PremiumEvidence source) throws SQLException {
        var assessment = premium.assessment();
        var text = new StringBuilder("premium-proof-v1|").append(request.fingerprint(policy)).append('\n')
                .append(assessment.capturedAt()).append('|').append(assessment.windowFrom()).append('|').append(zone.getId())
                .append('|').append(resetHour).append('|').append(assessment.retention().horizonDays()).append('\n')
                .append("H|").append(premium.home()).append('\n');
        for (var item : assessment.utilities().cases()) text.append("U|").append(item.bill().fingerprint()).append('|')
                .append(item.paymentEventId()).append('|').append(item.paidAt()).append('|').append(item.outcome()).append('\n');
        for (var item : assessment.incidents().cases()) text.append("I|").append(item).append('\n');
        for (var item : assessment.retention().cases()) text.append("R|").append(item).append('\n');
        if (source != null) {
            text.append("ORIGIN|").append(source.home()).append('|').append(score(source)).append('|')
                    .append(source.assessment().capturedAt()).append('|').append(source.assessment().windowFrom()).append('\n');
            for (var item : source.assessment().utilities().cases()) text.append("OU|").append(item).append('\n');
            for (var item : source.assessment().incidents().cases()) text.append("OI|").append(item).append('\n');
            for (var item : source.assessment().retention().cases()) text.append("OR|").append(item).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= 5_242_880, "Premium evidence exceeds bounded storage");
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Home home(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_properties WHERE property_id=? FOR UPDATE", id);
             ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Housing property missing");
            int epoch = integer(rs, "ownership_epoch"), capacity = integer(rs, "capacity"), condition = integer(rs, "condition_score");
            int enabled = integer(rs, "enabled");
            long revision = number(rs, "row_version"), rent = number(rs, "asking_rent_cents");
            String market = rs.getString("market_id"), propertyClass = rs.getString("class_key");
            HousingPolicy.key(market);
            HousingPolicy.key(propertyClass);
            require(epoch >= 0 && revision >= 0 && revision < Long.MAX_VALUE && capacity >= 1 && capacity <= 4
                    && condition >= 0 && condition <= 100 && rent >= 1 && rent <= Integer.MAX_VALUE
                    && enabled >= 0 && enabled <= 1, "Invalid housing property");
            byte[] owner = rs.getBytes("owner_uuid");
            Home result = new Home(id, owner == null ? null : Uuids.fromBytes(owner), Uuids.fromBytes(rs.getBytes("world_uuid")),
                    market, propertyClass, epoch, revision, capacity, rent, condition, enabled == 1);
            require(!next(c, ps, rs), "Duplicate housing property");
            return result;
        }
    }
    private long vacancy(Connection c, UUID resident, HousingPolicy.Tier tier, LocalDate day) throws SQLException {
        Long lastAdmission = null;
        LocalDate lastCooldown = null;
        UUID priorRequest = null;
        try (PreparedStatement ps = prepare(c,
                "SELECT request_id FROM npc_housing_moves WHERE resident_id=? "
                + "ORDER BY tenancy_revision_after DESC,request_id LIMIT 1 FOR UPDATE", resident);
             ResultSet rs = ps.executeQuery()) {
            if (next(c, ps, rs)) priorRequest = uuid(rs, "request_id");
        }
        UUID chronological = null;
        try (PreparedStatement ps = prepare(c,
                "SELECT request_id FROM npc_housing_moves WHERE resident_id=? "
                + "ORDER BY economy_day DESC,applied_at_millis DESC,request_id DESC LIMIT 1 FOR UPDATE", resident);
             ResultSet rs = ps.executeQuery()) {
            if (next(c, ps, rs)) chronological = uuid(rs, "request_id");
        }
        require(Objects.equals(priorRequest, chronological), "Housing receipt chronology disagrees with revision");
        if (priorRequest != null) {
            Receipt previous = receipt(c, priorRequest).orElseThrow(() -> new SQLException("Housing history vanished"));
            require(previous.request().residentId().equals(resident), "Housing history resident mismatch");
            lastAdmission = previous.tenancyRevisionAfter();
            lastCooldown = previous.cooldownUntil();
        }
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_tenants WHERE tenant_id=? FOR UPDATE", resident);
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) {
                require(lastAdmission == null, "Housing tenancy history requires reconciliation");
                return -1;
            }
            require(rs.getBytes("property_id") == null && rs.getObject("slot") == null, "Resident is not vacant");
            long revision = number(rs, "row_version");
            require(revision >= 0 && revision < Long.MAX_VALUE, "Invalid or exhausted tenancy revision");
            require(lastAdmission == null || revision > lastAdmission, "Housing tenancy revision regressed");
            require(tier.name().equals(rs.getString("tier")), "Housing tier requires reconciliation");
            String cooldown = rs.getString("move_cooldown_until_day");
            require(lastCooldown == null || cooldown != null && !parseDay(cooldown).isBefore(lastCooldown),
                    "Housing move cooldown regressed");
            require(cooldown == null || !day.isBefore(parseDay(cooldown)), "Resident move cooldown active");
            require(!next(c, ps, rs), "Duplicate resident tenancy");
            return revision;
        }
    }
    /**
     * Slots closed by a paid eviction. The immutable departure row is written in the eviction's own
     * transaction, so the lock is atomic with it; an economy day D eviction blocks D..D+lockDays-1.
     */
    static Set<Integer> evictedSlots(Connection c, EconomyClock days, UUID property, LocalDate day, int lockDays) throws SQLException {
        Set<Integer> out = new HashSet<>();
        if (lockDays <= 0) return out;
        Instant from = days.startOf(day.minusDays(lockDays - 1L));
        try (PreparedStatement ps = prepare(c,
                "SELECT DISTINCT slot FROM npc_tenancy_departures WHERE property_id=? AND cause='PAID_EVICTION' AND departed_at>=?",
                property, LocalDateTime.ofInstant(from, ZoneOffset.UTC));
             ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) out.add(integer(rs, "slot"));
        }
        return out;
    }
    private void availableSlot(Connection c, Home home, int selected, LocalDate day) throws SQLException {
        require(!evictedSlots(c, days, home.id(), day, evictedSlotLockDays).contains(selected), "Housing slot recently vacated");
        try (PreparedStatement ps = prepare(c,
                "SELECT tenant_id,slot FROM npc_tenants WHERE property_id=? ORDER BY tenant_id LIMIT 5 FOR UPDATE", home.id());
             ResultSet rs = ps.executeQuery()) {
            HashSet<Integer> slots = new HashSet<>();
            while (next(c, ps, rs)) {
                int slot = integer(rs, "slot");
                Uuids.fromBytes(rs.getBytes("tenant_id"));
                require(slot >= 0 && slot < home.capacity() && slots.add(slot) && slots.size() <= home.capacity(),
                        "Invalid existing housing occupancy");
                require(slot != selected, "Housing slot occupied");
            }
        }
    }
    private static Person person(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT name,occupation FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE", id);
             ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Resident profile missing");
            String name = rs.getString("name");
            require(name != null && !name.isBlank() && name.length() <= 64
                    && name.codePoints().noneMatch(ch -> Character.isISOControl(ch) || ch == 0x00a7), "Invalid housing resident name");
            String occupation = ResidentProfile.Occupation.valueOf(rs.getString("occupation")).name();
            require(!next(c, ps, rs), "Duplicate resident profile");
            return new Person(name, occupation);
        }
    }
    private static void pinPolicy(Connection c, LocalDate day, Policy policy) throws SQLException {
        Optional<Policy> existing = policy(c, day);
        if (existing.isPresent()) {
            require(existing.get().equals(policy), "Housing day policy conflict");
            return;
        }
        try (PreparedStatement ps = prepare(c,
                "INSERT INTO npc_housing_days(economy_day,server_limit,market_limit,property_limit,cooldown_days) VALUES (?,?,?,?,?)",
                day.toString(),
                policy.serverLimit(), policy.marketLimit(), policy.propertyLimit(), policy.cooldownDays())) { one(c, ps); }
    }
    private static Optional<Policy> policy(Connection c, LocalDate day) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_housing_days WHERE economy_day=? FOR UPDATE", day.toString());
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return Optional.empty();
            require(day.equals(parseDay(rs.getString("economy_day"))), "Invalid housing policy day");
            Policy value = new Policy(integer(rs, "server_limit"), integer(rs, "market_limit"),
                    integer(rs, "property_limit"), integer(rs, "cooldown_days"));
            require(!next(c, ps, rs), "Duplicate housing day");
            return Optional.of(value);
        }
    }
    private void quota(Connection c, HousingMoveRequest request, Policy policy) throws SQLException {
        int total = 0, market = 0, property = 0;
        HashSet<UUID> residents = new HashSet<>();
        try (PreparedStatement ps = prepare(c,
                "SELECT * FROM npc_housing_moves WHERE economy_day=? ORDER BY request_id LIMIT 513 FOR UPDATE", request.day().toString());
             ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                require(++total <= 512, "Housing daily receipts exceed bound");
                Receipt receipt = readReceipt(rs, policy);
                HousingMoveRequest r = receipt.request();
                require(r.day().equals(request.day()) && residents.add(r.residentId()), "Invalid housing daily receipts");
                require(!r.residentId().equals(request.residentId()), "Resident already admitted this day");
                if (r.worldId().equals(request.worldId()) && r.marketId().equals(request.marketId())) market++;
                if (r.propertyId().equals(request.propertyId())) property++;
            }
        }
        require(total < policy.serverLimit() && market < policy.marketLimit() && property < policy.propertyLimit(),
                "Housing daily quota reached");
    }
    private Optional<Receipt> receipt(Connection c, UUID requestId) throws SQLException {
        // Registry lock serializes receipt writers; release this reader before loading its day policy.
        LocalDate day;
        try (PreparedStatement ps = prepare(c, "SELECT economy_day FROM npc_housing_moves WHERE request_id=? FOR UPDATE", requestId);
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return Optional.empty();
            day = parseDay(rs.getString(1));
            require(!next(c, ps, rs), "Duplicate housing receipt");
        }
        Policy policy = policy(c, day).orElseThrow(() -> new SQLException("Housing receipt policy missing"));
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_housing_moves WHERE request_id=? FOR UPDATE", requestId);
             ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Housing receipt vanished");
            Receipt receipt = readReceipt(rs, policy);
            require(!next(c, ps, rs), "Duplicate housing receipt");
            return Optional.of(receipt);
        }
    }
    /** Shared stored-receipt decoder; callers own a consistent snapshot and warning checks. */
    public Receipt readReceipt(ResultSet rs, Policy policy) throws SQLException {
        boolean explicitTier = hasColumn(rs, "tier");
        HousingPolicy.Tier tier = explicitTier ? HousingPolicy.Tier.valueOf(rs.getString("tier")) : HousingPolicy.Tier.STANDARD;
        HousingMoveRequest.Origin origin = null;
        if (hasColumn(rs, "origin_property_id")) {
            if (rs.getBytes("origin_property_id") != null) origin = new HousingMoveRequest.Origin(uuid(rs, "origin_property_id"),
                    uuid(rs, "origin_owner_uuid"), integer(rs, "origin_ownership_epoch"), number(rs, "origin_property_revision"),
                    integer(rs, "origin_slot"), uuid(rs, "origin_admission_id"));
            else require(rs.getBytes("origin_owner_uuid") == null && rs.getObject("origin_ownership_epoch") == null
                    && rs.getObject("origin_property_revision") == null && rs.getObject("origin_slot") == null
                    && rs.getBytes("origin_admission_id") == null, "Partial relocation origin");
        }
        HousingMoveRequest r = new HousingMoveRequest(uuid(rs, "request_id"), uuid(rs, "resident_id"), uuid(rs, "property_id"),
                uuid(rs, "owner_uuid"), uuid(rs, "world_uuid"), rs.getString("market_id"), rs.getString("class_key"),
                integer(rs, "ownership_epoch"), number(rs, "property_revision"), number(rs, "membership_revision"),
                number(rs, "tenancy_revision_before"), integer(rs, "slot"), parseDay(rs.getString("economy_day")), tier, origin);
        require(r.fingerprint(policy).equals(rs.getString("payload_hash")), "Housing receipt binding mismatch");
        if (explicitTier) {
            byte[] proof = rs.getBytes("premium_evidence");
            String proofHash = rs.getString("premium_evidence_sha256");
            if (tier == HousingPolicy.Tier.STANDARD) require(proof == null && proofHash == null, "Standard receipt has premium evidence");
            else require(proof != null && proof.length > 0 && proof.length <= 5_242_880 && sha256(proof).equals(proofHash)
                    && new String(proof, StandardCharsets.UTF_8).startsWith("premium-proof-v1|" + r.fingerprint(policy) + "\n"),
                    "Premium receipt evidence is missing or changed");
        }
        long after = number(rs, "tenancy_revision_after");
        LocalDate cooldown = parseDay(rs.getString("cooldown_until"));
        Instant at = Instant.ofEpochMilli(number(rs, "applied_at_millis"));
        require(after == r.tenancyRevision() + (origin == null ? 1 : 2) && cooldown.equals(r.day().plusDays(policy.cooldownDays()))
                && days.dayAt(at).equals(r.day()) && !at.isAfter(clock.instant()), "Invalid housing receipt outcome");
        return new Receipt(r, policy, after, cooldown, at);
    }

    private static boolean hasColumn(ResultSet rs, String column) throws SQLException {
        var columns = rs.getMetaData();
        for (int i = 1; i <= columns.getColumnCount(); i++)
            if (column.equalsIgnoreCase(columns.getColumnLabel(i))) return true;
        return false;
    }
    private void currentDay(LocalDate day) throws SQLException { require(day.equals(days.today()),
            "Housing requires current economy day"); }
    private static void validDay(LocalDate day) {
        Objects.requireNonNull(day);
        if (day.getYear() < 1 || day.getYear() > 9999) throw new IllegalArgumentException("Housing day out of range");
    }
    private static LocalDate parseDay(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid housing day");
        LocalDate day = LocalDate.parse(value);
        validDay(day);
        return day;
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException { return Uuids.fromBytes(rs.getBytes(column)); }
    private static long number(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        require(!rs.wasNull(), "Missing housing number");
        return value;
    }
    private static int integer(ResultSet rs, String column) throws SQLException { return Math.toIntExact(number(rs, column)); }
    private static void require(boolean condition, String message) throws SQLException { if (!condition) throw new SQLException(message); }
    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, values[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException failure) {
            ps.close();
            throw failure;
        }
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        require(c.getWarnings() == null && ps.getWarnings() == null && (rs == null || rs.getWarnings() == null),
                "Incomplete housing move operation");
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs);
        boolean value = rs.next();
        warnings(c, ps, rs);
        return value;
    }
    private static void one(Connection c, PreparedStatement ps) throws SQLException {
        require(ps.executeUpdate() == 1, "Housing write did not affect one row");
        warnings(c, ps, null);
    }
    private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            }
            catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                if (failure instanceof SQLException sql) throw sql;
                throw new SQLException("Invalid housing move state", failure);
            }
        }
    }
}
