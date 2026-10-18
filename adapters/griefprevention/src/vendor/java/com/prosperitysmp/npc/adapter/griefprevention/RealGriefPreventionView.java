package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.GuardBinding;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.events.ClaimChangeEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimDeletedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimTransferEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;

/** GriefPrevention 16.18.7 behind {@link GriefPreventionView}. The only class besides the plugin that touches GP. */
final class RealGriefPreventionView implements GriefPreventionView {
    private final PluginManager plugins;

    RealGriefPreventionView(PluginManager plugins) {
        this.plugins = plugins;
    }

    @Override
    public boolean available() {
        return plugins.isPluginEnabled("GriefPrevention") && GriefPrevention.instance != null
                && GriefPrevention.instance.dataStore != null;
    }

    @Override
    public boolean realEstatePresent() {
        return plugins.getPlugin("RealEstate") != null;
    }

    @Override
    public GriefPreventionView.Claim claimAt(Location at) {
        // ignoreHeight true, subdivisions included: the innermost claim, as GriefPrevention's own commands see it.
        return snapshot(GriefPrevention.instance.dataStore.getClaimAt(at, true, false, null));
    }

    @Override
    public GriefPreventionView.Claim claim(long id) {
        var claim = GriefPrevention.instance.dataStore.getClaim(id);
        return claim == null || !claim.inDataStore ? null : snapshot(claim);
    }

    @Override
    public List<GuardBinding> bindings(Events events) {
        return List.of(
                GuardBinding.of(ClaimTransferEvent.class, EventPriority.HIGHEST, true, event -> {
                    var claim = snapshot(event.getClaim());
                    if (claim != null && events.transferRequested(claim)) event.setCancelled(true);
                }),
                GuardBinding.of(ClaimTransferEvent.class, EventPriority.MONITOR, true, event -> {
                    var claim = snapshot(event.getClaim());
                    if (claim != null) events.transferred(claim);
                }),
                GuardBinding.of(ClaimDeletedEvent.class, EventPriority.MONITOR, false, event -> {
                    var claim = snapshot(event.getClaim());
                    if (claim != null) events.deleted(claim);
                }),
                GuardBinding.of(ClaimChangeEvent.class, EventPriority.MONITOR, true, event -> {
                    var from = snapshot(event.getFrom());
                    if (from != null) events.changed(from);
                }));
    }

    /** Null for a claim without an id or world (not saved yet). getOwnerID(), never the field. */
    private static GriefPreventionView.Claim snapshot(me.ryanhamshire.GriefPrevention.Claim claim) {
        if (claim == null || claim.getID() == null) return null;
        Location corner = claim.getLesserBoundaryCorner();
        World world = corner == null ? null : corner.getWorld();
        if (world == null) return null;
        var children = new ArrayList<Long>();
        if (claim.children != null) for (var child : claim.children) if (child.getID() != null) children.add(child.getID());
        Long parent = claim.parent == null ? null : claim.parent.getID();
        return new GriefPreventionView.Claim(claim.getID(), world.getUID(), world.getName(), parent, claim.getOwnerID(),
                claim, children);
    }
}
