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

class AtomicOccupancyRecoveryTest {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    static final Duration AGE = Duration.ofHours(1);
    static String name(int id) { return ".occupancy-00000000-0000-4000-8000-" + String.format("%012d", id) + ".tmp"; }
    static PublicOccupancySnapshot snapshot() {
        var p = new Property(new UUID(0,1), new UUID(0,2), "probe", "market", "room", 2,100,100,null,0,80,true);
        return PublicOccupancySnapshot.build(NOW, List.of(new PublicOccupancySnapshot.Row(p,1)),
                List.of(new PublicOccupancySnapshot.Approval(p.propertyId(),p.worldUuid(),"probe","main")));
    }
    Path partial(Path dir, int id, Instant time) throws IOException {
        Path p = Files.writeString(dir.resolve(name(id)), "{partial");
        Files.setLastModifiedTime(p, FileTime.from(time)); return p;
    }
    @Test void restartRecoversPartialAndZeroByteWritesPreservingCompletedSnapshot() throws Exception {
        String complete;
        try(var writer=new AtomicOccupancyArtifacts(root)) { complete=writer.write(snapshot()).name(); }
        byte[] original=Files.readAllBytes(root.resolve(complete));
        partial(root,1,NOW.minusSeconds(3601));
        Path zero=partial(root,2,NOW.minusSeconds(3601)); Files.write(zero,new byte[0]);
        Files.setLastModifiedTime(zero,FileTime.from(NOW.minusSeconds(3601)));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertThrows(IOException.class,()->writer.write(snapshot()));
            assertEquals(new AtomicOccupancyArtifacts.PruneResult(2,2,8),writer.recoverTemporaryFiles(AGE,NOW));
            assertArrayEquals(original,Files.readAllBytes(root.resolve(complete)));
            assertEquals(complete,writer.write(snapshot()).name());
            assertEquals(0,writer.recoverTemporaryFiles(AGE,NOW).deleted());
        }
    }
    @Test void boundaryAndFutureTempsRemainAndHoldWrites() throws Exception {
        Path boundary=partial(root,1,NOW.minus(AGE)), future=partial(root,2,NOW.plusSeconds(1));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertEquals(0,writer.recoverTemporaryFiles(AGE,NOW).deleted());
            assertTrue(Files.exists(boundary)); assertTrue(Files.exists(future));
            assertThrows(IOException.class,()->writer.write(snapshot()));
        }
    }
    @Test void completeInventoryValidationPrecedesAnyDeletion() throws Exception {
        for(String kind:List.of("unknown","bad-uuid","oversize","directory","corrupt-artifact","foreign-schema")) {
            Path dir=Files.createDirectory(root.resolve(kind));
            try(var writer=new AtomicOccupancyArtifacts(dir)) {
                Path old=partial(dir,1,NOW.minusSeconds(7200));
                switch(kind) {
                    case "unknown" -> Files.writeString(dir.resolve("staff.txt"),"preserve");
                    case "bad-uuid" -> Files.writeString(dir.resolve(".occupancy-arbitrary.tmp"),"preserve");
                    case "oversize" -> Files.write(dir.resolve(name(2)),new byte[128*1024+1]);
                    case "directory" -> Files.createDirectory(dir.resolve(name(2)));
                    case "corrupt-artifact" -> Files.writeString(dir.resolve("a".repeat(64)+".json"),"corrupt");
                    case "foreign-schema" -> {
                        byte[] foreign="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(foreign));
                        Files.write(dir.resolve(hash+".json"),foreign);
                    }
                }
                assertThrows(IOException.class,()->writer.recoverTemporaryFiles(AGE,NOW),kind);
                assertEquals("{partial",Files.readString(old));
            }
        }
    }
    @Test void symlinkCannotBecomeRecoveryCandidate() throws Exception {
        Path old=partial(root,1,NOW.minusSeconds(7200));
        try { Files.createSymbolicLink(root.resolve(name(2)),old); }
        catch(UnsupportedOperationException | FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symlinks unavailable: "+unavailable.getClass().getSimpleName());
        }
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertThrows(IOException.class,()->writer.recoverTemporaryFiles(AGE,NOW));
            assertEquals("{partial",Files.readString(old));
        }
    }
    @Test void temporaryCountBoundFailsBeforeDeletion() throws Exception {
        for(int i=0;i<257;i++) partial(root,i,NOW.minusSeconds(7200));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertThrows(IOException.class,()->writer.recoverTemporaryFiles(AGE,NOW));
            try(var files=Files.list(root)) { assertEquals(258,files.count()); }
        }
    }
    @Test void maximumLegitimateTemporaryInventoryCanRecover() throws Exception {
        for(int i=0;i<256;i++) partial(root,i,NOW.minusSeconds(7200));
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertEquals(new AtomicOccupancyArtifacts.PruneResult(256,256,2048),
                    writer.recoverTemporaryFiles(AGE,NOW));
            assertNotNull(writer.write(snapshot()));
        }
    }
    @Test void deleteFailureAllowsRetryAndNeverDeletesSnapshots() throws Exception {
        String complete;
        try(var writer=new AtomicOccupancyArtifacts(root)) { complete=writer.write(snapshot()).name(); }
        byte[] original=Files.readAllBytes(root.resolve(complete));
        Path first=partial(root,1,NOW.minusSeconds(7200)), second=partial(root,2,NOW.minusSeconds(7200));
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->Files.move(a,b,StandardCopyOption.ATOMIC_MOVE),
                ignored->Long.MAX_VALUE,p->{if(p.equals(second))throw new IOException("fault");Files.delete(p);})) {
            assertThrows(IOException.class,()->writer.recoverTemporaryFiles(AGE,NOW));
            assertFalse(Files.exists(first)); assertTrue(Files.exists(second));
            assertArrayEquals(original,Files.readAllBytes(root.resolve(complete)));
        }
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            assertEquals(1,writer.recoverTemporaryFiles(AGE,NOW).deleted());
            assertArrayEquals(original,Files.readAllBytes(root.resolve(complete)));
        }
    }
    @Test void sameSizeSameMtimeCandidateChangeIsCaughtByHash() throws Exception {
        partial(root,1,NOW.minusSeconds(7200)); Path second=partial(root,2,NOW.minusSeconds(7200));
        FileTime old=Files.getLastModifiedTime(second);
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->Files.move(a,b,StandardCopyOption.ATOMIC_MOVE),
                ignored->Long.MAX_VALUE,p->{Files.delete(p);Files.writeString(second,"changed!");Files.setLastModifiedTime(second,old);})) {
            assertThrows(IOException.class,()->writer.recoverTemporaryFiles(AGE,NOW));
            assertEquals("changed!",Files.readString(second));
        }
    }
    @Test void invalidPolicyClosedWriterAndOtherWriterCannotRecover() throws Exception {
        var writer=new AtomicOccupancyArtifacts(root);
        try {
            assertThrows(IOException.class,()->new AtomicOccupancyArtifacts(root));
            for(Duration d:List.of(Duration.ZERO,Duration.ofSeconds(-1)))
                assertThrows(IOException.class,()->writer.recoverTemporaryFiles(d,NOW));
            assertThrows(IOException.class,()->writer.recoverTemporaryFiles(null,NOW));
            assertThrows(IOException.class,()->writer.recoverTemporaryFiles(AGE,null));
        } finally {writer.close();}
        assertThrows(IOException.class,()->writer.recoverTemporaryFiles(AGE,NOW));
    }
}
