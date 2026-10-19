package com.prosperitysmp.npc.example;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The example adapter is the reference a third party copies, so what it says about itself is pinned exactly. */
class ExampleNullBackendTest {
    private static final Set<String> DOCUMENTED_CAPABILITIES = Set.of("guard", "owner-model", "location-lookup",
            "region-by-id", "distinguishes-missing", "token-identity", "sees-owner-round-trip", "needs-worldguard",
            "region-id-max-length", "main-thread-only");

    @Test void itsIdIsTheDocumentedConstantAndAValidPieceId() {
        var backend = new ExampleNullBackend();
        assertEquals("example-null", ExampleNullBackend.ID);
        assertEquals(ExampleNullBackend.ID, backend.id());
        assertTrue(backend.id().matches("[a-z][a-z0-9-]{1,31}"), backend.id());
    }

    @Test void itReportsTheApiVersionItWasCompiledAgainst() {
        var backend = new ExampleNullBackend();
        assertEquals(ProsperityNpcApi.MAJOR, backend.apiMajor());
        assertEquals(ProsperityNpcApi.MINOR, backend.apiMinor());
        assertEquals(1, backend.apiMajor());
        assertEquals(2, backend.apiMinor());
    }

    @Test void staffSeeItLabelledDevOnlyUnderTheNameExample() {
        var backend = new ExampleNullBackend();
        assertEquals("DEV only", backend.label());
        assertEquals("Example", backend.displayName());
    }

    @Test void itIsAlwaysAvailable() {
        assertTrue(new ExampleNullBackend().available());
    }

    @Test void itsDiagnoseLineSaysItKnowsNoRegions() {
        assertEquals(List.of("example-null: knows no regions (DEV only)"), new ExampleNullBackend().describe());
    }

    @Test void itsCapabilitiesAreExactlyTheDocumentedSetInOrder() {
        assertEquals("{distinguishes-missing=true, guard=none, location-lookup=false, main-thread-only=true, "
                + "needs-worldguard=false, owner-model=none (example only), region-by-id=false, "
                + "region-id-max-length=128, token-identity=object}", new ExampleNullBackend().capabilities().toString());
    }

    @Test void everyCapabilityNameIsOneThePropertyBackendDocumentationDefines() {
        for (String name : new ExampleNullBackend().capabilities().all().keySet())
            assertTrue(DOCUMENTED_CAPABILITIES.contains(name), name);
    }

    @Test void itsGuardCapabilityNamesAGuardModeAndMatchesWhatInstallGuardAchieves() {
        var caps = new ExampleNullBackend().capabilities();
        assertEquals(GuardMode.NONE, GuardMode.valueOf(caps.value("guard").toUpperCase(Locale.ROOT).replace('-', '_')));
        assertFalse(new ExampleNullBackend().installGuard((world, region) -> () -> { }).isInstalled());
    }

    @Test void itsRegionIdLimitIsTheDocumentedOneHundredTwentyEight() {
        assertEquals(128, Integer.parseInt(new ExampleNullBackend().capabilities().value("region-id-max-length")));
    }

    @Test void itClaimsNoLocationOrIdLookupAndNoWorldGuardNeed() {
        var caps = new ExampleNullBackend().capabilities();
        assertFalse(caps.flag("location-lookup"));
        assertFalse(caps.flag("region-by-id"));
        assertFalse(caps.flag("needs-worldguard"));
        assertTrue(caps.flag("main-thread-only"));
        assertTrue(caps.flag("distinguishes-missing"));
        assertEquals("object", caps.value("token-identity"));
    }

    @Test void installGuardFailsWithTheDocumentedReasonEvenWithoutHooks() {
        var result = new ExampleNullBackend().installGuard(null);
        assertFalse(result.isInstalled());
        assertTrue(result.mode().isEmpty());
        assertEquals("example only", result.reason());
    }

    @Test void installGuardNeverTouchesTheHooksItIsGiven() {
        var touched = new AtomicInteger();
        OwnershipHooks hooks = new OwnershipHooks() {
            @Override public Runnable beginWrite(UUID world, String regionId) { touched.incrementAndGet(); return () -> { }; }
            @Override public void externalChange(UUID world, String regionId) { touched.incrementAndGet(); }
            @Override public void markDirty(UUID world, String regionId) { touched.incrementAndGet(); }
            @Override public boolean isHeld(UUID world, String regionId) { touched.incrementAndGet(); return false; }
            @Override public void guarded(String what, Runnable body) { touched.incrementAndGet(); }
        };
        new ExampleNullBackend().installGuard(hooks);
        assertEquals(0, touched.get());
    }

    @Test void itHasNoGuardBindings() {
        var backend = new ExampleNullBackend();
        backend.installGuard((world, region) -> () -> { });
        assertEquals(List.of(), backend.guardBindings());
    }

    @Test void everyReadAnswersEmptyEvenForNullArguments() {
        var backend = new ExampleNullBackend();
        assertTrue(backend.regionAt(null).isEmpty());
        assertTrue(backend.findRegion(null, null).isEmpty());
        assertTrue(backend.ownerReadAt(null, null).isEmpty());
        assertTrue(backend.ownerReadRegion(null, null, null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "none", "plausible-region", "rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr", "café"})
    void anyRegionIdIsMissingWhateverItsSpelling(String regionId) {
        var backend = new ExampleNullBackend();
        assertEquals(Presence.MISSING, backend.observe(new UUID(1, 1), regionId));
        assertTrue(backend.findRegion("world", regionId).isEmpty());
        assertTrue(backend.ownerReadRegion(new UUID(1, 1), regionId, new UUID(2, 2)).isEmpty());
    }

    @Test void observeNeverThrowsAndNeverSaysPresent() {
        var backend = new ExampleNullBackend();
        assertEquals(Presence.MISSING, assertDoesNotThrow(() -> backend.observe(null, null)));
        assertEquals(Presence.MISSING, backend.observe(new UUID(0, 0), "a"));
    }

    @Test void aLocationInNoWorldHasNoRegion() {
        assertTrue(new ExampleNullBackend().regionAt(new Location(null, 12.5, 70, -3.25)).isEmpty());
    }

    @Test void twoInstancesReportTheSameCapabilitiesAndKeepNoState() {
        var a = new ExampleNullBackend();
        var b = new ExampleNullBackend();
        a.installGuard(null);
        assertEquals(a.capabilities().all(), b.capabilities().all());
        assertNotSame(a.capabilities(), a.capabilities());
        assertEquals(a.describe(), b.describe());
    }
}
