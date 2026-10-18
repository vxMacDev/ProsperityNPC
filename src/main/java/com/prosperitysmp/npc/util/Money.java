package com.prosperitysmp.npc.util;

import java.util.Locale;

/** Money helpers. All amounts are integer cents; formatting is display-only. */
public final class Money {

    private Money() {
    }

    /** Formats cents as {@code $D.CC} (handles negatives). */
    public static String format(long cents) {
        // Divide first: Math.abs(Long.MIN_VALUE) is still negative.
        String s = String.format(Locale.ROOT, "$%d.%02d", Math.abs(cents / 100), Math.abs(cents % 100));
        return cents < 0 ? "-" + s : s;
    }

    /**
     * Parses a positive dollar amount ("2", "2.5", "3.75") into cents.
     * Returns -1 for anything invalid or non-positive. No floating point is used
     * for the final value.
     */
    public static long parseDollarsToCents(String input) {
        if (input == null) {
            return -1;
        }
        String s = input.trim();
        if (s.startsWith("$")) {
            s = s.substring(1);
        }
        if (!s.matches("\\d+(\\.\\d{1,2})?")) {
            return -1;
        }
        int dot = s.indexOf('.');
        try {
            long dollars;
            long cents;
            if (dot < 0) {
                dollars = Long.parseLong(s);
                cents = 0;
            } else {
                dollars = Long.parseLong(s.substring(0, dot));
                String frac = s.substring(dot + 1);
                if (frac.length() == 1) {
                    frac = frac + "0";
                }
                cents = Long.parseLong(frac);
            }
            long total = Math.addExact(Math.multiplyExact(dollars, 100), cents);
            return total > 0 ? total : -1;
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }
}
