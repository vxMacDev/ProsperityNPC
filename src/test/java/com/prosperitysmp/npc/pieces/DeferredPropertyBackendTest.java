package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The stand-in property backend: empty and unavailable until bound, a pure delegate while bound, empty again once lost. */
class DeferredPropertyBackendTest {
    private static final UUID WORLD = new UUID(0, 41);
    private static final UUID OWNER = new UUID(0, 42);
    private final List<String> log = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private record Ref(UUID worldId, String regionId) implements RegionRef { }

    private record Token(UUID worldId, String regionId, UUID ownerId) implements OwnershipToken {
        @Override public boolean sameIncarnation(OwnershipToken other) { return other == this; }
    }

    private final Token token = new Token(WORLD, "home-1", OWNER);
    private final RegionView view = new RegionView() {
        @Override public UUID worldId() { return WORLD; }
        @Override public String regionId() { return "home-1"; }
        @Override public String market() { return "lands"; }
        @Override public String kind() { return "owned"; }
        @Override public Optional<UUID> ownerUuid() { return Optional.of(OWNER); }
        @Override public boolean matches(String worldName, String regionId) { return true; }
    };
    private final Capabilities caps = Capabilities.builder().value("guard", "veto-events").value("token-identity", "generation").build();

    private final PropertyBackend real = new PropertyBackend() {
        @Override public String id() { return "lands"; }
        @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
        @Override public int apiMinor() { return ProsperityNpcApi.MINOR - 1; }
        @Override public Capabilities capabilities() { return caps; }
        @Override public String label() { return "unverified"; }
        @Override public String displayName() { return "Lands"; }
        @Override public boolean available() { calls.add("available"); return true; }
        @Override public GuardResult installGuard(OwnershipHooks hooks) {
            calls.add("installGuard " + (hooks == null ? "null" : "hooks"));
            return GuardResult.installed(GuardMode.VETO_EVENTS);
        }
        @Override public Optional<RegionRef> regionAt(Location at) {
            calls.add("regionAt " + (at == null ? "null" : at.getBlockX()));
            return Optional.of(new Ref(WORLD, "home-1"));
        }
        @Override public Optional<RegionView> findRegion(String worldName, String regionId) {
            calls.add("findRegion " + worldName + " " + regionId);
            return Optional.of(view);
        }
        @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
            calls.add("ownerReadAt " + owner);
            return Optional.of(token);
        }
        @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
            calls.add("ownerReadRegion " + world + " " + regionId + " " + owner);
            return Optional.of(token);
        }
        @Override public Presence observe(UUID world, String regionId) {
            calls.add("observe " + regionId);
            return Presence.MISSING;
        }
        @Override public List<String> describe() { return List.of("lands: 3 homes"); }
    };

    private Deferred<PropertyBackend> deferred() {
        return new Deferred<>("property.backend", "lands", log::add);
    }

    private PieceSelector.Selection<PropertyBackend> select() {
        return new PieceSelector<>(PropertyBackend.class).select("lands", Map.of(),
                List.of(new PieceSelector.Candidate<>(real, "Lands", "7.1", 0)));
    }

    private DeferredPropertyBackend bound(Deferred<PropertyBackend> deferred) {
        deferred.serverLoaded(true, this::select);
        assertEquals(Deferred.State.BOUND, deferred.state());
        return new DeferredPropertyBackend(deferred);
    }

    private static void assertRefusesEverything(DeferredPropertyBackend backend) {
        assertFalse(backend.available());
        assertEquals("", backend.label());
        assertEquals("lands", backend.displayName(), "the awaited id stands in for a display name");
        assertTrue(backend.capabilities().all().isEmpty());
        var guard = backend.installGuard(null);
        assertFalse(guard.isInstalled());
        assertEquals("not bound", guard.reason());
        assertEquals(Optional.empty(), backend.regionAt(null));
        assertEquals(Optional.empty(), backend.findRegion("town", "home-1"));
        assertEquals(Optional.empty(), backend.ownerReadAt(null, OWNER));
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", OWNER));
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, "home-1"));
    }

    @Test void aNullDeferredIsRefused() {
        assertThrows(NullPointerException.class, () -> new DeferredPropertyBackend(null));
    }

    @Test void identityAndApiVersionComeFromTheCoreNotTheBackend() {
        var deferred = deferred();
        var backend = bound(deferred);
        assertSame(deferred, backend.deferred());
        assertEquals("lands", backend.id());
        assertEquals(ProsperityNpcApi.MAJOR, backend.apiMajor());
        assertEquals(ProsperityNpcApi.MINOR, backend.apiMinor(), "the stand-in speaks the core's minor, not the backend's");
    }

    @Test void beforeBindingEveryReadIsEmptyAndNothingReachesTheBackend() {
        var backend = new DeferredPropertyBackend(deferred());
        assertRefusesEverything(backend);
        assertEquals(List.of("lands: waiting for server startup"), backend.describe());
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test void whileBoundEveryCallIsDelegatedWithItsArguments() {
        var backend = bound(deferred());
        Location at = new Location(null, 12, 64, -3);
        assertTrue(backend.available());
        assertSame(caps, backend.capabilities());
        assertEquals("unverified", backend.label());
        assertEquals("Lands", backend.displayName());
        assertEquals(GuardMode.VETO_EVENTS, backend.installGuard(null).mode().orElseThrow());
        assertEquals(new Ref(WORLD, "home-1"), backend.regionAt(at).orElseThrow());
        assertSame(view, backend.findRegion("town", "home-1").orElseThrow());
        assertSame(token, backend.ownerReadAt(at, OWNER).orElseThrow());
        assertSame(token, backend.ownerReadRegion(WORLD, "home-1", OWNER).orElseThrow());
        assertEquals(Presence.MISSING, backend.observe(WORLD, "home-1"));
        assertEquals(List.of("lands: 3 homes"), backend.describe());
        assertEquals(List.of("available", "installGuard null", "regionAt 12", "findRegion town home-1",
                "ownerReadAt " + OWNER, "ownerReadRegion " + WORLD + " home-1 " + OWNER, "observe home-1"), calls);
    }

    @Test void afterThePluginIsDisabledEveryReadIsEmptyAgain() {
        var deferred = deferred();
        var backend = bound(deferred);
        deferred.pluginDisabled("Lands");
        calls.clear();
        assertRefusesEverything(backend);
        assertEquals(List.of("lands: plugin Lands was disabled; restart required"), backend.describe());
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test void afterTheServiceIsUnregisteredEveryReadIsEmptyAgain() {
        var deferred = deferred();
        var backend = bound(deferred);
        deferred.unregistered(real);
        calls.clear();
        assertRefusesEverything(backend);
        assertEquals(List.of("lands: its service was unregistered; restart required"), backend.describe());
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test void aFailedSelectionDescribesWhy() {
        var deferred = deferred();
        deferred.serverLoaded(true, () -> new PieceSelector<>(PropertyBackend.class).select("lands", Map.of(), List.of()));
        var backend = new DeferredPropertyBackend(deferred);
        assertRefusesEverything(backend);
        assertEquals(List.of("lands: not found"), backend.describe());
    }

    @Test void aReloadBeforeBindingDescribesTheRestart() {
        var deferred = deferred();
        deferred.serverLoaded(false, this::select);
        assertEquals(List.of("lands: server reloaded before binding; restart required"),
                new DeferredPropertyBackend(deferred).describe());
    }

    @Test void aBoundBackendThatReportsUnavailableIsUnavailable() {
        var deferred = new Deferred<PropertyBackend>("property.backend", "down", log::add);
        PropertyBackend down = new PropertyBackend() {
            @Override public String id() { return "down"; }
            @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
            @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
            @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
            @Override public String displayName() { return "Down"; }
            @Override public boolean available() { return false; }
            @Override public GuardResult installGuard(OwnershipHooks hooks) { return GuardResult.failed("down"); }
            @Override public Optional<RegionRef> regionAt(Location at) { return Optional.empty(); }
            @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return Optional.empty(); }
            @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) { return Optional.empty(); }
            @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) { return Optional.empty(); }
            @Override public Presence observe(UUID world, String regionId) { return Presence.UNREADABLE; }
            @Override public List<String> describe() { return List.of(); }
        };
        deferred.serverLoaded(true, () -> new PieceSelector<>(PropertyBackend.class).select("down", Map.of(),
                List.of(new PieceSelector.Candidate<>(down, "Down", "1", 0))));
        var backend = new DeferredPropertyBackend(deferred);
        assertTrue(deferred.available());
        assertFalse(backend.available());
        assertEquals("Down", backend.displayName());
        assertEquals("down", backend.installGuard(null).reason());
        assertEquals(List.of(), backend.describe());
    }
}
