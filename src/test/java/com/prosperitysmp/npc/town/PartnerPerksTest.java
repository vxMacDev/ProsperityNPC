package com.prosperitysmp.npc.town;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Partner perks and the offer that carries them: bounds, the lower-of rule and when a perk does anything. */
class PartnerPerksTest {
    private static final UUID RESIDENT = new UUID(0, 9);

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "0,1", "60,4", "59,3", "30,2"})
    void inRangePerksAreKeptAsGiven(int pct, int rank) {
        PartnerPerks perks = new PartnerPerks(pct, rank);
        assertEquals(pct, perks.feeDiscountPct());
        assertEquals(rank, perks.rankBonusPoints());
    }

    @ParameterizedTest
    @CsvSource({"61,0", "0,5", "-1,0", "0,-1", "100,4", "60,5", "2147483647,0", "0,2147483647", "-2147483648,0"})
    void outOfRangePerksAreRefused(int pct, int rank) {
        assertThrows(IllegalArgumentException.class, () -> new PartnerPerks(pct, rank));
    }

    @Test void noneIsZeroZero() {
        assertEquals(new PartnerPerks(0, 0), PartnerPerks.NONE);
        assertFalse(PartnerPerks.NONE.any());
    }

    @ParameterizedTest
    @CsvSource({"0,0,false", "1,0,true", "0,1,true", "60,4,true", "0,4,true", "5,0,true"})
    void anyIsTrueWhenEitherPerkIsSet(int pct, int rank, boolean any) {
        assertEquals(any, new PartnerPerks(pct, rank).any());
    }

    @ParameterizedTest
    @CsvSource({
            "0,0,false,false", "0,0,true,false",
            "10,0,false,true", "10,0,true,true",
            "0,2,false,false", "0,2,true,true",
            "10,2,false,true", "10,2,true,true"})
    void aRankPerkOnlyDoesSomethingWhileRankingIsLive(int pct, int rank, boolean rankLive, boolean live) {
        assertEquals(live, new PartnerPerks(pct, rank).live(rankLive));
    }

    @Test void effectiveTakesTheLowerOfSnapshotAndConfigPerPerk() {
        PartnerPerks result = PartnerPerks.effective(new PartnerPerks(40, 1), new PartnerPerks(25, 3));
        assertEquals(new PartnerPerks(25, 1), result);
    }

    @Test void staffCanLowerARunningTermButNeverRaiseIt() {
        PartnerPerks snapshot = new PartnerPerks(20, 2);
        assertEquals(new PartnerPerks(20, 2), PartnerPerks.effective(snapshot, new PartnerPerks(60, 4)));
        assertEquals(new PartnerPerks(5, 2), PartnerPerks.effective(snapshot, new PartnerPerks(5, 4)));
        assertEquals(new PartnerPerks(20, 0), PartnerPerks.effective(snapshot, new PartnerPerks(60, 0)));
        assertEquals(PartnerPerks.NONE, PartnerPerks.effective(snapshot, PartnerPerks.NONE));
    }

    @Test void effectiveIsSymmetricInItsInputs() {
        PartnerPerks a = new PartnerPerks(40, 1);
        PartnerPerks b = new PartnerPerks(25, 3);
        assertEquals(PartnerPerks.effective(a, b), PartnerPerks.effective(b, a));
    }

    @Test void aMissingSnapshotOrConfigGivesNoPerk() {
        assertSame(PartnerPerks.NONE, PartnerPerks.effective(null, new PartnerPerks(10, 1)));
        assertSame(PartnerPerks.NONE, PartnerPerks.effective(new PartnerPerks(10, 1), null));
        assertSame(PartnerPerks.NONE, PartnerPerks.effective(null, null));
    }

    @Test void offerLimitsAreTheDocumentedOnes() {
        assertEquals(60, PartnerOffer.MAX_FEE_DISCOUNT);
        assertEquals(4, PartnerOffer.MAX_RANK_POINTS);
        assertEquals(160, PartnerOffer.MAX_PITCH);
    }

    private static PartnerOffer offer(String material, int quantity, int pct, int rank, String pitch) {
        return new PartnerOffer(RESIDENT, material, quantity, pct, rank, pitch);
    }

    @Test void anOfferExposesItsPerksAsOneRecord() {
        PartnerOffer o = offer("IRON_INGOT", 16, 40, 2, "Weekly iron keeps my forge warm.");
        assertEquals(new PartnerPerks(40, 2), o.perks());
        assertEquals(RESIDENT, o.residentId());
        assertEquals("IRON_INGOT", o.material());
        assertEquals(16, o.quantity());
        assertEquals("Weekly iron keeps my forge warm.", o.pitch());
    }

    @ParameterizedTest @ValueSource(strings = {"iron", "1IRON", "IRON INGOT", "", "IRON-INGOT", "minecraft:iron_ingot", "Iron", "IRON\n"})
    void anOfferMaterialMustBeUpperSnakeCase(String material) {
        assertThrows(IllegalArgumentException.class, () -> offer(material, 16, 10, 0, "Pitch."));
    }

    @Test void anOfferMaterialIsAtMostSixtyFourCharacters() {
        assertDoesNotThrow(() -> offer("A".repeat(64), 16, 10, 0, "Pitch."));
        assertThrows(IllegalArgumentException.class, () -> offer("A".repeat(65), 16, 10, 0, "Pitch."));
        assertThrows(IllegalArgumentException.class, () -> offer(null, 16, 10, 0, "Pitch."));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 257, 1000, Integer.MIN_VALUE})
    void anOfferQuantityIsOneToTwoFiftySix(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", quantity, 10, 0, "Pitch."));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 255, 256})
    void anOfferQuantityInsideTheRangeIsKept(int quantity) {
        assertEquals(quantity, offer("IRON_INGOT", quantity, 10, 0, "Pitch.").quantity());
    }

    @Test void anOfferRefusesAFeeDiscountOrRankOutsideItsRange() {
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", 1, 61, 0, "Pitch."));
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", 1, -1, 0, "Pitch."));
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", 1, 0, 5, "Pitch."));
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", 1, 0, -1, "Pitch."));
        assertDoesNotThrow(() -> offer("IRON_INGOT", 1, 60, 4, "Pitch."));
    }

    @Test void anOfferMayCarryNoPerkAtAll() {
        assertFalse(offer("IRON_INGOT", 1, 0, 0, "Pitch.").perks().any());
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "  padded", "padded  ", "line\nbreak", "tab\there", "nul\u0000"})
    void aPitchIsPlainSingleLineTextWithoutPadding(String pitch) {
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", 1, 10, 0, pitch));
    }

    @Test void aPitchIsAtMostOneHundredSixtyCharacters() {
        assertEquals(160, offer("IRON_INGOT", 1, 10, 0, "p".repeat(160)).pitch().length());
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", 1, 10, 0, "p".repeat(161)));
        assertThrows(IllegalArgumentException.class, () -> offer("IRON_INGOT", 1, 10, 0, null));
    }

    @Test void anOfferNeedsAResident() {
        assertThrows(NullPointerException.class, () -> new PartnerOffer(null, "IRON_INGOT", 1, 10, 0, "Pitch."));
    }
}
