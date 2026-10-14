package com.prosperitysmp.npc.arm;

import java.util.Set;
import java.util.UUID;

/** Pure checks for strict owner-only reads and live authority identity. */
public final class OwnershipReadGuard {

    private OwnershipReadGuard() {
    }

    public static boolean validOwner(UUID actor, Object uuidSet, Object namesSet,
                                     Object groupsSet, boolean hasParent) {
        if (actor == null || hasParent
                || !(uuidSet instanceof Set<?> uuids)
                || !(namesSet instanceof Set<?> names)
                || !(groupsSet instanceof Set<?> groups)
                || uuids.size() != 1 || !names.isEmpty() || !groups.isEmpty()) {
            return false;
        }
        Object owner = uuids.iterator().next();
        return owner instanceof UUID && actor.equals(owner);
    }

    public static boolean sameAuthority(Object[] captured, Object[] current) {
        if (captured == null || current == null || captured.length == 0
                || captured.length != current.length) {
            return false;
        }
        for (int i = 0; i < captured.length; i++) {
            if (captured[i] == null || captured[i] != current[i]) {
                return false;
            }
        }
        return true;
    }
}
