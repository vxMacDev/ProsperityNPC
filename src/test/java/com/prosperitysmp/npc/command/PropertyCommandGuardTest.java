package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.CoreTestSupport.Inbox;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.economy.ItemRepairSettings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /property branches that are decided before any payment or property service is touched. */
class PropertyCommandGuardTest {
    private static final String BASE_USAGE = "Usage: /property <status [text]|close|pause|resume|autocare|utility|repair|%srent|upgrade|maintain|receipt|rating>";

    private final Inbox inbox = new Inbox();
    private YamlConfiguration config;
    private PropertyCommand command;

    @BeforeEach
    void build(@TempDir Path data) {
        config = new YamlConfiguration();
        ProsperityNpcPlugin plugin = CoreTestSupport.plugin(data, config, new ArrayList<>());
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "itemRepairSettings", ItemRepairSettings.OFF);
        command = new PropertyCommand(plugin);
    }

    private Player player(Predicate<String> allowed) {
        return CoreTestSupport.sender(Player.class, inbox, allowed, new UUID(4, 4), "Alex");
    }

    private Player owner() {
        return player("prosperitynpc.use"::equals);
    }

    private void run(String... args) {
        assertTrue(command.onCommand(owner(), null, "property", args));
    }

    private void expect(NamedTextColor color, String text) {
        assertEquals(text, inbox.only().text());
        assertEquals(color, inbox.only().color());
    }

    private List<String> tab(String... args) {
        return command.onTabComplete(owner(), null, "property", args);
    }

    @Test
    void aConsoleIsToldOnlyPlayersCanUseIt() {
        var console = CoreTestSupport.sender(ConsoleCommandSender.class, inbox, node -> true, null, "CONSOLE");
        assertTrue(command.onCommand(console, null, "property", new String[0]));
        expect(NamedTextColor.RED, "Only players can use /property.");
    }

    @Test
    void aPlayerWithoutThePermissionIsRefused() {
        assertTrue(command.onCommand(player(node -> false), null, "property", new String[]{"status"}));
        expect(NamedTextColor.RED, "You don't have permission to use this command.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus", "statuss", "bill", "insure", "BOGUS"})
    void anUnknownSubcommandGetsTheUsageWithoutTheHiddenOnes(String word) {
        run(word);
        expect(NamedTextColor.RED, String.format(BASE_USAGE, ""));
    }

    @Test
    void insureAppearsInTheUsageWhenTheConfigTurnsItOn() {
        config.set("ux.insure-command", true);
        run("bogus");
        expect(NamedTextColor.RED, String.format(BASE_USAGE, "insure|"));
    }

    @Test
    void noArgumentsMeansStatusAndNeedsTheOwnerDesk() {
        run();
        expect(NamedTextColor.GRAY, "The owner desk is unavailable.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "STATUS", "close", "Close"})
    void statusAndCloseNeedTheOwnerDesk(String word) {
        run(word);
        expect(NamedTextColor.GRAY, "The owner desk is unavailable.");
    }

    @Test
    void statusWithTextStillNeedsTheOwnerDesk() {
        run("status", "text");
        expect(NamedTextColor.GRAY, "The owner desk is unavailable.");
    }

    @Test
    void ratingWithAnExtraWordGetsItsUsage() {
        run("rating", "now");
        expect(NamedTextColor.GRAY, "Usage: /property rating");
    }

    @Test
    void ratingWithoutTheRatingServiceSaysSo() {
        run("rating");
        expect(NamedTextColor.GRAY, "Property ratings are unavailable.");
    }

    @Test
    void aReceiptWithoutThePurchaseServiceSaysSo() {
        run("receipt", "123e4567-e89b-12d3-a456-426614174000");
        expect(NamedTextColor.RED, "Receipts are unavailable right now.");
    }

    @Test
    void upgradeNeedsFourWordsAndMaintainNeedsTwo() {
        run("upgrade", "advertising", "1");
        run("maintain");
        assertEquals(List.of("Usage: /property upgrade <advertising|facilities> <target-level> <maximum-dollars>",
                "Usage: /property maintain <maximum-dollars> (cost $10.00)"), inbox.texts());
        assertEquals(NamedTextColor.GOLD, inbox.messages().get(0).color());
        assertEquals(NamedTextColor.GOLD, inbox.messages().get(1).color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus 1 5", "advertising 0 5", "advertising 4 5", "advertising -1 5", "advertising one 5",
            "advertising 99999999999 5", "facilities 1 0", "facilities 1 -2", "facilities 1 abc", "facilities 1.5 5", "advertising 1 5.555"})
    void anUpgradeWithABadTypeLevelOrAmountIsRefusedBeforeAnyService(String text) {
        List<String> words = new ArrayList<>(List.of("upgrade"));
        words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        expect(NamedTextColor.RED, "Enter a valid upgrade level and maximum payment.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising 1 5", "FACILITIES 3 $12.50", "Advertising 2 0.01"})
    void aValidUpgradeStopsOnlyAtTheMissingPaymentRuntime(String text) {
        List<String> words = new ArrayList<>(List.of("upgrade"));
        words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        expect(NamedTextColor.RED, "Property payments are unavailable.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1e2", "", "1.234"})
    void aMaintainAmountThatIsNotPositiveDollarsIsRefused(String amount) {
        run("maintain", amount);
        expect(NamedTextColor.RED, "Enter a valid upgrade level and maximum payment.");
    }

    @Test
    void aValidMaintainAmountStopsOnlyAtTheMissingPaymentRuntime() {
        run("maintain", "10");
        expect(NamedTextColor.RED, "Property payments are unavailable.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "pay", "pay 5 extra", "other 5"})
    void utilityNeedsPayAndOneAmount(String text) {
        List<String> words = new ArrayList<>(List.of("utility"));
        if (!text.isEmpty()) words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        assertEquals(List.of("Usage: /property utility pay <maximum-dollars>",
                "Pays today's utility bill only, up to your limit. No recurring authorization."), inbox.texts());
        assertEquals(NamedTextColor.GOLD, inbox.messages().get(0).color());
        assertEquals(NamedTextColor.GRAY, inbox.messages().get(1).color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "abc", "0.00", "5.555"})
    void aUtilityAmountThatIsNotPositiveDollarsIsRefused(String amount) {
        run("utility", "pay", amount);
        expect(NamedTextColor.RED, "Enter a positive maximum payment in dollars.");
    }

    @Test
    void aValidUtilityPaymentStopsOnlyAtTheMissingPaymentRuntime() {
        run("utility", "PAY", "$4.25");
        expect(NamedTextColor.GRAY, "Utility payments are unavailable.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus", "on", "on 0", "on abc", "on -3", "status extra", "off extra", "on 5 extra"})
    void autocareWithAnUnknownFormOrABadCeilingGetsItsUsage(String text) {
        List<String> words = new ArrayList<>(List.of("autocare"));
        words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        expect(NamedTextColor.GOLD, "Usage: /property autocare <status|off|on <maximum-dollars-per-day>>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "status", "off", "STATUS", "on 5", "on $2.50"})
    void autocareWithAValidFormStopsOnlyAtTheMissingRuntime(String text) {
        List<String> words = new ArrayList<>(List.of("autocare"));
        if (!text.isEmpty()) words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        expect(NamedTextColor.GRAY, "Auto-care is unavailable.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "5", "5 now", "abc"})
    void rentWithoutThePropertyServiceGetsItsUsage(String text) {
        List<String> words = new ArrayList<>(List.of("rent"));
        if (!text.isEmpty()) words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        expect(NamedTextColor.GOLD, "Usage: /property rent <dollars-per-day>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "resume", "PAUSE"})
    void pauseAndResumeWithoutThePropertyServiceExplainWhereToStand(String word) {
        run(word);
        expect(NamedTextColor.RED, "Use /property pause or /property resume while inside your property.");
    }

    @Test
    void pauseWithAnExtraWordGetsTheSameExplanation() {
        run("pause", "now");
        expect(NamedTextColor.RED, "Use /property pause or /property resume while inside your property.");
    }

    @Test
    void repairNeedsAnIncidentAndAnAmount() {
        run("repair");
        run("repair", "7");
        run("repair", "7", "5", "extra");
        assertEquals(3, inbox.messages().size());
        for (String text : inbox.texts()) assertEquals("Usage: /property repair <incident-id> <maximum-dollars>", text);
        for (var message : inbox.messages()) assertEquals(NamedTextColor.GOLD, message.color());
    }

    @Test
    void repairReturnsWithItemRepairsOffAndNeverUsedFallsThroughToTheUsage() {
        run("repair", "returns");
        expect(NamedTextColor.GOLD, "Usage: /property repair <incident-id> <maximum-dollars>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc 5", "1.5 5", "99999999999999999999 5", "- 5", "0x10 5"})
    void anIncidentThatIsNotAWholeNumberIsReportedAsMalformed(String text) {
        List<String> words = new ArrayList<>(List.of("repair"));
        words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        expect(NamedTextColor.RED, "Enter a valid incident number and maximum payment.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"7 abc", "7 -1", "7 1.234", "7 1e2"})
    void anAmountThatIsNotPositiveDollarsIsHeldAsUnavailableOrInvalid(String text) {
        List<String> words = new ArrayList<>(List.of("repair"));
        words.addAll(List.of(text.split(" ")));
        run(words.toArray(String[]::new));
        expect(NamedTextColor.RED, "Repair payments are unavailable, or the amount is invalid.");
    }

    @Test
    void anIncidentThatIsNotANumberIsReportedAsMalformed() {
        run("repair", "abc", "5");
        expect(NamedTextColor.RED, "Enter a valid incident number and maximum payment.");
    }

    @Test
    void aZeroOrNegativeIncidentOrAmountIsHeldAsUnavailableOrInvalid() {
        run("repair", "0", "5");
        run("repair", "7", "0");
        run("repair", "-3", "5");
        assertEquals(3, inbox.messages().size());
        for (String text : inbox.texts()) assertEquals("Repair payments are unavailable, or the amount is invalid.", text);
    }

    @Test
    void aValidRepairStopsOnlyAtTheMissingPaymentRuntime() {
        run("repair", "7", "5");
        expect(NamedTextColor.RED, "Repair payments are unavailable, or the amount is invalid.");
    }

    @Test
    void handingInItemsWhileItemRepairsAreOffSaysSo() {
        run("repair", "7", "items");
        expect(NamedTextColor.GRAY, "Item repairs are turned off on this server.");
    }

    @Test
    void insureIsHiddenFromTheCommandUntilTheConfigTurnsItOn() {
        run("insure", "5");
        expect(NamedTextColor.RED, String.format(BASE_USAGE, ""));
    }

    @Test
    void insureNeedsExactlyOneAmountWhenOn() {
        config.set("ux.insure-command", true);
        run("insure");
        assertEquals(List.of("Usage: /property insure <maximum-dollars>",
                "Seven economy days of cover for one new incident. Prepaid, nonrefundable. Your limit must cover the premium."), inbox.texts());
        assertEquals(NamedTextColor.GOLD, inbox.messages().get(0).color());
        assertEquals(NamedTextColor.GRAY, inbox.messages().get(1).color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.999"})
    void anInsureAmountThatIsNotPositiveDollarsIsRefusedWhenOn(String amount) {
        config.set("ux.insure-command", true);
        run("insure", amount);
        expect(NamedTextColor.RED, "Enter a positive maximum payment in dollars.");
    }

    @Test
    void aValidInsureAmountStopsOnlyAtTheMissingPaymentRuntimeWhenOn() {
        config.set("ux.insure-command", true);
        run("insure", "3");
        expect(NamedTextColor.RED, "Property payments are unavailable.");
    }

    @Test
    void billsAreHiddenWithoutABillingRuntime() {
        run("bills");
        expect(NamedTextColor.RED, String.format(BASE_USAGE, ""));
    }

    @Test
    void aConsoleGetsNoTabChoices() {
        var console = CoreTestSupport.sender(ConsoleCommandSender.class, inbox, node -> true, null, "CONSOLE");
        assertEquals(List.of(), command.onTabComplete(console, null, "property", new String[]{""}));
    }

    @Test
    void aPlayerWithoutThePermissionGetsNoTabChoices() {
        assertEquals(List.of(), command.onTabComplete(player(node -> false), null, "property", new String[]{""}));
    }

    @Test
    void theFirstWordOffersEveryVisibleSubcommandInOrder() {
        assertEquals(List.of("status", "close", "pause", "resume", "autocare", "utility", "repair", "rent", "upgrade",
                "maintain", "receipt", "rating"), tab(""));
    }

    @Test
    void insureJoinsTheFirstWordChoicesWhenTheConfigTurnsItOn() {
        config.set("ux.insure-command", true);
        assertEquals(List.of("status", "close", "pause", "resume", "autocare", "utility", "repair", "insure", "rent", "upgrade",
                "maintain", "receipt", "rating"), tab(""));
        assertEquals(List.of("insure"), tab("INS"));
    }

    @Test
    void theFirstWordIsNarrowedByPrefix() {
        assertEquals(List.of("resume", "repair", "rent", "receipt", "rating"), tab("r"));
        assertEquals(List.of("maintain"), tab("Ma"));
        assertEquals(List.of(), tab("zz"));
    }

    @Test
    void autocareOffersStatusOffOn() {
        assertEquals(List.of("status", "off", "on"), tab("autocare", ""));
        assertEquals(List.of("off", "on"), tab("autocare", "o"));
        assertEquals(List.of(), tab("autocare", "x"));
    }

    @Test
    void statusOffersTextAndUtilityOffersPay() {
        assertEquals(List.of("text"), tab("status", ""));
        assertEquals(List.of("text"), tab("status", "TE"));
        assertEquals(List.of(), tab("status", "x"));
        assertEquals(List.of("pay"), tab("utility", "p"));
        assertEquals(List.of(), tab("utility", "x"));
    }

    @Test
    void upgradeOffersBothUpgradeTypes() {
        assertEquals(List.of("advertising", "facilities"), tab("upgrade", ""));
    }

    @Test
    void repairOffersNothingWhileItemRepairsAreOffAndNeverUsed() {
        assertEquals(List.of(), tab("repair", ""));
        assertEquals(List.of(), tab("repair", "7", ""));
    }

    @Test
    void billsOffersNothingWhileBillsAreOff() {
        assertEquals(List.of(), tab("bills", ""));
    }

    @Test
    void laterWordsOfOtherSubcommandsOfferNothing() {
        assertEquals(List.of(), tab("rent", ""));
        assertEquals(List.of(), tab("autocare", "on", ""));
        assertEquals(List.of(), tab());
    }
}
