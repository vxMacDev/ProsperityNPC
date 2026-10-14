package com.prosperitysmp.npc.economy;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Operator-owned binding of each systemd service to its server root, read by the offline recovery tools.
 * A runtime-masked unit has no loaded WorkingDirectory, so the tools cannot ask systemd; this file is the
 * independent record that the stopped and masked service is the one whose ledger is being adjudicated.
 * Format: one {@code service=/absolute/server/root} line per server, e.g. {@code minecraft=/opt/minecraft};
 * blank lines and {@code #} comments are allowed, nothing else is. The file must be a private (0600 or 0400)
 * root-owned regular file in a root-owned directory that only root can write.
 */
public final class RecoveryServers {
    public static final Path FILE = Path.of("/etc/prosperitynpc/recovery-servers.properties");
    private static final int MAX_BYTES = 4096;
    // No "." so only plain service names match; "minecraft.service" or a .socket unit cannot alias one.
    private static final String SERVICE = "[A-Za-z0-9][A-Za-z0-9_@-]{0,63}";

    private RecoveryServers() { }

    /** Returns the allowlisted server root for {@code service}; refuses unlisted services and unsafe custody. */
    public static String rootFor(String service) throws Exception {
        Path directory = FILE.getParent();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())
                || !Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).getName().equals("root")
                || Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).stream()
                    .anyMatch(p -> p == PosixFilePermission.GROUP_WRITE || p == PosixFilePermission.OTHERS_WRITE))
            throw new IllegalArgumentException("Recovery allowlist directory must be root-owned and writable only by root: " + directory);
        if (!Files.isRegularFile(FILE, LinkOption.NOFOLLOW_LINKS) || !FILE.equals(FILE.toRealPath())
                || !Files.getOwner(FILE, LinkOption.NOFOLLOW_LINKS).getName().equals("root") || Files.size(FILE) > MAX_BYTES
                || Files.getPosixFilePermissions(FILE, LinkOption.NOFOLLOW_LINKS).stream()
                    .anyMatch(p -> p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE))
            throw new IllegalArgumentException("Recovery allowlist must be a private root-owned regular file: " + FILE);
        byte[] bytes;
        try (var stream = Files.newInputStream(FILE, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Recovery allowlist size changed");
        return rootFor(bytes, service);
    }

    static String rootFor(byte[] bytes, String service) {
        if (service == null || !service.matches(SERVICE)) throw new IllegalArgumentException("Unsupported service");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Recovery allowlist must be UTF-8");
        }
        String root = null;
        Set<String> services = new HashSet<>();
        Set<String> roots = new HashSet<>();
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            int split = line.indexOf('=');
            String key = split < 0 ? "" : line.substring(0, split), value = split < 0 ? "" : line.substring(split + 1);
            if (!key.matches(SERVICE) || !validRoot(value))
                throw new IllegalArgumentException("Recovery allowlist lines must be service=/absolute/server/root");
            if (!services.add(key)) throw new IllegalArgumentException("Recovery allowlist repeats service " + key);
            // One server per root, and no root inside another, so a masked service always names exactly one server.
            for (String other : roots)
                if (other.equals(value) || other.startsWith(value + "/") || value.startsWith(other + "/"))
                    throw new IllegalArgumentException("Recovery allowlist roots must be distinct and not nested");
            roots.add(value);
            if (key.equals(service)) root = value;
        }
        if (root == null) throw new IllegalArgumentException("Unsupported service");
        return root;
    }

    private static boolean validRoot(String root) {
        return root.matches("(/[^/\\s\\p{Z}\\p{Cntrl}]+)+")
                && Arrays.stream(root.split("/")).noneMatch(part -> part.equals(".") || part.equals(".."));
    }
}
