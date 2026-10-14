package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicOccupancyArtifactsTest {
    @TempDir Path directory;
    private static final Instant CAPTURED = Instant.parse("2026-09-08T12:00:00Z");

    private static PublicOccupancySnapshot snapshot(int number) {
        var property = new Property(new UUID(0, number), new UUID(0, 99), "c" + number,
                "PRIVATE_MARKET", "PRIVATE_CLASS", 2, 827364, 918273, new UUID(1, 1), 0, 80, true);
        return PublicOccupancySnapshot.build(CAPTURED, List.of(new Row(property, 1)),
                List.of(new Approval(property.propertyId(), property.worldUuid(), property.regionId(), "main")));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private List<String> names() throws IOException {
        try (var entries = Files.list(directory)) { return entries.map(p -> p.getFileName().toString()).sorted().toList(); }
    }

    @Test void exactPublicBytesHashReceiptAndIdempotentReuse() throws Exception {
        var snapshot = snapshot(1); byte[] expected = snapshot.json().getBytes(StandardCharsets.UTF_8);
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            var receipt = writer.write(snapshot);
            assertEquals(hash(expected) + ".json", receipt.name());
            assertEquals(hash(expected), receipt.sha256());
            assertEquals(expected.length, receipt.bytes()); assertEquals(CAPTURED, receipt.capturedAt());
            assertArrayEquals(expected, Files.readAllBytes(directory.resolve(receipt.name())));
            var modified = Files.getLastModifiedTime(directory.resolve(receipt.name()));
            assertEquals(receipt, writer.write(snapshot));
            assertEquals(modified, Files.getLastModifiedTime(directory.resolve(receipt.name())));
            assertEquals(List.of(".writer.lock", receipt.name()), names());
            assertFalse(new String(expected, StandardCharsets.UTF_8).contains("PRIVATE"));
        }
    }

    @Test void exclusiveLifetimeLockAllowsNextInstanceOnlyAfterClose() throws Exception {
        var first = new AtomicOccupancyArtifacts(directory);
        try {
            assertThrows(IOException.class, () -> new AtomicOccupancyArtifacts(directory));
            first.write(snapshot(1));
        } finally { first.close(); }
        first.close();
        assertThrows(IOException.class, () -> first.write(snapshot(1)));
        try (var next = new AtomicOccupancyArtifacts(directory)) { assertNotNull(next.write(snapshot(1))); }
    }

    @Test void corruptionIsNeverOverwritten() throws Exception {
        var snapshot = snapshot(1);
        Path target = directory.resolve(hash(snapshot.json().getBytes(StandardCharsets.UTF_8)) + ".json");
        byte[] corrupt = "corrupt".getBytes(StandardCharsets.UTF_8); Files.write(target, corrupt);
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            assertThrows(IOException.class, () -> writer.write(snapshot));
            assertArrayEquals(corrupt, Files.readAllBytes(target));
            assertEquals(2, names().size());
        }
    }

    @Test void atomicMoveFailureCleansOnlyOwnedTemporaryAndRetainsHistory() throws Exception {
        String existing;
        try (var first = new AtomicOccupancyArtifacts(directory)) { existing = first.write(snapshot(1)).name(); }
        byte[] before = Files.readAllBytes(directory.resolve(existing));
        try (var writer = new AtomicOccupancyArtifacts(directory, (source, target) -> {
            assertTrue(Files.isRegularFile(source));
            assertArrayEquals(snapshot(2).json().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(source));
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
        }, ignored -> Long.MAX_VALUE)) {
            assertThrows(AtomicMoveNotSupportedException.class, () -> writer.write(snapshot(2)));
            assertEquals(List.of(".writer.lock", existing), names());
            assertArrayEquals(before, Files.readAllBytes(directory.resolve(existing)));
        }
    }

    @Test void lowDiskSpaceFailsWithoutDeletingHistoryOrCreatingTemp() throws Exception {
        try (var writer = new AtomicOccupancyArtifacts(directory,
                (source, target) -> { throw new AssertionError("Must not move"); }, ignored -> 0)) {
            assertThrows(IOException.class, () -> writer.write(snapshot(1)));
            assertEquals(List.of(".writer.lock"), names());
        }
    }

    @Test void lostAcknowledgmentAfterAtomicMoveRetainsArtifactAndRetryReusesIt() throws Exception {
        var snapshot = snapshot(1);
        byte[] expected = snapshot.json().getBytes(StandardCharsets.UTF_8);
        String name = hash(expected) + ".json";
        int[] moves = {0};
        try (var writer = new AtomicOccupancyArtifacts(directory, (source, target) -> {
            moves[0]++;
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            throw new IOException("Test acknowledgment lost after move");
        }, ignored -> Long.MAX_VALUE)) {
            assertThrows(IOException.class, () -> writer.write(snapshot));
            assertArrayEquals(expected, Files.readAllBytes(directory.resolve(name)));
            assertEquals(List.of(".writer.lock", name), names());
            var receipt = writer.write(snapshot);
            assertEquals(name, receipt.name()); assertEquals(expected.length, receipt.bytes());
            assertEquals(hash(expected), receipt.sha256()); assertEquals(CAPTURED, receipt.capturedAt());
            assertEquals(1, moves[0], "Retry must reuse the validated file without another move");
            assertEquals(List.of(".writer.lock", name), names());
        }
    }

    @Test void crashLeftoverTempHoldsWritesWithoutAutomaticRecovery() throws Exception {
        Path leftover = directory.resolve(".occupancy-" + UUID.randomUUID() + ".tmp");
        Files.writeString(leftover, "incomplete prior attempt");
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            assertThrows(IOException.class, () -> writer.write(snapshot(1)));
            assertEquals("incomplete prior attempt", Files.readString(leftover));
            assertEquals(2, names().size());
        }
    }

    @Test void inventoryLimitAllowsReuseButNotNewArtifacts() throws Exception {
        AtomicOccupancyArtifacts.Receipt first;
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            first = writer.write(snapshot(1));
            for (int i = 2; i <= 256; i++) writer.write(snapshot(i));
            assertEquals(first, writer.write(snapshot(1)));
            assertThrows(IOException.class, () -> writer.write(snapshot(257)));
            assertEquals(257, names().size());
        }
    }

    @Test void unexpectedFilesDirectoriesAndOversizedInventoryRejectWithoutDeletion() throws Exception {
        Path unknown = directory.resolve("notes.txt"); Files.writeString(unknown, "retain");
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            assertThrows(IOException.class, () -> writer.write(snapshot(1))); assertTrue(Files.exists(unknown));
            Files.delete(unknown);
            Path oversized = directory.resolve("a".repeat(64) + ".json"); Files.write(oversized, new byte[128 * 1024 + 1]);
            assertThrows(IOException.class, () -> writer.write(snapshot(1))); assertTrue(Files.exists(oversized));
            Files.delete(oversized);
            Path child = directory.resolve("b".repeat(64) + ".json"); Files.createDirectory(child);
            assertThrows(IOException.class, () -> writer.write(snapshot(1))); assertTrue(Files.isDirectory(child));
        }
    }

    @Test void missingAndFileRootRejectWithoutCreatingDirectory() throws Exception {
        Path missing = directory.resolve("missing");
        assertThrows(IOException.class, () -> new AtomicOccupancyArtifacts(missing)); assertFalse(Files.exists(missing));
        Path file = directory.resolve("file"); Files.writeString(file, "keep");
        assertThrows(IOException.class, () -> new AtomicOccupancyArtifacts(file));
        assertEquals("keep", Files.readString(file));
    }

    @Test void symlinkRootLockAndArtifactRejectWhenSupported() throws Exception {
        Path actual = Files.createDirectory(directory.resolve("actual"));
        Path rootLink = directory.resolve("root-link");
        symlink(rootLink, actual);
        assertThrows(IOException.class, () -> new AtomicOccupancyArtifacts(rootLink));
        Path external = directory.resolve("external"); Files.writeString(external, "outside");
        Path lock = actual.resolve(".writer.lock"); symlink(lock, external);
        assertThrows(IOException.class, () -> new AtomicOccupancyArtifacts(actual));
        Files.delete(lock);
        Path artifact = actual.resolve("a".repeat(64) + ".json"); symlink(artifact, external);
        try (var writer = new AtomicOccupancyArtifacts(actual)) {
            assertThrows(IOException.class, () -> writer.write(snapshot(1)));
        }
        assertEquals("outside", Files.readString(external));
    }

    @Test void malformedLockDoesNotGetTruncatedOrPreventLaterValidAcquisition() throws Exception {
        Path lock = directory.resolve(".writer.lock"); Files.writeString(lock, "retain");
        assertThrows(IOException.class, () -> new AtomicOccupancyArtifacts(directory));
        assertEquals("retain", Files.readString(lock));
        Files.delete(lock);
        try (var writer = new AtomicOccupancyArtifacts(directory)) { assertNotNull(writer.write(snapshot(1))); }
    }

    @Test void emptyRejectedAndMaximumLegitimateProjectionFitsByteBudget() throws Exception {
        try (var writer = new AtomicOccupancyArtifacts(directory)) {
            var empty = PublicOccupancySnapshot.build(CAPTURED, List.of(), List.of());
            assertThrows(IOException.class, () -> writer.write(empty));
            assertEquals(List.of(".writer.lock"), names());
            var rows = new ArrayList<Row>(); var approvals = new ArrayList<Approval>();
            for (int i = 0; i < 512; i++) {
                String suffix = Integer.toString(i);
                String region = "r".repeat(128 - suffix.length()) + suffix;
                var p = new Property(new UUID(0, i), new UUID(0, 9999), region,
                        "market", "class", 4, 100, 100, null, 0, 80, true);
                rows.add(new Row(p, 1));
                approvals.add(new Approval(p.propertyId(), p.worldUuid(), region, "w".repeat(64)));
            }
            var large = PublicOccupancySnapshot.build(CAPTURED, rows, approvals);
            var receipt = writer.write(large); assertTrue(receipt.bytes() <= 128 * 1024);
            assertArrayEquals(large.json().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(directory.resolve(receipt.name())));
        }
    }

    private static void symlink(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getClass().getSimpleName());
        }
    }
}
