package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.PublicOccupancyRepository.Evidence;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyPublicationSessionTest {
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-09-08T12:00:00Z");
    static final UUID PROPERTY=new UUID(0,1), WORLD=new UUID(0,2);
    static final List<Approval> SCOPE=List.of(new Approval(PROPERTY,WORLD,"probe","main"));
    static Evidence evidence(Instant captured,boolean enabled) {
        var p=new Property(PROPERTY,WORLD,"probe","market","room",2,100,100,null,0,80,enabled);
        var s=PublicOccupancySnapshot.build(captured,List.of(new PublicOccupancySnapshot.Row(p,1)),SCOPE);
        return new Evidence(s,captured,Map.of(PROPERTY,1L),Map.of());
    }
    final class Harness extends Clock implements AutoCloseable {
        volatile Instant now=NOW; final AtomicLong nano=new AtomicLong();
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final AtomicInteger reads=new AtomicInteger();
        final AtomicReference<OccupancyRefreshPump.Reader> source=new AtomicReference<>(s->evidence(now,true));
        final OccupancyPublicationSession session;
        Harness() {this(AtomicOccupancyArtifacts::new);}
        Harness(OccupancyPublicationSession.StoreFactory factory) {
            session=new OccupancyPublicationSession(root,SCOPE,s->{
                reads.incrementAndGet(); assertThrows(UnsupportedOperationException.class,s::clear); return source.get().read(s);
            },this,nano::get,Duration.ofSeconds(10),Duration.ofHours(1),Duration.ofDays(1),factory);
        }
        Evidence read() throws Exception {return worker.submit(()->session.read(SCOPE)).get(5,TimeUnit.SECONDS);}
        OccupancyGenerationHistory disk() throws Exception {
            return OccupancySelectionCodec.inspectForReset(Files.readAllBytes(root.resolve(".selection.json"))).history();
        }
        @Override public Instant instant(){return now;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){throw new UnsupportedOperationException();}
        @Override public void close() {
            try {worker.submit(()->{session.close();return null;}).get(5,TimeUnit.SECONDS);}
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            catch(ExecutionException | TimeoutException failed) {throw new AssertionError(failed);}
            finally {
                worker.shutdownNow();
                try {assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }
        }
    }
    @Test void wholePrivatePipelineCommitsBeforeReturningAndReleasesLock() throws Exception {
        try(var h=new Harness()) {
            Evidence result=h.read(); var first=h.disk().entries().getFirst().receipt();
            assertEquals(result.snapshot().json(),Files.readString(root.resolve(first.name())));
            assertFalse(Files.readString(root.resolve(first.name())).contains(PROPERTY.toString()));
            h.now=NOW.plusSeconds(1); h.nano.set(1_000_000_000L); h.read();
            assertEquals(2,h.disk().entries().size()); assertEquals(2,h.reads.get());
        }
        try(var reopened=new AtomicOccupancyArtifacts(root)) {assertTrue(reopened.beginAuthorization(UUID.randomUUID()).entries().isEmpty());}
    }
    @Test void closeFailureRemainsFailureIncludingCancellationFinally() throws Exception {
        for(boolean duringRead:List.of(false,true)) {
            Path directory=Files.createDirectory(root.resolve(Boolean.toString(duringRead)));
            var closes=new AtomicInteger();var holder=new AtomicReference<OccupancyPublicationSession>();
            var session=new OccupancyPublicationSession(directory,SCOPE,s->{
                if(duringRead)holder.get().cancel();
                return evidence(NOW,true);
            },Clock.fixed(NOW,ZoneOffset.UTC),()->0L,Duration.ofSeconds(10),Duration.ofHours(1),Duration.ofDays(1),
                    AtomicOccupancyArtifacts::new,store->{
                        closes.incrementAndGet();store.close();
                        throw new java.io.IOException("lost cleanup acknowledgment");
                    });
            holder.set(session);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                if(duringRead)assertThrows(ExecutionException.class,()->worker.submit(()->session.read(SCOPE)).get(5,TimeUnit.SECONDS));
                else worker.submit(()->session.read(SCOPE)).get(5,TimeUnit.SECONDS);
                for(int attempt=0;attempt<3;attempt++) {
                    var failure=assertThrows(ExecutionException.class,()->worker.submit(()->{session.close();return null;}).get(5,TimeUnit.SECONDS));
                    assertInstanceOf(java.io.IOException.class,failure.getCause());
                    assertTrue(failure.getCause().getMessage().contains("operator review"));
                }
                assertEquals(1,closes.get());
                // Physical release happened, but its lost acknowledgment must never turn into success.
                try(var reopened=new AtomicOccupancyArtifacts(directory)) {
                    assertTrue(reopened.beginAuthorization(UUID.randomUUID()).entries().isEmpty());
                }
            } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test void ownerThreadAndChangedScopeRejectBeforeAnyFileOrSourceAccess() throws Exception {
        try(var h=new Harness()) {
            assertThrows(IllegalStateException.class,()->h.session.read(SCOPE));
            assertThrows(ExecutionException.class,()->h.worker.submit(()->h.session.read(List.of())).get(5,TimeUnit.SECONDS));
            assertEquals(0,h.reads.get()); assertFalse(Files.exists(root.resolve(".writer.lock")));
        }
    }
    @Test void sourceFailureAndEmptyResultPreservePriorSelection() throws Exception {
        try(var h=new Harness()) {
            h.read(); byte[] before=Files.readAllBytes(root.resolve(".selection.json"));
            h.source.set(s->{throw new java.sql.SQLException("private sentinel");});
            assertThrows(ExecutionException.class,h::read);
            assertArrayEquals(before,Files.readAllBytes(root.resolve(".selection.json")));
            h.source.set(s->evidence(h.now,false)); assertTrue(h.read().snapshot().isEmpty());
            assertArrayEquals(before,Files.readAllBytes(root.resolve(".selection.json")));
            h.source.set(s->evidence(h.now,true)); assertFalse(h.read().snapshot().isEmpty());
        }
    }
    @Test void staleFutureAndElapsedEvidenceDoNotCommit() throws Exception {
        try(var h=new Harness()) {
            h.read(); byte[] before=Files.readAllBytes(root.resolve(".selection.json"));
            for(Instant bad:List.of(NOW.minusMillis(1),NOW.plusMillis(1))) {
                h.source.set(s->evidence(bad,true)); assertThrows(ExecutionException.class,h::read);
            }
            h.source.set(s->{h.nano.addAndGet(10_000_000_000L);return evidence(NOW,true);});
            assertThrows(ExecutionException.class,h::read);
            assertArrayEquals(before,Files.readAllBytes(root.resolve(".selection.json")));
        }
    }
    @Test void cancellationDuringSourceReadDoesNotCommitAndReleasesStore() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var h=new Harness()) {
            h.source.set(s->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");return evidence(NOW,true);});
            var future=h.worker.submit(()->h.session.read(SCOPE));
            try {assertTrue(entered.await(5,TimeUnit.SECONDS));h.session.cancel();}
            finally {release.countDown();}
            assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
            assertTrue(h.disk().entries().isEmpty());
            try(var reopened=new AtomicOccupancyArtifacts(root)) {assertNotNull(reopened);}
            assertThrows(ExecutionException.class,h::read);
        }
    }
    @Test void refreshPumpOnlyExposesSuccessfullyCommittedFreshResult() throws Exception {
        try(var h=new Harness()) {
            var done=new CountDownLatch(1);
            var pump=new OccupancyRefreshPump(task->h.worker.execute(()->{try{task.run();}finally{done.countDown();}}),
                    h.session,h,h.nano::get,Duration.ofSeconds(10));
            pump.configure(SCOPE);assertEquals(OccupancyRefreshPump.Acceptance.ACCEPTED,pump.request());
            assertTrue(done.await(5,TimeUnit.SECONDS));pump.tick();
            assertEquals(h.disk().entries().getFirst().receipt().capturedAt(),pump.current().orElseThrow().capturedAt());
            h.nano.set(10_000_000_000L);assertTrue(pump.current().isEmpty());
            pump.close();h.session.cancel();
        }
    }
    @Test void startupAtArtifactCapacityRecoversOldUnselectedFilesBeforeFreshWrite() throws Exception {
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            for(int i=0;i<256;i++) {
                var receipt=writer.write(evidence(NOW.plusSeconds(i),true).snapshot());
                Files.setLastModifiedTime(root.resolve(receipt.name()),java.nio.file.attribute.FileTime.from(Instant.EPOCH));
            }
            assertThrows(java.io.IOException.class,()->writer.write(evidence(NOW.plusSeconds(300),true).snapshot()));
        }
        try(var h=new Harness()) {
            h.now=NOW.plusSeconds(300);assertNotNull(h.read());
            assertEquals(1,h.disk().entries().size());
            try(var files=Files.list(root)) {assertEquals(3,files.count());}
        }
    }
    @Test void evidenceCaptureMismatchDoesNotReplaceCommittedData() throws Exception {
        try(var h=new Harness()) {
            h.read();byte[] before=Files.readAllBytes(root.resolve(".selection.json"));
            h.source.set(s->new Evidence(evidence(NOW,true).snapshot(),NOW.plusMillis(1),Map.of(),Map.of()));
            assertThrows(ExecutionException.class,h::read);
            assertArrayEquals(before,Files.readAllBytes(root.resolve(".selection.json")));
        }
    }
    @Test void nextReadReconcilesRealCommitAfterLostAcknowledgment() throws Exception {
        AtomicInteger pointerMoves=new AtomicInteger();
        try(var h=new Harness(dir->new AtomicOccupancyArtifacts(dir,(a,b)->{
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);
            if(b.getFileName().toString().equals(".selection.json")&&pointerMoves.incrementAndGet()==2)
                throw new java.io.IOException("lost committed reply");
        },p->Long.MAX_VALUE))) {
            assertThrows(ExecutionException.class,h::read);
            assertEquals(NOW,h.disk().entries().getFirst().receipt().capturedAt());
            h.now=NOW.plusSeconds(1);h.nano.set(1_000_000_000L);
            assertNotNull(h.read());
            assertEquals(List.of(NOW.plusSeconds(1),NOW),h.disk().entries().stream().map(e->e.receipt().capturedAt()).toList());
            assertEquals(3,pointerMoves.get()); // empty startup, committed failure, next committed refresh
        }
    }
    @Test void nextReadRecoversAfterPostCommitPruningFailure() throws Exception {
        Path orphan;
        try(var writer=new AtomicOccupancyArtifacts(root)) {
            orphan=root.resolve(writer.write(evidence(NOW.minusSeconds(100),true).snapshot()).name());
            Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.from(NOW));
        }
        AtomicInteger pointerMoves=new AtomicInteger();AtomicBoolean failDelete=new AtomicBoolean(true);
        try(var h=new Harness(dir->new AtomicOccupancyArtifacts(dir,(a,b)->{
            Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);
            if(b.getFileName().toString().equals(".selection.json")&&pointerMoves.incrementAndGet()==2)
                Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.from(Instant.EPOCH));
        },p->Long.MAX_VALUE,p->{
            if(p.equals(orphan)&&failDelete.getAndSet(false))throw new java.io.IOException("post-commit cleanup failure");
            Files.delete(p);
        }))) {
            assertThrows(ExecutionException.class,h::read);
            assertEquals(NOW,h.disk().entries().getFirst().receipt().capturedAt());
            assertTrue(Files.exists(orphan));assertFalse(failDelete.get());
            h.now=NOW.plusSeconds(1);h.nano.set(1_000_000_000L);assertNotNull(h.read());
            assertFalse(Files.exists(orphan));assertEquals(2,h.disk().entries().size());
        }
    }
}
