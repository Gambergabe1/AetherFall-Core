package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Timber (fell whole trees) and Vein Miner (mine connected ores).
 * Extra blocks are broken with Player#breakBlock, so claims, regions, logging,
 * enchantments, drops and durability all behave exactly like normal mining.
 */
public final class Harvester implements Listener {
    private static final Set<Material> TREE_CROWN = Set.of(Material.NETHER_WART_BLOCK, Material.WARPED_WART_BLOCK, Material.SHROOMLIGHT);
    private final AetherCore plugin;
    private boolean working;

    public Harvester(AetherCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (working) return;
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        Block block = event.getBlock();
        String tool = player.getInventory().getItemInMainHand().getType().name();
        var cfg = plugin.getConfig();

        if (tool.endsWith("_AXE") && Tag.LOGS.isTagged(block.getType()) && d.timber && cfg.getBoolean("timber.enabled")
                && (!cfg.getBoolean("timber.require-sneak") || player.isSneaking())
                && player.hasPermission("aethercore.timber")) {
            fell(player, block);
        } else if (tool.endsWith("_PICKAXE") && isOre(block.getType()) && d.vein && cfg.getBoolean("veinminer.enabled")
                && (!cfg.getBoolean("veinminer.require-sneak") || player.isSneaking())
                && player.hasPermission("aethercore.veinminer")) {
            vein(player, block);
        }
    }

    private void fell(Player player, Block origin) {
        if (plugin.placedBlocks().isPlaced(origin)) return;
        int max = Math.max(plugin.getConfig().getInt("timber.max-logs", 160), plugin.perks().of(player).timberMax());
        List<Block> logs = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        int leaves = 0;
        Set<Block> leafSeen = new HashSet<>();
        while (!queue.isEmpty() && logs.size() < max) {
            Block b = queue.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                Block n = b.getRelative(dx, dy, dz);
                if (!seen.add(n)) continue;
                if (Tag.LOGS.isTagged(n.getType())) {
                    if (plugin.placedBlocks().isPlaced(n)) continue;
                    logs.add(n);
                    queue.add(n);
                } else if (isNaturalCrown(n) && leafSeen.add(n)) {
                    leaves++;
                }
            }
        }
        // Houses and builds made of logs have no natural leaves around them, so they're left alone.
        if (logs.isEmpty() || leaves < plugin.getConfig().getInt("timber.min-natural-leaves", 4)) return;
        logs.sort(Comparator.comparingInt(Block::getY));
        Material sapling = saplingFor(origin.getType());
        breakAll(player, logs);

        if (sapling != null && plugin.getConfig().getBoolean("timber.replant")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Let the game decide if a sapling can live here (grass, dirt, moss, mud, ...).
                var data = sapling.createBlockData();
                if (origin.getType().isAir() && data.isSupported(origin)) origin.setBlockData(data);
            }, 2L);
        }
    }

    private void vein(Player player, Block origin) {
        String group = oreGroup(origin.getType());
        int max = Math.max(plugin.getConfig().getInt("veinminer.max-blocks", 48), plugin.perks().of(player).veinMax());
        List<Block> ores = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty() && ores.size() < max) {
            Block b = queue.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block n = b.getRelative(dx, dy, dz);
                if (!seen.add(n)) continue;
                if (isOre(n.getType()) && oreGroup(n.getType()).equals(group)) {
                    ores.add(n);
                    queue.add(n);
                }
            }
        }
        ores.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(origin.getLocation())));
        breakAll(player, ores);
    }

    private void breakAll(Player player, List<Block> blocks) {
        working = true;
        try {
            for (Block b : blocks) {
                if (!hasDurabilityLeft(player.getInventory().getItemInMainHand())) break;
                player.breakBlock(b);
            }
        } finally {
            working = false;
        }
    }

    /** Stops just before the tool would break, so nobody loses a favourite pickaxe to vein mining. */
    private static boolean hasDurabilityLeft(ItemStack tool) {
        if (tool.getType().isAir()) return false;
        int max = tool.getType().getMaxDurability();
        if (max <= 0 || !(tool.getItemMeta() instanceof Damageable dmg)) return true;
        if (tool.getItemMeta().isUnbreakable()) return true;
        return max - dmg.getDamage() > 2;
    }

    private static boolean isNaturalCrown(Block b) {
        if (b.getBlockData() instanceof Leaves leaves) return !leaves.isPersistent();
        return TREE_CROWN.contains(b.getType());
    }

    public static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    private static String oreGroup(Material m) {
        return m.name().replace("DEEPSLATE_", "").replace("NETHER_", "");
    }

    private static Material saplingFor(Material log) {
        String n = log.name();
        if (n.startsWith("STRIPPED_")) return null;
        if (n.equals("MANGROVE_LOG")) return Material.MANGROVE_PROPAGULE;
        if (!n.endsWith("_LOG")) return null;
        Material m = Material.matchMaterial(n.replace("_LOG", "_SAPLING"));
        return m != null && m.isBlock() ? m : null;
    }
}
