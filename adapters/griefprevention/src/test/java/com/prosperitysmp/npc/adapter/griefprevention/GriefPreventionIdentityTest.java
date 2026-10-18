package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.contract.RecordingHooks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** What the GriefPrevention backend says about itself: id, capabilities, availability and its staff diagnose lines. */
class GriefPreventionIdentityTest {
    private static final Set<String> DOCUMENTED_CAPABILITIES = Set.of("guard", "owner-model", "location-lookup",
            "region-by-id", "distinguishes-missing", "token-identity", "sees-owner-round-trip", "needs-worldguard",
            "region-id-max-length", "main-thread-only");

    private final ApiGpFakeView view = new ApiGpFakeView();
    private final GriefPreventionBackend backend = new GriefPreventionBackend(view, line -> { });

    static Stream<Arguments> probeFailures() {
        return Stream.of(
                Arguments.of(new IllegalStateException("probe failed")),
                Arguments.of(new NoClassDefFoundError("me/ryanhamshire/GriefPrevention/Claim")),
                Arguments.of(new NoSuchMethodError("getOwnerID")));
    }

    @Test void itsIdIsTheDocumentedConstantAndAValidPieceId() {
        assertEquals("griefprevention", GriefPreventionBackend.ID);
        assertEquals("griefprevention", backend.id());
        assertTrue(backend.id().matches("[a-z][a-z0-9-]{1,31}"));
    }

    @Test void itReportsTheCurrentApiVersion() {
        assertEquals(1, backend.apiMajor());
        assertEquals(2, backend.apiMinor());
    }

    @Test void staffSeeItNamedGriefPreventionAndLabelledUnverified() {
        assertEquals("GriefPrevention", backend.displayName());
        assertEquals("unverified", backend.label());
    }

    @Test void theConstructorRefusesAMissingViewOrLog() {
        assertThrows(NullPointerException.class, () -> new GriefPreventionBackend(null, line -> { }));
        assertThrows(NullPointerException.class, () -> new GriefPreventionBackend(view, null));
    }

    @Test void withoutRealEstateTheCapabilitiesAreExactlyTheDocumentedSet() {
        assertEquals("{distinguishes-missing=true, guard=veto-events, location-lookup=true, main-thread-only=true, "
                        + "needs-worldguard=false, owner-model=the claim owner (a subdivision pays its parent's owner); "
                        + "admin claims never, region-by-id=true, region-id-max-length=20, sees-owner-round-trip=false, "
                        + "token-identity=object}",
                backend.capabilities().toString());
    }

    @Test void withRealEstateOnlyTheGuardCapabilityChangesToNone() {
        var without = backend.capabilities().all();
        view.realEstate = true;
        var with = backend.capabilities().all();
        assertEquals("veto-events", without.get("guard"));
        assertEquals("none", with.get("guard"));
        var rest = new java.util.TreeMap<>(with);
        rest.put("guard", "veto-events");
        assertEquals(without, rest);
    }

    @ParameterizedTest
    @MethodSource("probeFailures")
    void whenTheRealEstateProbeFailsTheGuardCapabilityIsNoneSoNothingIsEverCancelled(Throwable failure) {
        view.realEstateThrows = failure;
        assertEquals("none", backend.capabilities().value("guard"));
    }

    @Test void everyCapabilityNameIsOneThePropertyBackendDocumentationDefines() {
        for (String name : backend.capabilities().all().keySet())
            assertTrue(DOCUMENTED_CAPABILITIES.contains(name), name);
    }

    @Test void theRegionIdLengthCapabilityCoversTheLongestPossibleClaimId() {
        int limit = Integer.parseInt(backend.capabilities().value("region-id-max-length"));
        assertEquals(20, limit);
        assertTrue(Long.toString(Long.MAX_VALUE).length() <= limit);
    }

    @Test void itClaimsNoRoundTripSightAndNoWorldGuardNeed() {
        var caps = backend.capabilities();
        assertFalse(caps.flag("sees-owner-round-trip"));
        assertFalse(caps.flag("needs-worldguard"));
        assertTrue(caps.flag("main-thread-only"));
        assertTrue(caps.flag("location-lookup"));
        assertTrue(caps.flag("region-by-id"));
        assertTrue(caps.flag("distinguishes-missing"));
        assertEquals("object", caps.value("token-identity"));
    }

    @Test void itIsAvailableWhileGriefPreventionIs() {
        assertTrue(backend.available());
    }

    @Test void itIsNotAvailableWhileGriefPreventionIsNot() {
        view.available = false;
        assertFalse(backend.available());
        view.available = true;
        assertTrue(backend.available(), "availability alone is not sticky");
    }

    @ParameterizedTest
    @MethodSource("probeFailures")
    void aFailingAvailabilityProbeMeansUnavailableAndNeverThrows(Throwable failure) {
        view.availableThrows = failure;
        assertFalse(assertDoesNotThrow(backend::available));
    }

    @Test void beforeTheGuardIsInstalledARealEstateChangeDoesNotMakeItUnavailable() {
        view.realEstate = true;
        assertTrue(backend.available());
        view.realEstate = false;
        assertTrue(backend.available());
    }

    @ParameterizedTest
    @MethodSource("probeFailures")
    void afterTheGuardIsInstalledAFailingRealEstateProbeHoldsPaymentsOnlyWhileItFails(Throwable failure) {
        assertTrue(backend.installGuard(new RecordingHooks()).isInstalled());
        view.realEstateThrows = failure;
        assertFalse(backend.available());
        view.realEstateThrows = null;
        assertTrue(backend.available(), "a probe that recovers does not mean the add-on changed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theDiagnoseLinesNameTheGuardAndEndWithTheVerificationStatement(boolean realEstate) {
        view.realEstate = realEstate;
        String guard = realEstate ? "RealEstate installed, so guard none (payouts held by default)" : "guard veto-events";
        assertEquals(List.of(
                "griefprevention: one claim owner per claim; subdivisions pay the parent's owner; " + guard,
                "GriefPrevention available; lab-recorded on GriefPrevention 16.18.7 (limited scope); not release-verified"),
                backend.describe());
    }

    @Test void theDiagnoseSaysUnavailableWhenGriefPreventionIsNot() {
        view.available = false;
        assertEquals("GriefPrevention UNAVAILABLE; " + GriefPreventionBackend.VERIFICATION, backend.describe().get(1));
    }

    @Test void theDiagnoseSaysARestartIsNeededOnceTheAddOnStateChanged() {
        assertTrue(backend.installGuard(new RecordingHooks()).isInstalled());
        view.realEstate = true;
        assertEquals("GriefPrevention UNAVAILABLE (RealEstate changed; restart required); " + GriefPreventionBackend.VERIFICATION,
                backend.describe().get(1));
    }

    @ParameterizedTest
    @MethodSource("probeFailures")
    void theDiagnoseStillAnswersWhenTheProbesFail(Throwable failure) {
        view.availableThrows = failure;
        view.realEstateThrows = failure;
        var lines = assertDoesNotThrow(backend::describe);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("RealEstate installed, so guard none (payouts held by default)"), lines.get(0));
        assertEquals("GriefPrevention UNAVAILABLE; " + GriefPreventionBackend.VERIFICATION, lines.get(1));
    }
}
