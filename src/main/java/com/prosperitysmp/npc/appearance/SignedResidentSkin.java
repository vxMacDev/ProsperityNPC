package com.prosperitysmp.npc.appearance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** Verified property bytes, not approval of the artwork or a guarantee of client availability. */
public final class SignedResidentSkin {
    private static final String KEY_RESOURCE = "/npc-skin-session-key.b64";
    private static final String KEY_SHA = "4a3f31581c5b9bce0e53dd79a98d2da53fa5e116c3a16c3c36db991f152b2554";
    private static final int MAX_KEY_BYTES = 2048;
    private final String value;
    private final String signature;
    private final String textureUrl;
    private final String model;
    private final String fingerprint;

    private SignedResidentSkin(String value, String signature, String textureUrl, String model) {
        this.value = value;
        this.signature = signature;
        this.textureUrl = textureUrl;
        this.model = model;
        this.fingerprint = sha256(value.getBytes(StandardCharsets.US_ASCII));
    }

    public String value() {
        return value;
    }

    public String signature() {
        return signature;
    }

    public String textureUrl() {
        return textureUrl;
    }

    public String model() {
        return model;
    }

    public String fingerprint() {
        return fingerprint;
    }

    /** Offline only. Invalid input or unavailable/mismatched trust material fails closed. */
    public static SignedResidentSkin verify(String value, String signature) {
        return verifyWithKey(value, signature, loadPinnedKey());
    }

    // Test-only trust injection. Production callers must use the public, pinned-key entry point.
    static SignedResidentSkin verifyWithKey(String value, String signature, PublicKey testOnlyKey) {
        byte[] payload = canonicalBase64(value, 8192);
        byte[] signed = canonicalBase64(signature, 2048);
        requireRsa4096(testOnlyKey);
        require(signed.length == 512, "Invalid RSA signature length");
        try {
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(testOnlyKey);
            // Mojang signs the original encoded ASCII value, not decoded or rewritten JSON.
            verifier.update(value.getBytes(StandardCharsets.US_ASCII));
            require(verifier.verify(signed), "Invalid texture signature");
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Texture signature verification failed", e);
        }
        JsonObject root = parsePayload(payload);
        onlyKeys(root, Set.of("textures", "timestamp", "profileId", "profileName", "signatureRequired"));
        validateProfileMetadata(root);
        JsonObject textures = object(root.get("textures"));
        onlyKeys(textures, Set.of("SKIN"));
        JsonObject skin = object(textures.get("SKIN"));
        onlyKeys(skin, Set.of("url", "metadata"));
        String url = string(skin.get("url"));
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid texture URL", e);
        }
        require(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && "textures.minecraft.net".equals(uri.getRawAuthority())
                && uri.getRawPath() != null && uri.getRawPath().matches("/texture/[0-9a-f]{64}")
                && uri.getRawQuery() == null && uri.getRawFragment() == null, "Unapproved texture URL");
        String model = "default";
        if (skin.has("metadata")) {
            JsonObject metadata = object(skin.get("metadata"));
            onlyKeys(metadata, Set.of("model"));
            if (metadata.has("model")) model = string(metadata.get("model"));
        }
        require(model.equals("default") || model.equals("slim"), "Unknown skin model");
        return new SignedResidentSkin(value, signature, url, model);
    }

    private static PublicKey loadPinnedKey() {
        try (var in = SignedResidentSkin.class.getResourceAsStream(KEY_RESOURCE)) {
            require(in != null, "Missing pinned skin key resource");
            return decodePinnedKey(in.readNBytes(MAX_KEY_BYTES + 1));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read pinned skin key", e);
        }
    }

    static PublicKey decodePinnedKey(byte[] resource) {
        require(resource != null && resource.length <= MAX_KEY_BYTES, "Invalid skin key resource size");
        // A text resource may have a final newline; embedded whitespace is still rejected.
        String text = new String(resource, StandardCharsets.US_ASCII).strip();
        byte[] der = canonicalBase64(text, MAX_KEY_BYTES);
        require(KEY_SHA.equals(sha256(der)), "Pinned skin key hash mismatch");
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            requireRsa4096(key);
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid pinned RSA key", e);
        }
    }

    private static void requireRsa4096(PublicKey key) {
        require(key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() == 4096,
                "A 4096-bit RSA key is required");
    }

    private static byte[] canonicalBase64(String text, int cap) {
        require(text != null && !text.isEmpty() && text.length() <= cap, "Invalid Base64 size");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64", e);
        }
        require(Base64.getEncoder().encodeToString(bytes).equals(text), "Noncanonical Base64");
        return bytes;
    }

    private static JsonObject parsePayload(byte[] payload) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                JsonObject root = readObject(reader, 1, new int[1]);
                require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing texture payload data");
                return root;
            }
        } catch (IOException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid texture JSON", e);
        }
    }

    private static JsonObject readObject(JsonReader reader, int depth, int[] fields) throws IOException {
        require(depth <= 4 && reader.peek() == JsonToken.BEGIN_OBJECT, "Invalid texture object/depth");
        reader.beginObject();
        JsonObject result = new JsonObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            require(++fields[0] <= 32 && name.length() <= 64, "Too many/oversized texture fields");
            require(!result.has(name), "Duplicate texture field");
            JsonElement value = switch (reader.peek()) {
                case BEGIN_OBJECT -> readObject(reader, depth + 1, fields);
                case STRING -> new JsonPrimitive(reader.nextString());
                case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
                case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
                default -> throw new IllegalArgumentException("Unsupported texture JSON value");
            };
            result.add(name, value);
        }
        reader.endObject();
        return result;
    }

    private static void validateProfileMetadata(JsonObject root) {
        if (root.has("timestamp")) {
            JsonElement time = root.get("timestamp");
            require(time.isJsonPrimitive() && time.getAsJsonPrimitive().isNumber(), "Invalid timestamp");
            try {
                require(time.getAsBigDecimal().longValueExact() >= 0, "Negative timestamp");
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Invalid timestamp", e);
            }
        }
        if (root.has("profileId")) require(string(root.get("profileId")).matches("[0-9a-fA-F]{32}"), "Invalid profile ID");
        if (root.has("profileName")) require(string(root.get("profileName")).matches("[A-Za-z0-9_]{1,16}"), "Invalid profile name");
        if (root.has("signatureRequired")) {
            JsonElement flag = root.get("signatureRequired");
            require(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean(), "Invalid signatureRequired");
        }
    }

    private static JsonObject object(JsonElement value) {
        require(value != null && value.isJsonObject(), "Required texture object missing");
        return value.getAsJsonObject();
    }

    private static String string(JsonElement value) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Required texture string missing");
        return value.getAsString();
    }

    private static void onlyKeys(JsonObject object, Set<String> allowed) {
        require(allowed.containsAll(object.keySet()), "Unknown texture fields/assets");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
