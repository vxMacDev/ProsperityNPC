package com.prosperitysmp.npc.api;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.function.Executable;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An adapter built for an older API minor overrides only what existed then. The methods added since answer with a
 * plain default, and the ones that cannot work without ProsperityNPC say so.
 */
class ApiDefaultsTest {
    private static final UUID WORLD = new UUID(1, 1);

    /** An API 1.0 hooks implementation: beginWrite and nothing else. */
    private static final OwnershipHooks OLD_HOOKS = (world, regionId) -> () -> { };

    static Stream<Arguments> unsupportedHooks() {
        return Stream.of(
                Arguments.of("externalChange", (Executable) () -> OLD_HOOKS.externalChange(WORLD, "r1"),
                        "externalChange needs ProsperityNPC API 1.2"),
                Arguments.of("markDirty", (Executable) () -> OLD_HOOKS.markDirty(WORLD, "r1"),
                        "markDirty needs ProsperityNPC API 1.2"),
                Arguments.of("isHeld", (Executable) () -> OLD_HOOKS.isHeld(WORLD, "r1"),
                        "isHeld needs ProsperityNPC API 1.2"),
                Arguments.of("guarded", (Executable) () -> OLD_HOOKS.guarded("handler", () -> { }),
                        "guarded needs ProsperityNPC API 1.2"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedHooks")
    void aHookAddedInApiOnePointTwoSaysWhichVersionItNeeds(String name, Executable call, String message) {
        var thrown = assertThrows(UnsupportedOperationException.class, call);
        assertEquals(message, thrown.getMessage());
    }

    @Test void theDefaultGuardedHookNeverRunsTheBody() {
        var ran = new AtomicInteger();
        assertThrows(UnsupportedOperationException.class, () -> OLD_HOOKS.guarded("handler", ran::incrementAndGet));
        assertEquals(0, ran.get());
    }

    @Test void beginWriteIsTheOnlyHookAnOldImplementationNeedsAndReturnsItsCallback() {
        var calls = new AtomicInteger();
        OwnershipHooks hooks = (world, regionId) -> calls::incrementAndGet;
        Runnable end = hooks.beginWrite(WORLD, "r1");
        assertEquals(0, calls.get());
        end.run();
        assertEquals(1, calls.get());
    }

    /** An API 1.0 property backend: none of the 1.2 methods are overridden. */
    private static final class OldBackend implements PropertyBackend {
        @Override public String id() { return "old-backend"; }
        @Override public int apiMajor() { return 1; }
        @Override public int apiMinor() { return 0; }
        @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
        @Override public String displayName() { return "Old"; }
        @Override public boolean available() { return true; }
        @Override public GuardResult installGuard(OwnershipHooks hooks) { return GuardResult.failed("old"); }
        @Override public Optional<RegionRef> regionAt(Location at) { return Optional.empty(); }
        @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return Optional.empty(); }
        @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) { return Optional.empty(); }
        @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
            return Optional.empty();
        }
        @Override public Presence observe(UUID world, String regionId) { return Presence.MISSING; }
        @Override public List<String> describe() { return List.of(); }
    }

    @Test void aBackendWithoutGuardBindingsHasNone() {
        assertEquals(List.of(), new OldBackend().guardBindings());
    }

    @Test void theDefaultGuardBindingsCannotBeAddedTo() {
        var bindings = new OldBackend().guardBindings();
        assertThrows(UnsupportedOperationException.class, () -> bindings.add(null));
    }

    @Test void aBackendWithoutALabelHasAnEmptyOne() {
        assertEquals("", new OldBackend().label());
    }

    @Test void aPieceKeepsAnOverriddenLabel() {
        Piece piece = new Piece() {
            @Override public String id() { return "labelled"; }
            @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
            @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
            @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
            @Override public String label() { return "unverified"; }
        };
        assertEquals("unverified", piece.label());
    }
}
