package gg.aetherfall.core.mobs;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.economy.Eco;
import gg.aetherfall.core.items.DropManager.Drop;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Boss battles: summoned or world bosses with boss bars, abilities, enrage phases and shared loot. */
public final class BossManager implements Listener {
    private static final int VIEW_RANGE = 64;

    private final class ActiveBoss {
        final String id;
        final ConfigurationSection def;
        final LivingEntity entity;
        final BossBar bar;
        final Map<UUID, Double> damage = new HashMap<>();
        final Map<Integer, Long> nextAbility = new HashMap<>();
        final List<LivingEntity> minions = new ArrayList<>();
        final Set<Player> viewers = new HashSet<>();
        int phase;
        double enrage = 1.0;
        long lastPlayerSeen = System.currentTimeMillis();
        final long spawned = System.currentTimeMillis();

        ActiveBoss(String id, ConfigurationSection def, LivingEntity entity, BossBar bar) {
            this.id = id;
            this.def = def;
            this.entity = entity;
            this.bar = bar;
        }
    }

    private final AetherCore plugin;
    private final NamespacedKey bossKey;
    private final NamespacedKey minionKey;
    private final Map<UUID, ActiveBoss> active = new LinkedHashMap<>();
    private final List<Block> tempWebs = new ArrayList<>();
    private long nextWorldBoss;
    private boolean worldBossWarned;
    private BukkitTask tickTask;
    private BukkitTask worldBossTask;

    public BossManager(AetherCore plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.minionKey = new NamespacedKey(plugin, "minion");
        scheduleWorldBoss();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        worldBossTask = Bukkit.getScheduler().runTaskTimer(plugin, this::worldBossTimer, 200L, 200L);
    }

    private ConfigurationSection def(String id) {
        return plugin.mobs().config().getConfigurationSection("bosses." + id);
    }

    public List<String> ids() {
        ConfigurationSection s = plugin.mobs().config().getConfigurationSection("bosses");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    public boolean isBoss(Entity e) {
        return e.getPersistentDataContainer().has(bossKey) || e.getPersistentDataContainer().has(minionKey);
    }

    /** Returns the configured boss id stored on a boss entity, or null for boss minions/other entities. */
    public String idOf(Entity e) {
        return e.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
    }

    public int activeCount() {
        return active.size();
    }

    // ── spawning ──────────────────────────────────────────────

    /** Called when a player uses a summon relic. Returns false if not allowed here. */
    public boolean summon(Player player, String bossId, Location at) {
        ConfigurationSection d = def(bossId);
        if (d == null) return false;
        Location spawn = at.getWorld().getSpawnLocation();
        if (at.getWorld().equals(spawn.getWorld()) && at.distance(spawn) < 150) {
            player.sendMessage(Text.mm("<red>Bosses can't be summoned within 150 blocks of spawn."));
            return false;
        }
        for (ActiveBoss b : active.values()) {
            if (b.entity.getWorld().equals(at.getWorld()) && b.entity.getLocation().distance(at) < 96) {
                player.sendMessage(Text.mm("<red>Another boss is already fighting nearby."));
                return false;
            }
        }
        spawnBoss(bossId, at);
        plugin.data().recordFunnel(player, "first_boss");
        Bukkit.broadcast(Text.mm("<dark_red>☠</dark_red> <yellow>{player}</yellow> <gray>has summoned</gray> ", Map.of("player", player.getName()))
                .append(Text.mm(d.getString("color", "<red>") + d.getString("name", bossId)))
                .append(Text.mm("<gray> at " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + "!")));
        return true;
    }

    public LivingEntity spawnBoss(String bossId, Location at) {
        ConfigurationSection d = def(bossId);
        if (d == null) return null;
        EntityType type = EntityType.valueOf(d.getString("base", "ZOMBIE").toUpperCase(Locale.ROOT));
        String display = d.getString("color", "<red>") + d.getString("name", bossId);
        BossBar bar = BossBar.bossBar(Text.mm(display), 1f, color(d.getString("bar", "RED")), BossBar.Overlay.NOTCHED_10);
        at.getWorld().strikeLightningEffect(at);
        LivingEntity boss = at.getWorld().spawn(at, type.getEntityClass().asSubclass(LivingEntity.class), CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            e.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, bossId);
            MobManager.setBase(e, Attribute.MAX_HEALTH, d.getDouble("health", 500));
            e.setHealth(d.getDouble("health", 500));
            MobManager.setBase(e, Attribute.ATTACK_DAMAGE, d.getDouble("damage", 10));
            if (d.contains("speed")) MobManager.setBase(e, Attribute.MOVEMENT_SPEED, d.getDouble("speed"));
            if (d.contains("scale")) MobManager.setBase(e, Attribute.SCALE, d.getDouble("scale"));
            MobManager.setBase(e, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            MobManager.setBase(e, Attribute.FOLLOW_RANGE, 48);
            e.customName(Text.mm(display));
            e.setCustomNameVisible(true);
            e.setRemoveWhenFarAway(false);
            e.setPersistent(false); // never saved: a restart mid-fight simply ends the fight
            e.setCanPickupItems(false);
            EntityEquipment eq = e.getEquipment();
            ConfigurationSection gear = d.getConfigurationSection("equipment");
            if (eq != null && gear != null) {
                for (String slot : gear.getKeys(false)) {
                    ItemStack item = plugin.items().stack(gear.getString(slot), 1);
                    switch (slot) {
                        case "helmet" -> { eq.setHelmet(item); eq.setHelmetDropChance(0); }
                        case "chestplate" -> { eq.setChestplate(item); eq.setChestplateDropChance(0); }
                        case "leggings" -> { eq.setLeggings(item); eq.setLeggingsDropChance(0); }
                        case "boots" -> { eq.setBoots(item); eq.setBootsDropChance(0); }
                        case "mainhand" -> { eq.setItemInMainHand(item); eq.setItemInMainHandDropChance(0); }
                        default -> { }
                    }
                }
            }
        });
        active.put(boss.getUniqueId(), new ActiveBoss(bossId, d, boss, bar));
        at.getWorld().playSound(at, Sound.ENTITY_WITHER_SPAWN, 2f, 0.8f);
        return boss;
    }

    private static BossBar.Color color(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.RED;
        }
    }

    // ── fight loop ────────────────────────────────────────────

    private void tick() {
        long now = System.currentTimeMillis();
        for (ActiveBoss b : new ArrayList<>(active.values())) {
            LivingEntity e = b.entity;
            if (!e.isValid()) {
                end(b, false);
                continue;
            }
            double max = e.getAttribute(Attribute.MAX_HEALTH).getValue();
            b.bar.progress((float) Math.max(0, Math.min(1, e.getHealth() / max)));
            b.bar.name(Text.mm(b.def.getString("color", "<red>") + b.def.getString("name", b.id) + " <gray>· <red>"
                    + (int) e.getHealth() + "<gray>/<red>" + (int) max + "❤"));
            List<Player> near = nearbyPlayers(e.getLocation(), VIEW_RANGE);
            for (Player p : near) if (b.viewers.add(p)) p.showBossBar(b.bar);
            b.viewers.removeIf(p -> {
                if (!p.isOnline() || !near.contains(p)) { p.hideBossBar(b.bar); return true; }
                return false;
            });
            if (!near.isEmpty()) b.lastPlayerSeen = now;
            // Leave quietly if everyone runs away or the fight drags on forever.
            if (now - b.lastPlayerSeen > 120_000 || now - b.spawned > 30 * 60_000) {
                e.getWorld().spawnParticle(Particle.LARGE_SMOKE, e.getLocation().add(0, 1, 0), 40, 1, 1, 1, 0.05);
                e.remove();
                end(b, false);
                continue;
            }
            if (e instanceof Mob mob && (mob.getTarget() == null || !mob.getTarget().isValid()) && !near.isEmpty()) {
                mob.setTarget(near.stream().filter(p -> p.getGameMode() == org.bukkit.GameMode.SURVIVAL || p.getGameMode() == org.bukkit.GameMode.ADVENTURE)
                        .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(e.getLocation()))).orElse(null));
            }
            checkPhase(b, e.getHealth() / max);
            List<Map<?, ?>> abilities = b.def.getMapList("abilities");
            for (int i = 0; i < abilities.size(); i++) {
                Map<?, ?> a = abilities.get(i);
                double every = (a.get("every") instanceof Number n ? n.doubleValue() : 10) / b.enrage;
                long due = b.nextAbility.computeIfAbsent(i, k -> now + (long) (every * 1000));
                if (now < due || near.isEmpty()) continue;
                b.nextAbility.put(i, now + (long) (every * 1000));
                cast(b, a, near);
            }
            b.minions.removeIf(m -> !m.isValid());
        }
    }

    private void checkPhase(ActiveBoss b, double fraction) {
        List<Map<?, ?>> phases = b.def.getMapList("phases");
        if (b.phase >= phases.size()) return;
        Map<?, ?> ph = phases.get(b.phase);
        double at = ph.get("at") instanceof Number n ? n.doubleValue() : 0.5;
        if (fraction > at) return;
        b.phase++;
        double mult = ph.get("enrage") instanceof Number n ? n.doubleValue() : 1.2;
        b.enrage *= mult;
        var dmg = b.entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * mult);
        var spd = b.entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(spd.getBaseValue() * Math.min(1.25, mult));
        String msg = String.valueOf(ph.get("message") == null ? "It's getting angry!" : ph.get("message"));
        for (Player p : b.viewers) {
            p.showTitle(Title.title(Text.mm(b.def.getString("color", "<red>") + b.def.getString("name", b.id)), Text.mm("<gray>" + msg),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
        }
    }

    private void cast(ActiveBoss b, Map<?, ?> a, List<Player> near) {
        LivingEntity e = b.entity;
        Location loc = e.getLocation();
        double dmgMult = b.enrage;
        switch (String.valueOf(a.get("type"))) {
            case "nova" -> {
                double radius = num(a, "radius", 6);
                double damage = num(a, "damage", 6) * dmgMult;
                String effect = String.valueOf(a.get("effect"));
                Particle particle = particle(String.valueOf(a.get("particle")));
                for (int i = 0; i < 48; i++) {
                    double ang = Math.PI * 2 * i / 48;
                    loc.getWorld().spawnParticle(particle, loc.clone().add(Math.cos(ang) * radius, 0.5, Math.sin(ang) * radius), 2, 0.1, 0.2, 0.1, 0.01);
                }
                loc.getWorld().playSound(loc, Sound.ENTITY_EVOKER_CAST_SPELL, 1.5f, 0.7f);
                for (Player p : near) {
                    if (p.getLocation().distance(loc) > radius) continue;
                    p.damage(damage, e);
                    if (effect.equals("fire")) p.setFireTicks(100);
                    else {
                        PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(effect.toLowerCase(Locale.ROOT)));
                        if (t != null) p.addPotionEffect(new PotionEffect(t, 80, 1));
                    }
                }
            }
            case "leap" -> {
                LivingEntity target = e instanceof Mob m && m.getTarget() != null ? m.getTarget() : near.getFirst();
                Vector v = target.getLocation().toVector().subtract(loc.toVector());
                if (v.lengthSquared() < 1) return;
                v = v.normalize().multiply(Math.min(2.2, 0.6 + v.length() * 0.12));
                v.setY(0.9);
                e.setVelocity(v);
                loc.getWorld().playSound(loc, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.6f);
                double radius = num(a, "radius", 4), damage = num(a, "damage", 8) * dmgMult;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!e.isValid()) return;
                    Location land = e.getLocation();
                    land.getWorld().spawnParticle(Particle.EXPLOSION, land, 3, 1, 0.2, 1);
                    land.getWorld().playSound(land, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
                    for (Player p : nearbyPlayers(land, radius)) {
                        p.damage(damage, e);
                        p.setVelocity(p.getLocation().toVector().subtract(land.toVector()).normalize().multiply(0.8).setY(0.6));
                    }
                }, 25L);
            }
            case "minions" -> {
                if (b.minions.size() >= 8) return;
                String mob = String.valueOf(a.get("mob"));
                int count = (int) num(a, "count", 3);
                for (int i = 0; i < count; i++) {
                    Location at = loc.clone().add(ThreadLocalRandom.current().nextDouble(-4, 4), 0.5, ThreadLocalRandom.current().nextDouble(-4, 4));
                    LivingEntity m = plugin.mobs().spawn(mob, at);
                    if (m == null) continue;
                    m.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
                    m.setPersistent(false);
                    if (m instanceof Mob mm) mm.setTarget(near.get(ThreadLocalRandom.current().nextInt(near.size())));
                    b.minions.add(m);
                    at.getWorld().spawnParticle(Particle.SOUL, at, 12, 0.3, 0.5, 0.3, 0.02);
                }
                loc.getWorld().playSound(loc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 1f);
            }
            case "fireballs" -> {
                for (Player p : near) {
                    if (p.getLocation().distance(loc) > 32) continue;
                    Location eye = e.getEyeLocation();
                    Vector dir = p.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
                    SmallFireball fb = e.getWorld().spawn(eye.add(dir), SmallFireball.class);
                    fb.setShooter(e);
                    fb.setDirection(dir.multiply(0.6));
                }
                loc.getWorld().playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.8f);
            }
            case "teleport" -> {
                Player p = near.get(ThreadLocalRandom.current().nextInt(near.size()));
                Location behind = p.getLocation().subtract(p.getLocation().getDirection().setY(0).normalize().multiply(2.5));
                if (!behind.getBlock().isPassable() || !behind.clone().add(0, 1, 0).getBlock().isPassable()) return;
                loc.getWorld().spawnParticle(Particle.PORTAL, loc, 60, 0.5, 1, 0.5);
                e.teleport(behind);
                e.getWorld().playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
                if (e instanceof Mob m) m.setTarget(p);
            }
            case "webs" -> {
                for (Player p : near) {
                    Block feet = p.getLocation().getBlock();
                    if (!feet.getType().isAir()) continue;
                    feet.setType(Material.COBWEB, false);
                    tempWebs.add(feet);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (feet.getType() == Material.COBWEB) feet.setType(Material.AIR, false);
                        tempWebs.remove(feet);
                    }, 80L);
                }
                loc.getWorld().playSound(loc, Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 0.5f);
            }
            case "lightning" -> {
                double damage = num(a, "damage", 8) * dmgMult;
                for (Player p : near) {
                    if (p.getLocation().distance(loc) > 40) continue;
                    Location at = p.getLocation();
                    at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 0.2, 0), 30, 0.6, 0.1, 0.6);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        at.getWorld().strikeLightningEffect(at);
                        for (Player hit : nearbyPlayers(at, 2.5)) hit.damage(damage, e);
                    }, 20L); // telegraphed: one second to dodge
                }
            }
            default -> { }
        }
    }

    private static double num(Map<?, ?> m, String key, double def) {
        return m.get(key) instanceof Number n ? n.doubleValue() : def;
    }

    private static Particle particle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Particle.CRIT;
        }
    }

    private static List<Player> nearbyPlayers(Location at, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            if (p.getLocation().distance(at) <= radius) out.add(p);
        }
        return out;
    }

    // ── damage tracking & rewards ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        ActiveBoss b = active.get(event.getEntity().getUniqueId());
        if (b == null) return;
        Entity d = event.getDamager();
        if (d instanceof Projectile proj && proj.getShooter() instanceof Entity s) d = s;
        if (d instanceof Player p) b.damage.merge(p.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    // Bosses don't burn in daylight, don't drown, suffocate or take fall damage, and don't grief blocks.
    @EventHandler(ignoreCancelled = true)
    public void onEnvDamage(EntityDamageEvent event) {
        if (!active.containsKey(event.getEntity().getUniqueId())) return;
        switch (event.getCause()) {
            case FALL, DROWNING, SUFFOCATION, FIRE_TICK, FIRE, LAVA, CONTACT, BLOCK_EXPLOSION -> event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnAllowed(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM && isBoss(event.getEntity())) event.setCancelled(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (isBoss(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof SmallFireball fb && fb.getShooter() instanceof Entity s && isBoss(s)) event.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        // Endermen bosses only move when *we* teleport them.
        if (active.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        // Minions and bosses never fight each other.
        if (isBoss(event.getEntity()) && event.getTarget() != null && isBoss(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(minionKey)) {
            event.getDrops().clear();
            event.setDroppedExp(2);
            return;
        }
        ActiveBoss b = active.get(event.getEntity().getUniqueId());
        if (b == null) return;
        event.getDrops().clear();
        event.setDroppedExp(500);
        end(b, true);
    }

    private void end(ActiveBoss b, boolean killed) {
        active.remove(b.entity.getUniqueId());
        for (Player p : b.viewers) p.hideBossBar(b.bar);
        for (LivingEntity m : b.minions) if (m.isValid()) m.remove();
        if (!killed) return;
        String name = b.def.getString("color", "<red>") + b.def.getString("name", b.id);
        double total = b.damage.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return;
        List<Map.Entry<UUID, Double>> ranking = new ArrayList<>(b.damage.entrySet());
        ranking.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        double pool = b.def.getDouble("rewards.coins", 1000);
        List<Drop> loot = plugin.mobs().parseDrops(b.def.getMapList("rewards.loot"));
        Location at = b.entity.getLocation();

        Bukkit.broadcast(Text.mm("<gold>☠ </gold>").append(Text.mm(name)).append(Text.mm(" <gray>has been defeated!")));
        String[] medals = {"<gold>1st", "<white>2nd", "<#CD7F32>3rd"};
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            var en = ranking.get(i);
            Player p = Bukkit.getPlayer(en.getKey());
            String who = p != null ? p.getName() : Bukkit.getOfflinePlayer(en.getKey()).getName();
            Bukkit.broadcast(Text.mm(" " + medals[i] + " <yellow>{p}</yellow> <gray>— <red>" + (int) Math.round(en.getValue()) + " damage <dark_gray>("
                    + Math.round(en.getValue() * 100 / total) + "%)", Map.of("p", who == null ? "?" : who)));
        }
        for (int i = 0; i < ranking.size(); i++) {
            var en = ranking.get(i);
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            double share = en.getValue() / total;
            // Party play: members qualify for loot if their party together did >= 5%.
            double partyShare = 0;
            for (UUID m : plugin.social().group(en.getKey())) partyShare += b.damage.getOrDefault(m, 0.0) / total;
            double coins = pool * share + (i == 0 ? pool * 0.1 : 0);
            Eco.deposit(p, coins);
            p.sendMessage(Text.mm("<gold>Boss reward:</gold> <yellow>" + Eco.fmt(coins) + " coins <gray>(" + Math.round(share * 100) + "% of the damage"
                    + (i == 0 ? ", <gold>top damage bonus</gold>" : "") + ")"));
            if (share >= 0.05 || partyShare >= 0.05) {
                // Each contributor rolls the loot table separately; loot drops at their feet.
                plugin.drops().roll(loot, p, p.getLocation(), i == 0 ? 1.25 : 1.0);
            }
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at.clone().add(0, 1, 0), 120, 1, 1.5, 1, 0.3);
    }

    /** Removes everything on shutdown so no half-fights linger. */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (worldBossTask != null) {
            worldBossTask.cancel();
            worldBossTask = null;
        }
        for (ActiveBoss b : new ArrayList<>(active.values())) {
            b.entity.remove();
            end(b, false);
        }
        for (Block w : tempWebs) if (w.getType() == Material.COBWEB) w.setType(Material.AIR, false);
    }

    // ── world boss ────────────────────────────────────────────

    private void scheduleWorldBoss() {
        long minutes = Math.max(10, plugin.mobs().config().getLong("world-boss.interval-minutes", 180));
        nextWorldBoss = System.currentTimeMillis() + minutes * 60_000;
        worldBossWarned = false;
    }

    public Location arena() {
        String raw = plugin.getConfig().getString("world-boss-arena", "");
        if (raw.isBlank()) return null;
        String[] p = raw.split(",");
        var world = Bukkit.getWorld(p[0]);
        return world == null ? null : new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
    }

    public long secondsUntilWorldBoss() {
        return Math.max(0, (nextWorldBoss - System.currentTimeMillis()) / 1000);
    }

    private void worldBossTimer() {
        var cfg = plugin.mobs().config();
        String bossId = cfg.getString("world-boss.boss", "");
        Location arena = arena();
        if (arena == null || def(bossId) == null) return;
        if (Bukkit.getOnlinePlayers().size() < cfg.getInt("world-boss.min-players", 3)) {
            if (System.currentTimeMillis() > nextWorldBoss) scheduleWorldBoss();
            return;
        }
        long warnAt = nextWorldBoss - cfg.getLong("world-boss.warning-minutes", 5) * 60_000;
        if (!worldBossWarned && System.currentTimeMillis() >= warnAt) {
            worldBossWarned = true;
            ConfigurationSection d = def(bossId);
            Bukkit.broadcast(Text.mm("<dark_red><bold>WORLD BOSS</bold></dark_red> ").append(Text.mm(d.getString("color", "<red>") + d.getString("name", bossId)))
                    .append(Text.mm(" <gray>rises at the <yellow>Arena</yellow> in <gold>" + cfg.getLong("world-boss.warning-minutes", 5) + " minutes</gold>! <dark_gray>(/warp arena)")));
        }
        if (System.currentTimeMillis() >= nextWorldBoss) {
            startWorldBoss();
        }
    }

    public void startWorldBoss() {
        String bossId = plugin.mobs().config().getString("world-boss.boss", "");
        Location arena = arena();
        scheduleWorldBoss();
        if (arena == null || def(bossId) == null) return;
        for (ActiveBoss b : active.values()) if (b.id.equals(bossId)) return;
        spawnBoss(bossId, arena);
        ConfigurationSection d = def(bossId);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Text.mm("<dark_red><bold>WORLD BOSS"), Text.mm(d.getString("color", "<red>") + d.getString("name", bossId) + " <gray>has appeared!"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.8f);
        }
        Bukkit.broadcast(Text.mm("<dark_red><bold>WORLD BOSS</bold></dark_red> <gray>has appeared at the Arena! Everyone who helps gets a share. <yellow>/warp arena"));
    }
}
