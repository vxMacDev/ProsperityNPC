package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import org.bukkit.Location;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stands in for a third-party property backend until it is bound. Before then, and after it is lost, every read is empty. */
public final class DeferredPropertyBackend implements PropertyBackend {
    private final Deferred<PropertyBackend> deferred;

    public DeferredPropertyBackend(Deferred<PropertyBackend> deferred) {
        this.deferred = Objects.requireNonNull(deferred);
    }

    public Deferred<PropertyBackend> deferred() { return deferred; }

    @Override public String id() { return deferred.id(); }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }

    @Override
    public Capabilities capabilities() {
        return deferred.current().map(PropertyBackend::capabilities).orElseGet(() -> Capabilities.builder().build());
    }

    @Override
    public String label() {
        return deferred.current().map(PropertyBackend::label).orElse("");
    }

    @Override
    public String displayName() {
        return deferred.current().map(PropertyBackend::displayName).orElse(deferred.id());
    }

    @Override
    public boolean available() {
        return deferred.current().map(PropertyBackend::available).orElse(false);
    }

    @Override
    public GuardResult installGuard(OwnershipHooks hooks) {
        return deferred.current().map(b -> b.installGuard(hooks)).orElseGet(() -> GuardResult.failed("not bound"));
    }

    @Override
    public Optional<RegionRef> regionAt(Location at) {
        return deferred.current().flatMap(b -> b.regionAt(at));
    }

    @Override
    public Optional<RegionView> findRegion(String worldName, String regionId) {
        return deferred.current().flatMap(b -> b.findRegion(worldName, regionId));
    }

    @Override
    public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
        return deferred.current().flatMap(b -> b.ownerReadAt(at, owner));
    }

    @Override
    public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
        return deferred.current().flatMap(b -> b.ownerReadRegion(world, regionId, owner));
    }

    @Override
    public Presence observe(UUID world, String regionId) {
        return deferred.current().map(b -> b.observe(world, regionId)).orElse(Presence.UNREADABLE);
    }

    @Override
    public List<String> describe() {
        return deferred.current().map(PropertyBackend::describe)
                .orElseGet(() -> List.of(deferred.id() + ": " + deferred.reason()));
    }
}
