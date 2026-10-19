package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.GuardBinding;
import org.bukkit.Location;
import org.bukkit.World;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A GriefPrevention view for tests that need to script one call at a time: which claims exist, what each probe
 * answers or throws, and which event handlers the backend registered. It keeps every lookup it was asked for.
 */
final class ApiGpFakeView implements GriefPreventionView {
    static final UUID WORLD = new UUID(0, 68001);
    static final UUID OTHER_WORLD = new UUID(0, 68002);
    static final UUID OWNER = new UUID(0, 68003);
    static final UUID BUYER = new UUID(0, 68004);
    static final String WORLD_NAME = "gp-api-test";

    // Location holds its world weakly, so the proxies live as long as the class.
    private static final World WORLD_PROXY = world(WORLD, WORLD_NAME);
    private static final World OTHER_WORLD_PROXY = world(OTHER_WORLD, "gp-api-other");

    final Map<Long, Claim> claims = new HashMap<>();
    final List<Long> lookups = new ArrayList<>();
    final Object parentIdentity = new Object();
    final Object childIdentity = new Object();
    boolean available = true;
    boolean realEstate;
    Throwable availableThrows, realEstateThrows, claimThrows, claimAtThrows;
    Claim claimAtResult;
    int claimAtCalls, bindingsCalls;
    Events events;
    List<GuardBinding> bindingList = List.of();

    /** A parent claim 10 with one subdivision 11, both owned by {@link #OWNER}. */
    ApiGpFakeView() {
        put(newClaim(10, null, OWNER, parentIdentity, 11L));
        put(newClaim(11, 10L, OWNER, childIdentity));
    }

    static Claim newClaim(long id, Long parent, UUID owner, Object identity, Long... children) {
        return new Claim(id, WORLD, WORLD_NAME, parent, owner, identity, List.of(children));
    }

    void put(Claim claim) { claims.put(claim.id(), claim); }

    static World world(UUID uid, String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> uid;
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> uid.hashCode();
                    case "toString" -> name;
                    default -> throw new AssertionError("Unexpected world call: " + method.getName());
                });
    }

    static Location at(UUID world) {
        World target = world == null ? null : world.equals(WORLD) ? WORLD_PROXY : OTHER_WORLD_PROXY;
        return new Location(target, 10, 70, 10);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Override public boolean available() {
        if (availableThrows != null) sneakyThrow(availableThrows);
        return available;
    }

    @Override public boolean realEstatePresent() {
        if (realEstateThrows != null) sneakyThrow(realEstateThrows);
        return realEstate;
    }

    @Override public Claim claimAt(Location at) {
        claimAtCalls++;
        if (claimAtThrows != null) sneakyThrow(claimAtThrows);
        return claimAtResult;
    }

    @Override public Claim claim(long id) {
        lookups.add(id);
        if (claimThrows != null) sneakyThrow(claimThrows);
        return claims.get(id);
    }

    @Override public List<GuardBinding> bindings(Events value) {
        bindingsCalls++;
        events = value;
        return bindingList;
    }
}
