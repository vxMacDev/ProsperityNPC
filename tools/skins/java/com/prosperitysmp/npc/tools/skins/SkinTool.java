package com.prosperitysmp.npc.tools.skins;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue;
import com.prosperitysmp.npc.appearance.SignedResidentSkin;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCastFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Offline resident skin tool. Never part of the installed plugin and never online.
 *
 * <pre>
 * author  &lt;skins.yml&gt; &lt;out-dir&gt;                  draw original 64x64 classic skins in code
 * recolor &lt;in.png&gt; &lt;RRGGBB=RRGGBB,...&gt; &lt;out.png&gt;  make an outfit variant by changing clothing colours only
 * prepare --out &lt;new.json&gt; [--residents &lt;residents.yml&gt;] [--current &lt;resident-appearance.json&gt;] &lt;candidate.json&gt;...
 * </pre>
 *
 * Every output is written CREATE_NEW: the tool never overwrites a file. Signing (the MineSkin upload) is not part
 * of this tool. See tools/skins/README.md.
 */
public final class SkinTool {
    public static final int SIZE = 64;
    private static final int HEAD_ROWS = 16;
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,31}");
    private static final Pattern COLOUR = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final Set<String> SLOTS = Set.of("everyday", "work", "leisure");
    private static final int MAX_CANDIDATE_BYTES = 16 * 1024;
    private static final int MAX_MANIFEST_BYTES = 512 * 1024;

    private SkinTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(2); return; }
        try {
            switch (args[0]) {
                case "author" -> {
                    if (args.length != 3) { usage(); System.exit(2); return; }
                    for (Path written : author(Path.of(args[1]), Path.of(args[2]))) System.out.println("wrote " + written);
                }
                case "recolor" -> {
                    if (args.length != 4) { usage(); System.exit(2); return; }
                    recolor(Path.of(args[1]), parseMap(args[2]), Path.of(args[3]));
                    System.out.println("wrote " + args[3]);
                }
                case "prepare" -> prepareCommand(args, System.out);
                default -> { usage(); System.exit(2); }
            }
        } catch (IllegalArgumentException | IOException refused) {
            System.err.println("Refused: " + refused.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("skins author <skins.yml> <out-dir>");
        System.err.println("skins recolor <in.png> <RRGGBB=RRGGBB,...> <out.png>");
        System.err.println("skins prepare --out <new.json> [--residents <residents.yml>] [--current <resident-appearance.json>] <candidate.json>...");
    }

    // ---------------------------------------------------------------- author

    /** One look: colours as 0xRRGGBB and a garment style. */
    record Look(int skin, int hair, int eyes, int top, int trim, int legs, int shoes, String style) {
        Look {
            if (!Set.of("plain", "apron", "jacket").contains(style)) throw new IllegalArgumentException("style must be plain, apron or jacket");
        }
    }

    record Request(String key, String slot, Look look) {
        Request {
            if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("bad key");
            if (!SLOTS.contains(slot)) throw new IllegalArgumentException("slot must be everyday, work or leisure");
        }
        String fileName() { return key + "-" + slot + "-v1.png"; }
    }

    /** Reads skins.yml (kept beside the PNGs, never in residents.yml) and draws each entry. Refuses to overwrite. */
    public static List<Path> author(Path skinsYml, Path outDir) throws IOException {
        List<Request> requests = readLooks(skinsYml);
        Files.createDirectories(outDir);
        for (Request request : requests)
            if (Files.exists(outDir.resolve(request.fileName()), LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException(request.fileName() + " already exists. Nothing was written.");
        List<Path> written = new ArrayList<>();
        for (Request request : requests) {
            Path out = outDir.resolve(request.fileName());
            writeNew(draw(request.look()), out);
            written.add(out);
        }
        return written;
    }

    static List<Request> readLooks(Path skinsYml) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(64 * 1024);
        Object root;
        try (InputStream in = Files.newInputStream(skinsYml, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024) throw new IllegalArgumentException("skins.yml is over 64 KiB");
            root = new Yaml(new SafeConstructor(options)).load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        }
        if (!(root instanceof Map<?, ?> top) || !Integer.valueOf(1).equals(top.get("version")) || !(top.get("skins") instanceof List<?> list))
            throw new IllegalArgumentException("skins.yml needs version: 1 and a skins: list");
        List<Request> requests = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> entry) || !(entry.get("look") instanceof Map<?, ?> look))
                throw new IllegalArgumentException("each skin needs key, slot and look");
            Request request = new Request(text(entry.get("key")), text(entry.get("slot")), new Look(colour(look.get("skin")),
                    colour(look.get("hair")), colour(look.get("eyes")), colour(look.get("top")), colour(look.get("trim")),
                    colour(look.get("legs")), colour(look.get("shoes")), look.get("style") == null ? "plain" : text(look.get("style"))));
            if (!seen.add(request.fileName())) throw new IllegalArgumentException("duplicate skin " + request.fileName());
            requests.add(request);
        }
        return requests;
    }

    /** A classic (wide-arm) 64x64 skin. The second (overlay) layer is left clear. */
    static BufferedImage draw(Look look) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        // Head: top, bottom, right, front, left, back.
        fill(image, 8, 0, 8, 8, look.hair());
        fill(image, 16, 0, 8, 8, look.skin());
        fill(image, 0, 8, 32, 8, look.skin());
        fill(image, 0, 8, 32, 2, look.hair());          // hairline on every side
        fill(image, 24, 8, 8, 6, look.hair());           // back of the head
        int white = 0xF2F2F2;
        int mouth = shade(look.skin(), 0.75);
        put(image, 9, 12, white); put(image, 10, 12, look.eyes());
        put(image, 13, 12, look.eyes()); put(image, 14, 12, white);
        put(image, 11, 14, mouth); put(image, 12, 14, mouth);
        // Body: top/bottom 8x4 faces, then the 4-sided band.
        fill(image, 20, 16, 16, 4, look.top());
        fill(image, 16, 20, 24, 12, look.top());
        fill(image, 16, 31, 24, 1, look.trim());         // belt
        switch (look.style()) {
            case "apron" -> fill(image, 21, 22, 6, 9, look.trim());
            case "jacket" -> { fill(image, 23, 20, 2, 12, look.trim()); fill(image, 35, 20, 2, 12, look.trim()); }
            default -> { }
        }
        // Arms: sleeves in the top colour, hands in the skin tone.
        arm(image, 40, 16, look);                        // right arm
        arm(image, 32, 48, look);                        // left arm
        // Legs: trousers with shoes on the last two rows.
        leg(image, 0, 16, look);                         // right leg
        leg(image, 16, 48, look);                        // left leg
        return image;
    }

    private static void arm(BufferedImage image, int x, int y, Look look) {
        fill(image, x + 4, y, 4, 4, look.top());
        fill(image, x + 8, y, 4, 4, look.skin());
        fill(image, x, y + 4, 16, 8, look.top());
        fill(image, x, y + 12, 16, 4, look.skin());
    }

    private static void leg(BufferedImage image, int x, int y, Look look) {
        fill(image, x + 4, y, 4, 4, look.legs());
        fill(image, x + 8, y, 4, 4, look.shoes());
        fill(image, x, y + 4, 16, 10, look.legs());
        fill(image, x, y + 14, 16, 2, look.shoes());
    }

    // ---------------------------------------------------------------- recolor

    /**
     * Changes exact colours on the clothing rows only (y 16 and below: body, arms, legs and their overlays). The head
     * rows are never touched, and a source colour that also appears on the head (the skin tone, hair or eyes) is
     * refused, so hands and faces keep one tone. Output is a new 64x64 RGBA PNG.
     */
    public static void recolor(Path in, Map<Integer, Integer> map, Path out) throws IOException {
        if (map.isEmpty()) throw new IllegalArgumentException("no colours to change");
        if (Files.exists(out, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException(out.getFileName() + " already exists");
        BufferedImage source;
        try (InputStream input = Files.newInputStream(in, LinkOption.NOFOLLOW_LINKS)) { source = ImageIO.read(input); }
        writeNew(recolor(source, map), out);
    }

    static BufferedImage recolor(BufferedImage source, Map<Integer, Integer> map) {
        if (source == null || source.getWidth() != SIZE || source.getHeight() != SIZE || !source.getColorModel().hasAlpha())
            throw new IllegalArgumentException("the source must be a 64x64 RGBA PNG");
        for (int y = 0; y < HEAD_ROWS; y++)
            for (int x = 0; x < SIZE; x++) {
                int argb = source.getRGB(x, y);
                if ((argb >>> 24) != 0 && map.containsKey(argb & 0xFFFFFF))
                    throw new IllegalArgumentException(String.format(Locale.ROOT, "%06X is on the head; pick a clothing-only colour",
                            argb & 0xFFFFFF));
            }
        BufferedImage result = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) {
                int argb = source.getRGB(x, y);
                Integer to = y >= HEAD_ROWS && (argb >>> 24) != 0 ? map.get(argb & 0xFFFFFF) : null;
                result.setRGB(x, y, to == null ? argb : (argb & 0xFF000000) | to);
            }
        return result;
    }

    static Map<Integer, Integer> parseMap(String text) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (String pair : text.split(",")) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2) throw new IllegalArgumentException("use RRGGBB=RRGGBB pairs");
            int from = colour(parts[0].strip());
            if (map.put(from, colour(parts[1].strip())) != null) throw new IllegalArgumentException("a colour is listed twice");
        }
        return map;
    }

    // ---------------------------------------------------------------- prepare

    record Candidate(String key, String value, String signature) { }

    private static void prepareCommand(String[] args, PrintStream out) throws IOException {
        Path output = null, residents = null, current = null;
        List<Path> candidates = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> output = Path.of(args[++i]);
                case "--residents" -> residents = Path.of(args[++i]);
                case "--current" -> current = Path.of(args[++i]);
                default -> candidates.add(Path.of(args[i]));
            }
        }
        if (output == null) throw new IllegalArgumentException("--out is required");
        for (String line : prepare(candidates, current, residents, output, SignedResidentSkin::verify)) out.println(line);
    }

    /**
     * Merges candidate skins and an optional current manifest into a NEW resident-appearance.json. Each entry must be
     * a key in the cast's universe, appear once, verify against the pinned Mojang key and use the classic model.
     * Returns one fingerprint line per skin plus the file's SHA-256.
     */
    public static List<String> prepare(List<Path> candidates, Path current, Path residentsYml, Path output,
                                       BiFunction<String, String, SignedResidentSkin> verifier) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException(output.getFileName() + " already exists");
        ResidentCast cast;
        if (residentsYml == null) cast = ResidentCast.builtIn();
        else try (InputStream in = Files.newInputStream(residentsYml, LinkOption.NOFOLLOW_LINKS)) { cast = ResidentCastFile.read(in); }
        ResidentAppearanceCatalogue universe = ResidentAppearanceCatalogue.forCast(cast);
        List<Candidate> entries = new ArrayList<>();
        if (current != null) entries.addAll(readManifest(current));
        for (Path path : candidates) entries.add(readCandidate(path));
        TreeMap<String, Candidate> merged = new TreeMap<>();
        List<String> report = new ArrayList<>();
        for (Candidate entry : entries) {
            if (!universe.allows(entry.key())) throw new IllegalArgumentException(entry.key() + " is not a skin for this cast");
            if (merged.put(entry.key(), entry) != null) throw new IllegalArgumentException(entry.key() + " is listed twice");
            SignedResidentSkin skin;
            try { skin = verifier.apply(entry.value(), entry.signature()); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException(entry.key() + " does not verify against the pinned key"); }
            if (skin == null) throw new IllegalArgumentException(entry.key() + " does not verify against the pinned key");
            if (!"default".equals(skin.model())) throw new IllegalArgumentException(entry.key() + " uses the slim model; residents need classic");
            report.add(entry.key() + " " + skin.fingerprint());
        }
        if (merged.size() > 114) throw new IllegalArgumentException("more than 114 skins");
        List<Map<String, String>> assets = new ArrayList<>();
        for (Candidate entry : merged.values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("key", entry.key()); row.put("value", entry.value()); row.put("signature", entry.signature());
            assets.add(row);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("assets", assets);
        byte[] bytes = (new Gson().toJson(document) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("the manifest would be over 512 KiB");
        try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            stream.write(bytes);
        }
        report.add("file sha256 " + sha256(bytes));
        return report;
    }

    static Candidate readCandidate(Path path) throws IOException {
        JsonObject object = json(path, MAX_CANDIDATE_BYTES).getAsJsonObject();
        return candidate(object);
    }

    static List<Candidate> readManifest(Path path) throws IOException {
        JsonObject root = json(path, MAX_MANIFEST_BYTES).getAsJsonObject();
        if (!root.keySet().equals(Set.of("version", "assets")) || root.get("version").getAsInt() != 1)
            throw new IllegalArgumentException("the current manifest is not format version 1");
        List<Candidate> list = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("assets")) list.add(candidate(element.getAsJsonObject()));
        return list;
    }

    private static Candidate candidate(JsonObject object) {
        if (!object.keySet().equals(Set.of("key", "value", "signature")))
            throw new IllegalArgumentException("a skin entry needs exactly key, value and signature");
        return new Candidate(object.get("key").getAsString(), object.get("value").getAsString(), object.get("signature").getAsString());
    }

    private static JsonElement json(Path path, int cap) throws IOException {
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(cap + 1);
            if (bytes.length > cap) throw new IllegalArgumentException(path.getFileName() + " is too large");
            JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            reader.setStrictness(Strictness.STRICT);
            return JsonParser.parseReader(reader);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(path.getFileName() + " is not valid JSON");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void writeNew(BufferedImage image, Path out) throws IOException {
        try (OutputStream stream = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (!ImageIO.write(image, "png", stream)) throw new IOException("no PNG writer");
        }
    }

    private static void fill(BufferedImage image, int x, int y, int w, int h, int rgb) {
        for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++) put(image, x + dx, y + dy, rgb);
    }

    private static void put(BufferedImage image, int x, int y, int rgb) { image.setRGB(x, y, 0xFF000000 | rgb); }

    private static int shade(int rgb, double factor) {
        int r = (int) (((rgb >> 16) & 255) * factor), g = (int) (((rgb >> 8) & 255) * factor), b = (int) ((rgb & 255) * factor);
        return (r << 16) | (g << 8) | b;
    }

    static int colour(Object value) {
        String text = text(value);
        if (!COLOUR.matcher(text).matches()) throw new IllegalArgumentException("colours are #RRGGBB");
        return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16);
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("expected text");
        return text;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
