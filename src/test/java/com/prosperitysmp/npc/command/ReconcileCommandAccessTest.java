package com.prosperitysmp.npc.command;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReconcileCommandAccessTest {
    private final UUID id = UUID.randomUUID();
    private boolean permitted = true, connected = true;
    private <T extends CommandSender> T sender(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "hasPermission" -> {
                    assertEquals("prosperitynpc.admin", args[0]);
                    yield permitted;
                }
                case "isOnline" -> connected;
                case "getUniqueId" -> id;
                case "toString" -> type.getSimpleName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("Unexpected command-gate call: " + method);
            };
        }));
    }
    @Test void rconCanStartButNeverReceivesDelayedReply() {
        var sender = sender(RemoteConsoleCommandSender.class);
        assertTrue(NpcPropertyCommand.canStart(sender, ignored -> { throw new AssertionError(); }));
        assertFalse(NpcPropertyCommand.canReceive(sender, ignored -> { throw new AssertionError(); }));
    }
    @Test void rconStillRequiresPermission() {
        permitted = false;
        assertFalse(NpcPropertyCommand.canStart(sender(RemoteConsoleCommandSender.class), ignored -> null));
    }
    @Test void localConsoleMayStartAndReceive() {
        var sender = sender(ConsoleCommandSender.class);
        assertTrue(NpcPropertyCommand.canStart(sender, ignored -> null));
        assertTrue(NpcPropertyCommand.canReceive(sender, ignored -> null));
        permitted = false;
        assertFalse(NpcPropertyCommand.canReceive(sender, ignored -> null));
    }
    @Test void exactPlayerSessionCanReceiveUntilPermissionRevoked() {
        var sender = sender(Player.class);
        assertTrue(NpcPropertyCommand.canStart(sender, uuid -> { assertEquals(id, uuid); return sender; }));
        permitted = false;
        assertFalse(NpcPropertyCommand.canReceive(sender, ignored -> sender));
    }
    @Test void offlinePlayerIsRejected() {
        var sender = sender(Player.class);
        connected = false;
        assertFalse(NpcPropertyCommand.canReceive(sender, ignored -> sender));
    }
    @Test void replacementSessionWithSameUuidIsRejected() {
        var original = sender(Player.class);
        var replacement = sender(Player.class);
        assertEquals(original.getUniqueId(), replacement.getUniqueId());
        assertFalse(NpcPropertyCommand.canReceive(original, ignored -> replacement));
    }
    @Test void disconnectedLookupIsRejected() {
        assertFalse(NpcPropertyCommand.canReceive(sender(Player.class), ignored -> null));
    }
    @Test void commandBlockDoesNotGainAdministrativeEntry() {
        assertFalse(NpcPropertyCommand.canStart(sender(BlockCommandSender.class), ignored -> null));
    }
}
