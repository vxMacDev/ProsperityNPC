package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.util.Money;
import java.util.List;
import java.util.UUID;

/** Every player and staff line for item repairs, in one place so the copy check reads them all. */
public final class ItemRepairCopy {
    private ItemRepairCopy() { }

    public static final String HAND_IN = "Hand in items";
    public static final String HAND_IN_UNAVAILABLE = "Hand in items (not available now)";
    public static final String BACK = "Back";
    public static final String CLOSE = "Close";
    public static final String FROM_INVENTORY = "Items come from your inventory. Your ender chest is not used.";
    public static final String PLAIN_ONLY = "Only plain items count. Named, enchanted or damaged items are skipped.";
    public static final String NO_FUNDING = "Item repairs do not add to this home's rent funding.";
    public static final String OFF = "Item repairs are turned off on this server.";
    public static final String UNAVAILABLE = "Item repair is not available right now. Cash repair still works.";
    public static final String SURVIVAL_ONLY = "Item repairs only work in survival mode.";
    public static final String CLOSE_INVENTORY = "Close any open inventory and try again.";
    public static final String HOME_BUSY = "This home is busy. Try again in a moment.";
    public static final String ALREADY_WAITING = "You already have an item repair waiting. Wait for it to finish.";
    public static final String RETURNS_FIRST = "You have items waiting. Type /property repair returns first.";
    public static final String CHECKING = "Checking the repair. Keep the items in your inventory until it finishes.";
    public static final String PRICES_CHANGED = "Item prices changed. Open the desk again to see the new list. Nothing was taken.";
    public static final String ITEMS_CHANGED = "Your items changed before the repair started. Nothing was taken.";
    public static final String HOME_CHANGED = "The repair could not finish because the home changed. Your items are coming back.";
    public static final String MAKE_ROOM = "Make room in your inventory or ender chest, then type /property repair returns.";
    public static final String NO_RETURNS = "No items are waiting for you.";
    public static final String DESK_NOTE = "Item repairs take plain items from your inventory. No money moves.";
    public static final String ITEMS_TO_COLLECT = "Items to collect: /property repair returns";
    public static final String USAGE = "Usage: /property repair <incident-id> items";
    public static final String NOT_HERE = "Stand in your enrolled property to repair it.";
    public static final String NO_SUCH_PROBLEM = "That problem is not open at this home.";
    public static final String CASH_WAITING = "A cash repair for this problem is already being checked. Nothing was taken.";

    public static String title(long incidentId) { return "Repair #" + incidentId; }

    public static String body(String typeLabel, long cashCents, String list) {
        return typeLabel + " at this home. Pay " + Money.format(cashCents) + " in cash, or hand in these items: " + list + ".";
    }

    public static String payCash(long cashCents) { return "Pay " + Money.format(cashCents) + " in cash"; }
    public static String missing(String list) { return "You are missing: " + list + "."; }
    public static String done(String list, UUID receipt) {
        return "Repair done. You handed in " + list + ". No money moved. Receipt: " + receipt + ".";
    }
    public static String returned(int quantity, String item) { return "Returned: " + quantity + " " + item + "."; }
    public static String held(UUID reference) {
        return "The item hand-in needs a staff check. Do not try again. Reference: " + reference + ".";
    }
    public static String staffAlert(UUID reference) { return "Staff alert: Item repair held for review. Reference " + reference + "."; }

    /** The choice screen and Bedrock form body, in order. */
    public static List<String> choiceLines(String typeLabel, long cashCents, String list, boolean available) {
        String first = available ? body(typeLabel, cashCents, list)
                : typeLabel + " at this home. Pay " + Money.format(cashCents) + " in cash. Item repair is not available right now.";
        return List.of(first, FROM_INVENTORY, PLAIN_ONLY, NO_FUNDING);
    }

    /** Every fixed line, for the copy check. */
    public static List<String> fixedLines() {
        return List.of(HAND_IN, HAND_IN_UNAVAILABLE, BACK, CLOSE, FROM_INVENTORY, PLAIN_ONLY, NO_FUNDING, OFF, UNAVAILABLE,
                SURVIVAL_ONLY, CLOSE_INVENTORY, HOME_BUSY, ALREADY_WAITING, RETURNS_FIRST, CHECKING, PRICES_CHANGED,
                ITEMS_CHANGED, HOME_CHANGED, MAKE_ROOM, NO_RETURNS, DESK_NOTE, ITEMS_TO_COLLECT, USAGE, NOT_HERE,
                NO_SUCH_PROBLEM, CASH_WAITING, UtilityPaymentRuntime.ITEMS_PENDING_REPLY);
    }
}
