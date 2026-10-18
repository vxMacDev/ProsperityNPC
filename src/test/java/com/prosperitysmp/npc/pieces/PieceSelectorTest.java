package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.Piece;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Selection rules: reserved ids, duplicates, priority, API version, scale, and no fallback. */
class PieceSelectorTest {

    private static MoneySource money(String id, int major, int minor, String scale) {
        return new MoneySource() {
            @Override public String id() { return id; }
            @Override public int apiMajor() { return major; }
            @Override public int apiMinor() { return minor; }
            @Override public Capabilities capabilities() {
                return Capabilities.builder().value("scale", scale).flag("artifact-hashable", true).build();
            }
            @Override public Optional<Pin> pin() { return Optional.empty(); }
            @Override public String toString() { return id; }
        };
    }

    private static MoneySource money(String id) { return money(id, ProsperityNpcApi.MAJOR, ProsperityNpcApi.MINOR, "2"); }

    private static PieceSelector.Candidate<MoneySource> from(MoneySource piece, String plugin, int priority) {
        return new PieceSelector.Candidate<>(piece, plugin, "1.0", priority);
    }

    private final PieceSelector<MoneySource> selector = new PieceSelector<>(MoneySource.class);
    private final MoneySource vault = money("vault");

    @Test void aReservedIdFromAThirdPartyIsRefusedAndTheBuiltInWins() {
        var impostor = money("vault");
        var result = selector.select("vault", Map.of("vault", vault), List.of(from(impostor, "SomePlugin", 5)));
        assertSame(vault, result.selected());
        assertEquals(1, result.refused().size());
        assertEquals("reserved id", result.refused().getFirst().reason());
        assertEquals("SomePlugin 1.0", result.refused().getFirst().from());
        for (String id : PieceSelector.RESERVED)
            assertEquals("reserved id", selector.refusal(money(id)), id);
    }

    @Test void twoRegistrationsWithTheConfiguredIdSelectNothingWhateverTheirPriority() {
        var a = money("bank"); var b = money("bank");
        var result = selector.select("bank", Map.of("vault", vault), List.of(from(a, "A", 0), from(b, "B", 4)));
        assertNull(result.selected());
        assertEquals("duplicate id bank from A 1.0 and B 1.0", result.reason());
        var reversed = selector.select("bank", Map.of("vault", vault), List.of(from(b, "B", 4), from(a, "A", 0)));
        assertNull(reversed.selected(), "neither order nor priority picks one");
    }

    @Test void priorityIsNeverUsed() {
        var low = money("bank");
        var result = selector.select("bank", Map.of(), List.of(from(low, "Low", 0), from(money("other"), "High", 4)));
        assertSame(low, result.selected(), "the only piece with the id is chosen, whatever the other's priority");
        assertEquals("Low", result.owner());
        assertEquals(1, result.unused().size());
    }

    @Test void aDifferentMajorOrAGreaterMinorIsRefusedWithTheNumbers() {
        var nextMajor = money("bank", ProsperityNpcApi.MAJOR + 1, 0, "2");
        var result = selector.select("bank", Map.of(), List.of(from(nextMajor, "A", 0)));
        assertNull(result.selected());
        assertEquals("API " + (ProsperityNpcApi.MAJOR + 1) + ".0, ProsperityNPC supports " + ProsperityNpcApi.MAJOR + "."
                + ProsperityNpcApi.MINOR, result.reason());
        var newerMinor = money("bank", ProsperityNpcApi.MAJOR, ProsperityNpcApi.MINOR + 1, "2");
        assertNull(selector.select("bank", Map.of(), List.of(from(newerMinor, "A", 0))).selected());
        var olderMajor = money("bank", ProsperityNpcApi.MAJOR - 1, 0, "2");
        assertNull(selector.select("bank", Map.of(), List.of(from(olderMajor, "A", 0))).selected());
        // Positive control: the same piece at the core's version is selected.
        assertNotNull(selector.select("bank", Map.of(), List.of(from(money("bank"), "A", 0))).selected());
    }

    @Test void aMissingIdSelectsNothingAndNeverFallsBack() {
        var result = selector.select("nope", Map.of("vault", vault), List.of(from(money("bank"), "A", 0)));
        assertNull(result.selected());
        assertEquals("not found", result.reason());
        var property = new PieceSelector<>(Piece.class).select("nope", Map.of("arm", money("arm")), List.of());
        assertNull(property.selected(), "no fallback to the built-in");
    }

    @Test void aScaleOtherThanTwoIsRefusedForMoney() {
        var cents3 = money("bank", ProsperityNpcApi.MAJOR, ProsperityNpcApi.MINOR, "3");
        var result = selector.select("bank", Map.of(), List.of(from(cents3, "A", 0)));
        assertNull(result.selected());
        assertTrue(result.reason().startsWith("scale '3'"), result.reason());
        var blank = money("bank", ProsperityNpcApi.MAJOR, ProsperityNpcApi.MINOR, "");
        assertNull(selector.select("bank", Map.of(), List.of(from(blank, "A", 0))).selected());
    }

    @Test void aBadIdIsRefused() {
        assertTrue(selector.refusal(money("Bank")).startsWith("invalid id"));
        assertTrue(selector.refusal(money("b")).startsWith("invalid id"), "too short");
        assertTrue(selector.refusal(money("b".repeat(33))).startsWith("invalid id"), "too long");
        assertNull(selector.refusal(money("my-bank2")));
    }

    @Test void aBuiltInIdNotInThisBuildSelectsNothing() {
        var result = new PieceSelector<>(Piece.class).select("flat", Map.of("arm", money("arm")), List.of());
        assertNull(result.selected());
        assertTrue(result.reason().contains("not available"));
    }
}
