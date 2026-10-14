package com.prosperitysmp.npc.publication;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PublicOccupancyArtifactCodecTest {
    private static final String ENTRY = "{\"world\":\"main\",\"region\":\"_c1\",\"occupancy\":\"PARTIAL\"}";
    private static String json(String entries) {
        return "{\"version\":\"npc-public-occupancy-v1\",\"capturedAt\":\"2026-09-08T12:00:00Z\",\"properties\":[" + entries + "]}";
    }
    private static void verify(String value) throws IOException {
        PublicOccupancyArtifactCodec.verify(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test void canonicalKnownSchemaAcceptsAllStates() {
        for (String state : List.of("EMPTY", "PARTIAL", "FULL"))
            assertDoesNotThrow(() -> verify(json(ENTRY.replace("PARTIAL", state))));
    }

    @Test void unknownDuplicateReorderedOrWrongTokensRejectWithoutGenericDescent() {
        String valid = json(ENTRY);
        String deeplyNested = valid.replace("\"main\"", "[".repeat(1000) + "0" + "]".repeat(1000));
        assertTrue(deeplyNested.getBytes(StandardCharsets.UTF_8).length <= 128 * 1024);
        for (String bad : List.of("{}", "[]", "null", json(""),
                valid.replace("\"version\":", "\"extra\":[],\"version\":"),
                valid.replace("\"world\":\"main\"", "\"world\":\"main\",\"world\":\"main\""),
                valid.replace("\"world\":\"main\"", "\"world\":\"main\",\"w\\u006frld\":\"main\""),
                valid.replace("\"occupancy\":", "\"\\u006fwner\":\"private\",\"occupancy\":"),
                valid.replace("\"world\":\"main\",\"region\":\"_c1\"", "\"region\":\"_c1\",\"world\":\"main\""),
                valid.replace("\"PARTIAL\"", "null"), valid.replace("\"main\"", "123"),
                deeplyNested,
                valid.replace("\"occupancy\":\"PARTIAL\"", "\"occupancy\":\"PARTIAL\",\"owner\":\"private\"")))
            assertThrows(IOException.class, () -> verify(bad));
    }

    @Test void byteCanonicalizationAndStrictUtf8RejectAlternatives() {
        String valid = json(ENTRY);
        for (String bad : List.of(" " + valid, valid + "\n", valid + "{}", "\ufeff" + valid,
                valid.replace("main", "m\\u0061in"), valid.replace("{\"version", "{/*comment*/\"version"),
                valid.replace("00Z", "00.000Z"), valid.replace("00Z", "00+00:00")))
            assertThrows(IOException.class, () -> verify(bad));
        assertThrows(IOException.class, () -> PublicOccupancyArtifactCodec.verify(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IOException.class, () -> PublicOccupancyArtifactCodec.verify(new byte[128 * 1024 + 1]));
    }

    @Test void timestampWorldRegionStateAndOrderingBounds() {
        String valid = json(ENTRY);
        for (String bad : List.of(valid.replace("2026-09-08T12:00:00Z", "0000-01-01T00:00:00Z"),
                valid.replace("2026-09-08T12:00:00Z", "+10000-01-01T00:00:00Z"),
                valid.replace("2026-09-08T12:00:00Z", "2026-09-08T12:00:00.000001Z"),
                valid.replace("main", "_main"), valid.replace("main", "a".repeat(65)),
                valid.replace("_c1", "C1"), valid.replace("_c1", "\u212a1"),
                valid.replace("_c1", "a".repeat(129)), valid.replace("PARTIAL", "VACANT"),
                json(ENTRY + "," + ENTRY), json(ENTRY.replace("_c1", "z") + "," + ENTRY)))
            assertThrows(IOException.class, () -> verify(bad));
        assertDoesNotThrow(() -> verify(valid.replace("main", "a".repeat(64)).replace("_c1", "_" + "a".repeat(127))));
        assertDoesNotThrow(() -> verify(valid.replace("2026-09-08T12:00:00Z", "0001-01-01T00:00:00Z")));
        assertDoesNotThrow(() -> verify(valid.replace("2026-09-08T12:00:00Z", "9999-12-31T23:59:59.999Z")));
    }

    @Test void exact512EntryBound() {
        var entries = new java.util.ArrayList<String>();
        for (int i = 0; i < 513; i++) entries.add(ENTRY.replace("_c1", String.format(java.util.Locale.ROOT, "c%03d", i)));
        assertDoesNotThrow(() -> verify(json(String.join(",", entries.subList(0, 512)))));
        assertThrows(IOException.class, () -> verify(json(String.join(",", entries))));
    }
}
