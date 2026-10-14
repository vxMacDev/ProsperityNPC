package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.AccountLinkRepository.Observed;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Join discovery with a fake Floodgate: what is saved, what is refused, and which notices players see. */
class FloodgateLinkListenerTest {
    private static final UUID JAVA = UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000a1");
    private static final UUID BEDROCK = new UUID(0, 0x0009_0001_00b2L);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

    /** Records every host call. observe answers with the queued outcome. */
    private static final class Host implements FloodgateLinkListener.Host {
        boolean enabled = true;
        LocalDate today = DAY;
        Observed answer = Observed.NEW;
        boolean grouped = true;
        final List<UUID[]> saved = new ArrayList<>();
        final List<UUID> groupChecks = new ArrayList<>();
        final List<String> warnings = new ArrayList<>(), told = new ArrayList<>();
        @Override public boolean enabled() { return enabled; }
        @Override public LocalDate today() { return today; }
        @Override public void observe(UUID java, UUID bedrock, Consumer<Observed> done) { saved.add(new UUID[] {java, bedrock}); done.accept(answer); }
        @Override public void inSavedGroup(UUID account, Consumer<Boolean> done) { groupChecks.add(account); done.accept(grouped); }
        @Override public void warn(String message) { warnings.add(message); }
        @Override public void tell(UUID player, String message) { told.add(player + ":" + message); }
        @Override public String linkedNotice() { return "LINKED"; }
        @Override public String unlinkedNotice() { return "UNLINKED"; }
    }

    private final Map<UUID, FloodgatePlayer> players = new HashMap<>();
    private final FloodgateApi api = (FloodgateApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {FloodgateApi.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlayer" -> players.get((UUID) args[0]);
                case "isFloodgateId" -> ((UUID) args[0]).getMostSignificantBits() == 0;
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("Unexpected Floodgate call: " + method.getName());
            });

    private static FloodgatePlayer player(boolean linked, LinkedPlayer lp, UUID correct) {
        return (FloodgatePlayer) Proxy.newProxyInstance(FloodgateLinkListenerTest.class.getClassLoader(), new Class<?>[] {FloodgatePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isLinked" -> linked;
                    case "getLinkedPlayer" -> lp;
                    case "getCorrectUniqueId" -> correct;
                    case "hashCode" -> 2;
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected player call: " + method.getName());
                });
    }

    @Test void aLinkedValidPlayerIsSavedAndToldOnce() {
        var lp = LinkedPlayer.of("Ann", JAVA, BEDROCK);
        assertEquals(JAVA, lp.getJavaUniqueId());
        assertEquals(BEDROCK, lp.getBedrockId());
        players.put(JAVA, player(true, lp, JAVA));
        var host = new Host();
        var listener = new FloodgateLinkListener(api, host);
        listener.joined(JAVA);
        assertEquals(1, host.saved.size());
        assertArrayEquals(new UUID[] {JAVA, BEDROCK}, host.saved.getFirst());
        assertEquals(List.of(JAVA + ":LINKED"), host.told);
        // The next join finds the pair already saved: saved again (last seen), no second notice.
        host.answer = Observed.SEEN;
        listener.joined(JAVA);
        assertEquals(2, host.saved.size());
        assertEquals(1, host.told.size());
        // A pair that staff split and Floodgate still links is told again when it rejoins.
        host.answer = Observed.REJOINED;
        listener.joined(JAVA);
        assertEquals(2, host.told.size());
        assertTrue(host.warnings.isEmpty());
    }

    @Test void notLinkedOrJavaOnlySavesNothing() {
        var host = new Host();
        host.grouped = false;
        var listener = new FloodgateLinkListener(api, host);
        listener.joined(JAVA);                      // Java client: no Floodgate player at all.
        players.put(BEDROCK, player(false, null, BEDROCK));
        listener.joined(BEDROCK);                   // Bedrock, not linked, in no group.
        assertTrue(host.saved.isEmpty());
        assertTrue(host.told.isEmpty());
        assertEquals(List.of(BEDROCK), host.groupChecks);
    }

    @Test void aMismatchedLinkSavesNothingAndWarnsOncePerDay() {
        var host = new Host();
        var listener = new FloodgateLinkListener(api, host);
        UUID other = UUID.fromString("6c000000-0000-4000-8000-0000000000c3");
        // Correct UUID is not the player.
        players.put(JAVA, player(true, LinkedPlayer.of("Ann", JAVA, BEDROCK), other));
        listener.joined(JAVA);
        listener.joined(JAVA);
        assertTrue(host.saved.isEmpty());
        assertEquals(1, host.warnings.size(), "once per UUID per day");
        host.today = DAY.plusDays(1);
        listener.joined(JAVA);
        assertEquals(2, host.warnings.size());
        // The linked Java UUID is not the player who joined.
        players.put(other, player(true, LinkedPlayer.of("Ann", JAVA, BEDROCK), other));
        listener.joined(other);
        assertTrue(host.saved.isEmpty());
        assertTrue(host.told.isEmpty());
    }

    @Test void wrongFloodgateRolesSaveNothing() {
        var host = new Host();
        var listener = new FloodgateLinkListener(api, host);
        UUID notFloodgate = UUID.fromString("6c000000-0000-4000-8000-0000000000c3");
        // The "Bedrock" side is not a Floodgate ID.
        players.put(JAVA, player(true, LinkedPlayer.of("Ann", JAVA, notFloodgate), JAVA));
        listener.joined(JAVA);
        // The "Java" side is a Floodgate ID.
        UUID floodgateJava = new UUID(0, 0x99);
        players.put(floodgateJava, player(true, LinkedPlayer.of("Bo", floodgateJava, BEDROCK), floodgateJava));
        listener.joined(floodgateJava);
        assertTrue(host.saved.isEmpty());
        assertEquals(2, host.warnings.size());
    }

    @Test void anUnlinkedAccountInAGroupIsToldOncePerDay() {
        var host = new Host();
        var listener = new FloodgateLinkListener(api, host);
        players.put(BEDROCK, player(false, null, BEDROCK));
        listener.joined(BEDROCK);
        listener.joined(BEDROCK);
        assertEquals(List.of(BEDROCK + ":UNLINKED"), host.told);
        assertEquals(1, host.groupChecks.size(), "checked once a day");
        host.today = DAY.plusDays(1);
        listener.joined(BEDROCK);
        assertEquals(2, host.told.size());
        assertTrue(host.saved.isEmpty());
    }

    @Test void flagOffDoesNothingAtAll() {
        var host = new Host();
        host.enabled = false;
        players.put(JAVA, player(true, LinkedPlayer.of("Ann", JAVA, BEDROCK), JAVA));
        players.put(BEDROCK, player(false, null, BEDROCK));
        var listener = new FloodgateLinkListener(api, host);
        listener.joined(JAVA);
        listener.joined(BEDROCK);
        assertTrue(host.saved.isEmpty() && host.told.isEmpty() && host.groupChecks.isEmpty() && host.warnings.isEmpty());
    }
}
