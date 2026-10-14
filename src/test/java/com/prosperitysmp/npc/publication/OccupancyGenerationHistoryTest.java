package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.AtomicOccupancyArtifacts.Receipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyGenerationHistoryTest {
    @TempDir Path root;
    static final UUID AUTH = new UUID(0, 7);
    static final Instant BASE = Instant.parse("2026-09-08T12:00:00Z");
    static final Duration TTL = Duration.ofMinutes(10);
    static PublicOccupancySnapshot snapshot(int second, int occupied) {
        var p=new Property(new UUID(0,1),new UUID(0,2),"probe","market","room",2,100,100,null,0,80,true);
        return PublicOccupancySnapshot.build(BASE.plusSeconds(second),List.of(new PublicOccupancySnapshot.Row(p,occupied)),
                List.of(new PublicOccupancySnapshot.Approval(p.propertyId(),p.worldUuid(),"probe","main")));
    }
    @Test void realStoreLineageKeepsExactlyCurrentAndTwoPredecessors() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var history=OccupancyGenerationHistory.empty(AUTH);
            var receipts=new ArrayList<Receipt>();
            for(int i=0;i<5;i++) {
                var s=snapshot(i,1); var r=writer.write(s); receipts.add(r);
                history=history.advance(AUTH,s,r,TTL,BASE.plusSeconds(i));
                Files.setLastModifiedTime(root.resolve(r.name()),FileTime.from(Instant.EPOCH));
            }
            var finalHistory=history;
            assertEquals(receipts.subList(2,5).reversed(),history.entries().stream().map(OccupancyGenerationHistory.Entry::receipt).toList());
            assertEquals(2,writer.prune(history.protectedNames(AUTH),Duration.ofDays(1),BASE).deleted());
            for(var e:history.entries()) assertTrue(Files.exists(root.resolve(e.receipt().name())));
            assertEquals(receipts.getLast(),history.current(AUTH,BASE.plusSeconds(5)).orElseThrow());
            assertThrows(UnsupportedOperationException.class,()->finalHistory.entries().clear());
            assertThrows(UnsupportedOperationException.class,()->finalHistory.protectedNames(AUTH).clear());
        }
    }
    @Test void expiryDoesNotFallBackToLongerLivedPredecessor() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var a=snapshot(0,0); var b=snapshot(1,1);
            var h=OccupancyGenerationHistory.empty(AUTH).advance(AUTH,a,writer.write(a),TTL,BASE)
                    .advance(AUTH,b,writer.write(b),Duration.ofSeconds(2),BASE.plusSeconds(1));
            assertTrue(h.current(AUTH,BASE.plusSeconds(3)).isEmpty());
            assertEquals(2,h.protectedNames(AUTH).size());
            assertTrue(h.current(AUTH,BASE).isEmpty());
        }
    }
    @Test void changedAuthorizationRejectsReadsWritesAndRetentionSelection() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var s=snapshot(0,1); var r=writer.write(s);
            var h=OccupancyGenerationHistory.empty(AUTH).advance(AUTH,s,r,TTL,BASE);
            UUID changed=UUID.randomUUID();
            assertThrows(IllegalArgumentException.class,()->h.current(changed,BASE));
            assertThrows(IllegalArgumentException.class,()->h.protectedNames(changed));
            assertThrows(IllegalArgumentException.class,()->h.advance(changed,s,r,TTL,BASE));
            assertTrue(OccupancyGenerationHistory.empty(changed).current(changed,BASE).isEmpty());
        }
    }
    @Test void retryIsIdentityAndNeverRenewsExpiry() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var s=snapshot(0,1); var r=writer.write(s);
            var h=OccupancyGenerationHistory.empty(AUTH).advance(AUTH,s,r,TTL,BASE);
            assertSame(h,h.advance(AUTH,s,r,TTL,BASE.plusSeconds(1)));
            assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,s,r,TTL.plusSeconds(1),BASE.plusSeconds(1)));
            assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,s,r,TTL,BASE.plus(TTL)));
            assertTrue(h.current(AUTH,BASE.plus(TTL)).isEmpty());
        }
    }
    @Test void receiptMetadataMustMatchActualPayloadNotJustItsShape() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var s=snapshot(0,1); var r=writer.write(s); var h=OccupancyGenerationHistory.empty(AUTH);
            for(var bad:List.of(new Receipt(r.name(),"a".repeat(64),r.capturedAt(),r.bytes()),
                    new Receipt("../"+r.name(),r.sha256(),r.capturedAt(),r.bytes()),
                    new Receipt(r.name(),r.sha256(),r.capturedAt(),r.bytes()+1),
                    new Receipt(r.name(),r.sha256(),BASE.plusSeconds(1),r.bytes()),writer.write(snapshot(0,0))))
                assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,s,bad,TTL,BASE));
        }
    }
    @Test void olderConflictingFutureAndExpiredCandidatesDoNotAlterHistory() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var s=snapshot(1,1); var h=OccupancyGenerationHistory.empty(AUTH).advance(AUTH,s,writer.write(s),TTL,BASE.plusSeconds(1));
            for(var bad:List.of(snapshot(0,1),snapshot(1,0),snapshot(3,1))) {
                var r=writer.write(bad);
                assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,bad,r,TTL,BASE.plusSeconds(2)));
            }
            assertEquals(1,h.entries().size());
            var r=writer.write(s);
            assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,s,r,Duration.ZERO,BASE.plusSeconds(1)));
            assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,s,r,Duration.ofSeconds(Long.MAX_VALUE),BASE.plusSeconds(1)));
        }
    }
    @Test void emptyInvalidTimeAndBackwardSelectionAreRejected() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            var s=snapshot(0,1); var r=writer.write(s);
            var h=OccupancyGenerationHistory.empty(AUTH).advance(AUTH,s,r,TTL,BASE.plusSeconds(5));
            var empty=PublicOccupancySnapshot.build(BASE,List.of(),List.of());
            assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,empty,r,TTL,BASE.plusSeconds(6)));
            var next=snapshot(1,1); var nextReceipt=writer.write(next);
            assertThrows(IllegalArgumentException.class,()->h.advance(AUTH,next,nextReceipt,TTL,BASE.plusSeconds(4)));
            assertThrows(IllegalArgumentException.class,()->h.current(AUTH,null));
            assertThrows(IllegalArgumentException.class,()->h.current(AUTH,Instant.parse("0000-12-31T23:59:59Z")));
            assertThrows(IllegalArgumentException.class,()->h.current(AUTH,Instant.parse("+10000-01-01T00:00:00Z")));
            assertTrue(h.current(AUTH,BASE.plusSeconds(4)).isEmpty());
            assertEquals(r,h.current(AUTH,BASE.plusSeconds(5)).orElseThrow());
        }
    }
}
