package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

/** Hypixel-style skill progression and derived RPG stats. This is intentionally data/API first so menus can grow on it. */
public final class SkillManager implements Listener, org.bukkit.command.TabExecutor {
    public static final java.util.List<String> SKILLS = java.util.List.of("combat", "mining", "farming", "fishing", "foraging", "enchanting");
    /** Server-owned provenance for every XP grant. Never accept client supplied values here. */
    public enum XpSource { BLOCK_BREAK, FISHING_CATCH, ENTITY_DEATH, ENCHANT, INTERNAL }
    private final AetherCore plugin;
    private final EquipmentCustomization equipment;
    private final CustomEnchantments customEnchants;
    private final Map<UUID, Integer> mana = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Window>> xpWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> actionTimes = new ConcurrentHashMap<>();
    private final Set<UUID> awardedDeaths = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> acceptedBySource = new ConcurrentHashMap<>();
    private final Map<String, Long> rejectedByReason = new ConcurrentHashMap<>();
    private BukkitTask regen;
    private final org.bukkit.NamespacedKey healthKey;

    public SkillManager(AetherCore plugin) {
        this.plugin = plugin;
        this.equipment = new EquipmentCustomization(plugin);
        this.customEnchants = new CustomEnchantments(plugin);
        healthKey = new org.bukkit.NamespacedKey(plugin, "skill_health");
        this.regen = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                refresh(p, false);
                PlayerData d = plugin.data().get(p);
                if (d != null) mana.put(p.getUniqueId(), Math.min(maxMana(d, p), mana.getOrDefault(p.getUniqueId(), maxMana(d, p)) + 2));
                if (d != null) {
                    var a = p.getAttribute(Attribute.MAX_HEALTH);
                    p.sendActionBar(Text.mm("<red>❤ " + Math.round(p.getHealth() * 5) + "/" + Math.round(a == null ? 100 : a.getValue() * 5)
                            + "  <green>❈ " + Math.round(stats(p, d).defense()) + "  <aqua>✎ " + currentMana(p, d) + "/" + maxMana(d, p)));
                }
            }
        }, 20L, 20L);
    }

    public EquipmentCustomization equipment() { return equipment; }

    public int level(PlayerData d, String skill) { return Math.max(0, Math.min(50, d.skillLevels.getOrDefault(skill.toLowerCase(Locale.ROOT), 0))); }

    public int totalLevel(PlayerData d) { return d.skillLevels.values().stream().mapToInt(Integer::intValue).sum(); }
    public long xp(PlayerData d, String skill) { return Math.max(0L, Math.min(xpForNext(49), d.skillXp.getOrDefault(skill.toLowerCase(Locale.ROOT), 0L))); }
    public long xpForNext(int level) { return 100L * (level + 1L) * (level + 1L); }

    public void addXp(Player player, String skill, long amount) {
        addXp(player, skill, amount, XpSource.INTERNAL, "plugin");
    }

    /**
     * Grants XP only after validating a server-generated source and action signature.
     * Event handlers must pass a stable signature so replayed/automated events are rate limited.
     */
    public void addXp(Player player, String skill, long amount, XpSource source, String signature) {
        if (amount <= 0) return;
        if (player == null || source == null || signature == null || signature.isBlank() || signature.length() > 200
                || signature.chars().anyMatch(ch -> ch < 32 || ch == 127)) { reject("invalid_provenance"); return; }
        skill = skill.toLowerCase(Locale.ROOT);
        if (!SKILLS.contains(skill)) { reject("invalid_skill"); return; }
        PlayerData d = plugin.data().get(player);
        if (d == null) { reject("unloaded_player"); return; }
        if (!allowXp(player, skill, amount)) { reject("rate_limit_or_state"); return; }
        acceptedBySource.merge(source.name(), amount, Long::sum);
        int old = level(d, skill);
        long cap = xpForNext(49);
        long currentXp = Math.max(0L, Math.min(cap, xp(d, skill)));
        long grant = Math.min(amount, cap - currentXp);
        long total = currentXp + grant;
        int next = old;
        while (next < 50 && total >= xpForNext(next)) next++;
        d.skillXp.put(skill, total);
        d.skillLevels.put(skill, next);
        if (next > old) {
            player.sendMessage(Text.mm("<green>✦ " + nice(skill) + " Level Up! <white>" + old + " ➜ <gold>" + next));
            if (old == 0) plugin.data().recordFunnel(player, "first_skill_level");
            for (int level = old + 1; level <= next; level++) grantLevelRewards(player, skill, level);
            plugin.onboarding().complete(player, Onboarding.Step.SKILL);
            refresh(player, true);
        }
        if (next > old) plugin.data().saveAsync(d); // ordinary gains are persisted by autosave and logout
    }

    private void reject(String reason) { rejectedByReason.merge(reason, 1L, Long::sum); }

    /** Compact counters for staff diagnostics; values are process-local by design. */
    public String xpAudit() {
        return "accepted=" + acceptedBySource + ", rejected=" + rejectedByReason;
    }

    public void show(Player player) {
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        Stats s = stats(player, d);
        player.sendMessage(Text.mm("<gradient:#8B5CF6:#22D3EE><bold>Aetherfall Skills & Stats"));
        for (String skill : SKILLS) {
            int lvl = level(d, skill);
            long current = xp(d, skill), needed = xpForNext(lvl);
            player.sendMessage(Text.mm("<gray>" + nice(skill) + " <white>" + lvl + " <dark_gray>(" + current + "/" + needed + " XP) <dark_gray>· <yellow>" + rewardText(skill)));
        }
        player.sendMessage(Text.mm("<red>❤ Health: <white>" + Math.round(s.health()) + "  <green>❈ Defense: <white>" + Math.round(s.defense()) + "  <aqua>✎ Mana: <white>" + currentMana(player, d) + "/" + Math.round(s.mana())));
        player.sendMessage(Text.mm("<red>⚔ Strength: <white>" + Math.round(s.strength()) + "  <yellow>☣ Crit Chance: <white>" + Math.round(s.critChance()) + "%  <yellow>Crit Damage: <white>" + Math.round(s.critDamage()) + "%"));
    }

    public record Stats(double health, double defense, double mana, double strength, double critChance,
                        double critDamage, double speed, double fortune) { }

    public Stats stats(Player player, PlayerData d) {
        EquipmentCustomization.Stats gear = equipment.equipped(player);
        return new Stats(100 + level(d, "combat") * 2 + level(d, "farming") + gear.health(),
                level(d, "combat") * 2 + level(d, "mining") + gear.defense(),
                100 + level(d, "enchanting") * 5 + gear.mana(),
                level(d, "combat") + level(d, "foraging") + gear.strength(),
                level(d, "fishing") * 0.5 + gear.critChance(),
                50 + level(d, "combat") + gear.critDamage(),
                100 + gear.speed(), gear.fortune());
    }

    public int currentMana(Player p, PlayerData d) { return mana.computeIfAbsent(p.getUniqueId(), id -> maxMana(d, p)); }
    public boolean spendMana(Player p, int amount) {
        PlayerData d = plugin.data().get(p); if (d == null || amount < 0) return false;
        int have = currentMana(p, d); if (have < amount) return false;
        mana.put(p.getUniqueId(), have - amount); return true;
    }
    public void restoreMana(Player p, int amount) {
        if (amount <= 0) return;
        PlayerData d = plugin.data().get(p); if (d == null) return;
        mana.put(p.getUniqueId(), Math.min(maxMana(d, p), currentMana(p, d) + amount));
    }
    private int maxMana(PlayerData d, Player p) { return (int) Math.round(stats(p, d).mana()); }

    public void refresh(Player p, boolean announce) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        equipment.refreshSetEffects(p);
        Stats s = stats(p, d);
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            double bonus = Math.max(0, s.health() / 5.0 - 20);
            var previous = attr.getModifiers().stream().filter(m -> m.getKey().equals(healthKey)).findFirst().orElse(null);
            if (previous == null || previous.getAmount() != bonus) {
                if (previous != null) attr.removeModifier(previous);
                if (bonus > 0) attr.addTransientModifier(new org.bukkit.attribute.AttributeModifier(healthKey, bonus, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER));
            }
            double max = attr.getValue();
            if (p.getHealth() > max) p.setHealth(max);
        }
        mana.putIfAbsent(p.getUniqueId(), (int) Math.round(s.mana()));
        mana.computeIfPresent(p.getUniqueId(), (id, value) -> Math.min(value, (int) Math.round(s.mana())));
        if (announce) p.sendActionBar(Text.mm("<red>❤ " + Math.round(s.health()) + "  <green>❈ " + Math.round(s.defense()) + "  <aqua>✎ " + currentMana(p, d)));
    }

    @EventHandler public void join(PlayerJoinEvent e) {
        migrateAuraSkills(e.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> refresh(e.getPlayer(), true));
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) { Bukkit.getScheduler().runTask(plugin, () -> refresh(e.getPlayer(), true)); }
    @EventHandler public void held(PlayerItemHeldEvent e) { Bukkit.getScheduler().runTask(plugin, () -> refresh(e.getPlayer(), false)); }
    @EventHandler public void click(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> refresh(p, false)); }
    @EventHandler public void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> refresh(p, false)); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void breakBlock(BlockBreakEvent e) {
        Material m = e.getBlock().getType();
        if (e.getPlayer().getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        String locationKey = "block:" + e.getBlock().getWorld().getUID() + ":" + e.getBlock().getX() + ":" + e.getBlock().getY() + ":" + e.getBlock().getZ();
        if (!once(e.getPlayer(), locationKey, 900L)) return;
        if (e.getBlock().getBlockData() instanceof org.bukkit.block.data.Ageable crop) {
            if (crop.getAge() == crop.getMaximumAge() && !m.name().endsWith("_STEM")) addXp(e.getPlayer(), "farming", 3, XpSource.BLOCK_BREAK, blockSignature(e));
            return;
        }
        if (plugin.placedBlocks().isPlaced(e.getBlock())) return;
        String skill = m.name().endsWith("_LOG") || m.name().endsWith("_WOOD") || m.name().contains("LEAVES") ? "foraging" :
                (m.name().contains("WHEAT") || m.name().contains("CARROT") || m.name().contains("POTATO") || m.name().contains("BEETROOT") || m.name().contains("MELON") || m.name().contains("PUMPKIN") ? "farming" :
                (m.name().contains("ORE") || m == Material.STONE || m == Material.DEEPSLATE ? "mining" : null));
        if (skill != null) addXp(e.getPlayer(), skill, skill.equals("mining") ? 5 : 3, XpSource.BLOCK_BREAK, blockSignature(e));
    }
    @EventHandler(ignoreCancelled = true) public void fish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof org.bukkit.entity.Item)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL || (!p.getInventory().getItemInMainHand().getType().equals(Material.FISHING_ROD)
                && !p.getInventory().getItemInOffHand().getType().equals(Material.FISHING_ROD))) return;
        if (once(p, "fish", 1200L)) {
            plugin.data().recordFunnel(p, "first_fishing");
            addXp(p, "fishing", 5, XpSource.FISHING_CATCH, "catch:" + p.getUniqueId() + ":" + p.getWorld().getUID());
        }
    }
    @EventHandler public void death(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (awardedDeaths.size() > 100_000) awardedDeaths.clear();
        if (p != null && p.getGameMode() == org.bukkit.GameMode.SURVIVAL && !(e.getEntity() instanceof Player)
                && !e.getEntity().fromMobSpawner() && awardedDeaths.add(e.getEntity().getUniqueId()))
            addXp(p, "combat", Math.max(3, Math.min(50, e.getDroppedExp() + 5)), XpSource.ENTITY_DEATH,
                    "death:" + e.getEntity().getUniqueId() + ":" + e.getEntity().getType().name());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void enchant(org.bukkit.event.enchantment.EnchantItemEvent e) {
        if (e.getEnchanter().getGameMode() == org.bukkit.GameMode.SURVIVAL && e.getExpLevelCost() > 0)
            addXp(e.getEnchanter(), "enchanting", Math.min(150, e.getExpLevelCost() * 5L), XpSource.ENCHANT,
                    "enchant:" + e.getEnchanter().getUniqueId() + ":" + e.getItem().getType().name() + ":" + e.getExpLevelCost());
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e) { mana.remove(e.getPlayer().getUniqueId()); xpWindows.remove(e.getPlayer().getUniqueId()); actionTimes.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void damage(EntityDamageEvent e) {
        Entity entity = e.getEntity(); if (!(entity instanceof Player p)) return;
        PlayerData d = plugin.data().get(p); if (d == null) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.SUICIDE) return;
        double defense = stats(p, d).defense();
        e.setDamage(e.getDamage() * (100.0 / (100.0 + Math.max(0, defense))));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void attack(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        PlayerData d = plugin.data().get(p); if (d == null) return;
        Stats s = stats(p, d);
        double damage = e.getDamage() * (1 + s.strength() / 100);
        Map<String, Integer> enchantLevels = customEnchants.levels(p.getInventory().getItemInMainHand());
        LivingEntity target = (LivingEntity) e.getEntity();
        var maxHealth = target.getAttribute(Attribute.MAX_HEALTH);
        double targetMax = maxHealth == null ? target.getHealth() : maxHealth.getValue();
        if (targetMax > 0 && target.getHealth() / targetMax <= 0.30)
            damage *= 1.0 + enchantLevels.getOrDefault("executioner", 0) * 0.02;
        if (plugin.bosses().isBoss(target))
            damage *= 1.0 + enchantLevels.getOrDefault("boss_hunter", 0) * 0.02;
        var weapon = plugin.items().customOf(p.getInventory().getItemInMainHand());
        if (weapon != null && (weapon.type() == gg.aetherfall.core.items.CustomItem.Type.WEAPON
                || weapon.type() == gg.aetherfall.core.items.CustomItem.Type.BOW)) {
            damage *= Math.max(1.0, plugin.getConfig().getDouble("combat.custom-weapon-damage-multiplier", 1.35));
        }
        boolean critical = p.getAttackCooldown() >= 0.9 && java.util.concurrent.ThreadLocalRandom.current().nextDouble(100) < Math.min(100, s.critChance());
        if (critical) {
            damage *= 1.0 + Math.max(0.0, s.critDamage()) / 100.0;
            int drain = enchantLevels.getOrDefault("aether_drain", 0);
            if (drain > 0) restoreMana(p, drain * 2);
        }
        e.setDamage(damage);
    }

    public void stop() {
        if (regen != null) regen.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var attr = p.getAttribute(Attribute.MAX_HEALTH);
            if (attr == null) continue;
            for (var mod : attr.getModifiers()) if (mod.getKey().equals(healthKey)) attr.removeModifier(mod);
            if (p.getHealth() > attr.getValue()) p.setHealth(attr.getValue());
        }
        mana.clear();
        xpWindows.clear(); actionTimes.clear(); awardedDeaths.clear();
        acceptedBySource.clear(); rejectedByReason.clear();
    }

    private static final class Window { long started; long amount; Window(long started) { this.started = started; } }

    private boolean allowXp(Player player, String skill, long amount) {
        if (!player.isOnline() || player.getGameMode() != org.bukkit.GameMode.SURVIVAL || amount <= 0) return false;
        long cap = plugin.getConfig().getLong("skills.xp-rate-limits." + skill, switch (skill) {
            case "combat" -> 2_500L; case "mining", "foraging", "farming" -> 1_500L;
            case "fishing" -> 600L; case "enchanting" -> 2_000L; default -> 0L;
        });
        if (cap <= 0) return false;
        long now = System.currentTimeMillis();
        Map<String, Window> windows = xpWindows.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        Window window = windows.computeIfAbsent(skill, id -> new Window(now));
        if (now - window.started >= 60_000L) { window.started = now; window.amount = 0; }
        if (window.amount < 0 || window.amount > cap || amount > cap - window.amount) return false;
        window.amount += amount;
        return true;
    }

    private boolean once(Player player, String key, long cooldown) {
        if (player == null || key == null || key.isBlank() || key.length() > 200 || cooldown < 1) { reject("invalid_action_signature"); return false; }
        long now = System.currentTimeMillis();
        Map<String, Long> times = actionTimes.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        times.entrySet().removeIf(entry -> now - entry.getValue() > Math.max(5_000L, cooldown * 4));
        Long previous = times.put(key, now);
        return previous == null || now - previous >= cooldown;
    }

    private static String blockSignature(BlockBreakEvent e) {
        var b = e.getBlock();
        return "block:" + b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ() + ":" + b.getType().name();
    }

    /** Imports the compatible part of AuraSkills once before the replacement system takes over. */
    /** Runs the one-time migration for an online player. Returns true when a file was imported. */
    public boolean migrateAuraSkills(Player player) {
        PlayerData data = plugin.data().get(player);
        if (data == null || data.skillXp.containsKey("_auraskills_migrated")) return false;
        File file = new File(plugin.getDataFolder().getParentFile(), "AuraSkills/userdata/" + player.getUniqueId() + ".yml");
        if (!file.isFile()) return false;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("skills");
        if (section == null) return false;
        for (String raw : section.getKeys(false)) {
            String id = raw.toLowerCase(Locale.ROOT).replace("auraskills/", "");
            id = switch (id) { case "fighting" -> "combat"; default -> id; };
            if (!SKILLS.contains(id)) continue;
            int oldLevel = Math.max(0, Math.min(50, section.getInt(raw + ".level", 0)));
            long oldProgress = Math.max(0L, Math.round(section.getDouble(raw + ".xp", 0D)));
            // AuraSkills stores XP progress within the current level. AetherCore
            // stores cumulative XP, so preserve the level and add the progress
            // after the native threshold for that level.
            long nativeCap = xpForNext(49);
            long nativeBase = oldLevel <= 0 ? 0 : xpForNext(Math.min(49, oldLevel - 1));
            long nativeNext = oldLevel >= 50 ? nativeCap : xpForNext(oldLevel);
            long nativeProgress = Math.min(oldProgress, Math.max(0L, nativeNext - nativeBase));
            long importedXp = Math.min(nativeCap, nativeBase + nativeProgress);
            long existingXp = Math.max(0L, Math.min(nativeCap, data.skillXp.getOrDefault(id, 0L)));
            data.skillXp.put(id, Math.max(existingXp, importedXp));
            data.skillLevels.put(id, Math.max(level(data, id), oldLevel));
        }
        data.skillXp.put("_auraskills_migrated", 1L);
        plugin.data().saveAsync(data);
        plugin.getLogger().info("Migrated compatible AuraSkills progress for " + player.getName() + ".");
        return true;
    }

    /** A concise audit string used by administrators while validating the replacement system. */
    public String audit(Player player) {
        PlayerData d = plugin.data().get(player);
        if (d == null) return "not-loaded";
        return SKILLS.stream().map(s -> nice(s) + "=" + level(d, s) + "/" + Text.number(xp(d, s)) + "xp").collect(java.util.stream.Collectors.joining(", "));
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        var menu = new gg.aetherfall.core.util.Menu(6, Text.mm("<dark_gray>Your Skills"));
        Material[] icons = {Material.STONE_SWORD, Material.GOLDEN_HOE, Material.FISHING_ROD, Material.STONE_PICKAXE, Material.JUNGLE_SAPLING, Material.ENCHANTING_TABLE};
        int[] slots = {19, 20, 21, 22, 23, 24};
        Stats stats = stats(p, d);
        double average = SKILLS.stream().mapToInt(skill -> level(d, skill)).average().orElse(0.0);
        menu.set(4, new gg.aetherfall.core.util.ItemBuilder(Material.DIAMOND_SWORD).name("<aqua><bold>Your Skills")
                .lore(java.util.List.of("<gray>View your Skill progression and rewards.", "<gray>Skill Average: <aqua>" + String.format(Locale.ROOT, "%.2f", average),
                        "<red>❤ Health: <white>" + Math.round(stats.health()), "<green>❈ Defense: <white>" + Math.round(stats.defense()),
                        "<aqua>✎ Mana: <white>" + currentMana(p, d) + "/" + Math.round(stats.mana()), "", "<yellow>▶ Click to show stats")).glow(true).build(), (pl, c) -> show(pl));

        String[] overviewSkills = {"combat", "farming", "fishing", "mining", "foraging", "enchanting"};
        for (int i = 0; i < overviewSkills.length; i++) {
            String id = overviewSkills[i]; int lvl = level(d, id);
            String reward = rewardText(id);
            long current = xp(d, id);
            long previous = lvl == 0 ? 0 : xpForNext(Math.min(49, lvl - 1));
            long target = lvl >= 50 ? xpForNext(49) : xpForNext(lvl);
            long progress = Math.max(0, current - previous);
            long needed = Math.max(1, target - previous);
            String next = lvl >= 50 ? "MAX" : roman(lvl + 1);
            String progressLine = lvl >= 50 ? "<gold>✦ MAX LEVEL" : "<gray>Progress to Level " + next + ": <white>" + Text.number(Math.min(progress, needed)) + "/" + Text.number(needed) + " XP";
            String progressLineBar = lvl >= 50 ? "<gold>" + "■".repeat(10) : "<dark_gray>" + progressBar(progress, needed);
            menu.set(slots[i], new gg.aetherfall.core.util.ItemBuilder(icons[i]).name(skillColor(id) + "<bold>" + nice(id) + " " + roman(lvl))
                    .lore(java.util.List.of("<gray>" + skillDescription(id), "", progressLine,
                            progressLineBar, "", "<gray>Reward: <green>" + reward, "<gray>Milestones: <gold>" + milestoneText(id),
                            lvl >= 50 ? "<gold>✦ MAX LEVEL" : "<yellow>▶ Click to view")).glow(lvl >= 50).build(), (pl, c) -> openSkillDetail(pl, id, 0));
        }
        addLockedSkill(menu, 25, Material.BREWING_STAND, "Alchemy");
        addLockedSkill(menu, 28, Material.CRAFTING_TABLE, "Carpentry");
        addLockedSkill(menu, 29, Material.MAGMA_CREAM, "Runecrafting");
        addLockedSkill(menu, 30, Material.WOLF_SPAWN_EGG, "Taming");
        addLockedSkill(menu, 32, Material.EMERALD, "Social");
        addLockedSkill(menu, 33, Material.LEAD, "Hunting");
        addLockedSkill(menu, 34, Material.WITHER_SKELETON_SKULL, "Dungeoneering");
        menu.set(45, new gg.aetherfall.core.util.ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.set(49, new gg.aetherfall.core.util.ItemBuilder(Material.BARRIER).name("<red>Close").build(), (pl, c) -> pl.closeInventory());
        menu.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private static String progressBar(long current, long needed) {
        int filled = needed <= 0 ? 10 : (int) Math.round(Math.min(1.0, (double) current / needed) * 10);
        return "<green>" + "■".repeat(filled) + "<dark_gray>" + "■".repeat(10 - filled);
    }

    private void openSkillDetail(Player p, String skill, int page) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        int lvl = level(d, skill);
        long current = xp(d, skill);
        long previous = lvl == 0 ? 0 : xpForNext(Math.min(49, lvl - 1));
        long target = lvl >= 50 ? xpForNext(49) : xpForNext(lvl);
        long progress = Math.max(0, current - previous);
        long needed = Math.max(1, target - previous);
        String detailProgress = lvl >= 50 ? "<gold>✦ MAX LEVEL" : "<gray>Progress to Level " + roman(lvl + 1) + ": <white>" + Text.number(Math.min(progress, needed)) + "/" + Text.number(needed) + " XP";
        String detailBar = lvl >= 50 ? "<gold>" + "■".repeat(10) : "<dark_gray>" + progressBar(progress, needed);
        page = Math.max(0, Math.min(1, page));
        final int currentPage = page;
        var menu = new gg.aetherfall.core.util.Menu(6, Text.mm("<dark_gray>" + nice(skill) + " Skill"));
        menu.set(0, new gg.aetherfall.core.util.ItemBuilder(skillIcon(skill)).name(skillColor(skill) + "<bold>" + nice(skill) + " " + roman(lvl))
                .lore(java.util.List.of(detailProgress, detailBar,
                        "", "<gray>Reward per level: <green>" + rewardText(skill), "<gray>Milestone reward: <gold>" + milestoneText(skill))).glow(lvl >= 50).build());
        int[] levelSlots = {9, 18, 27, 28, 29, 20, 11, 2, 3, 4, 13, 22, 31, 32, 33, 24, 15, 6, 7, 8, 17, 26, 35, 44, 53};
        for (int i = 0; i < levelSlots.length; i++) {
            int level = currentPage * 25 + i + 1;
            boolean unlocked = lvl >= level;
            boolean isCurrent = lvl == level;
            Material pane = isCurrent ? Material.YELLOW_STAINED_GLASS_PANE : unlocked ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            String reward = level % 5 == 0 ? milestoneText(skill) : rewardText(skill);
            menu.set(levelSlots[i], new gg.aetherfall.core.util.ItemBuilder(pane, Math.min(64, level))
                    .name((isCurrent ? "<yellow>" : unlocked ? "<green>" : "<red>") + nice(skill) + " Level " + roman(level))
                    .lore(java.util.List.of(isCurrent ? "<yellow>● Current level" : unlocked ? "<green>✔ Unlocked" : "<gray>Locked", "<gray>Reward: <gold>" + reward)).glow(isCurrent).build());
        }
        menu.set(39, new gg.aetherfall.core.util.ItemBuilder(Material.BOOK).name("<aqua>Bestiary").lore(java.util.List.of("<gray>Track your creature milestones.", "", "<yellow>▶ Click to open")).build(), (pl, c) -> plugin.bestiary().open(pl));
        menu.set(41, new gg.aetherfall.core.util.ItemBuilder(Material.BAT_SPAWN_EGG).name("<red>Slayer").lore(java.util.List.of("<gray>Hunt bosses for combat rewards.", "", "<yellow>▶ Click to open")).build(), (pl, c) -> plugin.slayer().open(pl));
        menu.set(45, new gg.aetherfall.core.util.ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (pl, c) -> open(pl));
        menu.set(49, new gg.aetherfall.core.util.ItemBuilder(Material.BARRIER).name("<red>Close").build(), (pl, c) -> pl.closeInventory());
        if (currentPage == 0) menu.set(50, new gg.aetherfall.core.util.ItemBuilder(Material.ARROW).name("<yellow>Next Page →").build(), (pl, c) -> openSkillDetail(pl, skill, 1));
        else menu.set(50, new gg.aetherfall.core.util.ItemBuilder(Material.ARROW).name("<yellow>← Previous Page").build(), (pl, c) -> openSkillDetail(pl, skill, 0));
        menu.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private static void addLockedSkill(gg.aetherfall.core.util.Menu menu, int slot, Material icon, String name) {
        menu.set(slot, new gg.aetherfall.core.util.ItemBuilder(icon).name("<dark_gray>" + name)
                .lore(java.util.List.of("<gray>Not unlocked in Aetherfall yet.")).build());
    }

    private static Material skillIcon(String skill) {
        return switch (skill) {
            case "combat" -> Material.STONE_SWORD;
            case "mining" -> Material.STONE_PICKAXE;
            case "farming" -> Material.GOLDEN_HOE;
            case "fishing" -> Material.FISHING_ROD;
            case "foraging" -> Material.JUNGLE_SAPLING;
            case "enchanting" -> Material.ENCHANTING_TABLE;
            default -> Material.EXPERIENCE_BOTTLE;
        };
    }

    private static String skillDescription(String skill) {
        return switch (skill) {
            case "combat" -> "Defeat mobs and bosses to gain Combat XP.";
            case "mining" -> "Break ores and stone to gain Mining XP.";
            case "farming" -> "Harvest crops to gain Farming XP.";
            case "fishing" -> "Catch fish and sea creatures to gain Fishing XP.";
            case "foraging" -> "Chop trees to gain Foraging XP.";
            case "enchanting" -> "Enchant items to gain Enchanting XP.";
            default -> "Play Aetherfall to gain skill XP.";
        };
    }

    private static String skillColor(String skill) {
        return "<green>";
    }

    private static String roman(int value) {
        if (value <= 0) return "0";
        int[] numbers = {50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < numbers.length; i++) while (value >= numbers[i]) { out.append(symbols[i]); value -= numbers[i]; }
        return out.toString();
    }

    @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (command.getName().equals("equipment")) equipment.open(p); else open(p);
        return true;
    }
    @Override public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) { return java.util.List.of(); }

    private void grantLevelRewards(Player player, String skill, int level) {
        PlayerData data = plugin.data().get(player);
        if (data == null) return;
        String item = milestoneItem(skill, level);
        if (level > 0 && level % 5 == 0 && item != null && !plugin.items().isValidKey(item)) {
            plugin.getLogger().warning("Missing skill milestone item '" + item + "' for " + skill + " level " + level);
            return;
        }
        if (!data.skillRewardsClaimed.add(skill + ":" + level)) return;
        player.sendMessage(Text.mm("<aqua>  Reward: <white>" + rewardText(skill)));
        if (level <= 0 || level % 5 != 0) return;
        if (item == null) return;
        int amount = skill.equals("enchanting") ? (level % 25 == 0 ? 1 : 0) : (level % 10 == 0 ? 2 : 1);
        if (amount > 0) {
            plugin.items().give(player, item, amount);
            player.sendMessage(Text.mm("<gold>  Milestone reward: <white>" + amount + "x " + plugin.items().displayName(item)));
        }
    }

    private static String rewardText(String skill) {
        return switch (skill) {
            case "combat" -> "+2 Health, +2 Defense, +1 Strength per level";
            case "mining" -> "+1 Defense per level";
            case "farming" -> "+1 Health per level";
            case "fishing" -> "+0.5% Crit Chance per level";
            case "foraging" -> "+1 Strength per level";
            case "enchanting" -> "+5 Mana per level";
            default -> "No reward";
        };
    }

    private static String milestoneText(String skill) {
        String item = milestoneItem(skill, 5);
        return skill.equals("enchanting") ? "Starlight at levels 25 and 50" : "Bonus " + nice(item);
    }

    private static String milestoneItem(String skill, int level) {
        if (skill.equals("enchanting") && level % 25 != 0) return null;
        return switch (skill) {
            case "combat" -> "bone_shard";
            case "mining" -> "aether_shard";
            case "farming" -> "golden_seed";
            case "fishing" -> "sea_glass";
            case "foraging" -> "ancient_bark";
            case "enchanting" -> "starlight";
            default -> null;
        };
    }

    private static String nice(String id) { return Character.toUpperCase(id.charAt(0)) + id.substring(1); }
}
