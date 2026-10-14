package com.prosperitysmp.npc.publication;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

/** Wire format only. Neither source verification, publication permission nor a durable delivery journal. */
public final class ChronicleWebhookProtocol {
    public static final String EXECUTE_QUERY="wait=true";
    private static final int MAX_RESPONSE_BYTES=32*1024;
    private ChronicleWebhookProtocol(){ }

    public record Destination(String webhookId,String channelId) {
        public Destination {snowflake(webhookId);snowflake(channelId);}
    }
    public record Receipt(String eventKey,String messageId,Destination destination) { }
    public static final class Message {
        private final String eventKey,title,description,footer,json;
        private Message(String eventKey,String title,String description,String footer,String json) {
            this.eventKey=eventKey;this.title=title;this.description=description;this.footer=footer;this.json=json;
        }
        public String json(){return json;}
        public String eventKey(){return eventKey;}
    }

    /** Rebuild only from the bounded, exact hash-bound held schema. Not evidence of current authorization. */
    static Message fromHeldPayload(String payload,String expectedHash) {
        try {
            if(payload==null || payload.length()>4096 || expectedHash==null || !expectedHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid held payload");
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>4096 || !expectedHash.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalArgumentException("Held payload hash mismatch");
            JsonObject o;
            try(var r=new JsonReader(new StringReader(payload))) {
                r.setStrictness(Strictness.STRICT);o=read(r,0).getAsJsonObject();
                if(r.peek()!=JsonToken.END_DOCUMENT || !o.keySet().equals(Set.of("eventKey","version","world","region","moveDate","title","text")))
                    throw new IllegalArgumentException("Invalid held payload schema");
            }
            String date=string(o,"moveDate");LocalDate parsed=LocalDate.parse(date);
            if(!parsed.toString().equals(date))throw new IllegalArgumentException("Invalid held date");
            return message(new ChronicleAdmissionTemplate.Preview(string(o,"eventKey"),string(o,"version"),string(o,"world"),
                    string(o,"region"),parsed,string(o,"title"),string(o,"text")));
        }catch(IOException | RuntimeException failure){throw new IllegalArgumentException("Invalid held Chronicle payload");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    /** Accepts only the exact authored v1 shape; a caller must separately verify its receipt and policy. */
    public static Message message(ChronicleAdmissionTemplate.Preview p) {
        Objects.requireNonNull(p);
        if(p.eventKey()==null || !p.eventKey().matches("[0-9a-f]{64}")
                || !"npc-admission-story-v1".equals(p.templateVersion())
                || p.world()==null || !p.world().matches("[a-z0-9][a-z0-9_-]{0,63}")
                || p.region()==null || !p.region().matches("[a-z0-9_-]{1,128}")
                || p.moveDate()==null || p.moveDate().getYear()<1 || p.moveDate().getYear()>9999
                || !("A new arrival at "+p.region()).equals(p.title())
                || !("A resident moved into "+p.region()+" on "+p.moveDate()+".").equals(p.text()))
            throw new IllegalArgumentException("Invalid authored Chronicle message");
        String description=p.text().replace("_","\\_")+"\nWorld: "+p.world().replace("_","\\_");
        String footer="Chronicle reference: "+p.eventKey();
        var embed=new JsonObject();embed.addProperty("title",p.title());embed.addProperty("description",description);
        var foot=new JsonObject();foot.addProperty("text",footer);embed.add("footer",foot);
        var embeds=new JsonArray();embeds.add(embed);
        var mentions=new JsonObject();mentions.add("parse",new JsonArray());mentions.add("users",new JsonArray());
        mentions.add("roles",new JsonArray());mentions.addProperty("replied_user",false);
        var root=new JsonObject();root.add("embeds",embeds);root.add("allowed_mentions",mentions);root.addProperty("tts",false);
        return new Message(p.eventKey(),p.title(),description,footer,root.toString());
    }

    /** Empty means unconfirmed, NOT safe to retry. Transport must bound reads before allocating this array. */
    public static Optional<Receipt> confirmed(int status,byte[] response,Destination destination,Message sent) {
        Objects.requireNonNull(destination);Objects.requireNonNull(sent);
        if(status!=200 || response==null || response.length==0 || response.length>MAX_RESPONSE_BYTES)return Optional.empty();
        try {
            String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response)).toString();
            JsonObject root;
            try(var reader=new JsonReader(new StringReader(body))) {
                reader.setStrictness(Strictness.STRICT);root=read(reader,0).getAsJsonObject();
                if(reader.peek()!=JsonToken.END_DOCUMENT)return Optional.empty();
            }
            String id=string(root,"id");snowflake(id);
            if(!destination.webhookId().equals(string(root,"webhook_id")) || !destination.channelId().equals(string(root,"channel_id"))
                    || !"".equals(string(root,"content")) || !isFalse(root,"mention_everyone") || !isFalse(root,"tts")
                    || !emptyArray(root,"mentions") || !emptyArray(root,"mention_roles") || !emptyArray(root,"attachments")
                    || root.has("components") && !emptyArray(root,"components")
                    || root.has("poll") && !root.get("poll").isJsonNull()
                    || root.has("sticker_items") && !emptyArray(root,"sticker_items")
                    || root.has("stickers") && !emptyArray(root,"stickers"))return Optional.empty();
            JsonArray embeds=root.getAsJsonArray("embeds");if(embeds.size()!=1)return Optional.empty();
            JsonObject embed=embeds.get(0).getAsJsonObject();
            JsonObject footer=embed.getAsJsonObject("footer");
            if(!Set.of("title","description","footer","type","content_scan_version").containsAll(embed.keySet())
                    || embed.has("content_scan_version") && !scanVersion(embed.get("content_scan_version"))
                    || !footer.keySet().equals(Set.of("text")) || embed.has("type") && !"rich".equals(string(embed,"type"))
                    || !sent.title.equals(string(embed,"title")) || !sent.description.equals(string(embed,"description"))
                    || !sent.footer.equals(string(footer,"text")))return Optional.empty();
            return Optional.of(new Receipt(sent.eventKey,id,destination));
        }catch(IOException | RuntimeException invalid){return Optional.empty();}
    }

    private static String string(JsonObject object,String name) {
        JsonElement value=object.get(name);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing wire string");
        return value.getAsString();
    }
    private static boolean isFalse(JsonObject o,String key) {
        var e=o.get(key);return e!=null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && !e.getAsBoolean();
    }
    private static boolean emptyArray(JsonObject o,String key){var e=o.get(key);return e!=null && e.isJsonArray() && e.getAsJsonArray().isEmpty();}
    // Observed Discord response metadata, not authored/displayed content. Unknown content stays rejected.
    private static boolean scanVersion(JsonElement value) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())return false;
        String number=value.getAsString();
        if(!number.matches("0|[1-9][0-9]{0,9}"))return false;
        try{return Long.parseLong(number)<=Integer.MAX_VALUE;}catch(NumberFormatException invalid){return false;}
    }
    private static void snowflake(String id) {
        if(id==null || !id.matches("[1-9][0-9]{0,19}"))throw new IllegalArgumentException("Invalid Discord id");
        try{Long.parseUnsignedLong(id);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid Discord id");}
    }
    /** Strict duplicate rejection, including nested fields; depth is bounded before descending. */
    private static JsonElement read(JsonReader r,int depth) throws IOException {
        if(depth>16)throw new IOException("Wire nesting limit");
        return switch(r.peek()) {
            case BEGIN_OBJECT -> {
                var o=new JsonObject();r.beginObject();while(r.hasNext()) {
                    String key=r.nextName();if(o.has(key))throw new IOException("Duplicate wire field");o.add(key,read(r,depth+1));
                }r.endObject();yield o;
            }
            case BEGIN_ARRAY -> {
                var a=new JsonArray();r.beginArray();while(r.hasNext())a.add(read(r,depth+1));r.endArray();yield a;
            }
            case STRING -> new JsonPrimitive(r.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(r.nextString()));
            case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
            case NULL -> {r.nextNull();yield JsonNull.INSTANCE;}
            default -> throw new IOException("Invalid wire token");
        };
    }
}
