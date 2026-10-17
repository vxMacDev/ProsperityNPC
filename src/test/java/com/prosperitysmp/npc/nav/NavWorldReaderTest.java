package com.prosperitysmp.npc.nav;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The reader's one world-touching helper that reports can call after a sweep has finished. */
class NavWorldReaderTest {

    private static World world(boolean loaded, AtomicInteger blockReads) {
        Block stone = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getType")) return Material.STONE;
                    throw new UnsupportedOperationException(method.getName());
                });
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isChunkLoaded":
                            return loaded;
                        case "getBlockAt":
                            blockReads.incrementAndGet();
                            return stone;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @Test
    void aBlockInAChunkThatUnloadedSinceTheSweepIsNamedQuestionMarkWithoutLoadingIt() {
        AtomicInteger reads = new AtomicInteger();
        NavWorldReader reader = new NavWorldReader(world(false, reads), null);
        assertEquals("?", reader.materialName(100, 64, -100));
        assertEquals(0, reads.get(), "getBlockAt on an unloaded chunk would load it on the main thread");
    }

    @Test
    void aLoadedChunkIsNamedByItsBlock() {
        AtomicInteger reads = new AtomicInteger();
        NavWorldReader reader = new NavWorldReader(world(true, reads), null);
        assertEquals("STONE", reader.materialName(100, 64, -100));
        assertEquals(1, reads.get());
    }
}
