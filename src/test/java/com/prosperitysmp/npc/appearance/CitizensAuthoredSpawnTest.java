package com.prosperitysmp.npc.appearance;

import com.prosperitysmp.npc.render.CitizensAdapter;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.CitizensPlugin;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.MetadataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.TraitFactory;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Real b4220 skin setters; fake server/NPC lifecycle, not a render or network test. */
@Isolated("Temporarily replaces Bukkit and Citizens static services")
class CitizensAuthoredSpawnTest {
    private static ResidentAppearanceCatalogue.Applied appearance;

    @BeforeAll static void syntheticSignedFixture() throws Exception {
        // Ephemeral test-only trust: not an authored Mojang asset.
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        var key = generator.generateKeyPair();
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                + "a".repeat(64) + "\"}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        var signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(key.getPrivate());
        signer.update(value.getBytes(StandardCharsets.US_ASCII));
        var skin = SignedResidentSkin.verifyWithKey(value,
                Base64.getEncoder().encodeToString(signer.sign()), key.getPublic());
        String asset = "resident/v1/restorer/everyday";
        appearance = new ResidentAppearanceCatalogue.Applied(asset,
                ResidentAppearanceCatalogue.citizensKey(asset), skin);
    }

    @Test void realSkinIsFullyInstalledBeforeSpawn() throws Exception {
        try (var h = new Harness()) {
            assertSame(h.npc, h.spawn(), () -> h.adapter.lastFailure());
            assertEquals(1, h.spawnCalls, () -> h.adapter.lastFailure());
            assertEquals(0, h.destroyCalls);
            assertTrue(h.registered);
            assertFalse(h.adapter.cleanupFailed());
        }
    }

    @Test void realPersistentSetterLinksOnExistingTestClasspath() throws Exception {
        try (var h = new Harness()) {
            h.skin.setSkinPersistent(appearance.citizensKey(), appearance.skin().signature(),
                    appearance.skin().value());
            assertEquals(appearance.skin().value(), h.skin.getTexture());
        }
    }

    @Test void requiredCitizensMetadataLinksOnExistingTestClasspath() {
        assertNotNull(NPC.Metadata.KEEP_CHUNK_LOADED);
    }

    @Test void nullAndInvalidAppearanceNeverCreateNpc() throws Exception {
        try (var h = new Harness()) {
            assertNull(h.adapter.spawnAuthored("Mara Vale", h.location, null));
            assertThrows(IllegalArgumentException.class, () -> new ResidentAppearanceCatalogue.Applied(
                    appearance.assetKey(), "foreign-cache", appearance.skin()));
            assertThrows(IllegalArgumentException.class, () -> new ResidentAppearanceCatalogue.Applied(
                    "unknown", appearance.citizensKey(), appearance.skin()));
            assertThrows(NullPointerException.class, () -> new ResidentAppearanceCatalogue.Applied(
                    appearance.assetKey(), appearance.citizensKey(), null));
            assertEquals(0, h.createCalls);
        }
    }

    @Test void aWellFormedKeyOutsideTheStartupUniverseNeverCreatesAnNpc() throws Exception {
        try (var h = new Harness()) {
            String otto = "resident/v1/otto-marsh/everyday";
            var foreign = new ResidentAppearanceCatalogue.Applied(otto, ResidentAppearanceCatalogue.citizensKey(otto), appearance.skin());
            assertNull(h.adapter.spawnAuthored("Otto Marsh", h.location, foreign));
            assertEquals(0, h.createCalls);
            assertSame(h.npc, h.spawn(), "a built-in key still spawns");
        }
    }

    @Test void exceptionInsideRealPersistentSetterCleansRegistry() throws Exception {
        try (var h = new Harness()) {
            h.failPersistent = true;
            assertNull(h.spawn());
            assertEquals(1, h.persistentCalls);
            assertEquals(appearance.skin().value(), h.skin.getTexture());
            assertEquals(appearance.skin().signature(), h.skin.getSignature());
            assertEquals(0, h.spawnCalls);
            assertEquals(1, h.destroyCalls);
            assertFalse(h.registered);
            assertFalse(h.adapter.cleanupFailed());
        }
    }

    @Test void createThrowAfterRegistrationDestroysPartialNpc() throws Exception {
        try (var h = new Harness()) {
            h.failCreate = true;
            assertNull(h.spawn());
            assertEquals(1, h.createCalls);
            assertEquals(1, h.destroyCalls);
            assertEquals(0, h.spawnCalls);
            assertFalse(h.registered);
            assertFalse(h.adapter.cleanupFailed());
        }
    }

    @Test void rejectedSpawnCleansRegistry() throws Exception {
        try (var h = new Harness()) {
            h.acceptSpawn = false;
            assertNull(h.spawn());
            assertEquals(1, h.spawnCalls);
            assertEquals(1, h.destroyCalls);
            assertFalse(h.registered);
            assertFalse(h.adapter.cleanupFailed());
        }
    }

    @Test void destroyExceptionUsesFallbackDeregister() throws Exception {
        try (var h = new Harness()) {
            h.acceptSpawn = false;
            h.failDestroy = true;
            assertNull(h.spawn());
            assertEquals(1, h.spawnCalls, () -> h.adapter.lastFailure());
            assertEquals(1, h.deregisterCalls);
            assertFalse(h.registered);
            assertFalse(h.adapter.cleanupFailed());
        }
    }

    @Test void failedCleanupBlocksCreationUntilExplicitCleanupSucceeds() throws Exception {
        try (var h = new Harness()) {
            h.acceptSpawn = false;
            h.failDestroy = true;
            h.failDeregister = true;
            assertNull(h.spawn());
            assertEquals(1, h.spawnCalls, () -> h.adapter.lastFailure());
            assertTrue(h.adapter.cleanupFailed());
            assertTrue(h.registered);
            assertNull(h.spawn());
            assertEquals(1, h.createCalls);
            h.failDestroy = false;
            h.failDeregister = false;
            h.adapter.destroyAll();
            assertFalse(h.adapter.cleanupFailed());
            assertFalse(h.registered);
            h.acceptSpawn = true;
            assertSame(h.npc, h.spawn());
            assertEquals(2, h.createCalls);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> type.getSimpleName() + "TestProxy";
                    default -> throw new AssertionError(m);
                };
            }
            return handler.invoke(p, m, a);
        }));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Harness implements AutoCloseable {
        final Field serverField = field(Bukkit.class, "server");
        final Field citizensField = field(CitizensAPI.class, "instance");
        final Object oldServer = serverField.get(null);
        final Object oldCitizens = citizensField.get(null);
        final Location location = new Location(null, 415.5, 72, -127.5);
        final Map<String, Object> metadata = new HashMap<>();
        NPC npc;
        SkinTrait skin;
        CitizensAdapter adapter;
        boolean registered, spawned, failPersistent, failCreate, failDestroy, failDeregister;
        boolean acceptSpawn = true;
        int createCalls, spawnCalls, destroyCalls, deregisterCalls, persistentCalls;

        Harness() throws Exception {
            try {
                serverField.set(null, proxy(Server.class, (p, m, a) -> {
                    if (m.getName().equals("isPrimaryThread")) return true;
                    throw new AssertionError(m);
                }));
                var factory = proxy(TraitFactory.class, (p, m, a) -> {
                    if (m.getName().equals("getId")) return 1;
                    throw new AssertionError(m);
                });
                citizensField.set(null, proxy(CitizensPlugin.class, (p, m, a) -> {
                    if (m.getName().equals("getTraitFactory")) return factory;
                    throw new AssertionError(m);
                }));
                var data = proxy(MetadataStore.class, (p, m, a) -> {
                    if (m.isDefault()) return InvocationHandler.invokeDefault(p, m, a);
                    if (m.getName().equals("setPersistent")) {
                        persistentCalls++;
                        if (failPersistent) throw new IllegalStateException("test persistent write failure");
                        metadata.put((String) a[0], a[1]);
                        return null;
                    }
                    if (m.getName().equals("set")) { metadata.put((String) a[0], a[1]); return null; }
                    throw new AssertionError(m);
                });
                var parameters = new NavigatorParameters();
                var navigator = proxy(Navigator.class, (p, m, a) -> switch (m.getName()) {
                    case "getDefaultParameters" -> parameters;
                    case "cancelNavigation" -> null;
                    default -> throw new AssertionError(m);
                });
                var entity = proxy(Entity.class, (p, m, a) -> switch (m.getName()) {
                    case "getLocation" -> location.clone();
                    case "isValid" -> spawned;
                    case "remove" -> { spawned = false; yield null; }
                    default -> throw new AssertionError(m);
                });
                npc = proxy(NPC.class, (p, m, a) -> switch (m.getName()) {
                    case "getId" -> 7;
                    case "data" -> data;
                    case "getCosmeticEntity" -> null;
                    case "getEntity" -> spawned ? entity : null;
                    case "getNavigator" -> navigator;
                    case "getOrAddTrait" -> { assertEquals(SkinTrait.class, a[0]); yield skin; }
                    case "setProtected" -> { assertEquals(true, a[0]); yield null; }
                    case "isSpawned" -> spawned;
                    case "spawn" -> {
                        spawnCalls++;
                        assertEquals(location, a[0]);
                        assertEquals(SpawnReason.PLUGIN, a[1]);
                        assertFalse(skin.fetchDefaultSkin());
                        assertFalse(skin.shouldUpdateSkins());
                        assertEquals(appearance.skin().value(), skin.getTexture());
                        assertEquals(appearance.skin().signature(), skin.getSignature());
                        assertEquals(appearance.citizensKey(), skin.getSkinName());
                        assertEquals(appearance.citizensKey(), metadata.get("cached-skin-uuid-name"));
                        assertNull(parameters.stuckAction());
                        assertEquals(0, parameters.destinationTeleportMargin());
                        spawned = acceptSpawn;
                        yield acceptSpawn;
                    }
                    case "destroy" -> {
                        destroyCalls++;
                        if (failDestroy) throw new IllegalStateException("test destroy failure");
                        registered = false; spawned = false; yield null;
                    }
                    default -> throw new AssertionError(m);
                });
                skin = new SkinTrait();
                skin.linkToNPC(npc);
                // Force opposite values so the test does not depend on Citizens defaults.
                skin.setFetchDefaultSkin(true);
                skin.setShouldUpdateSkins(true);
                var registry = proxy(NPCRegistry.class, (p, m, a) -> switch (m.getName()) {
                    case "createNPC" -> {
                        createCalls++;
                        assertEquals(EntityType.PLAYER, a[0]);
                        assertEquals("Mara Vale", a[1]);
                        registered = true;
                        if (failCreate) throw new IllegalStateException("test partial create failure");
                        yield npc;
                    }
                    case "getById" -> { assertEquals(7, a[0]); yield registered ? npc : null; }
                    case "forEach" -> InvocationHandler.invokeDefault(p, m, a);
                    case "iterator" -> (registered ? java.util.List.of(npc) : java.util.List.<NPC>of()).iterator();
                    case "deregister" -> {
                        deregisterCalls++;
                        assertSame(npc, a[0]);
                        if (failDeregister) throw new IllegalStateException("test deregister failure");
                        registered = false; yield null;
                    }
                    default -> throw new AssertionError(m);
                });
                var constructor = CitizensAdapter.class.getDeclaredConstructor(NPCRegistry.class,
                        Predicate.class, Predicate.class);
                constructor.setAccessible(true);
                adapter = constructor.newInstance(registry, (Predicate<Location>) l -> l == location,
                        (Predicate<Location>) l -> l == location);
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception restore) { failure.addSuppressed(restore); }
                throw failure;
            }
        }

        NPC spawn() { return adapter.spawnAuthored("Mara Vale", location, appearance); }

        @Override public void close() throws IllegalAccessException {
            try { citizensField.set(null, oldCitizens); }
            finally { serverField.set(null, oldServer); }
        }
    }
}
