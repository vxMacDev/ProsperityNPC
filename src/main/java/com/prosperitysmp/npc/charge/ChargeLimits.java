package com.prosperitysmp.npc.charge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The caps on configured bills, worked out without SQL or Bukkit. Config can set lower limits; these code ceilings
 * are the most any server can charge.
 *
 * <ol>
 *   <li>Each line is capped at its policy's {@code max-cents}. A line read from an outside meter whose result is
 *       more than four times that maximum is treated as a broken meter: it bills nothing and staff are told.</li>
 *   <li>The whole bill, all its lines together, is capped at $200. Lines are kept in policy-id order while they
 *       fit; a line that does not fit is left off, never cut down.</li>
 *   <li>The owner's daily limit (shared by linked accounts, one benefit unit) then keeps whole lines in policy-id
 *       order while they fit. A line that does not fit is left off, never cut down, so amounts stay whole.</li>
 *   <li>The server's daily limit does the same.</li>
 * </ol>
 */
public final class ChargeLimits {
    /** The most one bill line can ever be: $200. */
    public static final long PER_BILL_CEILING_CENTS = 20_000L;
    /** The most one whole bill (one home, one economy day, all its lines) can ever be: $200. */
    public static final long BILL_TOTAL_CEILING_CENTS = 20_000L;
    /** The most one owner unit can be billed in one economy day, across all their homes: $1,000. */
    public static final long OWNER_DAILY_CEILING_CENTS = 100_000L;
    /** The most the whole server can bill in one economy day: $25,000. */
    public static final long SERVER_DAILY_CEILING_CENTS = 2_500_000L;
    /** A meter line whose raw result is above this many times its maximum is a suspect reading. */
    public static final long SUSPECT_FACTOR = 4L;

    /** One policy's rounded result for one home, before caps. */
    public record Candidate(String policyId, long rawCents, long maxCents, long minCents, boolean externalMeter) {
        public Candidate {
            if (rawCents < 0 || maxCents < 1 || maxCents > PER_BILL_CEILING_CENTS || minCents < 0)
                throw new IllegalArgumentException("invalid bill candidate");
        }
    }

    /** A line that will be billed. {@code capped} is true when {@code max-cents} lowered it. */
    public record Line(String policyId, long rawCents, long finalCents, boolean capped) { }

    /** What the caps did. Every list is in policy-id order. */
    public record Outcome(List<Line> lines, List<String> belowMinimum, List<String> suspect,
                          List<String> ownerTrimmed, List<String> serverTrimmed, List<String> billTrimmed) {
        public long total() {
            long sum = 0;
            for (Line l : lines) sum = Math.addExact(sum, l.finalCents());
            return sum;
        }
    }

    private ChargeLimits() { }

    public static Outcome apply(List<Candidate> candidates, long ownerHeadroomCents, long serverHeadroomCents) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(Candidate::policyId));
        List<Line> lines = new ArrayList<>();
        List<String> below = new ArrayList<>(), suspect = new ArrayList<>(), owner = new ArrayList<>(), server = new ArrayList<>();
        List<String> bill = new ArrayList<>();
        long billLeft = BILL_TOTAL_CEILING_CENTS;
        long ownerLeft = Math.max(0, ownerHeadroomCents), serverLeft = Math.max(0, serverHeadroomCents);
        for (Candidate c : ordered) {
            if (c.externalMeter() && c.rawCents() > Math.multiplyExact(SUSPECT_FACTOR, c.maxCents())) {
                suspect.add(c.policyId());
                continue;
            }
            long capped = Math.min(c.rawCents(), c.maxCents());
            if (capped < Math.max(1, c.minCents())) {
                below.add(c.policyId());
                continue;
            }
            if (capped > billLeft) {
                bill.add(c.policyId());
                continue;
            }
            if (capped > ownerLeft) {
                owner.add(c.policyId());
                continue;
            }
            if (capped > serverLeft) {
                server.add(c.policyId());
                continue;
            }
            billLeft -= capped;
            ownerLeft -= capped;
            serverLeft -= capped;
            lines.add(new Line(c.policyId(), c.rawCents(), capped, capped < c.rawCents()));
        }
        return new Outcome(List.copyOf(lines), List.copyOf(below), List.copyOf(suspect), List.copyOf(owner),
                List.copyOf(server), List.copyOf(bill));
    }
}
