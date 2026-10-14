package com.prosperitysmp.npc.sim;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic per-(property, economy-day, purpose) rolls. A roll is a pure hash
 * of stable inputs plus a per-install seed, so a server restart produces the exact
 * same roll and a landlord can never restart to reroll a bad incident or occupancy
 * outcome. The install seed keeps outcomes unpredictable to players without
 * persisting every roll.
 */
public final class DailyRoll {

    private final long seed;

    public DailyRoll(long seed) {
        this.seed = seed;
    }

    /** Returns a stable value in [0, bound) for the given property/day/purpose. */
    public int roll(UUID propertyId, String economyDay, String purpose, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, got " + bound);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer head = ByteBuffer.allocate(24);
            head.putLong(seed);
            head.putLong(propertyId.getMostSignificantBits());
            head.putLong(propertyId.getLeastSignificantBits());
            md.update(head.array());
            md.update(economyDay.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            long v = ByteBuffer.wrap(digest, 0, 8).getLong();
            return (int) Math.floorMod(v, bound);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
