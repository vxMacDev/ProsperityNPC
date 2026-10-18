package com.prosperitysmp.npc.example;

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
import java.util.Optional;
import java.util.UUID;

/** A property backend that knows no regions and installs no guard. Every read is empty; observe says MISSING. */
public final class ExampleNullBackend implements PropertyBackend {
    public static final String ID = "example-null";

    @Override public String id() { return ID; }

    // These read the constants at this adapter's compile time; that is how the core spots an adapter built
    // against another API version.
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
                .value("guard", "none")
                .value("owner-model", "none (example only)")
                .flag("location-lookup", false)
                .flag("region-by-id", false)
                .flag("distinguishes-missing", true)
                .value("token-identity", "object")
                .flag("needs-worldguard", false)
                .value("region-id-max-length", "128")
                .flag("main-thread-only", true)
                .build();
    }

    @Override public String label() { return "DEV only"; }
    @Override public String displayName() { return "Example"; }
    @Override public boolean available() { return true; }
    @Override public GuardResult installGuard(OwnershipHooks hooks) { return GuardResult.failed("example only"); }
    @Override public Optional<RegionRef> regionAt(Location at) { return Optional.empty(); }
    @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return Optional.empty(); }
    @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) { return Optional.empty(); }

    @Override
    public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
        return Optional.empty();
    }

    @Override public Presence observe(UUID world, String regionId) { return Presence.MISSING; }
    @Override public List<String> describe() { return List.of("example-null: knows no regions (DEV only)"); }
}
