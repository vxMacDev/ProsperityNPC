package com.prosperitysmp.npc.tools.skins;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.prosperitysmp.npc.appearance.SignedResidentSkin;
import com.prosperitysmp.npc.appearance.TestSkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class SkinToolTest {
    private static final String LOOKS = """
            version: 1
            skins:
              - key: otto-marsh
                slot: everyday
                look: {skin: "#C68642", hair: "#3B2A1A", eyes: "#2B4A6F", top: "#7A2E2E", trim: "#D9C9A3", legs: "#3A3A46", shoes: "#2A1F17", style: apron}
            """;

    private static BufferedImage authored(Path dir) throws Exception {
        Path looks = dir.resolve("skins.yml");
        Files.writeString(looks, LOOKS, StandardCharsets.UTF_8);
        List<Path> written = SkinTool.author(looks, dir.resolve("staging"));
        assertEquals(List.of(dir.resolve("staging/otto-marsh-everyday-v1.png")), written);
        return ImageIO.read(written.getFirst().toFile());
    }

    @Test void authorDrawsA64RgbaClassicSkinAndNeverOverwrites(@TempDir Path dir) throws Exception {
        BufferedImage image = authored(dir);
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
        assertEquals(0xFFC68642, image.getRGB(10, 10), "face");
        assertEquals(0xFF7A2E2E, image.getRGB(18, 25), "shirt side");
        assertEquals(0, image.getRGB(40, 0) >>> 24, "overlay layer stays clear");
        Path looks = dir.resolve("skins.yml");
        var error = assertThrows(IllegalArgumentException.class, () -> SkinTool.author(looks, dir.resolve("staging")));
        assertTrue(error.getMessage().contains("already exists"));
    }

    @Test void recolorChangesClothingOnlyAndNeverTheHead(@TempDir Path dir) throws Exception {
        BufferedImage source = authored(dir);
        BufferedImage work = SkinTool.recolor(source, SkinTool.parseMap("7A2E2E=2E4A7A,D9C9A3=EEEEEE"));
        assertEquals(64, work.getWidth());
        assertTrue(work.getColorModel().hasAlpha());
        int changed = 0;
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) {
                if (y < 16) assertEquals(source.getRGB(x, y), work.getRGB(x, y), "head pixel " + x + "," + y);
                else if (source.getRGB(x, y) != work.getRGB(x, y)) changed++;
            }
        assertTrue(changed > 100, "the shirt and apron changed");
        assertEquals(0xFF2E4A7A, work.getRGB(18, 25));
        assertEquals(0xFFC68642, work.getRGB(40, 30), "hands keep the skin tone");
    }

    @Test void recolorRefusesAColourThatIsAlsoOnTheHead(@TempDir Path dir) throws Exception {
        BufferedImage source = authored(dir);
        var error = assertThrows(IllegalArgumentException.class, () -> SkinTool.recolor(source, SkinTool.parseMap("C68642=FFFFFF")));
        assertTrue(error.getMessage().contains("C68642"));
        assertThrows(IllegalArgumentException.class, () -> SkinTool.recolor(source, SkinTool.parseMap("3B2A1A=FFFFFF")), "hair");
        assertThrows(IllegalArgumentException.class, () -> SkinTool.recolor(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB),
                SkinTool.parseMap("7A2E2E=2E4A7A")), "64x32 legacy skins are refused");
        assertThrows(IllegalArgumentException.class, () -> SkinTool.recolor(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB),
                SkinTool.parseMap("7A2E2E=2E4A7A")), "no alpha");
        Path in = dir.resolve("staging/otto-marsh-everyday-v1.png");
        Path out = dir.resolve("staging/otto-marsh-work-v1.png");
        SkinTool.recolor(in, SkinTool.parseMap("7A2E2E=2E4A7A"), out);
        assertThrows(IllegalArgumentException.class, () -> SkinTool.recolor(in, SkinTool.parseMap("7A2E2E=2E4A7A"), out),
                "never overwrites");
    }

    private static final Gson GSON = new Gson();

    private static Path candidate(Path dir, String key, SignedResidentSkin skin) throws Exception {
        Path path = dir.resolve(key.replace('/', '_') + ".json");
        Files.writeString(path, GSON.toJson(Map.of("key", key, "value", skin.value(), "signature", skin.signature())));
        return path;
    }

    private static BiFunction<String, String, SignedResidentSkin> verifier(SignedResidentSkin... known) {
        Map<String, SignedResidentSkin> byValue = new HashMap<>();
        for (SignedResidentSkin skin : known) byValue.put(skin.value(), skin);
        return (value, signature) -> {
            SignedResidentSkin skin = byValue.get(value);
            if (skin == null || !skin.signature().equals(signature)) throw new IllegalArgumentException("bad signature");
            return skin;
        };
    }

    @Test void prepareWritesANewManifestWithFingerprints(@TempDir Path dir) throws Exception {
        var classic = TestSkins.synthetic('d');
        var other = TestSkins.synthetic('e');
        Path out = dir.resolve("resident-appearance.json");
        var report = SkinTool.prepare(List.of(candidate(dir, "resident/v1/mara-vale/everyday", classic),
                candidate(dir, "resident/v1/mara-vale/work", other)), null, null, out, verifier(classic, other));
        assertEquals("resident/v1/mara-vale/everyday " + classic.fingerprint(), report.get(0));
        assertTrue(report.getLast().startsWith("file sha256 "));
        var json = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
        assertEquals(1, json.get("version").getAsInt());
        assertEquals(2, json.getAsJsonArray("assets").size());
        var error = assertThrows(IllegalArgumentException.class, () -> SkinTool.prepare(List.of(), out, null, out, verifier(classic, other)));
        assertTrue(error.getMessage().contains("already exists"), "CREATE_NEW only");
        Path merged = dir.resolve("merged.json");
        var third = TestSkins.synthetic('f');
        SkinTool.prepare(List.of(candidate(dir, "resident/v1/nico-reed/everyday", third)), out, null, merged,
                verifier(classic, other, third));
        assertEquals(3, JsonParser.parseString(Files.readString(merged)).getAsJsonObject().getAsJsonArray("assets").size());
    }

    @Test void prepareRefusesSlimDuplicateForeignOrUnverifiedSkins(@TempDir Path dir) throws Exception {
        var classic = TestSkins.synthetic('d');
        var slim = TestSkins.synthetic('9', true);
        Path out = dir.resolve("new.json");
        var slimError = assertThrows(IllegalArgumentException.class, () -> SkinTool.prepare(
                List.of(candidate(dir, "resident/v1/mara-vale/everyday", slim)), null, null, out, verifier(slim)));
        assertTrue(slimError.getMessage().contains("slim"));
        Path a = candidate(dir, "resident/v1/mara-vale/everyday", classic);
        Path copy = dir.resolve("copy.json");
        Files.copy(a, copy);
        var duplicate = assertThrows(IllegalArgumentException.class, () -> SkinTool.prepare(List.of(a, copy), null, null, out, verifier(classic)));
        assertTrue(duplicate.getMessage().contains("twice"));
        assertThrows(IllegalArgumentException.class, () -> SkinTool.prepare(
                List.of(candidate(dir, "resident/v1/otto-marsh/everyday", classic)), null, null, out, verifier(classic)),
                "Otto is not in the built-in cast");
        Path residents = dir.resolve("residents.yml");
        Files.copy(Path.of("examples/residents.extended.yml"), residents);
        SkinTool.prepare(List.of(candidate(dir, "resident/v1/otto-marsh/everyday", classic)), null, residents, out, verifier(classic));
        assertTrue(Files.exists(out), "positive control: Otto is in the extended cast");
        assertThrows(IllegalArgumentException.class, () -> SkinTool.prepare(List.of(a), null, null, dir.resolve("x.json"), verifier()),
                "unverified");
        assertFalse(Files.exists(dir.resolve("x.json")), "nothing is written on refusal");
    }
}
