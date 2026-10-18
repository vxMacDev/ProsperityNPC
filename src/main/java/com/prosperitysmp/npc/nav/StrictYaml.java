package com.prosperitysmp.npc.nav;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/**
 * The loader for waypoint and route files. Never Bukkit's YamlConfiguration, which lifts SnakeYAML's alias
 * and size limits: this one refuses duplicate keys, deep nesting and big inputs, and a first pass over the
 * parse events refuses every alias, anchor, explicit tag and merge key, and any whole number not written in
 * plain decimal, before anything is built. JSON is accepted because it is YAML flow style. Parser messages
 * quote the input, so they are never passed on.
 */
public final class StrictYaml {

    /** A bare file name in the import folder: no folders, no hidden tricks. */
    public static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}\\.(yml|yaml|json)");
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    private static final String MERGE_KEY = "<<";
    // The only number form accepted: YAML 1.1 also reads 010 as octal, 0x10 as hex, 1_000 and 1:30 as numbers.
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)");
    // Read-only after construction; used only to ask what an unquoted scalar would be read as.
    private static final Resolver RESOLVER = new Resolver();

    /** Why a file was refused, in words safe to show staff (never the file's contents). */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String message) {
            super(message);
        }
    }

    private StrictYaml() {
    }

    /** Parses one document. Throws {@link Refused} for anything but plain maps, lists and scalars. */
    public static Object load(String text) {
        if (text == null) throw new Refused("The file is empty.");
        if (text.startsWith("﻿")) throw new Refused("The file starts with a byte-order mark; save it as plain UTF-8.");
        if (text.getBytes(StandardCharsets.UTF_8).length > DEFAULT_MAX_BYTES) throw new Refused("The file is too big.");
        try {
            for (Event event : new Yaml(new SafeConstructor(options())).parse(new StringReader(text))) {
                if (event instanceof AliasEvent) throw new Refused("The file uses a YAML alias; aliases are not allowed.");
                if (event instanceof NodeEvent node && node.getAnchor() != null) {
                    throw new Refused("The file uses a YAML anchor; anchors are not allowed.");
                }
                if (event instanceof ScalarEvent scalar) {
                    if (scalar.getTag() != null && !scalar.getImplicit().canOmitTagInPlainScalar()
                            && !scalar.getImplicit().canOmitTagInNonPlainScalar()) {
                        throw new Refused("The file uses an explicit YAML tag; tags are not allowed.");
                    }
                    if (scalar.isPlain() && MERGE_KEY.equals(scalar.getValue())) {
                        throw new Refused("The file uses a YAML merge key; merge keys are not allowed.");
                    }
                    if (scalar.isPlain() && !plainDecimalOrNotANumber(scalar.getValue())) {
                        throw new Refused("The file writes a number in a form other than plain decimal (no leading "
                                + "zeros, 0x, 0b, _ or :); write whole numbers like 72 or -128.");
                    }
                }
                if (event instanceof CollectionStartEvent collection && collection.getTag() != null
                        && !collection.getImplicit()) {
                    throw new Refused("The file uses an explicit YAML tag; tags are not allowed.");
                }
            }
            return new Yaml(new SafeConstructor(options())).load(new StringReader(text));
        } catch (Refused refused) {
            throw refused;
        } catch (RuntimeException | StackOverflowError failure) {
            // Duplicate keys, nesting and size limits all land here. The parser's own message quotes the input.
            throw new Refused("The file is not valid YAML or JSON, repeats a key, or nests too deeply.");
        }
    }

    /** False for an unquoted scalar the loader would read as a whole number written any way but plain decimal. */
    static boolean plainDecimalOrNotANumber(String value) {
        return !Tag.INT.equals(RESOLVER.resolve(NodeId.scalar, value, true)) || PLAIN_DECIMAL.matcher(value).matches();
    }

    static LoaderOptions options() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(1);
        options.setAllowRecursiveKeys(false);
        options.setNestingDepthLimit(8);
        options.setCodePointLimit(DEFAULT_MAX_BYTES);
        return options;
    }

    /**
     * Reads a file named in chat from the import folder: a bare name, a regular file, never through a link,
     * strict UTF-8, at most {@code maxBytes}.
     */
    public static String readImportFile(Path folder, String name, long maxBytes) {
        if (name == null || !FILE_NAME.matcher(name).matches() || name.startsWith(".")) {
            throw new Refused("Use a bare file name like district.yml (letters, digits, . _ -, ending .yml, .yaml or .json).");
        }
        Path path = folder.resolve(name).normalize();
        if (!path.getParent().equals(folder.normalize())) throw new Refused("The file must sit directly in the import folder.");
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new Refused("That is not a regular file.");
            if (attributes.size() > maxBytes) throw new Refused("The file is bigger than nav.import.max-bytes (" + maxBytes + ").");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                int readLimit = maxBytes < Integer.MAX_VALUE - 8 ? (int) (maxBytes + 1) : Integer.MAX_VALUE - 8;
                bytes = input.readNBytes(readLimit);
            }
            if (bytes.length > maxBytes) throw new Refused("The file is bigger than nav.import.max-bytes (" + maxBytes + ").");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new Refused("The file is not UTF-8 text.");
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new Refused("No file " + name + " in the import folder.");
        } catch (IOException failure) {
            throw new Refused("The file could not be read (" + failure.getClass().getSimpleName() + ").");
        }
    }
}
