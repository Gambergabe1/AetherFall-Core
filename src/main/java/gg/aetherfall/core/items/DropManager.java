package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Rare drops from mobs, fishing, natural blocks and crops (drops.yml). */
public final class DropManager implements Listener {
    public record Drop(String item, double chance, int min, int max) {}

    private final AetherCore plugin;
    private final Map<String, List<Drop>> mobs = new LinkedHashMap<>();
    private final List<Drop> fishing = new ArrayList<>();
    private final Map<Pattern, List<Drop>> blocks = new LinkedHashMap<>();
    private final Map<String, List<Drop>> crops = new LinkedHashMap<>();
    private double announceBelow;
    private YamlConfiguration yml;

    public DropManager(AetherCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "drops.yml");
        if (!file.exists()) plugin.saveResource("drops.yml", false);
        yml = YamlConfiguration.loadConfiguration(file);
        announceBelow = yml.getDouble("rare-drop-announce", 0.01);
        mobs.clear(); fishing.clear(); blocks.clear(); crops.clear();
        var m = yml.getConfigurationSection("mobs");
        if (m != null) for (String k : m.getKeys(false)) mobs.put(k.toUpperCase(Locale.ROOT), parse(m.getMapList(k)));
        fishing.addAll(parse(yml.getMapList("fishing")));
        var b = yml.getConfigurationSection("blocks");
        if (b != null) for (String k : b.getKeys(false)) {
            blocks.put(Pattern.compile(Pattern.quote(k.toUpperCase(Locale.ROOT)).replace("*", "\\E.*\\Q")), parse(b.getMapList(k)));
        }
        var c = yml.getConfigurationSection("crops");
        if (c != null) for (String k : c.getKeys(false)) crops.put(k.toUpperCase(Locale.ROOT), parse(c.getMapList(k)));
    }

    public YamlConfiguration config() {
        return yml;
    }

    private List<Drop> parse(List<Map<?, ?>> list) {
        List<Drop> out = new ArrayList<>();
        for (Map<?, ?> e : list) {
            String item = String.valueOf(e.get("item"));
            if (!plugin.items().isValidKey(item)) {
                plugin.getLogger().warning("drops.yml: unknown item '" + item + "'");
                continue;
            }
            double chance = e.get("chance") instanceof Number n ? n.doubleValue() : 0;
            int min = e.get("min") instanceof Number n ? n.intValue() : 1;
            int max = e.get("max") instanceof Number n ? n.intValue() : min;
            out.add(new Drop(item, chance, min, max));
        }
        return out;
    }

    /** Rolls a drop table and spawns the results. multiplier scales every chance. */
    public void roll(List<Drop> table, Player player, Location at, double multiplier) {
        if (table == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (plugin.abilities().hasTalisman(player, "luck")) multiplier *= 1.25;
        multiplier *= plugin.perks().of(player).luck(); // rank luck
        for (Drop d : table) {
            if (r.nextDouble() >= d.chance * multiplier) continue;
            int amount = d.min + r.nextInt(Math.max(1, d.max - d.min + 1));
            at.getWorld().dropItemNaturally(at, plugin.items().stack(d.item, amount));
            if (d.chance < announceBelow) announce(player, d.item);
        }
    }

    private void announce(Player player, String key) {
        Bukkit.broadcast(Text.mm("<gold><bold>RARE DROP!</bold></gold> <yellow>{player}</yellow> <gray>found</gray> ",
                Map.of("player", player.getName())).append(Text.mm(plugin.items().displayName(key))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
    }

    public List<Drop> mobTable(String type) {
        return mobs.get(type);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || entity instanceof Player) return;
        if (plugin.mobs() != null && plugin.mobs().isCustom(entity)) return; // custom mobs roll their own loot
        double mult = 1 + 0.2 * killer.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING);
        if (entity.fromMobSpawner()) mult *= 0.25; // grinders still work, but can't flood the market
        roll(mobs.get(entity.getType().name()), killer, entity.getLocation(), mult);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        Player player = event.getPlayer();
        ItemStack rod = player.getInventory().getItemInMainHand();
        double mult = 1 + 0.2 * rod.getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA);
        CustomItem custom = plugin.items().customOf(rod);
        if (custom != null && "sea_luck".equals(custom.ability())) mult *= 2;
        if (plugin.abilities().hasTalisman(player, "luck")) mult *= 1.25;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Drop d : fishing) {
            if (r.nextDouble() < d.chance * mult) {
                caught.setItemStack(plugin.items().stack(d.item, d.min + r.nextInt(Math.max(1, d.max - d.min + 1))));
                if (d.chance < announceBelow) announce(player, d.item);
                return; // one treasure per catch
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        Player player = event.getPlayer();
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        if (b.getBlockData() instanceof Ageable crop && crop.getAge() == crop.getMaximumAge()) {
            double mult = 1 + 0.2 * player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.FORTUNE);
            roll(crops.get(b.getType().name()), player, at, mult);
            return;
        }
        if (plugin.placedBlocks().isPlaced(b)) return;
        String name = b.getType().name();
        for (var e : blocks.entrySet()) {
            if (e.getKey().matcher(name).matches()) {
                roll(e.getValue(), player, at, 1);
                return;
            }
        }
    }
}
