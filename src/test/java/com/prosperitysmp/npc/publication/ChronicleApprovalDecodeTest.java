package com.prosperitysmp.npc.publication;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The private approval file is read in one fixed field order; anything else holds delivery. */
class ChronicleApprovalDecodeTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    private static JsonObject story(int index) {
        JsonObject story = new JsonObject();
        story.addProperty("requestId", new UUID(0, 1000 + index).toString());
        story.addProperty("propertyId", new UUID(0, 2000 + index).toString());
        story.addProperty("worldId", ID);
        story.addProperty("regionId", "room_" + index);
        story.addProperty("publicWorldKey", "main");
        story.addProperty("eventKey", String.format("%064x", index + 1));
        story.addProperty("payloadSha256", "e".repeat(64));
        return story;
    }

    private static JsonObject approval(int stories) {
        JsonObject json = new JsonObject();
        json.addProperty("version", "npc-chronicle-approval-v1");
        json.addProperty("approvalRef", "DEV:case-1");
        json.addProperty("generation", ID);
        json.addProperty("expiresAt", "2026-10-30T00:45:00Z");
        json.addProperty("webhookId", "123456789012345678");
        json.addProperty("channelId", "987654321098765432");
        json.addProperty("tokenSha256", "a".repeat(64));
        json.addProperty("bootstrapJarSha256", "b".repeat(64));
        json.addProperty("javaRuntimeVersion", "25.0.3+9-LTS");
        for (String key : new String[]{"startupInputsSha256", "httpLogSha256", "httpUtilsSha256", "httpDebugSha256", "sslLoggerSha256"})
            json.addProperty(key, "c".repeat(64));
        JsonArray array = new JsonArray();
        for (int i = 0; i < stories; i++) array.add(story(i));
        json.add("stories", array);
        return json;
    }

    private static ChronicleRuntime.DeliveryApproval decode(JsonObject json) throws IOException {
        return ChronicleProvisioning.decodeForTest(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void refused(JsonObject json) {
        assertThrows(IOException.class, () -> decode(json));
    }

    @Test
    void anExactFileGivesEveryFieldOfTheDeliveryApproval() throws IOException {
        var parsed = decode(approval(2));
        assertEquals("DEV:case-1", parsed.approvalRef());
        assertEquals(UUID.fromString(ID), parsed.generation());
        assertEquals("123456789012345678", parsed.destination().webhookId());
        assertEquals("987654321098765432", parsed.destination().channelId());
        assertEquals(2, parsed.stories().size());
        assertEquals(new UUID(0, 1001), parsed.stories().get(1).requestId());
        assertEquals(new UUID(0, 2001), parsed.stories().get(1).scope().propertyId());
        assertEquals("room_1", parsed.stories().get(1).scope().regionId());
        assertEquals("main", parsed.stories().get(1).scope().publicWorldKey());
        assertEquals("e".repeat(64), parsed.stories().get(1).held().payloadSha256());
    }

    @Test
    void theLargestAllowedNumberOfStoriesIsAccepted() throws IOException {
        assertEquals(128, decode(approval(128)).stories().size());
    }

    @Test
    void oneStoryOverTheLimitIsRefused() {
        refused(approval(129));
    }

    @Test
    void anApprovalWithNoStoriesIsRefused() {
        refused(approval(0));
    }

    @Test
    void anEmptyOrOversizedFileIsRefused() {
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest(new byte[0]));
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest(new byte[65537]));
    }

    @Test
    void aFileOfTheLargestAllowedSizeThatIsNotJsonIsRefused() {
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest("x".repeat(65536).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aWrongVersionIsRefused() {
        JsonObject json = approval(1);
        json.addProperty("version", "npc-chronicle-approval-v2");
        refused(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "approvalRef", "generation", "expiresAt", "webhookId", "channelId", "tokenSha256",
            "bootstrapJarSha256", "javaRuntimeVersion", "startupInputsSha256", "httpLogSha256", "httpUtilsSha256",
            "httpDebugSha256", "sslLoggerSha256", "stories"})
    void aMissingTopLevelFieldIsRefused(String key) {
        JsonObject json = approval(1);
        json.remove(key);
        refused(json);
    }

    @Test
    void fieldsInAnyOtherOrderAreRefused() {
        JsonObject original = approval(1);
        Map<String, JsonElement> fields = new LinkedHashMap<>();
        original.entrySet().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
        JsonObject swapped = new JsonObject();
        swapped.add("approvalRef", fields.get("approvalRef"));
        swapped.add("version", fields.get("version"));
        fields.forEach((key, value) -> {
            if (!swapped.has(key)) swapped.add(key, value);
        });
        refused(swapped);
    }

    @Test
    void anExtraTopLevelFieldIsRefused() {
        JsonObject json = approval(1);
        json.addProperty("enableEverything", true);
        refused(json);
    }

    @Test
    void anExtraFieldInsideAStoryIsRefused() {
        JsonObject json = approval(1);
        json.getAsJsonArray("stories").get(0).getAsJsonObject().addProperty("note", "x");
        refused(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tokenSha256", "bootstrapJarSha256", "startupInputsSha256", "httpLogSha256", "httpUtilsSha256",
            "httpDebugSha256", "sslLoggerSha256"})
    void aHashThatIsNotSixtyFourLowercaseHexDigitsIsRefused(String key) {
        for (String bad : new String[]{"A".repeat(64), "c".repeat(63), "c".repeat(65), "g".repeat(64), "", " " + "c".repeat(63)}) {
            JsonObject json = approval(1);
            json.addProperty(key, bad);
            refused(json);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventKey", "payloadSha256"})
    void aStoryHashThatIsNotSixtyFourLowercaseHexDigitsIsRefused(String key) {
        for (String bad : new String[]{"E".repeat(64), "e".repeat(63), "z".repeat(64)}) {
            JsonObject json = approval(1);
            json.getAsJsonArray("stories").get(0).getAsJsonObject().addProperty(key, bad);
            refused(json);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0123", "01", "12345678901234567890123", "18446744073709551616", "abc", "-5", "1.5", ""})
    void aDiscordIdThatIsNotAPositiveUnsignedNumberIsRefused(String id) {
        JsonObject webhook = approval(1);
        webhook.addProperty("webhookId", id);
        refused(webhook);
        JsonObject channel = approval(1);
        channel.addProperty("channelId", id);
        refused(channel);
    }

    @Test
    void theLargestUnsignedDiscordIdIsAccepted() throws IOException {
        JsonObject json = approval(1);
        json.addProperty("webhookId", "18446744073709551615");
        assertEquals("18446744073709551615", decode(json).destination().webhookId());
    }

    @Test
    void aWebhookIdGivenAsANumberIsRefused() {
        JsonObject json = approval(1);
        json.addProperty("webhookId", 123456789L);
        refused(json);
    }

    @Test
    void anApprovalReferenceOfNinetySixAllowedCharactersIsAcceptedAndNinetySevenIsNot() throws IOException {
        JsonObject json = approval(1);
        json.addProperty("approvalRef", "r".repeat(96));
        assertEquals("r".repeat(96), decode(json).approvalRef());
        json.addProperty("approvalRef", "r".repeat(97));
        refused(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad ref", "", "a/b", "café", "a,b"})
    void anApprovalReferenceWithOtherCharactersIsRefused(String reference) {
        JsonObject json = approval(1);
        json.addProperty("approvalRef", reference);
        refused(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-30T00:45:00+00:00", "2026-10-30 00:45:00Z", "2026-10-30T00:45Z", "tomorrow", "2026-10-30T00:45:00.000Z"})
    void anExpiryThatIsNotTheCanonicalInstantTextIsRefused(String expiry) {
        JsonObject json = approval(1);
        json.addProperty("expiresAt", expiry);
        refused(json);
    }

    @Test
    void aSubSecondExpiryInCanonicalFormIsAccepted() throws IOException {
        JsonObject json = approval(1);
        json.addProperty("expiresAt", "2026-10-30T00:45:00.500Z");
        decode(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Apt_Room", "has space", "", "a.b"})
    void aRegionOutsideTheAllowedPatternIsRefused(String region) {
        JsonObject json = approval(1);
        json.getAsJsonArray("stories").get(0).getAsJsonObject().addProperty("regionId", region);
        refused(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Main", "_x", "has space", ""})
    void aPublicWorldKeyOutsideTheAllowedPatternIsRefused(String key) {
        JsonObject json = approval(1);
        json.getAsJsonArray("stories").get(0).getAsJsonObject().addProperty("publicWorldKey", key);
        refused(json);
    }

    @Test
    void twoStoriesForTheSameRequestAreRefused() {
        JsonObject json = approval(2);
        json.getAsJsonArray("stories").get(1).getAsJsonObject().addProperty("requestId", new UUID(0, 1000).toString());
        refused(json);
    }

    @Test
    void twoStoriesWithTheSameEventKeyAreRefused() {
        JsonObject json = approval(2);
        json.getAsJsonArray("stories").get(1).getAsJsonObject().addProperty("eventKey", String.format("%064x", 1));
        refused(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-00000000000G", "0-0-0-0-1", "{00000000-0000-0000-0000-000000000001}"})
    void aGenerationOrRequestThatIsNotACanonicalUuidIsRefused(String uuid) {
        JsonObject generation = approval(1);
        generation.addProperty("generation", uuid);
        refused(generation);
        JsonObject request = approval(1);
        request.getAsJsonArray("stories").get(0).getAsJsonObject().addProperty("requestId", uuid);
        refused(request);
    }

    @Test
    void textAfterTheClosingBraceIsRefused() {
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest((approval(1) + "\n{}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest((approval(1) + " x").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aFileThatIsNotAnObjectIsRefused() {
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest("[]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest("null".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ChronicleProvisioning.decodeForTest("{".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theRefusalMessageNeverEchoesTheFileContents() {
        JsonObject json = approval(1);
        json.addProperty("approvalRef", "secret ref with spaces");
        var failure = assertThrows(IOException.class, () -> decode(json));
        assertEquals("Private Chronicle approval unavailable or invalid", failure.getMessage());
    }
}
