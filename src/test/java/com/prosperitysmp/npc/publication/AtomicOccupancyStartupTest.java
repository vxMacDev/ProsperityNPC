package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AtomicOccupancyStartupTest {
    @TempDir Path root;
    static final UUID OLD=new UUID(0,7), FRESH=new UUID(0,8);
    static final Instant NOW=Instant.parse("2026-09-08T12:00:00Z");
    static PublicOccupancySnapshot snapshot() {
        var p=new Property(new UUID(0,1),new UUID(0,2),"probe","market","room",2,100,100,null,0,80,true);
        return PublicOccupancySnapshot.build(NOW,List.of(new PublicOccupancySnapshot.Row(p,1)),
                List.of(new PublicOccupancySnapshot.Approval(p.propertyId(),p.worldUuid(),"probe","main")));
    }
    String seed() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var s=snapshot(); var r=writer.write(s);
            var h=OccupancyGenerationHistory.empty(OLD).advance(OLD,s,r,Duration.ofMinutes(10),NOW);
            writer.commitSelection(null,h,OLD); return r.name();
        }
    }
    @Test void freshStartupClearsPriorSelectionWithoutAdoptingRecordedIdentity() throws Exception {
        String artifact=seed(); byte[] original=Files.readAllBytes(root.resolve(artifact));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var empty=writer.beginAuthorization(FRESH);
            assertTrue(empty.entries().isEmpty()); assertTrue(empty.current(FRESH,NOW).isEmpty());
            assertThrows(IOException.class,()->writer.readSelection(OLD));
            assertTrue(writer.readSelection(FRESH).orElseThrow().entries().isEmpty());
            assertArrayEquals(original,Files.readAllBytes(root.resolve(artifact)));
            var s=snapshot(); var next=empty.advance(FRESH,s,writer.write(s),Duration.ofMinutes(10),NOW);
            writer.commitSelection(empty,next,FRESH);
            assertEquals(next.entries(),writer.readSelection(FRESH).orElseThrow().entries());
        }
    }
    @Test void sameRecordedGenerationWithContentIsNotAResumeShortcut() throws Exception {
        seed(); byte[] before=Files.readAllBytes(root.resolve(".selection.json"));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertThrows(IOException.class,()->writer.beginAuthorization(OLD));
            assertThrows(IOException.class,()->writer.write(snapshot()));
            assertArrayEquals(before,Files.readAllBytes(root.resolve(".selection.json")));
        }
    }
    @Test void absentPointerAndOrphanPartialCanInitializeThenRecover() throws Exception {
        Path partial=Files.writeString(root.resolve(".occupancy-00000000-0000-4000-8000-000000000001.tmp"),"partial");
        Files.setLastModifiedTime(partial,FileTime.from(Instant.EPOCH));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            writer.beginAuthorization(FRESH);
            assertThrows(IOException.class,()->writer.write(snapshot()));
            assertEquals(1,writer.recoverTemporaryFiles(Duration.ofHours(1),NOW).deleted());
            assertNotNull(writer.write(snapshot()));
            assertTrue(writer.beginAuthorization(FRESH).entries().isEmpty());
        }
    }
    @Test void missingArtifactDoesNotBlockStartupRevocation() throws Exception {
        Files.delete(root.resolve(seed()));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertTrue(writer.beginAuthorization(FRESH).entries().isEmpty());
        }
    }
    @Test void malformedMetadataAndNullGenerationAreNeverRepaired() throws Exception {
        Files.writeString(root.resolve(".selection.json"),"{}");
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertThrows(IOException.class,()->writer.beginAuthorization(FRESH));
            assertThrows(IOException.class,()->writer.beginAuthorization(null));
            assertEquals("{}",Files.readString(root.resolve(".selection.json")));
        }
    }
    @Test void lostAcknowledgmentCanRetryAfterRestartWithoutAnotherRename() throws Exception {
        seed();
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE); throw new IOException("lost reply");
        },p->Long.MAX_VALUE)) {
            assertThrows(IOException.class,()->writer.beginAuthorization(FRESH));
        }
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{throw new AssertionError("Retry rename");},p->Long.MAX_VALUE)) {
            assertTrue(writer.beginAuthorization(FRESH).entries().isEmpty());
            assertTrue(writer.readSelection(FRESH).orElseThrow().entries().isEmpty());
        }
    }
    @Test void firstInitializationLostReplyStillRetriesWithoutAnotherRename() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE); throw new IOException("lost initial reply");
        },p->Long.MAX_VALUE)) {
            assertThrows(IOException.class,()->writer.beginAuthorization(FRESH));
        }
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{throw new AssertionError("Retry rename");},p->Long.MAX_VALUE)) {
            assertTrue(writer.beginAuthorization(FRESH).entries().isEmpty());
        }
    }
    @Test void beforeMoveFailurePreservesDiskButClearsPreviouslyVerifiedWriteReadiness() throws Exception {
        seed(); byte[] old=Files.readAllBytes(root.resolve(".selection.json"));
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{throw new IOException("before move");},p->Long.MAX_VALUE)) {
            assertFalse(writer.readSelection(OLD).orElseThrow().entries().isEmpty());
            assertThrows(IOException.class,()->writer.beginAuthorization(FRESH));
            assertArrayEquals(old,Files.readAllBytes(root.resolve(".selection.json")));
            assertThrows(IOException.class,()->writer.write(snapshot()));
        }
    }
}
