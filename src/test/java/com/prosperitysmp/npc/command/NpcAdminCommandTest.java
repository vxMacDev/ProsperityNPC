package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.CoreTestSupport.Inbox;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.config.KillSwitches;
import com.prosperitysmp.npc.economy.LinkedAccounts;
import com.prosperitysmp.npc.economy.accounting.InternalSink;
import com.prosperitysmp.npc.economy.accounting.SkriptSink;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcAdminCommandTest {
    private final Inbox inbox = new Inbox();
    private final List<String> log = new ArrayList<>();
    private Path data;
    private YamlConfiguration config;
    private ProsperityNpcPlugin plugin;
    private NpcAdminCommand command;
    private KillSwitches switches;

    @BeforeEach
    void build(@TempDir Path folder) {
        data = folder;
        config = new YamlConfiguration();
        plugin = CoreTestSupport.plugin(data, config, log);
        switches = KillSwitches.load(plugin);
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "killSwitches", switches);
        command = new NpcAdminCommand(plugin);
    }

    private Player staff(Predicate<String> allowed) {
        return CoreTestSupport.sender(Player.class, inbox, allowed, new UUID(1, 1), "Mod");
    }

    private Player admin() {
        return staff("prosperitynpc.admin"::equals);
    }

    private boolean run(Player sender, String... args) {
        return command.onCommand(sender, null, "npcadmin", args);
    }

    private List<String> tab(String... args) {
        return command.onTabComplete(admin(), null, "npcadmin", args);
    }

    @Test
    void theSubcommandListIsExactlyTheDocumentedSeventeen() {
        assertEquals(List.of("health", "reload", "switch", "chronicle", "reconcile-arm", "roster", "roster-one", "match",
                "incidents", "rent", "settle", "rendertest", "appearance", "links", "itemrepair", "diagnose", "bills"),
                NpcAdminCommand.SUBCOMMANDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch", "bills", "settle", "reload", "roster", "bogus", "chronicle"})
    void someoneWithoutAdminPermissionGetsNoReplyAtAll(String word) {
        assertTrue(run(staff(node -> false), word, "economy", "on"));
        assertTrue(inbox.messages().isEmpty());
        assertFalse(switches.isEconomyEnabled());
    }

    @Test
    void thePermissionNeededIsTheAdminNodeNotTheUseNode() {
        assertTrue(run(staff("prosperitynpc.use"::equals), "switch", "economy", "on"));
        assertTrue(inbox.messages().isEmpty());
        assertFalse(switches.isEconomyEnabled());
        assertFalse(new File(data.toFile(), "config.yml").exists());
    }

    @Test
    void anUnknownSubcommandListsEveryValidOne() {
        assertTrue(run(admin(), "bogus"));
        assertEquals("Unknown subcommand. Use /npcadmin <" + String.join("|", NpcAdminCommand.SUBCOMMANDS) + ">.", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void theSubcommandIsMatchedInAnyCase() {
        assertTrue(run(admin(), "SETTLE", "extra"));
        assertEquals("Usage: /npcadmin settle (current day only; no force/backdating)", inbox.only().text());
    }

    @Test
    void settleWithAnyArgumentGetsTheUsageWithoutStartingACycle() {
        assertTrue(run(admin(), "settle", "force"));
        assertEquals("Usage: /npcadmin settle (current day only; no force/backdating)", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void switchWithTooFewArgumentsGetsTheUsage() {
        assertTrue(run(admin(), "switch"));
        assertTrue(run(admin(), "switch", "economy"));
        assertEquals(List.of("Usage: /npcadmin switch <system|economy|incidents|moves|rendering> <on|off>",
                "Usage: /npcadmin switch <system|economy|incidents|moves|rendering> <on|off>"), inbox.texts());
    }

    @Test
    void anUnknownSwitchIsNamedInTheRefusal() {
        assertTrue(run(admin(), "switch", "bogus", "on"));
        assertEquals("Unknown switch 'bogus'.", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"maybe", "1", "0", "", "onn", "of"})
    void aValueThatIsNotOnOrOffChangesNothing(String value) {
        assertTrue(run(admin(), "switch", "economy", value));
        assertEquals("Value must be on/off (or true/false).", inbox.only().text());
        assertFalse(switches.isEconomyEnabled());
        assertFalse(new File(data.toFile(), "config.yml").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"on", "true", "yes", "enable", "enabled", "ON", "True", "YeS", "Enabled"})
    void anOnWordTurnsTheSwitchOnAndSavesIt(String word) {
        assertTrue(run(admin(), "switch", "economy", word));
        assertEquals("Switch economy-enabled = true (persisted).", inbox.only().text());
        assertEquals(NamedTextColor.YELLOW, inbox.only().color());
        assertTrue(switches.isEconomyEnabled());
        assertTrue(YamlConfiguration.loadConfiguration(new File(data.toFile(), "config.yml")).getBoolean("switches.economy-enabled"));
        assertEquals(List.of("INFO: Mod set economy-enabled = true"), log);
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "false", "no", "disable", "disabled", "OFF", "False", "NO", "Disabled"})
    void anOffWordTurnsTheSwitchOffAndSavesIt(String word) {
        config.set("switches.moves-enabled", true);
        switches.reload();
        assertTrue(switches.isMovesEnabled());
        assertTrue(run(admin(), "switch", "moves", word));
        assertEquals("Switch moves-enabled = false (persisted).", inbox.only().text());
        assertFalse(switches.isMovesEnabled());
        var saved = YamlConfiguration.loadConfiguration(new File(data.toFile(), "config.yml"));
        assertTrue(saved.contains("switches.moves-enabled"));
        assertFalse(saved.getBoolean("switches.moves-enabled"));
    }

    @ParameterizedTest
    @CsvSource({"system,system-enabled", "SYSTEM,system-enabled", "incidents-enabled,incidents-enabled", "Rendering,rendering-enabled"})
    void aSwitchCanBeNamedByItsShortNameOrItsKey(String given, String key) {
        assertTrue(run(admin(), "switch", given, "on"));
        assertEquals("Switch " + key + " = true (persisted).", inbox.only().text());
    }

    @Test
    void switchingOneSwitchLeavesTheOthersOff() {
        assertTrue(run(admin(), "switch", "economy", "on"));
        assertTrue(switches.isEconomyEnabled());
        assertFalse(switches.isSystemEnabled());
        assertFalse(switches.isIncidentsEnabled());
        assertFalse(switches.isMovesEnabled());
        assertFalse(switches.isRenderingEnabled());
    }

    @Test
    void extraWordsAfterTheValueAreIgnored() {
        assertTrue(run(admin(), "switch", "economy", "on", "now", "please"));
        assertTrue(switches.isEconomyEnabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"roster", "roster-one"})
    void aRosterCommandWithoutTheHousingRuntimeGetsItsUsage(String word) {
        assertTrue(run(admin(), word));
        String expected = word.equals("roster")
                ? "Usage: /npcadmin roster <world> <market> <class> <max-daily-rent>"
                : "Usage: /npcadmin roster-one <resident-uuid> <world> <market> <class> <max-daily-rent> <standard|premium>";
        assertEquals(expected, inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void rosterWithTheRightArgumentCountStillStopsWithoutTheHousingRuntime() {
        assertTrue(run(admin(), "roster", "world", "market", "class", "5"));
        assertEquals("Usage: /npcadmin roster <world> <market> <class> <max-daily-rent>", inbox.only().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "show", "listt", "list extra", "LIST x y"})
    void itemRepairOnlyKnowsTheListWord(String arguments) {
        List<String> words = new ArrayList<>(List.of("itemrepair"));
        if (!arguments.isEmpty()) words.addAll(List.of(arguments.split(" ")));
        assertTrue(run(admin(), words.toArray(String[]::new)));
        assertEquals("Usage: /npcadmin itemrepair list", inbox.only().text());
        assertEquals(NamedTextColor.GRAY, inbox.only().color());
    }

    @Test
    void renderTestWithoutARendererSaysSo() {
        assertTrue(run(admin(), "rendertest", "world", "r1"));
        assertEquals("Renderer unavailable (Citizens not installed / not started).", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void reconcileWithoutThePropertySubsystemSaysSo() {
        assertTrue(run(admin(), "reconcile-arm"));
        assertEquals("Property subsystem unavailable (no database).", inbox.only().text());
    }

    @Test
    void billsWithoutTheBillingRuntimeSaysSo() {
        assertTrue(run(admin(), "bills"));
        assertEquals("Bills are unavailable (no database or payments runtime).", inbox.only().text());
        assertEquals(NamedTextColor.GRAY, inbox.only().color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus", "statu", "preview extra", "dispatch now"})
    void chronicleWithAnUnknownWordGetsItsUsage(String arguments) {
        List<String> words = new ArrayList<>(List.of("chronicle"));
        words.addAll(List.of(arguments.split(" ")));
        assertTrue(run(admin(), words.toArray(String[]::new)));
        assertEquals("Usage: /npcadmin chronicle <status|preview|bootstrap|dispatch>", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bootstrap", "dispatch", "BOOTSTRAP", "Dispatch"})
    void chronicleProvisioningFromAPlayerIsConsoleOnly(String word) {
        assertTrue(run(admin(), "chronicle", word));
        assertEquals("Chronicle delivery provisioning is console-only.", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void linksWithoutAValidSubcommandGetsItsUsage() {
        assertTrue(run(admin(), "links"));
        assertTrue(run(admin(), "links", "show"));
        assertTrue(run(admin(), "links", "split", "a", "b"));
        assertEquals(3, inbox.messages().size());
        for (String text : inbox.texts()) {
            assertEquals("Usage: /npcadmin links show <player|uuid> | /npcadmin links split <uuidA> <uuidB> <reason>", text);
        }
    }

    @Test
    void linksShowWithoutTheDatabaseSaysSo() {
        assertTrue(run(admin(), "links", "show", "Alex"));
        assertEquals("Linked accounts need the database.", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void linksSplitWhileTheFeatureIsOffNamesTheSetting() {
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "linkedSettings", LinkedAccounts.Settings.OFF);
        assertTrue(run(admin(), "links", "split", "a", "b", "same person"));
        assertEquals("Linked accounts are off. Turn on linked-accounts.enabled first.", inbox.only().text());
    }

    @Test
    void linksSplitWhileOnButWithoutTheDatabaseSaysSo() {
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "linkedSettings", new LinkedAccounts.Settings(true, false, 5));
        assertTrue(run(admin(), "links", "split", "a", "b", "same person"));
        assertEquals("Linked accounts need the database.", inbox.only().text());
    }

    @Test
    void theFirstWordOffersEverySubcommandInOrder() {
        assertEquals(NpcAdminCommand.SUBCOMMANDS, tab(""));
    }

    @Test
    void theFirstWordIsNarrowedByPrefixInAnyCase() {
        assertEquals(List.of("reload", "reconcile-arm", "roster", "roster-one", "rent", "rendertest"), tab("r"));
        assertEquals(List.of("roster", "roster-one"), tab("ROS"));
        assertEquals(List.of("switch", "settle"), tab("s"));
        assertEquals(List.of(), tab("zzz"));
    }

    @Test
    void billsOffersStatusAndPreview() {
        assertEquals(List.of("status", "preview"), tab("bills", ""));
        assertEquals(List.of("preview"), tab("bills", "P"));
        assertEquals(List.of(), tab("bills", "x"));
    }

    @Test
    void chronicleOffersItsFourWords() {
        assertEquals(List.of("status", "preview", "bootstrap", "dispatch"), tab("chronicle", ""));
        assertEquals(List.of("bootstrap"), tab("chronicle", "b"));
    }

    @Test
    void switchOffersItsFiveKeysThenOnAndOff() {
        assertEquals(List.of("system-enabled", "economy-enabled", "incidents-enabled", "moves-enabled", "rendering-enabled"),
                tab("switch", ""));
        assertEquals(List.of("economy-enabled"), tab("switch", "E"));
        assertEquals(List.of("on", "off"), tab("switch", "economy-enabled", ""));
    }

    @Test
    void itemRepairAndLinksOfferTheirSecondWord() {
        assertEquals(List.of("list"), tab("itemrepair", "l"));
        assertEquals(List.of(), tab("itemrepair", "x"));
        assertEquals(List.of("show", "split"), tab("links", ""));
        assertEquals(List.of("split"), tab("links", "sp"));
    }

    @Test
    void wordsBeyondWhatAnySubcommandTakesOfferNothing() {
        assertEquals(List.of(), tab("health", ""));
        assertEquals(List.of(), tab("bills", "preview", ""));
        assertEquals(List.of(), tab("settle", "x"));
        assertEquals(List.of(), tab("switch", "economy-enabled", "on", ""));
        assertEquals(List.of(), tab("roster", "w", "m", "c", ""));
    }

    @Test
    void noArgumentsAtAllOffersNothing() {
        assertEquals(List.of(), tab());
    }

    @Test
    void aConsoleWithAdminPermissionAlsoGetsThroughTheGate() {
        var console = CoreTestSupport.sender(ConsoleCommandSender.class, inbox, node -> true, null, "CONSOLE");
        assertTrue(command.onCommand(console, null, "npcadmin", new String[]{"settle", "x"}));
        assertEquals("Usage: /npcadmin settle (current day only; no force/backdating)", inbox.only().text());
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "-100"})
    void noSkippedSkinsMeansNoHealthLine(int skipped) {
        assertNull(NpcAdminCommand.skippedSkinsLine(skipped));
    }

    @Test
    void oneSkippedSkinIsSingularAndMoreArePlural() {
        assertEquals("Skins: 1 entry for a resident not in the cast was ignored.", NpcAdminCommand.skippedSkinsLine(1));
        assertEquals("Skins: 2 entries for residents not in the cast were ignored.", NpcAdminCommand.skippedSkinsLine(2));
        assertEquals("Skins: 32 entries for residents not in the cast were ignored.", NpcAdminCommand.skippedSkinsLine(32));
    }

    @Test
    void aBillPreviewLineStatesRawAndMaximumCentsAndTheMeter() {
        assertEquals("  water: raw 450 cents, max 1000, meter water-use {\"l\":3}",
                NpcAdminCommand.billPreviewLine("water", 450, 1000, "water-use", "{\"l\":3}", false));
    }

    @Test
    void aCumulativeMeterPreviewSaysItShowsNoBaseline() {
        assertEquals("  power: raw 9 cents, max 50, meter kwh {} running total (preview shows no baseline)",
                NpcAdminCommand.billPreviewLine("power", 9, 50, "kwh", "{}", true));
    }

    @Test
    void thePropertiesLineReportsAvailabilityAgeAndMissingRegions() {
        assertEquals("Properties: 12 enrolled; ARM available; last reconcile 5s ago",
                NpcAdminCommand.propertiesLine(12, "ARM", true, "5s ago", 0, 0));
        assertEquals("Properties: 0 enrolled; ARM UNAVAILABLE; last reconcile never",
                NpcAdminCommand.propertiesLine(0, "ARM", false, "never", 0, 0));
        assertEquals("Properties: 3 enrolled; Other available; last reconcile 9s ago; 2 region(s) MISSING (map change?)",
                NpcAdminCommand.propertiesLine(3, "Other", true, "9s ago", 2, 0));
        assertEquals("Properties: 3 enrolled; ARM available; last reconcile 9s ago; 4 waiting on a busy home",
                NpcAdminCommand.propertiesLine(3, "ARM", true, "9s ago", 0, 4));
        assertEquals("Properties: 3 enrolled; ARM available; last reconcile 9s ago; 1 region(s) MISSING (map change?); 1 waiting on a busy home",
                NpcAdminCommand.propertiesLine(3, "ARM", true, "9s ago", 1, 1));
    }

    @Test
    void theBridgeLineHasThreeStates() {
        assertEquals("Skript bridge: not initialized (it publishes accounting totals; it never moves money)",
                NpcAdminCommand.bridgeLine(false, false));
        assertEquals("Skript bridge: not initialized (it publishes accounting totals; it never moves money)",
                NpcAdminCommand.bridgeLine(false, true));
        assertEquals("Skript bridge: no heartbeat (it publishes accounting totals; it never moves money)",
                NpcAdminCommand.bridgeLine(true, false));
        assertEquals("Skript bridge: heartbeat present (it publishes accounting totals; it never moves money)",
                NpcAdminCommand.bridgeLine(true, true));
    }

    @Test
    void theAccountingLineFollowsTheSelectedSink() {
        assertEquals(SkriptSink.READY, NpcAdminCommand.accountingLine(new SkriptSink(() -> true), "skript", true));
        assertEquals(SkriptSink.WAITING, NpcAdminCommand.accountingLine(new SkriptSink(() -> true), "skript", false));
        assertEquals(InternalSink.READY, NpcAdminCommand.accountingLine(new InternalSink(), "internal", true));
        assertEquals(InternalSink.WAITING, NpcAdminCommand.accountingLine(new InternalSink(), "internal", false));
    }

    @Test
    void withoutASinkAnEmptyIdReadsAsTheDefaultSkriptSink() {
        assertEquals(SkriptSink.WAITING, NpcAdminCommand.accountingLine(null, "", false));
        assertEquals(SkriptSink.READY, NpcAdminCommand.accountingLine(null, null, true));
    }

    @Test
    void withoutASinkAnUnknownIdIsNamedAndPaymentsAreSaidToBeHeld() {
        assertEquals("Accounting projection: accounting.sink 'ledgerx' is unknown; use skript or internal (payments held)",
                NpcAdminCommand.accountingLine(null, "ledgerx", true));
    }
}
