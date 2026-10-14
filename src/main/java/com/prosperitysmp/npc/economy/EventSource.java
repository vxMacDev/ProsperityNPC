package com.prosperitysmp.npc.economy;

/**
 * The economy-event source keys (§4) and their direction. TO_PUBLIC events are
 * paid by CentralBank to a player (faucets: rent, appreciation) and are subject
 * to the daily caps + funding invariant. FROM_PUBLIC events are a player paying
 * CentralBank (sinks: utilities, upgrades, repairs, insurance, eviction) and are
 * not capped — they can only ever reduce net liquidity.
 */
public enum EventSource {
    RENT("npc-property-rent", Direction.TO_PUBLIC),
    APPRECIATION("npc-property-appreciation", Direction.TO_PUBLIC),
    UTILITY("npc-property-utility", Direction.FROM_PUBLIC),
    UPGRADE("npc-property-upgrade", Direction.FROM_PUBLIC),
    REPAIR("npc-property-repair", Direction.FROM_PUBLIC),
    INSURANCE("npc-property-insurance", Direction.FROM_PUBLIC),
    EVICTION("npc-property-eviction", Direction.FROM_PUBLIC);

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

    EventSource(String key, Direction direction) {
        this.key = key;
        this.direction = direction;
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

    public boolean isAppreciation() {
        return this == APPRECIATION;
    }
}
