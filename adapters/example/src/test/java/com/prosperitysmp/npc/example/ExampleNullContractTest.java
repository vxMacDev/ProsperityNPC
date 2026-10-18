package com.prosperitysmp.npc.example;

import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** example-null knows no regions, so only the region-free cases apply: outside is empty and nothing is PRESENT. */
class ExampleNullContractTest extends PropertyBackendContract {
    @Test
    void noRegionReadsNeverProduceOwnershipOrPresent() {
        var backend = new ExampleNullBackend();
        for (Location at : List.of(new Location(null, 0, 64, 0), new Location(null, -25.5, 70, 42.5))) {
            assertTrue(backend.regionAt(at).isEmpty());
            for (UUID owner : List.of(new UUID(2, 2), new UUID(3, 3)))
                assertTrue(backend.ownerReadAt(at, owner).isEmpty());
        }
        for (String region : List.of("none", "plausible-region")) {
            assertTrue(backend.findRegion("world", region).isEmpty());
            assertTrue(backend.ownerReadRegion(new UUID(1, 1), region, new UUID(2, 2)).isEmpty());
            assertEquals(Presence.MISSING, backend.observe(new UUID(1, 1), region));
        }
        assertTrue(backend.capabilities().flag("distinguishes-missing"));
        assertFalse(backend.capabilities().flag("location-lookup"));
        assertFalse(backend.capabilities().flag("region-by-id"));
    }

    @Test
    void availableExampleStillRefusesAnOwnershipGuardWithoutCallingHooks() {
        var backend = new ExampleNullBackend();
        var calls = new AtomicInteger();
        var result = backend.installGuard((world, region) -> {
            calls.incrementAndGet();
            return calls::incrementAndGet;
        });
        assertTrue(backend.available());
        assertFalse(result.isInstalled());
        assertTrue(result.mode().isEmpty());
        assertFalse(result.reason().isBlank());
        assertEquals(0, calls.get());
        assertEquals("none", backend.capabilities().value("guard"));
    }

    @Override
    protected Subject subject(Scenario s) {
        var backend = new ExampleNullBackend();
        return new Subject() {
            @Override public PropertyBackend backend() { return backend; }
            @Override public boolean knowsRegions() { return false; }
            @Override public UUID world() { return new UUID(1, 1); }
            @Override public String region() { return "none"; }
            @Override public UUID owner() { return new UUID(2, 2); }
            @Override public List<Location> cellsOf(String region) { return List.of(); }
            @Override public Location outside() { return new Location(null, 0, 64, 0); }
            @Override public void setOwner(String region, UUID owner) { }
            @Override public void addMember(String region, UUID member) { }
            @Override public void unrelatedEdit(String region) { }
            @Override public void removeRegion(String region) { }
            @Override public void makeUnreadable() { }
            @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0, 0)); }
        };
    }
}
