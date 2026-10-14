package com.prosperitysmp.npc.economy;

import java.util.Arrays;

/**
 * Pro-rata allocation of a capped pool (§3.3 step 5). When total requested exceeds
 * the cap, each request is scaled by cap/total and the leftover cents are handed
 * out by the largest-remainder method, so the allocation sums to EXACTLY
 * min(total, cap) and never depends on iteration order. All integer cents.
 */
public final class CapAllocator {

    private CapAllocator() {
    }

    public static long[] allocate(long[] requested, long cap) {
        int n = requested.length;
        long[] out = new long[n];
        if (n == 0) {
            return out;
        }
        long total = 0;
        for (long r : requested) {
            total += Math.max(0L, r);
        }
        if (cap < 0) {
            cap = 0;
        }
        if (total <= cap) {
            for (int i = 0; i < n; i++) {
                out[i] = Math.max(0L, requested[i]);
            }
            return out;
        }
        if (total == 0) {
            return out;
        }

        long allocated = 0;
        double[] remainderFrac = new double[n];
        for (int i = 0; i < n; i++) {
            long req = Math.max(0L, requested[i]);
            // req * cap fits in long for realistic caps; guard against overflow anyway.
            long product = Math.multiplyExact(req, cap);
            long base = product / total;
            out[i] = base;
            allocated += base;
            remainderFrac[i] = (double) (product % total) / (double) total;
        }

        long leftover = cap - allocated; // cents still to distribute (0 <= leftover < n)
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // Largest fractional remainder first; ties broken by lower index for determinism.
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(remainderFrac[b], remainderFrac[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        for (int k = 0; k < leftover && k < n; k++) {
            out[order[k]]++;
        }
        return out;
    }
}
