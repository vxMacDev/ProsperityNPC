package com.prosperitysmp.npc.resident;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict reader for plugins/ProsperityNPC/residents.yml. Read once at full startup and only while
 * residents.cast-file-enabled is on. Unknown or duplicate keys, aliases, anchors, tags, oversized files and
 * symlinks are refused. Any problem gives the built-in three (source FILE_REJECTED), never an empty or partial
 * cast, and no file contents are ever put in an exception or log line.
 */
public final class ResidentCastFile {
    public static final String FILE_NAME = "residents.yml";
    public static final int MAX_BYTES = 128 * 1024;
    private static final Set<String> TOP = Set.of("version", "residents");
    private static final Set<String> REQUIRED = Set.of("key", "name", "household", "generation", "occupation",
            "means", "life-stage", "temperament", "preference");
    private static final Set<String> OPTIONAL = Set.of("lines");
    private static final Set<String> LINE_KEYS = Set.of("greeting", "about", "home");
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,31}");
    private static final String MERGE_KEY = "<<";

    private ResidentCastFile() { }

    /** Off: exactly {@link ResidentCast#builtIn()}. On: the file's cast, or the built-in three marked rejected. */
    public static ResidentCast load(Path pluginDataDir, boolean enabled) {
        if (!enabled) return ResidentCast.builtIn();
        try {
            return read(pluginDataDir);
        } catch (IllegalArgumentException rejected) {
            return ResidentCast.rejected();
        }
    }

    /** A missing file is a rejection too: the flag says the owner wants a file. */
    static ResidentCast read(Path pluginDataDir) {
        try {
            if (pluginDataDir == null) throw new IllegalArgumentException();
            Path path = pluginDataDir.resolve(FILE_NAME);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES) throw new IllegalArgumentException();
            // Repeat the no-follow policy at open, not just in the attribute check.
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                return read(input);
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Invalid residents.yml");
        }
    }

    /** Leaves the caller's stream open. Throws one generic IllegalArgumentException for every problem. */
    public static ResidentCast read(InputStream input) {
        try {
            require(input != null);
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            require(bytes.length <= MAX_BYTES);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            require(!text.startsWith("\ufeff"));
            Yaml yaml = new Yaml(new SafeConstructor(options()));
            // The event pass refuses every alias, anchor, explicit tag and merge key before anything is constructed.
            for (Event event : yaml.parse(new StringReader(text))) {
                require(!(event instanceof AliasEvent));
                if (event instanceof NodeEvent node) require(node.getAnchor() == null);
                if (event instanceof ScalarEvent scalar) {
                    require(scalar.getTag() == null || scalar.getImplicit().canOmitTagInPlainScalar()
                            || scalar.getImplicit().canOmitTagInNonPlainScalar());
                    // A plain << is a merge key. SafeConstructor merges it and drops the << key before map() looks,
                    // which would hide a duplicate or unknown key.
                    require(!(scalar.isPlain() && MERGE_KEY.equals(scalar.getValue())));
                }
                if (event instanceof CollectionStartEvent collection) require(collection.getTag() == null || collection.getImplicit());
            }
            Object document = new Yaml(new SafeConstructor(options())).load(new StringReader(text));
            return cast(document);
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            // Parser messages quote the input. Never relay them.
            throw new IllegalArgumentException("Invalid residents.yml");
        }
    }

    private static LoaderOptions options() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setAllowRecursiveKeys(false);
        options.setCodePointLimit(MAX_BYTES);
        options.setNestingDepthLimit(8);
        return options;
    }

    private static ResidentCast cast(Object document) {
        Map<?, ?> top = map(document, TOP, Set.of());
        require(Integer.valueOf(1).equals(top.get("version")));
        require(top.get("residents") instanceof List<?>);
        List<?> entries = (List<?>) top.get("residents");
        require(!entries.isEmpty() && entries.size() <= ResidentCast.MAX_RESIDENTS);
        List<ResidentProfile> profiles = new ArrayList<>();
        Map<UUID, ResidentLines> lines = new HashMap<>();
        Set<String> keys = new HashSet<>();
        Set<String> firstNames = new HashSet<>();
        Set<String> fullNames = new HashSet<>();
        Set<String> occupations = new HashSet<>();
        for (ResidentProfile.Occupation occupation : ResidentProfile.Occupation.values())
            occupations.add(occupation.name().toLowerCase(Locale.ROOT));
        for (Object raw : entries) {
            Map<?, ?> entry = map(raw, REQUIRED, OPTIONAL);
            String key = string(entry.get("key"));
            require(KEY.matcher(key).matches() && keys.add(key));
            // A key equal to an occupation would share the generic resident/v1/<occupation> skin keys.
            require(!occupations.contains(key));
            String name = string(entry.get("name"));
            require(name.length() >= 3 && name.length() <= 64 && cleanText(name) && cleanName(name));
            String[] words = name.split(" ", -1);
            require(words.length == 2 && !words[0].isEmpty() && !words[1].isEmpty());
            // Same folding as ResidentCast.byFirstName (equalsIgnoreCase): two names it treats as one never both load.
            String first = words[0];
            require(firstNames.stream().noneMatch(first::equalsIgnoreCase) && firstNames.add(first) && fullNames.add(name));
            String household = string(entry.get("household"));
            require(household.length() <= 64 && household.equals(words[1]));
            require(entry.get("generation") instanceof Integer);
            int generation = (Integer) entry.get("generation");
            require(generation >= 1 && generation <= 8);
            ResidentProfile profile = ResidentCatalogue.authored(key, name, household, generation,
                    named(ResidentProfile.Occupation.class, entry.get("occupation")),
                    named(ResidentProfile.Means.class, entry.get("means")),
                    named(ResidentProfile.LifeStage.class, entry.get("life-stage")),
                    named(ResidentProfile.Temperament.class, entry.get("temperament")),
                    named(ResidentProfile.Preference.class, entry.get("preference")));
            profiles.add(profile);
            if (entry.containsKey("lines")) {
                Map<?, ?> authored = map(entry.get("lines"), Set.of(), LINE_KEYS);
                ResidentLines parsed = new ResidentLines(lineList(authored.get("greeting")),
                        lineList(authored.get("about")), lineList(authored.get("home")));
                if (!parsed.isEmpty()) lines.put(profile.id(), parsed);
            }
        }
        return new ResidentCast(profiles, lines, ResidentCast.Source.FILE);
    }

    /** Exactly the required keys, plus any of the optional ones, all strings. */
    private static Map<?, ?> map(Object value, Set<String> required, Set<String> optional) {
        require(value instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) value;
        for (Object key : map.keySet()) require(key instanceof String text && (required.contains(text) || optional.contains(text)));
        require(map.keySet().containsAll(required));
        return map;
    }

    private static List<String> lineList(Object value) {
        if (value == null) return List.of();
        require(value instanceof List<?> list && list.size() == ResidentDialogue.VARIANT_COUNT);
        List<String> lines = new ArrayList<>();
        for (Object line : (List<?>) value) {
            require(line instanceof String text && ResidentLines.validLine(text));
            lines.add((String) line);
        }
        return lines;
    }

    private static <E extends Enum<E>> E named(Class<E> type, Object value) {
        String text = string(value);
        for (E constant : type.getEnumConstants()) if (constant.name().equals(text)) return constant;
        throw new IllegalArgumentException();
    }

    private static String string(Object value) {
        require(value instanceof String);
        return (String) value;
    }

    private static boolean cleanText(String value) {
        return value.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x00a7
                || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029);
    }

    /**
     * Names reach dialogue through {name} and form titles, so they follow the line rules for money signs and
     * braces too. The only space allowed is a plain ASCII space, so tab-complete and /resident talk find them.
     */
    private static boolean cleanName(String value) {
        return value.codePoints().noneMatch(c -> c == '{' || c == '}' || ResidentLines.moneySign(c)
                || (c != ' ' && (Character.isWhitespace(c) || Character.isSpaceChar(c))));
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException();
    }
}
