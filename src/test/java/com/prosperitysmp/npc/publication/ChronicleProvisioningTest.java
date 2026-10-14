package com.prosperitysmp.npc.publication;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleProvisioningTest {
    private static final String UUID="00000000-0000-0000-0000-000000000001";
    private static JsonObject approval() {
        JsonObject json=new JsonObject();
        json.addProperty("version","npc-chronicle-approval-v1"); json.addProperty("approvalRef","DEV:test-case");
        json.addProperty("generation",UUID); json.addProperty("expiresAt","2026-09-30T00:45:00Z");
        json.addProperty("webhookId","123"); json.addProperty("channelId","456");
        json.addProperty("tokenSha256","a".repeat(64)); json.addProperty("bootstrapJarSha256","b".repeat(64));
        json.addProperty("javaRuntimeVersion","25.0.3+9-LTS");
        for(String key:List.of("startupInputsSha256","httpLogSha256","httpUtilsSha256","httpDebugSha256","sslLoggerSha256"))
            json.addProperty(key,"c".repeat(64));
        JsonObject story=new JsonObject(); story.addProperty("requestId",UUID); story.addProperty("propertyId",UUID);
        story.addProperty("worldId",UUID); story.addProperty("regionId","apt_room_03"); story.addProperty("publicWorldKey","main");
        story.addProperty("eventKey","d".repeat(64)); story.addProperty("payloadSha256","e".repeat(64));
        JsonArray stories=new JsonArray(); stories.add(story); json.add("stories",stories); return json;
    }
    private static byte[] bytes(String json) { return json.getBytes(StandardCharsets.UTF_8); }
    @Test void exactApprovalBindsStoryScopeDestinationAndGeneration() throws Exception {
        var parsed=ChronicleProvisioning.decodeForTest(bytes(approval().toString()));
        assertEquals("DEV:test-case",parsed.approvalRef()); assertEquals(UUID,parsed.generation().toString());
        assertEquals("123",parsed.destination().webhookId()); assertEquals("456",parsed.destination().channelId());
        assertEquals(1,parsed.stories().size()); assertEquals("apt_room_03",parsed.stories().getFirst().scope().regionId());
        assertEquals("e".repeat(64),parsed.stories().getFirst().held().payloadSha256());
    }
    @Test void unknownDuplicateAndNoncanonicalApprovalCannotAuthorize() {
        String valid=approval().toString();
        for(String invalid:List.of(valid.replace("\"version\":","\"version\":\"wrong\",\"version\":"),
                valid.replace("\"approvalRef\":","\"arbitraryEnable\":true,\"approvalRef\":"),
                valid.replace("2026-09-30T00:45:00Z","2026-09-30T00:45:00+00:00"),valid+" {}"))
            assertThrows(IOException.class,()->ChronicleProvisioning.decodeForTest(bytes(invalid)));
        assertThrows(IOException.class,()->ChronicleProvisioning.decodeForTest(new byte[65537]));
        assertThrows(IOException.class,()->ChronicleProvisioning.decodeForTest(new byte[]{(byte)0xc3,0x28}));
    }
    @Test void duplicateStoryAndInvalidHashCannotAuthorize() {
        JsonObject duplicate=approval(); duplicate.getAsJsonArray("stories").add(duplicate.getAsJsonArray("stories").get(0).deepCopy());
        assertThrows(IOException.class,()->ChronicleProvisioning.decodeForTest(bytes(duplicate.toString())));
        JsonObject invalid=approval(); invalid.addProperty("tokenSha256","A".repeat(64));
        assertThrows(IOException.class,()->ChronicleProvisioning.decodeForTest(bytes(invalid.toString())));
    }
}
