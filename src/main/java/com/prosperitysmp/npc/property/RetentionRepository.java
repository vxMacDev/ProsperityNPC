package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.property.RetentionAssessment.Case;
import com.prosperitysmp.npc.property.RetentionAssessment.Outcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Worker-only, bounded, read-only assessment of recorded admissions and departures.
 * No reconstruction of legacy tenancies and no ARM, wallet, score or premium authorization.
 * Uses the configured admission clock; changing that policy requires historical reconciliation.
 */
public final class RetentionRepository {
    private final Database database;
    private final Clock clock;
    private final ZoneId zone;
    private final int resetHour;
    private record Episode(UUID resident, long revision) { }
    private record Home(UUID owner, int epoch) { }
    private record Tenant(UUID property, Integer slot, long revision) { }
    private record Ownership(int epoch, UUID before, UUID after, Instant at) { }

    public RetentionRepository(Database database, Clock clock, ZoneId zone, int resetHour) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
        new EconomyClock(clock, zone, resetHour);
        this.resetHour = resetHour;
    }

    /** Cohort [admittedFrom,capturedAt]; explicit elapsed-day horizon, never an implicit success default. */
    public RetentionAssessment read(UUID owner, Instant admittedFrom, int horizonDays) throws SQLException {
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, clock)) { return read(snapshot, owner, admittedFrom, horizonDays); }
    }

    /** Internal composition on the exact database and cutoff that supplied the other service evidence. */
    public RetentionAssessment read(ReadSnapshot snapshot, UUID owner, Instant admittedFrom, int horizonDays) throws SQLException {
        return readInTransaction(snapshot.connection(database), snapshot.capturedAt(), owner, admittedFrom, horizonDays);
    }

    /** Trusted admission revalidation; caller owns the transaction and its captured cutoff. */
    public RetentionAssessment readInTransaction(Connection c, Instant at, UUID owner, Instant admittedFrom, int horizonDays) throws SQLException {
        Objects.requireNonNull(owner); Objects.requireNonNull(admittedFrom);
        ReadSnapshot.requireTransaction(c);
        try {
            new RetentionAssessment(owner, at, admittedFrom, horizonDays, List.of());
            Clock frozen = Clock.fixed(at, ZoneOffset.UTC);
            EconomyClock days = new EconomyClock(frozen, zone, resetHour);
            var decoder = new HousingMoveRepository(database, frozen, zone, resetHour);
            var admissions = new ArrayList<Receipt>();
            String range = "m.owner_uuid=? AND m.economy_day>=? AND m.economy_day<=?";
            try (PreparedStatement ps = prepare(c, "SELECT m.*,d.server_limit,d.market_limit,d.property_limit,d.cooldown_days "
                    + "FROM npc_housing_moves m LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day WHERE "
                    + range + " ORDER BY m.economy_day,m.request_id LIMIT 4097", owner,
                    days.dayAt(admittedFrom).toString(), days.dayAt(at).toString()); ResultSet rs = ps.executeQuery()) {
                int scanned = 0;
                while (next(c, ps, rs)) {
                    require(++scanned <= RetentionAssessment.MAX_CASES, "Retention admission cohort exceeds bound");
                    Policy p = new Policy(integer(rs, "server_limit"), integer(rs, "market_limit"),
                            integer(rs, "property_limit"), integer(rs, "cooldown_days"));
                    Receipt a = decoder.readReceipt(rs, p);
                    require(owner.equals(a.request().ownerId()), "Retention origin owner mismatch");
                    // Validate receipts before filtering same-day boundary candidates.
                    if (!a.appliedAt().isBefore(admittedFrom)) admissions.add(a);
                }
            }
            if (admissions.isEmpty()) return new RetentionAssessment(owner, at, admittedFrom, horizonDays, List.of());
            // Batch by unique identities; no per-resident query loop or registry/writer lock.
            var propertyIds = new LinkedHashSet<UUID>();
            var residentIds = new LinkedHashSet<UUID>();
            var episodes = new LinkedHashSet<Episode>();
            for (Receipt a : admissions) {
                propertyIds.add(a.request().propertyId()); residentIds.add(a.request().residentId());
                require(episodes.add(new Episode(a.request().residentId(), a.tenancyRevisionAfter())), "Duplicate retention episode");
            }
            Map<UUID, Home> homes = homes(c, propertyIds.stream().toList());
            Map<UUID, List<Ownership>> histories = histories(c, propertyIds.stream().toList(), at, homes);
            Map<UUID, Tenant> tenants = tenants(c, residentIds.stream().toList());
            Map<Episode, TenancyDeparture> departures = departures(c, episodes.stream().toList());
            Map<UUID, List<Receipt>> residentAdmissions = residentAdmissions(c, residentIds.stream().toList(), decoder);
            var cases = new ArrayList<Case>();
            for (Receipt a : admissions) cases.add(classify(a, horizonDays, at,
                    homes.get(a.request().propertyId()), histories.get(a.request().propertyId()),
                    tenants.get(a.request().residentId()), departures.get(new Episode(a.request().residentId(), a.tenancyRevisionAfter())),
                    residentAdmissions.get(a.request().residentId())));
            return new RetentionAssessment(owner, at, admittedFrom, horizonDays, cases);
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid retention evidence", failure);
        }
    }

    private static Case classify(Receipt a, int days, Instant at, Home home, List<Ownership> history,
                                 Tenant tenant, TenancyDeparture departure, List<Receipt> admissions) throws SQLException {
        var r = a.request();
        Instant horizon = a.appliedAt().plus(Duration.ofDays(days));
        if (home == null || history == null || history.isEmpty() || tenant == null)
            return new Case(a, Outcome.EVIDENCE_GAP, departure, null, null);
        require(home.epoch() >= r.ownershipEpoch(), "Retention ownership epoch regressed");
        require(tenant.revision() >= a.tenancyRevisionAfter(), "Retention occupancy revision regressed");
        require(admissions != null, "Retention resident admission history missing");
        int position = java.util.Collections.binarySearch(admissions, a,
                java.util.Comparator.comparingLong(Receipt::tenancyRevisionAfter));
        require(position >= 0 && admissions.get(position).equals(a), "Retention admission missing from resident history");
        require(tenant.revision() >= admissions.getLast().tenancyRevisionAfter(), "Retention resident admission exceeds current revision");
        if (departure != null && position + 1 < admissions.size()) {
            Receipt successor = admissions.get(position + 1);
            require(successor.tenancyRevisionAfter() > departure.occupancyRevisionAfter()
                    && !successor.appliedAt().isBefore(departure.departedAt()), "Retention departure overlaps successor admission");
        }
        // Histories are validated once per property; epoch is now an exact list index.
        Ownership original = history.get(r.ownershipEpoch());
        Instant handover = r.ownershipEpoch() + 1 < history.size() ? history.get(r.ownershipEpoch() + 1).at() : null;
        require(r.ownerId().equals(original.after()) && !original.at().isAfter(a.appliedAt())
                && (handover == null || !handover.isBefore(a.appliedAt())), "Retention admission ownership/time mismatch");
        boolean occupancyProven;
        if (departure == null) {
            occupancyProven = tenant.revision() == a.tenancyRevisionAfter()
                    && r.propertyId().equals(tenant.property()) && Objects.equals(tenant.slot(), r.slot());
        } else {
            require(departure.residentId().equals(r.residentId()) && departure.propertyId().equals(r.propertyId())
                    && departure.slot() == r.slot() && departure.occupancyRevisionBefore() == a.tenancyRevisionAfter()
                    && !departure.departedAt().isBefore(a.appliedAt()) && !departure.departedAt().isAfter(at), "Retention departure binding/time mismatch");
            require(departure.ownershipEpoch() < history.size(), "Retention departure ownership mismatch");
            Ownership departureOwner = history.get(departure.ownershipEpoch());
            require(Objects.equals(departureOwner.after(), departure.ownerAtDeparture())
                    && !departureOwner.at().isAfter(departure.departedAt()), "Retention departure ownership mismatch");
            if (departure.ownershipEpoch() + 1 < history.size())
                require(!history.get(departure.ownershipEpoch() + 1).at().isBefore(departure.departedAt()),
                        "Retention departure predates its recorded ownership epoch");
            require(tenant.revision() >= departure.occupancyRevisionAfter(), "Retention departure exceeds current revision");
            require(tenant.revision() != departure.occupancyRevisionAfter() || tenant.property() == null,
                    "Retention departure revision still occupied");
            occupancyProven = true;
        }
        var occupancy = tenant.property() == null ? null : new RetentionAssessment.Occupancy(tenant.property(), tenant.slot(), tenant.revision());
        if (!occupancyProven) return new Case(a, Outcome.EVIDENCE_GAP, departure, handover, occupancy);
        if (horizon.isAfter(at)) return new Case(a, Outcome.NOT_MATURED, departure, handover, occupancy);
        Instant end = departure != null && departure.departedAt().isBefore(horizon) ? departure.departedAt() : horizon;
        if (handover != null && !handover.isAfter(end)) return new Case(a, Outcome.HANDOVER_REVIEW, departure, handover, occupancy);
        return new Case(a, departure != null && departure.departedAt().isBefore(horizon) ? Outcome.DEPARTED : Outcome.RETAINED,
                departure, handover, occupancy);
    }

    private static Map<UUID, Home> homes(Connection c, List<UUID> ids) throws SQLException {
        var result = new HashMap<UUID, Home>();
        try (PreparedStatement ps = prepare(c, "SELECT property_id,owner_uuid,ownership_epoch FROM npc_properties WHERE property_id IN (" + marks(ids.size()) + ")", ids.toArray());
             ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                int epoch = integer(rs, "ownership_epoch"); require(epoch >= 0, "Invalid retention property epoch");
                require(result.put(uuid(rs, "property_id", false), new Home(uuid(rs, "owner_uuid", true), epoch)) == null, "Duplicate retention property");
            }
        }
        return result;
    }
    private static Map<UUID, List<Ownership>> histories(Connection c, List<UUID> ids, Instant at,
                                                      Map<UUID, Home> homes) throws SQLException {
        var result = new HashMap<UUID, List<Ownership>>();
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_property_ownership WHERE property_id IN (" + marks(ids.size())
                + ") ORDER BY property_id,ownership_epoch,id LIMIT 16385", ids.toArray()); ResultSet rs = ps.executeQuery()) {
            int count = 0;
            while (next(c, ps, rs)) {
                require(++count <= 16384, "Retention ownership history exceeds bound");
                int epoch = integer(rs, "ownership_epoch"); require(epoch >= 0, "Invalid retention history epoch");
                Instant observed = rs.getObject("observed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
                RetentionAssessment.time(observed); require(!observed.isAfter(at), "Retention ownership observation is in future");
                var h = new Ownership(epoch, uuid(rs, "old_owner_uuid", true), uuid(rs, "new_owner_uuid", true), observed);
                var list = result.computeIfAbsent(uuid(rs, "property_id", false), ignored -> new ArrayList<>());
                require(list.isEmpty() || list.getLast().epoch() < epoch, "Duplicate retention ownership epoch");
                list.add(h);
            }
        }
        for (var entry : result.entrySet()) {
            List<Ownership> list = entry.getValue();
            boolean complete = list.getFirst().epoch() == 0 && list.getFirst().before() == null;
            Ownership previous = null;
            for (Ownership h : list) {
                if (previous != null) {
                    complete &= h.epoch() == previous.epoch() + 1;
                    require(!h.at().isBefore(previous.at()) && Objects.equals(h.before(), previous.after()),
                            "Retention ownership chronology conflicts");
                }
                previous = h;
            }
            Home home = homes.get(entry.getKey());
            require(home != null && previous.epoch() == home.epoch() && Objects.equals(previous.after(), home.owner()),
                    "Retention ownership tip disagrees with property");
            if (!complete) entry.setValue(List.of());
        }
        return result;
    }
    private static Map<UUID, Tenant> tenants(Connection c, List<UUID> ids) throws SQLException {
        var result = new HashMap<UUID, Tenant>();
        try (PreparedStatement ps = prepare(c, "SELECT tenant_id,property_id,slot,row_version FROM npc_tenants WHERE tenant_id IN (" + marks(ids.size()) + ")", ids.toArray());
             ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                UUID property = uuid(rs, "property_id", true);
                Integer slot = rs.getObject("slot") == null ? null : integer(rs, "slot");
                long revision = number(rs, "row_version");
                require(revision >= 0 && (property == null) == (slot == null) && (slot == null || slot >= 0 && slot <= 3), "Invalid retention tenant");
                require(result.put(uuid(rs, "tenant_id", false), new Tenant(property, slot, revision)) == null, "Duplicate retention tenant");
            }
        }
        return result;
    }
    private static Map<Episode, TenancyDeparture> departures(Connection c, List<Episode> keys) throws SQLException {
        var result = new HashMap<Episode, TenancyDeparture>();
        var args = new ArrayList<Object>();
        for (Episode key : keys) { args.add(key.resident()); args.add(key.revision()); }
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_tenancy_departures WHERE (resident_id,occupancy_revision_before) IN ("
                + String.join(",", java.util.Collections.nCopies(keys.size(), "(?,?)")) + ")", args.toArray()); ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                TenancyDeparture d = TenantRepository.readDeparture(rs);
                require(result.put(new Episode(d.residentId(), d.occupancyRevisionBefore()), d) == null, "Duplicate retention departure");
            }
        }
        return result;
    }

    private static Map<UUID, List<Receipt>> residentAdmissions(Connection c, List<UUID> ids,
                                                              HousingMoveRepository decoder) throws SQLException {
        var result = new HashMap<UUID, List<Receipt>>();
        try (PreparedStatement ps = prepare(c, "SELECT m.*,d.server_limit,d.market_limit,d.property_limit,d.cooldown_days "
                + "FROM npc_housing_moves m LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day "
                + "WHERE m.resident_id IN (" + marks(ids.size()) + ") ORDER BY m.resident_id,m.tenancy_revision_after,m.request_id LIMIT 16385", ids.toArray());
             ResultSet rs = ps.executeQuery()) {
            int count = 0;
            while (next(c, ps, rs)) {
                require(++count <= 16384, "Retention resident admission history exceeds bound");
                Receipt a = decoder.readReceipt(rs, new Policy(integer(rs, "server_limit"), integer(rs, "market_limit"),
                        integer(rs, "property_limit"), integer(rs, "cooldown_days")));
                var list = result.computeIfAbsent(a.request().residentId(), ignored -> new ArrayList<>());
                require(list.isEmpty() || list.getLast().tenancyRevisionAfter() < a.tenancyRevisionAfter()
                        && !list.getLast().appliedAt().isAfter(a.appliedAt()), "Retention resident admissions conflict");
                list.add(a);
            }
        }
        return result;
    }

    private static String marks(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    private static UUID uuid(ResultSet rs, String name, boolean nullable) throws SQLException {
        byte[] bytes = rs.getBytes(name);
        return nullable && bytes == null ? null : Uuids.fromBytes(bytes);
    }
    private static long number(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name); require(!rs.wasNull(), "Missing retention number: " + name); return value;
    }
    private static int integer(ResultSet rs, String name) throws SQLException { return Math.toIntExact(number(rs, name)); }
    private static void require(boolean yes, String message) throws SQLException { if (!yes) throw new SQLException(message); }
    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id)); else ps.setObject(i + 1, args[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException failure) { ps.close(); throw failure; }
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        require(c.getWarnings() == null && ps.getWarnings() == null && (rs == null || rs.getWarnings() == null), "Incomplete retention read");
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs); boolean value = rs.next(); warnings(c, ps, rs); return value;
    }
}
