package com.prosperitysmp.npc.appearance;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Offline parsing and signature validation only; does not publish or approve artwork. */
public final class ResidentAppearanceManifest {
    private static final int MAX_BYTES = 512 * 1024;
    /** 18 generic occupation keys plus 3 for each of up to 32 cast members. */
    static final int MAX_ASSETS = 114;
    private record Encoded(String value, String signature) { }

    /**
     * Verified skins in the allowed set, plus how many well-formed resident keys were skipped because that resident
     * is not in the active cast. Skipped entries were still parsed and signature-checked.
     */
    public record Result(Map<String, SignedResidentSkin> assets, int skipped) {
        public Result {
            assets = Map.copyOf(assets);
            if (skipped < 0) throw new IllegalArgumentException();
        }
    }

    private ResidentAppearanceManifest() { }

    /** The built-in universe, with no skipping: any key outside it rejects the file, exactly as before. */
    public static Map<String, SignedResidentSkin> read(InputStream input) {
        return read(input, SignedResidentSkin::verify);
    }

    // Test-only verifier injection; production must use the public pinned-verification entry point.
    static Map<String, SignedResidentSkin> read(InputStream input,
            BiFunction<String, String, SignedResidentSkin> testOnlyVerifier) {
        return read(input, new ResidentAppearanceCatalogue().allowedKeys(), key -> false, testOnlyVerifier).assets();
    }

    /**
     * Leaves the caller's stream open, including on failure. No partial result is returned. A key in
     * {@code allowed} is kept. A key outside it that {@code skippable} accepts is verified, then skipped and
     * counted. Any other key, a malformed entry or a bad signature rejects the whole file.
     */
    public static Result read(InputStream input, Set<String> allowed, Predicate<String> skippable) {
        return read(input, allowed, skippable, SignedResidentSkin::verify);
    }

    // Test-only verifier injection; production must use the public pinned-verification entry point.
    static Result read(InputStream input, Set<String> allowed, Predicate<String> skippable,
            BiFunction<String, String, SignedResidentSkin> testOnlyVerifier) {
        try {
            require(input != null && allowed != null && skippable != null && testOnlyVerifier != null);
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            require(bytes.length <= MAX_BYTES);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            require(!text.startsWith("\ufeff"));
            Map<String, Encoded> encoded;
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                encoded = parse(reader, allowed, skippable);
            }
            Map<String, SignedResidentSkin> verified = new LinkedHashMap<>();
            int skipped = 0;
            for (var entry : encoded.entrySet()) {
                SignedResidentSkin skin = testOnlyVerifier.apply(entry.getValue().value(), entry.getValue().signature());
                require(skin != null);
                if (allowed.contains(entry.getKey())) verified.put(entry.getKey(), skin);
                else skipped++;
            }
            return new Result(verified, skipped);
        } catch (IOException | RuntimeException failure) {
            // Parser/verifier/I/O exception messages may contain supplied payloads. Do not relay them.
            throw new IllegalArgumentException("Invalid resident appearance manifest");
        }
    }

    private static Map<String, Encoded> parse(JsonReader reader, Set<String> allowed, Predicate<String> skippable)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT);
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        Map<String, Encoded> assets = null;
        while (reader.hasNext()) {
            String name = reader.nextName();
            require(seen.add(name));
            switch (name) {
                case "version" -> {
                    require(reader.peek() == JsonToken.NUMBER);
                    require(reader.nextString().equals("1"));
                }
                case "assets" -> assets = readAssets(reader, allowed, skippable);
                default -> throw new IllegalArgumentException();
            }
        }
        reader.endObject();
        require(seen.equals(Set.of("version", "assets")) && reader.peek() == JsonToken.END_DOCUMENT);
        return assets;
    }

    private static Map<String, Encoded> readAssets(JsonReader reader, Set<String> allowed, Predicate<String> skippable)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY);
        reader.beginArray();
        Map<String, Encoded> assets = new LinkedHashMap<>();
        while (reader.hasNext()) {
            require(assets.size() < MAX_ASSETS && reader.peek() == JsonToken.BEGIN_OBJECT);
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            String key = null, value = null, signature = null;
            while (reader.hasNext()) {
                String name = reader.nextName();
                require(seen.add(name));
                switch (name) {
                    case "key" -> key = boundedString(reader, 128);
                    case "value" -> value = boundedString(reader, 8192);
                    case "signature" -> signature = boundedString(reader, 2048);
                    default -> throw new IllegalArgumentException();
                }
            }
            reader.endObject();
            require(seen.equals(Set.of("key", "value", "signature")));
            require((allowed.contains(key) || skippable.test(key)) && !assets.containsKey(key));
            assets.put(key, new Encoded(value, signature));
        }
        reader.endArray();
        return assets;
    }

    private static String boundedString(JsonReader reader, int cap) throws IOException {
        require(reader.peek() == JsonToken.STRING);
        String value = reader.nextString();
        require(!value.isEmpty() && value.length() <= cap);
        return value;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException();
    }
}
