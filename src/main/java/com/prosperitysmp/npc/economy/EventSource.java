package com.prosperitysmp.npc.economy;

/**
 * The economy-event source keys and their direction. TO_PUBLIC events are
 * paid by CentralBank to a player (faucets: rent, appreciation) and are subject
 * to the daily caps + funding invariant. FROM_PUBLIC events are a player paying
 * CentralBank (sinks: utilities, upgrades, repairs, insurance, eviction) and are
 * not capped, because they can only ever reduce net liquidity.
 *
 * <p>{@code fundsRent} says whether a paid sink adds rent headroom (the funding ratio of each sink dollar). The five
 * original sinks do; configured bills ({@code BILLS}) do not. Payouts never read it. The constant names are part of
 * a stored hash, so they never change and new constants only go at the end.
 */
public enum EventSource {
    RENT("npc-property-rent", Direction.TO_PUBLIC, false),
    APPRECIATION("npc-property-appreciation", Direction.TO_PUBLIC, false),
    UTILITY("npc-property-utility", Direction.FROM_PUBLIC, true),
    UPGRADE("npc-property-upgrade", Direction.FROM_PUBLIC, true),
    REPAIR("npc-property-repair", Direction.FROM_PUBLIC, true),
    INSURANCE("npc-property-insurance", Direction.FROM_PUBLIC, true),
    EVICTION("npc-property-eviction", Direction.FROM_PUBLIC, true),
    /** Configured bills and taxes (dev.65). Paid to the reserve; never adds rent funding. */
    BILLS("npc-property-bills", Direction.FROM_PUBLIC, false);

    public enum Direction {
        TO_PUBLIC("TRANSFER_TO_PUBLIC"),
        FROM_PUBLIC("TRANSFER_FROM_PUBLIC");

        private final String moneyFlowKey;

        Direction(String moneyFlowKey) {
            this.moneyFlowKey = moneyFlowKey;
        }

        public String moneyFlowKey() {
            return moneyFlowKey;
        }
    }

    private final String key;
    private final Direction direction;
    private final boolean fundsRent;

    EventSource(String key, Direction direction, boolean fundsRent) {
        this.key = key;
        this.direction = direction;
        this.fundsRent = fundsRent;
    }

    public String key() {
        return key;
    }

    public Direction direction() {
        return direction;
    }

    public boolean isPayout() {
        return direction == Direction.TO_PUBLIC;
    }

    /** Whether a paid sink of this source adds rent headroom. Not read for payouts. */
    public boolean fundsRent() {
        return fundsRent;
    }

    public boolean isAppreciation() {
        return this == APPRECIATION;
    }
}
