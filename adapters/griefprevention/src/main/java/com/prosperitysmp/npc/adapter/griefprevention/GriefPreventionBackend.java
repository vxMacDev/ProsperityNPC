package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardBinding;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The property backend for GriefPrevention claims (id {@code griefprevention}). The home is a claim or a
 * subdivision; its region id is the claim id; the paying owner is {@code getOwnerID()}, which a subdivision takes
 * from its parent. An admin claim has no owner and is never paid.
 *
 * <p>Guard: veto events, but only without RealEstate. A transfer of a claim ProsperityNPC holds is cancelled at
 * HIGHEST. With RealEstate installed the guard is none and nothing is ever cancelled, because RealEstate takes the
 * buyer's money first and then ignores a cancel. Either way every applied transfer, deletion and change moves the
 * fence on for the claim and all its subdivisions.
 */
public final class GriefPreventionBackend implements PropertyBackend {
    public static final String ID = "griefprevention";
    public static final String VERIFICATION =
            "lab-recorded on GriefPrevention 16.18.7 (limited scope); not release-verified";

    private final GriefPreventionView view;
    private final Consumer<String> log;
    private OwnershipHooks hooks;
    private GuardMode mode;
    private volatile boolean guardChanged;

    /** @param log one line per held transfer, for staff */
    public GriefPreventionBackend(GriefPreventionView view, Consumer<String> log) {
        this.view = Objects.requireNonNull(view);
        this.log = Objects.requireNonNull(log);
    }

    /** One owner read: the same incarnation only for the same live claim object and owner. */
    record Token(UUID worldId, String regionId, UUID ownerId, Object claim) implements OwnershipToken {
        @Override
        public boolean sameIncarnation(OwnershipToken other) {
            return other instanceof Token t && worldId.equals(t.worldId) && regionId.equals(t.regionId)
                    && ownerId.equals(t.ownerId) && claim == t.claim;
        }
    }

    private record At(UUID worldId, String regionId) implements RegionRef { }

    @Override public String id() { return ID; }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
    @Override public String label() { return "unverified"; }
    @Override public String displayName() { return "GriefPrevention"; }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
                .value("guard", realEstate() ? "none" : "veto-events")
                .value("owner-model", "the claim owner (a subdivision pays its parent's owner); admin claims never")
                .flag("location-lookup", true)
                .flag("region-by-id", true)
                .flag("distinguishes-missing", true)
                .value("token-identity", "object")
                .flag("sees-owner-round-trip", false)
                .flag("needs-worldguard", false)
                .value("region-id-max-length", "20")
                .flag("main-thread-only", true)
                .build();
    }

    private boolean realEstate() {
        try {
            return view.realEstatePresent();
        } catch (RuntimeException | LinkageError unreadable) {
            return true; // When in doubt, never cancel.
        }
    }

    @Override
    public boolean available() {
        try {
            if (guardChanged || !view.available()) return false;
            if (mode != null) {
                GuardMode current = view.realEstatePresent() ? GuardMode.NONE : GuardMode.VETO_EVENTS;
                if (current != mode) guardChanged = true;
            }
            return !guardChanged;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Override
    public GuardResult installGuard(OwnershipHooks hooks) {
        this.hooks = Objects.requireNonNull(hooks);
        if (!available()) return GuardResult.failed("GriefPrevention is not available");
        mode = realEstate() ? GuardMode.NONE : GuardMode.VETO_EVENTS;
        return GuardResult.installed(mode);
    }

    @Override
    public List<GuardBinding> guardBindings() {
        if (hooks == null) return List.of();
        return view.bindings(new Handlers(hooks));
    }

    /** The guard's reactions. Every body runs inside hooks.guarded, so a vendor error holds payments. */
    private final class Handlers implements GriefPreventionView.Events {
        private final OwnershipHooks hooks;

        Handlers(OwnershipHooks hooks) { this.hooks = hooks; }

        @Override
        public boolean transferRequested(GriefPreventionView.Claim claim) {
            boolean[] cancel = {false};
            hooks.guarded("ClaimTransferEvent", () -> {
                if (mode != GuardMode.VETO_EVENTS || realEstate()) return;
                for (Key key : affected(claim)) if (hooks.isHeld(key.world, key.region)) cancel[0] = true;
                if (cancel[0]) log.accept("ProsperityNPC held a claim transfer on " + claim.id()
                        + ": an ownership operation was in progress.");
            });
            return cancel[0];
        }

        @Override
        public void transferred(GriefPreventionView.Claim claim) {
            hooks.guarded("ClaimTransferEvent", () -> bump(claim));
        }

        @Override
        public void deleted(GriefPreventionView.Claim claim) {
            hooks.guarded("ClaimDeletedEvent", () -> bump(claim));
        }

        @Override
        public void changed(GriefPreventionView.Claim claim) {
            hooks.guarded("ClaimChangeEvent", () -> bump(claim));
        }

        private void bump(GriefPreventionView.Claim claim) {
            for (Key key : affected(claim)) hooks.externalChange(key.world, key.region);
        }
    }

    private record Key(UUID world, String region) { }

    /** The claim and every subdivision under it: subdivisions report their parent's owner. */
    private static List<Key> affected(GriefPreventionView.Claim claim) {
        var out = new ArrayList<Key>();
        out.add(new Key(claim.worldId(), Long.toString(claim.id())));
        for (long child : claim.childIds()) out.add(new Key(claim.worldId(), Long.toString(child)));
        return out;
    }

    @Override
    public Optional<RegionRef> regionAt(Location at) {
        try {
            if (at == null || at.getWorld() == null || !available()) return Optional.empty();
            var claim = view.claimAt(at);
            if (claim == null || !claim.worldId().equals(at.getWorld().getUID())) return Optional.empty();
            return Optional.of(new At(claim.worldId(), Long.toString(claim.id())));
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RegionView> findRegion(String worldName, String regionId) {
        try {
            var claim = read(regionId);
            if (claim == null || !claim.worldName().equals(worldName)) return Optional.empty();
            Optional<UUID> owner = payable(claim.ownerId()) ? Optional.of(claim.ownerId()) : Optional.empty();
            return Optional.of(new RegionView() {
                @Override public UUID worldId() { return claim.worldId(); }
                @Override public String regionId() { return regionId; }
                @Override public String market() { return ID; }
                @Override public String kind() { return "owned"; }
                @Override public Optional<UUID> ownerUuid() { return owner; }
                @Override public boolean matches(String world, String id) { return worldName.equals(world) && regionId.equals(id); }
            });
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
        return regionAt(at).flatMap(ref -> ownerReadRegion(ref.worldId(), ref.regionId(), owner));
    }

    @Override
    public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
        try {
            var claim = read(regionId);
            if (claim == null || world == null || !world.equals(claim.worldId()) || !payable(owner)
                    || !owner.equals(claim.ownerId())) return Optional.empty();
            return Optional.of(new Token(world, regionId, owner, claim.identity()));
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    @Override
    public Presence observe(UUID world, String regionId) {
        try {
            if (world == null || !available()) return Presence.UNREADABLE;
            var claim = read(regionId);
            return claim != null && world.equals(claim.worldId()) ? Presence.PRESENT : Presence.MISSING;
        } catch (RuntimeException | LinkageError failure) {
            return Presence.UNREADABLE;
        }
    }

    @Override
    public List<String> describe() {
        boolean ready = available();
        return List.of("griefprevention: one claim owner per claim; subdivisions pay the parent's owner; "
                        + (realEstate() ? "RealEstate installed, so guard none (payouts held by default)" : "guard veto-events"),
                "GriefPrevention " + (ready ? "available" : guardChanged
                        ? "UNAVAILABLE (RealEstate changed; restart required)" : "UNAVAILABLE") + "; " + VERIFICATION);
    }

    private GriefPreventionView.Claim read(String regionId) {
        if (regionId == null || !available()) return null;
        long id;
        try {
            id = Long.parseLong(regionId);
        } catch (NumberFormatException notAClaim) {
            return null;
        }
        if (id < 0 || !Long.toString(id).equals(regionId)) return null;
        return view.claim(id);
    }

    private static boolean payable(UUID owner) {
        return owner != null && !owner.equals(new UUID(0, 0));
    }
}
