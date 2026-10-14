package com.prosperitysmp.npc.publication;

import com.google.gson.JsonObject;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Private held drafts only. No discovery cursor, authorization decision, delivery or source mutation. */
public final class ChronicleOutboxRepository {
    public record Held(String eventKey,String payloadSha256) { }
    private final Database database;
    private final Clock clock;
    private final ZoneId zone;
    private final ChronicleAdmissionRepository admissions;

    private final boolean receiptRevisionEvidence;

    public ChronicleOutboxRepository(Database database,Clock clock,ZoneId zone,int resetHour) {
        this(database,clock,zone,resetHour,false);
    }
    /** receiptRevisionEvidence=true: the move receipt's own property revision is the evidence; later edits do not stale it. */
    public ChronicleOutboxRepository(Database database,Clock clock,ZoneId zone,int resetHour,boolean receiptRevisionEvidence) {
        this.receiptRevisionEvidence=receiptRevisionEvidence;
        this.database=Objects.requireNonNull(database);this.clock=Objects.requireNonNull(clock);
        this.zone=Objects.requireNonNull(zone);admissions=new ChronicleAdmissionRepository(database,clock,zone,resetHour);
    }

    /** Internal worker API, not an endpoint. Empty/default configuration must never call this. */
    public Held stage(UUID requestId,Approval mapping,UUID authorizationGeneration) throws SQLException {
        Objects.requireNonNull(requestId);Objects.requireNonNull(mapping);Objects.requireNonNull(authorizationGeneration);
        try(Connection c=database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try {
                Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
                Receipt receipt=admissions.readInTransaction(c,at,List.of(requestId)).receipts().get(requestId);
                verifyMapping(c,receipt,mapping,receiptRevisionEvidence);
                var preview=ChronicleAdmissionTemplate.render(receipt,mapping,zone);
                String payload=payload(preview), payloadHash=sha(payload);
                String sourceHash=sha(receipt.request().fingerprint(receipt.policy())+"|"+receipt.tenancyRevisionAfter()
                        +"|"+receipt.cooldownUntil()+"|"+receipt.appliedAt());
                try(PreparedStatement ps=c.prepareStatement("""
                        INSERT INTO npc_chronicle_outbox(request_id,event_key,source_sha256,authorization_generation,
                          public_payload,payload_sha256,captured_at,state) VALUES (?,?,?,?,?,?,?,'HELD')
                        ON DUPLICATE KEY UPDATE event_key=event_key
                        """)) {
                    ps.setQueryTimeout(5);ps.setBytes(1,Uuids.toBytes(requestId));ps.setString(2,preview.eventKey());
                    ps.setString(3,sourceHash);ps.setBytes(4,Uuids.toBytes(authorizationGeneration));ps.setString(5,payload);
                    ps.setString(6,payloadHash);ps.setObject(7,LocalDateTime.ofInstant(at,ZoneOffset.UTC));
                    ps.executeUpdate();warnings(c,ps,null);
                }
                // Never trust affected-row counts: replay must match every immutable field under the row lock.
                try(PreparedStatement ps=c.prepareStatement("SELECT * FROM npc_chronicle_outbox WHERE request_id=? FOR UPDATE")) {
                    ps.setQueryTimeout(5);ps.setBytes(1,Uuids.toBytes(requestId));
                    try(ResultSet rs=ps.executeQuery()) {
                        warnings(c,ps,rs);
                        if(!rs.next() || !preview.eventKey().equals(rs.getString("event_key"))
                                || !sourceHash.equals(rs.getString("source_sha256"))
                                || !Arrays.equals(Uuids.toBytes(authorizationGeneration),rs.getBytes("authorization_generation"))
                                || !payload.equals(rs.getString("public_payload")) || !payloadHash.equals(rs.getString("payload_sha256"))
                                || !"HELD".equals(rs.getString("state")))throw new SQLException("Chronicle draft conflict");
                        if(rs.next())throw new SQLException("Duplicate Chronicle draft");warnings(c,ps,rs);
                    }
                }
                c.commit();return new Held(preview.eventKey(),payloadHash);
            } catch(SQLException | RuntimeException | Error failure) {
                try {c.rollback();}catch(SQLException cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
        }
    }

    private static void verifyMapping(Connection c,Receipt receipt,Approval mapping,boolean receiptEvidence) throws SQLException {
        if(!receipt.request().propertyId().equals(mapping.propertyId()) || !receipt.request().worldId().equals(mapping.worldId()))
            throw new SQLException("Chronicle property mapping mismatch");
        try(PreparedStatement ps=c.prepareStatement("SELECT world_uuid,region_id,row_version,enabled FROM npc_properties WHERE property_id=?")) {
            ps.setQueryTimeout(5);ps.setBytes(1,Uuids.toBytes(mapping.propertyId()));
            try(ResultSet rs=ps.executeQuery()) {
                warnings(c,ps,rs);
                if(!rs.next() || !Arrays.equals(Uuids.toBytes(mapping.worldId()),rs.getBytes("world_uuid"))
                        || !mapping.regionId().equalsIgnoreCase(rs.getString("region_id")))
                    throw new SQLException("Chronicle property mapping mismatch");
                long revision=rs.getLong("row_version");
                // Revisions only grow: a later edit is fine, a row older than the receipt cannot have hosted the move.
                if(rs.wasNull() || (receiptEvidence ? revision<receipt.request().propertyRevision() : revision!=receipt.request().propertyRevision()))
                    throw new SQLException("Chronicle historical property mapping needs review");
                int enabled=rs.getInt("enabled");
                if(rs.wasNull() || enabled!=1 || rs.next())throw new SQLException("Chronicle property unavailable");
                warnings(c,ps,rs);
            }
        }
    }

    private static String payload(ChronicleAdmissionTemplate.Preview preview) {
        var json=new JsonObject();json.addProperty("eventKey",preview.eventKey());json.addProperty("version",preview.templateVersion());
        json.addProperty("world",preview.world());json.addProperty("region",preview.region());
        json.addProperty("moveDate",preview.moveDate().toString());json.addProperty("title",preview.title());json.addProperty("text",preview.text());
        String result=json.toString();
        if(result.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("Chronicle payload too large");
        return result;
    }
    private static String sha(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void warnings(Connection c,PreparedStatement ps,ResultSet rs) throws SQLException {
        if(c.getWarnings()!=null || ps.getWarnings()!=null || rs!=null && rs.getWarnings()!=null)
            throw new SQLException("Incomplete Chronicle staging evidence");
    }
}
