package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.economy.Eco;
import gg.aetherfall.core.items.CustomItem.Type;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Everything custom items *do*: active abilities, on-hit effects, tool powers, set bonuses, talismans. */
public final class Abilities implements Listener {
    private static final Map<Material, Material> SMELT = Map.ofEntries(
            Map.entry(Material.RAW_IRON, Material.IRON_INGOT), Map.entry(Material.RAW_GOLD, Material.GOLD_INGOT),
            Map.entry(Material.RAW_COPPER, Material.COPPER_INGOT), Map.entry(Material.COBBLESTONE, Material.STONE),
            Map.entry(Material.COBBLED_DEEPSLATE, Material.DEEPSLATE), Map.entry(Material.SAND, Material.GLASS),
            Map.entry(Material.RED_SAND, Material.GLASS), Map.entry(Material.CLAY_BALL, Material.BRICK),
            Map.entry(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP), Map.entry(Material.NETHER_GOLD_ORE, Material.GOLD_INGOT));

    private final AetherCore plugin;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BlockFace> lastFace = new HashMap<>();
    private final Map<UUID, Set<String>> talismans = new HashMap<>();
    private boolean working;
    private BukkitTask passiveTask;

    public Abilities(AetherCore plugin) {
        this.plugin = plugin;
        passiveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::passives, 40L, 40L);
    }

    public void stop() {
        if (passiveTask != null) { passiveTask.cancel(); passiveTask = null; }
        cooldowns.clear(); lastFace.clear(); talismans.clear();
    }

    private CustomItem held(Player p) {
        return plugin.items().customOf(p.getInventory().getItemInMainHand());
    }

    private boolean cooldown(Player p, String ability, long millis) {
        String k = p.getUniqueId() + ":" + ability;
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(k);
        if (until != null && until > now) {
            p.sendActionBar(Text.mm("<red>Ability on cooldown (" + String.format(Locale.ROOT, "%.1f", (until - now) / 1000.0) + "s)"));
            return true;
        }
        cooldowns.put(k, now + millis);
        return false;
    }

    public boolean hasTalisman(Player p, String ability) {
        Set<String> s = talismans.get(p.getUniqueId());
        return s != null && s.contains(ability);
    }

    // ── passives: set bonuses, talismans, magnet ─────────────

    private void passives() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<String> found = new HashSet<>();
            Map<PotionEffectType, Integer> effects = new HashMap<>();
            for (ItemStack s : p.getInventory().getStorageContents()) {
                CustomItem c = plugin.items().customOf(s);
                if (c == null || c.type() != Type.TALISMAN) continue;
                if (c.ability() != null) found.add(c.ability());
                for (var e : c.effects().entrySet()) addEffect(effects, e.getKey(), e.getValue());
            }
            // Full set bonus: all four armor pieces from the same set.
            String set = null;
            boolean full = true;
            for (ItemStack a : p.getInventory().getArmorContents()) {
                CustomItem c = plugin.items().customOf(a);
                if (c == null || c.set() == null || (set != null && !set.equals(c.set()))) { full = false; break; }
                set = c.set();
            }
            if (full && set != null) {
                var def = plugin.items().set(set);
                if (def != null) {
                    boolean miner = set.equals("miner");
                    if (!miner || p.getLocation().getY() < 60) {
                        for (var e : def.effects().entrySet()) addEffect(effects, e.getKey(), e.getValue());
                    }
                }
            }
            for (var e : effects.entrySet()) {
                p.addPotionEffect(new PotionEffect(e.getKey(), e.getKey() == PotionEffectType.NIGHT_VISION ? 320 : 80, e.getValue(), true, false, true));
            }
            talismans.put(p.getUniqueId(), found);
            if (found.contains("magnet") && isMagnetEnabled(p)) {
                for (Entity en : p.getNearbyEntities(7, 4, 7)) {
                    if (en instanceof Item item && item.getPickupDelay() <= 0) {
                        Vector v = p.getLocation().add(0, 0.5, 0).toVector().subtract(item.getLocation().toVector());
                        item.setVelocity(v.normalize().multiply(0.6));
                    }
                }
            }
        }
    }

    private final Set<UUID> magnetDisabled = new HashSet<>();

    public boolean toggleMagnet(Player p) {
        if (magnetDisabled.contains(p.getUniqueId())) {
            magnetDisabled.remove(p.getUniqueId());
            return true;
        } else {
            magnetDisabled.add(p.getUniqueId());
            return false;
        }
    }

    public boolean isMagnetEnabled(Player p) {
        return !magnetDisabled.contains(p.getUniqueId());
    }

    private static void addEffect(Map<PotionEffectType, Integer> map, String key, int amp) {
        PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
        if (t != null) map.merge(t, amp, Math::max);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        talismans.remove(id);
        magnetDisabled.remove(id);
        lastFace.remove(id);
        cooldowns.keySet().removeIf(k -> k.startsWith(id.toString()));
    }

    // ── right-click abilities ─────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getBlockFace() != null) {
            lastFace.put(p.getUniqueId(), event.getBlockFace());
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        CustomItem c = held(p);
        if (c == null || c.ability() == null) return;
        if (c.ability().startsWith("summon:")) {
            event.setCancelled(true);
            if (event.getClickedBlock() == null) {
                p.sendActionBar(Text.mm("<red>Right-click the ground to summon."));
                return;
            }
            if (cooldown(p, "summon", 3_000)) return;
            Location at = event.getClickedBlock().getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5);
            if (plugin.bosses().summon(p, c.ability().substring(7), at)) consumeOne(p);
            return;
        }
        switch (c.ability()) {
            case "heal" -> {
                event.setCancelled(true);
                if (castCooldown(p, "heal", 20_000, 30)) return;
                double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + 12));
                p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 6, 0.4, 0.3, 0.4);
                p.playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.6f, 1.6f);
            }
            case "teleport" -> {
                event.setCancelled(true);
                if (castCooldown(p, "teleport", 1_500, 20)) return;
                teleportForward(p, 8);
            }
            case "starfall" -> {
                event.setCancelled(true);
                if (castCooldown(p, "starfall", 10_000, 50)) return;
                Block target = p.getTargetBlockExact(32);
                Location at = target == null ? p.getEyeLocation().add(p.getLocation().getDirection().multiply(16)) : target.getLocation().add(0.5, 1, 0.5);
                at.getWorld().strikeLightningEffect(at);
                at.getWorld().spawnParticle(Particle.END_ROD, at, 60, 1.5, 1, 1.5, 0.05);
                for (Entity e : at.getWorld().getNearbyEntities(at, 4, 4, 4)) {
                    if (e instanceof LivingEntity le && !(e instanceof Player) && e != p) le.damage(8, p);
                }
            }
            case "treasure" -> {
                event.setCancelled(true);
                consumeOne(p);
                openTreasure(p);
            }
            case "wisdom" -> {
                event.setCancelled(true);
                consumeOne(p);
                p.giveExp(1500);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
            default -> { }
        }
    }

    private boolean castCooldown(Player p, String ability, long millis, int cost) {
        var data = plugin.data().get(p);
        if (data == null || plugin.skills().currentMana(p, data) < cost) {
            p.sendActionBar(Text.mm("<aqua>Not enough mana! <gray>Requires " + cost));
            return true;
        }
        if (cooldown(p, ability, millis)) return true;
        return !plugin.skills().spendMana(p, cost);
    }

    private void teleportForward(Player p, int distance) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, distance);
        double d = hit == null ? distance : Math.max(0, hit.getHitPosition().distance(eye.toVector()) - 1);
        Location dest = p.getLocation().add(dir.clone().multiply(d));
        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());
        // Don't teleport into walls.
        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) return;
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3);
        p.teleport(dest);
        p.setFallDistance(0);
        p.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.3f);
    }

    private void consumeOne(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
    }

    private void openTreasure(Player p) {
        var cfg = plugin.drops().config();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Integer> coins = cfg.getIntegerList("treasure.coins");
        int c = coins.size() == 2 ? coins.get(0) + r.nextInt(Math.max(1, coins.get(1) - coins.get(0) + 1)) : 200;
        Eco.deposit(p, c);
        List<Map<?, ?>> loot = cfg.getMapList("treasure.loot");
        int total = 0;
        for (Map<?, ?> e : loot) total += e.get("weight") instanceof Number n ? n.intValue() : 1;
        int pick = r.nextInt(Math.max(1, total));
        for (Map<?, ?> e : loot) {
            pick -= e.get("weight") instanceof Number n ? n.intValue() : 1;
            if (pick < 0) {
                String key = String.valueOf(e.get("item"));
                List<?> amt = e.get("amount") instanceof List<?> l ? l : List.of(1, 1);
                int lo = ((Number) amt.get(0)).intValue(), hi = ((Number) amt.get(amt.size() - 1)).intValue();
                int n = lo + r.nextInt(Math.max(1, hi - lo + 1));
                plugin.items().give(p, key, n);
                p.sendMessage(Text.mm("<gold>Treasure!</gold> <gray>You found <gold>" + c + " coins</gold> and <white>" + n + "x</white> ")
                        .append(Text.mm(plugin.items().displayName(key))));
                break;
            }
        }
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        CustomItem c = plugin.items().customOf(event.getItem());
        if (c == null || !"feast".equals(c.ability())) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 3));
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 1200, 0));
        });
    }

    // ── combat ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p) || !(event.getEntity() instanceof LivingEntity target)) return;
        CustomItem c = held(p);
        if (c == null || c.ability() == null) return;
        switch (c.ability()) {
            case "frostbite" -> {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
            }
            case "ember" -> {
                if (target.getFireTicks() > 0) event.setDamage(event.getDamage() * 1.5);
                target.setFireTicks(Math.max(target.getFireTicks(), 80));
            }
            case "lifesteal" -> {
                double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + event.getFinalDamage() * 0.15));
            }
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player p) || !(event.getProjectile() instanceof Arrow arrow)) return;
        CustomItem c = plugin.items().customOf(event.getBow());
        if (c == null || !"triple_shot".equals(c.ability())) return;
        for (double angle : new double[]{-0.14, 0.14}) {
            Vector v = arrow.getVelocity().clone().rotateAroundY(angle);
            Arrow extra = p.launchProjectile(Arrow.class, v);
            extra.setDamage(arrow.getDamage());
            extra.setCritical(arrow.isCritical());
            extra.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player p = event.getPlayer();
        CustomItem c = held(p);
        if (c == null || !"grapple".equals(c.ability())) return;
        FishHook hook = event.getHook();
        boolean anchored = event.getState() == PlayerFishEvent.State.IN_GROUND
                || (event.getState() == PlayerFishEvent.State.REEL_IN && !hook.getLocation().getBlock().getRelative(BlockFace.DOWN).isPassable());
        if (!anchored || cooldown(p, "grapple", 2_000)) return;
        Vector pull = hook.getLocation().toVector().subtract(p.getLocation().toVector());
        Vector v = pull.clone().normalize().multiply(Math.min(2.4, 0.9 + pull.length() * 0.08));
        v.setY(Math.max(0.5, v.getY() + 0.4));
        p.setVelocity(v);
        p.setFallDistance(0);
        p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.8f);
    }

    // ── tool powers ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (working) return;
        Player p = event.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        CustomItem c = held(p);
        if (c == null || c.ability() == null) return;
        Block origin = event.getBlock();
        switch (c.ability()) {
            case "drill" -> { if (!p.isSneaking()) area(p, origin, Tag.MINEABLE_PICKAXE); }
            case "excavate" -> { if (!p.isSneaking()) area(p, origin, Tag.MINEABLE_SHOVEL); }
            case "replenish" -> replenish(p, origin);
            default -> { }
        }
    }

    /** Breaks the 3x3 plane facing the player (ignoring containers and unbreakable blocks). */
    private void area(Player p, Block origin, Tag<Material> tag) {
        if (!tag.isTagged(origin.getType())) return;
        BlockFace face = lastFace.getOrDefault(p.getUniqueId(), BlockFace.UP);
        List<Block> targets = new ArrayList<>();
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            if (a == 0 && b == 0) continue;
            Block t = switch (face) {
                case UP, DOWN -> origin.getRelative(a, 0, b);
                case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                default -> origin.getRelative(0, a, b);
            };
            float hardness = t.getType().getHardness();
            if (tag.isTagged(t.getType()) && hardness >= 0 && hardness < 30 && !(t.getState() instanceof org.bukkit.block.Container)) targets.add(t);
        }
        working = true;
        try {
            for (Block t : targets) p.breakBlock(t);
        } finally {
            working = false;
        }
    }

    private void replenish(Player p, Block origin) {
        if (!(origin.getBlockData() instanceof Ageable crop) || crop.getAge() != crop.getMaximumAge()) return;
        List<Block> crops = new ArrayList<>();
        crops.add(origin);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            Block t = origin.getRelative(dx, 0, dz);
            if (t.getType() == origin.getType() && t.getBlockData() instanceof Ageable a && a.getAge() == a.getMaximumAge()) crops.add(t);
        }
        Material type = origin.getType();
        working = true;
        try {
            for (Block t : crops) if (t != origin) p.breakBlock(t);
        } finally {
            working = false;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Block t : crops) {
                if (t.getType().isAir() && type.createBlockData().isSupported(t)) t.setType(type);
            }
        }, 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        CustomItem c = held(event.getPlayer());
        if (c == null || c.ability() == null) return;
        Material broken = event.getBlockState().getType();
        switch (c.ability()) {
            case "autosmelt" -> {
                boolean any = false;
                for (Item item : event.getItems()) {
                    ItemStack s = item.getItemStack();
                    Material out = SMELT.get(s.getType());
                    if (out != null && plugin.items().idOf(s) == null) {
                        item.setItemStack(new ItemStack(out, s.getAmount()));
                        any = true;
                    }
                }
                if (any) event.getBlock().getWorld().spawnParticle(Particle.FLAME, event.getBlock().getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.01);
            }
            case "woodsman" -> {
                if (!Tag.LOGS.isTagged(broken)) return;
                for (Item item : event.getItems()) {
                    ItemStack s = item.getItemStack();
                    if (plugin.items().idOf(s) == null) s.setAmount(Math.min(s.getMaxStackSize(), s.getAmount() * 2));
                    item.setItemStack(s);
                }
            }
            default -> { }
        }
    }
}
