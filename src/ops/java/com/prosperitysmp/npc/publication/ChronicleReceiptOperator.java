package com.prosperitysmp.npc.publication;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.Uuids;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Root wrapper authenticates GET and approval. This child has no webhook token, HTTP or send capability. */
public final class ChronicleReceiptOperator {
    private static final Set<String> FIELDS=Set.of("mode","expires_at","database_name","database_user","database_password",
        "database_port","event_key","payload_sha256","intent_id","generation","webhook_id","channel_id","message_id","response");
    private ChronicleReceiptOperator() { }
    public static void main(String[] args) {
        // Third-party connection diagnostics must not expose credential/input data.
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            if(args.length!=0)throw new IllegalArgumentException();
            byte[] bytes=System.in.readNBytes(65537);
            if(bytes.length>65536)throw new IllegalArgumentException();
            Map<String,String> input=decode(bytes);
            String mode=input.get("mode");
            if(!Set.of("inspect","check","apply").contains(mode)
                || !Instant.now().isBefore(Instant.parse(input.get("expires_at")))
                || !input.get("database_name").matches("[A-Za-z0-9_]{1,64}")
                || !input.get("database_user").matches("[A-Za-z0-9_]{1,64}")
                || !input.get("database_port").matches("[1-9][0-9]{0,4}")
                || input.get("database_password").isBlank())throw new IllegalArgumentException();
            int port=Integer.parseInt(input.get("database_port"));
            if(port>65535)throw new IllegalArgumentException();
            var held=new ChronicleOutboxRepository.Held(input.get("event_key"),input.get("payload_sha256"));
            if(!held.eventKey().matches("[0-9a-f]{64}") || !held.payloadSha256().matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            UUID intent=exactUuid(input.get("intent_id")),generation=exactUuid(input.get("generation"));
            var destination=new ChronicleWebhookProtocol.Destination(input.get("webhook_id"),input.get("channel_id"));
            new ChronicleWebhookProtocol.Destination(input.get("message_id"),destination.channelId());
            byte[] response=Base64.getDecoder().decode(input.get("response"));
            if(response.length>32768 || mode.equals("inspect") && response.length!=0)throw new IllegalArgumentException();
            try(var database=new Database()) {
                database.connect(new DatabaseSettings("127.0.0.1",port,input.get("database_name"),input.get("database_user"),
                    input.get("database_password"),1,5000));
                String state;
                ChronicleWebhookProtocol.Message message;
                try(var c=database.getConnection()) {
                    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);c.setAutoCommit(false);
                    try {
                        try(var p=c.prepareStatement("SELECT * FROM npc_chronicle_outbox WHERE event_key=?")) {
                            p.setQueryTimeout(5);p.setString(1,held.eventKey());
                            try(var r=p.executeQuery()) {
                                if(!r.next() || !held.payloadSha256().equals(r.getString("payload_sha256"))
                                    || !"HELD".equals(r.getString("state"))
                                    || !Arrays.equals(Uuids.toBytes(generation),r.getBytes("authorization_generation")))throw new SQLException();
                                message=ChronicleWebhookProtocol.fromHeldPayload(r.getString("public_payload"),held.payloadSha256());
                                if(!message.eventKey().equals(held.eventKey()) || r.next())throw new SQLException();
                                warnings(c,p,r);
                            }
                        }
                        try(var p=c.prepareStatement("SELECT * FROM npc_chronicle_send_intents WHERE event_key=?")) {
                            p.setQueryTimeout(5);p.setString(1,held.eventKey());
                            try(var r=p.executeQuery()) {
                                if(!r.next() || !held.payloadSha256().equals(r.getString("payload_sha256"))
                                    || !Arrays.equals(Uuids.toBytes(intent),r.getBytes("intent_id"))
                                    || !Arrays.equals(Uuids.toBytes(generation),r.getBytes("generation"))
                                    || !destination.webhookId().equals(r.getString("webhook_id"))
                                    || !destination.channelId().equals(r.getString("channel_id")))throw new SQLException();
                                state=r.getString("state");String oldMessage=r.getString("message_id");
                                if(!Set.of("INTENT","UNCERTAIN","ACKNOWLEDGED").contains(state)
                                    || (state.equals("ACKNOWLEDGED") ? !input.get("message_id").equals(oldMessage) : oldMessage!=null)
                                    || r.next())throw new SQLException();
                                warnings(c,p,r);
                            }
                        }
                        c.commit();
                    }catch(Exception failure){c.rollback();throw failure;}
                }
                if(!mode.equals("inspect")) {
                    var receipt=ChronicleWebhookProtocol.confirmed(200,response,destination,message).orElseThrow();
                    if(!receipt.messageId().equals(input.get("message_id")))throw new SQLException();
                    if(mode.equals("apply")) {
                        if(!Instant.now().isBefore(Instant.parse(input.get("expires_at"))))throw new IllegalArgumentException();
                        var saved=new ChronicleSendIntentRepository(database,Clock.systemUTC())
                            .reconcileAuthenticatedResponse(held,intent,generation,destination,200,response);
                        if(!saved.equals(receipt))throw new SQLException();
                    }
                }
                System.out.println("OK "+mode+" "+state+" "+input.get("message_id"));
            }
        }catch(Exception | LinkageError refused){System.out.println("REFUSED");System.exit(1);}
    }
    private static UUID exactUuid(String text) {
        UUID value=UUID.fromString(text);if(!value.toString().equals(text))throw new IllegalArgumentException();return value;
    }
    private static Map<String,String> decode(byte[] bytes) throws IOException {
        var result=new HashMap<String,String>();
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try(var r=new JsonReader(new StringReader(text))) {
            r.setStrictness(Strictness.STRICT);r.beginObject();
            while(r.hasNext()) {
                String key=r.nextName();if(!FIELDS.contains(key) || result.containsKey(key) || r.peek()!=JsonToken.STRING)throw new IOException();
                result.put(key,r.nextString());
            }
            r.endObject();if(r.peek()!=JsonToken.END_DOCUMENT || !result.keySet().equals(FIELDS))throw new IOException();
        }
        return result;
    }
    private static void warnings(Connection c,PreparedStatement p,ResultSet r) throws SQLException {
        if(c.getWarnings()!=null || p.getWarnings()!=null || r.getWarnings()!=null)throw new SQLException();
    }
}
