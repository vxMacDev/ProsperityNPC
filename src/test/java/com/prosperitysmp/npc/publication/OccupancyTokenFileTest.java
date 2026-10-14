package com.prosperitysmp.npc.publication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyTokenFileTest {
    @TempDir Path root;
    @Test void acceptsOnlyCanonical32ByteBase64UrlWithOptionalLf() throws Exception {
        byte[] raw=OccupancyHttpServerTest.token();
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertArrayEquals(raw,OccupancyTokenFile.decode(encoded.getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(raw,OccupancyTokenFile.decode((encoded+"\n").getBytes(StandardCharsets.US_ASCII)));
        for(String bad:List.of(encoded+"=",encoded+"\r\n",encoded+"\n\n",encoded.substring(1)," "+encoded,
                encoded.substring(0,42)+"!",""))
            assertThrows(IOException.class,()->OccupancyTokenFile.decode(bad.getBytes(StandardCharsets.US_ASCII)));
        // A 43-character input with nonzero padding bits must not alias a valid token.
        assertThrows(IOException.class,()->OccupancyTokenFile.decode(("A".repeat(42)+"B").getBytes(StandardCharsets.US_ASCII)));
    }
    @Test void missingDirectoryOversizeAndNonPosixFailWithoutPrivateDetails() throws Exception {
        Path path=root.resolve("publication-token.txt");
        assertUnavailable();Files.createDirectory(path);assertUnavailable();Files.delete(path);
        Files.writeString(path,"A".repeat(45));assertUnavailable();
        Files.writeString(path,"A".repeat(43));
        if(!Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertUnavailable(); // This platform is deliberately unsupported, not silently permissive.
        } else {
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
            assertEquals(32,OccupancyTokenFile.read(root).length);
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));assertUnavailable();
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("r--------"));
            assertEquals(32,OccupancyTokenFile.read(root).length);
        }
    }
    private void assertUnavailable() {
        var failure=assertThrows(IOException.class,()->OccupancyTokenFile.read(root));
        assertEquals("Occupancy transport token unavailable",failure.getMessage());assertNull(failure.getCause());
    }
}
