package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRoutePlanner;
import com.prosperitysmp.npc.resident.ResidentRoutine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure decision for a premium tenant walking to the new home instead of reappearing there.
 * No world, clock, database or Bukkit access. Any doubt is a fallback to the normal respawn.
 */
public final class PremiumWalk {
    /** How long a committed move may wait for the display snapshot, and how long one walk may last. */
    public static final long WINDOW_MS = 10L * 60_000L;
    public static final int MAX_PENDING = 64;

    /** A committed premium relocation. */
    public record Move(UUID residentId, UUID oldPropertyId, UUID newPropertyId) { }

    public enum Verdict { WALK, FALLBACK }
    public record Decision(Verdict verdict, String reason, List<Long> nodes) {
        public Decision { nodes = List.copyOf(nodes); }
        public boolean walk() { return verdict == Verdict.WALK; }
    }

    private PremiumWalk() { }

    /** Committed premium relocations only (newly applied, with an origin). */
    public static List<Move> moves(List<HousingMoveRepository.Result> results) {
        var out = new ArrayList<Move>();
        for (var r : results) {
            var q = r.receipt().request();
            if (!r.newlyApplied() || q.origin() == null || q.tier() != HousingPolicy.Tier.PREMIUM) continue;
            out.add(new Move(q.residentId(), q.origin().propertyId(), q.propertyId()));
        }
        return out;
    }

    /** The routine intent that sends a resident to a home anchor, whatever the clock says. */
    public static ResidentRoutine.Intent homeIntent(ResidentProfile profile) {
        return new ResidentRoutine.Intent(ResidentRoutine.Activity.HOME, "home", profile.wardrobe().everyday());
    }

    /** The authored anchor of one property's home, so the walk ends at the new home and not at the resident's old one. */
    public static String homeTag(UUID propertyId) { return "home:" + propertyId; }

    /**
     * Walk only when both homes sit on the same published graph of the same world, the new property has its own
     * {@code home:<propertyId>} anchor, and a bounded route exists from the avatar's current node.
     * {@code start} is a node of {@code oldGraph}.
     */
    public static Decision decide(UUID oldWorld, String oldRegion, NavGraph oldGraph,
                                  UUID newWorld, String newRegion, NavGraph newGraph,
                                  ResidentProfile profile, UUID newPropertyId, long start) {
        if (oldGraph == null || newGraph == null || oldWorld == null || newWorld == null
                || oldRegion == null || newRegion == null) return fallback("graph unavailable");
        if (!oldWorld.equals(newWorld) || !oldRegion.toLowerCase(Locale.ROOT).equals(newRegion.toLowerCase(Locale.ROOT)))
            return fallback("different graph");
        if (!sameGraph(oldGraph, newGraph)) return fallback("graph changed");
        if (profile == null || newPropertyId == null) return fallback("no profile");
        if (start < 0 || !newGraph.hasNode(start)) return fallback("no start node");
        var route = ResidentRoutePlanner.planToTag(newGraph, start, homeTag(newPropertyId));
        return switch (route.status()) {
            case WALK -> new Decision(Verdict.WALK, "route", route.nodes());
            case AT_DESTINATION -> new Decision(Verdict.WALK, "already there", route.nodes());
            default -> fallback("no route: " + route.status());
        };
    }

    static boolean sameGraph(NavGraph a, NavGraph b) {
        return a == b || (a.nodeCount() == b.nodeCount() && a.edges().equals(b.edges())
                && a.nodes().stream().allMatch(n -> n.equals(b.node(n.id()))));
    }

    private static Decision fallback(String reason) { return new Decision(Verdict.FALLBACK, reason, List.of()); }
}
