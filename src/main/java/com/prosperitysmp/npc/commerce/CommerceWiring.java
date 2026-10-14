package com.prosperitysmp.npc.commerce;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pure parts of the plugin's commerce wiring (status text, delivery gate, shutdown order), kept here so they can be
 * tested without a server.
 */
public final class CommerceWiring {
    /** The runtimes the plugin drains in its commerce block, in the order they are closed. */
    public enum Part { COMMERCE, COMMERCE_PAYMENTS, ITEM_REPAIRS, ITEM_DELIVERY }

    /**
     * Safety check, on by default: a paid order delivery is proven against a marked save made just before it, as an item
     * repair return is. Read once per delivery, only after the commerce gate let it through, so it is inert with
     * commerce off.
     */
    public static final String SAVE_MARKER = "town.commerce.delivery-save-marker";

    private CommerceWiring() { }

    /** Safety checks fail toward on: missing or true is on, false is off, anything else stays on. */
    static boolean saveMarkerOn(Object raw) {
        return !Boolean.FALSE.equals(raw);
    }

    /** A value that is neither missing nor true or false, so staff are told once that the check stays on. */
    static boolean saveMarkerMalformed(Object raw) {
        return raw != null && !(raw instanceof Boolean);
    }

    /**
     * /npcadmin status text. HELD_RUNTIME unless payments, commerce and item delivery all exist. The payment status
     * and delivery readiness are read only when they do.
     */
    public static String commerceStatus(boolean payments, boolean commerce, boolean delivery,
                                        Supplier<String> paymentStatus, BooleanSupplier deliveryReady) {
        Objects.requireNonNull(paymentStatus);
        Objects.requireNonNull(deliveryReady);
        return !payments || !commerce || !delivery ? "HELD_RUNTIME"
                : paymentStatus.get() + "; item custody " + (deliveryReady.getAsBoolean() ? "READY" : "STARTUP_PENDING");
    }

    /**
     * The live per-source delivery gate, read fresh on every call. Commerce goods read system, economy and
     * town.commerce-enabled in that order, stop at the first false and never read the database. Item-repair returns
     * need only system and a connected database, so items a repair took stay deliverable.
     */
    public static Predicate<ItemCustody.Source> deliveryGate(BooleanSupplier system, BooleanSupplier economy,
                                                             BooleanSupplier commerceEnabled, BooleanSupplier databaseConnected) {
        Objects.requireNonNull(system);
        Objects.requireNonNull(economy);
        Objects.requireNonNull(commerceEnabled);
        Objects.requireNonNull(databaseConnected);
        return source -> switch (Objects.requireNonNull(source)) {
            case WORKER_OUTPUT, MERCHANT_GOODS -> system.getAsBoolean() && economy.getAsBoolean() && commerceEnabled.getAsBoolean();
            case REPAIR_RETURN -> system.getAsBoolean() && databaseConnected.getAsBoolean();
        };
    }

    /**
     * Shutdown order for the runtimes that exist. Item repairs close after commerce payments and before item delivery,
     * because their returns go through it.
     */
    public static List<Part> closeOrder(boolean commerce, boolean payments, boolean itemRepairs, boolean delivery) {
        var order = new ArrayList<Part>(4);
        if (commerce) order.add(Part.COMMERCE);
        if (payments) order.add(Part.COMMERCE_PAYMENTS);
        if (itemRepairs) order.add(Part.ITEM_REPAIRS);
        if (delivery) order.add(Part.ITEM_DELIVERY);
        return List.copyOf(order);
    }
}
