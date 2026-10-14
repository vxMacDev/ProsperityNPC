package com.prosperitysmp.npc.resident;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The named residents this server runs with, read once at full startup. By default these are the built-in three:
 * Mara Vale, Ellis Ward and Nico Reed. With residents.cast-file-enabled on, the cast comes from residents.yml. It is
 * never empty and never half loaded: a rejected file gives the built-in three with source FILE_REJECTED. Immutable,
 * and passed in by constructor rather than held in a static.
 */
public record ResidentCast(List<ResidentProfile> profiles, Map<UUID, ResidentLines> lines, Source source) {
    public enum Source { BUILT_IN, FILE, FILE_REJECTED }

    public static final int MAX_RESIDENTS = 32;

    public ResidentCast {
        profiles = List.copyOf(profiles);
        lines = Map.copyOf(lines);
        Objects.requireNonNull(source);
        if (profiles.isEmpty() || profiles.size() > MAX_RESIDENTS) throw new IllegalArgumentException("Invalid cast size");
        Set<UUID> ids = new HashSet<>();
        Set<String> first = new HashSet<>();
        Set<String> full = new HashSet<>();
        for (ResidentProfile profile : profiles) {
            if (!ids.add(profile.id()) || !first.add(firstName(profile)) || !full.add(profile.name()))
                throw new IllegalArgumentException("Duplicate resident in cast");
        }
        if (!ids.containsAll(lines.keySet())) throw new IllegalArgumentException("Lines for a resident outside the cast");
    }

    /** Mara Vale, Ellis Ward and Nico Reed with the code's own lines. What every server runs with the flag off. */
    public static ResidentCast builtIn() {
        return new ResidentCast(ResidentCatalogue.betaCast(), Map.of(), Source.BUILT_IN);
    }

    /** The built-in three, marked so health can say residents.yml was refused. */
    public static ResidentCast rejected() {
        return new ResidentCast(ResidentCatalogue.betaCast(), Map.of(), Source.FILE_REJECTED);
    }

    /** Matches the first word of the name, ignoring case (equalsIgnoreCase). */
    public Optional<ResidentProfile> byFirstName(String name) {
        if (name == null) return Optional.empty();
        return profiles.stream().filter(p -> p.name().split(" ", 2)[0].equalsIgnoreCase(name)).findFirst();
    }

    public Optional<ResidentProfile> byKey(String key) {
        if (key == null) return Optional.empty();
        UUID id = ResidentCatalogue.idFor(key);
        return byId(id);
    }

    public Optional<ResidentProfile> byId(UUID id) {
        return profiles.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public boolean contains(UUID id) { return byId(id).isPresent(); }

    /** Lowercase first names in cast order, for tab-complete. */
    public List<String> firstNames() { return profiles.stream().map(ResidentCast::firstName).toList(); }

    /** Authored lines, or {@link ResidentLines#NONE} so the code lines are used. */
    public ResidentLines lines(UUID id) { return lines.getOrDefault(id, ResidentLines.NONE); }

    /** The single /npcadmin health line for the cast. Never includes file contents. */
    public String healthLine() {
        return switch (source) {
            case BUILT_IN -> "Residents: built-in three. residents.yml is off.";
            case FILE -> "Residents: " + profiles.size() + " from residents.yml";
            case FILE_REJECTED -> "Residents: residents.yml was rejected. Using the built-in three.";
        };
    }

    static String firstName(ResidentProfile profile) {
        return profile.name().split(" ", 2)[0].toLowerCase(Locale.ROOT);
    }
}
