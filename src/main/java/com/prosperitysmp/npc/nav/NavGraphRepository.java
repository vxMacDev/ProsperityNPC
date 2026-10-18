package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for district navigation graphs: draft → validate → publish,
 * versioned and district-scoped. A scan writes a {@code DRAFT}; publishing flips it
 * {@code ACTIVE} and archives the previously active graph for that world+region, so
 * a material scan can never silently replace the live graph.
 */
public final class NavGraphRepository {

    private static final Set<String> ROUTE_TAGS = Set.of("home", "work", "market", "leisure");
    private static final int MAX_TAGS_PER_NODE = 8;

    /**
     * {@code source} is how the graph was made ({@code scan}, {@code waypoints} or {@code file}); {@code originSha}
     * is the document a waypoint or file graph was built from, and {@code originRef} the file name, for display only.
     */
    public record GraphInfo(UUID graphId, UUID worldUuid, String regionId, String palette,
                            int version, String status, int nodeCount, int edgeCount, String paletteHash,
                            String source, String originSha, String originRef) {
        public GraphInfo {
            source = source == null ? SOURCE_SCAN : source;
        }

        /** A scan graph, as every graph was before graph sources existed. */
        public GraphInfo(UUID graphId, UUID worldUuid, String regionId, String palette,
                         int version, String status, int nodeCount, int edgeCount, String paletteHash) {
            this(graphId, worldUuid, regionId, palette, version, status, nodeCount, edgeCount, paletteHash,
                    SOURCE_SCAN, null, null);
        }
    }

    public static final String SOURCE_SCAN = "scan";
    public static final String SOURCE_WAYPOINTS = "waypoints";
    public static final String SOURCE_FILE = "file";

    /** The current waypoint set of a region: its revision, the sha of its document and the document itself. */
    public record WaypointSetRow(int revision, String docSha, String document, String updatedBy) {
    }

    /** Turns the current document (null when the region has no set yet) into the next one; may throw to refuse. */
    @FunctionalInterface
    public interface DocumentEdit {
        String apply(String current);
    }

    /** One node's stored class, for the audit and revalidate commands. */
    public record NodeSurface(long nodeId, int x, int y, int z, NavSurface surface) {
    }

    /** What {@link #copyTags} did, or would have done (nothing is written unless {@code written}). */
    public record CopyReport(boolean written, int exact, int near, int unmatched, int skippedInvalid,
                             int refusedFull, int inserted, int alreadyPresent, int anchorNodes,
                             List<String> details) {
        public CopyReport {
            details = List.copyOf(details);
        }
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
                        "SELECT status, source FROM npc_nav_graphs WHERE graph_id = ? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !"DRAFT".equals(rs.getString(1))) {
                            throw new IllegalArgumentException("Only an existing DRAFT graph can be tagged.");
                        }
                        refuseAuthoredTags(rs.getString(2));
                    }
                }
                try (PreparedStatement ps = timedStatement(c,
                        "SELECT surface FROM npc_nav_nodes WHERE graph_id = ? AND node_id = ? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    ps.setLong(2, nodeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Node does not belong to this graph.");
                        // A stop on a road would have residents start, wait and end trips in traffic.
                        if (add && isStreetCode(rs.getInt(1))) {
                            throw new IllegalArgumentException("That node is on a road or crosswalk; stops go on sidewalks.");
                        }
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

    /** The points (or the route file) are the single truth for a waypoint or file graph's tags. */
    private static void refuseAuthoredTags(String source) {
        if (SOURCE_WAYPOINTS.equals(source)) {
            throw new IllegalArgumentException("Tags on waypoint graphs come from the points; use /npcnav wp tag");
        }
        if (source != null && !SOURCE_SCAN.equals(source)) {
            throw new IllegalArgumentException("Tags on route-file graphs come from the file; edit it and import it again");
        }
    }

    private static boolean isStreetCode(int code) {
        return code == NavSurface.ROAD.code() || code == NavSurface.CROSSING.code();
    }

    /** Nearest standing node within 1.5 blocks in 3D, in the caller's captured world only. Never a road node. */
    public Long nearestTagNode(UUID graphId, UUID worldUuid, double x, double y, double z) throws SQLException {
        if (graphId == null || worldUuid == null || !Double.isFinite(x)
                || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Invalid graph or position.");
        }
        try (Connection c = database.getConnection(); PreparedStatement ps = timedStatement(c, """
                SELECT n.node_id, POW(n.x + 0.5 - ?, 2) + POW(n.y - ?, 2)
                                 + POW(n.z + 0.5 - ?, 2) AS distance_squared
                FROM npc_nav_nodes n JOIN npc_nav_graphs g ON g.graph_id = n.graph_id
                WHERE n.graph_id = ? AND g.world_uuid = ? AND n.surface NOT IN (3, 4)
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

    /** Persists a graph with no palette hash (a legacy or hand-built graph) as a new DRAFT. */
    public UUID saveDraft(UUID worldUuid, String regionId, String palette, NavGraph graph) throws SQLException {
        return saveDraft(worldUuid, regionId, palette, null, graph);
    }

    /**
     * Persists a scan as a new DRAFT graph and returns its id. The palette hash records which effective
     * block lists the graph was scanned with, because only the palette name is otherwise stored.
     */
    public UUID saveDraft(UUID worldUuid, String regionId, String palette, String paletteHash, NavGraph graph)
            throws SQLException {
        return saveDraft(worldUuid, regionId, palette, paletteHash, graph, SOURCE_SCAN, null, null);
    }

    /** As {@link #saveDraft(UUID, String, String, String, NavGraph)}, recording how the graph was made. */
    public UUID saveDraft(UUID worldUuid, String regionId, String palette, String paletteHash, NavGraph graph,
                          String source, String originSha, String originRef) throws SQLException {
        UUID graphId = UUID.randomUUID();
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                int version = nextVersion(c, worldUuid, regionId);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO npc_nav_graphs (graph_id, world_uuid, region_id, palette, version, status, "
                                + "palette_hash, source, origin_sha, origin_ref) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)")) {
                    ps.setBytes(1, Uuids.toBytes(graphId));
                    ps.setBytes(2, Uuids.toBytes(worldUuid));
                    ps.setString(3, regionId);
                    ps.setString(4, palette);
                    ps.setInt(5, version);
                    ps.setString(6, paletteHash);
                    ps.setString(7, source == null ? SOURCE_SCAN : source);
                    ps.setString(8, originSha);
                    ps.setString(9, originRef);
                    ps.executeUpdate();
                }
                Map<Long, Long> idMap = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO npc_nav_nodes (graph_id, x, y, z, surface, road_width) VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    for (NavGraph.Node n : graph.nodes()) {
                        ps.setBytes(1, Uuids.toBytes(graphId));
                        ps.setInt(2, n.x());
                        ps.setInt(3, n.y());
                        ps.setInt(4, n.z());
                        ps.setInt(5, n.surface().code());
                        // NULL where the scan recorded no width, so older and hand-made graphs read back as unmeasured.
                        if (n.roadWidth() > 0) {
                            ps.setInt(6, n.roadWidth());
                        } else {
                            ps.setNull(6, Types.SMALLINT);
                        }
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
                    "SELECT node_id, x, y, z, surface, road_width FROM npc_nav_nodes WHERE graph_id = ?")) {
                ps.setBytes(1, Uuids.toBytes(graphId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        List<String> t = tags.getOrDefault(id, List.of());
                        b.addNode(new NavGraph.Node(id, rs.getInt(2), rs.getInt(3), rs.getInt(4),
                                new HashSet<>(t), NavSurface.fromCode(rs.getInt(5)), rs.getInt(6)));
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
                            (SELECT COUNT(*) FROM npc_nav_edges e WHERE e.graph_id = g.graph_id) AS edges,
                            g.palette_hash, g.source, g.origin_sha, g.origin_ref
                     FROM npc_nav_graphs g WHERE g.graph_id = ?
                     """)) {
            ps.setBytes(1, Uuids.toBytes(graphId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new GraphInfo(graphId, Uuids.fromBytes(rs.getBytes(1)), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11));
            }
        }
    }

    public List<GraphInfo> list(int limit) throws SQLException {
        return query("ORDER BY g.created_at DESC LIMIT ?", Math.max(1, limit));
    }

    /** Every ACTIVE graph, one per published district. */
    public List<GraphInfo> listActive() throws SQLException {
        return query("WHERE g.status = 'ACTIVE' ORDER BY g.created_at DESC LIMIT ?", 1000);
    }

    private List<GraphInfo> query(String tail, int limit) throws SQLException {
        List<GraphInfo> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT g.graph_id, g.world_uuid, g.region_id, g.palette, g.version, g.status, "
                             + "(SELECT COUNT(*) FROM npc_nav_nodes n WHERE n.graph_id = g.graph_id), "
                             + "(SELECT COUNT(*) FROM npc_nav_edges e WHERE e.graph_id = g.graph_id), "
                             + "g.palette_hash, g.source, g.origin_sha, g.origin_ref FROM npc_nav_graphs g " + tail)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GraphInfo(Uuids.fromBytes(rs.getBytes(1)), Uuids.fromBytes(rs.getBytes(2)),
                            rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                            rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getString(10), rs.getString(11),
                            rs.getString(12)));
                }
            }
        }
        return out;
    }

    /** Each node's position and stored class (no tags, no edges): enough to compare with the world. */
    public List<NodeSurface> nodeSurfaces(UUID graphId) throws SQLException {
        List<NodeSurface> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT node_id, x, y, z, surface FROM npc_nav_nodes WHERE graph_id = ? ORDER BY node_id")) {
            ps.setBytes(1, Uuids.toBytes(graphId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new NodeSurface(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            NavSurface.fromCode(rs.getInt(5))));
                }
            }
        }
        return out;
    }

    /**
     * Copies authored route tags from one graph onto a DRAFT of the same world, matching each tag to the
     * node at the same position (EXACT) or one step up or down in the same column (NEAR). Road nodes are
     * never a target, tags that are no longer valid are reported, and the 8-tag cap holds. Without
     * {@code partial}, one tag with no home writes nothing at all.
     */
    public CopyReport copyTags(UUID from, UUID toDraft, boolean partial) throws SQLException {
        if (from == null || toDraft == null || from.equals(toDraft)) {
            throw new IllegalArgumentException("Pick two different graphs.");
        }
        record SourceTag(int x, int y, int z, String tag) { }
        record Target(long id, int x, int y, int z, NavSurface surface) { }
        record Planned(SourceTag source, Target target) { }
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                byte[] world;
                try (PreparedStatement ps = timedStatement(c,
                        "SELECT status, world_uuid, source FROM npc_nav_graphs WHERE graph_id = ? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(toDraft));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !"DRAFT".equals(rs.getString(1))) {
                            throw new IllegalArgumentException("Only an existing DRAFT graph can receive tags.");
                        }
                        refuseAuthoredTags(rs.getString(3));
                        world = rs.getBytes(2);
                    }
                }
                try (PreparedStatement ps = timedStatement(c,
                        "SELECT world_uuid FROM npc_nav_graphs WHERE graph_id = ?")) {
                    ps.setBytes(1, Uuids.toBytes(from));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("No graph " + from);
                        if (!java.util.Arrays.equals(world, rs.getBytes(1))) {
                            throw new IllegalArgumentException("Those graphs are in different worlds.");
                        }
                    }
                }
                List<SourceTag> sourceTags = new ArrayList<>();
                try (PreparedStatement ps = timedStatement(c, "SELECT n.x, n.y, n.z, t.tag FROM npc_nav_tags t "
                        + "JOIN npc_nav_nodes n ON n.node_id = t.node_id WHERE n.graph_id = ? ORDER BY n.node_id, t.tag")) {
                    ps.setBytes(1, Uuids.toBytes(from));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            sourceTags.add(new SourceTag(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)));
                        }
                    }
                }
                Map<String, List<Target>> byPosition = new HashMap<>();
                Map<String, List<Target>> byColumn = new HashMap<>();
                try (PreparedStatement ps = timedStatement(c, "SELECT node_id, x, y, z, surface FROM npc_nav_nodes "
                        + "WHERE graph_id = ? ORDER BY node_id")) {
                    ps.setBytes(1, Uuids.toBytes(toDraft));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Target target = new Target(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                                    NavSurface.fromCode(rs.getInt(5)));
                            if (target.surface().isStreet()) continue; // a stop never goes on a road
                            byPosition.computeIfAbsent(target.x() + "," + target.y() + "," + target.z(),
                                    k -> new ArrayList<>()).add(target);
                            byColumn.computeIfAbsent(target.x() + "," + target.z(), k -> new ArrayList<>()).add(target);
                        }
                    }
                }
                Map<Long, Set<String>> afterTags = new HashMap<>();
                try (PreparedStatement ps = timedStatement(c, "SELECT t.node_id, t.tag FROM npc_nav_tags t "
                        + "JOIN npc_nav_nodes n ON n.node_id = t.node_id WHERE n.graph_id = ?")) {
                    ps.setBytes(1, Uuids.toBytes(toDraft));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            afterTags.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2));
                        }
                    }
                }

                List<String> details = new ArrayList<>();
                List<Planned> plan = new ArrayList<>();
                int exact = 0;
                int near = 0;
                int unmatched = 0;
                int invalid = 0;
                int full = 0;
                int present = 0;
                for (SourceTag source : sourceTags) {
                    String at = source.x() + "," + source.y() + "," + source.z();
                    if (!isValidDraftTag(source.tag())) {
                        invalid++;
                        details.add("SKIPPED_INVALID " + source.tag() + " @" + at);
                        continue;
                    }
                    Target target = null;
                    boolean isExact = true;
                    int dy = 0;
                    List<Target> same = byPosition.get(at);
                    if (same != null) {
                        target = same.getFirst();
                    } else {
                        isExact = false;
                        for (Target candidate : byColumn.getOrDefault(source.x() + "," + source.z(), List.of())) {
                            int d = candidate.y() - source.y();
                            if (Math.abs(d) <= 1 && (target == null || Math.abs(d) < Math.abs(dy))) {
                                target = candidate;
                                dy = d;
                            }
                        }
                    }
                    if (target == null) {
                        unmatched++;
                        details.add("UNMATCHED " + source.tag() + " @" + at);
                        continue;
                    }
                    Set<String> tags = afterTags.computeIfAbsent(target.id(), k -> new HashSet<>());
                    String where = " -> node " + target.id() + " " + target.surface();
                    if (tags.contains(source.tag())) {
                        present++;
                        details.add("PRESENT " + source.tag() + " @" + at + where);
                        continue;
                    }
                    if (tags.size() >= MAX_TAGS_PER_NODE) {
                        full++;
                        details.add("REFUSED_FULL " + source.tag() + " @" + at + where);
                        continue;
                    }
                    tags.add(source.tag());
                    plan.add(new Planned(source, target));
                    if (isExact) {
                        exact++;
                        details.add("EXACT " + source.tag() + " @" + at + where);
                    } else {
                        near++;
                        details.add("NEAR " + source.tag() + " @" + at + where + " dy=" + dy);
                    }
                }
                int anchors = (int) afterTags.values().stream().filter(t -> !t.isEmpty()).count();
                if (!partial && (unmatched > 0 || full > 0)) {
                    c.rollback();
                    return new CopyReport(false, exact, near, unmatched, invalid, full, 0, present, anchors, details);
                }
                try (PreparedStatement ps = timedStatement(c,
                        "INSERT INTO npc_nav_tags (node_id, tag) VALUES (?, ?)")) {
                    for (Planned p : plan) {
                        ps.setLong(1, p.target().id());
                        ps.setString(2, p.source().tag());
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return new CopyReport(true, exact, near, unmatched, invalid, full, plan.size(), present, anchors, details);
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /**
     * Brings an ARCHIVED graph back as a new DRAFT (next version, same nodes, edges, tags and classes).
     * It never publishes: {@link #publish} still accepts only a DRAFT.
     */
    public UUID restore(UUID archived) throws SQLException {
        GraphInfo info = archived == null ? null : info(archived);
        if (info == null || !"ARCHIVED".equals(info.status())) {
            throw new IllegalArgumentException("Only an ARCHIVED graph can be restored.");
        }
        NavGraph graph = loadGraph(archived);
        if (graph == null) {
            throw new IllegalArgumentException("No graph " + archived);
        }
        return saveDraft(info.worldUuid(), info.regionId(), info.palette(), info.paletteHash(), graph,
                info.source(), info.originSha(), info.originRef());
    }

    /** SHA-256 of a document's UTF-8 text, as 64 lowercase hex digits. */
    public static String documentSha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Keeps a waypoint or route document by its sha (a repeat is a no-op) and returns the sha. */
    public String storeRouteDocument(String kind, String text) throws SQLException {
        try (Connection c = database.getConnection()) {
            return storeRouteDocument(c, kind, text);
        }
    }

    private static String storeRouteDocument(Connection c, String kind, String text) throws SQLException {
        String sha = documentSha(text);
        try (PreparedStatement ps = timedStatement(c,
                "INSERT IGNORE INTO npc_nav_route_docs (doc_sha256, kind, document) VALUES (?, ?, ?)")) {
            ps.setString(1, sha);
            ps.setString(2, kind);
            ps.setString(3, text);
            ps.executeUpdate();
        }
        return sha;
    }

    /** A stored document by sha, or null. The text is checked against its sha before it is returned. */
    public String routeDocument(String sha) throws SQLException {
        if (sha == null) return null;
        try (Connection c = database.getConnection(); PreparedStatement ps = timedStatement(c,
                "SELECT document FROM npc_nav_route_docs WHERE doc_sha256 = ?")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String text = rs.getString(1);
                return sha.equals(documentSha(text)) ? text : null;
            }
        }
    }

    /** The current waypoint set of a region, or null when it has none. */
    public WaypointSetRow waypointSet(UUID worldUuid, String regionId) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = timedStatement(c,
                "SELECT s.revision, s.doc_sha256, d.document, s.updated_by FROM npc_nav_waypoint_sets s "
                        + "JOIN npc_nav_route_docs d ON d.doc_sha256 = s.doc_sha256 WHERE s.world_uuid = ? AND s.region_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(worldUuid));
            ps.setString(2, regionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new WaypointSetRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null;
            }
        }
    }

    /**
     * Edits a region's waypoint set under a row lock, so two edits at once both land, one after the other.
     * {@code emptyDocument} seeds a region that has no set yet (revision 0) before the lock is taken. Each
     * new document is kept for good; the set row only points at the latest. An exception from {@code edit}
     * writes nothing but the seed.
     */
    public WaypointSetRow editWaypointSet(UUID worldUuid, String regionId, String by, String emptyDocument,
                                          DocumentEdit edit) throws SQLException {
        try (Connection c = database.getConnection()) {
            String emptySha = storeRouteDocument(c, SOURCE_WAYPOINTS, emptyDocument);
            try (PreparedStatement ps = timedStatement(c, "INSERT IGNORE INTO npc_nav_waypoint_sets "
                    + "(world_uuid, region_id, revision, doc_sha256, updated_by) VALUES (?, ?, 0, ?, NULL)")) {
                ps.setBytes(1, Uuids.toBytes(worldUuid));
                ps.setString(2, regionId);
                ps.setString(3, emptySha);
                ps.executeUpdate();
            }
            c.setAutoCommit(false);
            try {
                int revision;
                String current;
                try (PreparedStatement ps = timedStatement(c, "SELECT s.revision, d.document FROM npc_nav_waypoint_sets s "
                        + "JOIN npc_nav_route_docs d ON d.doc_sha256 = s.doc_sha256 "
                        + "WHERE s.world_uuid = ? AND s.region_id = ? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(worldUuid));
                    ps.setString(2, regionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("waypoint set row vanished");
                        revision = rs.getInt(1);
                        current = rs.getString(2);
                    }
                }
                String next = edit.apply(revision == 0 ? null : current);
                String sha = storeRouteDocument(c, SOURCE_WAYPOINTS, next);
                String who = by == null ? null : by.substring(0, Math.min(32, by.length()));
                try (PreparedStatement ps = timedStatement(c, "UPDATE npc_nav_waypoint_sets SET revision = ?, "
                        + "doc_sha256 = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE world_uuid = ? AND region_id = ?")) {
                    ps.setInt(1, revision + 1);
                    ps.setString(2, sha);
                    ps.setString(3, who);
                    ps.setBytes(4, Uuids.toBytes(worldUuid));
                    ps.setString(5, regionId);
                    ps.executeUpdate();
                }
                c.commit();
                return new WaypointSetRow(revision + 1, sha, next, who);
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(true);
            }
        }
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
