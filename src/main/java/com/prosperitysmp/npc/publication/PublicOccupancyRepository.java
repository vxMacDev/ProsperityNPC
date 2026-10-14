package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Row;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Worker-only approved-scope read. No publication, authorization, or source-state mutation. */
public final class PublicOccupancyRepository {
    /** Private publication evidence only: never serialize these records as public output. */
    public record TenantRevision(UUID propertyId, int slot, long revision) { }
    public record Evidence(PublicOccupancySnapshot snapshot, Instant capturedAt,
                           Map<UUID, Long> propertyRevisions, Map<UUID, TenantRevision> tenantRevisions) {
        public Evidence {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(capturedAt);
            propertyRevisions = Map.copyOf(propertyRevisions);
            tenantRevisions = Map.copyOf(tenantRevisions);
        }
    }
    private final Database database;
    private final Clock clock;

    public PublicOccupancyRepository(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
    }

    public PublicOccupancySnapshot read(List<Approval> approvals) throws SQLException {
        return readEvidence(approvals).snapshot();
    }

    public Evidence readEvidence(List<Approval> approvals) throws SQLException {
        List<Approval> scope = scope(approvals);
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, clock)) {
            return readEvidence(snapshot, scope);
        }
    }

    /** Borrows one caller-owned snapshot, never committing, closing, or changing its transaction. */
    public PublicOccupancySnapshot read(ReadSnapshot snapshot, List<Approval> approvals) throws SQLException {
        return readEvidence(snapshot, approvals).snapshot();
    }

    /** Internal evidence shares exactly the same transaction and capture as the public projection. */
    public Evidence readEvidence(ReadSnapshot snapshot, List<Approval> approvals) throws SQLException {
        List<Approval> scope = scope(approvals);
        try {
            Connection c = snapshot.connection(database);
            require(c.getWarnings() == null);
            if (scope.isEmpty()) return new Evidence(
                    PublicOccupancySnapshot.build(snapshot.capturedAt(), List.of(), scope),
                    snapshot.capturedAt(), Map.of(), Map.of());
            String placeholders = String.join(",", Collections.nCopies(scope.size(), "?"));
            var approved = new HashMap<UUID, Approval>();
            scope.forEach(a -> approved.put(a.propertyId(), a));
            var properties = new HashMap<UUID, Property>();
            var propertyRevisions = new HashMap<UUID, Long>();
            String propertySql = "SELECT property_id, world_uuid, region_id, market_id, class_key, capacity, "
                    + "reference_rent_cents, asking_rent_cents, owner_uuid, ownership_epoch, condition_score, enabled, row_version "
                    + "FROM npc_properties WHERE property_id IN (" + placeholders + ") ORDER BY property_id LIMIT 513";
            try (PreparedStatement ps = prepare(c, propertySql, scope); ResultSet rs = ps.executeQuery()) {
                warnings(c, ps, rs);
                while (rs.next()) {
                    require(properties.size() < scope.size());
                    Property p = property(rs);
                    propertyRevisions.put(p.propertyId(), revision(rs));
                    Approval a = approved.get(p.propertyId());
                    require(a != null && a.worldId().equals(p.worldUuid()) && a.regionId().equalsIgnoreCase(p.regionId())
                            && properties.putIfAbsent(p.propertyId(), p) == null);
                    warnings(c, ps, rs);
                }
                warnings(c, ps, rs);
            }
            require(properties.size() == scope.size());
            var slots = new HashMap<UUID, HashSet<Integer>>();
            var tenantIds = new HashSet<UUID>();
            var tenantRevisions = new HashMap<UUID, TenantRevision>();
            String tenantSql = "SELECT tenant_id, property_id, slot, row_version FROM npc_tenants WHERE property_id IN ("
                    + placeholders + ") ORDER BY property_id, slot, tenant_id LIMIT 2049";
            try (PreparedStatement ps = prepare(c, tenantSql, scope); ResultSet rs = ps.executeQuery()) {
                warnings(c, ps, rs);
                while (rs.next()) {
                    require(tenantIds.size() < scope.size() * 4);
                    UUID tenant = uuid(rs, "tenant_id");
                    UUID property = uuid(rs, "property_id");
                    int slot = integer(rs, "slot");
                    long revision = revision(rs);
                    Property p = properties.get(property);
                    require(p != null && tenantIds.add(tenant) && slot >= 0 && slot < p.capacity()
                            && slots.computeIfAbsent(property, ignored -> new HashSet<>()).add(slot));
                    tenantRevisions.put(tenant, new TenantRevision(property, slot, revision));
                    warnings(c, ps, rs);
                }
                warnings(c, ps, rs);
            }
            var rows = new ArrayList<Row>();
            for (Property p : properties.values()) {
                var occupied = slots.get(p.propertyId());
                rows.add(new Row(p, occupied == null ? 0 : occupied.size()));
            }
            require(c.getWarnings() == null);
            return new Evidence(PublicOccupancySnapshot.build(snapshot.capturedAt(), rows, scope),
                    snapshot.capturedAt(), propertyRevisions, tenantRevisions);
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid public occupancy read", failure);
        }
    }

    private static List<Approval> scope(List<Approval> approvals) throws SQLException {
        require(approvals != null && approvals.size() <= 512);
        var copy = new ArrayList<Approval>();
        var ids = new HashSet<UUID>();
        var scopes = new HashMap<UUID, HashSet<String>>();
        var publicByWorld = new HashMap<UUID, String>();
        var worldByPublic = new HashMap<String, UUID>();
        for (Approval a : approvals) {
            require(a != null && copy.size() < 512 && ids.add(a.propertyId()));
            require(scopes.computeIfAbsent(a.worldId(), ignored -> new HashSet<>()).add(a.regionId()));
            String alias = publicByWorld.putIfAbsent(a.worldId(), a.publicWorldKey());
            UUID world = worldByPublic.putIfAbsent(a.publicWorldKey(), a.worldId());
            require((alias == null || alias.equals(a.publicWorldKey())) && (world == null || world.equals(a.worldId())));
            copy.add(a);
        }
        return List.copyOf(copy);
    }

    private static Property property(ResultSet rs) throws SQLException {
        byte[] owner = rs.getBytes("owner_uuid");
        int enabled = integer(rs, "enabled");
        require(enabled == 0 || enabled == 1);
        Property p = new Property(uuid(rs, "property_id"), uuid(rs, "world_uuid"), text(rs, "region_id", 128),
                text(rs, "market_id", 64), text(rs, "class_key", 64), integer(rs, "capacity"),
                integer(rs, "reference_rent_cents"), integer(rs, "asking_rent_cents"),
                owner == null ? null : Uuids.fromBytes(owner), integer(rs, "ownership_epoch"),
                integer(rs, "condition_score"), enabled == 1);
        require(p.ownershipEpoch() >= 0 && p.referenceRentCents() >= 0 && p.askingRentCents() >= 0
                && p.conditionScore() >= 0 && p.conditionScore() <= 100);
        new Row(p, 0);
        return p;
    }

    private static String text(ResultSet rs, String column, int maximum) throws SQLException {
        String value = rs.getString(column);
        require(value != null && !value.isBlank() && value.length() <= maximum);
        return value;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return Uuids.fromBytes(rs.getBytes(column));
    }

    private static long revision(ResultSet rs) throws SQLException {
        long value = rs.getLong("row_version");
        require(!rs.wasNull() && value >= 0);
        return value;
    }

    private static int integer(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        require(!rs.wasNull() && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE);
        return (int) value;
    }

    private static PreparedStatement prepare(Connection c, String sql, List<Approval> approvals) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            for (int i = 0; i < approvals.size(); i++) ps.setBytes(i + 1, Uuids.toBytes(approvals.get(i).propertyId()));
            return ps;
        } catch (SQLException | RuntimeException | Error failure) {
            try { ps.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        require(c.getWarnings() == null && ps.getWarnings() == null && rs.getWarnings() == null);
    }

    private static void require(boolean valid) throws SQLException {
        if (!valid) throw new SQLException("Invalid public occupancy read");
    }
}
