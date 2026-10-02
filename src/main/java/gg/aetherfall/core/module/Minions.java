package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Hypixel-inspired minion storage: tiered production, fuel, collection, and material upgrades. */
public final class Minions implements TabExecutor, Listener {
    private static final int MAX_TIER = 11;
    private static final int MAX_STORAGE = 16;
    /** Hypixel-style tier curve: each tier has a distinct action speed rather than a linear boost. */
    private static final double[] SPEED = {1.00, .90, .80, .70, .60, .52, .45, .38, .32, .27, .23};
    private final AetherCore plugin;
    private final org.bukkit.NamespacedKey minionKey;
    private final org.bukkit.NamespacedKey idKey;
    private final org.bukkit.NamespacedKey ownerKey;
    private final org.bukkit.NamespacedKey tierKey;
    private final org.bukkit.NamespacedKey storageKey;
    private final org.bukkit.NamespacedKey fuelKey;
    private BukkitTask task;
    public Minions(AetherCore plugin) { this.plugin = plugin; this.minionKey = new org.bukkit.NamespacedKey(plugin, "minion_type"); this.idKey = new org.bukkit.NamespacedKey(plugin, "minion_id"); this.ownerKey = new org.bukkit.NamespacedKey(plugin, "minion_owner"); this.tierKey = new org.bukkit.NamespacedKey(plugin, "minion_tier"); this.storageKey = new org.bukkit.NamespacedKey(plugin, "minion_storage"); this.fuelKey = new org.bukkit.NamespacedKey(plugin, "minion_fuel"); }
    public void start() { if (task != null) task.cancel(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L); }
    public void stop() { if (task != null) task.cancel(); task = null; }
    private int capacity(PlayerData.MinionState m) { int tier = Math.max(1, Math.min(MAX_TIER, m.level)); int storage = Math.max(0, Math.min(MAX_STORAGE, m.storageLevel)); long cap = 64L + (tier - 1L) * 16L + storage * 32L; return (int) Math.min(2304L, cap); }
    private long interval(PlayerData.MinionState m) {
        long seconds = Math.max(1L, Math.min(86_400L, plugin.getConfig().getLong("minions." + m.type + ".seconds", 60L)));
        long base = seconds * 1000L;
        int tier = Math.max(1, Math.min(MAX_TIER, m.level));
        long result = Math.round(base * SPEED[tier - 1]);
        return m.fuelUntil > System.currentTimeMillis() ? result / 2 : result;
    }
    private void tick() { long now = System.currentTimeMillis(); for (Player p : Bukkit.getOnlinePlayers()) { PlayerData d = plugin.data().get(p); if (d == null) continue; boolean changed = false; for (PlayerData.MinionState m : d.minions.values()) { changed |= sanitize(m, now); int cap = capacity(m); if (m.stored < 0) { m.stored = 0; changed = true; } else if (m.stored > cap) { m.stored = cap; changed = true; } if (!m.placed()) continue; long wait = Math.max(1000L, interval(m)); long elapsed = Math.max(0L, now - m.collectedAt); int produced = (int) Math.min(Math.max(0, cap - m.stored), elapsed / wait); if (produced > 0) { m.stored += produced; long advance = (long) produced * wait; m.collectedAt = now - Math.max(0L, elapsed - advance); changed = true; } } if (changed) plugin.data().saveAsync(d); } }
    private int placedCount(PlayerData d) { int count = 0; for (PlayerData.MinionState m : d.minions.values()) if (m.placed()) count++; return count; }
    private boolean sanitize(PlayerData.MinionState m, long now) { boolean changed = false; int level = Math.max(1, Math.min(MAX_TIER, m.level)); int storage = Math.max(0, Math.min(MAX_STORAGE, m.storageLevel)); long fuel = Math.max(0L, m.fuelUntil); long collected = m.collectedAt <= 0L ? now : Math.min(m.collectedAt, now); if (m.level != level) { m.level = level; changed = true; } if (m.storageLevel != storage) { m.storageLevel = storage; changed = true; } if (m.fuelUntil != fuel) { m.fuelUntil = fuel; changed = true; } if (m.collectedAt != collected) { m.collectedAt = collected; changed = true; } return changed; }
    private Material icon(String type) { return switch (type) { case "miner" -> Material.IRON_PICKAXE; case "farmer" -> Material.IRON_HOE; case "hunter" -> Material.IRON_SWORD; case "wood" -> Material.IRON_AXE; default -> Material.CHEST; }; }
    private String output(String type) { return switch (type) { case "miner" -> "ore and custom cores"; case "farmer" -> "golden_seed"; case "hunter" -> "zombie_heart"; case "wood" -> "OAK_LOG"; default -> "aether_shard"; }; }
    private void collectMinerLoot(Player p, int amount, int level) { ThreadLocalRandom random = ThreadLocalRandom.current(); for (int i = 0; i < amount; i++) { int roll = random.nextInt(100); String item = roll < Math.min(8 + level, 20) ? (random.nextBoolean() ? "ember_core" : "volatile_core") : roll < 35 ? (random.nextBoolean() ? "mithril" : "titanium") : roll < 65 ? "IRON_INGOT" : roll < 82 ? "GOLD_INGOT" : roll < 95 ? "COAL" : "DIAMOND"; plugin.items().give(p, item, 1); } }
    private String upgradeItem(String type) { return switch (type) { case "miner" -> "COBBLESTONE"; case "farmer" -> "CARROT"; case "hunter" -> "BONE"; case "wood" -> "OAK_LOG"; default -> "COBBLESTONE"; }; }
    private int upgradeCost(String type, int level) {
        int base = switch (type) { case "miner" -> 80; case "farmer" -> 64; case "hunter" -> 48; case "wood" -> 64; default -> 64; };
        return Math.min(2304, base * (1 << Math.min(5, Math.max(0, level - 1))));
    }
    private void collectReady(PlayerData d) { long now = System.currentTimeMillis(); for (PlayerData.MinionState m : d.minions.values()) { sanitize(m, now); int cap = capacity(m); m.stored = Math.max(0, Math.min(cap, m.stored)); if (!m.placed()) continue; long wait = Math.max(1000L, interval(m)); long elapsed = Math.max(0L, now - m.collectedAt); int produced = (int) Math.min(Math.max(0, cap - m.stored), elapsed / wait); m.stored += produced; long advance = (long) produced * wait; m.collectedAt = now - Math.max(0L, elapsed - advance); } }

    private PlayerData.MinionState find(PlayerData d, String selector) {
        if (selector == null) return null;
        PlayerData.MinionState exact = d.minions.get(selector);
        if (exact != null) return exact;
        for (PlayerData.MinionState m : d.minions.values()) if (m.type.equalsIgnoreCase(selector)) return m;
        return null;
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p); if (d == null) return; collectReady(d);
        Menu menu = new Menu(6, Text.mm("<dark_gray>Minions · Collections"));
        Material border = Material.GRAY_STAINED_GLASS_PANE;
        for (int slot = 0; slot < 54; slot++) if (slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8) menu.set(slot, new ItemBuilder(border).name(" ").build());
        menu.set(4, new ItemBuilder(Material.NETHER_STAR).name("<aqua><bold>Minion Storage").lore(List.of("<gray>Collect resources, fuel minions,", "<gray>and upgrade them with materials.", "", "<yellow>Shift-click a minion to upgrade")).build());
        int[] slots = {20, 22, 24, 31, 33, 35}; int index = 0;
        for (PlayerData.MinionState m : d.minions.values()) { if (index >= slots.length) break; String fuel = m.fuelUntil > System.currentTimeMillis() ? Text.duration((m.fuelUntil - System.currentTimeMillis()) / 1000) : "Empty"; String recipe = m.level >= MAX_TIER ? "<gold>MAX TIER" : "<gray>- " + upgradeCost(m.type, m.level) + "x " + upgradeItem(m.type); List<String> lore = List.of("<gray>Tier: <aqua>" + m.level + " <dark_gray>(" + m.level + "/" + MAX_TIER + ")", "<gray>Storage: <white>" + m.stored + "/" + capacity(m), "<gray>Action time: <white>" + Text.duration(Math.max(1, interval(m) / 1000)), "", "<gray>Output: <white>" + output(m.type), "<gray>Fuel: <yellow>" + fuel, "", "<gold>Next tier recipe", recipe, "", "<green>Click to collect", m.level >= MAX_TIER ? "<dark_gray>Max tier reached" : "<yellow>Shift-click to upgrade"); menu.set(slots[index++], new ItemBuilder(icon(m.type)).name("<aqua><bold>" + m.type + " Minion <gray>Tier " + m.level).lore(lore).glow(m.stored > 0).build(), (pl, click) -> { if (click.isShiftClick()) upgrade(pl, m.id); else collect(pl, m.id); open(pl); }); }
        menu.set(40, new ItemBuilder(Material.COAL).name("<dark_gray>Fuel Station").lore(List.of("<gray>Fuel any minion with 16 coal.", "<gray>Fuel lasts 6 hours and doubles speed.", "", "<yellow>/minions fuel <type>", "<gray>If a marker is missing: <yellow>/minions pickup <type>")).build());
        menu.set(42, new ItemBuilder(Material.HOPPER).name("<gold>Collect All").lore(List.of("<gray>Collect stored resources from", "<gray>every minion at once.")).build(), (pl, click) -> { PlayerData pd = plugin.data().get(pl); if (pd != null) for (String id : new java.util.ArrayList<>(pd.minions.keySet())) collect(pl, id); open(pl); });
        if (d.minions.isEmpty()) menu.set(22, new ItemBuilder(Material.CHEST).name("<gold>No placed minions yet").lore(List.of("<gray>Craft a minion item, then place it", "<gray>to start production.", "", "<yellow>/minions craft miner")).build());
        menu.open(p);
    }
    public void collect(Player p, String selector) { PlayerData d = plugin.data().get(p); if (d == null) return; collectReady(d); PlayerData.MinionState m = find(d, selector); if (m == null || m.stored <= 0) { p.sendMessage(Text.mm("<gray>That minion has nothing ready.")); return; } int amount = m.stored; if (m.type.equalsIgnoreCase("miner")) collectMinerLoot(p, amount, m.level); else plugin.items().give(p, output(m.type), amount); p.sendMessage(Text.mm("<green>Collected " + amount + " resources from your " + m.type + " minion.")); m.stored = 0; m.collectedAt = System.currentTimeMillis(); plugin.data().saveNow(d); }
    public void upgrade(Player p, String selector) { PlayerData d = plugin.data().get(p); if (d == null) return; PlayerData.MinionState m = find(d, selector); if (m == null) { p.sendMessage(Text.mm("<red>You do not own that minion.")); return; } if (m.level >= MAX_TIER) { p.sendMessage(Text.mm("<gold>This minion is already tier " + MAX_TIER + ".")); return; } String material = upgradeItem(m.type); int cost = upgradeCost(m.type, m.level); if (plugin.items().count(p, material) < cost) { p.sendMessage(Text.mm("<red>Tier " + (m.level + 1) + " requires " + cost + "x " + plugin.items().displayName(material) + ".")); return; } collectReady(d); plugin.items().remove(p, material, cost); m.level++; plugin.data().saveNow(d); p.sendMessage(Text.mm("<green>Upgraded your " + m.type + " minion to tier " + m.level + "! Action time is now " + Text.duration(Math.max(1, interval(m) / 1000)) + ".")); }
    public void fuel(Player p, String selector) { PlayerData d = plugin.data().get(p); if (d == null) return; PlayerData.MinionState m = find(d, selector); if (m == null) { p.sendMessage(Text.mm("<red>You do not own that minion.")); return; } sanitize(m, System.currentTimeMillis()); String fuel = plugin.items().count(p, "BLAZE_ROD") >= 4 ? "BLAZE_ROD" : "COAL"; int amount = fuel.equals("BLAZE_ROD") ? 4 : 16; long duration = fuel.equals("BLAZE_ROD") ? 12 : 6; if (plugin.items().count(p, fuel) < amount) { p.sendMessage(Text.mm("<red>Fueling requires " + amount + "x " + fuel + ".")); return; } plugin.items().remove(p, fuel, amount); long added = duration * 60L * 60L * 1000L; long base = Math.max(System.currentTimeMillis(), m.fuelUntil); m.fuelUntil = base > Long.MAX_VALUE - added ? Long.MAX_VALUE : base + added; plugin.data().saveNow(d); p.sendMessage(Text.mm("<green>Fueled your " + m.type + " minion for " + duration + " hours of boosted production.")); }
    public void storage(Player p, String selector) { PlayerData d = plugin.data().get(p); if (d == null) return; PlayerData.MinionState m = find(d, selector); if (m == null) { p.sendMessage(Text.mm("<red>You do not own that minion.")); return; } sanitize(m, System.currentTimeMillis()); if (m.storageLevel >= MAX_STORAGE) { p.sendMessage(Text.mm("<gold>This minion has the maximum storage modules.")); return; } int cost = Math.min(2304, 2 + m.storageLevel * 2); if (plugin.items().count(p, "CHEST") < cost) { p.sendMessage(Text.mm("<red>Storage module requires " + cost + " chests.")); return; } plugin.items().remove(p, "CHEST", cost); m.storageLevel++; plugin.data().saveNow(d); p.sendMessage(Text.mm("<green>Installed storage module " + m.storageLevel + ". Capacity is now " + capacity(m) + " items.")); }
    public void pickup(Player p, String selector) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        PlayerData.MinionState state = find(d, selector);
        if (state == null) { p.sendMessage(Text.mm("<red>You do not own that minion.")); return; }
        collectReady(d);
        if (state.placed() && state.world != null) {
            var world = Bukkit.getWorld(state.world);
            if (world != null) {
                var at = world.getBlockAt(state.x, state.y, state.z).getLocation().add(.5, 0, .5);
                for (Entity entity : world.getNearbyEntities(at, .8, 1.5, .8)) {
                    if (entity instanceof ArmorStand stand && state.id.equals(stand.getPersistentDataContainer().get(idKey, PersistentDataType.STRING))
                            && p.getUniqueId().toString().equals(stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) stand.remove();
                }
            }
        }
        ItemStack item = minionItem(state.id, state.type, state.level, state.storageLevel, state.fuelUntil);
        p.getInventory().addItem(item).values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        d.minions.remove(state.id); plugin.data().saveNow(d);
        p.sendMessage(Text.mm("<green>Picked up your " + state.type + " minion and preserved its upgrades."));
    }
    private ItemStack minionItem(String type) { return minionItem(java.util.UUID.randomUUID().toString(), type, 1, 0, 0L); }
    private ItemStack minionItem(String id, String type, int level, int storage, long fuelUntil) {
        ItemStack item = new ItemBuilder(icon(type)).name("<aqua><bold>" + type + " Minion <gray>Tier " + Math.max(1, level)).lore(List.of("<gray>Place this minion to begin production.", "<gray>Collect resources and upgrade its tier.", storage > 0 ? "<gray>Storage modules: <white>" + storage : "", "", "<yellow>▶ Right-click to place")).build();
        var meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(minionKey, PersistentDataType.STRING, type);
        pdc.set(idKey, PersistentDataType.STRING, id);
        pdc.set(tierKey, PersistentDataType.INTEGER, Math.max(1, Math.min(MAX_TIER, level)));
        pdc.set(storageKey, PersistentDataType.INTEGER, Math.max(0, storage));
        if (fuelUntil > 0) pdc.set(fuelKey, PersistentDataType.LONG, fuelUntil);
        item.setItemMeta(meta);
        return item;
    }
    private void mark(ItemStack item, String type) { var meta = item.getItemMeta(); meta.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, type); item.setItemMeta(meta); }
    private void craft(Player p, String type) { if (!List.of("miner", "farmer", "hunter", "wood").contains(type)) { p.sendMessage(Text.mm("<red>Types: miner, farmer, hunter, wood")); return; } PlayerData d = plugin.data().get(p); if (d == null) return; String material = upgradeItem(type); int cost = 8; if (plugin.items().count(p, material) < cost) { p.sendMessage(Text.mm("<red>Crafting requires " + cost + "x " + plugin.items().displayName(material) + ".")); return; } plugin.items().remove(p, material, cost); ItemStack item = minionItem(type); mark(item, type); p.getInventory().addItem(item).values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i)); p.sendMessage(Text.mm("<green>Crafted a " + type + " minion. Place it to activate production.")); }
    @EventHandler(ignoreCancelled = true)
    public void place(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getItem() == null || e.getClickedBlock() == null) return;
        ItemStack item = e.getItem(); var meta = item.getItemMeta(); if (meta == null) return;
        String type = meta.getPersistentDataContainer().get(minionKey, PersistentDataType.STRING); if (type == null) return;
        String id = meta.getPersistentDataContainer().getOrDefault(idKey, PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
        Player p = e.getPlayer(); PlayerData d = plugin.data().get(p); if (d == null) return;
        e.setCancelled(true);
        var base = e.getClickedBlock().getRelative(e.getBlockFace());
        PlayerData.MinionState owned = d.minions.get(id);
        if (owned != null && owned.placed()) {
            p.sendMessage(Text.mm("<red>That minion is already placed. Pick it up before placing it again."));
            return;
        }
        int maxPlaced = Math.max(1, Math.min(200, plugin.getConfig().getInt("minions.max-per-player", 30)));
        if (owned == null && placedCount(d) >= maxPlaced) {
            p.sendMessage(Text.mm("<red>You may have at most " + maxPlaced + " placed minions."));
            return;
        }
        if (!MinionProtectionHook.canPlace(p, base.getLocation())) {
            p.sendMessage(Text.mm("<red>You cannot place a minion inside this claim."));
            return;
        }
        for (PlayerData.MinionState placed : d.minions.values()) if (placed.placed() && placed.world.equals(base.getWorld().getName()) && placed.x == base.getX() && placed.y == base.getY() && placed.z == base.getZ()) { p.sendMessage(Text.mm("<red>That location already has one of your minions.")); return; }
        if (!base.getType().isAir() || !base.getLocation().add(0, 1, 0).getBlock().getType().isAir()) { p.sendMessage(Text.mm("<red>That space is not clear enough for a minion.")); return; }
        var loc = base.getLocation().add(.5, 0, .5);
        ArmorStand stand = (ArmorStand) base.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setInvisible(true); stand.setMarker(true); stand.setGravity(false); stand.setInvulnerable(true);
        stand.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, type);
        stand.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        stand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
        PlayerData.MinionState existing = d.minions.get(id);
        if (existing == null) {
            int tier = meta.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 1);
            int storage = meta.getPersistentDataContainer().getOrDefault(storageKey, PersistentDataType.INTEGER, 0);
            long fuel = meta.getPersistentDataContainer().getOrDefault(fuelKey, PersistentDataType.LONG, 0L);
            existing = new PlayerData.MinionState(id, type, System.currentTimeMillis(), 0, tier, fuel, storage, null, 0, 0, 0);
        }
        existing.place(base.getWorld().getName(), base.getX(), base.getY(), base.getZ());
        d.minions.put(existing.id, existing);
        item.setAmount(item.getAmount() - 1);
        plugin.data().recordFunnel(p, "first_minion"); plugin.data().saveNow(d);
        p.sendMessage(Text.mm("<green>Placed your " + type + " minion. Production has started."));
    }

    @EventHandler public void interact(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ArmorStand stand)) return;
        String type = stand.getPersistentDataContainer().get(minionKey, PersistentDataType.STRING); String id = stand.getPersistentDataContainer().get(idKey, PersistentDataType.STRING); if (type == null || id == null) return;
        e.setCancelled(true); if (!(e.getPlayer() instanceof Player p)) return;
        String owner = stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (!p.getUniqueId().toString().equals(owner)) { p.sendMessage(Text.mm("<red>This minion belongs to another player.")); return; }
        collect(p, id);
    }

    @EventHandler public void breakMinion(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ArmorStand stand) || !(e.getDamager() instanceof Player p)) return;
        String type = stand.getPersistentDataContainer().get(minionKey, PersistentDataType.STRING); String id = stand.getPersistentDataContainer().get(idKey, PersistentDataType.STRING); if (type == null || id == null) return;
        e.setCancelled(true); String owner = stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (!p.getUniqueId().toString().equals(owner)) { p.sendMessage(Text.mm("<red>This minion belongs to another player.")); return; }
        if (!MinionProtectionHook.canPlace(p, stand.getLocation())) { p.sendMessage(Text.mm("<red>You cannot pick up a minion inside this claim.")); return; }
        PlayerData d = plugin.data().get(p); PlayerData.MinionState state = d == null ? null : d.minions.get(id); if (state == null) return;
        collectReady(d); ItemStack item = minionItem(state.id, state.type, state.level, state.storageLevel, state.fuelUntil);
        java.util.Map<Integer, ItemStack> overflow = p.getInventory().addItem(item); overflow.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        d.minions.remove(state.id); stand.remove(); plugin.data().saveNow(d); p.sendMessage(Text.mm("<green>Picked up your " + state.type + " minion."));
    }

    @EventHandler public void restore(PlayerJoinEvent e) {
        Player p = e.getPlayer(); PlayerData d = plugin.data().get(p); if (d == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> { for (PlayerData.MinionState m : d.minions.values()) if (m.placed()) restoreMarker(p, m); });
    }

    /** Recreate the visual projection after Paper unloads and reloads a chunk. */
    @EventHandler public void chunkLoad(ChunkLoadEvent e) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().get(p); if (d == null) continue;
            for (PlayerData.MinionState m : d.minions.values()) {
                if (m.placed() && e.getWorld().getName().equals(m.world)
                        && (m.x >> 4) == e.getChunk().getX() && (m.z >> 4) == e.getChunk().getZ()) restoreMarker(p, m);
            }
        }
    }

    /** Remove only AetherCore markers before a chunk unload; durable state remains intact. */
    @EventHandler public void chunkUnload(ChunkUnloadEvent e) {
        for (Entity entity : e.getChunk().getEntities()) {
            if (!(entity instanceof ArmorStand stand)) continue;
            if (stand.getPersistentDataContainer().has(minionKey, PersistentDataType.STRING)) stand.remove();
        }
    }

    private void restoreMarker(Player p, PlayerData.MinionState m) {
        var world = Bukkit.getWorld(m.world); if (world == null) return;
        var block = world.getBlockAt(m.x, m.y, m.z);
        if (!block.getType().isAir() || !block.getRelative(0, 1, 0).getType().isAir()) return;
        for (Entity entity : world.getNearbyEntities(block.getLocation().add(.5, 0, .5), .6, 1.2, .6))
            if (entity instanceof ArmorStand stand
                    && m.type.equals(stand.getPersistentDataContainer().get(minionKey, PersistentDataType.STRING))
                    && p.getUniqueId().toString().equals(stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) {
                String existingId = stand.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
                if (m.id.equals(existingId)) return;
                if (existingId == null) {
                    stand.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, m.id);
                    return;
                }
            }
        ArmorStand stand = (ArmorStand) world.spawnEntity(block.getLocation().add(.5, 0, .5), EntityType.ARMOR_STAND);
        stand.setInvisible(true); stand.setMarker(true); stand.setGravity(false); stand.setInvulnerable(true);
        stand.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, m.type);
        stand.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, m.id);
        stand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
    }
    @Override public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) { if (!(s instanceof Player p)) return true; PlayerData d = plugin.data().get(p); if (d == null) return true; if (a.length >= 2 && a[0].equalsIgnoreCase("craft")) { craft(p, a[1].toLowerCase(Locale.ROOT)); return true; } if (a.length >= 2 && a[0].equalsIgnoreCase("add")) { p.sendMessage(Text.mm("<yellow>Minions must be crafted and placed. Use <white>/minions craft " + a[1].toLowerCase(Locale.ROOT))); return true; } if (a.length >= 2 && a[0].equalsIgnoreCase("collect")) { collect(p, a[1].toLowerCase(Locale.ROOT)); return true; } if (a.length >= 2 && a[0].equalsIgnoreCase("upgrade")) { upgrade(p, a[1].toLowerCase(Locale.ROOT)); return true; } if (a.length >= 2 && a[0].equalsIgnoreCase("fuel")) { fuel(p, a[1].toLowerCase(Locale.ROOT)); return true; } if (a.length >= 2 && a[0].equalsIgnoreCase("storage")) { storage(p, a[1].toLowerCase(Locale.ROOT)); return true; } if (a.length >= 2 && a[0].equalsIgnoreCase("pickup")) { pickup(p, a[1].toLowerCase(Locale.ROOT)); return true; } open(p); return true; }
    @Override public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) { return a.length == 1 ? List.of("craft", "collect", "upgrade", "fuel", "storage", "pickup") : List.of("miner", "farmer", "hunter", "wood"); }
}
