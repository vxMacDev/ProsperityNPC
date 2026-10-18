package com.prosperitysmp.npc.charge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only descriptions of today's six charges in the bill formula language. They are not config and no code path
 * reads them to set a price: the prices stay in their own classes and are pinned by SQL checks. They exist so tests
 * prove the language can express each of today's prices exactly.
 */
public final class PinnedCharges {
    /** One pinned charge: its id, its formula and the range of each value it reads. */
    public record Descriptor(String id, String formula, Map<String, ChargeVariables.Range> variables) {
        public ChargeFormula parsed() {
            return ChargeFormula.parse(formula).bind(Map.of(), variables.keySet());
        }
    }

    private static Map<String, ChargeVariables.Range> ranges(Object... pairs) {
        Map<String, ChargeVariables.Range> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 3)
            m.put((String) pairs[i], new ChargeVariables.Range((Integer) pairs[i + 1], (Integer) pairs[i + 2]));
        return java.util.Collections.unmodifiableMap(m);
    }

    public static final Descriptor UTILITY = new Descriptor("utility-bill-v1",
            "100 + 75 * tenants + 50 * upgrade_levels", ranges("tenants", 0, 4, "upgrade_levels", 0, 6));
    public static final Descriptor UPGRADE_ADVERTISING = new Descriptor("upgrade-advertising-v1",
            "pick(target_level, 10000, 25000, 50000)", ranges("target_level", 1, 3));
    public static final Descriptor UPGRADE_FACILITIES = new Descriptor("upgrade-facilities-v1",
            "pick(target_level, 15000, 35000, 70000)", ranges("target_level", 1, 3));
    public static final Descriptor MAINTAIN = new Descriptor("maintain-v1", "1000", Map.of());
    public static final Descriptor REPAIR_CASH = new Descriptor("repair-cash-v1",
            "pick(incident, 2000, 3500, 5000)", ranges("incident", 1, 3));
    public static final Descriptor INSURANCE = new Descriptor("insurance-v1",
            "1200 + 200 * capacity", ranges("capacity", 1, 4));
    public static final Descriptor EVICTION = new Descriptor("eviction-v1", "1000", Map.of());

    public static final List<Descriptor> ALL = List.of(UTILITY, UPGRADE_ADVERTISING, UPGRADE_FACILITIES, MAINTAIN,
            REPAIR_CASH, INSURANCE, EVICTION);

    private PinnedCharges() { }
}
