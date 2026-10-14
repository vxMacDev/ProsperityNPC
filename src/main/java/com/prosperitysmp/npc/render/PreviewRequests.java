package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.resident.ResidentProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Main-thread, bounded admission for asynchronous staff previews; no Bukkit or database access. */
final class PreviewRequests {
    static final int LIMIT = 3;
    static final long TIMEOUT_MS = 10_000L;
    enum Admission { ACCEPTED, DUPLICATE, FULL }
    enum Eligibility { READY, REVOKED, EXPIRED, MOVED }
    record Request(UUID owner, ResidentProfile profile, UUID world, RenderPolicy.Vec origin,
                   String district, long expiresMs) {
        Request {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(world);
            Objects.requireNonNull(origin);
            if (district != null && !district.matches("[a-zA-Z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid district");
        }
    }
    private final Map<UUID, Request> pending = new LinkedHashMap<>();

    Admission offer(Request request, Set<UUID> activeResidents, int activePreviews, int serverCap) {
        if (activeResidents.contains(request.profile().id()) || pending.values().stream()
                .anyMatch(other -> !other.owner().equals(request.owner())
                        && other.profile().id().equals(request.profile().id()))) return Admission.DUPLICATE;
        int otherPending = pending.size() - (pending.containsKey(request.owner()) ? 1 : 0);
        if (otherPending + activePreviews >= LIMIT || otherPending + activeResidents.size() >= serverCap)
            return Admission.FULL;
        pending.put(request.owner(), request);
        return Admission.ACCEPTED;
    }

    static Eligibility eligible(Request request, long now, boolean allowed, UUID world, RenderPolicy.Vec position) {
        if (!allowed) return Eligibility.REVOKED;
        if (now >= request.expiresMs()) return Eligibility.EXPIRED;
        if (!request.world().equals(world) || position == null) return Eligibility.MOVED;
        RenderPolicy.Vec start = request.origin();
        double distance = Math.pow(start.x() - position.x(), 2) + Math.pow(start.y() - position.y(), 2)
                + Math.pow(start.z() - position.z(), 2);
        return Double.isFinite(distance) && distance <= 1 ? Eligibility.READY : Eligibility.MOVED;
    }

    List<Request> snapshot() { return List.copyOf(pending.values()); }
    boolean remove(Request request) {
        if (pending.get(request.owner()) != request) return false;
        pending.remove(request.owner());
        return true;
    }
    void cancel(UUID owner) { pending.remove(owner); }
    void clear() { pending.clear(); }
    boolean isEmpty() { return pending.isEmpty(); }
}
