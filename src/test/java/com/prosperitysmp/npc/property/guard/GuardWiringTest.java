package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardBinding;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import com.prosperitysmp.npc.arm.OwnershipFence;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guard install the plugin runs for a property backend other than ARM, driven with a fake backend, a fake
 * adapter plugin and a recording plugin manager: the token-identity mapping, where the handlers are filed, the
 * missing-adapter failure, and the replies the rent batch sends before and after the startup ownership pass.
 */
class GuardWiringTest {
    static final class PlotEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** A bound third-party backend: its guard mode, token identity and handlers are set per test. */
    static final class FakeBackend implements PropertyBackend {
        final GuardMode mode;
        final String tokenIdentity;
        final List<GuardBinding> bindings;
        int installs;

        FakeBackend(GuardMode mode, String tokenIdentity, List<GuardBinding> bindings) {
            this.mode = mode;
            this.tokenIdentity = tokenIdentity;
            this.bindings = bindings;
        }

        @Override public String id() { return "fake-plots"; }
        @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
        @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
        @Override public Capabilities capabilities() {
            var caps = Capabilities.builder().value("guard", mode.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            if (tokenIdentity != null) caps.value("token-identity", tokenIdentity);
            return caps.build();
        }
        @Override public String displayName() { return "Fake Plots"; }
        @Override public boolean available() { return true; }
        @Override public GuardResult installGuard(OwnershipHooks hooks) {
            installs++;
            return GuardResult.installed(mode);
        }
        @Override public Optional<RegionRef> regionAt(Location at) { return Optional.empty(); }
        @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return Optional.empty(); }
        @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) { return Optional.empty(); }
        @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) { return Optional.empty(); }
        @Override public Presence observe(UUID world, String regionId) { return Presence.PRESENT; }
        @Override public List<String> describe() { return List.of(); }
        @Override public List<GuardBinding> guardBindings() { return bindings; }
    }

    /** Every registerEvent call: the event type and the plugin the handler was filed under. */
    record Registration(Class<?> type, Object owner) { }

    private final List<Registration> registrations = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final PluginManager manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {PluginManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("registerEvent")) {
                    registrations.add(new Registration((Class<?>) args[0], args[4]));
                    return null;
                }
                if (method.getName().equals("toString")) return "manager";
                throw new UnsupportedOperationException(method.getName());
            });

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(GuardWiringTest.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PropertyGuard install(FakeBackend backend, Plugin filedUnder) {
        var guard = PropertyGuard.waiting("waiting for fake-plots to bind at server startup");
        var hooks = new FenceHooks(new OwnershipFence(() -> true), (what, failure) -> failures.add(what));
        guard.install(backend, hooks, filedUnder, manager, backend::available, (what, failure) -> {
            failures.add(what + ": " + failure.getClass().getSimpleName());
            guard.fail(what + ": " + failure.getClass().getSimpleName());
        });
        return guard;
    }

    private static List<GuardBinding> oneHandler() {
        return List.of(GuardBinding.of(PlotEvent.class, EventPriority.HIGHEST, true, event -> { }));
    }

    @Test void onlyObjectOrGenerationIsStrongAndAnythingElseIsWeak() {
        assertEquals(PropertyGuard.Identity.WEAK, PropertyGuard.identityOf("weak"));
        assertEquals(PropertyGuard.Identity.STRONG, PropertyGuard.identityOf("object"));
        assertEquals(PropertyGuard.Identity.STRONG, PropertyGuard.identityOf("generation"));
        assertEquals(PropertyGuard.Identity.WEAK, PropertyGuard.identityOf(""), "missing (Capabilities gives \"\")");
        assertEquals(PropertyGuard.Identity.WEAK, PropertyGuard.identityOf(null));
        assertEquals(PropertyGuard.Identity.WEAK, PropertyGuard.identityOf("obejct"), "misspelled");
        assertEquals(PropertyGuard.Identity.WEAK, PropertyGuard.identityOf("Object"), "not the exact value");
    }

    @Test void theInstalledGuardTakesItsIdentityFromTheBackend() {
        Plugin adapter = plugin("FakePlotsAdapter");
        String[] values = {"weak", "object", "generation", null, "obejct"};
        PropertyGuard.Identity[] expected = {PropertyGuard.Identity.WEAK, PropertyGuard.Identity.STRONG,
                PropertyGuard.Identity.STRONG, PropertyGuard.Identity.WEAK, PropertyGuard.Identity.WEAK};
        for (int i = 0; i < values.length; i++) {
            var guard = install(new FakeBackend(GuardMode.VETO_EVENTS, values[i], oneHandler()), adapter);
            assertTrue(guard.ready(), "token-identity " + values[i]);
            assertEquals(expected[i], guard.identity(), "token-identity " + values[i]);
            assertTrue(guard.startupPass(true, 0));
            assertEquals(expected[i] == PropertyGuard.Identity.STRONG, guard.payoutsAllowed(),
                    "a veto pays by default only with a strong identity: " + values[i]);
        }
        assertEquals(List.of(), failures);
    }

    @Test void handlersAreFiledUnderTheAdapterPlugin() {
        Plugin adapter = plugin("FakePlotsAdapter");
        var guard = install(new FakeBackend(GuardMode.VETO_EVENTS, "object", oneHandler()), adapter);
        assertTrue(guard.ready());
        assertEquals(List.of(new Registration(PlotEvent.class, adapter)), registrations,
                "filed under the adapter, so Bukkit drops them when it disables");
        assertEquals(List.of(), failures);
    }

    @Test void aMissingAdapterPluginFailsTheGuardInsteadOfFilingUnderTheCore() {
        var backend = new FakeBackend(GuardMode.VETO_EVENTS, "object", oneHandler());
        var guard = install(backend, null);
        assertEquals(1, backend.installs);
        assertEquals(List.of(), registrations, "no handler is registered anywhere");
        assertEquals(List.of("guard install: AdapterPluginMissing"), failures);
        assertFalse(guard.ready());
        assertTrue(guard.reason().contains("AdapterPluginMissing"));
        assertFalse(guard.startupPass(true, 0) && guard.payoutsAllowed(), "payouts stay held");
    }

    @Test void aBackendWithoutHandlersNeedsNoAdapterPlugin() {
        var guard = install(new FakeBackend(GuardMode.NONE, "object", List.of()), null);
        assertTrue(guard.ready(), "a guard of none registers nothing, so there is nothing to file");
        assertEquals(List.of(), failures);
    }

    @Test void theRentBatchWaitsForTheStartupPassThenHoldsAnUnguardedBackend() {
        var guard = install(new FakeBackend(GuardMode.NONE, "object", List.of()), plugin("FakePlotsAdapter"));
        assertTrue(guard.ready(), "debits and moves may start");
        assertFalse(guard.payoutsAllowed());
        assertEquals(PropertyGuard.NOT_RECONCILED_REPLY, guard.payoutRefusal(), "before the startup pass");
        assertFalse(guard.startupPass(true, 2), "a pass with unreadable homes does not count");
        assertEquals(PropertyGuard.NOT_RECONCILED_REPLY, guard.payoutRefusal());
        assertTrue(guard.startupPass(true, 0));
        assertFalse(guard.payoutsAllowed());
        assertEquals(PropertyGuard.PAYOUTS_HELD_REPLY, guard.payoutRefusal(), "unguarded: hold-payouts by default");
        guard.whenUnguarded("reread-before-payout");
        assertTrue(guard.payoutsAllowed(), "the opt-in");
        assertEquals("", guard.payoutRefusal());
    }

    @Test void aFailedLoadOfTheEnrolledHomesKeepsTheStartupPassFromCounting() {
        var guard = install(new FakeBackend(GuardMode.VETO_EVENTS, "object", oneHandler()), plugin("FakePlotsAdapter"));
        assertFalse(guard.startupPass(false, 0), "an empty list after a failed load is not a clean pass");
        assertTrue(guard.needsStartupReconcile());
        assertFalse(guard.payoutsAllowed());
        assertEquals(PropertyGuard.NOT_RECONCILED_REPLY, guard.payoutRefusal());
    }

    @Test void thePluginAndTheRentBatchUseThisWiring() throws Exception {
        Path j = Path.of("src/main/java/com/prosperitysmp/npc");
        String plugin = Files.readString(j.resolve("ProsperityNpcPlugin.java"));
        String install = plugin.substring(plugin.indexOf("private void installBackendGuard()"),
                plugin.indexOf("private void startupOwnershipPass()"));
        assertTrue(install.contains("propertyGuard.install(backend, hooks, filedUnder,"), "the plugin installs through PropertyGuard");
        assertTrue(install.contains("getServer().getPluginManager().getPlugin(owner)"), "a deferred backend files under its adapter");
        assertFalse(install.contains(": this,"), "never the core as a fallback");
        assertFalse(install.contains("token-identity"), "the identity mapping lives in PropertyGuard only");
        String pass = plugin.substring(plugin.indexOf("private void startupOwnershipPass()"));
        assertTrue(pass.contains("propertyGuard.startupPass(loaded, unreadable)"));
        assertTrue(pass.contains("boolean loaded = propertyService.cacheLoaded();"));
        String utility = Files.readString(j.resolve("economy/UtilityPaymentRuntime.java"));
        int batch = utility.indexOf("public boolean requestRentBatch(");
        int gate = utility.indexOf("if (!plugin.propertyGuard().payoutsAllowed()) {", batch);
        int reply = utility.indexOf("respond(authorized, reply, plugin.propertyGuard().payoutRefusal());", batch);
        assertTrue(batch > 0 && gate > batch && reply > gate && reply - gate < 120,
                "the rent batch replies with the guard's refusal before it reads any home");
    }
}
