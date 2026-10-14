package com.prosperitysmp.npc.resident;

import java.util.Objects;
import java.util.UUID;

/** Persistent character definition. Appearance keys are intentions, not installed skin assets. */
public record ResidentProfile(UUID id, String name, String household, int generation,
                              Occupation occupation, Means means, LifeStage lifeStage,
                              Temperament temperament, Preference preference, Wardrobe wardrobe) {
    public enum Occupation { RESTORER, SHOPKEEPER, GARDENER, COURIER, COOK, CARPENTER }
    public enum Means { STARTING_OUT, STEADY, ESTABLISHED }
    public enum LifeStage { YOUNG_ADULT, ADULT, OLDER_ADULT }
    public enum Temperament { RESERVED, WARM, DIRECT, INQUISITIVE }
    public enum Preference { WELL_KEPT_HOME, CLEAR_TERMS, QUIET_ROUTINE, NEIGHBORHOOD_LIFE }
    public record Wardrobe(String everyday, String work, String leisure) {
        public Wardrobe {
            checkKey(everyday);
            checkKey(work);
            checkKey(leisure);
        }
        private static void checkKey(String key) {
            if (key == null || !key.matches("[a-z0-9][a-z0-9/_-]{0,63}")) {
                throw new IllegalArgumentException("Invalid outfit asset key");
            }
        }
    }

    public ResidentProfile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(occupation);
        Objects.requireNonNull(means);
        Objects.requireNonNull(lifeStage);
        Objects.requireNonNull(temperament);
        Objects.requireNonNull(preference);
        Objects.requireNonNull(wardrobe);
        checkText(name, "name");
        checkText(household, "household");
        if (generation < 1 || generation > 8) throw new IllegalArgumentException("Invalid generation");
    }

    private static void checkText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 64
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7)) {
            throw new IllegalArgumentException("Invalid resident " + field);
        }
    }
}
