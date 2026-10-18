package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.CoreTestSupport.Inbox;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue;
import com.prosperitysmp.npc.resident.ResidentCast;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
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

class ResidentLabCommandTest {
    private static final String UNAVAILABLE = "Resident lab is unavailable.";
    private static final List<String> TOPICS = List.of("greeting", "routine", "home", "business", "commerce", "history",
            "about", "tips", "contracts");

    private final Inbox inbox = new Inbox();
    private YamlConfiguration config;
    private ResidentLabCommand command;

    @BeforeEach
    void build(@TempDir Path data) {
        config = CoreTestSupport.yaml("beta:\n  resident-lab-enabled: true\n");
        ProsperityNpcPlugin plugin = CoreTestSupport.plugin(data, config, new ArrayList<>());
        command = new ResidentLabCommand(plugin, null, ResidentCast.builtIn(), new ResidentAppearanceCatalogue());
    }

    @AfterEach
    void stop() {
        command.close();
    }

    private ConsoleCommandSender console(Predicate<String> allowed) {
        return CoreTestSupport.sender(ConsoleCommandSender.class, inbox, allowed, null, "CONSOLE");
    }

    private ConsoleCommandSender adminConsole() {
        return console("prosperitynpc.admin"::equals);
    }

    private Player adminPlayer() {
        return CoreTestSupport.sender(Player.class, inbox, "prosperitynpc.admin"::equals, new UUID(3, 3), "Staff");
    }

    private void run(CommandSender sender, String... args) {
        assertTrue(command.onCommand(sender, null, "residentlab", args));
    }

    @Test
    void theTopicNamesAreTheDialogueTopicsInOrderInLowerCase() {
        assertEquals(TOPICS, ResidentLabCommand.topicNames());
    }

    @Test
    void theUsageLineListsEveryTopicAfterTalk() {
        assertEquals("/residentlab list | seed | profile <name> | talk <name> [greeting|routine|home|business|commerce|history|about|tips|contracts]",
                ResidentLabCommand.usageLine());
    }

    @Test
    void withTheFlagOffEveryoneIsToldItIsUnavailable() {
        config.set("beta.resident-lab-enabled", false);
        run(adminConsole(), "list");
        assertEquals(UNAVAILABLE, inbox.only().text());
        assertEquals(NamedTextColor.GRAY, inbox.only().color());
    }

    @Test
    void withTheFlagMissingTheLabIsUnavailable() {
        config.set("beta", null);
        run(adminConsole(), "list");
        assertEquals(UNAVAILABLE, inbox.only().text());
    }

    @Test
    void withoutTheAdminPermissionTheLabIsUnavailable() {
        run(console(node -> false), "list");
        assertEquals(UNAVAILABLE, inbox.only().text());
    }

    @Test
    void theUsePermissionIsNotEnoughForTheLab() {
        run(console("prosperitynpc.use"::equals), "seed");
        assertEquals(UNAVAILABLE, inbox.only().text());
    }

    @Test
    void aClosedLabIsUnavailableEvenToAdmins() {
        command.close();
        run(adminConsole(), "list");
        assertEquals(UNAVAILABLE, inbox.only().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus", "lists", "SEEDS", "chat", "help"})
    void anUnknownSubcommandGetsTheUsageLine(String word) {
        run(adminConsole(), word);
        assertEquals(ResidentLabCommand.usageLine(), inbox.only().text());
        assertEquals(NamedTextColor.GRAY, inbox.only().color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"talk", "profile", "TALK", "Profile"})
    void talkAndProfileNeedAResidentName(String word) {
        run(adminConsole(), word);
        assertEquals(ResidentLabCommand.usageLine(), inbox.only().text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"talk", "profile"})
    void aNameNobodyHasIsNamedAsUnknown(String word) {
        run(adminConsole(), word, "zed");
        assertEquals("Unknown resident. Use /residentlab list.", inbox.only().text());
        assertEquals(NamedTextColor.GRAY, inbox.only().color());
    }

    @Test
    void aLastNameDoesNotFindAResident() {
        run(adminConsole(), "profile", "vale");
        assertEquals("Unknown resident. Use /residentlab list.", inbox.only().text());
    }

    @Test
    void talkingNeedsAPlayerBecauseTheConsoleHasNoIdentity() {
        run(adminConsole(), "talk", "mara");
        assertEquals("Talk uses your own player identity. Console can inspect profiles.", inbox.only().text());
    }

    @Test
    void aResidentIsFoundByFirstNameInAnyCaseBeforeTheTopicIsChecked() {
        run(adminPlayer(), "talk", "MARA", "nonsense");
        assertEquals(ResidentLabCommand.usageLine(), inbox.only().text());
    }

    @Test
    void aResidentIsFoundByUuidBeforeTheTopicIsChecked() {
        UUID mara = ResidentCast.builtIn().profiles().get(0).id();
        run(adminPlayer(), "talk", mara.toString().toUpperCase(Locale.ROOT), "nonsense");
        assertEquals(ResidentLabCommand.usageLine(), inbox.only().text());
    }

    @Test
    void anUnknownResidentIsReportedBeforeTheTopicIsChecked() {
        run(adminPlayer(), "talk", "zed", "nonsense");
        assertEquals("Unknown resident. Use /residentlab list.", inbox.only().text());
    }

    @Test
    void aBadClockZoneIsReportedAndDoesNotLeaveTheRequestPending() {
        config.set("clock.timezone", "Not/AZone");
        run(adminConsole(), "list");
        run(adminConsole(), "list");
        assertEquals(List.of("Resident clock configuration needs staff attention.", "Resident clock configuration needs staff attention."),
                inbox.texts());
        assertEquals(NamedTextColor.RED, inbox.messages().get(0).color());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 24, 99})
    void aBadResetHourIsReportedAsAClockProblem(int hour) {
        config.set("clock.daily-reset-hour", hour);
        run(adminConsole(), "seed");
        assertEquals("Resident clock configuration needs staff attention.", inbox.only().text());
    }

    @Test
    void tabCompleteIsEmptyWithTheFlagOffOrWithoutAdmin() {
        assertEquals(List.of(), command.onTabComplete(console(node -> false), null, "residentlab", new String[]{""}));
        config.set("beta.resident-lab-enabled", false);
        assertEquals(List.of(), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{""}));
    }

    @Test
    void theFirstWordOffersTheFourSubcommands() {
        assertEquals(List.of("list", "seed", "profile", "talk"), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{""}));
        assertEquals(List.of("profile"), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"PRO"}));
        assertEquals(List.of(), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"x"}));
    }

    @Test
    void profileAndTalkOfferTheCastFirstNames() {
        assertEquals(List.of("mara", "ellis", "nico"), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"talk", ""}));
        assertEquals(List.of("ellis"), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"PROFILE", "E"}));
        assertEquals(List.of(), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"list", ""}));
    }

    @Test
    void talkOffersEveryTopicAsTheThirdWord() {
        assertEquals(TOPICS, command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"talk", "mara", ""}));
        assertEquals(List.of("home", "history"), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"talk", "mara", "h"}));
        assertEquals(List.of(), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"profile", "mara", ""}));
    }

    @Test
    void aFourthWordOffersNothing() {
        assertEquals(List.of(), command.onTabComplete(adminConsole(), null, "residentlab", new String[]{"talk", "mara", "home", ""}));
    }
}
