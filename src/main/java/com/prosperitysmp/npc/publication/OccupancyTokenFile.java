package com.prosperitysmp.npc.publication;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Read-only fixed token input beneath a trusted plugin data directory; no creation or repair. */
public final class OccupancyTokenFile {
    private OccupancyTokenFile() { }

    public static byte[] read(Path dataDirectory) throws IOException {
        try {
            Path path=Objects.requireNonNull(dataDirectory).resolve("publication-token.txt");
            var before=attributes(path);byte[] bytes;
            try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {bytes=input.readNBytes(45);}
            try {
                var after=attributes(path);
                if(!Objects.equals(before.fileKey(),after.fileKey()) || before.size()!=after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException();
                return decode(bytes);
            } finally {Arrays.fill(bytes,(byte)0);}
        } catch(IOException | RuntimeException invalid) {throw new IOException("Occupancy transport token unavailable");}
    }
    private static PosixFileAttributes attributes(Path path) throws IOException {
        var attributes=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile() || attributes.size()<43 || attributes.size()>44
                || !(attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ))
                || attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))))
            throw new IOException();
        return attributes;
    }
    static byte[] decode(byte[] bytes) throws IOException {
        int length=bytes.length;
        if(length==44 && bytes[43]=='\n')length--;
        if(length!=43)throw new IOException("Invalid occupancy token encoding");
        for(int i=0;i<43;i++) {
            int b=bytes[i]&255;
            if(!(b>='A'&&b<='Z' || b>='a'&&b<='z' || b>='0'&&b<='9' || b=='-' || b=='_'))
                throw new IOException("Invalid occupancy token encoding");
        }
        byte[] encoded=Arrays.copyOf(bytes,43);
        try {
            byte[] decoded=Base64.getUrlDecoder().decode(encoded);
            if(decoded.length!=32 || !Arrays.equals(encoded,Base64.getUrlEncoder().withoutPadding().encode(decoded))) {
                Arrays.fill(decoded,(byte)0);throw new IOException("Invalid occupancy token encoding");
            }
            return decoded;
        } catch(IllegalArgumentException invalid) {throw new IOException("Invalid occupancy token encoding");}
        finally {Arrays.fill(encoded,(byte)0);}
    }
}
