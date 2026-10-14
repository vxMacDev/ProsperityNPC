package com.prosperitysmp.npc.publication;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Fixed, nonrecursive public schema verifier for destructive retention preflight. */
final class PublicOccupancyArtifactCodec {
    private static final Pattern WORLD = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern REGION = Pattern.compile("[a-z0-9_-]{1,128}");
    private PublicOccupancyArtifactCodec() { }

    static Instant verify(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > 128 * 1024) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                token(reader, JsonToken.BEGIN_OBJECT); reader.beginObject();
                name(reader, "version");
                String version = string(reader, 32);
                if (!version.equals("npc-public-occupancy-v1")) throw invalid();
                name(reader, "capturedAt");
                String captured = string(reader, 32);
                Instant instant = Instant.parse(captured);
                if (!instant.toString().equals(captured) || instant.getNano() % 1_000_000 != 0
                        || instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                        || !instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw invalid();
                name(reader, "properties"); token(reader, JsonToken.BEGIN_ARRAY); reader.beginArray();
                var properties = new JsonArray();
                String previousWorld = null, previousRegion = null;
                while (reader.peek() != JsonToken.END_ARRAY) {
                    if (properties.size() >= 512) throw invalid();
                    token(reader, JsonToken.BEGIN_OBJECT); reader.beginObject();
                    name(reader, "world"); String world = string(reader, 64);
                    name(reader, "region"); String region = string(reader, 128);
                    name(reader, "occupancy"); String occupancy = string(reader, 7);
                    if (!WORLD.matcher(world).matches() || !REGION.matcher(region).matches()
                            || !(occupancy.equals("EMPTY") || occupancy.equals("PARTIAL") || occupancy.equals("FULL"))) throw invalid();
                    token(reader, JsonToken.END_OBJECT); reader.endObject();
                    if (previousWorld != null && (previousWorld.compareTo(world) > 0
                            || (previousWorld.equals(world) && previousRegion.compareTo(region) >= 0))) throw invalid();
                    previousWorld = world; previousRegion = region;
                    var entry = new JsonObject();
                    entry.addProperty("world", world); entry.addProperty("region", region); entry.addProperty("occupancy", occupancy);
                    properties.add(entry);
                }
                if (properties.isEmpty()) throw invalid();
                reader.endArray(); token(reader, JsonToken.END_OBJECT); reader.endObject();
                token(reader, JsonToken.END_DOCUMENT);
                var canonical = new JsonObject();
                canonical.addProperty("version", version); canonical.addProperty("capturedAt", captured);
                canonical.add("properties", properties);
                if (!Arrays.equals(bytes, canonical.toString().getBytes(StandardCharsets.UTF_8))) throw invalid();
                return instant;
            }
        } catch (RuntimeException failure) { throw invalid(); }
    }

    private static void token(JsonReader reader, JsonToken expected) throws IOException {
        if (reader.peek() != expected) throw invalid();
    }
    private static void name(JsonReader reader, String expected) throws IOException {
        token(reader, JsonToken.NAME);
        if (!reader.nextName().equals(expected)) throw invalid();
    }
    private static String string(JsonReader reader, int maximum) throws IOException {
        token(reader, JsonToken.STRING);
        String value = reader.nextString();
        if (value.length() > maximum) throw invalid();
        return value;
    }
    private static IOException invalid() { return new IOException("Invalid canonical occupancy artifact"); }
}
