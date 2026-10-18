package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.arm.OwnershipFence;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The hooks a property backend's guard calls. Each one goes to the ownership fence, and {@link #guarded} turns any
 * throw into a backend failure (the guard goes UNAVAILABLE and staff are alerted) instead of an error inside the
 * vendor plugin's event.
 */
public final class FenceHooks implements OwnershipHooks {
    private final OwnershipFence fence;
    private final BiConsumer<String, Throwable> failed;

    /** @param failed called with what failed and the throwable; must not throw */
    public FenceHooks(OwnershipFence fence, BiConsumer<String, Throwable> failed) {
        this.fence = Objects.requireNonNull(fence);
        this.failed = Objects.requireNonNull(failed);
    }

    @Override public Runnable beginWrite(UUID world, String regionId) { return fence.beginWrite(world, regionId); }

    @Override public void externalChange(UUID world, String regionId) { fence.externalChange(world, regionId); }

    @Override public void markDirty(UUID world, String regionId) { fence.markDirty(world, regionId); }

    @Override public boolean isHeld(UUID world, String regionId) { return fence.isHeld(world, regionId); }

    @Override
    public void guarded(String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            failed.accept(what, failure);
        }
    }
}
