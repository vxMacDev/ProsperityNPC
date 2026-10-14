package com.prosperitysmp.npc.economy;

import java.util.Objects;
import java.util.UUID;

/** All NPC transfers share the reserve. Hold across SQL waits, not only around Vault calls. */
public final class FinancialFence {
    private Lease active;

    public synchronized Lease acquire(String owner) {
        Objects.requireNonNull(owner);
        if (active != null) throw new IllegalStateException("Another NPC payment holds the reserve");
        active = new Lease(UUID.randomUUID(), owner);
        return active;
    }

    public final class Lease {
        private final UUID id;
        private final String owner;
        private Lease(UUID id, String owner) { this.id = id; this.owner = owner; }
        public void validate() {
            synchronized (FinancialFence.this) {
                if (active != this) throw new IllegalStateException("NPC payment lease no longer active");
            }
        }
        public void releaseVerified() {
            synchronized (FinancialFence.this) { validate(); active = null; }
        }
        public UUID id() { return id; }
        public String owner() { return owner; }
    }
}
