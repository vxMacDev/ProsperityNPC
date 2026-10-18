package com.prosperitysmp.npc.api.contract;

import com.prosperitysmp.npc.api.OwnershipHooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ownership hooks the property contract hands a backend's guard. It plays the part of ProsperityNPC: the test
 * decides which regions are held, and every bump, failure and off-main misuse is recorded. The thread that builds
 * it counts as the main thread.
 */
public final class RecordingHooks implements OwnershipHooks {
    private final Thread main = Thread.currentThread();
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    private final List<String> bumps = Collections.synchronizedList(new ArrayList<>());
    private final List<String> offMain = Collections.synchronizedList(new ArrayList<>());
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    public static String key(UUID world, String region) {
        return world + "/" + region;
    }

    public void hold(UUID world, String region) { held.add(key(world, region)); }

    public void release(UUID world, String region) { held.remove(key(world, region)); }

    /** Every externalChange or markDirty, as "world/region", in order. */
    public List<String> bumps() { synchronized (bumps) { return List.copyOf(bumps); } }

    public long bumpsOf(UUID world, String region) {
        String key = key(world, region);
        return bumps().stream().filter(key::equals).count();
    }

    public void clearBumps() { bumps.clear(); }

    /** Main-thread-only hooks that were called off the main thread. */
    public List<String> offMainMisuse() { synchronized (offMain) { return List.copyOf(offMain); } }

    /** What failed inside {@link #guarded}, with the throwable's class. */
    public List<String> failures() { synchronized (failures) { return List.copyOf(failures); } }

    private boolean onMain() { return Thread.currentThread() == main; }

    @Override
    public Runnable beginWrite(UUID world, String regionId) {
        if (!onMain()) offMain.add("beginWrite");
        throw new UnsupportedOperationException("event-based guards never call beginWrite");
    }

    @Override
    public void externalChange(UUID world, String regionId) {
        if (!onMain()) offMain.add("externalChange");
        bumps.add(key(world, regionId));
    }

    @Override
    public void markDirty(UUID world, String regionId) {
        bumps.add(key(world, regionId));
    }

    @Override
    public boolean isHeld(UUID world, String regionId) {
        return held.contains(key(world, regionId));
    }

    @Override
    public void guarded(String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable failure) {
            failures.add(what + ": " + failure.getClass().getSimpleName());
        }
    }
}
