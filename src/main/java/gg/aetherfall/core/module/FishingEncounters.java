package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Bounded, attributable fishing encounters. */
public final class FishingEncounters implements Listener {
    private record Encounter(UUID owner, String mobId, long expiresAt) { }
    private record LootDrop(String item, int min, int max, double chance) { }
    private record LootTable(Map<String, Integer> guaranteed, List<LootDrop> drops) { }
    private final AetherCore plugin;
    private final CustomEnchantments enchants;
    private final NamespacedKey encounterKey;
    private final Map<UUID, Encounter> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private volatile Map<String, LootTable> lootTables = Map.of();
    private BukkitTask cleanupTask;

    public FishingEncounters(AetherCore p) {
        plugin = p;
        enchants = new CustomEnchantments(p);
        encounterKey = new NamespacedKey(p, "fishing_encounter");
        reload();
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanup, 200L, 200L);
    }

    /** Cancels the maintenance task and removes only encounters owned by this module. */
    public void stop() {
        if (cleanupTask != null) cleanupTask.cancel();
        for (UUID id : active.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) entity.remove();
        }
        active.clear();
        cooldowns.clear();
    }

    /** Revalidates operator-edited encounter tables without disturbing active encounters. */
    public void reload() {
        var encounters = plugin.getConfig().getConfigurationSection("fishing.encounters");
        if (encounters == null) {
            lootTables = Map.of();
            plugin.getLogger().warning("No fishing encounter tables configured; encounters will pay no custom loot.");
            return;
        }
        Map<String, LootTable> rebuilt = new LinkedHashMap<>();
        for (String mobId : encounters.getKeys(false)) {
            var table = encounters.getConfigurationSection(mobId);
            if (table == null) continue;
            Map<String, Integer> guaranteedItems = new LinkedHashMap<>();
            var guaranteed = table.getConfigurationSection("guaranteed");
            if (guaranteed != null) for (String item : guaranteed.getKeys(false)) {
                int amount = Math.min(64, Math.max(0, guaranteed.getInt(item, 0)));
                validateItem(mobId, item, amount);
                if (amount > 0 && plugin.items().isValidKey(item)) guaranteedItems.put(item, amount);
            }
            List<LootDrop> parsedDrops = new java.util.ArrayList<>();
            for (Map<?, ?> drop : table.getMapList("drops")) {
                Object raw = drop.get("item");
                String item = raw == null ? "" : String.valueOf(raw);
                int min = Math.min(64, Math.max(1, integer(drop.get("min"), 1)));
                int max = Math.min(64, Math.max(min, integer(drop.get("max"), min)));
                validateItem(mobId, item, max);
                double rawChance = number(drop.get("chance"), 1.0);
                if (!Double.isFinite(rawChance) || rawChance < 0 || rawChance > 1) plugin.getLogger().warning("Fishing encounter " + mobId + " has chance outside 0..1 for " + item + ". It will be clamped.");
                double chance = Double.isFinite(rawChance) ? Math.max(0.0, Math.min(1.0, rawChance)) : 0.0;
                if (plugin.items().isValidKey(item) && Double.isFinite(chance) && chance > 0) parsedDrops.add(new LootDrop(item, min, max, chance));
            }
            rebuilt.put(mobId, new LootTable(Map.copyOf(guaranteedItems), List.copyOf(parsedDrops)));
        }
        lootTables = Map.copyOf(rebuilt);
    }

    private void validateItem(String mobId, String item, int amount) {
        if (!plugin.items().isValidKey(item)) plugin.getLogger().warning("Fishing encounter " + mobId + " references unknown custom item: " + item);
        if (amount < 1) plugin.getLogger().warning("Fishing encounter " + mobId + " has a non-positive amount for " + item + ".");
    }

    @EventHandler(ignoreCancelled = true)
    public void fish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof org.bukkit.entity.Item)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        ItemStackView held = new ItemStackView(p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand());
        var levels = enchants.levels(held.rod());
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("fishing.encounter-cooldown-ms", 5000L);
        if (now - cooldowns.getOrDefault(p.getUniqueId(), 0L) >= cooldown) {
            int cap = Math.max(1, plugin.getConfig().getInt("fishing.encounter-cap", 3));
            long owned = active.values().stream().filter(v -> v.owner.equals(p.getUniqueId())).count();
            int chance = Math.min(25, Math.max(0, 8 + levels.getOrDefault("tidecaller", 0) * 3));
            if (owned < cap && ThreadLocalRandom.current().nextInt(100) < chance) {
                String mob = ThreadLocalRandom.current().nextInt(100) < 3 ? "deep_guardian" : ThreadLocalRandom.current().nextInt(100) < 8 ? "krakenling" : "reef_stalker";
                LivingEntity spawned = plugin.mobs().spawn(mob, p.getLocation());
                if (spawned != null) {
                    spawned.getPersistentDataContainer().set(encounterKey, PersistentDataType.BYTE, (byte) 1);
                    long lifetime = Math.max(10_000L, plugin.getConfig().getLong("fishing.encounter-lifetime-ms", 120_000L));
                    active.put(spawned.getUniqueId(), new Encounter(p.getUniqueId(), mob, now + lifetime));
                    cooldowns.put(p.getUniqueId(), now);
                    p.sendMessage(Text.mm("<dark_aqua><bold>Fishing encounter!</bold></dark_aqua> <gray>A " + mob.replace('_', ' ') + " has surfaced!"));
                }
            }
        }
        int deepwater = levels.getOrDefault("deepwater", 0);
        if (deepwater > 0 && ThreadLocalRandom.current().nextDouble() < Math.min(.12, deepwater * .02)) {
            plugin.items().give(p, "prismatic_scale", 1);
            p.sendMessage(Text.mm("<aqua>✦ Deepwater found a <light_purple>Prismatic Scale</light_purple>!"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        Encounter encounter = active.remove(e.getEntity().getUniqueId());
        if (encounter == null) return;
        Player owner = Bukkit.getPlayer(encounter.owner());
        // Bestiary skips marked encounters; ownership is the single authority
        // regardless of which listener runs first or who lands the final hit.
        if (owner != null) {
            plugin.bestiary().recordDefeat(owner, "elite:" + encounter.mobId(), e.getEntity().getUniqueId());
        }
        awardLoot(encounter.mobId(), owner, e.getEntity().getLocation());
    }

    private void awardLoot(String mobId, Player owner, org.bukkit.Location dropLocation) {
        LootTable table = lootTables.get(mobId);
        if (table == null) return;
        int remaining = Math.max(1, plugin.getConfig().getInt("fishing.max-encounter-item-count", 64));
        int awarded = 0;
        for (var entry : table.guaranteed().entrySet()) {
            String item = entry.getKey();
            int amount = entry.getValue();
            awarded += deliver(item, amount, owner, dropLocation, remaining - awarded);
        }
        for (LootDrop drop : table.drops()) {
            if (awarded >= remaining) break;
            if (ThreadLocalRandom.current().nextDouble() > drop.chance()) continue;
            int amount = ThreadLocalRandom.current().nextInt(drop.min(), drop.max() + 1);
            awarded += deliver(drop.item(), amount, owner, dropLocation, remaining - awarded);
        }
        if (awarded > 0 && owner != null) owner.sendMessage(Text.mm("<aqua>✦ Fishing encounter defeated! <gray>You received <white>" + awarded + "</white> custom loot."));
    }

    private int deliver(String item, int amount, Player owner, org.bukkit.Location location, int budget) {
        if (amount <= 0 || budget <= 0 || !plugin.items().isValidKey(item)) return 0;
        int count = Math.min(amount, budget);
        if (owner != null) plugin.items().give(owner, item, count);
        else {
            var stack = plugin.items().stack(item, count);
            if (stack != null) location.getWorld().dropItemNaturally(location, stack);
        }
        return count;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        active.entrySet().removeIf(entry -> {
            Encounter encounter = entry.getValue();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null || !entity.isValid() || encounter.expiresAt < now) {
                if (entity != null && entity.isValid()) entity.remove();
                return true;
            }
            return false;
        });
        cooldowns.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
    }

    private record ItemStackView(org.bukkit.inventory.ItemStack main, org.bukkit.inventory.ItemStack off) {
        org.bukkit.inventory.ItemStack rod() { return main != null && main.getType() == Material.FISHING_ROD ? main : off; }
    }
}
