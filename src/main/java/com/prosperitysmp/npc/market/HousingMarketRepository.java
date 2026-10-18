package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Home;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Participant;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Status;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.PartnershipRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Worker-only, server-enforced read-only consistent snapshot. Never freshens or reserves state. */
public final class HousingMarketRepository {
    private record PropertyRow(UUID id, UUID owner, UUID world, String region, String market, String kind,
                               int epoch, long revision, int capacity, long reference, long asking,
                               int condition, boolean enabled, String backend) { }
    private record Tenancy(UUID resident, UUID property, Integer slot, long revision,
                           HousingPolicy.Tier tier, LocalDate cooldown) { }
    private record Facilities(UUID property, int installed, int effective) { }
    private final Database database;
    private final Clock clock;
    private final EconomyClock days;
    private final HousingMoveRepository receipts;

    private final int evictedSlotLockDays;
    // The configured property backend (V45). Null: every enabled home can be matched (callers without a backend).
    private final String backendId;

    public HousingMarketRepository(Database database, Clock clock, ZoneId zone, int resetHour) {
        this(database, clock, zone, resetHour, 0);
    }

    /** evictedSlotLockDays 0 = off; N hides a paid-eviction slot from matching for N economy days. */
    public HousingMarketRepository(Database database, Clock clock, ZoneId zone, int resetHour, int evictedSlotLockDays) {
        this(database, clock, zone, resetHour, evictedSlotLockDays, null);
    }

    /**
     * As above. With {@code backendId} set, only homes enrolled under that property backend can be matched or be a
     * premium move's origin; homes enrolled under another backend stay out of every move.
     */
    public HousingMarketRepository(Database database, Clock clock, ZoneId zone, int resetHour, int evictedSlotLockDays,
                                   String backendId) {
        if (evictedSlotLockDays < 0 || evictedSlotLockDays > 30) throw new IllegalArgumentException("Invalid evicted slot lock days");
        this.evictedSlotLockDays = evictedSlotLockDays;
        this.backendId = backendId;
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        days = new EconomyClock(clock, zone, resetHour);
        receipts = new HousingMoveRepository(database, clock, zone, resetHour, false, evictedSlotLockDays);
    }

    /** Proposed policy is used only on a day with no pinned row; this method never pins it. */
    public HousingMarketSnapshot read(LocalDate day, Policy proposedPolicy) throws SQLException {
        try (ReadSnapshot shared = ReadSnapshot.open(database, clock)) {
            return read(shared, day, proposedPolicy);
        }
    }

    /** Borrow the caller's view without ending it; all composed readers must use this same handle. */
    public HousingMarketSnapshot read(ReadSnapshot shared, LocalDate day, Policy proposedPolicy) throws SQLException {
        return read(shared, day, proposedPolicy, Map.of());
    }

    /**
     * partnerRankCaps (town.partnerships rank perk, current config points per resident): empty reads no partnership
     * table and leaves every home at 0 partner points. Otherwise each home gets its cached owner's
     * partner points for the day, min(row, cap), from a plain read in this same view.
     */
    public HousingMarketSnapshot read(ReadSnapshot shared, LocalDate day, Policy proposedPolicy,
                                      Map<UUID, Integer> partnerRankCaps) throws SQLException {
        Objects.requireNonNull(day);
        Objects.requireNonNull(proposedPolicy);
        Objects.requireNonNull(partnerRankCaps);
        require(day.equals(days.today()), "Housing snapshot requires current economy day");
        try {
            Connection c = shared.connection(database);
            require(day.equals(days.dayAt(shared.capturedAt())), "Housing snapshot cutoff belongs to another economy day");
            HousingMarketSnapshot result = snapshot(c, day, shared.capturedAt(), proposedPolicy, partnerRankCaps);
            require(day.equals(days.today()), "Housing snapshot crossed economy day");
            return result;
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid housing market snapshot", failure);
        }
    }

    private HousingMarketSnapshot snapshot(Connection c, LocalDate day, Instant at, Policy proposed,
                                           Map<UUID, Integer> partnerRankCaps) throws SQLException {
        List<Integer> lock = query(c, "SELECT singleton_id FROM npc_housing_registry_lock ORDER BY singleton_id", 1,
                rs -> integer(rs, "singleton_id"));
        require(lock.equals(List.of(1)), "Housing admission lock missing or invalid");
        List<Policy> policies = query(c, "SELECT * FROM npc_housing_days WHERE economy_day=?", 1,
                HousingMarketRepository::policy, day.toString());
        Policy policy = policies.isEmpty() ? proposed : policies.getFirst();
        require(policy.equals(proposed), "Housing day policy conflict");
        List<HousingRegistry.Member> members = query(c,
                "SELECT * FROM npc_housing_members ORDER BY resident_id", 512, HousingRegistry::read);
        Map<UUID, HousingRegistry.Member> memberMap = new HashMap<>();
        for (var m : members) require(memberMap.put(m.residentId(), m) == null, "Duplicate housing member");
        List<PropertyRow> properties = query(c, "SELECT * FROM npc_properties ORDER BY property_id", 512,
                rs -> property(rs, backendId != null));
        Map<UUID, PropertyRow> propertyMap = new HashMap<>();
        for (var p : properties) require(propertyMap.put(p.id(), p) == null, "Duplicate housing property");
        List<Tenancy> tenancies = query(c, """
                SELECT t.* FROM npc_tenants t WHERE t.property_id IS NOT NULL OR t.slot IS NOT NULL
                  OR EXISTS (SELECT 1 FROM npc_housing_members h WHERE h.resident_id=t.tenant_id)
                ORDER BY t.tenant_id
                """, 2560, HousingMarketRepository::tenancy);
        Map<UUID, Tenancy> tenancyMap = new HashMap<>();
        Map<UUID, HashSet<Integer>> occupied = new HashMap<>();
        for (var t : tenancies) {
            require(tenancyMap.put(t.resident(), t) == null, "Duplicate housing tenancy");
            if (t.property() != null) {
                PropertyRow p = propertyMap.get(t.property());
                require(p != null && t.slot() < p.capacity(), "Housing occupancy outside property");
                require(occupied.computeIfAbsent(p.id(), key -> new HashSet<>()).add(t.slot()), "Duplicate housing slot");
            }
        }
        List<UUID> profiles = query(c, """
                SELECT p.resident_id,p.name,p.occupation FROM npc_housing_members h
                LEFT JOIN npc_resident_profiles p ON p.resident_id=h.resident_id ORDER BY h.resident_id
                """, 512, rs -> {
            UUID id = uuid(rs, "resident_id");
            String name = rs.getString("name");
            require(name != null && !name.isBlank() && name.length() <= 64
                    && name.codePoints().noneMatch(ch -> Character.isISOControl(ch) || ch == 0x00a7), "Invalid housing profile");
            ResidentProfile.Occupation.valueOf(rs.getString("occupation"));
            return id;
        });
        require(profiles.size() == members.size() && new HashSet<>(profiles).equals(memberMap.keySet()), "Housing profiles incomplete");

        String policyColumns = "d.server_limit,d.market_limit,d.property_limit,d.cooldown_days";
        List<Receipt> admissions = query(c, "SELECT m.*," + policyColumns + " FROM npc_housing_moves m "
                + "LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day WHERE m.economy_day=? ORDER BY m.request_id",
                512, rs -> receipts.readReceipt(rs, policy(rs)), day.toString());
        var admitted = new HashSet<UUID>();
        for (Receipt a : admissions) {
            require(a.request().day().equals(day) && a.policy().equals(policy)
                    && admitted.add(a.request().residentId()), "Invalid current housing admissions");
        }
        // One indexed latest-history lookup per member, not a scan of every historical move.
        List<Receipt> history = query(c, "SELECT m.*," + policyColumns + " FROM npc_housing_members h "
                + "JOIN npc_housing_moves m ON m.request_id=(SELECT prior.request_id FROM npc_housing_moves prior "
                + "WHERE prior.resident_id=h.resident_id ORDER BY prior.tenancy_revision_after DESC,prior.request_id LIMIT 1) "
                + "LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day ORDER BY h.resident_id",
                512, rs -> receipts.readReceipt(rs, policy(rs)));
        Map<UUID, Receipt> latest = new HashMap<>();
        for (Receipt a : history) require(memberMap.containsKey(a.request().residentId())
                && latest.put(a.request().residentId(), a) == null, "Invalid housing history membership");
        List<Receipt> chronological = query(c, "SELECT m.*," + policyColumns + " FROM npc_housing_members h "
                + "JOIN npc_housing_moves m ON m.request_id=(SELECT prior.request_id FROM npc_housing_moves prior "
                + "WHERE prior.resident_id=h.resident_id ORDER BY prior.economy_day DESC,prior.applied_at_millis DESC,"
                + "prior.request_id DESC LIMIT 1) "
                + "LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day ORDER BY h.resident_id",
                512, rs -> receipts.readReceipt(rs, policy(rs)));
        require(chronological.size() == latest.size(), "Housing receipt chronology disagrees with revision");
        for (Receipt a : chronological) require(a.equals(latest.get(a.request().residentId())),
                "Housing receipt chronology disagrees with revision");

        Map<UUID, Integer> advertising = new HashMap<>();
        List<Facilities> upgrades = query(c, "SELECT * FROM npc_upgrades ORDER BY property_id,upgrade_type", 1024, rs -> {
            UUID property = uuid(rs, "property_id");
            require(propertyMap.containsKey(property), "Housing upgrade property missing");
            UpgradeType type = UpgradeType.valueOf(rs.getString("upgrade_type"));
            int level = integer(rs, "level");
            require(level >= 0 && level <= 3, "Invalid housing upgrade level");
            Long incident = nullableLong(rs, "disabled_incident_id");
            require(incident == null || incident > 0, "Invalid disabled housing upgrade");
            if (type == UpgradeType.ADVERTISING) advertising.put(property, incident == null ? level : 0);
            return type == UpgradeType.FACILITIES ? new Facilities(property, level, incident == null ? level : 0)
                    : new Facilities(property, -1, -1);
        });
        Map<UUID, Facilities> facilities = new HashMap<>();
        for (Facilities f : upgrades) if (f.installed() >= 0)
            require(facilities.put(f.property(), f) == null, "Duplicate housing facilities");
        // Partnership rank perk: no read at all while it is off (empty caps).
        Map<UUID, Integer> partnerPoints = partnerRankCaps.isEmpty() ? Map.of()
                : PartnershipRepository.rankBonusByOwner(c, day, partnerRankCaps);
        List<Home> homes = new ArrayList<>();
        for (PropertyRow p : properties) if (p.enabled() && p.owner() != null && matchable(p)) {
            List<Integer> free = new ArrayList<>();
            var evicted = HousingMoveRepository.evictedSlots(c, days, p.id(), day, evictedSlotLockDays);
            for (int slot = 0; slot < p.capacity(); slot++)
                if (!occupied.getOrDefault(p.id(), new HashSet<>()).contains(slot) && !evicted.contains(slot)) free.add(slot);
            Facilities f = facilities.getOrDefault(p.id(), new Facilities(p.id(), 0, 0));
            homes.add(new Home(p.id(), p.owner(), p.world(), p.region(), p.market(), p.kind(), p.epoch(), p.revision(),
                    p.capacity(), p.reference(), p.asking(), p.condition(), f.installed(), f.effective(), free,
                    advertising.getOrDefault(p.id(), 0), partnerPoints.getOrDefault(p.owner(), 0)));
        }
        List<Participant> participants = new ArrayList<>();
        for (var m : members) {
            Tenancy t = tenancyMap.get(m.residentId());
            Receipt prior = latest.get(m.residentId());
            if (prior != null) require(t != null && (t.property() == null
                    ? t.revision() > prior.tenancyRevisionAfter() : t.revision() >= prior.tenancyRevisionAfter()),
                    "Housing tenancy history requires reconciliation");
            if (prior != null) {
                require(t.cooldown() != null && !t.cooldown().isBefore(prior.cooldownUntil()), "Housing move cooldown regressed");
                if (t.property() != null && t.revision() == prior.tenancyRevisionAfter())
                    require(t.property().equals(prior.request().propertyId()) && t.slot() == prior.request().slot(),
                            "Housing occupancy contradicts admission receipt");
            }
            if (t != null) require(t.tier() == m.policy().tier(), "Housing tier requires reconciliation");
            LocalDate cooldown = t == null || t.cooldown() == null ? day : t.cooldown();
            Status status = !m.enabled() ? Status.DISABLED : t != null && t.property() != null ? Status.OCCUPIED
                    : admitted.contains(m.residentId()) ? Status.ADMITTED_TODAY
                    : day.isBefore(m.availableSince()) ? Status.NOT_YET_AVAILABLE
                    : day.isBefore(cooldown) ? Status.COOLDOWN
                    : m.policy().tier() == HousingPolicy.Tier.PREMIUM ? Status.PREMIUM_HELD : Status.READY;
            HousingMoveRequest.Origin origin = null;
            if (status == Status.OCCUPIED && m.policy().tier() == HousingPolicy.Tier.PREMIUM && prior != null
                    && t.revision() == prior.tenancyRevisionAfter()) {
                PropertyRow source = propertyMap.get(t.property());
                if (source.enabled() && source.owner() != null && matchable(source) && source.world().equals(m.policy().worldId())
                        && source.market().equals(m.policy().marketId()) && source.kind().equals(m.policy().classKey()))
                    origin = new HousingMoveRequest.Origin(source.id(), source.owner(), source.epoch(), source.revision(),
                            t.slot(), prior.request().requestId());
            }
            participants.add(new Participant(m, t == null ? -1 : t.revision(), cooldown, status, origin));
        }
        return new HousingMarketSnapshot(day, at, policy, homes, participants, admissions);
    }

    /** Whether this home was enrolled under the configured backend (always true with no backend set). */
    private boolean matchable(PropertyRow p) {
        return backendId == null || backendId.equals(p.backend());
    }

    private static PropertyRow property(ResultSet rs, boolean withBackend) throws SQLException {
        UUID id = uuid(rs, "property_id"), world = uuid(rs, "world_uuid");
        byte[] ownerBytes = rs.getBytes("owner_uuid");
        UUID owner = ownerBytes == null ? null : Uuids.fromBytes(ownerBytes);
        String market = rs.getString("market_id"), kind = rs.getString("class_key"), region = rs.getString("region_id");
        HousingPolicy.key(market);
        HousingPolicy.key(kind);
        int epoch = integer(rs, "ownership_epoch"), capacity = integer(rs, "capacity"), condition = integer(rs, "condition_score");
        long revision = number(rs, "row_version"), reference = number(rs, "reference_rent_cents"), asking = number(rs, "asking_rent_cents");
        int enabled = integer(rs, "enabled");
        require(region != null && !region.isBlank() && region.length() <= 128 && epoch >= 0 && revision >= 0
                && revision < Long.MAX_VALUE && capacity >= 1 && capacity <= 4 && condition >= 0 && condition <= 100
                && reference > 0 && reference <= Integer.MAX_VALUE && asking > 0 && asking <= Integer.MAX_VALUE
                && enabled >= 0 && enabled <= 1, "Invalid housing snapshot property");
        return new PropertyRow(id, owner, world, region, market, kind, epoch, revision, capacity, reference, asking, condition,
                enabled == 1, withBackend ? rs.getString("backend_id") : null);
    }
    private static Tenancy tenancy(ResultSet rs) throws SQLException {
        UUID id = uuid(rs, "tenant_id");
        byte[] bytes = rs.getBytes("property_id");
        UUID property = bytes == null ? null : Uuids.fromBytes(bytes);
        Long rawSlot = nullableLong(rs, "slot");
        Integer slot = rawSlot == null ? null : Math.toIntExact(rawSlot);
        long revision = number(rs, "row_version");
        require((property == null) == (slot == null) && (slot == null || slot >= 0 && slot < 4)
                && revision >= 0 && revision < Long.MAX_VALUE, "Invalid housing snapshot tenancy");
        String cooldown = rs.getString("move_cooldown_until_day");
        return new Tenancy(id, property, slot, revision, HousingPolicy.Tier.valueOf(rs.getString("tier")),
                cooldown == null ? null : day(cooldown));
    }
    private static Policy policy(ResultSet rs) throws SQLException {
        return new Policy(integer(rs, "server_limit"), integer(rs, "market_limit"), integer(rs, "property_limit"),
                integer(rs, "cooldown_days"));
    }
    private static LocalDate day(String text) {
        LocalDate day = LocalDate.parse(text);
        if (!day.toString().equals(text) || day.getYear() < 1 || day.getYear() > 9999)
            throw new IllegalArgumentException("Invalid housing snapshot day");
        return day;
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException { return Uuids.fromBytes(rs.getBytes(column)); }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long n = rs.getLong(column);
        return rs.wasNull() ? null : n;
    }
    private static long number(ResultSet rs, String column) throws SQLException {
        Long n = nullableLong(rs, column);
        require(n != null, "Missing housing snapshot number");
        return n;
    }
    private static int integer(ResultSet rs, String column) throws SQLException { return Math.toIntExact(number(rs, column)); }
    private static void require(boolean valid, String message) throws SQLException { if (!valid) throw new SQLException(message); }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        require(c.getWarnings() == null && ps.getWarnings() == null && (rs == null || rs.getWarnings() == null),
                "Incomplete housing snapshot query");
    }
    private interface Row<T> { T read(ResultSet rs) throws SQLException; }
    private static <T> List<T> query(Connection c, String sql, int maximum, Row<T> row, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql + " LIMIT " + (maximum + 1) + " ROWS EXAMINED 65536")) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (true) {
                    warnings(c, ps, rs);
                    boolean next = rs.next();
                    warnings(c, ps, rs);
                    if (!next) return List.copyOf(result);
                    require(result.size() < maximum, "Housing snapshot exceeds query bound");
                    result.add(row.read(rs));
                }
            }
        }
    }
}
