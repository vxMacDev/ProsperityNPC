package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AtomicOccupancySelectionTest {
    @TempDir Path root;
    static final UUID AUTH=new UUID(0,7);
    static final Instant NOW=Instant.parse("2026-09-08T12:00:00Z");
    static final Duration TTL=Duration.ofMinutes(10);
    static PublicOccupancySnapshot snapshot(int i) {
        var p=new Property(new UUID(0,1),new UUID(0,2),"probe","market","room",2,100,100,null,0,80,true);
        return PublicOccupancySnapshot.build(NOW.plusSeconds(i),List.of(new PublicOccupancySnapshot.Row(p,1)),
                List.of(new PublicOccupancySnapshot.Approval(p.propertyId(),p.worldUuid(),"probe","main")));
    }
    OccupancyGenerationHistory advance(AtomicOccupancyArtifacts writer,OccupancyGenerationHistory h,int i) throws Exception {
        var s=snapshot(i); return h.advance(AUTH,s,writer.write(s),TTL,NOW.plusSeconds(i));
    }
    @Test void atomicCommitReopenAndAutomaticRetentionProtection() throws Exception {
        OccupancyGenerationHistory h;
        String orphan;
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertTrue(writer.readSelection(AUTH).isEmpty());
            h=advance(writer,OccupancyGenerationHistory.empty(AUTH),0);
            writer.commitSelection(null,h,AUTH);
            var next=advance(writer,h,1); writer.commitSelection(h,next,AUTH); h=next;
            orphan=writer.write(snapshot(2)).name();
            try(var files=Files.list(root)) { for(Path p:files.toList()) Files.setLastModifiedTime(p,FileTime.from(Instant.EPOCH)); }
        }
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertThrows(IOException.class,()->writer.write(snapshot(3)));
            assertEquals(h.entries(),writer.readSelection(AUTH).orElseThrow().entries());
            // Caller omission must not delete the selected current or predecessor.
            assertEquals(0,writer.prune(Set.of(orphan),Duration.ofHours(1),NOW).deleted());
            assertEquals(1,writer.prune(h.protectedNames(AUTH),Duration.ofHours(1),NOW).deleted());
            assertTrue(Files.exists(root.resolve(".selection.json")));
        }
    }
    @Test void staleCompareCannotOverwriteCommittedSelection() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var a=advance(writer,OccupancyGenerationHistory.empty(AUTH),0);
            var b=advance(writer,a,1); writer.commitSelection(null,a,AUTH); writer.commitSelection(a,b,AUTH);
            byte[] committed=Files.readAllBytes(root.resolve(".selection.json"));
            var c=advance(writer,b,2);
            assertThrows(IOException.class,()->writer.commitSelection(a,c,AUTH));
            assertThrows(IOException.class,()->writer.commitSelection(null,c,AUTH));
            assertArrayEquals(committed,Files.readAllBytes(root.resolve(".selection.json")));
        }
    }
    @Test void lostAcknowledgmentAfterReplaceRetriesWithoutAnotherMove() throws Exception {
        AtomicBoolean fail=new AtomicBoolean(true);
        var pointerMoves=new java.util.concurrent.atomic.AtomicInteger();
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);
            if(b.getFileName().toString().equals(".selection.json")) {
                pointerMoves.incrementAndGet();
                if(fail.getAndSet(false)) throw new IOException("lost reply");
            }
        },p->Long.MAX_VALUE)) {
            var h=advance(writer,OccupancyGenerationHistory.empty(AUTH),0);
            assertThrows(IOException.class,()->writer.commitSelection(null,h,AUTH));
            assertEquals(h.entries(),writer.readSelection(AUTH).orElseThrow().entries());
            writer.commitSelection(null,h,AUTH);
            assertEquals(1,pointerMoves.get());
            try(var files=Files.list(root)) { assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".tmp"))); }
        }
    }
    @Test void renameFailurePreservesPreviousAndCleansOnlyOwnedTemp() throws Exception {
        OccupancyGenerationHistory h;
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            h=advance(writer,OccupancyGenerationHistory.empty(AUTH),0); writer.commitSelection(null,h,AUTH);
        }
        byte[] before=Files.readAllBytes(root.resolve(".selection.json"));
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{
            if(b.getFileName().toString().equals(".selection.json")) throw new AtomicMoveNotSupportedException(a.toString(),b.toString(),"fixture");
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);
        },p->Long.MAX_VALUE)) {
            writer.readSelection(AUTH);
            var next=advance(writer,h,1);
            assertThrows(IOException.class,()->writer.commitSelection(h,next,AUTH));
            assertArrayEquals(before,Files.readAllBytes(root.resolve(".selection.json")));
            assertEquals(h.entries(),writer.readSelection(AUTH).orElseThrow().entries());
            try(var files=Files.list(root)) { assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".tmp"))); }
        }
    }
    @Test void missingCorruptAndMetadataMismatchedReferencesHoldPointer() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var h=advance(writer,OccupancyGenerationHistory.empty(AUTH),0); var r=h.entries().getFirst().receipt();
            byte[] bytes=Files.readAllBytes(root.resolve(r.name()));
            Files.delete(root.resolve(r.name()));
            assertThrows(IOException.class,()->writer.commitSelection(null,h,AUTH));
            Files.writeString(root.resolve(r.name()),"bad");
            assertThrows(IOException.class,()->writer.commitSelection(null,h,AUTH));
            Files.write(root.resolve(r.name()),bytes);
            var lie=OccupancyGenerationHistory.restore(AUTH,List.of(new OccupancyGenerationHistory.Entry(
                    new AtomicOccupancyArtifacts.Receipt(r.name(),r.sha256(),r.capturedAt(),r.bytes()+1),NOW,NOW.plus(TTL))));
            assertThrows(IOException.class,()->writer.commitSelection(null,lie,AUTH));
            var timeLie=OccupancyGenerationHistory.restore(AUTH,List.of(new OccupancyGenerationHistory.Entry(
                    new AtomicOccupancyArtifacts.Receipt(r.name(),r.sha256(),r.capturedAt().minusMillis(1),r.bytes()),NOW,NOW.plus(TTL))));
            assertThrows(IOException.class,()->writer.commitSelection(null,timeLie,AUTH));
            assertFalse(Files.exists(root.resolve(".selection.json")));
            writer.commitSelection(null,h,AUTH);
            Files.writeString(root.resolve(r.name()),"bad");
            assertThrows(IOException.class,()->writer.readSelection(AUTH));
            assertThrows(IOException.class,()->writer.prune(Set.of(r.name()),Duration.ofHours(1),NOW));
        }
    }
    @Test void foreignGenerationAndMalformedPointerNeverBecomeCurrent() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var h=advance(writer,OccupancyGenerationHistory.empty(AUTH),0); writer.commitSelection(null,h,AUTH);
            assertThrows(IOException.class,()->writer.readSelection(UUID.randomUUID()));
            Files.writeString(root.resolve(".selection.json"),"{}");
            assertThrows(IOException.class,()->writer.readSelection(AUTH));
            assertThrows(IOException.class,()->writer.write(snapshot(1)));
            assertThrows(IOException.class,()->writer.commitSelection(h,h,AUTH));
            assertEquals("{}",Files.readString(root.resolve(".selection.json")));
        }
    }
    @Test void selectionSymlinkCannotBeReadOrReplaced() throws Exception {
        Path external=Files.writeString(root.resolveSibling(root.getFileName()+"-selection-sentinel"),"unchanged");
        try {
            try(var writer=new AtomicOccupancyArtifacts(root)) {
                var h=advance(writer,OccupancyGenerationHistory.empty(AUTH),0);
                try { Files.createSymbolicLink(root.resolve(".selection.json"),external); }
                catch(UnsupportedOperationException | FileSystemException unavailable) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symlinks unavailable");
                }
                assertThrows(IOException.class,()->writer.readSelection(AUTH));
                assertThrows(IOException.class,()->writer.commitSelection(null,h,AUTH));
                assertEquals("unchanged",Files.readString(external));
            }
        } finally {Files.deleteIfExists(external);}
    }
    @Test void lowSpaceAndClosedStoreCannotCommit() throws Exception {
        AtomicBoolean full=new AtomicBoolean(false);
        var writer=new AtomicOccupancyArtifacts(root,(a,b)->Files.move(a,b,StandardCopyOption.ATOMIC_MOVE),
                p->full.get()?0:Long.MAX_VALUE);
        OccupancyGenerationHistory h;
        try {
            h=advance(writer,OccupancyGenerationHistory.empty(AUTH),0); full.set(true);
            assertThrows(IOException.class,()->writer.commitSelection(null,h,AUTH));
            assertFalse(Files.exists(root.resolve(".selection.json")));
        } finally {writer.close();}
        assertThrows(IOException.class,()->writer.readSelection(AUTH));
    }
    @Test void latestCasStillCannotRollBackResetOrDropProtectedLineage() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var a=advance(writer,OccupancyGenerationHistory.empty(AUTH),0);
            var b=advance(writer,a,1);
            assertThrows(IOException.class,()->writer.commitSelection(null,b,AUTH));
            writer.commitSelection(null,a,AUTH); writer.commitSelection(a,b,AUTH);
            var c=advance(writer,b,2);
            byte[] before=Files.readAllBytes(root.resolve(".selection.json"));
            assertThrows(IOException.class,()->writer.commitSelection(b,a,AUTH));
            assertThrows(IOException.class,()->writer.commitSelection(b,OccupancyGenerationHistory.empty(AUTH),AUTH));
            var wrongTail=OccupancyGenerationHistory.restore(AUTH,List.of(c.entries().getFirst(),a.entries().getFirst()));
            assertThrows(IOException.class,()->writer.commitSelection(b,wrongTail,AUTH));
            assertArrayEquals(before,Files.readAllBytes(root.resolve(".selection.json")));
            writer.commitSelection(b,c,AUTH);
            assertEquals(c.entries(),writer.readSelection(AUTH).orElseThrow().entries());
        }
    }
}
