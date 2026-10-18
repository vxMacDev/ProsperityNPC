package com.prosperitysmp.npc.nav;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Reads one world cell for {@link NavSweep}: whether it is a palette block (and its class), solid,
 * and clear (passable and not liquid, as the walking check demands). Main thread only. A chunk that
 * is not loaded is reported, never loaded or generated here.
 */
public final class NavWorldReader implements NavSweep.Reader {

    private final World world;
    private final NavPalette palette;

    public NavWorldReader(World world, NavPalette palette) {
        this.world = world;
        this.palette = palette;
    }

    /** A reader with no palette: every solid block counts as support, with no class. Only for a classless sweep. */
    public static NavWorldReader anySolid(World world) {
        return new NavWorldReader(world, null);
    }

    @Override
    public byte read(int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return NavSweep.NOT_LOADED;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return NavTerrainCapture.CLEAR;
        }
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        int value = 0;
        if (palette == null) {
            if (type.isSolid()) {
                value |= NavTerrainCapture.SUPPORT;
            }
        } else if (palette.isWalkable(type)) {
            value |= NavTerrainCapture.SUPPORT | palette.surfaceOf(type).code() << NavTerrainCapture.SURFACE_SHIFT;
        }
        if (type.isSolid()) {
            value |= NavSweep.SOLID;
        }
        if ((block.isEmpty() || block.isPassable()) && !block.isLiquid()) {
            value |= NavTerrainCapture.CLEAR;
        }
        return (byte) value;
    }

    /** Names a block for a report. A chunk that unloaded since the sweep read it is "?", never loaded here. */
    public String materialName(int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return "?";
        }
        return world.getBlockAt(x, y, z).getType().name();
    }
}
