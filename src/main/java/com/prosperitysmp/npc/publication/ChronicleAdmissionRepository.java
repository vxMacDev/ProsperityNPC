package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Private committed facts only. This neither classifies premium moves nor authorizes a story. */
public final class ChronicleAdmissionRepository {
    public record Batch(Instant capturedAt, Map<UUID, Receipt> receipts) {
        public Batch {
            Objects.requireNonNull(capturedAt);
            receipts = Map.copyOf(receipts);
        }
    }
    private final Database database;
    private final Clock clock;
    private final ZoneId zone;
    private final int resetHour;

    public ChronicleAdmissionRepository(Database database, Clock clock, ZoneId zone, int resetHour) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
        if (resetHour < 0 || resetHour > 23) throw new IllegalArgumentException("Invalid reset hour");
        this.resetHour = resetHour;
    }
    public Batch read(List<UUID> requestIds) throws SQLException {
        List<UUID> ids = scope(requestIds);
        if (ids.isEmpty()) return new Batch(clock.instant(), Map.of());
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, clock)) { return read(snapshot, ids); }
    }
    /** Borrow a consistent snapshot without locking the housing registry or changing any source row. */
    public Batch read(ReadSnapshot snapshot, List<UUID> requestIds) throws SQLException {
        return readInTransaction(snapshot.connection(database), snapshot.capturedAt(), requestIds);
    }
    /** Package-only composition for outbox staging; caller owns transaction and rollback. */
    Batch readInTransaction(Connection connection, Instant capturedAt, List<UUID> requestIds) throws SQLException {
        List<UUID> ids = scope(requestIds);
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
            throw new SQLException("Admission evidence requires a repeatable-read transaction");
        if (ids.isEmpty()) return new Batch(capturedAt, Map.of());
        String sql = "SELECT m.*,d.server_limit AS policy_server,d.market_limit AS policy_market,"
                + "d.property_limit AS policy_property,d.cooldown_days AS policy_cooldown "
                + "FROM npc_housing_moves m LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day "
                + "WHERE m.request_id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ") ORDER BY m.request_id LIMIT 129";
        var decoder = new HousingMoveRepository(database, Clock.fixed(capturedAt, ZoneOffset.UTC), zone, resetHour);
        var wanted = new HashSet<>(ids);
        var receipts = new HashMap<UUID, Receipt>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < ids.size(); i++) ps.setBytes(i + 1, Uuids.toBytes(ids.get(i)));
            warnings(connection, ps, null);
            try (ResultSet rs = ps.executeQuery()) {
                warnings(connection, ps, rs);
                while (rs.next()) {
                    warnings(connection, ps, rs);
                    if (receipts.size() >= ids.size()) throw new SQLException("Excess admission evidence");
                    var policy = new Policy(integer(rs, "policy_server"), integer(rs, "policy_market"),
                            integer(rs, "policy_property"), integer(rs, "policy_cooldown"));
                    Receipt receipt = decoder.readReceipt(rs, policy);
                    UUID id = receipt.request().requestId();
                    if (!wanted.contains(id) || receipts.putIfAbsent(id, receipt) != null)
                        throw new SQLException("Invalid admission identity");
                }
                warnings(connection, ps, rs);
            }
        } catch (RuntimeException invalid) { throw new SQLException("Invalid admission evidence", invalid); }
        if (receipts.size() != ids.size()) throw new SQLException("Missing admission evidence");
        return new Batch(capturedAt, receipts);
    }
    private static List<UUID> scope(List<UUID> ids) {
        if (ids == null || ids.size() > 128) throw new IllegalArgumentException("Invalid admission request batch");
        var copy = List.copyOf(ids);
        if (new HashSet<>(copy).size() != copy.size()) throw new IllegalArgumentException("Duplicate admission request");
        return copy;
    }
    private static int integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        if (rs.wasNull()) throw new SQLException("Missing admission policy");
        return value;
    }
    private static void warnings(Connection c, PreparedStatement p, ResultSet r) throws SQLException {
        if (c.getWarnings() != null || p.getWarnings() != null || r != null && r.getWarnings() != null)
            throw new SQLException("Incomplete admission evidence");
    }
}
