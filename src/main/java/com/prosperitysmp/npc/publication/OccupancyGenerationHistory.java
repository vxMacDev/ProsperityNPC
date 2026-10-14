package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.AtomicOccupancyArtifacts.Receipt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Immutable, private selection metadata: the current generation plus two predecessors. It touches no
 * files and grants no authorization: its wall-time eligibility check alone is not a cache, lease or HTTP
 * authorization check. The caller owns the authorization generation, replaces it on every
 * configuration change and restart, and commits the metadata atomically before treating a proposed
 * history as published. Retention protection is not permission to serve a predecessor, and expiry never
 * falls back to one. This value cannot remember clock observations or revoke what it has handed out, so
 * the runtime must also apply the refresh pump's monotonic expiry and clock-rollback gate on every serve.
 */
public final class OccupancyGenerationHistory {
    public record Entry(Receipt receipt, Instant selectedAt, Instant expiresAt) { }
    private final UUID authorizationGeneration;
    private final List<Entry> entries;

    private OccupancyGenerationHistory(UUID authorizationGeneration, List<Entry> entries) {
        this.authorizationGeneration = Objects.requireNonNull(authorizationGeneration);
        if (entries == null || entries.size() > 3) throw invalid();
        var names = new HashSet<String>();
        Entry newer = null;
        for (Entry entry : entries) {
            if (entry == null || entry.receipt() == null) throw invalid();
            Receipt r = entry.receipt();
            validTime(r.capturedAt());
            validTime(entry.selectedAt());
            validTime(entry.expiresAt());
            if (r.sha256() == null || !r.sha256().matches("[0-9a-f]{64}")
                    || !(r.sha256() + ".json").equals(r.name()) || r.bytes() < 1 || r.bytes() > 128 * 1024
                    || r.capturedAt().getNano() % 1_000_000 != 0 || !names.add(r.name())
                    || entry.selectedAt().isBefore(r.capturedAt()) || !entry.expiresAt().isAfter(entry.selectedAt())
                    || (newer != null && (!r.capturedAt().isBefore(newer.receipt().capturedAt())
                        || entry.selectedAt().isAfter(newer.selectedAt())))) throw invalid();
            newer = entry;
        }
        this.entries = List.copyOf(entries);
    }

    /** Codec-only structural restoration; callers still verify referenced artifact bytes and live authorization. */
    static OccupancyGenerationHistory restore(UUID authorizationGeneration, List<Entry> entries) {
        return new OccupancyGenerationHistory(authorizationGeneration, entries);
    }

    public static OccupancyGenerationHistory empty(UUID authorizationGeneration) {
        return new OccupancyGenerationHistory(authorizationGeneration, List.of());
    }

    /** Validate the receipt against actual public bytes; a receipt alone is not publication evidence. */
    public OccupancyGenerationHistory advance(UUID expectedAuthorization, PublicOccupancySnapshot snapshot,
                                              Receipt receipt, Duration maxAge, Instant now) {
        authorized(expectedAuthorization);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(receipt);
        validTime(now);
        if (snapshot.isEmpty() || maxAge == null || maxAge.isNegative() || maxAge.isZero())
            throw invalid();
        byte[] payload = snapshot.json().getBytes(StandardCharsets.UTF_8);
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (!hash.equals(receipt.sha256()) || !(hash + ".json").equals(receipt.name())
                || payload.length != receipt.bytes() || !snapshot.capturedAt().equals(receipt.capturedAt()))
            throw invalid();
        Instant expires;
        try {
            expires = snapshot.capturedAt().plus(maxAge);
            validTime(expires);
        }
        catch (RuntimeException invalid) { throw invalid(); }
        if (snapshot.capturedAt().isAfter(now) || !now.isBefore(expires)) throw invalid();
        if (!entries.isEmpty()) {
            Entry current = entries.getFirst();
            if (now.isBefore(current.selectedAt()) || snapshot.capturedAt().isBefore(current.receipt().capturedAt()))
                throw invalid();
            if (receipt.equals(current.receipt())) {
                // A retry does not renew expiry or turn changed policy into an implicit reauthorization.
                if (!expires.equals(current.expiresAt())) throw invalid();
                return this;
            }
            // Same capture instant but different content has no ordered source revision; hold it.
            if (snapshot.capturedAt().equals(current.receipt().capturedAt())) throw invalid();
        }
        var next = new ArrayList<Entry>(3);
        next.add(new Entry(receipt, now, expires));
        next.addAll(entries.subList(0, Math.min(entries.size(), 2)));
        return new OccupancyGenerationHistory(authorizationGeneration, next);
    }

    /** Wall-time eligibility only; caller must also enforce live monotonic expiry and revocation. */
    public Optional<Receipt> current(UUID expectedAuthorization, Instant now) {
        authorized(expectedAuthorization);
        validTime(now);
        if (entries.isEmpty()) return Optional.empty();
        Entry current = entries.getFirst();
        return now.isBefore(current.selectedAt()) || !now.isBefore(current.expiresAt())
                ? Optional.empty() : Optional.of(current.receipt());
    }

    /** Private metadata only. Copying it into HTTP output is forbidden. */
    public List<Entry> entries() { return entries; }

    /** Empty means retention must not run; AtomicOccupancyArtifacts.prune already rejects it. */
    public Set<String> protectedNames(UUID expectedAuthorization) {
        authorized(expectedAuthorization);
        var names = new HashSet<String>();
        for (Entry entry : entries) names.add(entry.receipt().name());
        return Set.copyOf(names);
    }

    private void authorized(UUID expected) {
        if (!authorizationGeneration.equals(expected))
            throw new IllegalArgumentException("Occupancy authorization generation changed");
    }
    private static void validTime(Instant time) {
        if (time == null || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid occupancy generation selection"); }
}
