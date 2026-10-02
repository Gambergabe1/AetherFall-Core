package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.data.PlayerData.QuestState;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

/** Rotating daily quests: each player gets a random set every day from a configurable pool. */
public final class QuestManager implements Listener {
    public enum Type { BREAK_BLOCK, PLACE_BLOCK, HARVEST, KILL_MOB, FISH, CRAFT, SMELT, BREED, ENCHANT, WALK, TRADE }

    public record QuestDef(String id, String name, Type type, List<Pattern> targets, int amount, long coins, Material icon) {
        boolean matches(String key) {
            for (Pattern p : targets) if (p.matcher(key).matches()) return true;
            return false;
        }

        String describe() {
            String verb = switch (type) {
                case BREAK_BLOCK -> "Mine";
                case PLACE_BLOCK -> "Place";
                case HARVEST -> "Harvest";
                case KILL_MOB -> "Slay";
                case FISH -> "Catch";
                case CRAFT -> "Craft";
                case SMELT -> "Smelt";
                case BREED -> "Breed";
                case ENCHANT -> "Enchant";
                case WALK -> "Travel";
                case TRADE -> "Trade with villagers";
            };
            String unit = switch (type) {
                case WALK -> " meters";
                case KILL_MOB, BREED -> " mobs";
                case FISH -> " fish";
                case ENCHANT -> " items";
                case TRADE -> " times";
                default -> "";
            };
            return verb + " " + amount + unit;
        }
    }

    private final AetherCore plugin;
    private final Map<String, QuestDef> pool = new LinkedHashMap<>();
    private final Map<UUID, Integer> walkBuffer = new HashMap<>();
    private final Random random = new Random();

    public QuestManager(AetherCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        pool.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("quests.pool");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection q = sec.getConfigurationSection(id);
            if (q == null) continue;
            try {
                Type type = Type.valueOf(q.getString("type", "").toUpperCase(Locale.ROOT));
                List<Pattern> targets = new ArrayList<>();
                for (String t : q.getStringList("targets")) {
                    targets.add(Pattern.compile(Pattern.quote(t.toUpperCase(Locale.ROOT)).replace("*", "\\E.*\\Q")));
                }
                if (targets.isEmpty()) targets.add(Pattern.compile(".*"));
                pool.put(id, new QuestDef(id, q.getString("name", id), type, targets,
                        Math.max(1, q.getInt("amount", 1)), q.getLong("coins"),
                        ItemBuilder.material(q.getString("icon"), Material.PAPER)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping quest '" + id + "': " + e.getMessage());
            }
        }
    }

    public QuestDef def(String id) {
        return pool.get(id);
    }

    /** Rolls new quests if the stored set is from a previous day (or references removed quests). */
    public void ensureToday(PlayerData d) {
        long today = plugin.today();
        boolean valid = d.questDay == today && !d.quests.isEmpty();
        if (valid) for (QuestState q : d.quests) if (!pool.containsKey(q.id)) valid = false;
        if (valid) return;
        d.questDay = today;
        d.questBonusClaimed = false;
        d.quests.clear();
        List<String> ids = new ArrayList<>(pool.keySet());
        Collections.shuffle(ids, random);
        int n = Math.min(plugin.getConfig().getInt("quests.per-day", 3) + plugin.perks().of(d).extraQuests(), ids.size());
        for (int i = 0; i < n; i++) d.quests.add(new QuestState(ids.get(i), 0, false));
    }

    public int completedToday(PlayerData d) {
        int c = 0;
        for (QuestState q : d.quests) if (q.done) c++;
        return c;
    }

    public void progress(Player player, Type type, String key, int amount) {
        if (amount <= 0) return;
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        ensureToday(d);
        if (plugin.weekly() != null) plugin.weekly().progress(player, type, key, amount);
        if (plugin.community() != null) plugin.community().progress(player, Math.max(1, amount / 16));
        String k = key.toUpperCase(Locale.ROOT);
        for (QuestState q : d.quests) {
            if (q.done) continue;
            QuestDef def = pool.get(q.id);
            if (def == null || def.type != type || !def.matches(k)) continue;
            q.progress = Math.min(def.amount, q.progress + amount);
            if (q.progress >= def.amount) {
                complete(player, d, q, def);
            } else {
                player.sendActionBar(Text.mm(Text.fill(plugin.raw("quest-progress"),
                        Map.of("quest", def.name, "progress", q.progress, "amount", def.amount))
                        + "  " + Text.progressBar((double) q.progress / def.amount, 20)));
            }
        }
    }

    private void complete(Player player, PlayerData d, QuestState q, QuestDef def) {
        q.done = true;
        d.questsDone++;
        long coins = Math.round(def.coins * (1 + plugin.perks().of(d).coinBonus()));
        plugin.giveCoins(player, coins);
        plugin.send(player, "quest-complete", Map.of("quest", def.name, "coins", Text.number(coins)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
        plugin.onboarding().complete(player, Onboarding.Step.QUEST);
        if (!d.questBonusClaimed && completedToday(d) == d.quests.size()) {
            d.questBonusClaimed = true;
            long bonus = plugin.getConfig().getLong("quests.all-complete-bonus", 0);
            plugin.giveCoins(player, bonus);
            plugin.send(player, "quest-all-complete", Map.of("coins", Text.number(bonus)));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
        }
        plugin.data().saveAsync(d);
    }

    // ── GUI ───────────────────────────────────────────────────

    public void open(Player player) {
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        ensureToday(d);
        Menu menu = new Menu(3, Text.mm("<dark_gray>Daily Quests · " + completedToday(d) + "/" + d.quests.size()));
        int[] slots = d.quests.size() <= 3 ? new int[]{11, 13, 15} : new int[]{10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < d.quests.size() && i < slots.length; i++) {
            QuestState q = d.quests.get(i);
            QuestDef def = pool.get(q.id);
            if (def == null) continue;
            double ratio = (double) q.progress / def.amount;
            List<String> lore = List.of(
                    "<gray>" + def.describe(),
                    "",
                    Text.progressBar(ratio, 24) + " <white>" + q.progress + "/" + def.amount,
                    "",
                    "<gray>Reward: <gold>" + Text.number(Math.round(def.coins * (1 + plugin.perks().of(d).coinBonus()))) + " coins",
                    q.done ? "<green>✔ Completed" : "<yellow>In progress…");
            menu.set(slots[i], new ItemBuilder(q.done ? Material.LIME_DYE : def.icon)
                    .name((q.done ? "<green>" : "<yellow>") + def.name).lore(lore).glow(q.done).build());
        }
        long bonus = plugin.getConfig().getLong("quests.all-complete-bonus", 0);
        menu.set(22, new ItemBuilder(d.questBonusClaimed ? Material.NETHER_STAR : Material.CLOCK)
                .name("<gradient:#FDE68A:#F59E0B>Daily Bonus").lore(List.of(
                        "<gray>Finish every quest for <gold>" + Text.number(bonus) + " bonus coins<gray>.",
                        d.questBonusClaimed ? "<green>✔ Earned today" : "",
                        "<gray>New quests in <yellow>" + Text.duration(plugin.secondsUntilReset()),
                        "<gray>Lifetime quests: <white>" + Text.number(d.questsDone)))
                .glow(d.questBonusClaimed).build());
        menu.set(18, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, c) -> plugin.menus().openMain(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    // ── tracking ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        boolean placed = plugin.placedBlocks().consume(block);
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() == crop.getMaximumAge()
                && !block.getType().name().endsWith("_STEM")) {
            progress(player, Type.HARVEST, block.getType().name(), 1);
        }
        if (!placed) progress(player, Type.BREAK_BLOCK, block.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        progress(event.getPlayer(), Type.PLACE_BLOCK, event.getBlockPlaced().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || entity instanceof Player) return;
        if (entity.fromMobSpawner() && !plugin.getConfig().getBoolean("quests.count-spawner-kills")) return;
        progress(killer, Type.KILL_MOB, entity.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) return;
        progress(event.getPlayer(), Type.FISH, item.getItemStack().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getRecipe().getResult();
        int crafts = 1;
        if (event.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack s : event.getInventory().getMatrix()) {
                if (s != null && !s.getType().isAir()) min = Math.min(min, s.getAmount());
            }
            crafts = min == Integer.MAX_VALUE ? 1 : min;
        }
        progress(player, Type.CRAFT, result.getType().name(), crafts * result.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent event) {
        progress(event.getPlayer(), Type.SMELT, event.getItemType().name(), event.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) progress(player, Type.BREED, event.getEntity().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        progress(event.getEnchanter(), Type.ENCHANT, event.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        progress(event.getPlayer(), Type.TRADE, event.getTrade().getResult().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWalk(PlayerStatisticIncrementEvent event) {
        Statistic s = event.getStatistic();
        if (s != Statistic.WALK_ONE_CM && s != Statistic.SPRINT_ONE_CM) return;
        UUID id = event.getPlayer().getUniqueId();
        int cm = walkBuffer.getOrDefault(id, 0) + (event.getNewValue() - event.getPreviousValue());
        if (cm >= 1000) { // flush every 10 meters to keep this cheap
            progress(event.getPlayer(), Type.WALK, "WALK", cm / 100);
            cm %= 100;
        }
        walkBuffer.put(id, cm);
    }

    public void forget(UUID id) {
        walkBuffer.remove(id);
    }
}
