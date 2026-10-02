package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Adaptive farming rewards active harvesting while limiting macro/automation bonuses. */
public final class FarmingFramework implements Listener {
    private final AetherCore plugin;
    private final CustomEnchantments enchants;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public FarmingFramework(AetherCore plugin) { this.plugin = plugin; this.enchants = new CustomEnchantments(plugin); }

    @EventHandler
    public void quit(PlayerQuitEvent event) { states.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void harvest(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        Block block = event.getBlock();
        Material harvestedType = block.getType();
        boolean ageableHarvest = block.getBlockData() instanceof Ageable;
        if (!plugin.getConfig().getBoolean("farming.adaptive.enabled", true)) return;
        if (!isHarvestable(block) || plugin.placedBlocks().isPlaced(block)) return;

        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        long gap = state.lastHarvest == 0 ? Long.MAX_VALUE : now - state.lastHarvest;
        int fortune = farmingStat(player, "farming_fortune") + farmingStat(player, cropKey(block.getType()) + "_fortune");
        int speed = farmingStat(player, "farming_speed");
        var held = player.getInventory().getItemInMainHand();
        Map<String, Integer> customEnchants = enchants.levels(held);
        fortune += customEnchants.getOrDefault("cultivation", 0) * 2;
        fortune += customEnchants.getOrDefault("dedication", 0);
        String crop = cropKey(block.getType());
        if (crop.equals("sugar_cane")) fortune += customEnchants.getOrDefault("cane_affinity", 0) * 20;
        if (crop.equals("cactus")) fortune += customEnchants.getOrDefault("cactus_affinity", 0) * 20;
        speed += customEnchants.getOrDefault("turbo", 0) * 10;
        long minimumGap = Math.max(100L, plugin.getConfig().getLong("farming.adaptive.minimum-gap-ms", 250L) - speed * 2L);
        boolean manualPace = gap >= minimumGap && gap <= plugin.getConfig().getLong("farming.adaptive.combo-timeout-ms", 12_000L);
        boolean validTool = isFarmingTool(player.getInventory().getItemInMainHand().getType());
        boolean automatedBurst = gap < Math.max(80L, minimumGap - 70L);

        if (manualPace && validTool) state.combo = Math.min(100, state.combo + 1);
        else if (automatedBurst || !validTool) state.combo = Math.max(0, state.combo - 3);
        else state.combo = Math.max(0, state.combo - 1);
        state.lastHarvest = now;

        // The base SkillManager award remains intact. These adaptive rewards are
        // only granted for deliberate manual harvesting at a human pace.
        if (!manualPace || !validTool || automatedBurst) return;
        PlayerData data = plugin.data().get(player);
        if (data == null) return;
        int xp = Math.min(8, 2 + state.combo / 10);
        plugin.skills().addXp(player, "farming", xp);

        int farmingLevel = plugin.skills().level(data, "farming");
        double chance = Math.min(plugin.getConfig().getDouble("farming.adaptive.maximum-bonus-chance", 0.28),
                plugin.getConfig().getDouble("farming.adaptive.base-bonus-chance", 0.04)
                        + farmingLevel * plugin.getConfig().getDouble("farming.adaptive.level-bonus-chance", 0.002)
                        + state.combo * plugin.getConfig().getDouble("farming.adaptive.combo-bonus-chance", 0.0015)
                        + fortune * plugin.getConfig().getDouble("farming.adaptive.fortune-bonus-chance", 0.002));
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            Material cropDrop = cropDrop(block.getType());
            if (cropDrop != null) {
                int amount = Math.min(3, 1 + fortune / 100);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(cropDrop, amount));
                overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                player.sendActionBar(Text.mm("<green>Manual Harvest Bonus <dark_gray>· <yellow>+1 " + pretty(cropDrop)
                        + " <dark_gray>· Fortune " + fortune + " <dark_gray>· Combo " + state.combo));
            }
        } else if (state.combo > 0 && state.combo % 10 == 0) {
            player.sendActionBar(Text.mm("<gold>Harvest Combo " + state.combo + " <dark_gray>· <green>Farming XP rate increased"));
        }
        String replantItem = replantItem(harvestedType);
        if (customEnchants.getOrDefault("replenish", 0) > 0 && ageableHarvest && replantItem != null
                && plugin.items().count(player, replantItem) > 0) {
            plugin.items().remove(player, replantItem, 1);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (block.getType().isAir() && harvestedType.createBlockData() instanceof Ageable current) {
                    current.setAge(0);
                    block.setBlockData(current, false);
                }
            });
        }
    }

    private static String replantItem(Material crop) {
        return switch (crop) {
            case WHEAT -> "WHEAT_SEEDS";
            case CARROTS -> "CARROT";
            case POTATOES -> "POTATO";
            case BEETROOTS -> "BEETROOT_SEEDS";
            case NETHER_WART -> "NETHER_WART";
            case COCOA -> "COCOA_BEANS";
            default -> null;
        };
    }

    private boolean isHarvestable(Block block) {
        if (block.getBlockData() instanceof Ageable crop) return crop.getAge() == crop.getMaximumAge()
                && !block.getType().name().endsWith("_STEM");
        Material type = block.getType();
        if (type != Material.SUGAR_CANE && type != Material.CACTUS) return false;
        return block.getRelative(org.bukkit.block.BlockFace.UP).getType() == type
                || block.getRelative(org.bukkit.block.BlockFace.DOWN).getType() == type;
    }

    private int farmingStat(Player player, String key) {
        int total = 0;
        for (ItemStack item : player.getInventory().getArmorContents()) total += attribute(item, key);
        total += attribute(player.getInventory().getItemInMainHand(), key);
        PlayerData data = plugin.data().get(player);
        if (data != null && key.equals("farming_fortune")) total += plugin.skills().level(data, "farming");
        return Math.max(0, total);
    }

    private int attribute(ItemStack stack, String key) {
        var item = plugin.items().customOf(stack);
        return item == null ? 0 : (int) Math.round(item.attributes().getOrDefault(key, 0D));
    }

    private static String cropKey(Material type) {
        return switch (type) {
            case WHEAT -> "wheat"; case CARROTS -> "carrot"; case POTATOES -> "potato";
            case BEETROOTS -> "beetroot"; case NETHER_WART -> "wart"; case COCOA -> "cocoa";
            case SUGAR_CANE -> "sugar_cane"; case CACTUS -> "cactus"; default -> "";
        };
    }

    private static boolean isFarmingTool(Material material) {
        return material.name().endsWith("_HOE") || material == Material.SHEARS || material == Material.AIR;
    }

    private static Material cropDrop(Material material) {
        return switch (material) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case NETHER_WART -> Material.NETHER_WART;
            case SUGAR_CANE -> Material.SUGAR_CANE;
            case CACTUS -> Material.CACTUS;
            case COCOA -> Material.COCOA_BEANS;
            default -> null;
        };
    }

    private static String pretty(Material material) {
        String[] words = material.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) { if (!out.isEmpty()) out.append(' '); out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); }
        return out.toString();
    }

    private static final class State {
        long lastHarvest;
        int combo;
    }
}
