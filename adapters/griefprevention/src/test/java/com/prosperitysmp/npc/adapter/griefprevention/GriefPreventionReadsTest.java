package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.BUYER;
import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.OTHER_WORLD;
import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.OWNER;
import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.WORLD;
import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.WORLD_NAME;
import static org.junit.jupiter.api.Assertions.*;

/** Every read the backend answers from GriefPrevention's claims, including what it refuses and how it fails. */
class GriefPreventionReadsTest {
    private final ApiGpFakeView view = new ApiGpFakeView();
    private final GriefPreventionBackend backend = new GriefPreventionBackend(view, line -> { });

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new IllegalStateException("claim store unreadable")),
                Arguments.of(new NullPointerException()),
                Arguments.of(new NoClassDefFoundError("me/ryanhamshire/GriefPrevention/Claim")),
                Arguments.of(new NoSuchMethodError("getOwnerID")));
    }

    // ---- regionAt --------------------------------------------------------------------------------------------

    @Test void aLocationInsideAClaimGivesTheClaimIdAndItsWorld() {
        view.claimAtResult = view.claims.get(10L);
        var ref = backend.regionAt(ApiGpFakeView.at(WORLD)).orElseThrow();
        assertEquals("10", ref.regionId());
        assertEquals(WORLD, ref.worldId());
    }

    @Test void aLocationInASubdivisionGivesTheSubdivisionId() {
        view.claimAtResult = view.claims.get(11L);
        assertEquals("11", backend.regionAt(ApiGpFakeView.at(WORLD)).orElseThrow().regionId());
    }

    @Test void aLocationInNoClaimGivesNothing() {
        view.claimAtResult = null;
        assertTrue(backend.regionAt(ApiGpFakeView.at(WORLD)).isEmpty());
    }

    @Test void aNullLocationOrOneWithNoWorldNeverReachesGriefPrevention() {
        view.claimAtResult = view.claims.get(10L);
        assertTrue(backend.regionAt(null).isEmpty());
        assertTrue(backend.regionAt(ApiGpFakeView.at(null)).isEmpty());
        assertEquals(0, view.claimAtCalls);
    }

    @Test void aClaimInAnotherWorldThanTheLocationIsNotTheLocationsRegion() {
        view.claimAtResult = view.claims.get(10L);
        assertTrue(backend.regionAt(ApiGpFakeView.at(OTHER_WORLD)).isEmpty());
        assertEquals(1, view.claimAtCalls);
    }

    @Test void anUnavailableBackendAnswersNoRegionWithoutAskingForTheClaim() {
        view.claimAtResult = view.claims.get(10L);
        view.available = false;
        assertTrue(backend.regionAt(ApiGpFakeView.at(WORLD)).isEmpty());
        assertEquals(0, view.claimAtCalls);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void aFailingLocationLookupGivesNothingInsteadOfThrowing(Throwable failure) {
        view.claimAtThrows = failure;
        assertEquals(Optional.empty(), assertDoesNotThrow(() -> backend.regionAt(ApiGpFakeView.at(WORLD))));
    }

    // ---- findRegion ------------------------------------------------------------------------------------------

    @Test void aFoundRegionDescribesTheClaimAsOwnedByItsPayer() {
        var region = backend.findRegion(WORLD_NAME, "10").orElseThrow();
        assertEquals(WORLD, region.worldId());
        assertEquals("10", region.regionId());
        assertEquals("griefprevention", region.market());
        assertEquals("owned", region.kind());
        assertEquals(Optional.of(OWNER), region.ownerUuid());
    }

    @Test void aSubdivisionIsFoundByItsOwnIdWithTheParentsOwner() {
        var region = backend.findRegion(WORLD_NAME, "11").orElseThrow();
        assertEquals("11", region.regionId());
        assertEquals(Optional.of(OWNER), region.ownerUuid());
    }

    @Test void matchesNeedsTheExactWorldNameAndRegionId() {
        var region = backend.findRegion(WORLD_NAME, "10").orElseThrow();
        assertTrue(region.matches(WORLD_NAME, "10"));
        assertFalse(region.matches(WORLD_NAME, "11"));
        assertFalse(region.matches("gp-api-other", "10"));
        assertFalse(region.matches(WORLD_NAME.toUpperCase(), "10"));
        assertFalse(region.matches(WORLD_NAME, " 10"));
        assertFalse(region.matches(null, "10"));
        assertFalse(region.matches(WORLD_NAME, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gp-api-other", "GP-API-TEST", "gp-api-test ", ""})
    void aRegionInADifferentlyNamedWorldIsNotFound(String worldName) {
        assertTrue(backend.findRegion(worldName, "10").isEmpty());
    }

    @Test void aNullWorldNameFindsNothing() {
        assertTrue(backend.findRegion(null, "10").isEmpty());
    }

    @Test void anAdminClaimIsFoundWithNoPayingOwner() {
        view.put(ApiGpFakeView.newClaim(20, null, null, new Object()));
        var region = backend.findRegion(WORLD_NAME, "20").orElseThrow();
        assertEquals(Optional.empty(), region.ownerUuid());
    }

    @Test void aClaimOwnedByTheZeroUuidIsFoundWithNoPayingOwner() {
        view.put(ApiGpFakeView.newClaim(21, null, new UUID(0, 0), new Object()));
        assertEquals(Optional.empty(), backend.findRegion(WORLD_NAME, "21").orElseThrow().ownerUuid());
    }

    @Test void aDeletedOrUnknownClaimIsNotFound() {
        assertTrue(backend.findRegion(WORLD_NAME, "99").isEmpty());
        view.claims.remove(10L);
        assertTrue(backend.findRegion(WORLD_NAME, "10").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " 10", "10 ", "010", "+10", "-1", "-0", "1.0", "0x10", "ten", "9223372036854775808"})
    void anIdThatIsNotACanonicalClaimIdNeverReachesGriefPrevention(String regionId) {
        assertTrue(backend.findRegion(WORLD_NAME, regionId).isEmpty());
        assertEquals(List.of(), view.lookups);
    }

    @Test void theLargestClaimIdIsLookedUpAsItsPlainDigits() {
        backend.findRegion(WORLD_NAME, "9223372036854775807");
        assertEquals(List.of(Long.MAX_VALUE), view.lookups);
    }

    @Test void aNullRegionIdFindsNothing() {
        assertTrue(backend.findRegion(WORLD_NAME, null).isEmpty());
        assertEquals(List.of(), view.lookups);
    }

    @Test void anUnavailableBackendFindsNothingAndAsksForNoClaim() {
        view.available = false;
        assertTrue(backend.findRegion(WORLD_NAME, "10").isEmpty());
        assertEquals(List.of(), view.lookups);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void aFailingClaimLookupFindsNothingInsteadOfThrowing(Throwable failure) {
        view.claimThrows = failure;
        assertEquals(Optional.empty(), assertDoesNotThrow(() -> backend.findRegion(WORLD_NAME, "10")));
    }

    // ---- ownerReadAt and ownerReadRegion ----------------------------------------------------------------------

    @Test void theOwnerReadByLocationCarriesTheWorldRegionAndOwner() {
        view.claimAtResult = view.claims.get(11L);
        var token = backend.ownerReadAt(ApiGpFakeView.at(WORLD), OWNER).orElseThrow();
        assertEquals(WORLD, token.worldId());
        assertEquals("11", token.regionId());
        assertEquals(OWNER, token.ownerId());
    }

    @Test void theOwnerReadByLocationRefusesSomeoneWhoDoesNotPay() {
        view.claimAtResult = view.claims.get(10L);
        assertTrue(backend.ownerReadAt(ApiGpFakeView.at(WORLD), BUYER).isEmpty());
        assertTrue(backend.ownerReadAt(ApiGpFakeView.at(WORLD), null).isEmpty());
    }

    @Test void theOwnerReadByLocationIsEmptyOutsideEveryClaim() {
        view.claimAtResult = null;
        assertTrue(backend.ownerReadAt(ApiGpFakeView.at(WORLD), OWNER).isEmpty());
    }

    @Test void theOwnerReadByRegionCarriesWhatWasAsked() {
        var token = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        assertEquals(WORLD, token.worldId());
        assertEquals("10", token.regionId());
        assertEquals(OWNER, token.ownerId());
    }

    @Test void theOwnerReadByRegionRefusesAnotherWorldOrNoWorld() {
        assertTrue(backend.ownerReadRegion(OTHER_WORLD, "10", OWNER).isEmpty());
        assertTrue(backend.ownerReadRegion(null, "10", OWNER).isEmpty());
    }

    @Test void theOwnerReadByRegionRefusesAnyoneButTheClaimOwner() {
        assertTrue(backend.ownerReadRegion(WORLD, "10", BUYER).isEmpty());
        assertTrue(backend.ownerReadRegion(WORLD, "10", null).isEmpty());
    }

    @Test void theZeroUuidNeverPaysEvenWhenItOwnsTheClaim() {
        view.put(ApiGpFakeView.newClaim(30, null, new UUID(0, 0), new Object()));
        assertTrue(backend.ownerReadRegion(WORLD, "30", new UUID(0, 0)).isEmpty());
    }

    @Test void anAdminClaimIsNeverPaidByAnyone() {
        view.put(ApiGpFakeView.newClaim(31, null, null, new Object()));
        assertTrue(backend.ownerReadRegion(WORLD, "31", OWNER).isEmpty());
        assertTrue(backend.ownerReadRegion(WORLD, "31", null).isEmpty());
    }

    @Test void aDeletedClaimGivesNoOwnerRead() {
        view.claims.remove(10L);
        assertTrue(backend.ownerReadRegion(WORLD, "10", OWNER).isEmpty());
    }

    @Test void aNullRegionIdGivesNoOwnerRead() {
        assertTrue(backend.ownerReadRegion(WORLD, null, OWNER).isEmpty());
    }

    @Test void theOwnerReadComparesTheCurrentOwnerNotAnEarlierOne() {
        view.put(ApiGpFakeView.newClaim(10, null, BUYER, view.parentIdentity, 11L));
        assertTrue(backend.ownerReadRegion(WORLD, "10", OWNER).isEmpty());
        assertEquals(BUYER, backend.ownerReadRegion(WORLD, "10", BUYER).orElseThrow().ownerId());
    }

    @ParameterizedTest
    @MethodSource("failures")
    void aFailingOwnerReadGivesNothingInsteadOfThrowing(Throwable failure) {
        view.claimThrows = failure;
        assertEquals(Optional.empty(), assertDoesNotThrow(() -> backend.ownerReadRegion(WORLD, "10", OWNER)));
    }

    @Test void anUnavailableBackendGivesNoOwnerRead() {
        view.available = false;
        assertTrue(backend.ownerReadRegion(WORLD, "10", OWNER).isEmpty());
    }

    // ---- tokens -----------------------------------------------------------------------------------------------

    @Test void twoReadsOfTheSameLiveClaimAreTheSameIncarnation() {
        var first = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        var second = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        assertTrue(first.sameIncarnation(second));
        assertTrue(second.sameIncarnation(first));
        assertTrue(first.sameIncarnation(first));
    }

    @Test void aClaimRecreatedWithTheSameIdAndOwnerIsANewIncarnation() {
        var before = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        view.put(ApiGpFakeView.newClaim(10, null, OWNER, new Object(), 11L));
        var after = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        assertFalse(before.sameIncarnation(after));
        assertFalse(after.sameIncarnation(before));
    }

    @Test void aSubdivisionAndItsParentAreNotTheSameIncarnation() {
        var parent = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        var child = backend.ownerReadRegion(WORLD, "11", OWNER).orElseThrow();
        assertFalse(parent.sameIncarnation(child));
    }

    @Test void aSoldClaimIsANewIncarnationEvenWhenTheClaimObjectStaysTheSame() {
        var before = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        view.put(ApiGpFakeView.newClaim(10, null, BUYER, view.parentIdentity, 11L));
        var after = backend.ownerReadRegion(WORLD, "10", BUYER).orElseThrow();
        assertFalse(before.sameIncarnation(after));
    }

    @Test void aTokenIsNeverTheSameIncarnationAsNothingOrAForeignToken() {
        var token = backend.ownerReadRegion(WORLD, "10", OWNER).orElseThrow();
        assertFalse(token.sameIncarnation(null));
        OwnershipToken foreign = new OwnershipToken() {
            @Override public UUID worldId() { return WORLD; }
            @Override public String regionId() { return "10"; }
            @Override public UUID ownerId() { return OWNER; }
            @Override public boolean sameIncarnation(OwnershipToken other) { return true; }
        };
        assertFalse(token.sameIncarnation(foreign));
    }

    @Test void tokensComparedByWorldRegionOwnerAndTheLiveObject() {
        Object live = new Object();
        var base = new GriefPreventionBackend.Token(WORLD, "10", OWNER, live);
        assertTrue(base.sameIncarnation(new GriefPreventionBackend.Token(WORLD, "10", OWNER, live)));
        assertFalse(base.sameIncarnation(new GriefPreventionBackend.Token(OTHER_WORLD, "10", OWNER, live)));
        assertFalse(base.sameIncarnation(new GriefPreventionBackend.Token(WORLD, "11", OWNER, live)));
        assertFalse(base.sameIncarnation(new GriefPreventionBackend.Token(WORLD, "10", BUYER, live)));
        assertFalse(base.sameIncarnation(new GriefPreventionBackend.Token(WORLD, "10", OWNER, new Object())));
    }

    // ---- observe ---------------------------------------------------------------------------------------------

    @Test void aLiveClaimInTheAskedWorldIsPresent() {
        assertEquals(Presence.PRESENT, backend.observe(WORLD, "10"));
        assertEquals(Presence.PRESENT, backend.observe(WORLD, "11"));
    }

    @Test void aDeletedClaimIsMissing() {
        view.claims.remove(11L);
        assertEquals(Presence.MISSING, backend.observe(WORLD, "11"));
    }

    @Test void aClaimOfAnotherWorldIsMissingFromTheAskedWorld() {
        assertEquals(Presence.MISSING, backend.observe(OTHER_WORLD, "10"));
    }

    @Test void anAdminClaimIsPresent() {
        view.put(ApiGpFakeView.newClaim(40, null, null, new Object()));
        assertEquals(Presence.PRESENT, backend.observe(WORLD, "40"));
    }

    @Test void aNullWorldIsUnreadable() {
        assertEquals(Presence.UNREADABLE, backend.observe(null, "10"));
        assertEquals(List.of(), view.lookups);
    }

    @Test void anUnavailableBackendIsUnreadableAndAsksForNoClaim() {
        view.available = false;
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, "10"));
        assertEquals(List.of(), view.lookups);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void aFailingLookupIsUnreadableNotMissing(Throwable failure) {
        view.claimThrows = failure;
        assertEquals(Presence.UNREADABLE, assertDoesNotThrow(() -> backend.observe(WORLD, "10")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ten", "010", "-5"})
    void anIdThatCannotBeAClaimIsMissingWithoutALookup(String regionId) {
        assertEquals(Presence.MISSING, backend.observe(WORLD, regionId));
        assertEquals(List.of(), view.lookups);
    }

    @Test void aNullRegionIdIsMissing() {
        assertEquals(Presence.MISSING, backend.observe(WORLD, null));
    }

    @Test void presenceIsReadFromTheCurrentClaimsEachTime() {
        assertEquals(Presence.PRESENT, backend.observe(WORLD, "10"));
        view.claims.remove(10L);
        assertEquals(Presence.MISSING, backend.observe(WORLD, "10"));
        view.put(ApiGpFakeView.newClaim(10, null, OWNER, new Object()));
        assertEquals(Presence.PRESENT, backend.observe(WORLD, "10"));
    }
}
