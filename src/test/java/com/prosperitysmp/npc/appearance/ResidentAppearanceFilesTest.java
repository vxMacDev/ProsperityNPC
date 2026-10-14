package com.prosperitysmp.npc.appearance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ResidentAppearanceFilesTest {
    private static final String EMPTY = "{\"version\":1,\"assets\":[]}";
    @TempDir Path directory;

    private Path manifest() { return directory.resolve("resident-appearance.json"); }

    private void write(String text) throws IOException {
        Files.writeString(manifest(), text, StandardCharsets.UTF_8);
    }

    private static void generic(IllegalArgumentException error) {
        assertEquals("Cannot load resident appearance file", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void missingFileReturnsImmutableEmptyMapWithoutCreatingAnything() throws Exception {
        Map<String, SignedResidentSkin> result = ResidentAppearanceFiles.load(directory);
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.put("test", null));
        assertFalse(Files.exists(manifest()));
        try (var children = Files.list(directory)) { assertEquals(0, children.count()); }
        assertTrue(ResidentAppearanceFiles.load(directory, in -> {
            fail("Missing input must not invoke parser");
            return Map.of();
        }).isEmpty());
    }

    @Test void missingDirectoryDoesNotGetCreated() {
        Path absent = directory.resolve("absent");
        assertTrue(ResidentAppearanceFiles.load(absent).isEmpty());
        assertFalse(Files.exists(absent));
    }

    @Test void validEmptyManifestIsReadWithoutChangingItsBytes() throws Exception {
        write(EMPTY);
        byte[] before = Files.readAllBytes(manifest());
        assertTrue(ResidentAppearanceFiles.load(directory).isEmpty());
        assertArrayEquals(before, Files.readAllBytes(manifest()));
    }

    @Test void onlyExactFixedFilenameIsRead() throws Exception {
        Files.writeString(directory.resolve("appearance.json"), "invalid-other-input");
        assertTrue(ResidentAppearanceFiles.load(directory).isEmpty());
        write("invalid-fixed-input");
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory)));
    }

    @Test void emptyFileAndInvalidJsonAreErrorsNotMissingInput() throws Exception {
        for (String text : new String[]{"", "not-json-secret", EMPTY + " trailing-secret"}) {
            write(text);
            generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory)));
            assertEquals(text, Files.readString(manifest()));
        }
    }

    @Test void directoryAtFixedTargetIsRejectedBeforeParser() throws Exception {
        Files.createDirectory(manifest());
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory, in -> {
            fail("Non-regular input must not reach parser");
            return Map.of();
        })));
        assertTrue(Files.isDirectory(manifest()));
    }

    @Test void symlinkToRegularFileIsRejectedWhenPlatformPermitsCreation() throws Exception {
        Path actual = directory.resolve("actual.json");
        Files.writeString(actual, EMPTY);
        createSymlinkOrSkip(manifest(), actual);
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory)));
        assertTrue(Files.isSymbolicLink(manifest()));
        assertEquals(EMPTY, Files.readString(actual));
    }

    @Test void danglingSymlinkIsInvalidNotMissingWhenPlatformPermitsCreation() throws Exception {
        createSymlinkOrSkip(manifest(), directory.resolve("absent.json"));
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory)));
        assertTrue(Files.isSymbolicLink(manifest()));
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            assumeTrue(false, "Platform/permissions do not allow test symlink creation: " + unavailable.getClass().getSimpleName());
        }
    }

    @Test void parserByteLimitAppliesToActualFile() throws Exception {
        String exact = EMPTY + " ".repeat(512 * 1024 - EMPTY.length());
        write(exact);
        assertTrue(ResidentAppearanceFiles.load(directory).isEmpty());
        write(exact + " ");
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory)));
        assertEquals(512 * 1024 + 1, Files.size(manifest()));
    }

    @Test void ownedStreamClosesAfterSuccessfulParserReturn() throws Exception {
        write(EMPTY);
        var captured = new AtomicReference<InputStream>();
        var result = ResidentAppearanceFiles.load(directory, in -> {
            captured.set(in);
            return new java.util.HashMap<>();
        });
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.put("test", null));
        assertThrows(IOException.class, () -> captured.get().read());
    }

    @Test void ownedStreamClosesAndErrorIsSanitizedAfterParserFailure() throws Exception {
        write(EMPTY);
        var captured = new AtomicReference<InputStream>();
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory, in -> {
            captured.set(in);
            throw new IllegalArgumentException("secret-payload", new IOException("private-path"));
        })));
        assertThrows(IOException.class, () -> captured.get().read());
        assertEquals(EMPTY, Files.readString(manifest()));
    }

    @Test void nullParserResultAlsoClosesOwnedStream() throws Exception {
        write(EMPTY);
        var captured = new AtomicReference<InputStream>();
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory, in -> {
            captured.set(in);
            return null;
        })));
        assertThrows(IOException.class, () -> captured.get().read());
    }

    @Test void publicLoaderRejectsUnsignedUntrustedEntry() throws Exception {
        String key = new ResidentAppearanceCatalogue().allowedKeys().iterator().next();
        write("{\"version\":1,\"assets\":[{\"key\":\"" + key + "\",\"value\":\"YQ==\",\"signature\":\"YQ==\"}]}");
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory)));
    }

    @Test void unreadableFileFailsWhereFilesystemAndIdentityEnforcePosixPermissions() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"), "POSIX permission test unavailable");
        write(EMPTY);
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(manifest());
        try {
            Files.setPosixFilePermissions(manifest(), Set.of());
            assumeTrue(!Files.isReadable(manifest()), "Current identity bypasses POSIX read permissions");
            generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory)));
        } finally {
            Files.setPosixFilePermissions(manifest(), before);
        }
    }

    @Test void nullArgumentsFailGenerically() {
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(null)));
        generic(assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceFiles.load(directory, null)));
    }
}
