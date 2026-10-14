package com.prosperitysmp.npc.publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable public-schema artifacts plus a private selected-generation pointer; not public serving,
 * privacy approval or a freshness check. All paths remain outside the web root.
 * Blocking filesystem operations belong on the publication worker, including acquisition and close.
 * The existing dedicated directory and ancestors are trusted and cooperatively managed, not a sandbox.
 * File contents are forced before atomic rename; directory fsync/power-loss durability is not claimed.
 * Never unlink .writer.lock while any writer process is active: replacing its inode defeats exclusion.
 * Crash-leftover temporary files hold writes until explicit lock-held recovery; no automatic startup deletion.
 */
public final class AtomicOccupancyArtifacts implements AutoCloseable {
    private static final int MAX_BYTES = 128 * 1024;
    private static final int MAX_ARTIFACTS = 256;
    private static final Pattern NAME = Pattern.compile("[0-9a-f]{64}\\.json");
    private static final Pattern TEMP_NAME = Pattern.compile("\\.occupancy-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.tmp");
    private final Path directory;
    private final Mover mover;
    private final Space space;
    private final Deleter deleter;
    private final FileChannel lockChannel;
    private final FileLock writerLock;
    private boolean closed;
    private UUID selectionAuthorization;
    private static final String SELECTION = ".selection.json";

    public record Receipt(String name, String sha256, Instant capturedAt, int bytes) { }
    public record PruneResult(int inspected, int deleted, long bytes) { }
    private record Artifact(Path path, BasicFileAttributes attributes, Instant capturedAt) { }
    private record Temporary(Artifact artifact, String hash) { }
    @FunctionalInterface interface Mover { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface Space { long usable(Path directory) throws IOException; }
    @FunctionalInterface interface Deleter { void delete(Path path) throws IOException; }

    public AtomicOccupancyArtifacts(Path trustedDirectory) throws IOException {
        this(trustedDirectory, (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE),
                path -> Files.getFileStore(path).getUsableSpace());
    }

    // Test-only failure seams; production always requires ATOMIC_MOVE and checks the actual file store.
    AtomicOccupancyArtifacts(Path trustedDirectory, Mover mover, Space space) throws IOException {
        this(trustedDirectory, mover, space, Files::delete);
    }

    AtomicOccupancyArtifacts(Path trustedDirectory, Mover mover, Space space, Deleter deleter) throws IOException {
        directory = Objects.requireNonNull(trustedDirectory).toAbsolutePath().normalize();
        this.mover = Objects.requireNonNull(mover); this.space = Objects.requireNonNull(space);
        this.deleter = Objects.requireNonNull(deleter);
        checkDirectory();
        Path lockPath = directory.resolve(".writer.lock");
        try { checkLockFile(lockPath); } catch (NoSuchFileException absent) { /* First writer reserves it below. */ }
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Occupancy writer busy", busy); }
            if (acquired == null) throw new IOException("Occupancy writer busy");
            checkLockFile(lockPath);
            lockChannel = channel; writerLock = acquired;
        } catch (IOException | RuntimeException | Error failure) {
            if (acquired != null) {
                try { acquired.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** An exception can follow a successful move; it does not prove the artifact is absent. */
    public synchronized Receipt write(PublicOccupancySnapshot snapshot) throws IOException {
        if (closed || !writerLock.isValid()) throw new IOException("Occupancy writer closed");
        Objects.requireNonNull(snapshot);
        if (snapshot.isEmpty()) throw new IOException("Empty occupancy artifact held");
        byte[] payload = snapshot.json().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_BYTES) throw new IOException("Occupancy artifact exceeds byte bound");
        String hash = hash(payload);
        String name = hash + ".json";
        Receipt receipt = new Receipt(name, hash, snapshot.capturedAt(), payload.length);
        checkDirectory();
        int count = scan();
        Path target = directory.resolve(name);
        if (matchesExisting(target, payload)) return receipt;
        if (count >= MAX_ARTIFACTS || space.usable(directory) < payload.length)
            throw new IOException("Occupancy artifact storage budget exhausted");
        Path temp = directory.resolve(".occupancy-" + UUID.randomUUID() + ".tmp");
        boolean owned = false;
        Throwable failure = null;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                owned = true;
                ByteBuffer bytes = ByteBuffer.wrap(payload);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            checkDirectory();
            // The lifetime OS lock excludes other cooperative writers.
            if (!matchesExisting(target, payload)) mover.move(temp, target);
            if (!matchesExisting(target, payload)) throw new IOException("Occupancy artifact missing after move");
            return receipt;
        } catch (IOException | RuntimeException | Error problem) {
            failure = problem;
            throw problem;
        } finally {
            if (owned) {
                try { Files.deleteIfExists(temp); }
                catch (IOException cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }

    /**
     * Explicit retention only. The caller must supply the complete authoritative current/rollback keep-set.
     * All inventory is verified before deletion, but sequential deletion is not transactional: a failure
     * can leave partial cleanup. Retry with the same protected set is safe; protected files are never deleted.
     * bytes counts successfully deleted payload bytes, not reclaimed filesystem allocation.
     */
    public synchronized PruneResult prune(Set<String> keepNames, Duration minAge, Instant now) throws IOException {
        return pruneInventory(keepNames, minAge, now, false);
    }

    /** Explicit cleanup after verified empty selection: no published generation remains to protect. */
    public synchronized PruneResult pruneUnselected(Duration minAge, Instant now) throws IOException {
        requireOpen();
        if (selectionAuthorization == null) throw new IOException("Unverified empty selection");
        var selected = readSelection(selectionAuthorization);
        if (selected.isEmpty() || !selected.get().entries().isEmpty()) throw new IOException("Selection is not empty");
        return pruneInventory(Set.of(), minAge, now, true);
    }

    private PruneResult pruneInventory(Set<String> keepNames, Duration minAge, Instant now, boolean emptySelection) throws IOException {
        if (closed || !writerLock.isValid()) throw new IOException("Occupancy writer closed");
        Set<String> keep;
        Instant cutoff;
        try {
            if (keepNames == null || (!emptySelection && keepNames.isEmpty()) || keepNames.size() > MAX_ARTIFACTS
                    || minAge == null || minAge.isZero() || minAge.isNegative() || now == null
                    || now.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !now.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw new IllegalArgumentException();
            keep = Set.copyOf(keepNames);
            if (keep.stream().anyMatch(name -> !NAME.matcher(name).matches())) throw new IllegalArgumentException();
            cutoff = now.minus(minAge);
        } catch (RuntimeException invalid) { throw new IOException("Invalid occupancy retention request"); }
        var combined = new HashSet<>(keep);
        combined.addAll(selectionProtection()); keep = Set.copyOf(combined);
        checkDirectory(); checkLockFile(directory.resolve(".writer.lock"));
        var inventory = new ArrayList<Artifact>();
        var found = new HashSet<String>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals(".writer.lock")) { checkLockFile(entry); continue; }
                if (name.equals(SELECTION)) { selectionProtection(); continue; }
                if (inventory.size() >= MAX_ARTIFACTS || !NAME.matcher(name).matches())
                    throw new IOException("Unexpected occupancy retention inventory");
                inventory.add(verifyArtifact(entry)); found.add(name);
            }
        }
        if (!found.containsAll(keep)) throw new IOException("Missing protected occupancy artifact");
        inventory.sort(Comparator.comparing(artifact -> artifact.path().getFileName().toString()));
        int deleted = 0; long bytes = 0;
        for (Artifact artifact : inventory) {
            if (keep.contains(artifact.path().getFileName().toString())
                    || !artifact.attributes().lastModifiedTime().toInstant().isBefore(cutoff)) continue;
            checkDirectory(); checkLockFile(directory.resolve(".writer.lock"));
            Artifact rechecked = verifyArtifact(artifact.path());
            if (!sameAttributes(artifact.attributes(), rechecked.attributes()))
                throw new IOException("Occupancy retention candidate changed");
            deleter.delete(artifact.path());
            deleted++; bytes += artifact.attributes().size();
        }
        return new PruneResult(inventory.size(), deleted, bytes);
    }

    /**
     * Explicit restart recovery of old, strictly named partial writes. Completed artifacts are all
     * validated but never removed. Recent temporary files remain and continue to hold publication.
     * Sequential deletion can fail partially; retry is safe. This requires the trusted directory and
     * cooperative writer lock, not protection against an adversarial process replacing files.
     */
    public synchronized PruneResult recoverTemporaryFiles(Duration minAge, Instant now) throws IOException {
        if (closed || !writerLock.isValid()) throw new IOException("Occupancy writer closed");
        Instant cutoff;
        try {
            if (minAge == null || minAge.isNegative() || minAge.isZero() || now == null
                    || now.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !now.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw new IllegalArgumentException();
            cutoff = now.minus(minAge);
        } catch (RuntimeException invalid) { throw new IOException("Invalid occupancy recovery request"); }
        checkDirectory(); checkLockFile(directory.resolve(".writer.lock"));
        var temporary = new ArrayList<Temporary>();
        int artifacts = 0;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals(".writer.lock")) { checkLockFile(entry); continue; }
                if (name.equals(SELECTION)) { selectionProtection(); continue; }
                if (NAME.matcher(name).matches()) {
                    if (++artifacts > MAX_ARTIFACTS) throw new IOException("Recovery artifact bound exceeded");
                    verifyArtifact(entry);
                } else if (TEMP_NAME.matcher(name).matches()) {
                    if (temporary.size() >= MAX_ARTIFACTS) throw new IOException("Recovery temporary bound exceeded");
                    temporary.add(verifyTemporary(entry));
                } else throw new IOException("Unexpected occupancy recovery inventory");
            }
        }
        temporary.sort(Comparator.comparing(t -> t.artifact().path().getFileName().toString()));
        int deleted = 0; long bytes = 0;
        for (Temporary candidate : temporary) {
            Artifact artifact = candidate.artifact();
            if (!artifact.attributes().lastModifiedTime().toInstant().isBefore(cutoff)) continue;
            checkDirectory(); checkLockFile(directory.resolve(".writer.lock"));
            Temporary rechecked = verifyTemporary(artifact.path());
            if (!sameAttributes(artifact.attributes(), rechecked.artifact().attributes())
                    || !candidate.hash().equals(rechecked.hash()))
                throw new IOException("Occupancy recovery candidate changed");
            deleter.delete(artifact.path()); deleted++; bytes += artifact.attributes().size();
        }
        return new PruneResult(temporary.size(), deleted, bytes);
    }

    private static Temporary verifyTemporary(Path path) throws IOException {
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_BYTES) throw new IOException("Invalid occupancy temporary file");
        byte[] payload;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { payload = input.readNBytes(MAX_BYTES + 1); }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (payload.length > MAX_BYTES || !after.isRegularFile() || !sameAttributes(before, after))
            throw new IOException("Occupancy temporary file changed");
        return new Temporary(new Artifact(path, after, null), hash(payload));
    }

    private static Artifact verifyArtifact(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_BYTES) throw new IOException("Invalid occupancy retention artifact");
        byte[] payload;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { payload = input.readNBytes(MAX_BYTES + 1); }
        if (payload.length > MAX_BYTES || !path.getFileName().toString().equals(hash(payload) + ".json"))
            throw new IOException("Occupancy retention artifact hash mismatch");
        Instant captured = PublicOccupancyArtifactCodec.verify(payload);
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || !sameAttributes(before, after)) throw new IOException("Occupancy retention artifact changed");
        return new Artifact(path, after, captured);
    }

    /** Read private metadata and verify every referenced artifact, not live publication authorization. */
    public synchronized java.util.Optional<OccupancyGenerationHistory> readSelection(UUID expectedAuthorization) throws IOException {
        requireOpen();
        byte[] bytes = selectionBytes();
        if (bytes == null) return java.util.Optional.empty();
        var history = OccupancySelectionCodec.decode(bytes, expectedAuthorization);
        verifySelectionArtifacts(history);
        selectionAuthorization = expectedAuthorization;
        return java.util.Optional.of(history);
    }

    /**
     * Compare-and-replace the private pointer. null previous means absent; lost-ack retries reuse exact
     * committed bytes. Caller may not claim publication until return/readback and live authorization checks.
     */
    public synchronized void commitSelection(OccupancyGenerationHistory previous, OccupancyGenerationHistory next,
                                             UUID expectedAuthorization) throws IOException {
        requireOpen();
        byte[] before, after;
        try {
            before = previous == null ? null : OccupancySelectionCodec.encode(previous, expectedAuthorization);
            after = OccupancySelectionCodec.encode(next, expectedAuthorization);
        } catch (RuntimeException invalid) { throw new IOException("Invalid occupancy selection commit"); }
        verifySelectionArtifacts(next);
        byte[] actual = selectionBytes();
        if (Arrays.equals(actual, after)) { selectionAuthorization = expectedAuthorization; return; }
        if (!Arrays.equals(actual, before)) throw new IOException("Occupancy selection changed");
        verifyForwardSelection(previous, next);
        if (actual != null) {
            var old = OccupancySelectionCodec.decode(actual, expectedAuthorization);
            verifySelectionArtifacts(old); selectionAuthorization = expectedAuthorization;
        }
        scan();
        replaceSelection(before, after, next);
        selectionAuthorization = expectedAuthorization;
    }

    /**
     * Explicit revocation, never a normal publication transition. Caller supplies the exact known prior
     * metadata and distinct new authorization generation. Does not delete history or require old source
     * artifacts to be healthy. Runtime serving must be revoked BEFORE this potentially failing I/O.
     */
    public synchronized OccupancyGenerationHistory resetSelection(OccupancyGenerationHistory previous,
            UUID previousAuthorization, UUID newAuthorization) throws IOException {
        selectionAuthorization = null;
        requireOpen();
        byte[] before, after;
        OccupancyGenerationHistory empty;
        try {
            if (previous == null || previousAuthorization == null || newAuthorization == null
                    || previousAuthorization.equals(newAuthorization)) throw new IllegalArgumentException();
            before = OccupancySelectionCodec.encode(previous, previousAuthorization);
            empty = OccupancyGenerationHistory.empty(newAuthorization);
            after = OccupancySelectionCodec.encode(empty, newAuthorization);
        } catch (RuntimeException invalid) { throw new IOException("Invalid occupancy selection reset"); }
        byte[] actual = selectionBytes();
        if (Arrays.equals(actual, after)) { selectionAuthorization = newAuthorization; return empty; }
        if (!Arrays.equals(actual, before)) throw new IOException("Occupancy selection changed before reset");
        // Revocation must not depend on a still-readable artifact that is being withdrawn.
        OccupancySelectionCodec.decode(actual, previousAuthorization);
        replaceSelection(before, after, empty);
        selectionAuthorization = newAuthorization;
        return empty;
    }

    /**
     * Startup/configuration barrier, called with serving already off and a new caller-owned generation.
     * Stored identity is inspected solely to revoke it. Same-token empty retries are allowed, but
     * same-token nonempty history cannot be resumed. Does not delete orphan files or approve a scope.
     */
    public synchronized OccupancyGenerationHistory beginAuthorization(UUID freshGeneration) throws IOException {
        selectionAuthorization = null;
        requireOpen();
        if (freshGeneration == null) throw new IOException("Missing occupancy authorization generation");
        byte[] actual = selectionBytes();
        var empty = OccupancyGenerationHistory.empty(freshGeneration);
        if (actual == null) {
            replaceSelection(null, OccupancySelectionCodec.encode(empty, freshGeneration), empty);
            selectionAuthorization = freshGeneration;
            return empty;
        }
        var inspected = OccupancySelectionCodec.inspectForReset(actual);
        if (freshGeneration.equals(inspected.recordedGeneration())) {
            if (!inspected.history().entries().isEmpty()) throw new IOException("Cannot resume old occupancy authorization");
            selectionAuthorization = freshGeneration;
            return empty;
        }
        return resetSelection(inspected.history(), inspected.recordedGeneration(), freshGeneration);
    }

    private void replaceSelection(byte[] before, byte[] after, OccupancyGenerationHistory next) throws IOException {
        if (space.usable(directory) < after.length) throw new IOException("Occupancy selection storage budget exhausted");
        Path temp = directory.resolve(".occupancy-" + UUID.randomUUID() + ".tmp");
        boolean owned = false;
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                owned = true; var buffer = ByteBuffer.wrap(after);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            requireOpen();
            if (!Arrays.equals(selectionBytes(), before)) throw new IOException("Occupancy selection changed before commit");
            verifySelectionArtifacts(next);
            mover.move(temp, directory.resolve(SELECTION));
            if (!Arrays.equals(selectionBytes(), after)) throw new IOException("Occupancy selection missing after commit");
        } finally { if (owned) Files.deleteIfExists(temp); }
    }

    private void requireOpen() throws IOException {
        if (closed || !writerLock.isValid()) throw new IOException("Occupancy writer closed");
        checkDirectory(); checkLockFile(directory.resolve(".writer.lock"));
    }
    private static void verifyForwardSelection(OccupancyGenerationHistory previous, OccupancyGenerationHistory next) throws IOException {
        var old = previous == null ? java.util.List.<OccupancyGenerationHistory.Entry>of() : previous.entries();
        var proposed = next.entries();
        if (old.isEmpty()) {
            if (proposed.size() <= 1) return;
        } else if (proposed.size() == Math.min(3, old.size() + 1)
                && proposed.getFirst().receipt().capturedAt().isAfter(old.getFirst().receipt().capturedAt())
                && !proposed.getFirst().selectedAt().isBefore(old.getFirst().selectedAt())
                && proposed.subList(1, proposed.size()).equals(old.subList(0, Math.min(2, old.size())))) return;
        throw new IOException("Occupancy selection is not a forward transition");
    }
    private byte[] selectionBytes() throws IOException {
        Path file = directory.resolve(SELECTION);
        BasicFileAttributes before;
        try { before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return null; }
        if (!before.isRegularFile() || before.size() > OccupancySelectionCodec.MAX_BYTES)
            throw new IOException("Invalid occupancy selection file");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(OccupancySelectionCodec.MAX_BYTES + 1); }
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || bytes.length > OccupancySelectionCodec.MAX_BYTES || !sameAttributes(before, after))
            throw new IOException("Occupancy selection file changed");
        return bytes;
    }
    private void verifySelectionArtifacts(OccupancyGenerationHistory history) throws IOException {
        for (var entry : history.entries()) {
            Receipt receipt = entry.receipt();
            Artifact artifact = verifyArtifact(directory.resolve(receipt.name()));
            if (artifact.attributes().size() != receipt.bytes() || !artifact.capturedAt().equals(receipt.capturedAt()))
                throw new IOException("Occupancy selection receipt mismatch");
        }
    }
    private Set<String> selectionProtection() throws IOException {
        byte[] bytes = selectionBytes();
        if (bytes == null) return Set.of();
        if (selectionAuthorization == null) throw new IOException("Occupancy selection must be verified before writes");
        var history = OccupancySelectionCodec.decode(bytes, selectionAuthorization);
        verifySelectionArtifacts(history);
        return history.protectedNames(selectionAuthorization);
    }

    private static boolean sameAttributes(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                && before.creationTime().equals(after.creationTime()) && Objects.equals(before.fileKey(), after.fileKey());
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { writerLock.release(); } finally { lockChannel.close(); }
    }

    private static void checkLockFile(Path lockPath) throws IOException {
        var attributes = Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != 0)
            throw new IOException("Invalid occupancy writer lock");
    }

    private void checkDirectory() throws IOException {
        if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
            throw new IOException("Invalid occupancy artifact directory");
    }

    private int scan() throws IOException {
        int count = 0;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().equals(".writer.lock")) {
                    checkLockFile(entry); continue;
                }
                if (entry.getFileName().toString().equals(SELECTION)) { selectionProtection(); continue; }
                if (++count > MAX_ARTIFACTS || !NAME.matcher(entry.getFileName().toString()).matches())
                    throw new IOException("Unexpected occupancy artifact inventory");
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES)
                    throw new IOException("Invalid occupancy artifact inventory");
            }
        }
        return count;
    }

    private static boolean matchesExisting(Path target, byte[] payload) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return false; }
        if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES)
            throw new IOException("Invalid existing occupancy artifact");
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(payload, input.readNBytes(MAX_BYTES + 1)))
                throw new IOException("Existing occupancy artifact differs");
        }
        return true;
    }

    private static String hash(byte[] payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
