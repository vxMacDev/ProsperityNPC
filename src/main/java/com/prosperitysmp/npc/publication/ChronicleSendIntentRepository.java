package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.db.*;
import com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One durable intent per event, across destinations/generations. Not publication authorization. */
public final class ChronicleSendIntentRepository {
    public static final class Intent {
        private final UUID id, generation;
        private final String eventKey, payloadHash;
        private final Destination destination;
        private final Message message;
        private Intent(UUID id, String eventKey, String payloadHash, UUID generation, Destination destination, Message message) {
            this.id = id;
            this.eventKey = eventKey;
            this.payloadHash = payloadHash;
            this.generation = generation;
            this.destination = destination;
            this.message = message;
        }
        public UUID id() { return id; }
        public UUID generation() { return generation; }
        public String eventKey() { return eventKey; }
        public String payloadHash() { return payloadHash; }
        public Destination destination() { return destination; }
        public Message message() { return message; }
    }
    private final Database database;
    private final Clock clock;
    public ChronicleSendIntentRepository(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Only a newly committed intent is returned. A retry after a lost commit reply NEVER returns it again. */
    public Optional<Intent> reserve(ChronicleOutboxRepository.Held held, UUID generation, Destination destination) throws SQLException {
        Objects.requireNonNull(held);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(destination);
        hash(held.eventKey());
        hash(held.payloadSha256());
        UUID attemptId = UUID.randomUUID();
        return transaction(c -> {
            Message message;
            try (var ps = prepare(c,
                    "SELECT payload_sha256,public_payload,authorization_generation,state FROM npc_chronicle_outbox"
                            + " WHERE event_key=? FOR UPDATE")) {
                ps.setString(1, held.eventKey());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next() || !held.payloadSha256().equals(rs.getString("payload_sha256"))
                            || !held.payloadSha256().equals(sha(rs.getString("public_payload"))) || !"HELD".equals(rs.getString("state"))
                            || !Arrays.equals(Uuids.toBytes(generation), rs.getBytes("authorization_generation")))
                        throw new SQLException("Missing or conflicting held send evidence");
                    warnings(c, ps, rs);
                    try { message = ChronicleWebhookProtocol.fromHeldPayload(rs.getString("public_payload"), held.payloadSha256()); }
                    catch (IllegalArgumentException invalid) { throw new SQLException("Invalid held send message"); }
                    if (!held.eventKey().equals(message.eventKey())) throw new SQLException("Held send event mismatch");
                }
            }
            var proposed = new Intent(attemptId, held.eventKey(), held.payloadSha256(), generation, destination, message);
            try (var ps = prepare(c, """
                    INSERT INTO npc_chronicle_send_intents(event_key,intent_id,payload_sha256,generation,webhook_id,channel_id,created_at,state)
                    VALUES (?,?,?,?,?,?,?,'INTENT') ON DUPLICATE KEY UPDATE event_key=event_key
                    """)) {
                ps.setString(1, proposed.eventKey());
                ps.setBytes(2, Uuids.toBytes(proposed.id()));
                ps.setString(3, proposed.payloadHash());
                ps.setBytes(4, Uuids.toBytes(generation));
                ps.setString(5, destination.webhookId());
                ps.setString(6, destination.channelId());
                ps.setObject(7, LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
                ps.executeUpdate();
                warnings(c, ps, null);
            }
            try (var ps = prepare(c, "SELECT * FROM npc_chronicle_send_intents WHERE event_key=? FOR UPDATE")) {
                ps.setString(1, held.eventKey());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Missing send intent");
                    requireBinding(rs, proposed, false);
                    warnings(c, ps, rs);
                    if (!Arrays.equals(rs.getBytes("intent_id"), Uuids.toBytes(proposed.id()))) return Optional.empty();
                    if (!"INTENT".equals(rs.getString("state")) || rs.getString("message_id") != null)
                        throw new SQLException("New send intent was not preserved");
                    return Optional.of(proposed);
                }
            }
        });
    }

    /** Receipt must come from the authenticated response for this intent, not arbitrary caller data. */
    public void acknowledge(Intent intent, Receipt receipt) throws SQLException {
        Objects.requireNonNull(receipt);
        if (!intent.eventKey().equals(receipt.eventKey()) || !intent.destination().equals(receipt.destination()))
            throw new SQLException("Send receipt binding mismatch");
        new Destination(receipt.messageId(), receipt.destination().channelId());
        finish(intent, receipt.messageId());
    }
    /** No retry permission is created. An unacknowledged INTENT after a crash has the same uncertainty. */
    public void uncertain(Intent intent) throws SQLException { finish(intent, null); }

    /**
     * Operator recovery ONLY after authenticated GET of the already-sent message. Response bytes
     * are not authorization: caller must verify its HTTPS origin and the approved destination.
     * Never returns an Intent or creates send permission; exact retained identity is mandatory.
     */
    public Receipt reconcileAuthenticatedResponse(ChronicleOutboxRepository.Held held, UUID intentId,
            UUID generation, Destination destination, int httpStatus, byte[] response) throws SQLException {
        Objects.requireNonNull(held);
        Objects.requireNonNull(intentId);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(destination);
        hash(held.eventKey());
        hash(held.payloadSha256());
        record Verified(Intent intent, Receipt receipt) { }
        Verified verified = transaction(c -> {
            Message message;
            try (var ps = prepare(c,
                    "SELECT payload_sha256,public_payload,authorization_generation,state FROM npc_chronicle_outbox"
                            + " WHERE event_key=? FOR UPDATE")) {
                ps.setString(1, held.eventKey());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next() || !held.payloadSha256().equals(rs.getString("payload_sha256"))
                            || !"HELD".equals(rs.getString("state"))
                            || !Arrays.equals(Uuids.toBytes(generation), rs.getBytes("authorization_generation")))
                        throw new SQLException("Recovery held evidence mismatch");
                    warnings(c, ps, rs);
                    try { message = ChronicleWebhookProtocol.fromHeldPayload(rs.getString("public_payload"), held.payloadSha256()); }
                    catch (IllegalArgumentException invalid) { throw new SQLException("Recovery held message invalid"); }
                    if (!held.eventKey().equals(message.eventKey())) throw new SQLException("Recovery event mismatch");
                }
            }
            Receipt receipt = ChronicleWebhookProtocol.confirmed(httpStatus, response, destination, message)
                    .orElseThrow(() -> new SQLException("Recovery response unconfirmed"));
            var intent = new Intent(intentId, held.eventKey(), held.payloadSha256(), generation, destination, message);
            try (var ps = prepare(c, "SELECT * FROM npc_chronicle_send_intents WHERE event_key=? FOR UPDATE")) {
                ps.setString(1, held.eventKey());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Recovery send intent missing");
                    requireBinding(rs, intent, true);
                    warnings(c, ps, rs);
                }
            }
            return new Verified(intent, receipt);
        });
        // finish re-locks and verifies the exact retained identity/state before changing the receipt.
        // A conflicting concurrent acknowledgement fails; lost commit reply can repeat this GET safely.
        acknowledge(verified.intent(), verified.receipt());
        return verified.receipt();
    }

    private void finish(Intent intent, String messageId) throws SQLException {
        Objects.requireNonNull(intent);
        transaction(c -> {
            try (var ps = prepare(c, "SELECT * FROM npc_chronicle_send_intents WHERE event_key=? FOR UPDATE")) {
                ps.setString(1, intent.eventKey());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Missing send intent");
                    requireBinding(rs, intent, true);
                    String state = rs.getString("state");
                    warnings(c, ps, rs);
                    if ("ACKNOWLEDGED".equals(state)) {
                        if (messageId == null || messageId.equals(rs.getString("message_id"))) return null;
                        throw new SQLException("Conflicting remote message receipt");
                    }
                }
            }
            try (var ps = prepare(c, "UPDATE npc_chronicle_send_intents SET state=?,message_id=? WHERE event_key=?")) {
                ps.setString(1, messageId == null ? "UNCERTAIN" : "ACKNOWLEDGED");
                ps.setString(2, messageId);
                ps.setString(3, intent.eventKey());
                ps.executeUpdate();
                warnings(c, ps, null);
            }
            try (var ps = prepare(c, "SELECT * FROM npc_chronicle_send_intents WHERE event_key=? FOR UPDATE")) {
                ps.setString(1, intent.eventKey());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Send outcome disappeared");
                    requireBinding(rs, intent, true);
                    if (!(messageId == null ? "UNCERTAIN" : "ACKNOWLEDGED").equals(rs.getString("state"))
                            || !Objects.equals(messageId, rs.getString("message_id")))
                        throw new SQLException("Send outcome was not preserved");
                    warnings(c, ps, rs);
                }
            }
            return null;
        });
    }
    private static void requireBinding(ResultSet rs, Intent intent, boolean requireId) throws SQLException {
        if (!intent.payloadHash().equals(rs.getString("payload_sha256"))
                || !Arrays.equals(Uuids.toBytes(intent.generation()), rs.getBytes("generation"))
                || !intent.destination().webhookId().equals(rs.getString("webhook_id"))
                || !intent.destination().channelId().equals(rs.getString("channel_id"))
                || requireId && !Arrays.equals(Uuids.toBytes(intent.id()), rs.getBytes("intent_id")))
            throw new SQLException("Conflicting send intent");
        String state = rs.getString("state"), message = rs.getString("message_id");
        if (!Set.of("INTENT", "UNCERTAIN", "ACKNOWLEDGED").contains(state)
                || "ACKNOWLEDGED".equals(state) != (message != null)) throw new SQLException("Invalid send intent state");
        if (message != null)
            try { new Destination(message, rs.getString("channel_id")); }
            catch (IllegalArgumentException invalid) { throw new SQLException("Invalid remote receipt id"); }
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid send hash");
    }
    private static String sha(String text) throws SQLException {
        if (text == null) throw new SQLException("Missing held payload");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var ps = c.prepareStatement(sql);
        ps.setQueryTimeout(5);
        return ps;
    }
    private static void warnings(Connection c, PreparedStatement p, ResultSet r) throws SQLException {
        if (c.getWarnings() != null || p.getWarnings() != null || r != null && r.getWarnings() != null)
            throw new SQLException("Incomplete send intent evidence");
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (var c = database.getConnection()) {
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
