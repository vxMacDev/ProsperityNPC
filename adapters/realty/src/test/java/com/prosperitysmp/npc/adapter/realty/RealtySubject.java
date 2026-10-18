package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import org.bukkit.Location;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The model, the backend and the contract controls in one object, like the GriefPrevention subject. A sold freehold
 * PLOT holds a vacant leasehold UNIT inside it. The backend reads a snapshot, so every silent change in the model
 * ends with {@link #settle}: it moves both clocks past a full refresh, ticks the backend and runs every queued task.
 * Without that, a change that fired no event would stay invisible to the contract.
 */
final class RealtySubject implements PropertyBackendContract.Subject, PropertyBackendContract.GuardControls {
    static final UUID WORLD = new UUID(0, 68001), OWNER = new UUID(0, 68002), TENANT = new UUID(0, 68003);
    static final String WORLD_NAME = "realty-test", PLOT = "plot", UNIT = "unit";

    final RealtyModel model = new RealtyModel();
    final ModelView view = new ModelView(model);
    final List<String> logged = new ArrayList<>();
    final RealtyPropertyBackend backend = new RealtyPropertyBackend(view, logged::add);
    private OwnershipHooks hooks;

    RealtySubject() {
        model.addWorld(WORLD_NAME, WORLD);
        model.addFreehold(WORLD, PLOT, OWNER, null, 0, 0, 20, 20);
        model.addLease(WORLD, UNIT, null, null, PLOT, 5, 5, 9, 9);
        backend.start();
        view.pump();
    }

    /** Lets a refresh happen, as a running server does within the refresh interval. */
    void settle() {
        model.advance(Duration.ofMillis(RegionCache.Tuning.FULL_EVERY + 1000));
        backend.tick();
        view.pump();
    }

    Location at(int x, int z) {
        return view.at(WORLD, x, z);
    }

    @Override public PropertyBackend backend() { return backend; }
    @Override public boolean knowsRegions() { return true; }
    @Override public UUID world() { return WORLD; }
    @Override public String region() { return PLOT; }
    @Override public UUID owner() { return OWNER; }

    @Override
    public List<Location> cellsOf(String region) {
        return region.equals(UNIT) ? List.of(at(6, 6), at(8, 7)) : List.of(at(1, 1), at(15, 15), at(19, 2));
    }

    @Override public Location outside() { return at(50, 50); }

    @Override
    public void setOwner(String region, UUID owner) {
        model.region(WORLD, region).freeholdHolder = owner;
        model.staffSetOwners(WORLD, region, owner).runAll();
        settle();
    }

    @Override public void addMember(String region, UUID member) { model.guard(WORLD, region).members.add(member); }
    @Override public void unrelatedEdit(String region) { model.flagEdits++; }

    @Override
    public void removeRegion(String region) {
        model.deleteRegion(WORLD, region, true);
        settle();
    }

    @Override
    public void makeUnreadable() {
        model.installed = false;
        model.sourceFails = true;
        model.wgReadable = false;
    }

    @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0, 0)); }
    @Override public PropertyBackendContract.GuardControls guard() { return this; }

    // Guard controls

    @Override
    public GuardResult install(OwnershipHooks value) {
        hooks = value;
        var result = backend.installGuard(value);
        backend.guardBindings();
        return result;
    }

    @Override public void uninstall() { model.bus = null; }

    /** A transfer: the same steps Realty runs. The queued reload is left pending so a test can look at it. */
    @Override
    public boolean changeOwner(String region, UUID owner, String reason) {
        guarded("transfer fixture", () -> model.transfer(WORLD, region, owner).runAll());
        return true;
    }

    @Override
    public void changeOwnerCancelledElsewhere(String region, UUID owner) {
        // Another plugin cancelled the pre-event, so nothing changed and no post-event fired.
    }

    @Override public List<String> neverCancelled() { return List.of(); }
    @Override public List<String> affectedWith(String region) { return region.equals(PLOT) ? List.of(UNIT) : List.of(); }

    @Override
    public void recreate(String region, UUID owner) {
        guarded("recreate fixture", () -> {
            model.deleteRegion(WORLD, region, true);
            model.fire("RegionDeletedEvent", WORLD, region);
            model.addFreehold(WORLD, region, owner, null, 0, 0, 20, 20);
            model.fire("RegionCreatedEvent", WORLD, region);
        });
        settle();
    }

    /** Runs the replay the way the core's wrapper does once a guard is installed. */
    private void guarded(String what, Runnable replay) {
        if (hooks == null) replay.run();
        else hooks.guarded(what, replay);
    }

    @Override public void breakLinkage() { view.broken = true; }
}
