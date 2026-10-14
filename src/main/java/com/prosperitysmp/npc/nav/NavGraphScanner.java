package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Builds a draft {@link NavGraph} from real world blocks. It samples the
 * scan box on the main thread and delegates the node/edge rules to the pure
 * {@link NavScanLogic}. Capture is sliced across ticks and never loads a chunk.
 * Only the completed captured terrain may be passed to worker-side graph derivation.
 */
public final class NavGraphScanner {

    private final ProsperityNpcPlugin plugin;

    public NavGraphScanner(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    public Capture begin(World world, NavScanLogic.Bounds bounds, NavPalette palette) {
        requireMain();
        int headroom = plugin.getConfig().getInt("nav.headroom", 2);
        int maxStep = plugin.getConfig().getInt("nav.max-step", 1);
        NavScanLogic.validate(bounds, headroom, maxStep);
        if (bounds.minY() < world.getMinHeight() || (long) bounds.maxY() + headroom >= world.getMaxHeight()) {
            throw new IllegalArgumentException("scan and headroom must fit within world build height");
        }
        return new Capture(world, bounds, palette, headroom, maxStep);
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("world capture requires main thread");
    }

    public static final class Capture {
        private final World world;
        private final NavScanLogic.Bounds bounds;
        private final NavPalette palette;
        private final int headroom, maxStep;
        private final NavTerrainCapture terrain;

        private Capture(World world, NavScanLogic.Bounds bounds, NavPalette palette, int headroom, int maxStep) {
            this.world = world;
            this.bounds = bounds;
            this.palette = Objects.requireNonNull(palette);
            this.headroom = headroom;
            this.maxStep = maxStep;
            terrain = new NavTerrainCapture(bounds, headroom, maxStep);
        }

        public boolean advance() {
            requireMain();
            if (Bukkit.getWorld(world.getUID()) != world) throw new IllegalStateException("scan world was unloaded");
            return terrain.advance((x, y, z) -> {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    throw new IllegalStateException("scan contains an unloaded chunk; use a smaller loaded area");
                }
                Block block = world.getBlockAt(x, y, z);
                return (byte) ((palette.isWalkable(block.getType()) ? NavTerrainCapture.SUPPORT : 0)
                        | (block.isEmpty() || block.isPassable() ? NavTerrainCapture.CLEAR : 0));
            }, 512, 2_000_000L, System::nanoTime);
        }

        /** Worker-safe after advance returns true; no Bukkit or world calls. */
        public NavGraph finish() {
            if (!terrain.complete()) throw new IllegalStateException("scan is incomplete");
            return NavScanLogic.scan(bounds, terrain, headroom, maxStep);
        }
    }
}
