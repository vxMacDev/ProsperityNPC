package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.CoreTestSupport.Inbox;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.api.PropertyBackend;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcPropertyCommandTest {
    private final Inbox inbox = new Inbox();
    private YamlConfiguration config;
    private ProsperityNpcPlugin plugin;
    private NpcPropertyCommand command;

    @BeforeEach
    void build(@TempDir Path data) {
        config = CoreTestSupport.yaml("""
                enrollment:
                  markets: [downtown, harbor]
                  classes: [cottage, villa]
                """);
        plugin = CoreTestSupport.plugin(data, config, new ArrayList<>());
        command = new NpcPropertyCommand(plugin);
    }

    private ConsoleCommandSender console(Predicate<String> allowed) {
        return CoreTestSupport.sender(ConsoleCommandSender.class, inbox, allowed, null, "CONSOLE");
    }

    private ConsoleCommandSender admin() {
        return console("prosperitynpc.admin"::equals);
    }

    private List<String> tab(String... args) {
        return command.onTabComplete(admin(), null, "npcproperty", args);
    }

    private static PropertyBackend backendNamed(String name) {
        return (PropertyBackend) Proxy.newProxyInstance(PropertyBackend.class.getClassLoader(), new Class<?>[]{PropertyBackend.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("displayName")) return name;
                    throw new AssertionError("Unexpected backend call: " + method);
                });
    }

    @Test
    void withoutABackendStaffTextCallsItArm() {
        assertEquals("ARM", NpcPropertyCommand.backendName(plugin));
    }

    @Test
    void withABackendStaffTextUsesItsDisplayName() {
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "propertyBackend", backendNamed("Example Regions"));
        assertEquals("Example Regions", NpcPropertyCommand.backendName(plugin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"enroll", "list", "info", "reconcile", "armdump", "bogus", ""})
    void someoneWithoutAdminPermissionGetsNoReply(String word) {
        assertTrue(command.onCommand(console(node -> false), null, "npcproperty", new String[]{word}));
        assertTrue(inbox.messages().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"enroll", "list", "info", "reconcile", "armdump", "pause", "resume", "unenroll", "bogus", ""})
    void everySubcommandIsHeldWhileThePropertyServiceIsOff(String word) {
        assertTrue(command.onCommand(admin(), null, "npcproperty", new String[]{word}));
        assertEquals("Property subsystem unavailable (database not connected).", inbox.only().text());
        assertEquals(NamedTextColor.RED, inbox.only().color());
    }

    @Test
    void noArgumentsAtAllIsAlsoHeldWhilePropertyServiceIsOff() {
        assertTrue(command.onCommand(admin(), null, "npcproperty", new String[0]));
        assertEquals("Property subsystem unavailable (database not connected).", inbox.only().text());
    }

    @Test
    void someoneWithoutAdminCannotSeeTheSubcommandNames() {
        assertEquals(List.of(), command.onTabComplete(console(node -> false), null, "npcproperty", new String[]{""}));
    }

    @Test
    void theFirstWordOffersAllEightSubcommandsInOrder() {
        assertEquals(List.of("enroll", "pause", "resume", "unenroll", "list", "info", "reconcile", "armdump"), tab(""));
    }

    @Test
    void theFirstWordIsNarrowedByPrefixInAnyCase() {
        assertEquals(List.of("resume", "reconcile"), tab("re"));
        assertEquals(List.of("unenroll"), tab("UN"));
        assertEquals(List.of(), tab("zzz"));
    }

    @Test
    void enrollOffersTheConfiguredMarketsThenClasses() {
        assertEquals(List.of("downtown", "harbor"), tab("enroll", "w", "r", ""));
        assertEquals(List.of("cottage", "villa"), tab("ENROLL", "w", "r", "m", ""));
    }

    @Test
    void enrollWithNothingConfiguredOffersNothing() {
        config.set("enrollment", null);
        assertEquals(List.of(), tab("enroll", "w", "r", ""));
        assertEquals(List.of(), tab("enroll", "w", "r", "m", ""));
    }

    @Test
    void otherPositionsAndSubcommandsOfferNothing() {
        assertEquals(List.of(), tab("enroll", ""));
        assertEquals(List.of(), tab("enroll", "w", ""));
        assertEquals(List.of(), tab("enroll", "w", "r", "m", "c", ""));
        assertEquals(List.of(), tab("info", "w", "r", ""));
        assertEquals(List.of(), tab("list", ""));
        assertEquals(List.of(), tab());
    }
}
