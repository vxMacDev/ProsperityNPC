package com.prosperitysmp.npc.arm;

import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.property.worldguard.WorldGuardListedBackend;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The parts of the ARM backend that read no server state: identity, capabilities and owner-read identity. */
class ArmAdapterSurfaceTest {
    private static final UUID WORLD = new UUID(0, 31);
    private static final UUID OWNER = new UUID(0, 32);
    private static final World WORLD_OBJECT = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (p, m, a) -> switch (m.getName()) {
                case "hashCode" -> 31;
                case "equals" -> p == a[0];
                case "toString" -> "arm-world";
                default -> throw new UnsupportedOperationException(m.getName());
            });

    private final Object provider = new Object(), manager = new Object(), region = new Object(), raw = new Object(),
            wgManager = new Object();

    private ArmAdapter.OwnerRead read() {
        return new ArmAdapter.OwnerRead(WORLD, "c001", OWNER, provider, manager, region, raw, wgManager, WORLD_OBJECT);
    }

    private ArmAdapter.OwnerRead readReplacing(int index) {
        Object[] parts = {provider, manager, region, raw, wgManager};
        if (index < 5) parts[index] = new Object();
        World world = index == 5 ? (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (p, m, a) -> switch (m.getName()) {
                    case "hashCode" -> 32;
                    case "equals" -> p == a[0];
                    default -> "other-world";
                }) : WORLD_OBJECT;
        return new ArmAdapter.OwnerRead(WORLD, "c001", OWNER, parts[0], parts[1], parts[2], parts[3], parts[4], world);
    }

    @Test void identityNamesTheBuiltInArmBackend() {
        var arm = new ArmAdapter(null);
        assertEquals("arm", arm.id());
        assertEquals(ArmAdapter.ID, arm.id());
        assertEquals("ARM", arm.displayName());
        assertEquals(1, arm.apiMajor());
        assertEquals(2, arm.apiMinor());
        assertEquals("", arm.label());
    }

    @Test void capabilitiesAreExactlyTheArmSet() {
        var expected = new TreeMap<String, String>(Map.of(
                "guard", "write-hook",
                "owner-model", "one UUID owner, no members",
                "location-lookup", "true",
                "region-by-id", "true",
                "distinguishes-missing", "false",
                "token-identity", "object",
                "needs-worldguard", "true",
                "region-id-max-length", "128",
                "main-thread-only", "true"));
        assertEquals(expected, new ArmAdapter(null).capabilities().all());
    }

    @Test void armCannotTellAMissingRegionFromAFailedRead() {
        var caps = new ArmAdapter(null).capabilities();
        assertFalse(caps.flag("distinguishes-missing"));
        assertFalse(caps.flag("sees-owner-round-trip"), "never set, so it reads false");
        assertEquals("", caps.value("guard-bindings"));
    }

    @Test void twoAdaptersShareOneImmutableCapabilitySet() {
        var first = new ArmAdapter(null).capabilities();
        assertSame(first, new ArmAdapter(null).capabilities());
        assertThrows(UnsupportedOperationException.class, () -> first.all().put("guard", "none"));
    }

    @Test void aReadIsTheSameIncarnationAsAnIdenticalRead() {
        assertTrue(read().sameIncarnation(read()));
        var one = read();
        assertTrue(one.sameIncarnation(one));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void anyReplacedLiveObjectIsANewIncarnation(int index) {
        assertFalse(read().sameIncarnation(readReplacing(index)));
        assertFalse(readReplacing(index).sameIncarnation(read()));
    }

    @Test void equalButDistinctLiveObjectsAreNotTheSameIncarnation() {
        var a = new ArmAdapter.OwnerRead(WORLD, "c001", OWNER, "p", manager, region, raw, wgManager, WORLD_OBJECT);
        var b = new ArmAdapter.OwnerRead(WORLD, "c001", OWNER, new String("p"), manager, region, raw, wgManager, WORLD_OBJECT);
        assertEquals(a.provider(), b.provider());
        assertFalse(a.sameIncarnation(b), "identity, not equality, decides");
    }

    @Test void aDifferentOwnerRegionOrWorldIsNotTheSameIncarnation() {
        var base = read();
        assertFalse(base.sameIncarnation(new ArmAdapter.OwnerRead(WORLD, "c001", new UUID(0, 99), provider, manager, region,
                raw, wgManager, WORLD_OBJECT)));
        assertFalse(base.sameIncarnation(new ArmAdapter.OwnerRead(WORLD, "C001", OWNER, provider, manager, region,
                raw, wgManager, WORLD_OBJECT)), "the region id is compared exactly");
        assertFalse(base.sameIncarnation(new ArmAdapter.OwnerRead(new UUID(0, 98), "c001", OWNER, provider, manager, region,
                raw, wgManager, WORLD_OBJECT)));
    }

    @Test void aMissingLiveObjectIsNeverTheSameIncarnationEvenWithItself() {
        var partial = new ArmAdapter.OwnerRead(WORLD, "c001", OWNER, provider, null, region, raw, wgManager, WORLD_OBJECT);
        assertFalse(partial.sameIncarnation(partial));
        var noWorld = new ArmAdapter.OwnerRead(WORLD, "c001", OWNER, provider, manager, region, raw, wgManager, null);
        assertFalse(noWorld.sameIncarnation(noWorld));
    }

    @Test void aTokenFromAnotherBackendIsNeverTheSameIncarnation() {
        OwnershipToken worldGuard = new WorldGuardListedBackend.Token(WORLD, "c001", OWNER, region, wgManager);
        assertFalse(read().sameIncarnation(worldGuard));
        assertFalse(read().sameIncarnation(null));
        assertFalse(worldGuard.sameIncarnation(read()));
    }

    @Test void theGuardInstallFailureCarriesTheOriginalReflectiveException() {
        var missing = new ClassNotFoundException("net.alex9849.arm.NpcTransactionGuard");
        var failure = new ArmAdapter.GuardInstallFailure(missing);
        assertSame(missing, failure.reflective());
        assertSame(missing, failure.getCause());
        assertEquals("java.lang.ClassNotFoundException: net.alex9849.arm.NpcTransactionGuard", failure.getMessage());
    }
}
