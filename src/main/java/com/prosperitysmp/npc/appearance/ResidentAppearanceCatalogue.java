package com.prosperitysmp.npc.appearance;

import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRoutine;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable spawn-time selections; performs no I/O. The renderer changes a visible avatar's outfit only through
 * {@link OutfitChangePolicy}, behind appearance.outfit-change-while-rendered, and only by spawning a new avatar.
 */
public final class ResidentAppearanceCatalogue {
    public enum Status { REQUESTED, BASE_FALLBACK, MISSING_BASE }
    public record Applied(String assetKey, String citizensKey, SignedResidentSkin skin) {
        public Applied {
            Objects.requireNonNull(assetKey);
            Objects.requireNonNull(citizensKey);
            Objects.requireNonNull(skin);
            if (!ResidentAppearanceCatalogue.citizensKey(assetKey).equals(citizensKey))
                throw new IllegalArgumentException("Mismatched authored skin cache key");
        }
    }

    public record Selection(long revision, String requestedKey, Status status, Optional<Applied> applied) { }
    private record State(long revision, Map<String, SignedResidentSkin> assets) { }

    /** 18 generic occupation keys, the built-in three's 9 and 3 for each of up to 32 cast members. */
    public static final int MAX_UNIVERSE = 3 * ResidentProfile.Occupation.values().length + 9 + 3 * ResidentCast.MAX_RESIDENTS;
    private static final Pattern RESIDENT_KEY =
            Pattern.compile("resident/v1/[a-z0-9][a-z0-9-]{1,31}/(everyday|work|leisure)");

    private final Set<String> allowed;
    private volatile State state = new State(0, Map.of());

    /** The built-in universe: generic occupation keys plus Mara, Ellis and Nico. 27 keys in all. */
    public ResidentAppearanceCatalogue() {
        this(universe(ResidentCast.builtIn()));
    }

    private ResidentAppearanceCatalogue(Set<String> allowed) {
        this.allowed = allowed;
    }

    /**
     * Built once at full startup, after the cast loads and before the skin file is read. The universe is fixed for
     * the life of the process, which keeps Citizens' process-wide skin cache bounded.
     */
    public static ResidentAppearanceCatalogue forCast(ResidentCast cast) {
        return new ResidentAppearanceCatalogue(universe(Objects.requireNonNull(cast)));
    }

    public Set<String> allowedKeys() {
        return allowed;
    }

    public boolean allows(String assetKey) {
        return assetKey != null && allowed.contains(assetKey);
    }

    /** The Citizens cache key for a key in this universe. Refuses any other key. */
    public String cacheKey(String assetKey) {
        if (!allows(assetKey)) throw new IllegalArgumentException("Unsupported authored skin key");
        return citizensKey(assetKey);
    }

    /** Shape of any resident skin key, cast member or not: resident/v1/&lt;key&gt;/&lt;everyday|work|leisure&gt;. */
    public static boolean wellFormedResidentKey(String assetKey) {
        return assetKey != null && RESIDENT_KEY.matcher(assetKey).matches();
    }

    /** Reject the entire replacement before publishing; existing selections remain immutable. */
    public synchronized long replace(Map<String, SignedResidentSkin> replacement) {
        Objects.requireNonNull(replacement);
        if (replacement.size() > allowed.size()) throw new IllegalArgumentException("Too many skin assets");
        var next = Map.copyOf(replacement);
        if (!allowed.containsAll(next.keySet())) throw new IllegalArgumentException("Unsupported authored skin key");
        State before = state;
        long revision = Math.incrementExact(before.revision());
        state = new State(revision, next);
        return revision;
    }

    public Selection forSpawn(ResidentProfile resident, long worldTime) {
        Objects.requireNonNull(resident);
        State captured = state;
        String requested = ResidentRoutine.at(resident, worldTime).outfitKey();
        String base = resident.wardrobe().everyday();
        // A variant alone is not a complete resident appearance.
        if (!captured.assets().containsKey(base)) {
            return new Selection(captured.revision(), requested, Status.MISSING_BASE, Optional.empty());
        }
        String chosen = captured.assets().containsKey(requested) ? requested : base;
        Status status = chosen.equals(requested) ? Status.REQUESTED : Status.BASE_FALLBACK;
        return new Selection(captured.revision(), requested, status,
                Optional.of(new Applied(chosen, citizensKey(chosen), captured.assets().get(chosen))));
    }

    /** Whether a verified skin is installed under this key. */
    public boolean installed(String assetKey) {
        return assetKey != null && state.assets().containsKey(assetKey);
    }

    public boolean hasEveryday(ResidentProfile resident) {
        return installed(resident.wardrobe().everyday());
    }

    /** One plain line per resident for /npcadmin appearance and /residentlab profile. Reads the same on Bedrock. */
    public String reportLine(ResidentProfile resident) {
        if (!hasEveryday(resident)) return resident.name() + ": no everyday skin. Avatar held.";
        return resident.name() + ": everyday ready, work " + slot(resident.wardrobe().work())
                + ", leisure " + slot(resident.wardrobe().leisure()) + ".";
    }

    private String slot(String key) {
        return installed(key) ? "ready" : "missing (wears everyday)";
    }

    public long revision() {
        return state.revision();
    }

    public int size() {
        return state.assets().size();
    }

    /** Pure mapping for a well-formed resident key; the universe check is {@link #cacheKey}. */
    public static String citizensKey(String assetKey) {
        if (!wellFormedResidentKey(assetKey)) throw new IllegalArgumentException("Unsupported authored skin key");
        return "cit-pnpc-" + assetKey.replace('/', '-');
    }

    private static Set<String> universe(ResidentCast cast) {
        Set<String> keys = new HashSet<>();
        for (ResidentProfile.Occupation occupation : ResidentProfile.Occupation.values()) {
            String root = "resident/v1/" + occupation.name().toLowerCase(Locale.ROOT);
            keys.add(root + "/everyday");
            keys.add(root + "/work");
            keys.add(root + "/leisure");
        }
        // The built-in three stay allowed even when a file leaves them out: their saved rows still render.
        for (ResidentProfile profile : ResidentCatalogue.betaCast()) add(keys, profile);
        for (ResidentProfile profile : cast.profiles()) add(keys, profile);
        if (keys.size() > MAX_UNIVERSE || !keys.stream().allMatch(ResidentAppearanceCatalogue::wellFormedResidentKey))
            throw new IllegalStateException("Invalid skin key universe");
        if (keys.stream().map(key -> key.replace('/', '-')).distinct().count() != keys.size()) {
            throw new IllegalStateException("Citizens asset-key collision");
        }
        return Set.copyOf(keys);
    }

    private static void add(Set<String> keys, ResidentProfile profile) {
        keys.add(profile.wardrobe().everyday());
        keys.add(profile.wardrobe().work());
        keys.add(profile.wardrobe().leisure());
    }
}
