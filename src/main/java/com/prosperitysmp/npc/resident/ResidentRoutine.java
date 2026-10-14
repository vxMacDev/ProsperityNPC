package com.prosperitysmp.npc.resident;

/** O(1) encounter-time schedule evaluation. No ticking each offscreen resident, DB access or pathfinding. */
public final class ResidentRoutine {
    public enum Activity { HOME, WORK, ERRAND, LEISURE }
    public record Intent(Activity activity, String destinationTag, String outfitKey) { }

    private ResidentRoutine() { }

    public static Intent at(ResidentProfile resident, long worldTime) {
        int tick = (int) Math.floorMod(worldTime, 24_000L);
        // Minecraft tick zero is 06:00. A deterministic occupation offset avoids one town-wide rush.
        int start = switch (resident.occupation()) {
            case COOK -> 500;
            case COURIER, GARDENER -> 1000;
            case RESTORER, CARPENTER -> 2000;
            case SHOPKEEPER -> 3000;
        };
        if (tick < start || tick >= 16_000) {
            return new Intent(Activity.HOME, "home", resident.wardrobe().everyday());
        }
        if (tick < 9000) return new Intent(Activity.WORK, "work", resident.wardrobe().work());
        if (tick < 11_000) return new Intent(Activity.ERRAND, "market", resident.wardrobe().everyday());
        return new Intent(Activity.LEISURE, "leisure", resident.wardrobe().leisure());
    }
}
