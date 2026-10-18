package com.prosperitysmp.npc.api.contract.broken;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import com.prosperitysmp.npc.api.contract.ContractMeta;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import org.bukkit.Location;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deliberately broken property backends, one per critical case of {@link PropertyBackendContract}. The guard
 * mutants (PB-03 onwards) break the hooks the guard is given, or the fake vendor flow around it, the way a broken
 * adapter would behave. Used only by the contract meta-test, against a subject that supports every guard case.
 */
public enum PropertyBackendMutants implements ContractMeta.Mutant {
    /** The token compares world and region only, so a new owner looks like the same incarnation. A recreated region too. */
    TOKEN_IGNORES_OWNER("PB-02", "PB-16"),
    /** A missing region is reported as present. */
    MISSING_AS_PRESENT("PB-12"),
    /** The veto is never applied: the guard never sees a held region. Off-main vetoes fail with it. */
    VETO_NEVER_APPLIED("PB-03", "PB-10"),
    /** No bump reaches ProsperityNPC. Every case that counts bumps fails with it. */
    NO_BUMP("PB-04", "PB-06", "PB-13", "PB-14", "PB-15"),
    /** A location read's token ignores the owner. */
    LOCATION_TOKEN_IGNORES_OWNER("PB-05"),
    /** A reason that must never be cancelled is cancelled while the region is held. */
    CANCELS_NEVER_CANCELLED("PB-06"),
    /** An off-main event calls externalChange instead of markDirty. */
    EXTERNAL_CHANGE_OFF_MAIN("PB-10"),
    /** A handler's LinkageError is swallowed and never reported. */
    ERROR_SWALLOWED("PB-11"),
    /** The post event's bump is missing when a later subscriber overwrote the veto. */
    POST_HANDLER_MISSING("PB-13"),
    /** One event bumps only the first region it affects. */
    BUMPS_FIRST_KEY_ONLY("PB-15"),
    /** The token drops the region's incarnation: world, region and owner only. Selling and buying back fails too. */
    TOKEN_DROPS_INCARNATION("PB-16", "PB-02");

    private final String caseId;
    private final Set<String> alsoBreaks;

    PropertyBackendMutants(String caseId, String... alsoBreaks) {
        this.caseId = caseId;
        this.alsoBreaks = Set.of(alsoBreaks);
    }

    @Override public String caseId() { return caseId; }

    @Override public Set<String> alsoBreaks() { return alsoBreaks; }

    @Override
    public Object mutate(Object subject) {
        if (!(subject instanceof PropertyBackendContract.Subject good)) return subject;
        PropertyBackend broken = new Forwarding(good.backend()) {
            @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
                var token = inner.ownerReadRegion(world, regionId, owner);
                if (PropertyBackendMutants.this == TOKEN_IGNORES_OWNER) return token.map(t -> blind(t, false));
                if (PropertyBackendMutants.this == TOKEN_DROPS_INCARNATION) return token.map(t -> blind(t, true));
                return token;
            }
            @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
                var token = inner.ownerReadAt(at, owner);
                return PropertyBackendMutants.this == LOCATION_TOKEN_IGNORES_OWNER ? token.map(t -> blind(t, false)) : token;
            }
            @Override public Presence observe(UUID world, String regionId) {
                Presence presence = inner.observe(world, regionId);
                return PropertyBackendMutants.this == MISSING_AS_PRESENT && presence == Presence.MISSING ? Presence.PRESENT : presence;
            }
        };
        var controls = good.guard() == null ? null : new BrokenControls(good.guard(), good.world());
        return Proxy.newProxyInstance(PropertyBackendContract.Subject.class.getClassLoader(),
                new Class<?>[]{PropertyBackendContract.Subject.class}, (self, method, args) -> switch (method.getName()) {
                    case "backend" -> broken;
                    case "guard" -> controls;
                    default -> method.invoke(good, args);
                });
    }

    /** A token that compares world, region and, when {@code withOwner}, the owner; never the live objects. */
    private static OwnershipToken blind(OwnershipToken token, boolean withOwner) {
        return new OwnershipToken() {
            @Override public UUID worldId() { return token.worldId(); }
            @Override public String regionId() { return token.regionId(); }
            @Override public UUID ownerId() { return token.ownerId(); }
            @Override public boolean sameIncarnation(OwnershipToken other) {
                return other != null && token.worldId().equals(other.worldId()) && token.regionId().equals(other.regionId())
                        && (!withOwner || token.ownerId().equals(other.ownerId()));
            }
        };
    }

    /** The hooks a broken guard effectively has. */
    private final class BrokenHooks implements OwnershipHooks {
        final OwnershipHooks inner;
        volatile boolean muted;
        volatile String firstKey;
        volatile boolean tracking;

        BrokenHooks(OwnershipHooks inner) { this.inner = inner; }

        private boolean drop(UUID world, String region) {
            if (muted || PropertyBackendMutants.this == NO_BUMP) return true;
            if (PropertyBackendMutants.this == BUMPS_FIRST_KEY_ONLY && tracking) {
                String key = world + "/" + region;
                synchronized (this) {
                    if (firstKey == null) firstKey = key;
                    return !firstKey.equals(key);
                }
            }
            return false;
        }

        @Override public Runnable beginWrite(UUID world, String regionId) { return inner.beginWrite(world, regionId); }

        @Override public void externalChange(UUID world, String regionId) {
            if (!drop(world, regionId)) inner.externalChange(world, regionId);
        }

        @Override public void markDirty(UUID world, String regionId) {
            if (drop(world, regionId)) return;
            if (PropertyBackendMutants.this == EXTERNAL_CHANGE_OFF_MAIN) inner.externalChange(world, regionId);
            else inner.markDirty(world, regionId);
        }

        @Override public boolean isHeld(UUID world, String regionId) {
            return PropertyBackendMutants.this != VETO_NEVER_APPLIED && inner.isHeld(world, regionId);
        }

        @Override public void guarded(String what, Runnable body) {
            if (PropertyBackendMutants.this != ERROR_SWALLOWED) {
                inner.guarded(what, body);
                return;
            }
            try {
                body.run();
            } catch (Throwable swallowed) {
                // Deliberately lost.
            }
        }
    }

    /** The fake vendor flow around a broken guard. */
    private final class BrokenControls implements PropertyBackendContract.GuardControls {
        final PropertyBackendContract.GuardControls inner;
        final UUID world;
        BrokenHooks hooks;

        BrokenControls(PropertyBackendContract.GuardControls inner, UUID world) {
            this.inner = inner;
            this.world = world;
        }

        @Override public GuardResult install(OwnershipHooks real) {
            hooks = new BrokenHooks(real);
            return inner.install(hooks);
        }

        @Override public void uninstall() { inner.uninstall(); }

        @Override public boolean changeOwner(String region, UUID owner, String reason) {
            if (PropertyBackendMutants.this == CANCELS_NEVER_CANCELLED && inner.neverCancelled().contains(reason)
                    && hooks != null && hooks.inner.isHeld(world, region)) return false;
            if (hooks == null) return inner.changeOwner(region, owner, reason);
            synchronized (hooks) { hooks.firstKey = null; }
            hooks.tracking = true;
            try {
                return inner.changeOwner(region, owner, reason);
            } finally {
                hooks.tracking = false;
            }
        }

        @Override public void changeOwnerCancelledElsewhere(String region, UUID owner) { inner.changeOwnerCancelledElsewhere(region, owner); }

        @Override public List<String> neverCancelled() { return inner.neverCancelled(); }

        @Override public boolean eventsOffMain() { return inner.eventsOffMain(); }

        @Override public boolean supportsOverride() { return inner.supportsOverride(); }

        @Override public void changeOwnerOverridden(String region, UUID owner) {
            boolean mute = PropertyBackendMutants.this == POST_HANDLER_MISSING && hooks != null;
            if (mute) hooks.muted = true;
            try {
                inner.changeOwnerOverridden(region, owner);
            } finally {
                if (mute) hooks.muted = false;
            }
        }

        @Override public List<String> affectedWith(String region) { return inner.affectedWith(region); }

        @Override public void recreate(String region, UUID owner) { inner.recreate(region, owner); }

        @Override public void breakLinkage() { inner.breakLinkage(); }
    }

    /** A backend that forwards everything to the working one. */
    private static class Forwarding implements PropertyBackend {
        final PropertyBackend inner;
        Forwarding(PropertyBackend inner) { this.inner = inner; }
        @Override public String id() { return inner.id(); }
        @Override public int apiMajor() { return inner.apiMajor(); }
        @Override public int apiMinor() { return inner.apiMinor(); }
        @Override public Capabilities capabilities() { return inner.capabilities(); }
        @Override public String label() { return inner.label(); }
        @Override public String displayName() { return inner.displayName(); }
        @Override public boolean available() { return inner.available(); }
        @Override public GuardResult installGuard(OwnershipHooks hooks) { return inner.installGuard(hooks); }
        @Override public java.util.List<com.prosperitysmp.npc.api.GuardBinding> guardBindings() { return inner.guardBindings(); }
        @Override public Optional<RegionRef> regionAt(Location at) { return inner.regionAt(at); }
        @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return inner.findRegion(worldName, regionId); }
        @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) { return inner.ownerReadAt(at, owner); }
        @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
            return inner.ownerReadRegion(world, regionId, owner);
        }
        @Override public Presence observe(UUID world, String regionId) { return inner.observe(world, regionId); }
        @Override public List<String> describe() { return inner.describe(); }
    }
}
