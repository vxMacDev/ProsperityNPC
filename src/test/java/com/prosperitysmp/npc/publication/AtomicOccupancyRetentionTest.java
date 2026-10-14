package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicOccupancyRetentionTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private static final Duration AGE = Duration.ofHours(1);

    private static PublicOccupancySnapshot snapshot(int id) {
        var p = new Property(new UUID(0, id), new UUID(1, 1), "c" + id, "market", "class",
                1, 100, 100, null, 0, 80, true);
        return PublicOccupancySnapshot.build(NOW, List.of(new Row(p, 0)),
                List.of(new Approval(p.propertyId(), p.worldUuid(), p.regionId(), "main")));
    }
    private String stage(AtomicOccupancyArtifacts writer, int id, Instant mtime) throws IOException {
        String name = writer.write(snapshot(id)).name();
        Files.setLastModifiedTime(directory.resolve(name), FileTime.from(mtime));
        return name;
    }
    private Set<String> names() throws IOException {
        try (var files = Files.list(directory)) { return files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()); }
    }
    private Path foreign(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return Files.write(directory.resolve(hash + ".json"), bytes);
    }

    @Test void strictAgeProtectedHistoryAndRetryAccounting() throws Exception {
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            String keep = stage(writer, 1, NOW.minusSeconds(7200));
            String old = stage(writer, 2, NOW.minusSeconds(3601));
            String boundary = stage(writer, 3, NOW.minus(AGE));
            String future = stage(writer, 4, NOW.plusSeconds(1));
            long bytes = Files.size(directory.resolve(old));
            assertEquals(new AtomicOccupancyArtifacts.PruneResult(4, 1, bytes), writer.prune(Set.of(keep), AGE, NOW));
            assertEquals(Set.of(".writer.lock", keep, boundary, future), names());
            assertEquals(new AtomicOccupancyArtifacts.PruneResult(3, 0, 0), writer.prune(Set.of(keep), AGE, NOW));
        }
    }
    @Test void unselectedCleanupRequiresVerifiedPresentEmptySelection() throws Exception {
        UUID generation=UUID.randomUUID();
        try(var writer=new AtomicOccupancyArtifacts(directory)) {
            String name=stage(writer,1,NOW.minusSeconds(7200));
            assertThrows(IOException.class,()->writer.pruneUnselected(AGE,NOW));
            var s=snapshot(1);
            var selected=OccupancyGenerationHistory.empty(generation).advance(generation,s,writer.write(s),AGE,NOW);
            writer.commitSelection(null,selected,generation);
            assertThrows(IOException.class,()->writer.pruneUnselected(AGE,NOW));
            assertTrue(Files.exists(directory.resolve(name)));
            writer.beginAuthorization(UUID.randomUUID());
            assertEquals(1,writer.pruneUnselected(AGE,NOW).deleted());
            assertFalse(Files.exists(directory.resolve(name)));
            assertTrue(Files.exists(directory.resolve(".selection.json")));
        }
    }

    @Test void verifiedRetentionRestoresCapacityWithoutChangingProtectedBytes() throws Exception {
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            String keep = stage(writer, 1, NOW.minusSeconds(7200));
            byte[] protectedBytes = Files.readAllBytes(directory.resolve(keep));
            for (int id = 2; id <= 256; id++) stage(writer, id, NOW.minusSeconds(7200));
            assertThrows(IOException.class, () -> writer.write(snapshot(257)));
            var result = writer.prune(Set.of(keep), AGE, NOW);
            assertEquals(256, result.inspected());
            assertEquals(255, result.deleted());
            String next = writer.write(snapshot(257)).name();
            assertEquals(Set.of(".writer.lock", keep, next), names());
            assertArrayEquals(protectedBytes, Files.readAllBytes(directory.resolve(keep)));
            assertArrayEquals(snapshot(257).json().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(directory.resolve(next)));
        }
    }

    @Test void missingProtectedUnknownTempCorruptAndForeignSchemaAbortBeforeAnyDeletion() throws Exception {
        for (String kind : List.of("missing", "unknown", "temp", "corrupt", "foreign", "oversized", "directory")) {
            Path root = Files.createDirectory(directory.resolve(kind));
            try (var writer = new AtomicOccupancyArtifacts(root)) {
                String keep = writer.write(snapshot(1)).name(); String candidate = writer.write(snapshot(2)).name();
                Files.setLastModifiedTime(root.resolve(candidate), FileTime.from(NOW.minusSeconds(7200)));
                switch (kind) {
                    case "unknown" -> Files.writeString(root.resolve("notes.txt"), "keep");
                    case "temp" -> Files.writeString(root.resolve(".occupancy-leftover.tmp"), "keep");
                    case "corrupt" -> Files.writeString(root.resolve(keep), "corrupt protected file");
                    case "foreign" -> {
                        byte[] bytes = "{\"otherProject\":true}".getBytes(StandardCharsets.UTF_8);
                        String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".json";
                        Files.write(root.resolve(name), bytes);
                    }
                    case "oversized" -> Files.write(root.resolve("a".repeat(64) + ".json"), new byte[128 * 1024 + 1]);
                    case "directory" -> Files.createDirectory(root.resolve("a".repeat(64) + ".json"));
                    default -> { }
                }
                Set<String> protect = Set.of(kind.equals("missing") ? "f".repeat(64) + ".json" : keep);
                assertThrows(IOException.class, () -> writer.prune(protect, AGE, NOW), kind);
                assertTrue(Files.exists(root.resolve(candidate)), kind);
            }
        }
    }

    @Test void validHashButNoncanonicalNpcJsonAlsoRejects() throws Exception {
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            String keep = stage(writer, 1, NOW.minusSeconds(7200));
            String candidate = stage(writer, 2, NOW.minusSeconds(7200));
            Path noncanonical = foreign(snapshot(3).json() + "\n");
            Files.setLastModifiedTime(noncanonical, FileTime.from(NOW));
            assertThrows(IOException.class, () -> writer.prune(Set.of(keep), AGE, NOW));
            assertTrue(Files.exists(directory.resolve(candidate))); assertTrue(Files.exists(noncanonical));
            assertThrows(IOException.class, () -> writer.prune(Set.of(keep, noncanonical.getFileName().toString()), AGE, NOW));
            assertTrue(Files.exists(directory.resolve(candidate)), "Protected recent invalid files must still abort before deletion");
        }
    }

    @Test void invalidParametersAndClosedWriterNeverDelete() throws Exception {
        var writer = new AtomicOccupancyArtifacts(directory);
        String keep;
        try {
            keep = stage(writer, 1, NOW.minusSeconds(7200));
            assertThrows(IOException.class, () -> writer.prune(Set.of(), AGE, NOW));
            assertThrows(IOException.class, () -> writer.prune(Set.of("../other"), AGE, NOW));
            var huge = new java.util.HashSet<String>();
            for (int i = 0; i < 257; i++) huge.add(String.format(java.util.Locale.ROOT, "%064x.json", i));
            assertThrows(IOException.class, () -> writer.prune(huge, AGE, NOW));
            for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE)))
                assertThrows(IOException.class, () -> writer.prune(Set.of(keep), invalid, NOW));
            assertThrows(IOException.class, () -> writer.prune(Set.of(keep), AGE, Instant.MAX));
            assertTrue(Files.exists(directory.resolve(keep)));
        } finally { writer.close(); }
        assertThrows(IOException.class, () -> writer.prune(Set.of(keep), AGE, NOW));
    }

    @Test void sequentialDeleteFailureIsPartialAndRetryKeepsProtection() throws Exception {
        int[] deletes = {0};
        var protectedNames = new java.util.HashSet<String>();
        try (var writer = new AtomicOccupancyArtifacts(directory,
                (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE), ignored -> Long.MAX_VALUE, path -> {
                    if (++deletes[0] == 2) throw new IOException("test deletion failure");
                    Files.delete(path);
                    protectedNames.clear(); // The caller's mutable set must already have been copied.
                })) {
            String keep = stage(writer, 1, NOW.minusSeconds(7200)); protectedNames.add(keep);
            stage(writer, 2, NOW.minusSeconds(7200)); stage(writer, 3, NOW.minusSeconds(7200));
            assertThrows(IOException.class, () -> writer.prune(protectedNames, AGE, NOW));
            assertTrue(Files.exists(directory.resolve(keep))); assertEquals(3, names().size());
            assertEquals(1, writer.prune(Set.of(keep), AGE, NOW).deleted());
            assertEquals(Set.of(".writer.lock", keep), names());
        }
    }

    @Test void changedCandidateIsRecheckedAfterPriorDeletion() throws Exception {
        var candidates = new ArrayList<Path>();
        try (var writer = new AtomicOccupancyArtifacts(directory,
                (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE), ignored -> Long.MAX_VALUE, path -> {
                    Files.delete(path);
                    Files.writeString(candidates.get(1), "changed after full validation");
                })) {
            String keep = stage(writer, 1, NOW.minusSeconds(7200));
            candidates.add(directory.resolve(stage(writer, 2, NOW.minusSeconds(7200))));
            candidates.add(directory.resolve(stage(writer, 3, NOW.minusSeconds(7200))));
            candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
            assertThrows(IOException.class, () -> writer.prune(Set.of(keep), AGE, NOW));
            assertTrue(Files.exists(directory.resolve(keep)));
            assertEquals("changed after full validation", Files.readString(candidates.get(1)));
        }
    }

    @Test void symlinkInventoryAbortsBeforeDeletionWhenSupported() throws Exception {
        Path outside = Files.createTempFile(directory.getParent(), "occupancy-retention-test-", ".json");
        try {
            try (var writer = new AtomicOccupancyArtifacts(directory)) {
                String keep = stage(writer, 1, NOW.minusSeconds(7200));
                String candidate = stage(writer, 2, NOW.minusSeconds(7200));
                try { Files.createSymbolicLink(directory.resolve("a".repeat(64) + ".json"), outside); }
                catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                    assumeTrue(false, "Symbolic links unavailable");
                }
                assertThrows(IOException.class, () -> writer.prune(Set.of(keep), AGE, NOW));
                assertTrue(Files.exists(directory.resolve(candidate))); assertTrue(Files.exists(outside));
            }
        } finally { Files.deleteIfExists(outside); }
    }
}
