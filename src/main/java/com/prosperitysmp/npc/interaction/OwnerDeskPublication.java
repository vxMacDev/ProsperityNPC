package com.prosperitysmp.npc.interaction;

import java.util.function.BooleanSupplier;

/** Inventory-open listeners can invalidate a request reentrantly. Publish private items only afterward. */
final class OwnerDeskPublication {
    private OwnerDeskPublication() { }
    static boolean publish(BooleanSupplier openEmpty, BooleanSupplier authorized,
                           Runnable populate, Runnable discardExactView) {
        boolean published = false;
        try {
            if (!openEmpty.getAsBoolean() || !authorized.getAsBoolean()) return false;
            populate.run();
            published = true;
            return true;
        } finally {
            if (!published) discardExactView.run();
        }
    }
}
