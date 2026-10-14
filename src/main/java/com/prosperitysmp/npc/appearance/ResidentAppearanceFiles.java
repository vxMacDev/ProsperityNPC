package com.prosperitysmp.npc.appearance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Fixed optional input beneath a trusted plugin data directory; never creates or writes files. */
public final class ResidentAppearanceFiles {
    private static final String FILE_NAME = "resident-appearance.json";

    private ResidentAppearanceFiles() { }

    /** Missing file means no assets. Every other failure rejects the load without publishing. */
    public static Map<String, SignedResidentSkin> load(Path pluginDataDir) {
        return load(pluginDataDir, ResidentAppearanceManifest::read);
    }

    /**
     * Same file rules, checked against the active cast's universe. {@code skippable} well-formed keys outside it are
     * skipped and counted rather than fatal. A missing file means no assets and nothing skipped.
     */
    public static ResidentAppearanceManifest.Result load(Path pluginDataDir, Set<String> allowed,
                                                         Predicate<String> skippable) {
        if (allowed == null || skippable == null) throw new IllegalArgumentException("Cannot load resident appearance file");
        int[] skipped = new int[1];
        Map<String, SignedResidentSkin> assets = load(pluginDataDir, input -> {
            var result = ResidentAppearanceManifest.read(input, allowed, skippable);
            skipped[0] = result.skipped();
            return result.assets();
        });
        return new ResidentAppearanceManifest.Result(assets, skipped[0]);
    }

    // Test-only parser injection for owned-stream lifecycle checks.
    static Map<String, SignedResidentSkin> load(Path pluginDataDir,
            Function<InputStream, Map<String, SignedResidentSkin>> testOnlyParser) {
        try {
            if (pluginDataDir == null || testOnlyParser == null) throw new IllegalArgumentException();
            Path path = pluginDataDir.resolve(FILE_NAME);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                return Map.of();
            }
            if (!attributes.isRegularFile()) throw new IllegalArgumentException();
            // Repeat the no-follow policy at open, not just the preceding attribute check.
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                return Map.copyOf(testOnlyParser.apply(input));
            }
        } catch (IOException | RuntimeException failure) {
            // Neither paths, parser payloads nor nested I/O details are exposed to callers.
            throw new IllegalArgumentException("Cannot load resident appearance file");
        }
    }
}
