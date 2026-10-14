package com.prosperitysmp.npc.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReserveAccountTest {
    private static YamlConfiguration config(Object value) {
        var config = new YamlConfiguration();
        if (value != null) config.set(ReserveAccount.PATH, value);
        return config;
    }

    @Test
    void canonicalUuidIsTheReserve() {
        String text = "123e4567-e89b-42d3-a456-426614174000";
        assertEquals(UUID.fromString(text), ReserveAccount.fromConfig(config(text)));
    }

    @Test
    void missingOrBlankHoldsPayments() {
        assertThrows(IllegalArgumentException.class, () -> ReserveAccount.fromConfig(config(null)));
        assertThrows(IllegalArgumentException.class, () -> ReserveAccount.fromConfig(config("")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "123E4567-E89B-42D3-A456-426614174000", " 123e4567-e89b-42d3-a456-426614174000",
            "123e4567-e89b-42d3-a456-4266141740000", "123e4567e89b42d3a456426614174000", "CentralBank"})
    void nonCanonicalSpellingsHoldPayments(String text) {
        assertFalse(ReserveAccount.isCanonical(text));
        assertThrows(IllegalArgumentException.class, () -> ReserveAccount.fromConfig(config(text)));
    }
}
