package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** The record's checks match the SQL table, so a row it accepts the database also accepts. */
class ContractDefinitionTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    static ContractDefinition of(String id, ContractDefinition.Kind kind, UUID giver, UUID recipient, String material,
                                 int quantity, int minutes) {
        return new ContractDefinition(id, kind, "Wall stone", "Bring me cobblestone.", giver, recipient, material, quantity,
                Stage.STRANGER, minutes);
    }

    @Test void validSupplyAndDeliverContracts() {
        var supply = of("mara-wall-stone", ContractDefinition.Kind.SUPPLY, A, A, "COBBLESTONE", 64, 1440);
        var deliver = of("nico-coal-run", ContractDefinition.Kind.DELIVER, A, B, "COAL", 1, 10);
        assertEquals(64, supply.quantity());
        assertEquals(B, deliver.recipientId());
        assertFalse(supply.partnerStart());
    }

    @Test void supplyMustGoBackToItsGiverAndDeliverToSomeoneElse() {
        // Mutation guard: drop the giver/recipient switch and the DELIVER-equal case passes.
        assertThrows(IllegalArgumentException.class, () -> of("a", ContractDefinition.Kind.DELIVER, A, A, "COAL", 1, 30));
        assertThrows(IllegalArgumentException.class, () -> of("a", ContractDefinition.Kind.SUPPLY, A, B, "COAL", 1, 30));
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition(ContractDefinition.PARTNER_START,
                ContractDefinition.Kind.PARTNERSHIP, "P", "D", A, B, "COAL", 1, Stage.TRUSTED, 10));
    }

    @Test void idQuantityDurationAndMaterialBounds() {
        for (String bad : new String[] {"", "Upper", "has space", "a".repeat(49), "semi;colon"})
            assertThrows(IllegalArgumentException.class, () -> of(bad, ContractDefinition.Kind.SUPPLY, A, A, "COAL", 1, 30), bad);
        assertDoesNotThrow(() -> of("a".repeat(48), ContractDefinition.Kind.SUPPLY, A, A, "COAL", 1, 30));
        assertThrows(IllegalArgumentException.class, () -> of("a", ContractDefinition.Kind.SUPPLY, A, A, "COAL", 0, 30));
        assertThrows(IllegalArgumentException.class, () -> of("a", ContractDefinition.Kind.SUPPLY, A, A, "COAL", 257, 30));
        assertDoesNotThrow(() -> of("a", ContractDefinition.Kind.SUPPLY, A, A, "COAL", 256, 30));
        assertThrows(IllegalArgumentException.class, () -> of("a", ContractDefinition.Kind.SUPPLY, A, A, "COAL", 1, 9));
        assertThrows(IllegalArgumentException.class, () -> of("a", ContractDefinition.Kind.SUPPLY, A, A, "COAL", 1, 1441));
        assertDoesNotThrow(() -> of("a", ContractDefinition.Kind.SUPPLY, A, A, "COAL", 1, 10));
        for (String bad : new String[] {"coal", "1COAL", "CO AL", "", "minecraft:coal"})
            assertThrows(IllegalArgumentException.class, () -> of("a", ContractDefinition.Kind.SUPPLY, A, A, bad, 1, 30), bad);
    }

    @Test void textHasNoControlCharactersAndFitsItsColumn() {
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition("a", ContractDefinition.Kind.SUPPLY,
                "Line\nbreak", "D", A, A, "COAL", 1, Stage.STRANGER, 30));
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition("a", ContractDefinition.Kind.SUPPLY,
                "T", "Tab\there", A, A, "COAL", 1, Stage.STRANGER, 30));
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition("a", ContractDefinition.Kind.SUPPLY,
                "x".repeat(81), "D", A, A, "COAL", 1, Stage.STRANGER, 30));
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition("a", ContractDefinition.Kind.SUPPLY,
                "T", "x".repeat(513), A, A, "COAL", 1, Stage.STRANGER, 30));
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition("a", ContractDefinition.Kind.SUPPLY,
                " padded", "D", A, A, "COAL", 1, Stage.STRANGER, 30));
    }

    @Test void partnershipHandOversAreTenMinutesToTheResidentItself() {
        var start = ContractDefinition.partnership(true, A, "IRON_INGOT", 16, Stage.TRUSTED);
        var renew = ContractDefinition.partnership(false, A, "IRON_INGOT", 16, Stage.TRUSTED);
        assertTrue(start.partnerStart());
        assertFalse(renew.partnerStart());
        assertEquals(10, start.durationMinutes());
        assertEquals(A, start.recipientId());
    }

    @Test void storedContractMatchesTheSqlStateChecks() {
        var d = of("a", ContractDefinition.Kind.SUPPLY, A, A, "COAL", 4, 30);
        Instant at = Instant.parse("2026-10-14T10:00:00Z");
        LocalDate day = LocalDate.of(2026, 10, 14);
        Instant expires = at.plusSeconds(1800);
        assertDoesNotThrow(() -> new TownContract(UUID.randomUUID(), B, d, at, day, 1, expires, TownContract.Status.ACTIVE, null, null, null));
        // An open hand-over needs its run ID; an ACTIVE row must not have one.
        assertThrows(IllegalArgumentException.class, () -> new TownContract(UUID.randomUUID(), B, d, at, day, 1, expires,
                TownContract.Status.HANDOVER_INTENT, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TownContract(UUID.randomUUID(), B, d, at, day, 1, expires,
                TownContract.Status.ACTIVE, UUID.randomUUID(), null, null));
        // A contract uses slot 1-3; slot 0 is for partnership hand-overs only.
        assertThrows(IllegalArgumentException.class, () -> new TownContract(UUID.randomUUID(), B, d, at, day, 0, expires,
                TownContract.Status.ACTIVE, null, null, null));
        // Expiry closes at the deadline; completion carries its day.
        assertThrows(IllegalArgumentException.class, () -> new TownContract(UUID.randomUUID(), B, d, at, day, 1, expires,
                TownContract.Status.EXPIRED, null, expires.plusSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> new TownContract(UUID.randomUUID(), B, d, at, day, 1, expires,
                TownContract.Status.COMPLETED, UUID.randomUUID(), at.plusSeconds(60), null));
        assertThrows(IllegalArgumentException.class, () -> new TownContract(UUID.randomUUID(), B, d, at, day, 1, at.plusSeconds(60),
                TownContract.Status.ACTIVE, null, null, null));
    }
}
