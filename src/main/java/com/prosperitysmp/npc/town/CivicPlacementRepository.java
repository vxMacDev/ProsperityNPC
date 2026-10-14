package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** JDBC-only placement sidecar. Caller owns staff authorization and off-main-thread scheduling. */
public final class CivicPlacementRepository {
    private static final int MAX_DISPLAY = 100;
    private static final int MAX_WORLDS = 128;
    private static final String COLUMNS = "resident_id, world_uuid, district_id, enabled, revision";
    private final Database database;

    public CivicPlacementRepository(Database database) {
        this.database = Objects.requireNonNull(database);
    }

    /**
     * Places or re-enables one existing identity. Returns false for an identical enabled placement.
     * The stored district is the matched ACTIVE graph's region_id, not caller casing or an alias.
     * Generic/personal anchors and occupation-specific work anchors follow ResidentRoutePlanner.
     * Current occupancy excludes placement regardless of state; retained fully vacant rows do not.
     * Partial/corrupt occupancy is rejected rather than interpreted as vacancy.
     */
    public boolean place(UUID resident, UUID world, String district) throws SQLException {
        Objects.requireNonNull(resident);
        Objects.requireNonNull(world);
        CivicPlacement.requireDistrict(district);
        return transaction(c -> {
            // Housing admission locks tenant before profile. REPEATABLE READ also locks an absent
            // tenant's PK gap, fencing legacy inserts that do not take the profile lock.
            try (PreparedStatement ps = prepare(c,
                    "SELECT property_id,slot FROM npc_tenants WHERE tenant_id=? FOR UPDATE")) {
                ps.setBytes(1, Uuids.toBytes(resident));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] property = rs.getBytes("property_id");
                        int slot = rs.getInt("slot");
                        boolean noSlot = rs.wasNull();
                        if ((property == null) != noSlot || (property != null && property.length != 16)
                                || (!noSlot && (slot < 0 || slot >= 4))) {
                            throw new SQLException("Invalid tenant occupancy");
                        }
                        if (property != null) throw new IllegalArgumentException("Resident is currently occupied");
                    }
                }
            }
            String occupation = requireProfile(c, resident);
            CivicPlacement prior = readLocked(c, resident);
            String canonical = requireGraph(c, resident, world, district, occupation);
            if (prior != null && prior.enabled() && prior.worldUuid().equals(world)
                    && prior.districtId().equals(canonical)) return false;

            long revision = prior == null ? 1 : nextRevision(prior.revision());
            try (PreparedStatement ps = prepare(c, prior == null ? """
                    INSERT INTO npc_civic_placements(resident_id,world_uuid,district_id,enabled,revision)
                    VALUES (?,?,?,TRUE,?)
                    """ : """
                    UPDATE npc_civic_placements SET world_uuid=?,district_id=?,enabled=TRUE,revision=?
                    WHERE resident_id=? AND revision=?
                    """)) {
                if (prior == null) {
                    ps.setBytes(1, Uuids.toBytes(resident));
                    ps.setBytes(2, Uuids.toBytes(world));
                    ps.setString(3, canonical);
                    ps.setLong(4, revision);
                } else {
                    ps.setBytes(1, Uuids.toBytes(world));
                    ps.setString(2, canonical);
                    ps.setLong(3, revision);
                    ps.setBytes(4, Uuids.toBytes(resident));
                    ps.setLong(5, prior.revision());
                }
                requireOne(ps.executeUpdate());
            }
            return true;
        });
    }

    /** Keeps authored location/history. Missing or already disabled placement returns false. */
    public boolean disable(UUID resident) throws SQLException {
        Objects.requireNonNull(resident);
        return transaction(c -> {
            CivicPlacement prior = readLocked(c, resident);
            if (prior == null || !prior.enabled()) return false;
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_civic_placements SET enabled=FALSE,revision=? WHERE resident_id=? AND revision=?
                    """)) {
                ps.setLong(1, nextRevision(prior.revision()));
                ps.setBytes(2, Uuids.toBytes(resident));
                ps.setLong(3, prior.revision());
                requireOne(ps.executeUpdate());
            }
            return true;
        });
    }

    /**
     * Immutable committed snapshot in stable resident UUID order, capped at 100 rows and 128 worlds.
     * Nonpositive limits/empty worlds return empty. Current occupancy wins regardless of state.
     * Partial occupancy is excluded too; only retained rows with both property and slot NULL are vacant.
     * A later admission can invalidate this snapshot; the renderer must reconcile identity across sources.
     */
    public List<CivicPlacement> listForDisplay(Set<UUID> worlds, int limit) throws SQLException {
        Objects.requireNonNull(worlds);
        if (worlds.size() > MAX_WORLDS || worlds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid placement world batch");
        }
        List<UUID> sorted = worlds.stream().sorted().toList();
        if (limit <= 0 || sorted.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(sorted.size(), "?"));
        List<CivicPlacement> result = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT " + COLUMNS + " FROM npc_civic_placements p WHERE p.enabled=TRUE AND p.world_uuid IN ("
                        + placeholders + ") AND NOT EXISTS (SELECT 1 FROM npc_tenants t WHERE t.tenant_id=p.resident_id "
                        + "AND (t.property_id IS NOT NULL OR t.slot IS NOT NULL)) "
                        + "ORDER BY p.resident_id LIMIT ?")) {
            for (int i = 0; i < sorted.size(); i++) ps.setBytes(i + 1, Uuids.toBytes(sorted.get(i)));
            ps.setInt(sorted.size() + 1, Math.min(limit, MAX_DISPLAY));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readPlacement(rs));
            }
        }
        return List.copyOf(result);
    }

    private static String requireProfile(Connection c, UUID resident) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT occupation FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(resident));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Resident profile does not exist: " + resident);
                try {
                    return Occupation.valueOf(rs.getString(1)).name().toLowerCase(Locale.ROOT);
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new SQLException("Invalid stored resident occupation", invalid);
                }
            }
        }
    }

    private static String requireGraph(Connection c, UUID resident, UUID world, String district, String occupation)
            throws SQLException {
        UUID graph;
        try (PreparedStatement ps = prepare(c, """
                SELECT graph_id FROM npc_nav_graphs
                WHERE world_uuid=? AND region_id=? AND status='ACTIVE'
                ORDER BY version DESC,graph_id LIMIT 1
                """)) {
            ps.setBytes(1, Uuids.toBytes(world));
            ps.setString(2, district);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("District has no ACTIVE navigation graph in this world");
                }
                graph = Uuids.fromBytes(rs.getBytes("graph_id"));
            }
        }
        String canonical;
        // Discover via the district index, then lock by PK like publish does. Locking the secondary
        // index first would invert publish's PK -> status-index order during an ACTIVE transition.
        try (PreparedStatement ps = prepare(c,
                "SELECT world_uuid,region_id,status FROM npc_nav_graphs WHERE graph_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(graph));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"ACTIVE".equals(rs.getString("status"))
                        || !world.equals(Uuids.fromBytes(rs.getBytes("world_uuid")))
                        || !district.equalsIgnoreCase(rs.getString("region_id"))) {
                    throw new IllegalArgumentException("Navigation graph changed before placement");
                }
                canonical = rs.getString("region_id");
                CivicPlacement.requireDistrict(canonical);
            }
        }
        // Current bounded reads under the graph lock serialize validation with draft edits/publication.
        for (String tag : List.of("home", "work", "market", "leisure")) {
            try (PreparedStatement ps = prepare(c, """
                    SELECT t.node_id FROM npc_nav_nodes n JOIN npc_nav_tags t ON t.node_id=n.node_id
                    WHERE n.graph_id=? AND (BINARY t.tag=? OR BINARY t.tag=? OR BINARY t.tag=?)
                    ORDER BY t.node_id,t.tag LIMIT 1 FOR UPDATE
                    """)) {
                ps.setBytes(1, Uuids.toBytes(graph));
                ps.setString(2, tag);
                ps.setString(3, tag + ':' + resident);
                ps.setString(4, tag.equals("work") ? "work:" + occupation : tag);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("ACTIVE graph is missing a " + tag + " anchor");
                }
            }
        }
        return canonical;
    }

    private static CivicPlacement readLocked(Connection c, UUID resident) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT " + COLUMNS + " FROM npc_civic_placements WHERE resident_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(resident));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readPlacement(rs) : null;
            }
        }
    }

    private static CivicPlacement readPlacement(ResultSet rs) throws SQLException {
        try {
            int enabled = rs.getInt("enabled");
            if (enabled != 0 && enabled != 1) throw new IllegalArgumentException("Invalid enabled flag");
            return new CivicPlacement(Uuids.fromBytes(rs.getBytes("resident_id")),
                    Uuids.fromBytes(rs.getBytes("world_uuid")), rs.getString("district_id"), enabled == 1,
                    rs.getLong("revision"));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid stored civic placement", invalid);
        }
    }

    private static long nextRevision(long revision) throws SQLException {
        if (revision == Long.MAX_VALUE) throw new SQLException("Civic placement revision exhausted");
        return revision + 1;
    }

    private static void requireOne(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("Civic placement write did not affect exactly one row");
    }

    private <T> T transaction(Transaction<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            // Hikari resets connection settings on close; never enable autocommit after failed rollback.
        }
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run(Connection connection) throws SQLException;
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException failure) {
            ps.close();
            throw failure;
        }
    }
}
