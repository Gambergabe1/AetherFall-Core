package gg.aetherfall.core.mobs;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.economy.Eco;
import gg.aetherfall.core.items.DropManager.Drop;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Elite mobs: natural spawns upgraded with stats, gear, abilities, nameplates and loot. */
public final class MobManager implements Listener {
    public record Elite(String id, EntityType base, String name, String color, int level, double health, double damage,
                        double speed, double scale, double knockbackResistance, boolean charged, int size,
                        List<EntityType> replaceTypes, double chance, List<Pattern> biomes, int minY, int maxY,
                        Map<String, String> equipment, Map<String, int[]> onHit, List<String> abilities,
                        List<Drop> drops, double coins, int xp) {}

    private final AetherCore plugin;
    private final NamespacedKey mobKey;
    private final NamespacedKey fishingEncounterKey;
    private final Map<String, Elite> elites = new LinkedHashMap<>();
    private YamlConfiguration yml;
    private BukkitTask abilityTask;

    public MobManager(AetherCore plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "mob");
        this.fishingEncounterKey = new NamespacedKey(plugin, "fishing_encounter");
        reload();
        // Cave Brutes leap at their target every few seconds.
        abilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAbilities, 60L, 60L);
    }

    public void stop() {
        if (abilityTask != null) { abilityTask.cancel(); abilityTask = null; }
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "mobs.yml");
        if (!file.exists()) plugin.saveResource("mobs.yml", false);
        yml = YamlConfiguration.loadConfiguration(file);
        elites.clear();
        ConfigurationSection sec = yml.getConfigurationSection("elites");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            try {
                elites.put(id, parse(id, sec.getConfigurationSection(id)));
            } catch (Exception e) {
                plugin.getLogger().warning("mobs.yml: elite '" + id + "' is invalid: " + e.getMessage());
            }
        }
    }

    public YamlConfiguration config() {
        return yml;
    }

    private Elite parse(String id, ConfigurationSection s) {
        EntityType base = EntityType.valueOf(s.getString("base", "ZOMBIE").toUpperCase(Locale.ROOT));
        ConfigurationSection r = s.getConfigurationSection("replace");
        List<EntityType> types = new ArrayList<>();
        List<Pattern> biomes = new ArrayList<>();
        if (r != null) {
            for (String t : r.getStringList("types")) types.add(EntityType.valueOf(t.toUpperCase(Locale.ROOT)));
            for (String b : r.getStringList("biomes")) biomes.add(Pattern.compile(Pattern.quote(b.toUpperCase(Locale.ROOT)).replace("*", "\\E.*\\Q")));
        }
        Map<String, String> equipment = new LinkedHashMap<>();
        ConfigurationSection eq = s.getConfigurationSection("equipment");
        if (eq != null) for (String k : eq.getKeys(false)) equipment.put(k.toLowerCase(Locale.ROOT), eq.getString(k));
        Map<String, int[]> onHit = new LinkedHashMap<>();
        ConfigurationSection oh = s.getConfigurationSection("on-hit");
        if (oh != null) for (String k : oh.getKeys(false)) {
            List<Integer> v = oh.getIntegerList(k);
            onHit.put(k.toLowerCase(Locale.ROOT), new int[]{v.isEmpty() ? 60 : v.get(0), v.size() < 2 ? 0 : v.get(1)});
        }
        return new Elite(id, base, s.getString("name", id), s.getString("color", "<white>"), s.getInt("level", 1),
                s.getDouble("health", 20), s.getDouble("damage", 0), s.getDouble("speed", 0), s.getDouble("scale", 0),
                s.getDouble("knockback-resistance", 0), s.getBoolean("charged"), s.getInt("size", 0),
                types, r == null ? 0 : r.getDouble("chance", 0), biomes,
                r == null ? Integer.MIN_VALUE : r.getInt("min-y", Integer.MIN_VALUE), r == null ? Integer.MAX_VALUE : r.getInt("max-y", Integer.MAX_VALUE),
                equipment, onHit, s.getStringList("abilities"), parseDrops(s.getMapList("drops")),
                s.getDouble("coins", 0), s.getInt("xp", 0));
    }

    public List<Drop> parseDrops(List<Map<?, ?>> list) {
        List<Drop> out = new ArrayList<>();
        for (Map<?, ?> e : list) {
            String item = String.valueOf(e.get("item"));
            if (!plugin.items().isValidKey(item)) {
                plugin.getLogger().warning("mobs.yml: unknown drop item '" + item + "'");
                continue;
            }
            double chance = e.get("chance") instanceof Number n ? n.doubleValue() : 1;
            int min = e.get("min") instanceof Number n ? n.intValue() : 1;
            int max = e.get("max") instanceof Number n ? n.intValue() : min;
            out.add(new Drop(item, chance, min, max));
        }
        return out;
    }

    public Elite elite(String id) {
        return elites.get(id);
    }

    public java.util.Set<String> eliteIds() {
        return java.util.Collections.unmodifiableSet(elites.keySet());
    }

    public boolean isCustom(Entity e) {
        return e.getPersistentDataContainer().has(mobKey) || plugin.bosses().isBoss(e);
    }

    public String idOf(Entity e) {
        return e.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
    }

    // ── spawning ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        LivingEntity e = event.getEntity();
        Location loc = e.getLocation();
        String biome = loc.getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Elite el : elites.values()) {
            if (!el.replaceTypes.contains(e.getType()) || el.chance <= 0) continue;
            if (loc.getBlockY() < el.minY || loc.getBlockY() > el.maxY) continue;
            if (!el.biomes.isEmpty() && el.biomes.stream().noneMatch(p -> p.matcher(biome).matches())) continue;
            if (r.nextDouble() >= el.chance) continue;
            if (el.base != e.getType()) {
                event.setCancelled(true);
                loc.getWorld().spawn(loc, el.base.getEntityClass().asSubclass(LivingEntity.class), CreatureSpawnEvent.SpawnReason.CUSTOM, n -> apply(n, el));
            } else {
                apply(e, el);
            }
            return;
        }
    }

    /** Spawns an elite at a location (used by bosses for minions and by admins). */
    public LivingEntity spawn(String id, Location at) {
        Elite el = elites.get(id);
        if (el == null) return null;
        return at.getWorld().spawn(at, el.base.getEntityClass().asSubclass(LivingEntity.class), CreatureSpawnEvent.SpawnReason.CUSTOM, n -> apply(n, el));
    }

    public void apply(LivingEntity e, Elite el) {
        e.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, el.id);
        setBase(e, Attribute.MAX_HEALTH, el.health);
        e.setHealth(el.health);
        if (el.damage > 0) setBase(e, Attribute.ATTACK_DAMAGE, el.damage);
        if (el.speed > 0) setBase(e, Attribute.MOVEMENT_SPEED, el.speed);
        if (el.scale > 0) setBase(e, Attribute.SCALE, el.scale);
        if (el.knockbackResistance > 0) setBase(e, Attribute.KNOCKBACK_RESISTANCE, el.knockbackResistance);
        if (e instanceof Creeper c && el.charged) c.setPowered(true);
        if (e instanceof Slime s && el.size > 0) s.setSize(el.size);
        EntityEquipment eq = e.getEquipment();
        if (eq != null) {
            for (var en : el.equipment.entrySet()) {
                ItemStack item = plugin.items().stack(en.getValue(), 1);
                switch (en.getKey()) {
                    case "helmet" -> { eq.setHelmet(item); eq.setHelmetDropChance(0); }
                    case "chestplate" -> { eq.setChestplate(item); eq.setChestplateDropChance(0); }
                    case "leggings" -> { eq.setLeggings(item); eq.setLeggingsDropChance(0); }
                    case "boots" -> { eq.setBoots(item); eq.setBootsDropChance(0); }
                    case "mainhand" -> { eq.setItemInMainHand(item); eq.setItemInMainHandDropChance(0); }
                    default -> { }
                }
            }
        }
        if (e instanceof Mob) e.setCanPickupItems(false);
        updateName(e);
    }

    static void setBase(LivingEntity e, Attribute a, double v) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    void updateName(LivingEntity e) {
        Elite el = elites.get(idOf(e));
        if (el == null) return;
        double max = e.getAttribute(Attribute.MAX_HEALTH).getValue();
        e.customName(Text.mm("<dark_gray>[<gray>Lv" + el.level + "<dark_gray>] " + el.color + el.name + " <red>"
                + (int) Math.ceil(e.getHealth()) + "<dark_gray>/<red>" + (int) max + "❤"));
        e.setCustomNameVisible(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity le && event.getEntity().getPersistentDataContainer().has(mobKey)) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (le.isValid()) updateName(le); });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof LivingEntity le && event.getEntity().getPersistentDataContainer().has(mobKey)) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (le.isValid()) updateName(le); });
        }
    }

    // ── abilities ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) attacker = shooter;
        Elite el = elites.get(idOf(attacker));
        if (el == null) return;
        applyOnHit(victim, el.onHit);
    }

    public static void applyOnHit(Player victim, Map<String, int[]> effects) {
        for (var e : effects.entrySet()) {
            if (e.getKey().equals("fire")) {
                victim.setFireTicks(Math.max(victim.getFireTicks(), e.getValue()[0]));
                continue;
            }
            PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(e.getKey()));
            if (t != null) victim.addPotionEffect(new PotionEffect(t, e.getValue()[0], e.getValue()[1]));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        Elite el = elites.get(idOf(event.getEntity()));
        if (el != null && el.abilities.contains("fire_arrows") && event.getProjectile() instanceof AbstractArrow arrow) {
            arrow.setFireTicks(200);
            arrow.setDamage(arrow.getDamage() * 1.5);
        }
    }

    private void tickAbilities() {
        for (var world : Bukkit.getWorlds()) {
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                Elite el = elites.get(idOf(mob));
                if (el == null || !el.abilities.contains("leap")) continue;
                LivingEntity target = mob.getTarget();
                if (target == null || !mob.isOnGround() || ThreadLocalRandom.current().nextInt(3) != 0) continue;
                double dist = target.getLocation().distance(mob.getLocation());
                if (dist < 4 || dist > 14) continue;
                Vector v = target.getLocation().toVector().subtract(mob.getLocation().toVector()).normalize().multiply(1.1);
                v.setY(0.55);
                mob.setVelocity(v);
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.4f);
            }
        }
    }

    // ── loot ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity e = event.getEntity();
        Elite el = elites.get(idOf(e));
        if (el == null) return;
        event.setDroppedExp(el.xp);
        // Fishing encounters have their own bounded, owner-attributed loot table.
        // Keep elite XP/nameplate behavior, but do not also roll the generic elite drops.
        if (e.getPersistentDataContainer().has(fishingEncounterKey, PersistentDataType.BYTE)) return;
        Player killer = e.getKiller();
        if (killer == null) return;
        plugin.drops().roll(el.drops, killer, e.getLocation(), 1);
        if (el.coins > 0) {
            Eco.deposit(killer, el.coins);
            killer.sendActionBar(Text.mm("<gold>+" + Eco.fmt(el.coins) + " coins <gray>· " + el.color + el.name));
        }
    }
}
