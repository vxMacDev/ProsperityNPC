package com.prosperitysmp.npc.nav;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chunk source that talks to a real world: which calls it makes for a load, a ticket, a release and a count. */
class NavChunkLoaderSourceTest {

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "plugin";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    private static Chunk chunk() {
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return 1;
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "chunk";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    private static World world(List<String> log, Chunk loaded, boolean accepts, Map<Plugin, Collection<Long>> held) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getChunkAtAsync":
                    log.add("load " + args[0] + "," + args[1] + " generate=" + args[2]);
                    return CompletableFuture.completedFuture(loaded);
                case "addPluginChunkTicket":
                    log.add("add " + args[0] + "," + args[1]);
                    return accepts;
                case "removePluginChunkTicket":
                    log.add("remove " + args[0] + "," + args[1]);
                    return true;
                case "getPluginChunkTickets":
                    return held;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "world";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    @Test
    void loadingAsksTheWorldForTheChunkWithoutGeneratingItAndSaysTrueWhenItExists() throws Exception {
        List<String> log = new ArrayList<>();
        NavChunkLoader.Source source = NavChunkLoader.of(world(log, chunk(), true, Map.of()), plugin());
        assertTrue(source.load(3, -4).get());
        assertEquals(List.of("load 3,-4 generate=false"), log);
    }

    @Test
    void aChunkThatWasNeverGeneratedLoadsAsFalse() throws Exception {
        NavChunkLoader.Source source = NavChunkLoader.of(world(new ArrayList<>(), null, true, Map.of()), plugin());
        assertFalse(source.load(0, 0).get());
    }

    @Test
    void addingATicketPassesTheChunkAndReportsWhatTheWorldAnswered() {
        List<String> log = new ArrayList<>();
        NavChunkLoader.Source accepting = NavChunkLoader.of(world(log, null, true, Map.of()), plugin());
        assertTrue(accepting.addTicket(7, 8));
        NavChunkLoader.Source refusing = NavChunkLoader.of(world(log, null, false, Map.of()), plugin());
        assertFalse(refusing.addTicket(9, 10));
        assertEquals(List.of("add 7,8", "add 9,10"), log);
    }

    @Test
    void removingATicketPassesTheChunk() {
        List<String> log = new ArrayList<>();
        NavChunkLoader.of(world(log, null, true, Map.of()), plugin()).removeTicket(-1, 2);
        assertEquals(List.of("remove -1,2"), log);
    }

    @Test
    void ticketsHeldCountsOnlyThisPluginsTickets() {
        Plugin ours = plugin();
        Plugin theirs = plugin();
        Map<Plugin, Collection<Long>> held = Map.of(ours, List.of(1L, 2L, 3L), theirs, List.of(9L));
        assertEquals(3, NavChunkLoader.of(world(new ArrayList<>(), null, true, held), ours).ticketsHeld());
        assertEquals(1, NavChunkLoader.of(world(new ArrayList<>(), null, true, held), theirs).ticketsHeld());
    }

    @Test
    void aPluginWithNoTicketsHoldsZero() {
        assertEquals(0, NavChunkLoader.of(world(new ArrayList<>(), null, true, Map.of()), plugin()).ticketsHeld());
        assertEquals(0, NavChunkLoader.of(world(new ArrayList<>(), null, true, Map.of(plugin(), List.of(5L))), plugin()).ticketsHeld());
    }
}
