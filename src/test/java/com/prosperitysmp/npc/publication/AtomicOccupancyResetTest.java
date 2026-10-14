package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AtomicOccupancyResetTest {
    @TempDir Path root;
    static final UUID OLD=new UUID(0,7), NEW=new UUID(0,8);
    static final Instant NOW=Instant.parse("2026-09-08T12:00:00Z");
    static PublicOccupancySnapshot snapshot(int second) {
        var p=new Property(new UUID(0,1),new UUID(0,2),"probe","market","room",2,100,100,null,0,80,true);
        return PublicOccupancySnapshot.build(NOW.plusSeconds(second),List.of(new PublicOccupancySnapshot.Row(p,1)),
                List.of(new PublicOccupancySnapshot.Approval(p.propertyId(),p.worldUuid(),"probe","main")));
    }
    OccupancyGenerationHistory seed(AtomicOccupancyArtifacts writer) throws Exception {
        var s=snapshot(0); var h=OccupancyGenerationHistory.empty(OLD).advance(OLD,s,writer.write(s),Duration.ofMinutes(10),NOW);
        writer.commitSelection(null,h,OLD); return h;
    }
    @Test void resetPreservesArtifactsRevokesOldGenerationAndAllowsFreshSelection() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var h=seed(writer); var r=h.entries().getFirst().receipt(); byte[] bytes=Files.readAllBytes(root.resolve(r.name()));
            var empty=writer.resetSelection(h,OLD,NEW);
            assertTrue(empty.current(NEW,NOW).isEmpty());
            assertThrows(IOException.class,()->writer.readSelection(OLD));
            assertTrue(writer.readSelection(NEW).orElseThrow().entries().isEmpty());
            assertArrayEquals(bytes,Files.readAllBytes(root.resolve(r.name())));
            var s=snapshot(1); var next=empty.advance(NEW,s,writer.write(s),Duration.ofMinutes(10),NOW.plusSeconds(1));
            writer.commitSelection(empty,next,NEW);
            assertEquals(next.entries(),writer.readSelection(NEW).orElseThrow().entries());
            assertThrows(IOException.class,()->writer.resetSelection(h,OLD,NEW));
            assertEquals(next.entries(),writer.readSelection(NEW).orElseThrow().entries());
        }
    }
    @Test void resetDoesNotNeedHealthyWithdrawnArtifacts() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var h=seed(writer); Path artifact=root.resolve(h.entries().getFirst().receipt().name());
            Files.writeString(artifact,"corrupt retained evidence");
            assertThrows(IOException.class,()->writer.readSelection(OLD));
            writer.resetSelection(h,OLD,NEW);
            assertTrue(writer.readSelection(NEW).orElseThrow().entries().isEmpty());
            assertEquals("corrupt retained evidence",Files.readString(artifact));
        }
    }
    @Test void lostReplyResetIsIdempotentWithOnlyOneRename() throws Exception {
        AtomicBoolean fail=new AtomicBoolean(false); AtomicInteger moves=new AtomicInteger();
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);
            if(b.getFileName().toString().equals(".selection.json")) {
                moves.incrementAndGet(); if(fail.getAndSet(false)) throw new IOException("lost reply");
            }
        },p->Long.MAX_VALUE)) {
            var h=seed(writer); fail.set(true);
            assertThrows(IOException.class,()->writer.resetSelection(h,OLD,NEW));
            assertTrue(writer.resetSelection(h,OLD,NEW).entries().isEmpty());
            assertEquals(2,moves.get());
        }
    }
    @Test void failedResetKeepsOldDiskBytesButInvalidatesLocalWriteReadiness() throws Exception {
        AtomicBoolean fail=new AtomicBoolean(false);
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{
            if(fail.get()) throw new IOException("before rename"); Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);
        },p->Long.MAX_VALUE)) {
            var h=seed(writer); byte[] old=Files.readAllBytes(root.resolve(".selection.json")); fail.set(true);
            assertThrows(IOException.class,()->writer.resetSelection(h,OLD,NEW));
            assertArrayEquals(old,Files.readAllBytes(root.resolve(".selection.json")));
            assertThrows(IOException.class,()->writer.write(snapshot(1)));
            try(var paths=Files.list(root)) {assertFalse(paths.anyMatch(p->p.getFileName().toString().endsWith(".tmp")));}
            fail.set(false); assertTrue(writer.resetSelection(h,OLD,NEW).entries().isEmpty());
        }
    }
    @Test void invalidIdentityStaleMetadataAndCorruptPointerAreNotOverwritten() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var h=seed(writer); byte[] old=Files.readAllBytes(root.resolve(".selection.json"));
            for(org.junit.jupiter.api.function.Executable invalid:List.<org.junit.jupiter.api.function.Executable>of(
                    ()->writer.resetSelection(h,OLD,OLD), ()->writer.resetSelection(h,NEW,OLD),
                    ()->writer.resetSelection(null,OLD,NEW),
                    ()->writer.resetSelection(OccupancyGenerationHistory.empty(OLD),OLD,NEW))) {
                writer.readSelection(OLD);
                assertThrows(IOException.class,invalid);
                assertThrows(IOException.class,()->writer.write(snapshot(1)));
            }
            assertArrayEquals(old,Files.readAllBytes(root.resolve(".selection.json")));
            Files.writeString(root.resolve(".selection.json"),"{}");
            assertThrows(IOException.class,()->writer.resetSelection(h,OLD,NEW));
            assertEquals("{}",Files.readString(root.resolve(".selection.json")));
        }
    }
    @Test void missingWithdrawnArtifactDoesNotPreventRevocation() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var h=seed(writer); Files.delete(root.resolve(h.entries().getFirst().receipt().name()));
            assertTrue(writer.resetSelection(h,OLD,NEW).entries().isEmpty());
            assertTrue(writer.readSelection(NEW).orElseThrow().entries().isEmpty());
        }
    }
    @Test void lostAcknowledgmentResetRetryWorksAfterReopening() throws Exception {
        AtomicBoolean fail=new AtomicBoolean(false);
        OccupancyGenerationHistory h;
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);
            if(fail.get()) throw new IOException("lost reply");
        },p->Long.MAX_VALUE)) {
            h=seed(writer); fail.set(true);
            assertThrows(IOException.class,()->writer.resetSelection(h,OLD,NEW));
        }
        try(var writer=new AtomicOccupancyArtifacts(root,(a,b)->{throw new AssertionError("Retry must not move");},p->Long.MAX_VALUE)) {
            assertTrue(writer.resetSelection(h,OLD,NEW).entries().isEmpty());
            assertTrue(writer.readSelection(NEW).orElseThrow().entries().isEmpty());
        }
    }
}
