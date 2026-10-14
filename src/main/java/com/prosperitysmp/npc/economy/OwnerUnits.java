package com.prosperitysmp.npc.economy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owner groups for the benefit-unit cap (linked-accounts.enabled). Pure: no Bukkit, no SQL. A group is a
 * connected component of the saved account links active for one economy day. Its key is the smallest
 * member by unsigned byte order (the order MariaDB uses for BINARY(16)), so the key is the same however the
 * edges are listed and is never stored. An owner with no links is its own group. Extra edges can only join
 * groups, never split them, so more links can only lower the shared headroom.
 */
public final class OwnerUnits {
    /** One saved link between two different accounts. */
    public record Edge(UUID a, UUID b) {
        public Edge {
            if (a == null || b == null) throw new IllegalArgumentException("Account link needs two accounts");
            if (a.equals(b)) throw new IllegalArgumentException("Account link cannot join an account to itself");
        }
    }

    /** Unsigned byte order of the 16-byte form, the same order as MariaDB BINARY comparison. */
    public static final Comparator<UUID> BYTE_ORDER = (x, y) -> {
        int high = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    };

    private static final OwnerUnits IDENTITY = new OwnerUnits(Map.of(), Map.of());
    private final Map<UUID, UUID> keyOf;
    private final Map<UUID, List<UUID>> membersOf;

    private OwnerUnits(Map<UUID, UUID> keyOf, Map<UUID, List<UUID>> membersOf) {
        this.keyOf = keyOf;
        this.membersOf = membersOf;
    }

    /** Every owner is its own group, with no links read. */
    public static OwnerUnits identity() { return IDENTITY; }

    /** Union-find over the edges. A null or self edge throws IllegalArgumentException. */
    public static OwnerUnits of(Collection<Edge> edges) {
        Objects.requireNonNull(edges);
        var parent = new HashMap<UUID, UUID>();
        for (Edge edge : edges) {
            if (edge == null) throw new IllegalArgumentException("Account link needs two accounts");
            UUID ra = root(parent, edge.a()), rb = root(parent, edge.b());
            if (ra.equals(rb)) continue;
            // The smaller root stays the root, so the final root of a component is its smallest member.
            if (BYTE_ORDER.compare(ra, rb) < 0) parent.put(rb, ra);
            else parent.put(ra, rb);
        }
        if (parent.isEmpty()) return IDENTITY;
        var keyOf = new HashMap<UUID, UUID>();
        var members = new HashMap<UUID, List<UUID>>();
        for (UUID member : parent.keySet()) {
            UUID key = root(parent, member);
            keyOf.put(member, key);
            members.computeIfAbsent(key, ignored -> new ArrayList<>()).add(member);
        }
        var sorted = new HashMap<UUID, List<UUID>>();
        for (var entry : members.entrySet()) {
            var list = new ArrayList<>(entry.getValue());
            list.sort(BYTE_ORDER);
            if (!list.getFirst().equals(entry.getKey())) throw new IllegalStateException("Owner group key is not its smallest member");
            sorted.put(entry.getKey(), List.copyOf(list));
        }
        return new OwnerUnits(Map.copyOf(keyOf), Map.copyOf(sorted));
    }

    private static UUID root(Map<UUID, UUID> parent, UUID node) {
        parent.putIfAbsent(node, node);
        UUID at = node;
        while (!parent.get(at).equals(at)) at = parent.get(at);
        // Path compression keeps lookups short; it never changes which node is the root.
        UUID walk = node;
        while (!walk.equals(at)) {
            UUID next = parent.get(walk);
            parent.put(walk, at);
            walk = next;
        }
        return at;
    }

    /** The group key: the smallest member by unsigned byte order, or the owner itself with no links. */
    public UUID unitOf(UUID owner) {
        Objects.requireNonNull(owner);
        return keyOf.getOrDefault(owner, owner);
    }

    /** Every account in the owner's group, sorted by unsigned byte order. Just the owner with no links. */
    public List<UUID> members(UUID owner) {
        Objects.requireNonNull(owner);
        return membersOf.getOrDefault(unitOf(owner), List.of(owner));
    }

    /** True when no owner shares a group with another. */
    public boolean isIdentity() { return keyOf.isEmpty(); }
}
