package com.prosperitysmp.npc.interaction;

/** Pure compass math; Bukkit yaw zero faces south and negative yaw turns east. */
final class GuideGeometry {
    private static final String[] DIRECTIONS = {"Ahead", "Ahead-right", "Right", "Behind-right",
            "Behind", "Behind-left", "Left", "Ahead-left"};
    private GuideGeometry() { }
    static String direction(double dx, double dz, float yaw) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || !Float.isFinite(yaw)) return "Nearby";
        if (dx == 0 && dz == 0) return "Above/below";
        double relative = Math.toDegrees(Math.atan2(-dx, dz)) - yaw;
        int index = Math.floorMod((int) Math.floor((relative + 22.5) / 45), 8);
        return DIRECTIONS[index];
    }
    static double progress(double distance, double initialDistance) {
        if (!Double.isFinite(distance) || !Double.isFinite(initialDistance) || initialDistance <= 0) return 0;
        return Math.clamp(1 - distance / initialDistance, 0, 1);
    }
}
