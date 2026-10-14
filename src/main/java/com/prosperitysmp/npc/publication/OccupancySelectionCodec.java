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
import java.util.*;

/** Private, bounded, nonrecursive selection metadata codec. Not an artifact verifier or authorization source. */
final class OccupancySelectionCodec {
    static final int MAX_BYTES = 4096;
    private static final String VERSION = "npc-occupancy-selection-v1";
    private OccupancySelectionCodec() { }

    static byte[] encode(OccupancyGenerationHistory history, UUID expectedAuthorization) {
        history.protectedNames(expectedAuthorization);
        var root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("authorizationGeneration", expectedAuthorization.toString());
        var entries = new JsonArray();
        for (var e : history.entries()) {
            var r = e.receipt();
            var item = new JsonObject();
            item.addProperty("name", r.name());
            item.addProperty("sha256", r.sha256());
            item.addProperty("bytes", r.bytes());
            item.addProperty("capturedAt", r.capturedAt().toString());
            item.addProperty("selectedAt", e.selectedAt().toString());
            item.addProperty("expiresAt", e.expiresAt().toString());
            entries.add(item);
        }
        root.add("entries", entries);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Selection metadata exceeds bound");
        return bytes;
    }

    static OccupancyGenerationHistory decode(byte[] bytes, UUID expectedAuthorization) throws IOException {
        if (expectedAuthorization == null) throw invalid();
        Inspection inspected = inspectForReset(bytes);
        if (!expectedAuthorization.equals(inspected.recordedGeneration())) throw invalid();
        return inspected.history();
    }

    /** Untrusted recorded identity, usable only to revoke/reset old state; never adopt it as live authorization. */
    record Inspection(UUID recordedGeneration, OccupancyGenerationHistory history) { }

    static Inspection inspectForReset(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var r = new JsonReader(new StringReader(text))) {
                r.setStrictness(Strictness.STRICT);
                token(r, JsonToken.BEGIN_OBJECT);
                r.beginObject();
                name(r, "version");
                if (!VERSION.equals(string(r, 32))) throw invalid();
                name(r, "authorizationGeneration");
                String generation = string(r, 36);
                UUID recorded = UUID.fromString(generation);
                if (!recorded.toString().equals(generation)) throw invalid();
                name(r, "entries");
                token(r, JsonToken.BEGIN_ARRAY);
                r.beginArray();
                var entries = new ArrayList<OccupancyGenerationHistory.Entry>();
                while (r.peek() != JsonToken.END_ARRAY) {
                    if (entries.size() >= 3) throw invalid();
                    token(r, JsonToken.BEGIN_OBJECT);
                    r.beginObject();
                    name(r, "name");
                    String file = string(r, 69);
                    name(r, "sha256");
                    String hash = string(r, 64);
                    name(r, "bytes");
                    token(r, JsonToken.NUMBER);
                    String size = r.nextString();
                    if (!size.matches("[1-9][0-9]{0,5}")) throw invalid();
                    name(r, "capturedAt");
                    Instant captured = instant(r);
                    name(r, "selectedAt");
                    Instant selected = instant(r);
                    name(r, "expiresAt");
                    Instant expires = instant(r);
                    token(r, JsonToken.END_OBJECT);
                    r.endObject();
                    entries.add(new OccupancyGenerationHistory.Entry(new AtomicOccupancyArtifacts.Receipt(
                            file, hash, captured, Integer.parseInt(size)), selected, expires));
                }
                r.endArray();
                token(r, JsonToken.END_OBJECT);
                r.endObject();
                token(r, JsonToken.END_DOCUMENT);
                var history = OccupancyGenerationHistory.restore(recorded, entries);
                if (!Arrays.equals(bytes, encode(history, recorded))) throw invalid();
                return new Inspection(recorded, history);
            }
        } catch (RuntimeException malformed) { throw invalid(); }
    }

    private static Instant instant(JsonReader r) throws IOException {
        String text = string(r, 32);
        Instant time = Instant.parse(text);
        if (!time.toString().equals(text)) throw invalid();
        return time;
    }
    private static void token(JsonReader r, JsonToken expected) throws IOException {
        if (r.peek() != expected) throw invalid();
    }
    private static void name(JsonReader r, String expected) throws IOException {
        token(r, JsonToken.NAME);
        if (!r.nextName().equals(expected)) throw invalid();
    }
    private static String string(JsonReader r, int max) throws IOException {
        token(r, JsonToken.STRING);
        String value = r.nextString();
        if (value.length() > max) throw invalid();
        return value;
    }
    private static IOException invalid() { return new IOException("Invalid private occupancy selection metadata"); }
}
