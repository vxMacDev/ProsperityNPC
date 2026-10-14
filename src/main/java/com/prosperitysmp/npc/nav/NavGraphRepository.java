package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for district navigation graphs (§10.1): draft → validate → publish,
 * versioned and district-scoped. A scan writes a {@code DRAFT}; publishing flips it
 * {@code ACTIVE} and archives the previously active graph for that world+region, so
 * a material scan can never silently replace the live graph.
 */
public final class NavGraphRepository {

    private static final Set<String> ROUTE_TAGS = Set.of("home", "work", "market", "leisure");
    private static final int MAX_TAGS_PER_NODE = 8;

    public record GraphInfo(UUID graphId, UUID worldUuid, String regionId, String palette,
                            int version, String status, int nodeCount, int edgeCount) {
    }

    private final Database database;

    public NavGraphRepository(Database database) {
        this.database = database;
    }

    /** Explicit routing metadata only: never evidence of ownership or permission to enter. */
    public static boolean isValidDraftTag(String tag) {
        if (tag == null || tag.length() > 64) return false;
        if (tag.equals("spawn") || ROUTE_TAGS.contains(tag)) return true;
        int colon = tag.indexOf(':');
        if (colon < 0 || !ROUTE_TAGS.contains(tag.substring(0, colon))) return false;
        String suffix = tag.substring(colon + 1);
        if (tag.startsWith("work:")) {
            for (Occupation occupation : Occupation.values()) {
                if (suffix.equals(occupation.name().toLowerCase(Locale.ROOT))) return true;
            }
        }
        try {
            return suffix.length() == 36 && UUID.fromString(suffix).toString().equals(suffix);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * Adds/removes an authored tag on a DRAFT. Returns false for an idempotent repeat.
     * Invalid tags, missing/mismatched nodes, non-DRAFT graphs and excess tags are rejected
     * with IllegalArgumentException. The graph lock serializes edits with publish.
     */
    public boolean setDraftTag(UUID graphId, long nodeId, String tag, boolean add) throws SQLException {
        if (graphId == null || nodeId <= 0 || !isValidDraftTag(tag)) {
            throw new IllegalArgumentException("Invalid graph, node or route tag.");
        }
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = timedStatement(c,
                        "SELECT status FROM npc_nav_graphs WHERE graph_id = ? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !"DRAFT".equals(rs.getString(1))) {
                            throw new IllegalArgumentException("Only an existing DRAFT graph can be tagged.");
                        }
                    }
                }
                try (PreparedStatement ps = timedStatement(c,
                        "SELECT node_id FROM npc_nav_nodes WHERE graph_id = ? AND node_id = ? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    ps.setLong(2, nodeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Node does not belong to this graph.");
                    }
                }
                int count;
                boolean present;
                try (PreparedStatement ps = timedStatement(c,
                        "SELECT COUNT(*), COALESCE(MAX(tag = ?), 0) FROM npc_nav_tags WHERE node_id = ?")) {
                    ps.setString(1, tag);
                    ps.setLong(2, nodeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        count = rs.getInt(1);
                        present = rs.getBoolean(2);
                    }
                }
                if (present == add) {
                    c.commit();
                    return false;
                }
                if (add && count >= MAX_TAGS_PER_NODE) {
                    throw new IllegalArgumentException("A node can have at most 8 tags.");
                }
                try (PreparedStatement ps = timedStatement(c, add
                        ? "INSERT INTO npc_nav_tags (node_id, tag) VALUES (?, ?)"
                        : "DELETE FROM npc_nav_tags WHERE node_id = ? AND tag = ?")) {
                    ps.setLong(1, nodeId);
                    ps.setString(2, tag);
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Nearest standing node within 1.5 blocks in 3D, in the caller's captured world only. */
    public Long nearestTagNode(UUID graphId, UUID worldUuid, double x, double y, double z) throws SQLException {
        if (graphId == null || worldUuid == null || !Double.isFinite(x)
                || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Invalid graph or position.");
        }
        try (Connection c = database.getConnection(); PreparedStatement ps = timedStatement(c, """
                SELECT n.node_id, POW(n.x + 0.5 - ?, 2) + POW(n.y - ?, 2)
                                 + POW(n.z + 0.5 - ?, 2) AS distance_squared
                FROM npc_nav_nodes n JOIN npc_nav_graphs g ON g.graph_id = n.graph_id
                WHERE n.graph_id = ? AND g.world_uuid = ?
                  AND n.x + 0.5 BETWEEN ? AND ? AND n.y BETWEEN ? AND ?
                  AND n.z + 0.5 BETWEEN ? AND ?
                HAVING distance_squared <= 2.25 ORDER BY distance_squared, n.node_id LIMIT 1
                """)) {
            ps.setDouble(1, x);
            ps.setDouble(2, y);
            ps.setDouble(3, z);
            ps.setBytes(4, Uuids.toBytes(graphId));
            ps.setBytes(5, Uuids.toBytes(worldUuid));
            ps.setDouble(6, x - 1.5);
            ps.setDouble(7, x + 1.5);
            ps.setDouble(8, y - 1.5);
            ps.setDouble(9, y + 1.5);
            ps.setDouble(10, z - 1.5);
            ps.setDouble(11, z + 1.5);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static PreparedStatement timedStatement(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException failure) {
            ps.close();
            throw failure;
        }
    }

    /** Persists a scan as a new DRAFT graph and returns its id. */
    public UUID saveDraft(UUID worldUuid, String regionId, String palette, NavGraph graph) throws SQLException {
        UUID graphId = UUID.randomUUID();
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                int version = nextVersion(c, worldUuid, regionId);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO npc_nav_graphs (graph_id, world_uuid, region_id, palette, version, status) "
                                + "VALUES (?, ?, ?, ?, ?, 'DRAFT')")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    ps.setBytes(2, Uuids.toBytes(worldUuid));
                    ps.setString(3, regionId);
                    ps.setString(4, palette);
                    ps.setInt(5, version);
                    ps.executeUpdate();
                }
                Map<Long, Long> idMap = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO npc_nav_nodes (graph_id, x, y, z) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    for (NavGraph.Node n : graph.nodes()) {
                        ps.setBytes(1, Uuids.toBytes(graphId));
                        ps.setInt(2, n.x());
                        ps.setInt(3, n.y());
                        ps.setInt(4, n.z());
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) {
                                idMap.put(n.id(), keys.getLong(1));
                            }
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO npc_nav_edges (graph_id, from_node_id, to_node_id, cost) VALUES (?, ?, ?, ?)")) {
                    for (NavGraph.Edge e : graph.edges()) {
                        Long from = idMap.get(e.from());
                        Long to = idMap.get(e.to());
                        if (from == null || to == null) {
                            continue;
                        }
                        ps.setBytes(1, Uuids.toBytes(graphId));
                        ps.setLong(2, from);
                        ps.setLong(3, to);
                        ps.setInt(4, e.cost());
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO npc_nav_tags (node_id, tag) VALUES (?, ?)")) {
                    for (NavGraph.Node n : graph.nodes()) {
                        Long dbId = idMap.get(n.id());
                        if (dbId == null) {
                            continue;
                        }
                        for (String tag : n.tags()) {
                            ps.setLong(1, dbId);
                            ps.setString(2, tag);
                            ps.executeUpdate();
                        }
                    }
                }
                c.commit();
                return graphId;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private int nextVersion(Connection c, UUID worldUuid, String regionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM npc_nav_graphs WHERE world_uuid = ? AND region_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(worldUuid));
            ps.setString(2, regionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
    }

    /** Loads a graph's nodes/edges/tags into an in-memory {@link NavGraph} (DB node ids), or null. */
    public NavGraph loadGraph(UUID graphId) throws SQLException {
        try (Connection c = database.getConnection()) {
            if (!graphExists(c, graphId)) {
                return null;
            }
            Map<Long, List<String>> tags = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.node_id, t.tag FROM npc_nav_tags t JOIN npc_nav_nodes n ON n.node_id = t.node_id "
                            + "WHERE n.graph_id = ?")) {
                ps.setBytes(1, Uuids.toBytes(graphId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tags.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            }
            NavGraph.Builder b = NavGraph.builder();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT node_id, x, y, z FROM npc_nav_nodes WHERE graph_id = ?")) {
                ps.setBytes(1, Uuids.toBytes(graphId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        List<String> t = tags.getOrDefault(id, List.of());
                        b.addNode(new NavGraph.Node(id, rs.getInt(2), rs.getInt(3), rs.getInt(4),
                                new java.util.HashSet<>(t)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT from_node_id, to_node_id, cost FROM npc_nav_edges WHERE graph_id = ?")) {
                ps.setBytes(1, Uuids.toBytes(graphId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        b.addEdge(rs.getLong(1), rs.getLong(2), rs.getInt(3));
                    }
                }
            }
            return b.build();
        }
    }

    /** The published (ACTIVE) graph for a district, or null if none is live. */
    public NavGraph loadActive(UUID worldUuid, String regionId) throws SQLException {
        UUID id = activeGraphId(worldUuid, regionId);
        return id == null ? null : loadGraph(id);
    }

    public UUID activeGraphId(UUID worldUuid, String regionId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT graph_id FROM npc_nav_graphs WHERE world_uuid = ? AND region_id = ? "
                             + "AND status = 'ACTIVE' ORDER BY version DESC LIMIT 1")) {
            ps.setBytes(1, Uuids.toBytes(worldUuid));
            ps.setString(2, regionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Uuids.fromBytes(rs.getBytes(1)) : null;
            }
        }
    }

    /** Publishes a DRAFT as ACTIVE, archiving any prior ACTIVE for the same world+region. */
    public boolean publish(UUID graphId) throws SQLException {
        if (graphId == null) return false;
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                byte[] world;
                String region;
                try (PreparedStatement ps = timedStatement(c,
                        "SELECT world_uuid, region_id, status FROM npc_nav_graphs WHERE graph_id = ? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !"DRAFT".equals(rs.getString(3))) {
                            c.rollback();
                            return false;
                        }
                        world = rs.getBytes(1);
                        region = rs.getString(2);
                    }
                }
                try (PreparedStatement ps = timedStatement(c,
                        "UPDATE npc_nav_graphs SET status = 'ARCHIVED' WHERE world_uuid = ? AND region_id = ? "
                                + "AND status = 'ACTIVE'")) {
                    ps.setBytes(1, world);
                    ps.setString(2, region);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = timedStatement(c,
                        "UPDATE npc_nav_graphs SET status = 'ACTIVE', published_at = CURRENT_TIMESTAMP(3) "
                                + "WHERE graph_id = ? AND status = 'DRAFT'")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public GraphInfo info(UUID graphId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT g.world_uuid, g.region_id, g.palette, g.version, g.status,
                            (SELECT COUNT(*) FROM npc_nav_nodes n WHERE n.graph_id = g.graph_id) AS nodes,
                            (SELECT COUNT(*) FROM npc_nav_edges e WHERE e.graph_id = g.graph_id) AS edges
                     FROM npc_nav_graphs g WHERE g.graph_id = ?
                     """)) {
            ps.setBytes(1, Uuids.toBytes(graphId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new GraphInfo(graphId, Uuids.fromBytes(rs.getBytes(1)), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getInt(6), rs.getInt(7));
            }
        }
    }

    public List<GraphInfo> list(int limit) throws SQLException {
        List<GraphInfo> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT g.graph_id, g.world_uuid, g.region_id, g.palette, g.version, g.status,
                            (SELECT COUNT(*) FROM npc_nav_nodes n WHERE n.graph_id = g.graph_id) AS nodes,
                            (SELECT COUNT(*) FROM npc_nav_edges e WHERE e.graph_id = g.graph_id) AS edges
                     FROM npc_nav_graphs g ORDER BY g.created_at DESC LIMIT ?
                     """)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GraphInfo(Uuids.fromBytes(rs.getBytes(1)), Uuids.fromBytes(rs.getBytes(2)),
                            rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                            rs.getInt(7), rs.getInt(8)));
                }
            }
        }
        return out;
    }

    private boolean graphExists(Connection c, UUID graphId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM npc_nav_graphs WHERE graph_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(graphId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
