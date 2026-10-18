package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.property.Property;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BillReadAccessTest {
    private final UUID owner = UUID.randomUUID(), world = UUID.randomUUID(), home = UUID.randomUUID();
    private final Object backend = new Object();

    private record Token(UUID worldId, String regionId, UUID ownerId, Object backend) implements OwnershipToken {
        @Override public boolean sameIncarnation(OwnershipToken other) {
            return other instanceof Token token && worldId.equals(token.worldId) && regionId.equals(token.regionId)
                    && ownerId.equals(token.ownerId) && backend == token.backend;
        }
    }

    private Property property(UUID id, UUID world, String region, UUID owner, int epoch, boolean enabled) {
        return new Property(id, world, region, "town", "room", 1, 1000, 1000, owner, epoch, 80, enabled);
    }

    @Test void delayedReadRequiresTheSamePropertyOwnerEpochAndBackendIncarnation() {
        var original = property(home, world, "Home", owner, 3, true);
        var token = new Token(world, "Home", owner, backend);
        assertTrue(PropertyCommand.sameBillOwnership(original, original, token, new Token(world, "Home", owner, backend)));
        for (var changed : List.of(property(home, world, "Home", owner, 4, true),
                property(home, world, "Home", UUID.randomUUID(), 3, true),
                property(UUID.randomUUID(), world, "Home", owner, 3, true),
                property(home, UUID.randomUUID(), "Home", owner, 3, true),
                property(home, world, "home", owner, 3, true), property(home, world, "Home", owner, 3, false)))
            assertFalse(PropertyCommand.sameBillOwnership(original, changed, token, token));
        assertFalse(PropertyCommand.sameBillOwnership(original, original, token, new Token(world, "Home", owner, new Object())));
        assertFalse(PropertyCommand.sameBillOwnership(original, original, new Token(world, "home", owner, backend), token));
    }

    private Player player(boolean connected, boolean dead, boolean permission) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "isOnline" -> connected;
                case "isDead" -> dead;
                case "hasPermission" -> { assertEquals("prosperitynpc.use", args[0]); yield permission; }
                case "getUniqueId" -> owner;
                default -> throw new AssertionError("Unexpected player read: " + method.getName());
            };
        });
    }

    @Test void delayedRepliesRejectLogoutReplacementSessionRevokedPermissionAndDeath() {
        var original = player(true, false, true);
        var replacement = player(true, false, true);
        assertTrue(PropertyCommand.billPlayerCurrent(original, id -> original));
        assertFalse(PropertyCommand.billPlayerCurrent(original, id -> replacement));
        assertFalse(PropertyCommand.billPlayerCurrent(original, id -> null));
        for (var unavailable : List.of(player(false, false, true), player(true, true, true), player(true, false, false)))
            assertFalse(PropertyCommand.billPlayerCurrent(unavailable, id -> unavailable));
    }
}
