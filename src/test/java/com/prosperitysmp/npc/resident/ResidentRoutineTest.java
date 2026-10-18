package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import com.prosperitysmp.npc.resident.ResidentRoutine.Activity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The encounter-time schedule: a fixed day of 24000 ticks, one start tick per trade, no clock and no randomness. */
class ResidentRoutineTest {
    private static ResidentProfile resident(Occupation occupation) {
        return new ResidentProfile(new UUID(0, 1), "Mara Vale", "Vale", 1, occupation, ResidentProfile.Means.STEADY,
                ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM, ResidentProfile.Preference.QUIET_ROUTINE,
                new ResidentProfile.Wardrobe("outfit/everyday", "outfit/work", "outfit/leisure"));
    }

    private static Activity activity(Occupation occupation, long worldTime) {
        return ResidentRoutine.at(resident(occupation), worldTime).activity();
    }

    @ParameterizedTest
    @CsvSource({
            "COOK,0,HOME", "COOK,499,HOME", "COOK,500,WORK", "COOK,8999,WORK",
            "COURIER,0,HOME", "COURIER,999,HOME", "COURIER,1000,WORK", "COURIER,8999,WORK",
            "GARDENER,999,HOME", "GARDENER,1000,WORK", "GARDENER,8999,WORK",
            "RESTORER,1999,HOME", "RESTORER,2000,WORK", "RESTORER,8999,WORK",
            "CARPENTER,1999,HOME", "CARPENTER,2000,WORK", "CARPENTER,8999,WORK",
            "SHOPKEEPER,2999,HOME", "SHOPKEEPER,3000,WORK", "SHOPKEEPER,8999,WORK"})
    void eachTradeStartsWorkAtItsOwnTick(Occupation occupation, long tick, Activity expected) {
        assertEquals(expected, activity(occupation, tick));
    }

    @ParameterizedTest @EnumSource(Occupation.class)
    void everyTradeSharesTheSameErrandLeisureAndNightBoundaries(Occupation occupation) {
        assertEquals(Activity.WORK, activity(occupation, 8_999));
        assertEquals(Activity.ERRAND, activity(occupation, 9_000));
        assertEquals(Activity.ERRAND, activity(occupation, 10_999));
        assertEquals(Activity.LEISURE, activity(occupation, 11_000));
        assertEquals(Activity.LEISURE, activity(occupation, 15_999));
        assertEquals(Activity.HOME, activity(occupation, 16_000));
        assertEquals(Activity.HOME, activity(occupation, 23_999));
    }

    @Test void theDayRepeatsEvery24000Ticks() {
        for (Occupation occupation : Occupation.values()) {
            for (long tick : new long[] {0, 499, 500, 2_500, 9_000, 11_000, 15_999, 16_000, 23_999}) {
                assertEquals(activity(occupation, tick), activity(occupation, tick + 24_000L), occupation + " " + tick);
                assertEquals(activity(occupation, tick), activity(occupation, tick + 24_000L * 1_000_000), occupation + " " + tick);
            }
        }
    }

    @Test void negativeWorldTimeWrapsBackwardsFromTheEndOfTheDay() {
        assertEquals(Activity.HOME, activity(Occupation.COOK, -1));
        assertEquals(Activity.HOME, activity(Occupation.COOK, -8_000));
        assertEquals(Activity.LEISURE, activity(Occupation.COOK, -8_001));
        assertEquals(Activity.LEISURE, activity(Occupation.COOK, -13_000));
        assertEquals(Activity.ERRAND, activity(Occupation.COOK, -13_001));
        assertEquals(Activity.HOME, activity(Occupation.COOK, -24_000));
        assertEquals(Activity.WORK, activity(Occupation.COOK, -23_500));
    }

    @Test void theExtremeWorldTimesStillLandInsideTheDay() {
        // 9223372036854775807 mod 24000 = 7807 (work for a cook); -9223372036854775808 mod 24000 = 16192 (home).
        assertEquals(Activity.WORK, activity(Occupation.COOK, Long.MAX_VALUE));
        assertEquals(Activity.HOME, activity(Occupation.COOK, Long.MIN_VALUE));
    }

    @Test void homeUsesTheEverydayOutfitAndTheHomeTag() {
        var intent = ResidentRoutine.at(resident(Occupation.COOK), 0);
        assertEquals(Activity.HOME, intent.activity());
        assertEquals("home", intent.destinationTag());
        assertEquals("outfit/everyday", intent.outfitKey());
    }

    @Test void workUsesTheWorkOutfitAndTheWorkTag() {
        var intent = ResidentRoutine.at(resident(Occupation.COOK), 5_000);
        assertEquals(Activity.WORK, intent.activity());
        assertEquals("work", intent.destinationTag());
        assertEquals("outfit/work", intent.outfitKey());
    }

    @Test void anErrandGoesToTheMarketInTheEverydayOutfit() {
        var intent = ResidentRoutine.at(resident(Occupation.COOK), 10_000);
        assertEquals(Activity.ERRAND, intent.activity());
        assertEquals("market", intent.destinationTag());
        assertEquals("outfit/everyday", intent.outfitKey());
    }

    @Test void leisureUsesTheLeisureOutfitAndTheLeisureTag() {
        var intent = ResidentRoutine.at(resident(Occupation.COOK), 12_000);
        assertEquals(Activity.LEISURE, intent.activity());
        assertEquals("leisure", intent.destinationTag());
        assertEquals("outfit/leisure", intent.outfitKey());
    }

    @Test void theNightBeforeWorkAlsoSendsTheResidentHomeInTheEverydayOutfit() {
        var intent = ResidentRoutine.at(resident(Occupation.SHOPKEEPER), 2_500);
        assertEquals(Activity.HOME, intent.activity());
        assertEquals("home", intent.destinationTag());
        assertEquals("outfit/everyday", intent.outfitKey());
    }

    @Test void aMissingResidentIsRefused() {
        assertThrows(NullPointerException.class, () -> ResidentRoutine.at(null, 0));
    }
}
