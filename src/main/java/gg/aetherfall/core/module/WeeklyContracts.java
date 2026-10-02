package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.data.PlayerData.WeeklyState;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/** Three rotating weekly goals built from the same actions as daily quests. */
public final class WeeklyContracts {
    public record Contract(String id, String name, QuestManager.Type type, Pattern target, int amount, long coins, Material icon) {
        boolean matches(String key) { return target.matcher(key.toUpperCase(Locale.ROOT)).matches(); }
        String describe() {
            return switch (type) {
                case BREAK_BLOCK -> "Mine " + amount + " blocks";
                case PLACE_BLOCK -> "Place " + amount + " blocks";
                case HARVEST -> "Harvest " + amount + " crops";
                case KILL_MOB -> "Slay " + amount + " mobs";
                case FISH -> "Catch " + amount + " fish";
                case CRAFT -> "Craft " + amount + " items";
                case SMELT -> "Smelt " + amount + " items";
                case BREED -> "Breed " + amount + " animals";
                case ENCHANT -> "Enchant " + amount + " items";
                case WALK -> "Travel " + amount + " meters";
                case TRADE -> "Trade " + amount + " times";
            };
        }
    }

    private final AetherCore plugin;
    private final List<Contract> pool = new ArrayList<>();
    private final Random random = new Random();

    public WeeklyContracts(AetherCore plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        pool.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("weekly.pool");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) continue;
            try {
                QuestManager.Type type = QuestManager.Type.valueOf(c.getString("type", "BREAK_BLOCK").toUpperCase(Locale.ROOT));
                String target = c.getString("target", "*").toUpperCase(Locale.ROOT);
                Pattern pattern = Pattern.compile(Pattern.quote(target).replace("*", "\\E.*\\Q"));
                pool.add(new Contract(id, c.getString("name", id), type, pattern, Math.max(1, c.getInt("amount", 1)),
                        c.getLong("coins", 500), ItemBuilder.material(c.getString("icon"), Material.PAPER)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping weekly contract '" + id + "': " + ex.getMessage());
            }
        }
    }

    public long currentPeriod() {
        LocalDate date = LocalDate.ofEpochDay(plugin.today());
        return date.minusDays(date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()).toEpochDay() / 7;
    }

    public void ensure(PlayerData d) {
        long period = currentPeriod();
        boolean valid = d.weeklyPeriod == period && !d.weeklyContracts.isEmpty();
        if (valid) for (WeeklyState state : d.weeklyContracts) if (find(state.id) == null) valid = false;
        if (valid) return;
        d.weeklyPeriod = period;
        d.weeklyBonusClaimed = false;
        d.weeklyContracts.clear();
        List<Contract> choices = new ArrayList<>(pool);
        Collections.shuffle(choices, random);
        int count = Math.min(plugin.getConfig().getInt("weekly.contracts-per-week", 3), choices.size());
        for (int i = 0; i < count; i++) d.weeklyContracts.add(new WeeklyState(choices.get(i).id(), 0, false));
    }

    private Contract find(String id) { return pool.stream().filter(c -> c.id().equalsIgnoreCase(id)).findFirst().orElse(null); }

    public void progress(Player player, QuestManager.Type type, String key, int amount) {
        if (!plugin.getConfig().getBoolean("weekly.enabled", true) || amount <= 0) return;
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        ensure(d);
        for (WeeklyState state : d.weeklyContracts) {
            if (state.done) continue;
            Contract c = find(state.id);
            if (c == null || c.type() != type || !c.matches(key)) continue;
            state.progress = Math.min(c.amount(), state.progress + amount);
            if (state.progress >= c.amount()) complete(player, d, state, c);
        }
        plugin.data().saveAsync(d);
    }

    private void complete(Player player, PlayerData d, WeeklyState state, Contract c) {
        state.done = true;
        long reward = Math.round(c.coins() * (1 + plugin.perks().of(d).coinBonus()));
        plugin.giveCoins(player, reward);
        player.sendMessage(Text.mm("<gold>★ Weekly contract complete:</gold> <white>" + c.name() + " <gray>(+" + Text.number(reward) + " coins)"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
        boolean all = !d.weeklyContracts.isEmpty() && d.weeklyContracts.stream().allMatch(s -> s.done);
        if (all && !d.weeklyBonusClaimed) {
            d.weeklyBonusClaimed = true;
            long bonus = plugin.getConfig().getLong("weekly.completion-bonus", 1500);
            plugin.giveCoins(player, bonus);
            player.sendMessage(Text.mm("<gradient:#FDE68A:#F59E0B><bold>Weekly contracts complete!</bold></gradient> <gray>Bonus: <gold>+" + Text.number(bonus) + " coins"));
        }
    }

    public int completed(PlayerData d) { ensure(d); return (int) d.weeklyContracts.stream().filter(s -> s.done).count(); }

    public void open(Player player) {
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        ensure(d);
        Menu menu = new Menu(4, Text.mm("<dark_gray>Weekly Contracts · " + completed(d) + "/" + d.weeklyContracts.size()));
        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < d.weeklyContracts.size() && i < slots.length; i++) {
            WeeklyState state = d.weeklyContracts.get(i);
            Contract c = find(state.id);
            if (c == null) continue;
            double ratio = (double) state.progress / c.amount();
            List<String> lore = List.of("<gray>" + c.describe(), "", Text.progressBar(ratio, 24) + " <white>" + state.progress + "/" + c.amount(),
                    "", "<gray>Reward: <gold>" + Text.number(c.coins()) + " coins", state.done ? "<green>✔ Complete" : "<yellow>Active this week");
            menu.set(slots[i], new ItemBuilder(state.done ? Material.LIME_DYE : c.icon()).name((state.done ? "<green>" : "<aqua>") + c.name()).lore(lore).glow(state.done).build());
        }
        menu.set(31, new ItemBuilder(d.weeklyBonusClaimed ? Material.NETHER_STAR : Material.CLOCK).name("<gold><bold>Weekly Bonus")
                .lore(List.of("<gray>Complete every contract for", "<gold>" + Text.number(plugin.getConfig().getLong("weekly.completion-bonus", 1500)) + " bonus coins.", "", d.weeklyBonusClaimed ? "<green>✔ Earned this week" : "<yellow>Keep going!"))
                .glow(d.weeklyBonusClaimed).build());
        menu.set(27, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, c) -> plugin.menus().openMain(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }
}
