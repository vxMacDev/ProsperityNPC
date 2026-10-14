package com.prosperitysmp.npc.resident;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Source-wiring, config and copy checks for residents.yml and outfits. Not a server or client test. */
public class ResidentCastWiringTest {
    private static final String ROOT = "src/main/java/com/prosperitysmp/npc/";

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(ROOT + path));
    }

    private static String method(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue(start >= 0, signature);
        int depth = 0;
        for (int i = code.indexOf('{', start); i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            else if (code.charAt(i) == '}' && --depth == 0) return code.substring(start, i + 1);
        }
        throw new AssertionError(signature);
    }

    @Test void everyNewKeyShipsOffWithTheDefaultTheCodeUses() throws Exception {
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        assertFalse(config.getBoolean("residents.cast-file-enabled", true));
        assertTrue(config.isBoolean("residents.cast-file-enabled"));
        assertTrue(config.getBoolean("residents.require-skin-for-roster", false));
        assertFalse(config.getBoolean("appearance.outfit-change-while-rendered", true));
        assertTrue(config.isBoolean("appearance.outfit-change-while-rendered"));
        assertEquals(16, config.getInt("appearance.outfit-change-hidden-radius"));
        assertEquals(2, config.getInt("appearance.outfit-changes-per-tick"));
        String plugin = source("ProsperityNpcPlugin.java");
        assertTrue(plugin.contains("getConfig().getBoolean(\"residents.cast-file-enabled\", false)"));
        String housing = source("market/HousingRuntime.java");
        assertTrue(housing.contains("REQUIRE_SKIN_FOR_ROSTER = \"residents.require-skin-for-roster\";"));
        assertTrue(housing.contains("plugin.getConfig().getBoolean(REQUIRE_SKIN_FOR_ROSTER, true)"));
        String render = source("render/RenderManager.java");
        assertTrue(render.contains("getBoolean(\"appearance.outfit-change-while-rendered\", false)"));
        assertTrue(render.contains("\"appearance.outfit-changes-per-tick\", OutfitChangePolicy.DEFAULT_CHANGES_PER_TICK"));
        assertTrue(render.contains("\"appearance.outfit-change-hidden-radius\",\n                            OutfitChangePolicy.DEFAULT_HIDDEN_RADIUS"));
    }

    @Test void skinWorkInProgressIsIgnoredWhereTheToolWritesIt() throws Exception {
        String readme = Files.readString(Path.of("tools/skins/README.md"));
        // The README's commands run from the repo root and write these folders there.
        assertTrue(readme.contains("skins author skins.yml staging/") && readme.contains("candidates/*.json"));
        var ignored = Files.readAllLines(Path.of(".gitignore")).stream().map(String::strip).toList();
        for (String rule : List.of("/staging/", "/candidates/", "receipts/", "*.receipt.json", "*.key"))
            assertTrue(ignored.contains(rule), rule);
    }

    @Test void rosterOneAsksTheSkinCheckAndPassesTheAnswerOn() throws Exception {
        String admin = source("command/NpcAdminCommand.java");
        int start = admin.indexOf("case \"roster-one\" ->");
        String rosterOne = admin.substring(start, admin.indexOf("case \"rendertest\"", start));
        assertTrue(rosterOne.contains("boolean held = com.prosperitysmp.npc.market.HousingRuntime.heldForSkin(resident, plugin.residentCast(),"));
        assertTrue(rosterOne.contains("HousingRuntime.REQUIRE_SKIN_FOR_ROSTER, true)"));
        assertTrue(rosterOne.contains("registerResident(resident, policy, held,"));
        String housing = source("market/HousingRuntime.java");
        String register = method(housing, "public boolean registerResident(UUID residentId, HousingPolicy policy, boolean heldForSkin,");
        assertTrue(register.contains("rosterOne(database, residentId, policy, available, heldForSkin)"));
    }

    @Test void theSkinCheckIsNeverReadWhileTheCastFileIsOff() throws Exception {
        String housing = source("market/HousingRuntime.java");
        String register = method(housing, "public boolean registerCast(");
        assertTrue(register.contains("boolean requireSkin = skinRequired(cast,"));
        assertTrue(register.contains("() -> plugin.getConfig().getBoolean(REQUIRE_SKIN_FOR_ROSTER, true));"),
                "read lazily, through the supplier");
        assertTrue(register.contains("requireSkin ? hasEverydaySkin : person -> true"));
        String check = method(housing, "static boolean skinRequired(");
        int builtIn = check.indexOf("cast.source() != com.prosperitysmp.npc.resident.ResidentCast.Source.BUILT_IN");
        int read = check.indexOf("&& requireSkinForRoster.getAsBoolean()");
        assertTrue(builtIn >= 0 && read > builtIn, "short-circuit: the flag is only read for a file cast");
    }

    @Test void outfitSwapsAreGatedAndFailSafeWithoutAController() throws Exception {
        String render = source("render/RenderManager.java");
        String advance = method(render, "private void advance(long time)");
        int ready = advance.indexOf("if (!enabledLastTick || time < nextRenderMs) return;");
        int gate = advance.indexOf("if (plugin.getConfig().getBoolean(\"appearance.outfit-change-while-rendered\", false)) {");
        int call = advance.indexOf("changeOutfits(rendered, outfitHost()");
        assertTrue(ready >= 0 && gate > ready && call > gate, "behind the flag and the normal render gates");
        assertEquals(1, render.split("changeOutfits\\(rendered", -1).length - 1);
        String probe = method(render, "@Override public boolean conversationOpen(UUID resident)");
        assertTrue(probe.contains("return probe == null || probe.test(resident);"), "no controller means no swap");
        String controller = source("interaction/ResidentEncounterController.java");
        assertTrue(controller.contains("renderer.setConversationProbe(sessions::hasOpenFor);"));
        assertTrue(method(controller, "@Override public void close()").contains("renderer.setConversationProbe(null);"));
    }

    @Test void theCastIsLoadedBeforeTheSkinUniverseAndEverythingThatReadsIt() throws Exception {
        String plugin = source("ProsperityNpcPlugin.java");
        String enable = method(plugin, "public void onEnable()");
        assertTrue(enable.indexOf("initResidentCast();") < enable.indexOf("initRender();"));
        assertTrue(enable.indexOf("initResidentCast();") < enable.indexOf("initDatabase();"));
        String init = method(plugin, "private void initResidentCast()");
        assertTrue(init.indexOf("ResidentCastFile.load(") < init.indexOf("appearances = ResidentAppearanceCatalogue.forCast(residentCast);"));
        assertFalse(init.contains("residentCast.profiles().get") || init.contains(".lines("), "only the source and count are logged");
        assertTrue(plugin.contains("new CitizensAdapter(appearances)"));
        assertTrue(plugin.contains("new com.prosperitysmp.npc.town.TownTaskRepository(database), residentCast)"));
        assertTrue(plugin.contains("new ResidentRepository(database), renderManager, residentCast)"));
        for (String file : List.of("interaction/ResidentEncounterController.java", "command/NpcTownCommand.java",
                "command/ResidentLabCommand.java", "market/HousingRuntime.java", "town/TownTaskCatalogue.java")) {
            String code = source(file);
            assertFalse(code.contains("ResidentCatalogue.betaCast()"), file + " uses the active cast");
            assertFalse(code.contains("\"mara\", \"ellis\", \"nico\""), file + " has no hard-coded names");
        }
    }

    private static final Pattern NOT_NOT = Pattern.compile("\\bnot [^.]*, not\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> HYPE = List.of("amazing", "awesome", "incredible", "exciting", "epic", "ultimate", "seamless");

    public static List<String> violations(String text) {
        List<String> found = new ArrayList<>();
        if (text.contains("\u2014") || text.contains("\u2013")) found.add("dash");
        if (text.codePoints().anyMatch(c -> c >= 0x1F000 || (c >= 0x2600 && c <= 0x27BF))) found.add("emoji");
        if (text.contains("!")) found.add("exclamation");
        if (NOT_NOT.matcher(text).find()) found.add("not-x-not-y");
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : HYPE) if (lower.contains(word)) found.add(word);
        return found;
    }

    @Test void theCopyLinterCatchesWhatItShould() {
        assertEquals(List.of("dash"), violations("Homes \u2014 rent"));
        assertEquals(List.of("emoji"), violations("Hello \uD83D\uDE00"));
        assertEquals(List.of("exclamation"), violations("Hello!"));
        assertEquals(List.of("not-x-not-y"), violations("Not cash, not items, but trust."));
        assertEquals(List.of("amazing"), violations("An amazing town."));
        assertTrue(violations("Otto Marsh: everyday ready, work ready, leisure missing (wears everyday).").isEmpty());
    }

    @Test void newPlayerAndStaffCopyIsPlain() throws Exception {
        List<String> texts = new ArrayList<>();
        texts.add(Files.readString(Path.of("examples/residents.extended.yml")));
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        texts.add(config.substring(config.indexOf("# Your own named residents."), config.indexOf("outfit-changes-per-tick:")));
        texts.add(ResidentCast.builtIn().healthLine());
        texts.add(ResidentCast.rejected().healthLine());
        texts.add(com.prosperitysmp.npc.command.CopySamples.all());
        for (String text : texts) assertEquals(List.of(), violations(text), text);
    }
}
