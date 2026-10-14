package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancySelectionCodecTest {
    @TempDir Path root;
    static final UUID AUTH = new UUID(0,7);
    static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    OccupancyGenerationHistory history(int count) throws Exception {
        var h=OccupancyGenerationHistory.empty(AUTH);
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            for(int i=0;i<count;i++) {
                var p=new Property(new UUID(0,1),new UUID(0,2),"probe","market","room",2,100,100,null,0,80,true);
                var s=PublicOccupancySnapshot.build(NOW.plusSeconds(i),List.of(new PublicOccupancySnapshot.Row(p,1)),
                        List.of(new PublicOccupancySnapshot.Approval(p.propertyId(),p.worldUuid(),"probe","main")));
                h=h.advance(AUTH,s,writer.write(s),Duration.ofMinutes(10),NOW.plusSeconds(i));
            }
        }
        return h;
    }
    String json(int count) throws Exception { return new String(OccupancySelectionCodec.encode(history(count),AUTH),StandardCharsets.UTF_8); }
    void rejects(String json) { assertThrows(IOException.class,()->OccupancySelectionCodec.decode(json.getBytes(StandardCharsets.UTF_8),AUTH)); }
    @Test void roundTripEmptyAndThreeEntriesHasExactBytesAndProtection() throws Exception {
        for(int count:List.of(0,3)) {
            var h=history(count); byte[] bytes=OccupancySelectionCodec.encode(h,AUTH);
            assertTrue(bytes.length<=4096);
            var decoded=OccupancySelectionCodec.decode(bytes,AUTH);
            assertEquals(h.entries(),decoded.entries()); assertEquals(h.protectedNames(AUTH),decoded.protectedNames(AUTH));
            assertArrayEquals(bytes,OccupancySelectionCodec.encode(decoded,AUTH));
        }
    }
    @Test void expectedGenerationCannotBeTakenFromUntrustedFile() throws Exception {
        var h=history(1); byte[] bytes=OccupancySelectionCodec.encode(h,AUTH);
        assertThrows(IOException.class,()->OccupancySelectionCodec.decode(bytes,UUID.randomUUID()));
        assertThrows(IOException.class,()->OccupancySelectionCodec.decode(bytes,null));
        assertThrows(IllegalArgumentException.class,()->OccupancySelectionCodec.encode(h,UUID.randomUUID()));
        rejects(new String(bytes,StandardCharsets.UTF_8).replace(AUTH.toString(),"0-0-0-0-7"));
    }
    @Test void foreignDuplicateUnknownNestedAndNoncanonicalInputReject() throws Exception {
        String j=json(1);
        for(String bad:List.of(j+"\n",j+"{}",j.replace("\"entries\":", "\"other\":0,\"entries\":"),
                j.replace("\"entries\":", "\"entries\":[],\"entries\":"),j.replace("npc-occupancy-selection-v1","other"),
                j.replace("\"entries\":[", "\"entries\":[["),j.replace("\"name\"","\"na\\u006de\""),
                "{\"version\":"+"[".repeat(1000))) rejects(bad);
    }
    @Test void invalidReceiptNumbersTimesAndNamesReject() throws Exception {
        String j=json(1);
        for(String number:List.of("0","-1","1.0","1e2","131073","\"100\"","null"))
            rejects(j.replaceFirst("\"bytes\":[0-9]+","\"bytes\":"+number));
        rejects(j.replace("\"name\":\"","\"name\":\"../"));
        rejects(j.replace("2026-09-08T12:00:00Z","2026-99-08T12:00:00Z"));
        rejects(j.replace("2026-09-08T12:00:00Z","2026-09-08T12:00:00.000Z"));
        rejects(j.replace("2026-09-08T12:10:00Z","2026-09-08T12:00:00Z"));
    }
    @Test void duplicateReversedOrFourthEntryRejectsEvenCanonicalJson() throws Exception {
        var tree=com.google.gson.JsonParser.parseString(json(3)).getAsJsonObject();
        var entries=tree.getAsJsonArray("entries");
        var original=entries.deepCopy(); entries.add(original.get(0)); rejects(tree.toString());
        tree.add("entries",original.deepCopy()); tree.getAsJsonArray("entries").set(1,original.get(0)); rejects(tree.toString());
        var reversed=new com.google.gson.JsonArray(); for(int i=2;i>=0;i--) reversed.add(original.get(i));
        tree.add("entries",reversed); rejects(tree.toString());
    }
    @Test void sizeAndUtf8LimitsAreEnforced() throws Exception {
        assertThrows(IOException.class,()->OccupancySelectionCodec.decode(new byte[4097],AUTH));
        assertThrows(IOException.class,()->OccupancySelectionCodec.decode(null,AUTH));
        assertThrows(IOException.class,()->OccupancySelectionCodec.decode(new byte[]{(byte)0xc3,0x28},AUTH));
        rejects("\ufeff"+json(1));
    }
    @Test void canonicalEntryStillNeedsHashAndTimeRelationshipValidation() throws Exception {
        String valid=json(1);
        for(String field:List.of("sha256","capturedAt","selectedAt","expiresAt")) {
            var tree=com.google.gson.JsonParser.parseString(valid).getAsJsonObject();
            var entry=tree.getAsJsonArray("entries").get(0).getAsJsonObject();
            entry.addProperty(field,switch(field) {
                case "sha256" -> "a".repeat(64);
                case "capturedAt" -> "2026-09-08T12:00:00.000001Z";
                case "selectedAt" -> "2026-09-08T11:59:59Z";
                default -> "2026-09-08T11:59:59Z";
            });
            if(field.equals("capturedAt")) entry.addProperty("selectedAt","2026-09-08T12:00:01Z");
            rejects(tree.toString());
        }
        rejects(valid.replace("2026-09-08","0000-09-08"));
        rejects(valid.replace("2026-09-08","+10000-09-08"));
        var tree=com.google.gson.JsonParser.parseString(json(3)).getAsJsonObject();
        tree.getAsJsonArray("entries").get(1).getAsJsonObject().addProperty("selectedAt","2026-09-08T12:00:03Z");
        rejects(tree.toString());
    }
}
