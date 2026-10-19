package com.prosperitysmp.npc.adapter.realty;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.prosperitysmp.npc.adapter.realty.RegionCache.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class RegionCacheTest {
    static final UUID W = new UUID(0, 5), H1 = new UUID(0, 11), H2 = new UUID(0, 12), H3 = new UUID(0, 13);
    final RealtyModel model = new RealtyModel();
    final ModelView view = new ModelView(model);
    final List<String> logged = new ArrayList<>();
    RegionCache cache;

    @BeforeEach void setUp() {
        model.addWorld("w", W);
        model.addFreehold(W, "a", H1, null, 0, 0, 9, 9);
        model.addFreehold(W, "b", H2, null, 20, 20, 29, 29);
        cache = new RegionCache(view, logged::add);
    }

    void load() {
        cache.tick();
        view.pump();
    }

    void later(long millis) {
        model.advance(Duration.ofMillis(millis));
    }

    RegionCache.Status status(String id) {
        return cache.find(W, id, false).status();
    }

    @Test void nothingIsKnownBeforeTheFirstLoad() {
        assertEquals(NOT_LOADED, status("a"));
        assertFalse(cache.loadedOnce());
        assertEquals(Long.MAX_VALUE, cache.lastFullAgeMillis());
    }

    @Test void aFullLoadFillsTheSlots() {
        load();
        assertTrue(cache.loadedOnce());
        assertEquals(FOUND, status("a"));
        assertEquals(FOUND, status("B"));
        assertEquals(H1, cache.find(W, "a", false).plot().holderId());
        assertEquals(ABSENT, status("c"));
        assertEquals(2, cache.size());
    }

    @Test void aRegionThatLeftRealtyIsRemovedByTheNextFullLoad() {
        load();
        model.deleteRegion(W, "a", true);
        later(RegionCache.Tuning.FULL_EVERY + 1000);
        load();
        assertEquals(ABSENT, status("a"));
        assertEquals(FOUND, status("b"));
    }

    @Test void aQuietHotReloadKeepsTheIdentity() {
        load();
        Object before = cache.find(W, "a", true).identity();
        later(RegionCache.Tuning.HOT_EVERY + 1000);
        load();
        var after = cache.find(W, "a", true);
        assertEquals(FOUND, after.status());
        assertSame(before, after.identity());
    }

    @Test void aChangedPlotGetsANewIdentityWithoutAnEvent() {
        load();
        Object before = cache.find(W, "a", true).identity();
        model.region(W, "a").freeholdHolder = H3;
        later(RegionCache.Tuning.HOT_EVERY + 1000);
        load();
        var after = cache.find(W, "a", true);
        assertNotSame(before, after.identity());
        assertEquals(H3, after.plot().holderId());
    }

    @Test void aTouchedPlotGetsANewIdentityAndReadsStaleUntilReloaded() {
        load();
        Object before = cache.find(W, "a", false).identity();
        cache.touched(W, "a", null);
        assertEquals(STALE, status("a"));
        view.pump();
        var after = cache.find(W, "a", false);
        assertEquals(FOUND, after.status());
        assertNotSame(before, after.identity());
    }

    @Test void aLoadThatStartedBeforeATouchCannotOverwriteIt() {
        load();
        cache.touched(W, "a", null);
        view.pumpAsyncOnly();
        model.region(W, "a").freeholdHolder = H3;
        cache.touched(W, "a", null);
        view.pumpMainOnly();
        assertEquals(STALE, status("a"), "the older answer was dropped");
        view.pump();
        assertEquals(H3, cache.find(W, "a", false).plot().holderId());
    }

    @Test void aFullLoadThatStartedBeforeATouchCannotOverwriteIt() {
        cache.tick();
        view.pumpAsyncOnly();
        view.pumpMainOnly();
        view.pumpAsyncOnly();
        cache.touched(W, "a", null);
        view.pumpMainOnly();
        assertEquals(STALE, status("a"), "the chunk did not replace the touched slot");
        view.pump();
        assertEquals(FOUND, status("a"));
    }

    @Test void singleLoadsRunBetweenTheChunksOfAFullLoad() {
        for (int i = 0; i < 120; i++) model.addFreehold(W, "lot" + i, H1, null, 100 + i * 3, 100, 101 + i * 3, 101);
        load();
        assertEquals(122, cache.size());
        later(RegionCache.Tuning.FULL_EVERY + 1000);
        cache.tick();
        view.pumpAsyncOnly();
        view.pumpMainOnly();
        cache.touched(W, "lot7", null);
        view.pumpAsyncOnly();
        view.pumpMainOnly();
        assertEquals(FOUND, status("lot7"), "the single load did not wait for the whole full load");
        assertEquals(1, view.asyncSize(), "the full load is still reading its next chunk");
        view.pump();
        assertEquals(0, view.asyncSize());
    }

    @Test void aFailedLoadKeepsTheDataUntilTheTimeToLiveEnds() {
        load();
        model.sourceFails = true;
        later(20 * 60_000);
        cache.tick();
        view.pump();
        assertEquals(FOUND, status("a"), "twenty minutes old is still inside the limit");
        later(6 * 60_000);
        assertEquals(NOT_LOADED, status("a"), "the last good full load is older than the limit");
    }

    @Test void aHotSlotAgesOutSoonerThanAColdOne() {
        load();
        cache.find(W, "a", true);
        model.sourceFails = true;
        later(RegionCache.Tuning.HOT_TTL + 1000);
        assertEquals(STALE, status("a"));
        assertEquals(FOUND, status("b"));
    }

    @Test void onlyReadsMarkASlotHot() {
        load();
        assertEquals(0, cache.hotSize());
        cache.find(W, "a", false);
        assertEquals(0, cache.hotSize());
        cache.find(W, "a", true);
        assertEquals(1, cache.hotSize());
    }

    @Test void atMostOneFullLoadAndOneHotBatchAreQueued() {
        load();
        cache.find(W, "a", true);
        later(RegionCache.Tuning.FULL_EVERY + 1000);
        cache.tick();
        cache.tick();
        later(RegionCache.Tuning.FULL_EVERY + 1000);
        cache.tick();
        assertEquals(2, view.asyncSize(), "one list read and one hot batch");
    }

    @Test void aSecondHotBatchWaitsForTheFirstToFinish() {
        load();
        cache.find(W, "a", true);
        later(RegionCache.Tuning.HOT_EVERY + 1000);
        cache.tick();
        assertEquals(1, view.asyncSize());
        cache.find(W, "b", true);
        later(RegionCache.Tuning.HOT_EVERY + 1000);
        cache.tick();
        assertEquals(1, view.asyncSize(), "the first batch has not run, so no second one is queued");
        view.pump();
        later(RegionCache.Tuning.HOT_EVERY + 1000);
        cache.tick();
        assertEquals(1, view.asyncSize(), "once it ran, the next batch is queued");
    }

    @Test void aStaleSlotIsRetriedEveryFiveSeconds() {
        load();
        model.sourceFails = true;
        cache.touched(W, "a", null);
        view.pump();
        int loads = view.loads;
        cache.tick();
        view.pump();
        assertEquals(loads, view.loads, "too soon to try again");
        later(RegionCache.Tuning.RETRY);
        cache.tick();
        view.pump();
        assertTrue(view.loads > loads);
        model.sourceFails = false;
        later(RegionCache.Tuning.RETRY);
        cache.tick();
        view.pump();
        assertEquals(FOUND, status("a"));
    }

    @Test void aSilentChangeStaysHiddenUntilARefreshIsDue() {
        load();
        model.region(W, "a").freeholdHolder = H3;
        cache.tick();
        view.pump();
        assertEquals(H1, cache.find(W, "a", false).plot().holderId(), "no refresh was due, so nothing was read");
        later(RegionCache.Tuning.FULL_EVERY + 1000);
        load();
        assertEquals(H3, cache.find(W, "a", false).plot().holderId());
    }

    @Test void theReadStartTimeIsTheStampOfAFullLoad() {
        load();
        cache.find(W, "a", true);
        later(RegionCache.Tuning.FULL_EVERY + 1000);
        cache.find(W, "a", true);
        cache.tick();
        view.pumpAsyncOnly();
        later(RegionCache.Tuning.HOT_TTL + 1000);
        view.pump();
        assertEquals(STALE, status("a"), "the load took longer than the hot limit, so a hot slot is born stale");
        assertEquals(FOUND, status("b"));
    }

    @Test void aDisagreementMarksTheSlotStaleAndIsRateLimited() {
        load();
        cache.disagree(W, "a", List.of(H3));
        assertEquals(STALE, status("a"));
        assertEquals(1, view.asyncSize());
        view.pump();
        assertEquals(FOUND, status("a"));
        cache.disagree(W, "a", List.of(H3));
        assertEquals(FOUND, status("a"), "the same slot was touched a moment ago");
        assertEquals(0, view.asyncSize());
        later(RegionCache.Tuning.DISAGREE_RETOUCH);
        cache.disagree(W, "a", List.of(H3));
        assertEquals(STALE, status("a"));
    }

    @Test void aNewWorldGuardObjectActsLikeATouch() {
        load();
        Object first = new Object();
        assertTrue(cache.noteGuard(W, "a", first));
        assertTrue(cache.noteGuard(W, "a", first));
        Object second = new Object();
        assertFalse(cache.noteGuard(W, "a", second));
        assertEquals(STALE, status("a"));
        view.pump();
        assertTrue(cache.noteGuard(W, "a", second));
    }

    @Test void aTitleMovedToAPlayerFromAStaleListHoldsThePlot() {
        load();
        cache.disagree(W, "a", List.of(H3));
        view.pump();
        model.region(W, "a").freeholdHolder = H3;
        cache.touched(W, "a", null);
        view.pump();
        assertTrue(cache.isHeld(W, "a"));
        assertEquals(1, logged.size());
    }

    @Test void aTitleMovedToAnyoneElseIsNotHeld() {
        load();
        cache.disagree(W, "a", List.of(H3));
        view.pump();
        model.region(W, "a").freeholdHolder = H2;
        cache.touched(W, "a", null);
        view.pump();
        assertFalse(cache.isHeld(W, "a"));
    }

    @Test void aRegionRegisteredAgainUnderAnotherHolderIsHeld() {
        load();
        model.deleteRegion(W, "a", false);
        model.addFreehold(W, "a", H3, null, 0, 0, 9, 9);
        cache.touched(W, "a", null);
        view.pump();
        assertTrue(cache.isHeld(W, "a"));
    }

    @Test void aRegionRegisteredAgainUnderTheSameHolderIsNotHeld() {
        load();
        model.deleteRegion(W, "a", false);
        model.addFreehold(W, "a", H1, null, 0, 0, 9, 9);
        cache.touched(W, "a", null);
        view.pump();
        assertFalse(cache.isHeld(W, "a"));
        assertEquals(FOUND, status("a"));
    }

    @Test void releasingAHeldPlotLetsItPayAgainAndItStaysReleased() {
        load();
        model.deleteRegion(W, "a", false);
        model.addFreehold(W, "a", H3, null, 0, 0, 9, 9);
        cache.touched(W, "a", null);
        view.pump();
        assertTrue(cache.release(W, "a"));
        assertFalse(cache.release(W, "a"));
        cache.touched(W, "a", null);
        view.pump();
        assertFalse(cache.isHeld(W, "a"));
    }

    @Test void clearDropsEverythingButTheHeldList() {
        load();
        model.deleteRegion(W, "a", false);
        model.addFreehold(W, "a", H3, null, 0, 0, 9, 9);
        cache.touched(W, "a", null);
        view.pump();
        cache.clear();
        assertEquals(NOT_LOADED, status("a"));
        assertEquals(0, cache.size());
        assertTrue(cache.isHeld(W, "a"));
        load();
        assertEquals(FOUND, status("b"));
    }

    @Test void answersThatWereInFlightWhenTheCacheWasClearedAreDropped() {
        load();
        cache.touched(W, "a", null);
        cache.clear();
        view.pump();
        assertEquals(NOT_LOADED, status("a"));
        assertEquals(0, cache.size());
    }

    @Test void aRentMeasuresTheGapBetweenTheDatabaseClockAndTheServerClock() {
        load();
        assertTrue(cache.skewBound().isEmpty());
        assertEquals(Duration.ofSeconds(60), cache.margin());
        model.dbOffset = Duration.ofMinutes(5);
        model.addLease(W, "unit", null, null, "a", 1, 1, 2, 2);
        cache.touched(W, "unit", null);
        view.pump();
        model.rent(W, "unit", H3, Duration.ofDays(7)).runAll();
        cache.touched(W, "unit", model.jvmNow());
        view.pump();
        assertEquals(Duration.ofMinutes(5), cache.skewBound().orElseThrow());
        assertEquals(Duration.ofMinutes(5), cache.margin());
    }

    @Test void theMarginNeverDropsBelowTheFloor() {
        load();
        model.addLease(W, "unit", null, null, "a", 1, 1, 2, 2);
        cache.touched(W, "unit", null);
        view.pump();
        model.rent(W, "unit", H3, Duration.ofDays(7)).runAll();
        cache.touched(W, "unit", model.jvmNow());
        view.pump();
        assertEquals(Duration.ZERO, cache.skewBound().orElseThrow());
        assertEquals(Duration.ofSeconds(60), cache.margin());
    }

    @Test void idsOverTheLimitAreSkippedAndCounted() {
        model.addFreehold(W, "x".repeat(129), H1, null, 40, 40, 41, 41);
        model.addFreehold(W, "y".repeat(128), H1, null, 50, 50, 51, 51);
        load();
        assertEquals(1, cache.skippedLongIds());
        assertEquals(ABSENT, cache.find(W, "x".repeat(129), false).status());
        assertEquals(FOUND, cache.find(W, "y".repeat(128), false).status());
    }

    @Test void touchingARegionNobodyKnowsCreatesAPlaceholder() {
        load();
        model.addFreehold(W, "fresh", H3, null, 60, 60, 61, 61);
        cache.touched(W, "fresh", null);
        assertEquals(STALE, status("fresh"));
        assertNull(cache.find(W, "fresh", false).plot());
        view.pump();
        assertEquals(FOUND, status("fresh"));
        cache.touched(W, "nowhere", null);
        assertEquals(STALE, status("nowhere"));
        view.pump();
        assertEquals(ABSENT, status("nowhere"));
    }

    @Test void theCaseOfAnIdDoesNotMatterButTheDatabaseSpellingIsKept() {
        model.addFreehold(W, "MyPlot", H1, null, 70, 70, 71, 71);
        load();
        assertEquals("MyPlot", cache.find(W, "myplot", false).plot().regionId());
        assertEquals(FOUND, status("MYPLOT"));
    }

    @Test void theChildIndexFollowsTheWorldGuardParents() {
        model.addLease(W, "unit", null, null, "a", 1, 1, 2, 2);
        assertTrue(cache.childrenOf(W, "a").isEmpty(), "nothing was indexed yet");
        load();
        assertEquals(List.of("unit"), cache.childrenOf(W, "A").orElseThrow());
        assertEquals(List.of(), cache.childrenOf(W, "unit").orElseThrow());
    }

    @Test void aWorldThatCannotBeIndexedStaysUnknown() {
        view.childIndexFails = true;
        load();
        assertTrue(cache.childrenOf(W, "a").isEmpty());
        assertTrue(cache.failures() > 0);
    }

    @Test void aFailedFirstFullLoadIsTriedAgainAfterTheRetryTime() {
        model.sourceFails = true;
        load();
        assertFalse(cache.loadedOnce());
        model.sourceFails = false;
        cache.tick();
        view.pump();
        assertFalse(cache.loadedOnce(), "too soon");
        later(RegionCache.Tuning.RETRY);
        load();
        assertTrue(cache.loadedOnce());
    }

    @Test void nothingIsLoadedWhileRealtyIsNotInstalled() {
        model.installed = false;
        load();
        assertFalse(cache.loadedOnce());
        assertEquals(0, view.loads);
    }

    @Test void theFirstLoadIsAppliedAtOnceAndNoSecondOneStartsRightAway() {
        var source = view.source();
        try {
            cache.applyFirst(source.read(source.list()), model.jvmMillis);
        } catch (Exception failure) {
            fail(failure);
        }
        assertEquals(FOUND, status("a"));
        int before = view.loads;
        cache.tick();
        view.pump();
        assertEquals(before, view.loads);
    }
}
