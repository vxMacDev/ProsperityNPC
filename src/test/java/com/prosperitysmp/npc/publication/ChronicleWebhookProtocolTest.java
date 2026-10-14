package com.prosperitysmp.npc.publication;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleWebhookProtocolTest {
    private static final String EVENT="a".repeat(64);
    private static final ChronicleWebhookProtocol.Destination DEST=new ChronicleWebhookProtocol.Destination("123","456");
    private static ChronicleAdmissionTemplate.Preview preview(){return new ChronicleAdmissionTemplate.Preview(EVENT,"npc-admission-story-v1",
            "main","apt_room_03",LocalDate.of(2026,9,8),"A new arrival at apt_room_03","A resident moved into apt_room_03 on 2026-09-08.");}
    private static ChronicleWebhookProtocol.Message message(){return ChronicleWebhookProtocol.message(preview());}
    private static String heldPayload(){
        var p=preview();var o=new JsonObject();o.addProperty("eventKey",p.eventKey());o.addProperty("version",p.templateVersion());
        o.addProperty("world",p.world());o.addProperty("region",p.region());o.addProperty("moveDate",p.moveDate().toString());
        o.addProperty("title",p.title());o.addProperty("text",p.text());return o.toString();
    }
    private static String digest(String payload) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void heldPayloadBindsExactBytesToAuthoredWireMessage() throws Exception {
        String payload=heldPayload();
        assertEquals(message().json(),ChronicleWebhookProtocol.fromHeldPayload(payload,digest(payload)).json());
        String hash=digest(payload);
        assertThrows(IllegalArgumentException.class,()->ChronicleWebhookProtocol.fromHeldPayload(payload+" ",hash));
        assertThrows(IllegalArgumentException.class,()->ChronicleWebhookProtocol.fromHeldPayload(payload,"0".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->ChronicleWebhookProtocol.fromHeldPayload(null,hash));
    }
    @Test void matchingHashDoesNotBlessExtraPrivateFieldsDuplicateKeysOrInventedCopy() throws Exception {
        var original=JsonParser.parseString(heldPayload()).getAsJsonObject();
        var privateField=original.deepCopy();privateField.addProperty("ownerBalance",99999);
        var copy=original.deepCopy();copy.addProperty("text","Buy this profitable property now");
        var wrongType=original.deepCopy();wrongType.addProperty("eventKey",123);
        var badDate=original.deepCopy();badDate.addProperty("moveDate","2026-02-30");
        String duplicated=heldPayload().replace("\"eventKey\":","\"eventKey\":\"wrong\",\"eventKey\":");
        for(String payload:List.of(privateField.toString(),copy.toString(),wrongType.toString(),badDate.toString(),duplicated,
                heldPayload()+" {}"," ".repeat(4097))) {
            String hash=digest(payload);
            assertThrows(IllegalArgumentException.class,()->ChronicleWebhookProtocol.fromHeldPayload(payload,hash));
        }
    }
    /** Documented Discord message shape, NOT a captured live API success. No real webhook is called. */
    private static JsonObject response(){
        var o=new JsonObject();o.addProperty("id","789");o.addProperty("channel_id","456");o.addProperty("webhook_id","123");
        o.addProperty("content","");o.addProperty("mention_everyone",false);o.addProperty("tts",false);
        o.add("mentions",new JsonArray());o.add("mention_roles",new JsonArray());o.add("attachments",new JsonArray());
        o.add("embeds",JsonParser.parseString(message().json()).getAsJsonObject().get("embeds"));return o;
    }
    private static Optional<ChronicleWebhookProtocol.Receipt> parse(JsonObject o){return parse(o.toString());}
    private static Optional<ChronicleWebhookProtocol.Receipt> parse(String body){return ChronicleWebhookProtocol.confirmed(200,body.getBytes(StandardCharsets.UTF_8),DEST,message());}

    @Test void boundedAuthoredRequestDisablesMentionsAndUsesConfirmedResponseMode(){
        var json=JsonParser.parseString(message().json()).getAsJsonObject();
        assertEquals("wait=true",ChronicleWebhookProtocol.EXECUTE_QUERY);
        assertEquals(Set.of("embeds","allowed_mentions","tts"),json.keySet());
        assertEquals("{\"parse\":[],\"users\":[],\"roles\":[],\"replied_user\":false}",json.get("allowed_mentions").toString());
        assertFalse(json.get("tts").getAsBoolean());
        assertTrue(json.getAsJsonArray("embeds").get(0).getAsJsonObject().get("description").getAsString().contains("apt\\_room\\_03"));
        assertTrue(message().json().getBytes(StandardCharsets.UTF_8).length<2000);
    }
    @Test void matchingMessageProducesDestinationBoundReceipt(){
        var receipt=parse(response()).orElseThrow();assertEquals(EVENT,receipt.eventKey());assertEquals("789",receipt.messageId());assertEquals(DEST,receipt.destination());
        var o=response();o.addProperty("timestamp","2026-09-08T12:00:00Z");assertTrue(parse(o).isPresent());
    }
    @Test void observedDiscordScanMetadataIsAcceptedButCannotSmuggleContent(){
        for(int version:new int[]{0,4,Integer.MAX_VALUE}) {
            var o=response();o.getAsJsonArray("embeds").get(0).getAsJsonObject().addProperty("content_scan_version",version);
            assertTrue(parse(o).isPresent());
        }
        for(JsonElement invalid:List.of(new JsonPrimitive(-1),new JsonPrimitive(2147483648L),
                new JsonPrimitive(0.5),new JsonPrimitive("0"),new JsonPrimitive(true),JsonNull.INSTANCE,new JsonObject())) {
            var o=response();o.getAsJsonArray("embeds").get(0).getAsJsonObject().add("content_scan_version",invalid);
            assertTrue(parse(o).isEmpty());
        }
        var o=response();var embed=o.getAsJsonArray("embeds").get(0).getAsJsonObject();
        embed.addProperty("content_scan_version",0);embed.addProperty("description","unauthored");
        assertTrue(parse(o).isEmpty());
        embed.addProperty("description",JsonParser.parseString(message().json()).getAsJsonObject().getAsJsonArray("embeds")
                .get(0).getAsJsonObject().get("description").getAsString());
        embed.addProperty("image","extra");assertTrue(parse(o).isEmpty());
    }
    @Test void errorsAndNoContentNeverCertifySuccess(){
        for(int status:new int[]{0,201,204,301,400,401,403,404,429,500,502,503})
            assertTrue(ChronicleWebhookProtocol.confirmed(status,response().toString().getBytes(StandardCharsets.UTF_8),DEST,message()).isEmpty());
        assertTrue(ChronicleWebhookProtocol.confirmed(200,null,DEST,message()).isEmpty());
        assertTrue(ChronicleWebhookProtocol.confirmed(200,new byte[32769],DEST,message()).isEmpty());
        assertTrue(ChronicleWebhookProtocol.confirmed(200,new byte[]{(byte)0xc3,0x28},DEST,message()).isEmpty());
        for(String invalid:List.of("","null","[]","{}","{\"id\":789}",response()+" {}"))assertTrue(parse(invalid).isEmpty());
    }
    @Test void wrongDestinationBodyIdsAndMentionsAreUnconfirmed(){
        for(String field:List.of("id","webhook_id","channel_id","content")) {
            var o=response();o.addProperty(field,field.equals("id") ? "18446744073709551616" : "wrong");assertTrue(parse(o).isEmpty(),field);
        }
        for(String field:List.of("mention_everyone","tts")){var o=response();o.addProperty(field,true);assertTrue(parse(o).isEmpty());}
        for(String field:List.of("mentions","mention_roles","attachments")){var o=response();o.getAsJsonArray(field).add("unexpected");assertTrue(parse(o).isEmpty());}
        for(String field:List.of("title","description")) {
            var o=response();o.getAsJsonArray("embeds").get(0).getAsJsonObject().addProperty(field,"changed");assertTrue(parse(o).isEmpty());
        }
        var o=response();o.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonObject("footer").addProperty("text","changed");assertTrue(parse(o).isEmpty());
        o=response();o.getAsJsonArray("embeds").add(new JsonObject());assertTrue(parse(o).isEmpty());
        for(String field:List.of("id","mentions","tts")){o=response();o.remove(field);assertTrue(parse(o).isEmpty());}
    }
    @Test void duplicateLenientAndDeepJsonCannotMasqueradeAsReceipt(){
        String honest=response().toString();
        assertTrue(parse(honest.replace("\"id\":\"789\"","\"id\":\"wrong\",\"id\":\"789\"")).isEmpty());
        assertTrue(parse(honest.replace("\"text\":","\"text\":\"wrong\",\"text\":")).isEmpty());
        assertTrue(parse(honest.replaceFirst("\\{","{/*comment*/")).isEmpty());
        assertTrue(parse(honest.substring(0,honest.length()-1)+",\"x\":"+"[".repeat(18)+"0"+"]".repeat(18)+"}").isEmpty());
    }
    @Test void matchingTextWithUnauthoredContentDoesNotConfirm(){
        for(String field:List.of("fields","image","thumbnail","author","url","video","provider")) {
            var o=response();o.getAsJsonArray("embeds").get(0).getAsJsonObject().addProperty(field,"extra");
            assertTrue(parse(o).isEmpty(),field);
        }
        var o=response();o.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonObject("footer").addProperty("icon_url","https://example.invalid/extra");
        assertTrue(parse(o).isEmpty());
        for(String field:List.of("components","poll","sticker_items","stickers")) {
            o=response();var extra=new JsonArray();extra.add("extra");o.add(field,extra);assertTrue(parse(o).isEmpty(),field);
        }
        o=response();o.add("components",new JsonArray());o.add("poll",JsonNull.INSTANCE);
        o.getAsJsonArray("embeds").get(0).getAsJsonObject().addProperty("type","rich");assertTrue(parse(o).isPresent());
    }
    @Test void arbitraryCopyOrMentionInjectionCannotBecomeAuthoredMessage(){
        var p=preview();
        assertThrows(IllegalArgumentException.class,()->ChronicleWebhookProtocol.message(new ChronicleAdmissionTemplate.Preview(
                p.eventKey(),p.templateVersion(),p.world(),p.region(),p.moveDate(),p.title(),"@everyone free money")));
        assertThrows(IllegalArgumentException.class,()->new ChronicleWebhookProtocol.Destination("0","456"));
        assertThrows(IllegalArgumentException.class,()->new ChronicleWebhookProtocol.Destination("01","456"));
        assertThrows(IllegalArgumentException.class,()->new ChronicleWebhookProtocol.Destination("18446744073709551616","456"));
    }
}
