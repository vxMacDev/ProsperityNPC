package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.CoreTestSupport.Inbox;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import java.util.Locale;
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

class TenantsCommandTest {
    private static final String RESIDENT = "123e4567-e89b-12d3-a456-426614174000";
    private static final String REFUSAL = "Enter the resident's full UUID and a positive maximum payment.";

    private final Inbox inbox = new Inbox();
    private TenantsCommand command;
    private ProsperityNpcPlugin plugin;

    @BeforeEach
    void build(@TempDir Path data) {
        plugin = CoreTestSupport.plugin(data, new YamlConfiguration(), new ArrayList<>());
        command = new TenantsCommand(plugin);
    }

    private Player player(Predicate<String> allowed) {
        return CoreTestSupport.sender(Player.class, inbox, allowed, new UUID(7, 8), "Ellis");
    }

    private Player resident() {
        return player("prosperitynpc.use"::equals);
    }

    private boolean run(Player player, String... args) {
        return command.onCommand(player, null, "tenants", args);
    }

    @Test
    void aConsoleIsToldOnlyPlayersCanUseIt() {
        var console = CoreTestSupport.sender(ConsoleCommandSender.class, inbox, node -> true, null, "Console");
        assertTrue(command.onCommand(console, null, "tenants", new String[0]));
        assertEquals("Only players can use /tenants.", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void aPlayerWithoutThePermissionIsRefusedBeforeAnyArgumentIsRead() {
        assertTrue(run(player(node -> false), "evict", "garbage"));
        assertEquals("You don't have permission to use this command.", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void onlyTheUsePermissionOpensTheCommand() {
        assertTrue(run(player("prosperitynpc.admin"::equals)));
        assertEquals("You don't have permission to use this command.", inbox.only().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "LIST", "List"})
    void listWithAnyCaseReachesTheOwnerDeskAndSaysWhenItIsMissing(String word) {
        assertTrue(run(resident(), word));
        assertEquals("The owner desk is unavailable.", inbox.only().text());
        assertEquals(NamedTextColor.GRAY, inbox.only().color());
    }

    @Test
    void noArgumentsMeansListAndSaysWhenTheDeskIsMissing() {
        assertTrue(run(resident()));
        assertEquals("The owner desk is unavailable.", inbox.only().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "TEXT", "Text"})
    void listTextWithAnyCaseIsAccepted(String word) {
        assertTrue(run(resident(), "list", word));
        assertEquals("The owner desk is unavailable.", inbox.only().text());
    }

    @Test
    void listWithAnyOtherWordGetsTheListUsage() {
        assertTrue(run(resident(), "list", "x"));
        assertEquals("Usage: /tenants [list [text]]", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void listWithTwoExtraWordsGetsTheListUsage() {
        assertTrue(run(resident(), "list", "a", "b"));
        assertEquals("Usage: /tenants [list [text]]", inbox.only().text());
    }

    @Test
    void listTextFollowedByAnotherWordGetsTheListUsage() {
        assertTrue(run(resident(), "list", "text", "more"));
        assertEquals("Usage: /tenants [list [text]]", inbox.only().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus", "listx", "evic", "evictt", "", "text"})
    void anUnknownSubcommandGetsTheFullUsage(String word) {
        assertTrue(run(resident(), word));
        assertEquals("Usage: /tenants <list [text]|evict <resident-uuid> <maximum-dollars>>", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void evictWithNoFurtherArgumentsShowsTheUsageAndTheHint() {
        assertTrue(run(resident(), "evict"));
        assertEquals(List.of("Usage: /tenants evict <resident-uuid> <maximum-dollars> (cost $10.00)",
                "Use /tenants list text for each resident's exact command."), inbox.texts());
        assertEquals(NamedTextColor.GOLD, inbox.messages().get(0).color());
        assertEquals(NamedTextColor.GRAY, inbox.messages().get(1).color());
    }

    @Test
    void evictWithOnlyAResidentShowsTheUsageAndTheHint() {
        assertTrue(run(resident(), "evict", RESIDENT));
        assertEquals(2, inbox.messages().size());
        assertEquals("Usage: /tenants evict <resident-uuid> <maximum-dollars> (cost $10.00)", inbox.texts().get(0));
    }

    @Test
    void evictWithAFourthArgumentShowsTheUsageAndTheHint() {
        assertTrue(run(resident(), "evict", RESIDENT, "5", "now"));
        assertEquals(2, inbox.messages().size());
        assertEquals("Use /tenants list text for each resident's exact command.", inbox.texts().get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "123e4567e89b12d3a456426614174000", " 123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-42661417400", "123e4567-e89b-12d3-a456-4266141740000", "", "{123e4567-e89b-12d3-a456-426614174000}"})
    void aResidentThatIsNotACanonicalUuidIsRefused(String uuid) {
        assertTrue(run(resident(), "evict", uuid, "5"));
        assertEquals(REFUSAL, inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "abc", "1e3", "2.999", "99999999999999999999", "", "1,000", "$", "$$5", " ", ".5", "5."})
    void aMaximumThatIsNotAPositiveDollarAmountIsRefused(String maximum) {
        assertTrue(run(resident(), "evict", RESIDENT, maximum));
        assertEquals(REFUSAL, inbox.only().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "$5", "0.01", "5.5", "$3.75", "5 ", "92233720368547758.07"})
    void aCanonicalResidentWithAPositiveAmountPassesTheChecksAndStopsAtTheMissingPaymentRuntime(String maximum) {
        assertTrue(run(resident(), "evict", RESIDENT, maximum));
        assertEquals("Property payments are unavailable.", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void anUppercaseResidentUuidIsAccepted() {
        assertTrue(run(resident(), "evict", RESIDENT.toUpperCase(Locale.ROOT), "5"));
        assertEquals("Property payments are unavailable.", inbox.only().text());
    }

    @Test
    void theEvictWordIsMatchedInAnyCase() {
        assertTrue(run(resident(), "EVICT", RESIDENT, "5"));
        assertEquals("Property payments are unavailable.", inbox.only().text());
    }

    @Test
    void aConsoleGetsNoTabChoices() {
        var console = CoreTestSupport.sender(ConsoleCommandSender.class, inbox, node -> true, null, "Console");
        assertEquals(List.of(), command.onTabComplete(console, null, "tenants", new String[]{""}));
    }

    @Test
    void aPlayerWithoutThePermissionGetsNoTabChoices() {
        assertEquals(List.of(), command.onTabComplete(player(node -> false), null, "tenants", new String[]{""}));
    }

    @Test
    void theFirstWordOffersListAndEvict() {
        assertEquals(List.of("list", "evict"), command.onTabComplete(resident(), null, "tenants", new String[]{""}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"e", "E", "ev", "EVICT"})
    void aPrefixOfEvictNarrowsTheFirstWord(String prefix) {
        assertEquals(List.of("evict"), command.onTabComplete(resident(), null, "tenants", new String[]{prefix}));
    }

    @Test
    void aPrefixOfListNarrowsTheFirstWord() {
        assertEquals(List.of("list"), command.onTabComplete(resident(), null, "tenants", new String[]{"L"}));
    }

    @Test
    void aWordThatMatchesNothingOffersNothing() {
        assertEquals(List.of(), command.onTabComplete(resident(), null, "tenants", new String[]{"x"}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "t", "T", "te", "text"})
    void listOffersTextForTheSecondWord(String prefix) {
        assertEquals(List.of("text"), command.onTabComplete(resident(), null, "tenants", new String[]{"list", prefix}));
    }

    @Test
    void listOffersNothingWhenTheSecondWordCannotBecomeText() {
        assertEquals(List.of(), command.onTabComplete(resident(), null, "tenants", new String[]{"list", "x"}));
    }

    @Test
    void evictOffersNothingForItsArguments() {
        assertEquals(List.of(), command.onTabComplete(resident(), null, "tenants", new String[]{"evict", ""}));
        assertEquals(List.of(), command.onTabComplete(resident(), null, "tenants", new String[]{"evict", RESIDENT, ""}));
    }

    @Test
    void aThirdWordOffersNothingEvenAfterList() {
        assertEquals(List.of(), command.onTabComplete(resident(), null, "tenants", new String[]{"list", "text", ""}));
    }
}
