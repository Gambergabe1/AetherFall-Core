package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent creature collection with categories, pages, milestones, and detail views. */
public final class Bestiary implements Listener, TabExecutor {
    private static final int[] ENTRY_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final List<String> CATEGORIES = List.of("ALL", "OVERWORLD", "AQUATIC", "FISHING", "NETHER", "ELITES", "BOSSES");
    private final AetherCore plugin;
    /** Entity-death delivery is idempotent even if the same event is delivered twice. */
    private final Set<UUID> countedDeaths = ConcurrentHashMap.newKeySet();

    public Bestiary(AetherCore plugin) { this.plugin = plugin; }

    public int entryCount() { return keys().size(); }

    public long discoveredCount(PlayerData data) {
        return keys().stream().filter(k -> data.bestiary.getOrDefault(k, 0) > 0).count();
    }

    public long masteredCount(PlayerData data) {
        return keys().stream().filter(k -> data.bestiary.getOrDefault(k, 0) >= completionThreshold(k)).count();
    }

    @EventHandler public void death(EntityDeathEvent event) {
        // Fishing encounters are credited by their owner-aware listener. A killer
        // must not also receive progress for another player's encounter.
        if (event.getEntity().getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey(plugin, "fishing_encounter"), org.bukkit.persistence.PersistentDataType.BYTE)) return;
        Player p = event.getEntity().getKiller();
        if (p == null || p.getGameMode() != org.bukkit.GameMode.SURVIVAL || event.getEntity() instanceof Player) return;
        recordDefeat(p, key(event.getEntity()), event.getEntity().getUniqueId());
    }

    /** Shared identity gate for normal deaths and owner-attributed encounters. */
    public void recordDefeat(Player p, String key, UUID entityId) {
        if (p == null || !p.isOnline() || p.getGameMode() != org.bukkit.GameMode.SURVIVAL
                || plugin.data().get(p) == null || entityId == null) return;
        if (countedDeaths.size() > 100_000) countedDeaths.clear();
        if (!countedDeaths.add(entityId)) return;
        recordDefeat(p, key);
    }

    /** Records a server-owned encounter defeat for its credited owner. */
    public void recordDefeat(Player p, String key) {
        if (p == null || p.getGameMode() != org.bukkit.GameMode.SURVIVAL || key == null || key.isBlank()) return;
        if (!enabled(key)) return;
        PlayerData d = plugin.data().get(p); if (d == null) return;
        int count = d.bestiary.compute(key, (id, old) -> old == null ? 1 : old == Integer.MAX_VALUE ? old : old + 1);
        for (int milestone : milestones(key)) if (count >= milestone && d.bestiaryRewardsClaimed.add(key + ":" + milestone)) {
            long reward = rewardFor(key, milestone);
            plugin.giveCoins(p, reward);
            p.sendMessage(Text.mm("<gold>Bestiary milestone:</gold> <white>" + label(key) + " " + milestone + " kills</white> <gray>(+" + reward + " coins)"));
        }
        if (count >= completionThreshold(key) && d.bestiaryRewardsClaimed.add(key + ":complete")) {
            long bonus = completionReward(key);
            if (bonus > 0) {
                plugin.giveCoins(p, bonus);
                p.sendMessage(Text.mm("<gold>Bestiary completed: <white>" + label(key) + " <gray>(+" + bonus + " coins)"));
            }
        }
        plugin.data().saveAsync(d);
        if (plugin.achievements() != null) plugin.achievements().check(p);
    }

    private String key(Entity e) {
        String elite = plugin.mobs().idOf(e);
        if (elite != null) return "elite:" + elite;
        String boss = plugin.bosses().idOf(e);
        if (boss != null) return "boss:" + boss;
        return e.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String pretty(String key) {
        return key.replace("elite:", "").replace("boss:", "").replace('_', ' ');
    }

    private String label(String key) {
        ConfigurationSection entry = definition(key);
        if (entry != null && entry.isString("name")) return entry.getString("name");
        if (key.startsWith("elite:")) {
            var elite = plugin.mobs().elite(key.substring("elite:".length()));
            if (elite != null) return elite.name();
        }
        if (key.startsWith("boss:")) {
            ConfigurationSection boss = plugin.mobs().config().getConfigurationSection("bosses." + key.substring("boss:".length()));
            if (boss != null) return boss.getString("name", pretty(key));
        }
        return pretty(key);
    }

    private List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (EntityType type : EntityType.values()) if (type.isAlive() && type.isSpawnable()) keys.add(type.name().toLowerCase(Locale.ROOT));
        plugin.mobs().eliteIds().forEach(id -> keys.add("elite:" + id));
        plugin.bosses().ids().forEach(id -> keys.add("boss:" + id));
        return keys.stream().distinct().filter(this::enabled).sorted().toList();
    }

    private String category(String key) {
        ConfigurationSection entry = definition(key);
        if (entry != null) {
            String configured = entry.getString("category", "").toUpperCase(Locale.ROOT);
            if (CATEGORIES.contains(configured) && !configured.equals("ALL")) return configured;
        }
        String clean = key.replace("elite:", "").replace("boss:", "").toLowerCase(Locale.ROOT);
        if (key.startsWith("boss:")) return "BOSSES";
        if (key.startsWith("elite:")) return isFishing(clean) ? "FISHING" : clean.contains("blaze") || clean.contains("piglin") || clean.contains("magma") || clean.contains("wither") ? "NETHER" : "ELITES";
        if (isFishing(clean)) return "FISHING";
        if (clean.contains("guardian") || clean.contains("drowned") || clean.contains("squid") || clean.contains("axolotl") || clean.contains("turtle") || clean.contains("dolphin")) return "AQUATIC";
        if (clean.contains("blaze") || clean.contains("piglin") || clean.contains("magma") || clean.contains("ghast") || clean.contains("wither") || clean.contains("hoglin") || clean.contains("strider")) return "NETHER";
        return "OVERWORLD";
    }

    private boolean isFishing(String clean) {
        return clean.contains("reef") || clean.contains("kraken") || clean.contains("deep_guardian") || clean.contains("angler") || clean.contains("sea_creature") || clean.contains("fish");
    }

    public void open(Player p) { open(p, "ALL", 0); }

    public void open(Player p, String rawCategory, int rawPage) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        String selected = CATEGORIES.stream().filter(c -> c.equalsIgnoreCase(rawCategory)).findFirst().orElse("ALL");
        if (selected.equals("ALL") && rawCategory.equalsIgnoreCase("categories")) { openCategories(p); return; }
        openCategory(p, selected, Math.max(0, rawPage));
    }

    private void openCategories(Player p) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        List<String> all = keys();
        Menu menu = new Menu(6, Text.mm("<dark_gray>Bestiary · Categories"));
        menu.set(4, new ItemBuilder(Material.BOOK).name("<gold><bold>Bestiary").lore(List.of("<gray>Discover creatures, complete milestones,", "<gray>and earn rewards for every hunt.", "", "<aqua>Discovered: <white>" + discovered(d, all) + "/" + all.size(), "<gray>Mastered: <white>" + mastered(d, all))).build());
        int[] slots = {20, 22, 24, 29, 31, 33, 40};
        for (int i = 0; i < CATEGORIES.size(); i++) {
            String category = CATEGORIES.get(i);
            List<String> entries = filtered(all, category);
            long found = entries.stream().filter(k -> d.bestiary.getOrDefault(k, 0) > 0).count();
            menu.set(slots[i], new ItemBuilder(categoryIcon(category)).name(categoryColor(category) + "<bold>" + categoryName(category)).lore(List.of("<gray>Entries: <white>" + found + "/" + entries.size(), "<gray>Completed: <white>" + mastered(d, entries), "", "<yellow>▶ Click to browse")).glow(found > 0).build(), (pl, c) -> openCategory(pl, category, 0));
        }
        menu.set(49, new ItemBuilder(Material.ARROW).name("<gray>← Back to Main Menu").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openCategory(Player p, String category, int page) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        List<String> all = keys();
        List<String> list = filtered(all, category);
        int pages = Math.max(1, (list.size() + ENTRY_SLOTS.length - 1) / ENTRY_SLOTS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        final int currentPage = page;
        Menu menu = new Menu(6, Text.mm("<dark_gray>Bestiary · " + categoryName(category) + " · " + (page + 1) + "/" + pages));
        int start = page * ENTRY_SLOTS.length;
        for (int i = 0; i < ENTRY_SLOTS.length && start + i < list.size(); i++) {
            String key = list.get(start + i);
            int kills = d.bestiary.getOrDefault(key, 0);
            boolean seen = kills > 0;
            int next = nextMilestone(key, kills);
            List<String> lore = new ArrayList<>();
            lore.add(seen ? "<green>✔ Discovered" : "<dark_gray>✖ Not discovered");
            lore.add(seen ? "<gray>Kills: <white>" + kills : "<gray>Defeat this creature to discover it.");
            lore.add("<gray>Next milestone: <yellow>" + (next > 0 ? next : "MAX") + " kills");
            lore.add("");
            lore.add("<yellow>▶ Click for details");
            menu.set(ENTRY_SLOTS[i], new ItemBuilder(seen ? icon(key) : Material.GRAY_DYE).name((seen ? "<green>" : "<dark_gray>") + (seen ? label(key) : "???")).lore(lore).glow(seen).build(), (pl, c) -> openDetail(pl, category, currentPage, key));
        }
        if (currentPage > 0) menu.set(48, new ItemBuilder(Material.ARROW).name("<yellow>← Previous Page").build(), (pl, c) -> openCategory(pl, category, currentPage - 1));
        menu.set(49, new ItemBuilder(Material.BOOK).name("<gray>← Categories").build(), (pl, c) -> openCategories(pl));
        if (currentPage + 1 < pages) menu.set(50, new ItemBuilder(Material.ARROW).name("<yellow>Next Page →").build(), (pl, c) -> openCategory(pl, category, currentPage + 1));
        menu.set(53, new ItemBuilder(Material.OAK_DOOR).name("<gray>← Main Menu").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openDetail(Player p, String category, int page, String key) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        int kills = d.bestiary.getOrDefault(key, 0);
        boolean seen = kills > 0;
        int next = nextMilestone(key, kills);
        List<String> lore = new ArrayList<>();
        lore.add(seen ? "<green>✔ Discovered" : "<dark_gray>✖ Not discovered");
        lore.add("<gray>Category: <aqua>" + categoryName(category));
        lore.add("<gray>Kills: <white>" + kills);
        lore.add("<gray>Next milestone: <yellow>" + (next > 0 ? next : "MAX") + " kills");
        lore.add("");
        for (int milestone : milestones(key)) {
            boolean claimed = d.bestiaryRewardsClaimed.contains(key + ":" + milestone);
            lore.add((claimed ? "<green>✔ " : "<gray>• ") + milestone + " kills: <gold>" + rewardFor(key, milestone) + " coins");
        }
        lore.add("<gray>Completion bonus: <gold>" + completionReward(key) + " coins");
        lore.add(d.bestiaryRewardsClaimed.contains(key + ":complete") ? "<green>✔ Entry completed" : "<gray>Complete at " + completionThreshold(key) + " kills.");
        lore.add("<gray>Rewards are granted automatically when milestones are reached.");
        ConfigurationSection entry = definition(key);
        if (entry != null && seen) for (String line : entry.getStringList("description")) lore.add("<gray>" + line);
        appendDefinitionInfo(lore, key, seen);
        appendFishingEncounterInfo(lore, key);
        Menu menu = new Menu(3, Text.mm("<dark_gray>Bestiary · " + (seen ? label(key) : "???")));
        menu.set(13, new ItemBuilder(seen ? icon(key) : Material.GRAY_DYE).name(seen ? "<green>" + label(key) : "<dark_gray>Undiscovered Creature").lore(lore).glow(seen).build());
        menu.set(18, new ItemBuilder(Material.ARROW).name("<gray>← Back to " + categoryName(category)).build(), (pl, c) -> openCategory(pl, category, page));
        menu.set(22, new ItemBuilder(Material.BOOK).name("<gray>← Categories").build(), (pl, c) -> openCategories(pl));
        menu.set(26, new ItemBuilder(Material.OAK_DOOR).name("<gray>← Main Menu").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void appendDefinitionInfo(List<String> lore, String key, boolean discovered) {
        if (!discovered) return;
        if (key.startsWith("elite:")) {
            var elite = plugin.mobs().elite(key.substring("elite:".length()));
            if (elite != null) {
                lore.add("");
                lore.add("<gray>Level: <white>" + elite.level());
                lore.add("<gray>Health: <red>" + Math.round(elite.health()) + "❤");
                lore.add("<gray>Combat XP: <aqua>" + elite.xp());
            }
        } else if (key.startsWith("boss:")) {
            ConfigurationSection boss = plugin.mobs().config().getConfigurationSection("bosses." + key.substring("boss:".length()));
            if (boss != null) {
                lore.add("");
                lore.add("<gray>Health: <red>" + Math.round(boss.getDouble("health", 0)) + "❤");
                lore.add("<gray>Damage: <red>" + Math.round(boss.getDouble("damage", 0)));
                lore.add("<gray>Boss loot is awarded from the encounter.");
            }
        }
    }

    private void appendFishingEncounterInfo(List<String> lore, String key) {
        if (!key.startsWith("elite:")) return;
        String mobId = key.substring("elite:".length());
        ConfigurationSection encounter = plugin.getConfig().getConfigurationSection("fishing.encounters." + mobId);
        if (encounter == null) return;
        lore.add("");
        lore.add("<aqua>Fishing encounter");
        lore.add("<gray>Cast a rod to summon this creature.");
        ConfigurationSection guaranteed = encounter.getConfigurationSection("guaranteed");
        if (guaranteed != null) for (String item : guaranteed.getKeys(false)) {
            if (plugin.items().isValidKey(item)) lore.add("<gray>Guaranteed: <white>" + plugin.items().displayName(item) + " ×" + guaranteed.getInt(item, 0));
        }
    }

    private List<String> filtered(List<String> all, String category) { return "ALL".equals(category) ? all : all.stream().filter(k -> category.equals(this.category(k))).toList(); }
    private long discovered(PlayerData d, List<String> all) { return all.stream().filter(k -> d.bestiary.getOrDefault(k, 0) > 0).count(); }
    private long mastered(PlayerData d, List<String> all) { return all.stream().filter(k -> d.bestiary.getOrDefault(k, 0) >= completionThreshold(k)).count(); }
    private ConfigurationSection definition(String key) { return plugin.getConfig().getConfigurationSection("bestiary.entries." + key); }
    private boolean enabled(String key) { ConfigurationSection entry = definition(key); return entry == null || entry.getBoolean("enabled", true); }
    private int[] milestones(String key) {
        ConfigurationSection entry = definition(key);
        java.util.List<Integer> configured = entry != null && entry.contains("milestones") ? entry.getIntegerList("milestones") : plugin.getConfig().getIntegerList("bestiary.milestones");
        int[] valid = configured.stream().filter(v -> v > 0).distinct().sorted().mapToInt(Integer::intValue).toArray();
        return valid.length == 0 ? new int[]{10, 50, 100, 250, 500} : valid;
    }
    private long rewardFor(String key, int milestone) {
        long fallback = switch (milestone) { case 10 -> 100L; case 50 -> 400L; case 100 -> 1000L; case 250 -> 2500L; case 500 -> 5000L; default -> 0L; };
        ConfigurationSection entry = definition(key);
        long global = plugin.getConfig().getLong("bestiary.rewards." + milestone, fallback);
        return Math.max(0L, entry == null ? global : entry.getLong("rewards." + milestone, global));
    }
    private long completionReward(String key) {
        ConfigurationSection entry = definition(key);
        long global = plugin.getConfig().getLong("bestiary.completion-coins", 0);
        return Math.max(0L, entry == null ? global : entry.getLong("completion-coins", global));
    }
    private int completionThreshold(String key) { int[] values = milestones(key); return values[values.length - 1]; }
    private int nextMilestone(String key, int kills) { for (int milestone : milestones(key)) if (kills < milestone) return milestone; return -1; }
    private Material icon(String key) { if (key.contains("zombie")) return Material.ROTTEN_FLESH; if (key.contains("skeleton")) return Material.BONE; if (key.contains("spider")) return Material.STRING; if (key.contains("creeper")) return Material.GUNPOWDER; if (key.contains("guardian")) return Material.PRISMARINE_SHARD; if (key.contains("fish") || key.contains("reef") || key.contains("kraken")) return Material.COD; if (key.contains("end")) return Material.ENDER_PEARL; if (key.startsWith("boss:")) return Material.NETHER_STAR; return Material.IRON_SWORD; }
    private static String categoryName(String category) { String lower = category.toLowerCase(Locale.ROOT); return Character.toUpperCase(lower.charAt(0)) + lower.substring(1).replace('_', ' '); }
    private static String categoryColor(String category) { return switch (category) { case "FISHING" -> "<aqua>"; case "AQUATIC" -> "<blue>"; case "NETHER" -> "<red>"; case "ELITES" -> "<light_purple>"; case "BOSSES" -> "<gold>"; case "OVERWORLD" -> "<green>"; default -> "<yellow>"; }; }
    private static Material categoryIcon(String category) { return switch (category) { case "FISHING" -> Material.FISHING_ROD; case "AQUATIC" -> Material.PRISMARINE; case "NETHER" -> Material.NETHERRACK; case "ELITES" -> Material.DIAMOND_SWORD; case "BOSSES" -> Material.NETHER_STAR; case "OVERWORLD" -> Material.GRASS_BLOCK; default -> Material.BOOK; }; }

    @Override public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
        if (!(s instanceof Player p)) return true;
        String category = a.length == 0 ? "categories" : a[0];
        int page = 0;
        if (a.length > 1) try { page = Math.max(0, Integer.parseInt(a[1]) - 1); } catch (NumberFormatException ignored) { }
        if (category.equalsIgnoreCase("categories")) openCategories(p); else open(p, category, page);
        return true;
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
        if (a.length == 1) return CATEGORIES.stream().map(String::toLowerCase).filter(v -> v.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        return List.of("1", "2", "3");
    }
}
