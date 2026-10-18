package com.prosperitysmp.npc.config;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable snapshot of the "safe cash envelope" limits, all held as integer
 * cents. Loaded from config.yml under {@code economy}. Rent and appreciation
 * draw from the same limits; this class does not perform allocation, it only
 * carries the validated constants.
 */
public final class EconomyLimits {

    private final long serverDailyCapCents;
    private final long benefitUnitDailyCapCents;
    private final long propertyDailyCapCents;
    private final long tenantDailyCapCents;
    private final long appreciationDailyCapCents;
    private final long centralBankFloorCents;
    private final long propertySeedCents;
    private final long rolloutSeedPoolCents;
    private final double lifetimeSinkFundingRatio;

    private EconomyLimits(long serverDailyCapCents, long benefitUnitDailyCapCents,
                          long propertyDailyCapCents, long tenantDailyCapCents,
                          long appreciationDailyCapCents, long centralBankFloorCents,
                          long propertySeedCents, long rolloutSeedPoolCents,
                          double lifetimeSinkFundingRatio) {
        this.serverDailyCapCents = serverDailyCapCents;
        this.benefitUnitDailyCapCents = benefitUnitDailyCapCents;
        this.propertyDailyCapCents = propertyDailyCapCents;
        this.tenantDailyCapCents = tenantDailyCapCents;
        this.appreciationDailyCapCents = appreciationDailyCapCents;
        this.centralBankFloorCents = centralBankFloorCents;
        this.propertySeedCents = propertySeedCents;
        this.rolloutSeedPoolCents = rolloutSeedPoolCents;
        this.lifetimeSinkFundingRatio = lifetimeSinkFundingRatio;
    }

    /** Loads and validates the limits from the plugin's config.yml. */
    public static EconomyLimits load(ProsperityNpcPlugin plugin) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("economy");
        if (s == null) {
            throw new IllegalStateException("missing 'economy' section in config.yml");
        }
        EconomyLimits limits = new EconomyLimits(
                s.getLong("server-daily-cap-cents", -1L),
                s.getLong("benefit-unit-daily-cap-cents", -1L),
                s.getLong("property-daily-cap-cents", -1L),
                s.getLong("tenant-daily-cap-cents", -1L),
                s.getLong("appreciation-daily-cap-cents", -1L),
                s.getLong("centralbank-floor-cents", -1L),
                s.getLong("property-seed-cents", -1L),
                s.getLong("rollout-seed-pool-cents", -1L),
                s.getDouble("lifetime-sink-funding-ratio", -1.0));
        limits.validate();
        return limits;
    }

    /** Fail-closed defaults: every cap is zero and the reserve floor is maximal. */
    public static EconomyLimits disabledDefaults() {
        return new EconomyLimits(0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L, 0L, 0.0);
    }

    private void validate() {
        requireNonNegative("server-daily-cap-cents", serverDailyCapCents);
        requireNonNegative("benefit-unit-daily-cap-cents", benefitUnitDailyCapCents);
        requireNonNegative("property-daily-cap-cents", propertyDailyCapCents);
        requireNonNegative("tenant-daily-cap-cents", tenantDailyCapCents);
        requireNonNegative("appreciation-daily-cap-cents", appreciationDailyCapCents);
        requireNonNegative("centralbank-floor-cents", centralBankFloorCents);
        requireNonNegative("property-seed-cents", propertySeedCents);
        requireNonNegative("rollout-seed-pool-cents", rolloutSeedPoolCents);
        if (!(lifetimeSinkFundingRatio >= 0.0 && lifetimeSinkFundingRatio <= 1.0)) {
            throw new IllegalStateException(
                    "lifetime-sink-funding-ratio must be within [0,1], got " + lifetimeSinkFundingRatio);
        }
    }

    private static void requireNonNegative(String key, long value) {
        if (value < 0L) {
            throw new IllegalStateException(key + " must be a non-negative integer (cents), got " + value);
        }
    }

    public long serverDailyCapCents() {
        return serverDailyCapCents;
    }

    public long benefitUnitDailyCapCents() {
        return benefitUnitDailyCapCents;
    }

    public long propertyDailyCapCents() {
        return propertyDailyCapCents;
    }

    public long tenantDailyCapCents() {
        return tenantDailyCapCents;
    }

    public long appreciationDailyCapCents() {
        return appreciationDailyCapCents;
    }

    public long centralBankFloorCents() {
        return centralBankFloorCents;
    }

    public long propertySeedCents() {
        return propertySeedCents;
    }

    public long rolloutSeedPoolCents() {
        return rolloutSeedPoolCents;
    }

    public double lifetimeSinkFundingRatio() {
        return lifetimeSinkFundingRatio;
    }
}
