package com.prosperitysmp.npc.publication;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository.*;
import com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.*;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Durable private progress, not publication authorization. Changed scope/generation requires explicit review. */
public final class ChronicleRecoveryJournal implements Journal {
    private final Database database;
    public ChronicleRecoveryJournal(Database database) { this.database = Objects.requireNonNull(database); }

    @Override public Checkpoint load(Approval scope, UUID generation) throws SQLException {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(generation);
        return transaction(c -> {
            try (var ps = prepare(c, "SELECT singleton_id FROM npc_chronicle_recovery_lock WHERE singleton_id=1 FOR UPDATE");
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Missing Chronicle recovery lock");
                warnings(c, ps, rs);
            }
            State existing = state(c, scope, generation);
            if (existing != null) return existing.checkpoint();
            try (var ps = prepare(c, "SELECT COUNT(*) AS total FROM npc_chronicle_progress"); var rs = ps.executeQuery()) {
                if (!rs.next() || rs.getLong("total") >= 512) throw new SQLException("Chronicle progress capacity reached");
                warnings(c, ps, rs);
            }
            try (var ps = prepare(c, "INSERT INTO npc_chronicle_progress(property_id,generation,scope_sha256) VALUES (?,?,?)")) {
                ps.setBytes(1, Uuids.toBytes(scope.propertyId()));
                ps.setBytes(2, Uuids.toBytes(generation));
                ps.setString(3, scopeHash(scope));
                if (ps.executeUpdate() != 1) throw new SQLException("Chronicle progress initialization failed");
                warnings(c, ps, null);
            }
            return new Checkpoint(scope, generation, 0, null);
        });
    }

    @Override public void commit(Checkpoint expected, Page page, List<Attempt> attempts) throws SQLException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(page);
        attempts = List.copyOf(attempts);
        if (attempts.size() > 16 || attempts.size() != page.requestIds().size() || expected.revision() >= Long.MAX_VALUE - 1)
            throw new SQLException("Invalid Chronicle checkpoint batch");
        var seen = new HashSet<UUID>();
        for (int i = 0; i < attempts.size(); i++)
            if (!seen.add(attempts.get(i).requestId()) || !attempts.get(i).requestId().equals(page.requestIds().get(i)))
                throw new SQLException("Chronicle attempt coverage mismatch");
        Cursor next = page.continuation();
        if (next != null && (!expected.scope().equals(next.scope()) || !expected.generation().equals(next.generation())
                || attempts.isEmpty() || !next.after().equals(attempts.getLast().requestId())
                || expected.cursor() != null && !expected.cursor().through().equals(next.through())))
            throw new SQLException("Chronicle continuation mismatch");
        UUID prior = expected.cursor() == null ? null : expected.cursor().after();
        for (Attempt attempt : attempts) {
            UUID id = attempt.requestId();
            if (prior != null && compare(prior, id) >= 0 || expected.cursor() != null && compare(id, expected.cursor().through()) > 0)
                throw new SQLException("Chronicle checkpoint order mismatch");
            prior = id;
        }
        String hash = commitHash(expected, page, attempts);
        List<Attempt> immutable = attempts;
        transaction(c -> {
            State current = state(c, expected.scope(), expected.generation());
            if (current == null) throw new SQLException("Missing Chronicle progress");
            if (current.checkpoint().revision() == expected.revision() + 1 && hash.equals(current.lastCommit())) {
                if (!Objects.equals(current.checkpoint().cursor(), next)) throw new SQLException("Damaged Chronicle replay cursor");
                for (Attempt attempt : immutable) {
                    verifyAttempt(c, expected, attempt);
                    if (attempt.outcome() == Outcome.STAGED) verifyHeld(c, expected, attempt);
                }
                return null;
            }
            if (!current.checkpoint().equals(expected)) throw new SQLException("Stale Chronicle progress");
            for (Attempt attempt : immutable) {
                if (attempt.outcome() == Outcome.STAGED) verifyHeld(c, expected, attempt);
                try (var ps = prepare(c, """
                        INSERT INTO npc_chronicle_attempts(property_id,request_id,outcome,event_key,payload_sha256,ever_unresolved)
                        VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE outcome=VALUES(outcome),event_key=VALUES(event_key),
                        payload_sha256=VALUES(payload_sha256),ever_unresolved=GREATEST(ever_unresolved,VALUES(ever_unresolved))
                        """)) {
                    ps.setBytes(1, Uuids.toBytes(expected.scope().propertyId()));
                    ps.setBytes(2, Uuids.toBytes(attempt.requestId()));
                    ps.setString(3, attempt.outcome().name());
                    ps.setString(4, attempt.held() == null ? null : attempt.held().eventKey());
                    ps.setString(5, attempt.held() == null ? null : attempt.held().payloadSha256());
                    ps.setInt(6, attempt.outcome() == Outcome.UNRESOLVED ? 1 : 0);
                    ps.executeUpdate();
                    warnings(c, ps, null);
                }
            }
            try (var ps = prepare(c,
                    "UPDATE npc_chronicle_progress SET revision=revision+1,cursor_after=?,cursor_through=?,last_commit_sha256=?"
                            + " WHERE property_id=?")) {
                ps.setBytes(1, next == null ? null : Uuids.toBytes(next.after()));
                ps.setBytes(2, next == null ? null : Uuids.toBytes(next.through()));
                ps.setString(3, hash);
                ps.setBytes(4, Uuids.toBytes(expected.scope().propertyId()));
                if (ps.executeUpdate() != 1) throw new SQLException("Chronicle progress update failed");
                warnings(c, ps, null);
            }
            return null;
        });
    }

    private record State(Checkpoint checkpoint, String lastCommit) { }
    private static State state(Connection c, Approval scope, UUID generation) throws SQLException {
        try (var ps = prepare(c, "SELECT * FROM npc_chronicle_progress WHERE property_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(scope.propertyId()));
            try (var rs = ps.executeQuery()) {
                warnings(c, ps, rs);
                if (!rs.next()) return null;
                if (!Arrays.equals(Uuids.toBytes(generation), rs.getBytes("generation"))
                        || !scopeHash(scope).equals(rs.getString("scope_sha256")))
                    throw new SQLException("Chronicle progress scope requires review");
                long revision = rs.getLong("revision");
                if (rs.wasNull()) throw new SQLException("Invalid Chronicle revision");
                byte[] after = rs.getBytes("cursor_after"), through = rs.getBytes("cursor_through");
                if ((after == null) != (through == null)) throw new SQLException("Invalid Chronicle stored cursor");
                try {
                    Cursor cursor = after == null ? null : new Cursor(generation, scope, Uuids.fromBytes(after), Uuids.fromBytes(through));
                    String last = rs.getString("last_commit_sha256");
                    if (revision == 0 ? last != null || cursor != null : last == null || !last.matches("[0-9a-f]{64}"))
                        throw new SQLException("Invalid Chronicle commit marker");
                    State result = new State(new Checkpoint(scope, generation, revision, cursor), last);
                    warnings(c, ps, rs);
                    return result;
                } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid Chronicle stored progress", invalid); }
            }
        }
    }
    private static void verifyAttempt(Connection c, Checkpoint expected, Attempt attempt) throws SQLException {
        try (var ps = prepare(c,
                "SELECT outcome,event_key,payload_sha256,ever_unresolved FROM npc_chronicle_attempts"
                        + " WHERE property_id=? AND request_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(expected.scope().propertyId()));
            ps.setBytes(2, Uuids.toBytes(attempt.requestId()));
            try (var rs = ps.executeQuery()) {
                if (!rs.next() || !attempt.outcome().name().equals(rs.getString("outcome"))
                        || !Objects.equals(attempt.held() == null ? null : attempt.held().eventKey(), rs.getString("event_key"))
                        || !Objects.equals(attempt.held() == null ? null : attempt.held().payloadSha256(), rs.getString("payload_sha256"))
                        || attempt.outcome() == Outcome.UNRESOLVED && rs.getInt("ever_unresolved") != 1)
                    throw new SQLException("Damaged Chronicle replay attempt");
                warnings(c, ps, rs);
            }
        }
    }
    private static void verifyHeld(Connection c, Checkpoint expected, Attempt attempt) throws SQLException {
        try (var ps = prepare(c, "SELECT o.event_key,o.payload_sha256,o.public_payload,o.authorization_generation,o.state,m.property_id"
                + " FROM npc_chronicle_outbox o JOIN npc_housing_moves m ON m.request_id=o.request_id WHERE o.request_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(attempt.requestId()));
            try (var rs = ps.executeQuery()) {
                warnings(c, ps, rs);
                if (!rs.next() || !attempt.held().eventKey().equals(rs.getString("event_key"))
                        || !attempt.held().payloadSha256().equals(rs.getString("payload_sha256"))
                        || !attempt.held().payloadSha256().equals(sha(rs.getString("public_payload")))
                        || !"HELD".equals(rs.getString("state"))
                        || !Arrays.equals(Uuids.toBytes(expected.generation()), rs.getBytes("authorization_generation"))
                        || !Arrays.equals(Uuids.toBytes(expected.scope().propertyId()), rs.getBytes("property_id")))
                    throw new SQLException("Chronicle staged outcome is not backed by held evidence");
                warnings(c, ps, rs);
            }
        }
    }
    private static String scopeHash(Approval scope) {
        return sha(scope.propertyId() + "|" + scope.worldId() + "|" + scope.regionId() + "|" + scope.publicWorldKey());
    }
    private static String commitHash(Checkpoint expected, Page page, List<Attempt> attempts) {
        var root = new JsonObject();
        root.addProperty("version", "chronicle-checkpoint-v1");
        root.addProperty("scope", scopeHash(expected.scope()));
        root.addProperty("generation", expected.generation().toString());
        root.addProperty("revision", expected.revision());
        root.add("before", cursorJson(expected.cursor()));
        root.add("after", cursorJson(page.continuation()));
        root.addProperty("capturedAt", page.capturedAt().toString());
        var rows = new JsonArray();
        for (Attempt attempt : attempts) {
            var row = new JsonObject();
            row.addProperty("requestId", attempt.requestId().toString());
            row.addProperty("outcome", attempt.outcome().name());
            row.addProperty("eventKey", attempt.held() == null ? null : attempt.held().eventKey());
            row.addProperty("payloadSha256", attempt.held() == null ? null : attempt.held().payloadSha256());
            rows.add(row);
        }
        root.add("attempts", rows);
        return sha(root.toString());
    }
    private static JsonElement cursorJson(Cursor cursor) {
        if (cursor == null) return JsonNull.INSTANCE;
        var json = new JsonObject();
        json.addProperty("after", cursor.after().toString());
        json.addProperty("through", cursor.through().toString());
        return json;
    }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static int compare(UUID a, UUID b) { return Arrays.compareUnsigned(Uuids.toBytes(a), Uuids.toBytes(b)); }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var ps = c.prepareStatement(sql);
        ps.setQueryTimeout(5);
        return ps;
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || rs != null && rs.getWarnings() != null)
            throw new SQLException("Incomplete Chronicle journal evidence");
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            }
            catch (SQLException | RuntimeException | Error failure) {
                try { c.rollback(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }
}
