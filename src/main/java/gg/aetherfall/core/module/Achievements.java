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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent achievement milestones that unlock selectable player titles. */
public final class Achievements implements TabExecutor {
    private record Achievement(String id, String name, String title, String description, long reward) {}
    private final AetherCore plugin;
    private final Map<String, Achievement> all = new LinkedHashMap<>();
    public Achievements(AetherCore plugin) {
        this.plugin = plugin;
        add("first_bestiary", "First Entry", "Beast Hunter", "Discover your first creature", 100);
        add("bestiary_master", "Creature Master", "Monster Scholar", "Master 5 creatures", 750);
        add("collector", "Collector", "Curator", "Discover 25 custom items", 750);
        add("quester", "Dedicated", "Questbound", "Complete 25 daily quests", 1000);
        add("veteran", "Veteran", "Aetherfall Veteran", "Play for 24 hours", 1500);
    }
    private void add(String id, String name, String title, String desc, long reward) { all.put(id, new Achievement(id, name, title, desc, reward)); }
    public void check(Player p) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        for (Achievement a : all.values()) if (!d.achievements.contains(a.id) && unlocked(a, d)) {
            d.achievements.add(a.id); plugin.giveCoins(p, a.reward); p.sendMessage(Text.mm("<gold>★ Achievement unlocked:</gold> <white>" + a.name + "</white> <gray>Title: <aqua>" + a.title + "</aqua> (" + a.reward + " coins)")); plugin.data().saveAsync(d);
        }
    }
    private boolean unlocked(Achievement a, PlayerData d) { return switch (a.id) { case "first_bestiary" -> plugin.bestiary().discoveredCount(d) > 0; case "bestiary_master" -> plugin.bestiary().masteredCount(d) >= 5; case "collector" -> d.collections.size() >= 25; case "quester" -> d.questsDone >= 25; case "veteran" -> d.playtime >= 86400; default -> false; }; }
    public void open(Player p) {
        PlayerData d = plugin.data().get(p); if (d == null) return; check(p);
        Menu menu = new Menu(3, Text.mm("<dark_gray>Achievements")); int i = 10;
        for (Achievement a : all.values()) { boolean done = d.achievements.contains(a.id); menu.set(i++, new ItemBuilder(done ? Material.NETHER_STAR : Material.GRAY_DYE).name((done ? "<gold>" : "<gray>") + a.name).lore(List.of("<gray>" + a.description, "", done ? "<green>✔ Unlocked · Title: " + a.title : "<dark_gray>Locked", done ? "<yellow>Click to equip title" : "<gray>Reward: " + a.reward + " coins")).glow(done).build(), done ? (pl, click) -> { d.selectedTitle = a.title; plugin.data().saveAsync(d); pl.sendMessage(Text.mm("<green>Equipped title: <aqua>" + a.title)); } : null); if (i == 17) i = 19; }
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }
    public List<String> titles(PlayerData d) { return all.values().stream().filter(a -> d.achievements.contains(a.id)).map(Achievement::title).toList(); }
    @Override public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) { if (!(s instanceof Player p)) return true; if ((c == null ? l : c.getName()).equalsIgnoreCase("title") && a.length > 0) { PlayerData d = plugin.data().get(p); if (d != null && titles(d).stream().anyMatch(t -> t.equalsIgnoreCase(String.join(" ", a)))) { d.selectedTitle = String.join(" ", a); plugin.data().saveAsync(d); p.sendMessage(Text.mm("<green>Equipped title: <aqua>" + d.selectedTitle)); } else p.sendMessage(Text.mm("<red>You have not unlocked that title.")); } else open(p); return true; }
    @Override public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) { return List.of(); }
}
