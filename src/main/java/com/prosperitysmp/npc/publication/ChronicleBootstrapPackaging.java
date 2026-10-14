package com.prosperitysmp.npc.publication;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Offline packaging only: avoids putting Bukkit/plugin classes on the system loader via -javaagent. */
public final class ChronicleBootstrapPackaging {
    private static final String CLASS = "com/prosperitysmp/npc/publication/bootstrap/ChronicleBootstrap";
    private ChronicleBootstrapPackaging() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected <built NPC jar> <new skinny bootstrap jar>");
        Path input = Path.of(args[0]).toAbsolutePath().normalize(), output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!input.equals(input.toRealPath()) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                || !output.getParent().equals(output.getParent().toRealPath()))
            throw new IllegalArgumentException("Invalid artifact path");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", CLASS.replace('/', '.'));
        try (JarFile source = new JarFile(input.toFile());
             var raw = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             JarOutputStream target = new JarOutputStream(raw, manifest)) {
            var entries = source.stream().filter(entry -> entry.getName().equals(CLASS + ".class")
                    || entry.getName().startsWith(CLASS + "$") && entry.getName().endsWith(".class"))
                    .sorted(Comparator.comparing(JarEntry::getName)).toList();
            if (entries.isEmpty() || entries.size() > 8 || entries.stream().noneMatch(entry -> entry.getName().equals(CLASS + ".class")))
                throw new IllegalArgumentException("Bootstrap classes absent or exceed bound");
            for (JarEntry entry : entries) {
                byte[] bytes;
                try (var stream = source.getInputStream(entry)) { bytes = stream.readNBytes(262145); }
                if (bytes.length < 1 || bytes.length > 262144) throw new IllegalArgumentException("Bootstrap class exceeds bound");
                JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(0);
                target.putNextEntry(copy);
                target.write(bytes);
                target.closeEntry();
            }
        }
        System.out.println("Bootstrap sha256="
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output))));
    }
}
