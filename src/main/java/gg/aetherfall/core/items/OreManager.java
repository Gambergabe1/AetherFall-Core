package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates custom ore veins into overworld chunks (once per chunk) and handles mining them.
 * Ore positions live in the chunk's persistent data, so a player placing the same block
 * never creates "ore".
 */
public final class OreManager implements Listener {
    public record OreDef(String id, Material block, String drop, int min, int max, int minY, int maxY, int veins,
                         double chance, int sizeMin, int sizeMax, int tier, int xp, World.Environment env, Set<Material> replace) {}

    private static final String[] TIERS = {"WOOD", "STONE", "IRON", "DIAMOND", "NETHERITE"};
    private final AetherCore plugin;
    private final gg.aetherfall.core.module.CustomEnchantments customEnchants;
    private final NamespacedKey posKey;
    private final NamespacedKey genKey;
    private final Map<Material, OreDef> byBlock = new EnumMap<>(Material.class);
    private final List<OreDef> ores = new ArrayList<>();
    private final Set<Material> replaceable = EnumSet.noneOf(Material.class);
    private int version;

    public OreManager(AetherCore plugin) {
        this.plugin = plugin;
        this.customEnchants = new gg.aetherfall.core.module.CustomEnchantments(plugin);
        this.posKey = new NamespacedKey(plugin, "ores");
        this.genKey = new NamespacedKey(plugin, "ore_gen");
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "ores.yml");
        if (!file.exists()) plugin.saveResource("ores.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        version = yml.getInt("version", 1);
        ores.clear();
        byBlock.clear();
        replaceable.clear();
        for (String m : yml.getStringList("replaceable")) {
            Material mat = Material.matchMaterial(m);
            if (mat != null) replaceable.add(mat);
        }
        ConfigurationSection sec = yml.getConfigurationSection("ores");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            Material block = Material.matchMaterial(s.getString("block", ""));
            if (block == null || !block.isBlock() || plugin.items().get(s.getString("drop")) == null) {
                plugin.getLogger().warning("ores.yml: '" + id + "' has an invalid block or drop");
                continue;
            }
            List<Integer> y = s.getIntegerList("y");
            List<Integer> size = s.getIntegerList("size");
            int tier = List.of(TIERS).indexOf(s.getString("tool", "STONE").toUpperCase(Locale.ROOT));
            World.Environment env;
            try {
                env = World.Environment.valueOf(s.getString("dimension", "NORMAL").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("ores.yml: '" + id + "' has an invalid dimension (NORMAL, NETHER, THE_END)");
                continue;
            }
            Set<Material> replace = EnumSet.noneOf(Material.class);
            for (String m : s.getStringList("replace")) {
                Material mat = Material.matchMaterial(m);
                if (mat != null) replace.add(mat);
            }
            if (replace.isEmpty()) replace.addAll(replaceable);
            OreDef def = new OreDef(id, block, s.getString("drop"), s.getInt("min", 1), s.getInt("max", 1),
                    y.isEmpty() ? -64 : y.get(0), y.size() < 2 ? 64 : y.get(1), s.getInt("veins", 1), s.getDouble("chance", 1),
                    size.isEmpty() ? 1 : size.get(0), size.size() < 2 ? 3 : size.get(1), Math.max(0, tier), s.getInt("xp", 0), env, replace);
            ores.add(def);
            byBlock.put(block, def);
        }
    }

    /** Populates chunks that were already loaded before the plugin enabled (spawn area). */
    public void populateLoaded() {
        for (World w : Bukkit.getWorlds()) {
            if (!hasOres(w.getEnvironment())) continue;
            for (Chunk c : w.getLoadedChunks()) maybePopulate(c);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (hasOres(event.getWorld().getEnvironment())) maybePopulate(event.getChunk());
    }

    private boolean hasOres(World.Environment env) {
        for (OreDef o : ores) if (o.env == env) return true;
        return false;
    }

    private void maybePopulate(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        Integer done = pdc.get(genKey, PersistentDataType.INTEGER);
        if (done != null && done >= version) return;
        World w = chunk.getWorld();
        long seed = w.getSeed() ^ (chunk.getX() * 341873128712L) ^ (chunk.getZ() * 132897987541L) ^ version;
        Random r = new Random(seed);
        List<Integer> placed = new ArrayList<>();
        int bx = chunk.getX() << 4, bz = chunk.getZ() << 4;
        for (OreDef ore : ores) {
            if (ore.env != w.getEnvironment()) continue;
            int lo = Math.max(ore.minY, w.getMinHeight() + 1), hi = Math.min(ore.maxY, w.getMaxHeight() - 1);
            if (lo >= hi) continue;
            for (int v = 0; v < ore.veins; v++) {
                if (r.nextDouble() >= ore.chance) continue;
                int x = r.nextInt(16), y = lo + r.nextInt(hi - lo), z = r.nextInt(16);
                int size = ore.sizeMin + r.nextInt(Math.max(1, ore.sizeMax - ore.sizeMin + 1));
                for (int n = 0; n < size; n++) {
                    Block b = w.getBlockAt(bx + x, y, bz + z);
                    if (ore.replace.contains(b.getType())) {
                        b.setType(ore.block, false);
                        placed.add(pack(x, y, z));
                    }
                    switch (r.nextInt(6)) {
                        case 0 -> x = Math.min(15, x + 1);
                        case 1 -> x = Math.max(0, x - 1);
                        case 2 -> y = Math.min(hi, y + 1);
                        case 3 -> y = Math.max(lo, y - 1);
                        case 4 -> z = Math.min(15, z + 1);
                        default -> z = Math.max(0, z - 1);
                    }
                }
            }
        }
        int[] existing = pdc.getOrDefault(posKey, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int[] merged = new int[existing.length + placed.size()];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        for (int i = 0; i < placed.size(); i++) merged[existing.length + i] = placed.get(i);
        pdc.set(posKey, PersistentDataType.INTEGER_ARRAY, merged);
        pdc.set(genKey, PersistentDataType.INTEGER, version);
    }

    private static int pack(int x, int y, int z) {
        return (x & 15) | ((z & 15) << 4) | ((y + 2048) << 8);
    }

    public OreDef oreAt(Block b) {
        OreDef def = byBlock.get(b.getType());
        if (def == null) return null;
        int[] arr = b.getChunk().getPersistentDataContainer().get(posKey, PersistentDataType.INTEGER_ARRAY);
        if (arr == null) return null;
        int p = pack(b.getX() & 15, b.getY(), b.getZ() & 15);
        for (int v : arr) if (v == p) return def;
        return null;
    }

    private void forget(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] arr = pdc.get(posKey, PersistentDataType.INTEGER_ARRAY);
        if (arr == null) return;
        int p = pack(b.getX() & 15, b.getY(), b.getZ() & 15);
        int[] out = new int[arr.length];
        int n = 0;
        for (int v : arr) if (v != p) out[n++] = v;
        pdc.set(posKey, PersistentDataType.INTEGER_ARRAY, java.util.Arrays.copyOf(out, n));
    }

    /** Counts tracked ore blocks per type in loaded chunks within radius of a location (admin diagnostics). */
    public Map<String, Integer> scan(org.bukkit.Location at, int radius) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        World w = at.getWorld();
        int r = radius >> 4;
        for (int x = (at.getBlockX() >> 4) - r; x <= (at.getBlockX() >> 4) + r; x++) {
            for (int z = (at.getBlockZ() >> 4) - r; z <= (at.getBlockZ() >> 4) + r; z++) {
                if (!w.isChunkLoaded(x, z)) continue;
                Chunk c = w.getChunkAt(x, z);
                int[] arr = c.getPersistentDataContainer().get(posKey, PersistentDataType.INTEGER_ARRAY);
                if (arr == null) continue;
                for (int v : arr) {
                    Block b = c.getBlock(v & 15, (v >> 8) - 2048, (v >> 4) & 15);
                    OreDef d = byBlock.get(b.getType());
                    if (d != null) out.merge(d.id, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    public static int tierOf(ItemStack tool, CustomItem custom) {
        String n = tool.getType().name();
        if (!n.endsWith("_PICKAXE")) return -1;
        if (custom != null && n.startsWith("GOLDEN_")) return 3; // enchanted gold tools count as diamond
        if (n.startsWith("NETHERITE_")) return 4;
        if (n.startsWith("DIAMOND_")) return 3;
        if (n.startsWith("IRON_")) return 2;
        if (n.startsWith("STONE_") || n.startsWith("COPPER_")) return 1;
        return 0;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        OreDef ore = oreAt(block);
        if (ore == null) return;
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        CustomItem custom = plugin.items().customOf(tool);
        if (player.getGameMode() != GameMode.CREATIVE && tierOf(tool, custom) < ore.tier) {
            event.setCancelled(true);
            player.sendActionBar(Text.mm("<red>This ore needs a " + ItemRegistry.prettify(TIERS[ore.tier]) + " pickaxe or better."));
            return;
        }
        forget(block);
        event.setDropItems(false);
        event.setExpToDrop(ore.xp);
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int amount = ore.min + r.nextInt(Math.max(1, ore.max - ore.min + 1));
        int fortune = tool.getEnchantmentLevel(Enchantment.FORTUNE);
        if (fortune > 0) amount += r.nextInt(fortune + 1);
        if (custom != null && "prospector".equals(custom.ability()) && r.nextDouble() < 0.5) amount *= 2;
        ItemStack drop = plugin.items().stack(ore.drop, 1);
        drop.setAmount(Math.min(amount, drop.getMaxStackSize()));
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        int resonance = customEnchants.levels(tool).getOrDefault("core_resonance", 0);
        if (resonance > 0 && ThreadLocalRandom.current().nextDouble() < Math.min(0.20, resonance * 0.02)) {
            String core = ThreadLocalRandom.current().nextBoolean() ? "ember_core" : "volatile_core";
            plugin.items().give(player, core, 1);
            player.sendActionBar(Text.mm("<light_purple>Core Resonance found a <aqua>" + plugin.items().displayName(core)));
        }
        plugin.onboarding().complete(player, gg.aetherfall.core.module.Onboarding.Step.ORE);
    }

    // Keep ore blocks where they are: no pushing them around or blowing them up.
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) if (oreAt(b) != null) { event.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) if (oreAt(b) != null) { event.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> oreAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> oreAt(b) != null);
    }
}
