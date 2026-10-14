package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.ItemRepairRecipes.Line;
import com.prosperitysmp.npc.economy.ItemRepairRecipes.Quoted;
import com.prosperitysmp.npc.economy.ItemRepairRecipes.Refused;
import com.prosperitysmp.npc.economy.ItemRepairRecipes.Unavailable;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Pure price math: never cheaper than ratio x cash, never below the base list, unknown prices fail closed. */
class ItemRepairRecipesTest {
    private static final ToIntFunction<String> STACK64 = name -> 64;
    private static final List<String> NAMES = List.of("PAPER", "BAKED_POTATO", "COPPER_INGOT", "IRON_INGOT");

    private static Function<String, OptionalLong> prices(Map<String, Long> table) {
        return name -> table.containsKey(name) ? OptionalLong.of(table.get(name)) : OptionalLong.empty();
    }

    @Test void randomPricesAlwaysMeetTheFloorAndNeverGoBelowBase() {
        Random random = new Random(20261013L);
        int quoted = 0, refused = 0;
        for (int run = 0; run < 10_000; run++) {
            long cash = 1 + random.nextInt(100_000);
            int ratio = 10_000 + random.nextInt(90_001);
            int lines = 1 + random.nextInt(4);
            var base = new ArrayList<Line>();
            var table = new HashMap<String, Long>();
            for (int i = 0; i < lines; i++) {
                base.add(new Line(NAMES.get(i), 1 + random.nextInt(64)));
                table.put(NAMES.get(i), 1L + random.nextInt(random.nextBoolean() ? 1_000 : 1_000_000));
            }
            var result = ItemRepairRecipes.quote(base, cash, ratio, 256, 4, prices(table), STACK64);
            long required = ItemRepairRecipes.requiredCents(cash, ratio);
            if (result instanceof Quoted q) {
                quoted++;
                assertTrue(q.valueCents() >= required, "value below the floor in run " + run);
                assertEquals(required, q.requiredCents());
                long value = 0;
                for (int i = 0; i < lines; i++) {
                    assertEquals(base.get(i).material(), q.lines().get(i).material());
                    assertTrue(q.lines().get(i).quantity() >= base.get(i).quantity(), "scaled below base in run " + run);
                    assertEquals(table.get(base.get(i).material()), q.lines().get(i).unitCents());
                    value += (long) q.lines().get(i).quantity() * q.lines().get(i).unitCents();
                }
                assertEquals(value, q.valueCents());
            } else {
                refused++;
                var reason = ((Refused) result).reason();
                assertTrue(reason == Unavailable.TOO_MANY_UNITS || reason == Unavailable.TOO_MANY_STACKS, "unexpected " + reason);
            }
        }
        // Positive controls: both branches are exercised, so the property is not vacuous.
        assertTrue(quoted > 1_000, "quoted " + quoted);
        assertTrue(refused > 100, "refused " + refused);
    }

    @Test void requiredValueIsAnExactCeiling() {
        assertEquals(3_000, ItemRepairRecipes.requiredCents(2_000, 15_000));
        assertEquals(5_250, ItemRepairRecipes.requiredCents(3_500, 15_000));
        // 2001 x 1.5 = 3001.5: floor would give 3001, which would let items be worth less than the floor.
        assertEquals(3_002, ItemRepairRecipes.requiredCents(2_001, 15_000));
        assertEquals(2, ItemRepairRecipes.requiredCents(1, 15_000));
        assertEquals(1, ItemRepairRecipes.requiredCents(1, 10_000));
    }

    @Test void scalingRoundsUpSoOneCentItemsStillMeetTheFloor() {
        var result = ItemRepairRecipes.quote(List.of(new Line("PAPER", 1)), 1, 15_000, 256, 4,
                prices(Map.of("PAPER", 1L)), STACK64);
        var q = assertInstanceOf(Quoted.class, result);
        assertEquals(2, q.requiredCents());
        assertEquals(2, q.lines().getFirst().quantity());
        assertEquals(2, q.valueCents());
        // Base list already above the floor: unchanged, never shrunk by a high price.
        var high = assertInstanceOf(Quoted.class, ItemRepairRecipes.quote(List.of(new Line("PAPER", 16)), 2_000, 15_000, 256, 4,
                prices(Map.of("PAPER", 1_000_000L)), STACK64));
        assertEquals(16, high.lines().getFirst().quantity());
    }

    @Test void anyUnknownOrZeroPriceRefusesTheQuote() {
        var base = List.of(new Line("PAPER", 16), new Line("BAKED_POTATO", 8));
        assertEquals(new Refused(Unavailable.PRICE_UNKNOWN), ItemRepairRecipes.quote(base, 2_000, 15_000, 256, 4,
                prices(Map.of("PAPER", 100L)), STACK64));
        assertEquals(new Refused(Unavailable.PRICE_UNKNOWN), ItemRepairRecipes.quote(base, 2_000, 15_000, 256, 4,
                prices(Map.of("PAPER", 100L, "BAKED_POTATO", 0L)), STACK64));
        assertEquals(new Refused(Unavailable.PRICE_UNKNOWN), ItemRepairRecipes.quote(base, 2_000, 15_000, 256, 4,
                prices(Map.of("PAPER", 100L, "BAKED_POTATO", ItemRepairRecipes.MAX_UNIT_CENTS + 1)), STACK64));
        // Positive control: both known prices quote.
        assertInstanceOf(Quoted.class, ItemRepairRecipes.quote(base, 2_000, 15_000, 256, 4,
                prices(Map.of("PAPER", 100L, "BAKED_POTATO", 300L)), STACK64));
    }

    @Test void ratioBelowOneHundredPercentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecipes.requiredCents(2_000, 9_999));
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecipes.requiredCents(2_000, 100_001));
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecipes.quote(List.of(new Line("PAPER", 1)), 2_000,
                9_999, 256, 4, prices(Map.of("PAPER", 1L)), STACK64));
    }

    @Test void largestAllowedInputsDoNotOverflowOrThrow() {
        // With the bounded cash, ratio and price inputs the exact math cannot overflow; extremes give a bound refusal.
        var base = List.of(new Line("PAPER", 256), new Line("GLASS", 256), new Line("IRON_INGOT", 256), new Line("BAKED_POTATO", 256));
        var table = Map.of("PAPER", 1L, "GLASS", 1L, "IRON_INGOT", 1L, "BAKED_POTATO", 1L);
        var result = ItemRepairRecipes.quote(base, ItemRepairRecipes.MAX_CASH_CENTS, ItemRepairRecipes.MAX_RATIO_BPS, 256, 4,
                prices(table), STACK64);
        assertEquals(new Refused(Unavailable.TOO_MANY_UNITS), result);
        var expensive = Map.of("PAPER", ItemRepairRecipes.MAX_UNIT_CENTS, "GLASS", ItemRepairRecipes.MAX_UNIT_CENTS,
                "IRON_INGOT", ItemRepairRecipes.MAX_UNIT_CENTS, "BAKED_POTATO", ItemRepairRecipes.MAX_UNIT_CENTS);
        assertInstanceOf(Refused.class, ItemRepairRecipes.quote(base, ItemRepairRecipes.MAX_CASH_CENTS,
                ItemRepairRecipes.MAX_RATIO_BPS, 256, 4, prices(expensive), STACK64));
    }

    @Test void unitAndStackBoundsRefuse() {
        // A cheap item scaled past 256 in one line.
        assertEquals(new Refused(Unavailable.TOO_MANY_UNITS), ItemRepairRecipes.quote(List.of(new Line("PAPER", 16)), 50_000,
                15_000, 256, 4, prices(Map.of("PAPER", 1L)), STACK64));
        // The total over max-units-per-repair.
        assertEquals(new Refused(Unavailable.TOO_MANY_UNITS), ItemRepairRecipes.quote(List.of(new Line("PAPER", 200)), 1,
                15_000, 100, 4, prices(Map.of("PAPER", 1L)), STACK64));
        assertInstanceOf(Quoted.class, ItemRepairRecipes.quote(List.of(new Line("PAPER", 100)), 1,
                15_000, 100, 4, prices(Map.of("PAPER", 1L)), STACK64));
        // Eight stacks of 16 (128 items, inside the unit bound) cannot come back within four.
        var base = List.of(new Line("PAPER", 64), new Line("GLASS", 64));
        var table = Map.of("PAPER", 100L, "GLASS", 100L, "IRON_INGOT", 100L);
        assertEquals(new Refused(Unavailable.TOO_MANY_STACKS), ItemRepairRecipes.quote(base, 2_000, 15_000, 256, 4, prices(table), name -> 16));
        assertInstanceOf(Quoted.class, ItemRepairRecipes.quote(base, 2_000, 15_000, 256, 4, prices(table), STACK64));
        assertEquals(new Refused(Unavailable.TOO_MANY_STACKS), ItemRepairRecipes.quote(List.of(new Line("PAPER", 64), new Line("GLASS", 1)),
                2_000, 15_000, 256, 1, prices(table), STACK64));
        assertInstanceOf(Quoted.class, ItemRepairRecipes.quote(List.of(new Line("PAPER", 64)), 2_000, 15_000, 256, 1, prices(table), STACK64));
    }

    @Test void recipesNeedStackableUniqueMaterials() {
        assertEquals(new Refused(Unavailable.NO_RECIPE), ItemRepairRecipes.quote(List.of(), 2_000, 15_000, 256, 4,
                prices(Map.of()), STACK64));
        assertEquals(new Refused(Unavailable.NO_RECIPE), ItemRepairRecipes.quote(List.of(new Line("PAPER", 1), new Line("PAPER", 2)),
                2_000, 15_000, 256, 4, prices(Map.of("PAPER", 100L)), STACK64));
        assertEquals(new Refused(Unavailable.NO_RECIPE), ItemRepairRecipes.quote(List.of(new Line("DIAMOND_SWORD", 1)),
                2_000, 15_000, 256, 4, prices(Map.of("DIAMOND_SWORD", 100L)), name -> 1));
    }

    @Test void denyListAndUnstackableItemsAreRejectedAtLoad() {
        for (String denied : List.of("SPAWNER", "BEDROCK", "BARRIER", "PLAYER_HEAD", "ZOMBIE_SPAWN_EGG", "COMMAND_BLOCK",
                "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART", "DEBUG_STICK", "TRIAL_SPAWNER", "VAULT", "LIGHT"))
            assertFalse(ItemRepairRecipes.allowedMaterial(denied, 64), denied);
        assertFalse(ItemRepairRecipes.allowedMaterial("DIAMOND_SWORD", 1));
        assertFalse(ItemRepairRecipes.allowedMaterial("paper", 64));
        assertFalse(ItemRepairRecipes.allowedMaterial("NOT_AN_ITEM", 0));
        // Positive control: the shipped recipe materials pass.
        for (String plain : List.of("PAPER", "BAKED_POTATO", "COPPER_INGOT", "IRON_INGOT", "GLASS"))
            assertTrue(ItemRepairRecipes.allowedMaterial(plain, 64), plain);
        assertTrue(ItemRepairSettings.parse(List.of(Map.of("material", "SPAWNER", "quantity", 1)), STACK64).isEmpty());
        assertTrue(ItemRepairSettings.parse(List.of(Map.of("material", "DIAMOND_SWORD", "quantity", 1)), name -> 1).isEmpty());
        assertTrue(ItemRepairSettings.parse(List.of(Map.of("material", "PAPER", "quantity", 1)), STACK64).isPresent());
    }

    @Test void chunksNeverExceedTheStackSize() {
        assertEquals(List.of(64, 36), ItemRepairRecipes.chunks(100, 64));
        assertEquals(List.of(16), ItemRepairRecipes.chunks(16, 64));
        assertEquals(List.of(16, 16, 16, 16), ItemRepairRecipes.chunks(64, 16));
    }

    @Test void describeIsPlain() {
        assertEquals("16 copper ingot, 4 iron ingot", ItemRepairRecipes.describe(List.of(new Line("COPPER_INGOT", 16), new Line("IRON_INGOT", 4))));
    }
}
