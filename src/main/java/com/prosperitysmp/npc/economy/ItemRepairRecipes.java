package com.prosperitysmp.npc.economy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Pure item-repair price math: no Bukkit, SQL or money. A quote asks for plain items worth at least
 * ratio x cash at the full price-book value. Quantities start at the configured base and only ever scale up,
 * so a high price can never shrink what is taken. An unknown price refuses the quote; it is never zero.
 */
public final class ItemRepairRecipes {
    public static final int MAX_LINES = 4;
    public static final int MAX_LINE_QUANTITY = 256;
    public static final int MIN_RATIO_BPS = 10_000;
    public static final int MAX_RATIO_BPS = 100_000;
    public static final long MAX_UNIT_CENTS = 100_000_000L;
    public static final long MAX_CASH_CENTS = 100_000_000L;
    /** Items that must never stand in for plain goods, whatever their price. Spawn eggs and command blocks by pattern. */
    static final Set<String> DENIED = Set.of("STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "BARRIER", "LIGHT",
            "DEBUG_STICK", "KNOWLEDGE_BOOK", "BEDROCK", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST",
            "SPAWNER", "TRIAL_SPAWNER", "VAULT", "PETRIFIED_OAK_SLAB", "PLAYER_HEAD");

    public record Line(String material, int quantity) {
        public Line {
            if (material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}") || quantity < 1 || quantity > MAX_LINE_QUANTITY)
                throw new IllegalArgumentException("Invalid item repair line");
        }
    }
    public record Priced(String material, int quantity, long unitCents) {
        public Priced {
            Objects.requireNonNull(material);
            if (quantity < 1 || quantity > MAX_LINE_QUANTITY || unitCents < 1 || unitCents > MAX_UNIT_CENTS)
                throw new IllegalArgumentException("Invalid priced line");
        }
    }
    public enum Unavailable { PRICE_UNKNOWN, NO_RECIPE, TOO_MANY_UNITS, TOO_MANY_STACKS, OVERFLOW }
    public sealed interface Result permits Quoted, Refused { }
    public record Quoted(List<Priced> lines, long valueCents, long requiredCents) implements Result {
        public Quoted { lines = List.copyOf(lines); }
    }
    public record Refused(Unavailable reason) implements Result {
        public Refused { Objects.requireNonNull(reason); }
    }

    private ItemRepairRecipes() { }

    /** ceil(cash x ratio / 10000) with exact long math. Throws ArithmeticException on overflow. */
    public static long requiredCents(long cashCents, int ratioBps) {
        if (cashCents < 1 || cashCents > MAX_CASH_CENTS || ratioBps < MIN_RATIO_BPS || ratioBps > MAX_RATIO_BPS)
            throw new IllegalArgumentException("Invalid item repair price terms");
        return Math.ceilDiv(Math.multiplyExact(cashCents, (long) ratioBps), 10_000L);
    }

    /**
     * maxStack gives the item's stack size (0 for an unknown or non-item material). fullUnitCents gives the full
     * price-book unit price; empty means unknown or stale and refuses the quote.
     */
    public static Result quote(List<Line> base, long cashCents, int ratioBps, int maxUnits, int maxStacks,
                               Function<String, OptionalLong> fullUnitCents, ToIntFunction<String> maxStack) {
        Objects.requireNonNull(fullUnitCents); Objects.requireNonNull(maxStack);
        if (maxUnits < 1 || maxUnits > MAX_LINE_QUANTITY || maxStacks < 1 || maxStacks > MAX_LINES)
            throw new IllegalArgumentException("Invalid item repair bounds");
        long required = requiredCents(cashCents, ratioBps);
        if (base == null || base.isEmpty() || base.size() > MAX_LINES) return new Refused(Unavailable.NO_RECIPE);
        var seen = new HashSet<String>();
        long[] prices = new long[base.size()];
        int[] stacks = new int[base.size()];
        for (int i = 0; i < base.size(); i++) {
            Line line = base.get(i);
            if (!seen.add(line.material())) return new Refused(Unavailable.NO_RECIPE);
            stacks[i] = maxStack.applyAsInt(line.material());
            if (stacks[i] < 2) return new Refused(Unavailable.NO_RECIPE);
            OptionalLong price = fullUnitCents.apply(line.material());
            // Fail closed: unknown, stale, zero or absurd prices never price a quote.
            if (price == null || price.isEmpty() || price.getAsLong() < 1 || price.getAsLong() > MAX_UNIT_CENTS)
                return new Refused(Unavailable.PRICE_UNKNOWN);
            prices[i] = price.getAsLong();
        }
        long[] quantities = new long[base.size()];
        long value;
        try {
            value = 0;
            for (int i = 0; i < base.size(); i++) {
                quantities[i] = base.get(i).quantity();
                value = Math.addExact(value, Math.multiplyExact(quantities[i], prices[i]));
            }
            if (value < required) {
                // Each line scales by required/value, rounded up, so the new total is at least required.
                long old = value;
                value = 0;
                for (int i = 0; i < quantities.length; i++) {
                    quantities[i] = Math.ceilDiv(Math.multiplyExact(quantities[i], required), old);
                    value = Math.addExact(value, Math.multiplyExact(quantities[i], prices[i]));
                }
            }
        } catch (ArithmeticException overflow) {
            return new Refused(Unavailable.OVERFLOW);
        }
        if (value < required) throw new IllegalStateException("Item repair scaling fell short");
        long units = 0, stackCount = 0;
        for (int i = 0; i < quantities.length; i++) {
            if (quantities[i] > MAX_LINE_QUANTITY) return new Refused(Unavailable.TOO_MANY_UNITS);
            units += quantities[i];
            stackCount += Math.ceilDiv(quantities[i], stacks[i]);
        }
        if (units > maxUnits) return new Refused(Unavailable.TOO_MANY_UNITS);
        if (stackCount > maxStacks) return new Refused(Unavailable.TOO_MANY_STACKS);
        var lines = new ArrayList<Priced>();
        for (int i = 0; i < quantities.length; i++)
            lines.add(new Priced(base.get(i).material(), (int) quantities[i], prices[i]));
        return new Quoted(lines, value, required);
    }

    /** Stack sizes for a return: full stacks first, then the rest. Never more than maxStack per stack. */
    public static List<Integer> chunks(int quantity, int maxStack) {
        if (quantity < 1 || quantity > MAX_LINE_QUANTITY || maxStack < 1) throw new IllegalArgumentException("Invalid chunking");
        var out = new ArrayList<Integer>();
        for (int left = quantity; left > 0; left -= Math.min(left, maxStack)) out.add(Math.min(left, maxStack));
        return List.copyOf(out);
    }

    /** A recipe material: an item name, stackable, and not on the deny list. */
    public static boolean allowedMaterial(String name, int maxStack) {
        return name != null && name.matches("[A-Z][A-Z0-9_]{0,63}") && maxStack > 1 && !DENIED.contains(name)
                && !name.endsWith("_SPAWN_EGG") && !name.startsWith("COMMAND_BLOCK") && !name.endsWith("COMMAND_BLOCK");
    }

    /** Short plain list for players, for example "16 copper ingot, 4 iron ingot". */
    public static String describe(List<? extends Object> lines) {
        var parts = new ArrayList<String>();
        for (Object line : lines) {
            String material; int quantity;
            if (line instanceof Priced p) { material = p.material(); quantity = p.quantity(); }
            else if (line instanceof Line l) { material = l.material(); quantity = l.quantity(); }
            else if (line instanceof ItemRepairQuote.Line q) { material = q.material(); quantity = q.quantity(); }
            else throw new IllegalArgumentException("Unknown item line");
            parts.add(quantity + " " + itemName(material));
        }
        return String.join(", ", parts);
    }

    public static String itemName(String material) {
        return material.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
