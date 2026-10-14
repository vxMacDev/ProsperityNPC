package com.prosperitysmp.npc.appearance;

/**
 * Whether an avatar already on screen should change into the outfit for its current routine. Pure: no I/O, no
 * Bukkit. A change only happens out of sight and out of conversation, because an encounter ticket is bound to the
 * avatar's entity UUID and a swap gives the resident a new entity.
 */
public final class OutfitChangePolicy {
    public enum Decision { NONE, WAIT, CHANGE }

    public static final double DEFAULT_HIDDEN_RADIUS = 16;
    public static final int DEFAULT_CHANGES_PER_TICK = 2;

    private OutfitChangePolicy() { }

    /**
     * NONE when the avatar already wears the selected skin, or no complete outfit is installed. WAIT while a player
     * is within {@code hiddenRadius} blocks or a conversation is open. CHANGE otherwise. An unknown distance (NaN)
     * counts as someone close by.
     */
    public static Decision decide(String appliedKey, ResidentAppearanceCatalogue.Selection current,
                                  double nearestViewerBlocks, boolean conversationOpen, double hiddenRadius) {
        if (!wantsChange(appliedKey, current)) return Decision.NONE;
        if (conversationOpen || !(nearestViewerBlocks > hiddenRadius)) return Decision.WAIT;
        return Decision.CHANGE;
    }

    /**
     * True when the avatar wears a different skin from the complete outfit selected now. Cheap, so callers check it
     * before looking for the nearest player.
     */
    public static boolean wantsChange(String appliedKey, ResidentAppearanceCatalogue.Selection current) {
        if (appliedKey == null || current == null || current.applied().isEmpty()) return false;
        if (current.status() != ResidentAppearanceCatalogue.Status.REQUESTED
                && current.status() != ResidentAppearanceCatalogue.Status.BASE_FALLBACK) return false;
        return !appliedKey.equals(current.applied().get().assetKey());
    }

    /** 8 to 48 blocks, and never above the rendering radius (but never below 8). */
    public static double hiddenRadius(double configured, double renderingRadius) {
        double value = Double.isFinite(configured) ? configured : DEFAULT_HIDDEN_RADIUS;
        double ceiling = Double.isFinite(renderingRadius) ? Math.clamp(renderingRadius, 8, 48) : 48;
        return Math.clamp(value, 8, ceiling);
    }

    /** 1 to 5 changes per render pass. */
    public static int changesPerTick(int configured) {
        return Math.clamp(configured, 1, 5);
    }
}
