package com.prosperitysmp.npc.appearance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic payloads signed by ephemeral test keys; these are NOT authored Mojang assets. */
class SignedResidentSkinTest {
    private static final String URL = "https://textures.minecraft.net/texture/" + "a".repeat(64);
    private static final String JSON = "{\"textures\":{\"SKIN\":{\"url\":\"" + URL + "\"}}}";
    private static KeyPair testKey;
    private static KeyPair wrongKey;

    @BeforeAll static void testOnlyKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        testKey = generator.generateKeyPair();
        wrongKey = generator.generateKeyPair();
    }

    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String value) throws Exception {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(testKey.getPrivate());
        signer.update(value.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static SignedResidentSkin synthetic(String json) throws Exception {
        String value = encode(json);
        return SignedResidentSkin.verifyWithKey(value, sign(value), testKey.getPublic());
    }

    private static void rejectsJson(String json) throws Exception {
        String value = encode(json), signature = sign(value);
        assertThrows(IllegalArgumentException.class,
                () -> SignedResidentSkin.verifyWithKey(value, signature, testKey.getPublic()), json);
    }

    @Test void preservesOriginalSignedBytesAndExposesFingerprintNotCitizensKey() throws Exception {
        String value = encode("  " + JSON + "\n"), signature = sign(value);
        SignedResidentSkin result = SignedResidentSkin.verifyWithKey(value, signature, testKey.getPublic());
        assertEquals(value, result.value());
        assertEquals(signature, result.signature());
        assertEquals(URL, result.textureUrl());
        assertEquals("default", result.model());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII))), result.fingerprint());
        assertTrue(result.fingerprint().matches("[0-9a-f]{64}"));
        assertNotEquals(synthetic(JSON).fingerprint(), result.fingerprint());
    }

    @Test void acceptsKnownOptionalProfileMetadataAndModels() throws Exception {
        for (String model : List.of("default", "slim")) {
            String json = "{\"timestamp\":1720000000000,\"profileId\":\"0123456789abcdef0123456789abcdef\","
                    + "\"profileName\":\"TestOnly\",\"signatureRequired\":true,\"textures\":{\"SKIN\":{"
                    + "\"url\":\"" + URL + "\",\"metadata\":{\"model\":\"" + model + "\"}}}}";
            assertEquals(model, synthetic(json).model());
        }
        assertEquals(URL.replace("https:", "http:"), synthetic(JSON.replace("https:", "http:")).textureUrl());
    }

    @Test void rejectsWrongKeyAndTamperingAndDecodedJsonSignatures() throws Exception {
        String value = encode(JSON), signature = sign(value);
        assertThrows(IllegalArgumentException.class,
                () -> SignedResidentSkin.verifyWithKey(value, signature, wrongKey.getPublic()));
        assertThrows(IllegalArgumentException.class,
                () -> SignedResidentSkin.verifyWithKey(encode(JSON + " "), signature, testKey.getPublic()));
        byte[] bad = Base64.getDecoder().decode(signature);
        bad[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SignedResidentSkin.verifyWithKey(value,
                Base64.getEncoder().encodeToString(bad), testKey.getPublic()));
        String decodedSignature = sign(JSON);
        assertThrows(IllegalArgumentException.class,
                () -> SignedResidentSkin.verifyWithKey(value, decodedSignature, testKey.getPublic()));
    }

    @Test void rejectsNoncanonicalBase64AndSizeViolations() throws Exception {
        String value = encode(JSON), signature = sign(value);
        for (String bad : new String[]{null, "", "!", "YQ", "YR==", "YQ==\n", "YQ__", "a".repeat(8193)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SignedResidentSkin.verifyWithKey(bad, signature, testKey.getPublic()));
        }
        for (String bad : new String[]{null, "", signature + "\n", signature.replace("=", ""),
                "YQ==", "a".repeat(2049)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SignedResidentSkin.verifyWithKey(value, bad, testKey.getPublic()));
        }
        // Exact 8192-character value boundary remains usable, including signed JSON whitespace.
        String padded = JSON + " ".repeat(6144 - JSON.length());
        assertEquals(8192, synthetic(padded).value().length());
        rejectsJson(padded + " ");
    }

    @Test void rejectsDuplicateKeysAtEveryObjectLevelIncludingEscapedNames() throws Exception {
        String skin = "{\"url\":\"" + URL + "\"}";
        String textures = "{\"SKIN\":" + skin + "}";
        String metadata = "{\"model\":\"slim\"}";
        String withMetadata = "{\"textures\":{\"SKIN\":{\"url\":\"" + URL
                + "\",\"metadata\":" + metadata + "}}}";
        assertEquals(URL, synthetic(JSON).textureUrl());
        assertEquals("slim", synthetic(withMetadata).model());
        // Either occurrence is valid: first-wins/last-wins parsing must not rescue these payloads.
        for (String json : List.of(
                "{\"timestamp\":1,\"timestamp\":2,\"textures\":" + textures + "}",
                "{\"textures\":" + textures + ",\"textures\":" + textures + "}",
                "{\"textures\":" + textures + ",\"\\u0074extures\":" + textures + "}",
                "{\"textures\":{\"SKIN\":" + skin + ",\"SKIN\":" + skin + "}}",
                "{\"textures\":{\"SKIN\":" + skin + ",\"\\u0053KIN\":" + skin + "}}",
                JSON.replace("\"url\":", "\"url\":\"" + URL + "\",\"url\":"),
                JSON.replace("\"url\":", "\"url\":\"" + URL + "\",\"\\u0075rl\":"),
                withMetadata.replace("\"metadata\":", "\"metadata\":" + metadata + ",\"metadata\":"),
                withMetadata.replace("\"metadata\":", "\"metadata\":" + metadata + ",\"\\u006detadata\":"),
                withMetadata.replace("\"model\":", "\"model\":\"default\",\"model\":"),
                withMetadata.replace("\"model\":", "\"model\":\"default\",\"\\u006dodel\":"))) {
            String value = encode(json), signature = sign(value);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> SignedResidentSkin.verifyWithKey(value, signature, testKey.getPublic()), json);
            assertEquals("Duplicate texture field", error.getMessage(), json);
        }
    }

    @Test void rejectsLenientJsonTrailingDataAndMalformedUtf8() throws Exception {
        for (String json : List.of(JSON + " {}", JSON + " true", "/* comment */" + JSON,
                JSON.replace("\"textures\"", "textures"), JSON.replace('"', '\''),
                JSON.substring(0, JSON.length() - 1) + ",}", "[]", "null", "{\"textures\":NaN}")) {
            rejectsJson(json);
        }
        String value = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xc3, 0x28});
        String signature = sign(value);
        assertThrows(IllegalArgumentException.class,
                () -> SignedResidentSkin.verifyWithKey(value, signature, testKey.getPublic()));
    }

    @Test void rejectsExcessiveDepthFieldsAndUnknownPayloadMembers() throws Exception {
        rejectsJson("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{}}}}}}");
        StringBuilder fields = new StringBuilder("{");
        for (int i = 0; i < 33; i++) fields.append(i == 0 ? "" : ",").append('"').append(i).append("\":true");
        rejectsJson(fields.append('}').toString());
        rejectsJson("{\"" + "a".repeat(65) + "\":true}");
        rejectsJson(JSON.substring(0, JSON.length() - 1) + ",\"publicKey\":\"untrusted\"}");
        rejectsJson("{\"textures\":{\"SKIN\":{\"url\":\"" + URL + "\",\"unknown\":true}}}");
    }

    @Test void requiresSkinAndRejectsCapeExtraAssetsAndModels() throws Exception {
        for (String json : List.of("{}", "{\"textures\":{}}", "{\"textures\":null}",
                "{\"textures\":{\"SKIN\":[]}}", "{\"textures\":{\"SKIN\":{}}}",
                JSON.replace("\"SKIN\"", "\"CAPE\""),
                JSON.replace("\"SKIN\":", "\"CAPE\":{},\"SKIN\":"),
                JSON.replace("\"SKIN\":", "\"ELYTRA\":{},\"SKIN\":"),
                JSON.replace("\"url\":", "\"metadata\":{\"model\":\"wide\"},\"url\":"),
                JSON.replace("\"url\":", "\"metadata\":{\"model\":false},\"url\":"),
                JSON.replace("\"url\":", "\"metadata\":{\"cape\":\"x\"},\"url\":"))) {
            rejectsJson(json);
        }
    }

    @Test void rejectsUnsafeUrlsWithoutNetworkAccess() throws Exception {
        for (String url : List.of(URL.replace("https:", "file:"), URL.replace("https:", "HTTPS:"),
                URL.replace("textures.minecraft.net", "textures.minecraft.net.evil.example"),
                URL.replace("textures.minecraft.net", "TEXTURES.MINECRAFT.NET"),
                URL.replace("textures.minecraft.net", "textures.minecraft.net:443"),
                URL.replace("textures.minecraft.net", "user@textures.minecraft.net"),
                URL + "?x=1", URL + "#x", URL + "/", URL.replace("/texture/", "/texture/../texture/"),
                URL.replace("/texture/", "/%74exture/"), URL.substring(0, URL.length() - 1),
                URL.substring(0, URL.length() - 1) + "A", " " + URL)) {
            rejectsJson(JSON.replace(URL, url));
        }
    }

    @Test void rejectsMistypedOrUnboundedProfileMetadata() throws Exception {
        for (String field : List.of("\"timestamp\":-1", "\"timestamp\":0.5", "\"timestamp\":9223372036854775808",
                "\"timestamp\":\"1\"", "\"profileId\":\"bad\"", "\"profileName\":\"bad name\"",
                "\"profileName\":\"12345678901234567\"", "\"signatureRequired\":\"true\"")) {
            rejectsJson("{" + field + "," + JSON.substring(1));
        }
    }

    @Test void rejectsNon4096TestKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String value = encode(JSON), signature = sign(value);
        assertThrows(IllegalArgumentException.class, () -> SignedResidentSkin.verifyWithKey(value,
                signature, generator.generateKeyPair().getPublic()));
        assertThrows(IllegalArgumentException.class, () -> SignedResidentSkin.verifyWithKey(value, signature, null));
    }

    @Test void pinnedResourceIsActualExpectedKeyAndCannotTrustEphemeralTestKey() throws Exception {
        byte[] resource;
        try (var in = SignedResidentSkin.class.getResourceAsStream("/npc-skin-session-key.b64")) {
            assertNotNull(in, "Parent-owned pinned public key resource must be packaged");
            resource = in.readAllBytes();
        }
        var key = SignedResidentSkin.decodePinnedKey(resource);
        assertEquals(4096, ((RSAPublicKey) key).getModulus().bitLength());
        assertEquals("4a3f31581c5b9bce0e53dd79a98d2da53fa5e116c3a16c3c36db991f152b2554",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())));
        byte[] arbitraryKey = Base64.getEncoder().encode(testKey.getPublic().getEncoded());
        assertThrows(IllegalArgumentException.class, () -> SignedResidentSkin.decodePinnedKey(arbitraryKey));
        byte[] changed = resource.clone();
        changed[10] = changed[10] == 'A' ? (byte) 'B' : (byte) 'A';
        assertThrows(IllegalArgumentException.class, () -> SignedResidentSkin.decodePinnedKey(changed));
        assertThrows(IllegalArgumentException.class, () -> SignedResidentSkin.decodePinnedKey(new byte[2049]));
        String value = encode(JSON), signature = sign(value);
        assertThrows(IllegalArgumentException.class, () -> SignedResidentSkin.verify(value, signature),
                "Synthetic test signatures must NEVER pass production Mojang trust");
    }
}
