package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.ProsperityNpcApi;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A money source with no provider: it never pins, never probes true and names why. */
class UnavailableMoneySourceTest {

    @Test
    void itReportsItsIdReasonAndNoProvider() {
        var source = new UnavailableMoneySource("vault", "Vault is not installed");
        assertEquals("vault", source.id());
        assertEquals("Vault is not installed", source.reason());
        assertEquals("none", source.providerName());
        assertFalse(source.probe());
        assertTrue(source.pin().isEmpty());
    }

    @Test
    void itSpeaksTheCurrentApiVersion() {
        var source = new UnavailableMoneySource("x", "y");
        assertEquals(ProsperityNpcApi.MAJOR, source.apiMajor());
        assertEquals(ProsperityNpcApi.MINOR, source.apiMinor());
    }

    @Test
    void itsOnlyCapabilityIsTwoDecimalPlaces() {
        var capabilities = new UnavailableMoneySource("x", "y").capabilities();
        assertEquals(Map.of("scale", "2"), capabilities.all());
    }

    @Test
    void anEmptyReasonIsKeptButNullIsRefused() {
        assertEquals("", new UnavailableMoneySource("x", "").reason());
        assertThrows(NullPointerException.class, () -> new UnavailableMoneySource(null, "reason"));
        assertThrows(NullPointerException.class, () -> new UnavailableMoneySource("x", null));
    }
}
