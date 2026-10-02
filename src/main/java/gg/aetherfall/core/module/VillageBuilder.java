package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Builds the Aetherfall village around the spawn plaza: levelled town ground, streets,
 * a wall with gates, themed shop buildings with NPCs, homes, farms, a park and the boss arena.
 * Work is queued and spread across ticks so the server never freezes.
 */
public final class VillageBuilder {
    public static final int VR = 76;        // village radius (wall)
    private static final int ROAD = 2;      // main roads are |offset| <= 2 wide
    private final AetherCore plugin;
    private final NamespacedKey npcKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey buildKey;
    private boolean entitiesOnly;
    private long buildId;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private World w;
    private int cx, cy, cz;
    private final Random rnd = new Random(7);

    public VillageBuilder(AetherCore plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "npc");
        this.displayKey = new NamespacedKey(plugin, "spawn_display");
        this.buildKey = new NamespacedKey(plugin, "build");
    }

    public void build(Location center, CommandSender sender) {
        build(center, sender, false);
    }

    /** entitiesOnly = only re-create NPCs and floating signs (no block changes). */
    public void build(Location center, CommandSender sender, boolean entitiesOnly) {
        this.entitiesOnly = entitiesOnly;
        w = center.getWorld();
        cx = center.getBlockX();
        cy = center.getBlockY();
        cz = center.getBlockZ();
        long start = System.currentTimeMillis();
        buildId = start;
        plugin.getConfig().set("village-build-id", buildId);
        plugin.getConfig().set("village-center", w.getName() + "," + cx + "," + cy + "," + cz);
        plugin.saveConfig();
        sender.sendMessage(Text.mm("<gray>Building the village around " + cx + ", " + cy + ", " + cz + " — this takes a few seconds..."));

        clearOldEntities();
        // 1) Terrain: level the town disc, fill the sea, clear hills and trees above.
        if (!entitiesOnly) for (int dx = -VR - 3; dx <= VR + 3; dx++) {
            final int fdx = dx;
            queue.add(() -> terraformRow(fdx));
        }
        // 2) Streets, wall, gates.
        queue.add(this::streets);
        queue.add(this::wall);
        // 3) Plaza in the middle (reuses the plaza builder; it also sets spawn + region).
        if (entitiesOnly) queue.add(() -> new SpawnBuilder(plugin).refreshDisplays(new Location(w, cx, cy, cz)));
        else queue.add(() -> sender.sendMessage(Text.mm("<gray>" + new SpawnBuilder(plugin).build(new Location(w, cx, cy, cz)))));
        // 4) Buildings.
        queue.add(this::bazaarHall);
        queue.add(this::generalStore);
        queue.add(this::forge);
        queue.add(this::questGuild);
        queue.add(this::tavern);
        queue.add(this::farm);
        queue.add(this::park);
        int[][] houses = {{-12, -60, 'E'}, {12, -64, 'W'}, {62, 13, 'N'}, {62, -13, 'S'}, {-12, 64, 'E'}, {12, 64, 'W'},
                {-42, -13, 'S'}, {-62, -13, 'S'}, {-62, 13, 'N'}, {-14, -32, 'E'}, {40, 34, 'N'}, {-40, 36, 'N'}};
        for (int[] h : houses) queue.add(() -> house(cx + h[0], cz + h[1], (char) h[2]));
        queue.add(this::decorations);
        // 5) Arena outside the south gate.
        queue.add(this::arena);
        // 6) Protection, warps, NPCs.
        queue.add(() -> finish(sender, start));
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            long budget = System.nanoTime() + 30_000_000L; // ~30 ms per tick
            while (!queue.isEmpty() && System.nanoTime() < budget) queue.poll().run();
            if (queue.isEmpty()) task.cancel();
        }, 1L, 1L);
    }

    // ── helpers ───────────────────────────────────────────────

    private void set(int x, int y, int z, Material m) {
        if (entitiesOnly) return;
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void set(int x, int y, int z, String data) {
        if (entitiesOnly) return;
        BlockData bd = Bukkit.createBlockData(data);
        w.getBlockAt(x, y, z).setBlockData(bd, false);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, m);
    }

    private static double dist(int dx, int dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void clearOldEntities() {
        Location c = new Location(w, cx, cy, cz);
        for (Entity e : w.getNearbyEntities(c, VR + 80, 80, VR + 80)) {
            var pdc = e.getPersistentDataContainer();
            if (pdc.has(npcKey) || pdc.has(displayKey)) e.remove();
        }
    }

    // ── terrain ───────────────────────────────────────────────

    private void terraformRow(int dx) {
        for (int dz = -VR - 3; dz <= VR + 3; dz++) {
            double d = dist(dx, dz);
            if (d > VR + 2.5) continue;
            int x = cx + dx, z = cz + dz;
            for (int y = cy + 1; y <= cy + 40; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
            // Solid ground: stone below, dirt, then grass on top.
            for (int y = cy - 1; y >= Math.max(w.getMinHeight() + 1, cy - 45); y--) {
                Block b = w.getBlockAt(x, y, z);
                boolean solid = b.getType().isSolid() && !b.isLiquid() && b.getType() != Material.ICE;
                if (solid && y < cy - 4) break;
                b.setType(y >= cy - 3 ? Material.DIRT : Material.STONE, false);
            }
            set(x, cy, z, Material.GRASS_BLOCK);
        }
    }

    // ── streets & wall ────────────────────────────────────────

    private void streets() {
        for (int dx = -VR; dx <= VR; dx++) for (int dz = -VR; dz <= VR; dz++) {
            double d = dist(dx, dz);
            if (d > VR - 1 || d < 24.5) continue;
            boolean main = (Math.abs(dx) <= ROAD || Math.abs(dz) <= ROAD);
            boolean ring = d >= 24.5 && d <= 28.5;
            if (!main && !ring) continue;
            Material m = (Math.abs(dx) == ROAD + 0 && Math.abs(dz) > ROAD) || (Math.abs(dz) == ROAD && Math.abs(dx) > ROAD)
                    ? Material.COBBLESTONE : (rnd.nextInt(5) == 0 ? Material.GRAVEL : Material.DIRT_PATH);
            if (ring && !main) m = rnd.nextInt(4) == 0 ? Material.COARSE_DIRT : Material.DIRT_PATH;
            set(cx + dx, cy, cz + dz, m);
        }
        // Street lamps every 12 blocks on both sides of each main road.
        for (int r = 34; r < VR - 4; r += 12) {
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int px = dir[0] * r, pz = dir[1] * r;
                int sx = dir[1] != 0 ? 1 : 0, sz = dir[0] != 0 ? 1 : 0;
                lamp(cx + px + sx * (ROAD + 1), cz + pz + sz * (ROAD + 1));
                lamp(cx + px - sx * (ROAD + 1), cz + pz - sz * (ROAD + 1));
            }
        }
    }

    private void lamp(int x, int z) {
        set(x, cy + 1, z, Material.SPRUCE_FENCE);
        set(x, cy + 2, z, Material.SPRUCE_FENCE);
        set(x, cy + 3, z, Material.SPRUCE_FENCE);
        set(x, cy + 4, z, "minecraft:lantern[hanging=false]");
    }

    private void wall() {
        for (int dx = -VR - 2; dx <= VR + 2; dx++) for (int dz = -VR - 2; dz <= VR + 2; dz++) {
            double d = dist(dx, dz);
            if (d < VR - 0.5 || d > VR + 1.5) continue;
            boolean gate = Math.abs(dx) <= ROAD + 1 || Math.abs(dz) <= ROAD + 1;
            int x = cx + dx, z = cz + dz;
            set(x, cy, z, Material.STONE_BRICKS);
            if (gate) continue;
            for (int y = cy + 1; y <= cy + 4; y++) set(x, y, z, y == cy + 1 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            set(x, cy + 5, z, (dx + dz) % 2 == 0 ? Material.STONE_BRICK_WALL : Material.AIR);
        }
        // Gatehouses: towers on both sides of each road.
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int side : new int[]{-1, 1}) {
                int tx = cx + dir[0] * VR + (dir[1] != 0 ? side * (ROAD + 3) : 0);
                int tz = cz + dir[1] * VR + (dir[0] != 0 ? side * (ROAD + 3) : 0);
                fill(tx - 1, cy + 1, tz - 1, tx + 1, cy + 7, tz + 1, Material.STONE_BRICKS);
                fill(tx - 1, cy + 8, tz - 1, tx + 1, cy + 8, tz + 1, Material.STONE_BRICK_SLAB);
                set(tx, cy + 8, tz, "minecraft:lantern[hanging=false]");
            }
        }
    }

    // ── buildings ─────────────────────────────────────────────

    private record Style(Material floor, Material base, Material wall, Material corner, String stairs, Material ridge, Material gable) {}

    private static final Style TIMBER = new Style(Material.SPRUCE_PLANKS, Material.STONE_BRICKS, Material.OAK_PLANKS, Material.STRIPPED_SPRUCE_LOG,
            "minecraft:dark_oak_stairs", Material.DARK_OAK_SLAB, Material.OAK_PLANKS);
    private static final Style STONE = new Style(Material.POLISHED_ANDESITE, Material.COBBLESTONE, Material.STONE_BRICKS, Material.POLISHED_DEEPSLATE,
            "minecraft:deepslate_tile_stairs", Material.DEEPSLATE_TILE_SLAB, Material.STONE_BRICKS);
    private static final Style MARKET = new Style(Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE, Material.STRIPPED_BIRCH_LOG,
            "minecraft:brick_stairs", Material.BRICK_SLAB, Material.SMOOTH_SANDSTONE);
    private static final Style COZY = new Style(Material.OAK_PLANKS, Material.COBBLESTONE, Material.WHITE_TERRACOTTA, Material.DARK_OAK_LOG,
            "minecraft:spruce_stairs", Material.SPRUCE_SLAB, Material.SPRUCE_PLANKS);

    /**
     * Builds a gabled building centred on (x, z), w wide (x), d deep (z), walls h high,
     * with the door on side 'N','S','E','W'. Returns the inside floor y.
     */
    private void building(int x, int z, int wid, int dep, int h, Style s, char door, int doorWidth) {
        int x1 = x - wid / 2, x2 = x1 + wid - 1, z1 = z - dep / 2, z2 = z1 + dep - 1;
        // Clear space and lay the floor.
        fill(x1 - 1, cy + 1, z1 - 1, x2 + 1, cy + h + dep, z2 + 1, Material.AIR);
        fill(x1, cy, z1, x2, cy, z2, s.floor);
        for (int bx = x1; bx <= x2; bx++) for (int bz = z1; bz <= z2; bz++) {
            boolean edge = bx == x1 || bx == x2 || bz == z1 || bz == z2;
            if (!edge) continue;
            boolean corner = (bx == x1 || bx == x2) && (bz == z1 || bz == z2);
            for (int y = cy + 1; y <= cy + h; y++) {
                Material m = corner ? s.corner : (y == cy + 1 ? s.base : s.wall);
                // Windows: every other block, middle rows.
                boolean alongX = bz == z1 || bz == z2;
                int pos = alongX ? bx - x1 : bz - z1;
                int len = alongX ? wid : dep;
                if (!corner && (y == cy + 2 || y == cy + 3) && pos % 3 == 2 && pos < len - 1 && h >= 4) m = Material.GLASS_PANE;
                set(bx, y, bz, m);
            }
        }
        // Door opening(s) with doors.
        int half = doorWidth / 2;
        for (int o = -half; o <= half; o++) {
            int dx = 0, dz = 0;
            String facing;
            switch (door) {
                case 'N' -> { dx = x + o; dz = z1; facing = "north"; }
                case 'S' -> { dx = x + o; dz = z2; facing = "south"; }
                case 'E' -> { dx = x2; dz = z + o; facing = "east"; }
                default -> { dx = x1; dz = z + o; facing = "west"; }
            }
            if (doorWidth >= 3) {
                set(dx, cy + 1, dz, Material.AIR);
                set(dx, cy + 2, dz, Material.AIR);
                set(dx, cy + 3, dz, Material.AIR);
            } else {
                set(dx, cy + 1, dz, "minecraft:spruce_door[facing=" + facing + ",half=lower,hinge=left,open=false]");
                set(dx, cy + 2, dz, "minecraft:spruce_door[facing=" + facing + ",half=upper,hinge=left,open=false]");
            }
        }
        // Ceiling lanterns.
        set(x, cy + h, z, "minecraft:lantern[hanging=true]");
        if (wid > 9) {
            set(x1 + 2, cy + h, z, "minecraft:lantern[hanging=true]");
            set(x2 - 2, cy + h, z, "minecraft:lantern[hanging=true]");
        }
        roof(x1, x2, z1, z2, cy + h + 1, s);
    }

    private void roof(int x1, int x2, int z1, int z2, int y0, Style s) {
        boolean ridgeAlongX = (x2 - x1) >= (z2 - z1);
        if (ridgeAlongX) {
            int lo = z1 - 1, hi = z2 + 1, y = y0;
            while (lo < hi) {
                for (int x = x1 - 1; x <= x2 + 1; x++) {
                    set(x, y, lo, s.stairs + "[facing=south,half=bottom]");
                    set(x, y, hi, s.stairs + "[facing=north,half=bottom]");
                }
                for (int zz = lo + 1; zz < hi; zz++) { set(x1, y, zz, s.gable); set(x2, y, zz, s.gable); }
                lo++; hi--; y++;
            }
            if (lo == hi) for (int x = x1 - 1; x <= x2 + 1; x++) set(x, y, lo, s.ridge);
        } else {
            int lo = x1 - 1, hi = x2 + 1, y = y0;
            while (lo < hi) {
                for (int z = z1 - 1; z <= z2 + 1; z++) {
                    set(lo, y, z, s.stairs + "[facing=east,half=bottom]");
                    set(hi, y, z, s.stairs + "[facing=west,half=bottom]");
                }
                for (int xx = lo + 1; xx < hi; xx++) { set(xx, y, z1, s.gable); set(xx, y, z2, s.gable); }
                lo++; hi--; y++;
            }
            if (lo == hi) for (int z = z1 - 1; z <= z2 + 1; z++) set(lo, y, z, s.ridge);
        }
    }

    private void bazaarHall() {
        int x = cx + 15, z = cz - 44;
        building(x, z, 19, 13, 6, MARKET, 'W', 3);
        // Trading counters and goods inside.
        for (int dz = -4; dz <= 4; dz += 4) {
            fill(x + 3, cy + 1, z + dz - 1, x + 3, cy + 1, z + dz + 1, Material.BARREL);
        }
        for (int dx = -6; dx <= 0; dx += 3) set(x + dx, cy + 1, z - 5, Material.CHEST);
        fill(x - 7, cy + 1, z + 5, x - 5, cy + 1, z + 5, Material.HAY_BLOCK);
        set(x - 4, cy + 1, z + 5, Material.COMPOSTER);
        npc(x + 5, z - 2, "Bazaar Merchant", "<gold><bold>", Villager.Profession.CARTOGRAPHER, "bazaar", "Right-click to trade materials");
        npc(x + 5, z + 2, "Auctioneer", "<yellow><bold>", Villager.Profession.LIBRARIAN, "ah", "Right-click for the Auction House");
        sign(x - 9 - 1.2, z + 0.5, "<gold><bold>✦ BAZAAR & AUCTIONS ✦</bold>\n<gray>Materials · Gear · Rare items");
        // Market stalls along the road outside.
        for (int dx = 8; dx <= 18; dx += 5) stall(cx + dx, cz - 34, rnd.nextInt(4));
    }

    private void stall(int x, int z, int color) {
        String[] wool = {"RED", "YELLOW", "LIME", "LIGHT_BLUE"};
        for (int dz = -1; dz <= 1; dz += 2) {
            set(x, cy + 1, z + dz, Material.SPRUCE_FENCE);
            set(x, cy + 2, z + dz, Material.SPRUCE_FENCE);
            set(x + 2, cy + 1, z + dz, Material.SPRUCE_FENCE);
            set(x + 2, cy + 2, z + dz, Material.SPRUCE_FENCE);
        }
        fill(x, cy + 3, z - 1, x + 2, cy + 3, z + 1, Material.valueOf(wool[color] + "_WOOL"));
        set(x + 1, cy + 1, z, Material.BARREL);
        set(x + 1, cy + 1, z - 1, Material.PUMPKIN);
        set(x + 1, cy + 1, z + 1, Material.MELON);
    }

    private void generalStore() {
        int x = cx - 13, z = cz - 42;
        building(x, z, 11, 9, 5, TIMBER, 'E', 1);
        fill(x - 2, cy + 1, z - 2, x - 2, cy + 1, z + 2, Material.BARREL);
        fill(x - 4, cy + 1, z - 3, x + 2, cy + 1, z - 3, Material.BOOKSHELF);
        npc(x - 3, z, "Shopkeeper", "<green><bold>", Villager.Profession.FARMER, "shop", "Right-click to buy & sell");
        sign(x + 6.8, z + 0.5, "<green><bold>✦ GENERAL STORE ✦</bold>\n<gray>Buy basics · Sell anything");
    }

    private void forge() {
        int x = cx + 43, z = cz + 13;
        building(x, z, 13, 9, 5, STONE, 'N', 1);
        set(x - 4, cy + 1, z + 2, Material.ANVIL);
        set(x - 2, cy + 1, z + 3, Material.BLAST_FURNACE);
        set(x - 1, cy + 1, z + 3, Material.FURNACE);
        set(x, cy + 1, z + 3, Material.SMITHING_TABLE);
        set(x + 2, cy + 1, z + 3, Material.GRINDSTONE);
        set(x + 4, cy + 1, z + 3, Material.LAVA_CAULDRON);
        // Chimney.
        fill(x + 4, cy + 6, z + 2, x + 4, cy + 11, z + 2, Material.BRICKS);
        set(x + 4, cy + 12, z + 2, Material.CAMPFIRE);
        npc(x, z + 1, "Blacksmith", "<gold><bold>", Villager.Profession.WEAPONSMITH, "forge", "Right-click to forge custom gear");
        sign(x + 0.5, z - 5.2, "<gold><bold>⚒ AETHER FORGE ⚒</bold>\n<gray>Custom tools · Armor · Relics");
    }

    private void questGuild() {
        int x = cx + 43, z = cz - 13;
        building(x, z, 13, 9, 5, TIMBER, 'S', 1);
        fill(x - 5, cy + 1, z - 3, x + 5, cy + 2, z - 3, Material.BOOKSHELF);
        set(x - 3, cy + 1, z, Material.LECTERN);
        set(x + 3, cy + 1, z, Material.CARTOGRAPHY_TABLE);
        npc(x - 1, z - 1, "Quest Master", "<aqua><bold>", Villager.Profession.LIBRARIAN, "quests", "Right-click for daily quests");
        npc(x + 2, z - 1, "Reward Keeper", "<yellow><bold>", Villager.Profession.CLERIC, "daily", "Right-click for daily rewards");
        sign(x + 0.5, z + 5.2, "<aqua><bold>✦ QUEST GUILD ✦</bold>\n<gray>Daily quests · Rewards · Ranks");
    }

    private void tavern() {
        int x = cx - 15, z = cz + 44;
        building(x, z, 15, 11, 5, COZY, 'E', 1);
        for (int dz = -3; dz <= 3; dz += 3) {
            set(x - 2, cy + 1, z + dz, Material.SPRUCE_FENCE);
            set(x - 2, cy + 2, z + dz, Material.SPRUCE_PRESSURE_PLATE);
            set(x - 3, cy + 1, z + dz, "minecraft:spruce_stairs[facing=east]");
            set(x - 1, cy + 1, z + dz, "minecraft:spruce_stairs[facing=west]");
        }
        fill(x - 6, cy + 1, z - 4, x - 6, cy + 1, z + 4, Material.BARREL);
        set(x - 5, cy + 1, z, Material.BREWING_STAND);
        fill(x - 6, cy + 5, z - 4, x - 6, cy + 10, z - 4, Material.BRICKS);
        set(x - 6, cy + 11, z - 4, Material.CAMPFIRE);
        npc(x - 4, z + 2, "Innkeeper", "<light_purple><bold>", Villager.Profession.NITWIT, "menu", "Right-click for the main menu");
        sign(x + 8.8, z + 0.5, "<light_purple><bold>✦ THE GILDED TANKARD ✦</bold>\n<gray>Rest, chat & meet travellers");
    }

    private void farm() {
        int x1 = cx + 8, z1 = cz + 36, x2 = cx + 26, z2 = cz + 54;
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            boolean border = x == x1 || x == x2 || z == z1 || z == z2;
            if (border) {
                set(x, cy, z, Material.OAK_LOG);
                set(x, cy + 1, z, Material.OAK_FENCE);
                continue;
            }
            if ((x - x1) % 5 == 0) {
                set(x, cy, z, Material.WATER);
                continue;
            }
            set(x, cy, z, "minecraft:farmland[moisture=7]");
            String crop = ((z - z1) / 5) % 3 == 0 ? "wheat" : ((z - z1) / 5) % 3 == 1 ? "carrots" : "potatoes";
            set(x, cy + 1, z, "minecraft:" + crop + "[age=" + (4 + rnd.nextInt(4)) + "]");
        }
        set(x1, cy + 1, (z1 + z2) / 2, "minecraft:oak_fence_gate[facing=west]");
        // Scarecrow.
        set(x2 - 2, cy + 1, z1 + 2, Material.OAK_FENCE);
        set(x2 - 2, cy + 2, z1 + 2, Material.HAY_BLOCK);
        set(x2 - 2, cy + 3, z1 + 2, Material.CARVED_PUMPKIN);
    }

    private void park() {
        int x = cx - 44, z = cz + 14;
        for (int dx = -9; dx <= 9; dx++) for (int dz = -8; dz <= 8; dz++) {
            if (rnd.nextInt(7) == 0) set(x + dx, cy + 1, z + dz, flowers());
        }
        // Stone well.
        fill(x - 1, cy, z - 1, x + 1, cy, z + 1, Material.WATER);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) set(x + dx, cy + 1, z + dz, Material.COBBLESTONE_WALL);
        }
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            set(x + c[0], cy + 2, z + c[1], Material.OAK_FENCE);
            set(x + c[0], cy + 3, z + c[1], Material.OAK_FENCE);
        }
        fill(x - 2, cy + 4, z - 2, x + 2, cy + 4, z + 2, Material.SPRUCE_SLAB);
        tree(x - 7, z - 5); tree(x + 7, z - 5); tree(x - 7, z + 6); tree(x + 6, z + 6);
    }

    private Material flowers() {
        Material[] f = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.ALLIUM, Material.AZURE_BLUET, Material.SHORT_GRASS, Material.SHORT_GRASS};
        return f[rnd.nextInt(f.length)];
    }

    private void tree(int x, int z) {
        for (int y = 1; y <= 5; y++) set(x, cy + y, z, Material.OAK_LOG);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 6; dy++) {
            int r = dy >= 5 ? 1 : 2;
            if (Math.abs(dx) > r || Math.abs(dz) > r || (Math.abs(dx) == 2 && Math.abs(dz) == 2)) continue;
            Block b = w.getBlockAt(x + dx, cy + dy, z + dz);
            if (!b.getType().isAir() || entitiesOnly) continue;
            b.setType(Material.OAK_LEAVES, false);
            if (b.getBlockData() instanceof Leaves l) { l.setPersistent(true); b.setBlockData(l, false); }
        }
    }

    private void house(int x, int z, char door) {
        Style[] styles = {TIMBER, COZY, TIMBER, COZY, STONE};
        Style s = styles[Math.floorMod(x * 31 + z, styles.length)];
        boolean ns = door == 'N' || door == 'S';
        building(x, z, ns ? 9 : 7, ns ? 7 : 9, 4, s, door, 1);
        set(x, cy + 1, z, Material.CRAFTING_TABLE);
        set(x + 1, cy + 1, z, Material.BARREL);
        set(x - 1, cy + 1, z + (ns ? 0 : 1), "minecraft:red_bed[facing=" + (ns ? "west" : "south") + ",part=head]");
        // Garden patch in front.
        int fx = door == 'E' ? x + 6 : door == 'W' ? x - 6 : x;
        int fz = door == 'S' ? z + 6 : door == 'N' ? z - 6 : z;
        for (int o = -1; o <= 1; o++) {
            int gx = ns ? fx + o * 3 : fx, gz = ns ? fz : fz + o * 3;
            if (Math.abs(gx - cx) <= ROAD + 1 || Math.abs(gz - cz) <= ROAD + 1) continue;
            set(gx, cy + 1, gz, flowers());
        }
    }

    private void decorations() {
        // Trees and flowers scattered in the green spaces between buildings.
        int[][] trees = {{30, 30}, {-30, 30}, {30, -30}, {-30, -30}, {52, 52}, {-52, 52}, {52, -52}, {-52, -52}, {60, 30}, {-28, -54}, {28, 58}};
        for (int[] t : trees) tree(cx + t[0], cz + t[1]);
        for (int i = 0; i < 260; i++) {
            int dx = rnd.nextInt(VR * 2) - VR, dz = rnd.nextInt(VR * 2) - VR;
            double d = dist(dx, dz);
            if (d < 30 || d > VR - 3 || Math.abs(dx) <= ROAD + 1 || Math.abs(dz) <= ROAD + 1) continue;
            Block top = w.getBlockAt(cx + dx, cy, cz + dz);
            Block above = top.getRelative(0, 1, 0);
            if (!entitiesOnly && top.getType() == Material.GRASS_BLOCK && above.getType().isAir()) above.setType(flowers(), false);
        }
        // Benches along the ring road.
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i + Math.PI / 8;
            int bx = cx + (int) Math.round(Math.cos(a) * 30), bz = cz + (int) Math.round(Math.sin(a) * 30);
            set(bx, cy + 1, bz, "minecraft:spruce_stairs[facing=" + (Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? "east" : "west") : (Math.sin(a) > 0 ? "south" : "north")) + "]");
        }
    }

    // ── arena ─────────────────────────────────────────────────

    private int arenaZ() {
        return cz + VR + 38;
    }

    private void arena() {
        int ax = cx, az = arenaZ(), r = 18;
        // Road from the south gate to the arena.
        for (int z = cz + VR; z <= az - r - 4; z++) for (int x = cx - ROAD; x <= cx + ROAD; x++) {
            groundColumn(x, z);
            set(x, cy, z, Math.abs(x - cx) == ROAD ? Material.COBBLESTONE : Material.DIRT_PATH);
        }
        for (int dx = -r - 6; dx <= r + 6; dx++) for (int dz = -r - 6; dz <= r + 6; dz++) {
            double d = dist(dx, dz);
            if (d > r + 6.5) continue;
            int x = ax + dx, z = az + dz;
            groundColumn(x, z);
            if (d <= r) {
                set(x, cy, z, ((dx + dz) & 1) == 0 ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE);
                if (d > r - 1) for (int y = cy + 1; y <= cy + 3; y++) set(x, y, z, Material.STONE_BRICKS);
            } else {
                // Tiered stands for spectators.
                int tier = (int) Math.min(5, d - r);
                for (int y = cy + 1; y <= cy + tier; y++) set(x, y, z, Material.STONE_BRICKS);
            }
        }
        // Entrance on the north side, braziers around the ring.
        for (int x = ax - 1; x <= ax + 1; x++) for (int z = az - r - 6; z <= az - r + 1; z++) for (int y = cy + 1; y <= cy + 6; y++) set(x, y, z, Material.AIR);
        for (int x = ax - 1; x <= ax + 1; x++) for (int z = az - r - 6; z <= az - r + 1; z++) set(x, cy, z, Material.DIRT_PATH);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            int bx = ax + (int) Math.round(Math.cos(a) * (r - 2)), bz = az + (int) Math.round(Math.sin(a) * (r - 2));
            set(bx, cy + 1, bz, Material.CAMPFIRE);
        }
        sign(ax + 0.5, az - r - 6 + 0.5 + 3, "<dark_red><bold>☠ BOSS ARENA ☠</bold>\n<gray>World bosses appear here\n<gray>Summon relics: <yellow>/forge");
        plugin.getConfig().set("world-boss-arena", w.getName() + "," + (ax + 0.5) + "," + (cy + 1) + "," + (az + 0.5));
        plugin.saveConfig();
    }

    /** Fills a column down to the ground and clears above so outside builds don't float. */
    private void groundColumn(int x, int z) {
        if (entitiesOnly) return;
        for (int y = cy + 1; y <= cy + 30; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().isAir()) b.setType(Material.AIR, false);
        }
        for (int y = cy - 1; y >= Math.max(w.getMinHeight() + 1, cy - 45); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && !b.isLiquid() && y < cy - 3) break;
            b.setType(y >= cy - 3 ? Material.DIRT : Material.STONE, false);
        }
        set(x, cy, z, Material.GRASS_BLOCK);
    }

    // ── NPCs, signs, finishing ────────────────────────────────

    private void npc(int x, int z, String name, String color, Villager.Profession profession, String action, String hint) {
        Location at = new Location(w, x + 0.5, cy + 1, z + 0.5);
        w.spawn(at, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.setProfession(profession);
            v.setVillagerLevel(5);
            v.customName(Text.mm(color + name));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, action);
            v.getPersistentDataContainer().set(buildKey, PersistentDataType.LONG, buildId);
        });
        w.spawn(at.clone().add(0, 2.75, 0), TextDisplay.class, d -> {
            d.text(Text.mm("<gray>" + hint));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
            d.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true);
            d.getPersistentDataContainer().set(buildKey, PersistentDataType.LONG, buildId);
        });
    }

    private void sign(double x, double z, String mini) {
        w.spawn(new Location(w, x, cy + 5.2, z), TextDisplay.class, d -> {
            d.text(Text.mm(mini));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 0, 0, 0));
            d.setViewRange(1.2f);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.3f, 1.3f, 1.3f), new AxisAngle4f()));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true);
            d.getPersistentDataContainer().set(buildKey, PersistentDataType.LONG, buildId);
        });
    }

    private void finish(CommandSender sender, long start) {
        Location spawn = w.getSpawnLocation();
        boolean wg = Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
        if (wg) {
            WorldGuardHook.protectSpawn(spawn, VR + 8);
            WorldGuardHook.protectArena(new Location(w, cx, cy, arenaZ()), 26);
        }
        writeWarp("arena", new Location(w, cx + 0.5, cy + 1, arenaZ() - 26 + 0.5, 0f, 0f));
        writeWarp("bazaar", new Location(w, cx + 3.5, cy + 1, cz - 44 + 0.5, -90f, 0f));
        writeWarp("forge", new Location(w, cx + 43.5, cy + 1, cz + 5.5, 0f, 0f));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "essentials reload");
        sender.sendMessage(Text.mm("<green>Village complete in " + (System.currentTimeMillis() - start) / 1000 + "s"
                + (wg ? " — protected (regions 'spawn' r=" + (VR + 8) + ", 'arena')" : "") + ". Warps: arena, bazaar, forge."));
    }

    private void writeWarp(String name, Location l) {
        var ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess == null) return;
        File dir = new File(ess.getDataFolder(), "warps");
        dir.mkdirs();
        var yml = new org.bukkit.configuration.file.YamlConfiguration();
        yml.set("world", l.getWorld().getName());
        yml.set("world-name", l.getWorld().getName());
        yml.set("x", l.getX());
        yml.set("y", l.getY());
        yml.set("z", l.getZ());
        yml.set("yaw", l.getYaw());
        yml.set("pitch", l.getPitch());
        yml.set("name", name);
        try {
            yml.save(new File(dir, name + ".yml"));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not write warp " + name + ": " + e.getMessage());
        }
    }
}
