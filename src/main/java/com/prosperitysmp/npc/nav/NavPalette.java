package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import org.bukkit.Material;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of materials that count as a walkable support surface for a scan
 * (§10.1). A palette is "a seed, not unquestioned truth" — admins define named
 * palettes in config ({@code nav.palettes.<name>: [MATERIAL, ...]}); if a name is
 * unknown a broad built-in floor/road default is used.
 */
public final class NavPalette {

    private static final Set<Material> DEFAULT = defaultSet();

    private final String name;
    private final Set<Material> walkable;

    private NavPalette(String name, Set<Material> walkable) {
        this.name = name;
        this.walkable = walkable;
    }

    public String name() {
        return name;
    }

    public boolean isWalkable(Material material) {
        return walkable.contains(material);
    }

    public int size() {
        return walkable.size();
    }

    public static NavPalette load(ProsperityNpcPlugin plugin, String name) {
        List<String> configured = plugin.getConfig().getStringList("nav.palettes." + name);
        Set<Material> mats = new HashSet<>();
        for (String s : configured) {
            Material m = Material.matchMaterial(s);
            if (m != null) {
                mats.add(m);
            }
        }
        if (mats.isEmpty()) {
            mats = new HashSet<>(DEFAULT);
        }
        return new NavPalette(name, mats);
    }

    private static Set<Material> defaultSet() {
        Set<Material> s = EnumSet.noneOf(Material.class);
        String[] names = {
                "STONE", "STONE_BRICKS", "SMOOTH_STONE", "COBBLESTONE", "GRAVEL", "DIRT_PATH",
                "GRASS_BLOCK", "DIRT", "COARSE_DIRT", "PODZOL", "SAND", "RED_SAND", "SANDSTONE",
                "SMOOTH_SANDSTONE", "BRICKS", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES", "COBBLED_DEEPSLATE",
                "POLISHED_ANDESITE", "POLISHED_GRANITE", "POLISHED_DIORITE", "ANDESITE", "GRANITE", "DIORITE",
                "QUARTZ_BLOCK", "SMOOTH_QUARTZ", "QUARTZ_BRICKS", "BLACKSTONE", "POLISHED_BLACKSTONE",
                "POLISHED_BLACKSTONE_BRICKS", "GILDED_BLACKSTONE", "MUD_BRICKS", "PACKED_MUD",
                "OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "DARK_OAK_PLANKS", "ACACIA_PLANKS",
                "JUNGLE_PLANKS", "MANGROVE_PLANKS", "CHERRY_PLANKS", "BAMBOO_PLANKS", "CRIMSON_PLANKS",
                "WARPED_PLANKS", "WHITE_CONCRETE", "GRAY_CONCRETE", "LIGHT_GRAY_CONCRETE", "BLACK_CONCRETE",
                "BROWN_CONCRETE", "PRISMARINE", "PRISMARINE_BRICKS", "DARK_PRISMARINE", "END_STONE_BRICKS",
                "PURPUR_BLOCK", "NETHER_BRICKS", "RED_NETHER_BRICKS", "TERRACOTTA", "WHITE_TERRACOTTA",
                "GRAY_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA", "CUT_COPPER", "OXIDIZED_CUT_COPPER"
        };
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) {
                s.add(m);
            }
        }
        return s;
    }
}
