package com.prosperitysmp.npc.arm;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipReadGuardTest {

    private static final UUID ACTOR = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

    @Test
    void acceptsOnlyTheSingleActorUuidByValue() {
        UUID sameValue = new UUID(ACTOR.getMostSignificantBits(), ACTOR.getLeastSignificantBits());
        assertNotSame(ACTOR, sameValue);
        assertTrue(OwnershipReadGuard.validOwner(ACTOR, Set.of(sameValue), Set.of(), Set.of(), false));
        assertTrue(OwnershipReadGuard.validOwner(ACTOR, new HashSet<>(Set.of(ACTOR)),
                new HashSet<>(), new HashSet<>(), false));
        assertFalse(OwnershipReadGuard.validOwner(ACTOR, Set.of(OTHER), Set.of(), Set.of(), false));
    }

    @Test
    void acceptsDirectFloodgateShapedUuidWithoutConversion() {
        UUID actor = UUID.fromString("00000000-0000-0000-0009-000001234567");
        assertTrue(OwnershipReadGuard.validOwner(actor, Set.of(actor), Set.of(), Set.of(), false));
        assertFalse(OwnershipReadGuard.validOwner(actor, Set.of(actor.toString()), Set.of(), Set.of(), false));
    }

    @Test
    void rejectsNullActorAndNonSetDomains() {
        assertFalse(OwnershipReadGuard.validOwner(null, Set.of(ACTOR), Set.of(), Set.of(), false));
        Object[] invalid = {null, new Object(), ACTOR, List.of(), List.of(ACTOR),
                new Object[0], new UUID[] {ACTOR}, Map.of()};
        for (Object value : invalid) {
            assertFalse(OwnershipReadGuard.validOwner(ACTOR, value, Set.of(), Set.of(), false));
            assertFalse(OwnershipReadGuard.validOwner(ACTOR, Set.of(ACTOR), value, Set.of(), false));
            assertFalse(OwnershipReadGuard.validOwner(ACTOR, Set.of(ACTOR), Set.of(), value, false));
        }
    }

    @Test
    void rejectsEmptyMultipleNullAndWrongTypeUuidEntries() {
        Object[] invalid = {Set.of(), Set.of(ACTOR, OTHER), Collections.singleton(null),
                Set.of(ACTOR.toString()), Set.of(new Object()), Set.of(ACTOR, "unexpected")};
        for (Object value : invalid) {
            assertFalse(OwnershipReadGuard.validOwner(ACTOR, value, Set.of(), Set.of(), false));
        }
    }

    @Test
    void rejectsEveryNonemptyNameOrGroupDomain() {
        Object[] nonempty = {Set.of("owner"), Set.of(ACTOR.toString()), Set.of(ACTOR),
                Set.of(""), Collections.singleton(null)};
        for (Object value : nonempty) {
            assertFalse(OwnershipReadGuard.validOwner(ACTOR, Set.of(ACTOR), value, Set.of(), false));
            assertFalse(OwnershipReadGuard.validOwner(ACTOR, Set.of(ACTOR), Set.of(), value, false));
        }
        assertFalse(OwnershipReadGuard.validOwner(ACTOR, Set.of(ACTOR), Set.of("owner"),
                Set.of("owners"), false));
    }

    @Test
    void rejectsParentEvenWithOtherwiseValidDirectOwner() {
        assertFalse(OwnershipReadGuard.validOwner(ACTOR, Set.of(ACTOR), Set.of(), Set.of(), true));
    }

    @Test
    void acceptsIdenticalReferencesInTheSameOrSeparateArrays() {
        Object[] captured = authority();
        assertTrue(OwnershipReadGuard.sameAuthority(captured, captured));
        assertTrue(OwnershipReadGuard.sameAuthority(captured, captured.clone()));
        Object identity = new Object();
        assertTrue(OwnershipReadGuard.sameAuthority(new Object[] {identity}, new Object[] {identity}));
    }

    @Test
    void rejectsNullEmptyAndDifferentLengthAuthorityArrays() {
        Object[] captured = authority();
        assertFalse(OwnershipReadGuard.sameAuthority(null, null));
        assertFalse(OwnershipReadGuard.sameAuthority(null, captured));
        assertFalse(OwnershipReadGuard.sameAuthority(captured, null));
        assertFalse(OwnershipReadGuard.sameAuthority(new Object[0], new Object[0]));
        Object[] shorter = {captured[0]};
        assertFalse(OwnershipReadGuard.sameAuthority(captured, shorter));
        assertFalse(OwnershipReadGuard.sameAuthority(shorter, captured));
    }

    @Test
    void rejectsNullInEveryAuthoritySlotOnEitherSide() {
        Object[] captured = authority();
        for (int i = 0; i < captured.length; i++) {
            Object[] missing = captured.clone();
            missing[i] = null;
            assertFalse(OwnershipReadGuard.sameAuthority(captured, missing));
            assertFalse(OwnershipReadGuard.sameAuthority(missing, captured));
            assertFalse(OwnershipReadGuard.sameAuthority(missing, missing));
        }
    }

    @Test
    void rejectsEqualButDistinctProviderManagersRegionRawRegionAndWorld() {
        Object[] captured = authority();
        for (int i = 0; i < captured.length; i++) {
            Authority previous = (Authority) captured[i];
            Object[] current = captured.clone();
            current[i] = new Authority(previous.role());
            assertEquals(captured[i], current[i]);
            assertNotSame(captured[i], current[i]);
            assertFalse(OwnershipReadGuard.sameAuthority(captured, current), previous.role());
            assertFalse(OwnershipReadGuard.sameAuthority(current, captured), previous.role());
        }
    }

    @Test
    void rejectsReorderedReferences() {
        Object[] captured = authority();
        Object[] current = captured.clone();
        current[0] = captured[1];
        current[1] = captured[0];
        assertFalse(OwnershipReadGuard.sameAuthority(captured, current));
    }

    private static Object[] authority() {
        return new Object[] {new Authority("provider"), new Authority("armManager"),
                new Authority("wgManager"), new Authority("region"),
                new Authority("rawRegion"), new Authority("world")};
    }

    private record Authority(String role) {
    }
}
