package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Generates a welcoming spawn plaza: patterned floor, fountain, lamp posts, planters,
 * floating info text, then protects it and sets it as the spawn point.
 */
public final class SpawnBuilder {
    private static final int R = 24;      // plaza radius
    private static final int CLEAR = 22;  // air cleared above the floor
    private final AetherCore plugin;
    private final NamespacedKey displayKey;

    public SpawnBuilder(AetherCore plugin) {
        this.plugin = plugin;
        this.displayKey = new NamespacedKey(plugin, "spawn_display");
    }

    /** Picks a floor height at the given column: ground level, never below sea level. */
    public static Location groundAt(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return new Location(world, x + 0.5, Math.max(y, world.getSeaLevel()), z + 0.5);
    }

    /** Re-creates only the plaza's floating text (used by /aether respawnnpcs). */
    public void refreshDisplays(Location center) {
        displays(center.getWorld(), center.getBlockX(), center.getBlockY(), center.getBlockZ());
    }

    public String build(Location center) {
        World world = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        for (int dx = -R - 2; dx <= R + 2; dx++) {
            for (int dz = -R - 2; dz <= R + 2; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 1.5) continue;
                int x = cx + dx, z = cz + dz;
                for (int y = cy + 1; y <= cy + CLEAR; y++) set(world, x, y, z, Material.AIR);
                if (d > R + 0.5) continue;
                // Solid foundation down to the terrain so the plaza never floats.
                for (int y = cy - 1; y >= Math.max(world.getMinHeight(), cy - 40); y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isSolid() && !b.isLiquid() && y < cy - 3) break;
                    b.setType(d > R - 1 ? Material.STONE_BRICKS : Material.STONE, false);
                }
                set(world, x, cy, z, floor(dx, dz, d));
                if (d > R - 0.5) {
                    set(world, x, cy + 1, z, Material.STONE_BRICK_WALL);
                }
            }
        }

        fountain(world, cx, cy, cz);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i + Math.PI / 8;
            lampPost(world, cx + (int) Math.round(Math.cos(a) * (R - 3)), cy, cz + (int) Math.round(Math.sin(a) * (R - 3)));
        }
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i + Math.PI / 4;
            planter(world, cx + (int) Math.round(Math.cos(a) * 12), cy, cz + (int) Math.round(Math.sin(a) * 12));
        }
        // Gaps in the wall where the four paths leave the plaza.
        for (int w = -1; w <= 1; w++) {
            set(world, cx + w, cy + 1, cz - R, Material.AIR);
            set(world, cx + w, cy + 1, cz + R, Material.AIR);
            set(world, cx - R, cy + 1, cz + w, Material.AIR);
            set(world, cx + R, cy + 1, cz + w, Material.AIR);
        }

        displays(world, cx, cy, cz);

        Location spawn = new Location(world, cx + 0.5, cy + 1, cz + 6.5, 180f, 0f);
        world.setSpawnLocation(spawn);
        world.setGameRule(org.bukkit.GameRules.RESPAWN_RADIUS, 0);
        if (Bukkit.getPluginManager().isPluginEnabled("Essentials")) {
            // EssentialsSpawn stores its own spawn; write it directly so this works from console too.
            var ess = Bukkit.getPluginManager().getPlugin("Essentials").getDataFolder();
            var file = new java.io.File(ess, "spawn.yml");
            var yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            yml.set("spawns.default.world", world.getName());
            yml.set("spawns.default.x", spawn.getX());
            yml.set("spawns.default.y", spawn.getY());
            yml.set("spawns.default.z", spawn.getZ());
            yml.set("spawns.default.yaw", spawn.getYaw());
            yml.set("spawns.default.pitch", spawn.getPitch());
            try { yml.save(file); } catch (java.io.IOException e) { plugin.getLogger().warning("Could not write spawn.yml: " + e.getMessage()); }
        }
        boolean protectedOk = Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && WorldGuardHook.protectSpawn(spawn, 64);
        return "Spawn built at " + cx + ", " + cy + ", " + cz + (protectedOk ? " and protected (WorldGuard region 'spawn', radius 64)." : ".")
                + " Restart or run 'essentials reload' so EssentialsSpawn picks it up.";
    }

    private static Material floor(int dx, int dz, double d) {
        int ring = (int) Math.floor(d);
        boolean path = Math.abs(dx) <= 1 || Math.abs(dz) <= 1;
        if (d > R - 0.5) return Material.POLISHED_DEEPSLATE;
        if (path && d > 4) return (Math.abs(dx) == 1 || Math.abs(dz) == 1) ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE;
        if (ring == 5 || ring == 11 || ring == 17) return Material.POLISHED_BLACKSTONE_BRICKS;
        if (ring == 6 || ring == 12 || ring == 18) return Material.CHISELED_STONE_BRICKS;
        return ((dx + dz) & 1) == 0 ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE;
    }

    private static void fountain(World w, int cx, int cy, int cz) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            int x = cx + dx, z = cz + dz;
            if (d <= 3.2) {
                set(w, x, cy - 1, z, Material.PRISMARINE_BRICKS);
                set(w, x, cy, z, Material.WATER);
            } else if (d <= 4.3) {
                set(w, x, cy, z, Material.QUARTZ_BRICKS);
                set(w, x, cy + 1, z, Material.QUARTZ_SLAB);
            }
        }
        for (int y = cy; y <= cy + 3; y++) set(w, cx, y, cz, Material.QUARTZ_PILLAR);
        set(w, cx, cy + 4, cz, Material.SEA_LANTERN);
        set(w, cx, cy + 5, cz, Material.AMETHYST_CLUSTER);
        set(w, cx, cy - 1, cz, Material.SEA_LANTERN);
        for (BlockFace f : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            set(w, cx + f.getModX() * 2, cy - 1, cz + f.getModZ() * 2, Material.SEA_LANTERN);
        }
    }

    private static void lampPost(World w, int x, int cy, int z) {
        set(w, x, cy + 1, z, Material.POLISHED_BLACKSTONE_WALL);
        set(w, x, cy + 2, z, Material.DARK_OAK_FENCE);
        set(w, x, cy + 3, z, Material.DARK_OAK_FENCE);
        Block lantern = w.getBlockAt(x, cy + 4, z);
        lantern.setType(Material.LANTERN, false);
        if (lantern.getBlockData() instanceof Lantern data) {
            data.setHanging(false);
            lantern.setBlockData(data, false);
        }
    }

    private static void planter(World w, int x, int cy, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            boolean edge = dx != 0 || dz != 0;
            set(w, x + dx, cy, z + dz, Material.MOSS_BLOCK);
            set(w, x + dx, cy + 1, z + dz, edge ? Material.FLOWERING_AZALEA_LEAVES : Material.FLOWERING_AZALEA);
        }
        set(w, x, cy + 1, z, Material.MOSS_BLOCK);
        set(w, x, cy + 2, z, Material.FLOWERING_AZALEA);
        // Floor-level spruce trim around each planter.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) set(w, x + dx, cy, z + dz, Material.STRIPPED_SPRUCE_WOOD);
        }
        // Leaves placed by code would decay; mark them persistent.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Block b = w.getBlockAt(x + dx, cy + 1, z + dz);
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Leaves leaves) {
                leaves.setPersistent(true);
                b.setBlockData(leaves, false);
            }
        }
    }

    private void displays(World w, int cx, int cy, int cz) {
        // Remove displays from a previous build so re-running is clean.
        for (Entity e : w.getNearbyEntities(new Location(w, cx, cy, cz), R + 4, 30, R + 4)) {
            if (e.getPersistentDataContainer().has(displayKey)) e.remove();
        }
        text(w, cx + 0.5, cy + 7.2, cz + 0.5, 2.2f,
                "<gradient:#8B5CF6:#22D3EE><bold>✦ AETHERFALL ✦</bold></gradient>\n<gray>Welcome, traveller!");
        text(w, cx + 0.5, cy + 3.2, cz + 5.5, 1.0f,
                "<yellow><bold>New here?</bold>\n<white>Type <gold>/menu</gold> to begin\n<gray>Daily rewards · Quests · Ranks");
        text(w, cx + 0.5, cy + 3.0, cz - R + 2.5, 1.0f,
                "<aqua><bold>Explore the wild</bold>\n<white>/rtp <gray>teleports you somewhere new\n<gray>Claim land with a <yellow>golden shovel");
        text(w, cx + R - 2.5, cy + 3.0, cz + 0.5, 1.0f,
                "<green><bold>Level up</bold>\n<white>/skills <gray>· <white>/quests\n<gray>Everything you do earns XP");
        text(w, cx - R + 2.5, cy + 3.0, cz + 0.5, 1.0f,
                "<light_purple><bold>Community</bold>\n<white>/discord <gray>· <white>/map\n<gray>Be kind. Have fun!");
    }

    private void text(World w, double x, double y, double z, float scale, String mini) {
        w.spawn(new Location(w, x, y, z), TextDisplay.class, d -> {
            d.text(Text.mm(mini));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(96, 0, 0, 0));
            d.setViewRange(1.5f);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true);
            d.getPersistentDataContainer().set(new NamespacedKey(plugin, "build"), PersistentDataType.LONG,
                    plugin.getConfig().getLong("village-build-id", 0));
        });
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }
}
