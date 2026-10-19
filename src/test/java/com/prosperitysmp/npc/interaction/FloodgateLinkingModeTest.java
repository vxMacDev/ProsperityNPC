package com.prosperitysmp.npc.interaction;

import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** The health line that says which kind of Floodgate linking is running, from the link's class and switch. */
class FloodgateLinkingModeTest {
    private static final String OFF = "Floodgate linking off";
    private static final String LOCAL = "Floodgate local linking (lookups read Floodgate's own database)";
    private static final String GLOBAL = "Floodgate global linking (lookups call api.geysermc.org)";

    /** A link that does nothing; only its switch and its class name matter here. */
    private abstract static class Stub implements PlayerLink {
        private final boolean enabled;
        Stub(boolean enabled) { this.enabled = enabled; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void load() { }
        @Override public CompletableFuture<LinkedPlayer> getLinkedPlayer(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Boolean> isLinkedPlayer(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Void> linkPlayer(UUID bedrock, UUID java, String name) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Void> unlinkPlayer(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<?> createLinkRequest(UUID uuid, String name, String bedrock) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<LinkRequestResult> verifyLinkRequest(UUID uuid, String name, String bedrock, String code) {
            throw new UnsupportedOperationException();
        }
        @Override public String getName() { return "stub"; }
        @Override public long getVerifyLinkTimeout() { return 0; }
        @Override public boolean isAllowLinking() { return true; }
        @Override public void stop() { }
    }

    private static final class GlobalPlayerLinking extends Stub { GlobalPlayerLinking(boolean on) { super(on); } }
    private static final class MyGlobalLink extends Stub { MyGlobalLink(boolean on) { super(on); } }
    private static final class SqliteLink extends Stub { SqliteLink(boolean on) { super(on); } }
    private static final class globalLowercase extends Stub { globalLowercase(boolean on) { super(on); } }

    private static FloodgateApi api(PlayerLink link) {
        return (FloodgateApi) Proxy.newProxyInstance(FloodgateLinkingModeTest.class.getClassLoader(), new Class<?>[] {FloodgateApi.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayerLink" -> link;
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected Floodgate call: " + method.getName());
                });
    }

    @Test void noLinkAtAllIsLinkingOff() {
        assertEquals(OFF, FloodgateLinkListener.linkingMode(api(null)));
    }

    @Test void aLinkThatIsSwitchedOffIsLinkingOffWhateverItsKind() {
        assertEquals(OFF, FloodgateLinkListener.linkingMode(api(new SqliteLink(false))));
        assertEquals(OFF, FloodgateLinkListener.linkingMode(api(new GlobalPlayerLinking(false))));
    }

    @Test void anEnabledLinkWithAPlainNameIsLocalLinking() {
        assertEquals(LOCAL, FloodgateLinkListener.linkingMode(api(new SqliteLink(true))));
    }

    @Test void anEnabledLinkWhoseClassNameHasGlobalInItIsGlobalLinking() {
        assertEquals(GLOBAL, FloodgateLinkListener.linkingMode(api(new GlobalPlayerLinking(true))));
        assertEquals(GLOBAL, FloodgateLinkListener.linkingMode(api(new MyGlobalLink(true))));
    }

    @Test void theGlobalCheckIsCaseSensitive() {
        assertEquals(LOCAL, FloodgateLinkListener.linkingMode(api(new globalLowercase(true))));
    }

    @Test void aMissingApiIsRefused() {
        assertThrows(NullPointerException.class, () -> FloodgateLinkListener.linkingMode(null));
    }
}
