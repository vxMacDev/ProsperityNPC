package com.prosperitysmp.npc.publication;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** Explicit staging policy; no credential or sending authority is inferred from configuration. */
public final class ChronicleSettings {
    private ChronicleSettings() { }

    public static ChronicleRuntime.Settings read(ConfigurationSection section, ConfigurationSection publication) {
        if (section == null || Boolean.FALSE.equals(section.get("enabled"))) return ChronicleRuntime.Settings.disabled();
        if (!Boolean.TRUE.equals(section.get("enabled"))
                || !Set.of("enabled", "authorization-generation", "refresh-seconds", "receipt-revision-evidence")
                .containsAll(section.getKeys(false)) || !section.getKeys(false).containsAll(
                Set.of("enabled", "authorization-generation", "refresh-seconds")))
            throw new IllegalArgumentException("Invalid Chronicle settings");
        Object raw = section.get("authorization-generation"), interval = section.get("refresh-seconds");
        if (!(raw instanceof String text) || !(interval instanceof Integer || interval instanceof Long))
            throw new IllegalArgumentException("Invalid Chronicle generation or interval");
        Object evidence = section.get("receipt-revision-evidence");
        if (evidence != null && !(evidence instanceof Boolean))
            throw new IllegalArgumentException("Invalid Chronicle receipt-revision-evidence");
        UUID generation = UUID.fromString(text);
        if (!generation.toString().equals(text)) throw new IllegalArgumentException("Canonical generation required");
        var scopes = OccupancyPublicationSettings.read(publication).orElseThrow(
                () -> new IllegalArgumentException("Explicit publication scopes required"));
        return new ChronicleRuntime.Settings(true, generation, scopes.approvals(),
                Duration.ofSeconds(((Number) interval).longValue()), Boolean.TRUE.equals(evidence));
    }
}
