package com.prosperitysmp.npc.resident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import static com.prosperitysmp.npc.resident.ResidentProfile.*;

/** Versioned, deterministic authoring defaults. Persist the chosen profile; never reroll at login. */
public final class ResidentCatalogue {
    public static final int VERSION = 1;
    private static final String[] FIRST = {"Mara", "Ellis", "Nico", "Ada", "Rowan", "Jules", "Theo", "Iris",
            "Robin", "Sasha", "Cleo", "Leon", "Wren", "Tessa", "Morgan", "Finn"};
    private static final String[] HOUSEHOLDS = {"Vale", "Ward", "Reed", "Ashby", "Bell", "Hale", "Mercer", "Linden"};

    private ResidentCatalogue() { }

    /** Surnames given to generated tenants. A residents.yml example household must not reuse one. */
    static List<String> generatedHouseholds() { return List.of(HOUSEHOLDS); }

    /** Existing tenant names are preserved; null is allowed only for a newly authored resident. */
    public static ResidentProfile draft(UUID id, String existingName) {
        int family = pick(id, "household", HOUSEHOLDS.length);
        String name = existingName == null
                ? FIRST[pick(id, "given-name", FIRST.length)] + " " + HOUSEHOLDS[family] : existingName;
        Occupation job = Occupation.values()[pick(id, "occupation", Occupation.values().length)];
        String outfit = "resident/v1/" + job.name().toLowerCase(java.util.Locale.ROOT);
        return new ResidentProfile(id, name, HOUSEHOLDS[family], 1 + pick(id, "generation", 3), job,
                Means.values()[pick(id, "means", Means.values().length)],
                LifeStage.values()[pick(id, "life-stage", LifeStage.values().length)],
                Temperament.values()[pick(id, "temperament", Temperament.values().length)],
                Preference.values()[pick(id, "preference", Preference.values().length)],
                new Wardrobe(outfit + "/everyday", outfit + "/work", outfit + "/leisure"));
    }

    /** Fictional DEV cast; not automatically enrolled or inserted anywhere. */
    public static List<ResidentProfile> betaCast() {
        return List.of(
                authored("mara-vale", "Mara Vale", "Vale", 2, Occupation.RESTORER, Means.STEADY,
                        LifeStage.OLDER_ADULT, Temperament.WARM, Preference.WELL_KEPT_HOME),
                authored("ellis-ward", "Ellis Ward", "Ward", 1, Occupation.SHOPKEEPER, Means.ESTABLISHED,
                        LifeStage.ADULT, Temperament.DIRECT, Preference.CLEAR_TERMS),
                authored("nico-reed", "Nico Reed", "Reed", 1, Occupation.COURIER, Means.STARTING_OUT,
                        LifeStage.YOUNG_ADULT, Temperament.INQUISITIVE, Preference.NEIGHBORHOOD_LIFE));
    }

    /** Stable resident UUID for an authored key. The key is permanent: a renamed key is a new person. */
    public static UUID idFor(String key) {
        return UUID.nameUUIDFromBytes(("prosperitynpc:dev-cast:v1:" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Also used by the residents.yml loader, so a file entry and a built-in entry with one key are one person. */
    static ResidentProfile authored(String key, String name, String household, int generation,
                                    Occupation occupation, Means means, LifeStage age,
                                    Temperament temperament, Preference preference) {
        UUID id = idFor(key);
        String outfit = "resident/v1/" + key;
        return new ResidentProfile(id, name, household, generation, occupation, means, age, temperament, preference,
                new Wardrobe(outfit + "/everyday", outfit + "/work", outfit + "/leisure"));
    }

    private static int pick(UUID id, String dimension, int bound) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    (VERSION + ":" + id + ":" + dimension).getBytes(StandardCharsets.UTF_8));
            int number = ((bytes[0] & 255) << 16) | ((bytes[1] & 255) << 8) | (bytes[2] & 255);
            return number % bound;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM is missing SHA-256", e);
        }
    }
}
