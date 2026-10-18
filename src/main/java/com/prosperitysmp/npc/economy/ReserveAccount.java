package com.prosperitysmp.npc.economy;

import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;

/**
 * The configured reserve account ({@code centralbank.uuid}) that funds payouts and receives charges.
 * Only a canonical lowercase UUID is accepted: payment checks compare the configured text to the
 * startup reserve exactly, so any other spelling is refused here, at startup, instead of later.
 */
public final class ReserveAccount {
    public static final String PATH = "centralbank.uuid";

    private ReserveAccount() { }

    /** The configured reserve text, read live on every call. Never cached: a reload that changes it must refuse in-flight work. */
    public static String liveText(ConfigurationSection config) { return config.getString(PATH, ""); }

    /** Throws IllegalArgumentException when blank or not canonical; callers treat that as payments held. */
    public static UUID fromConfig(ConfigurationSection config) {
        String text = liveText(config);
        if (!isCanonical(text)) throw new IllegalArgumentException(PATH + " must be a canonical lowercase UUID");
        return UUID.fromString(text);
    }

    public static boolean isCanonical(String text) {
        if (text == null || text.length() != 36) return false;
        try {
            return UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
