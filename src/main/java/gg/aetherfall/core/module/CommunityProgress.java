package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Shared server goals funded by ordinary player activity. */
public final class CommunityProgress implements TabExecutor {
    private final AetherCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private long points;
    private int tier;
    private BukkitTask saveTask;
    public CommunityProgress(AetherCore plugin) {
        this.plugin = plugin; file = new File(plugin.getDataFolder(), "community.yml");
        data = YamlConfiguration.loadConfiguration(file); points = Math.max(0L, data.getLong("points", 0L)); tier = Math.max(0, data.getInt("tier", 0));
    }
    public void progress(Player source, int amount) {
        long increment = Math.min(1000L, Math.max(1, amount));
        points = points > Long.MAX_VALUE - increment ? Long.MAX_VALUE : points + increment;
        long perTier = Math.max(1L, plugin.getConfig().getLong("community.points-per-tier", 1000L));
        while (tier < 1000 && points >= nextTarget(perTier)) {
            tier++;
            Bukkit.broadcast(Text.mm("<gradient:#22D3EE:#8B5CF6><bold>Community milestone!</bold></gradient> <gray>Tier " + tier + " unlocked by everyone. All online players receive <gold>500 coins</gold>!"));
            for (Player p : Bukkit.getOnlinePlayers()) plugin.giveCoins(p, 500);
        }
        save();
    }
    private long nextTarget(long perTier) { return tier >= Long.MAX_VALUE / perTier - 1 ? Long.MAX_VALUE : (tier + 1L) * perTier; }
    private void save() {
        data.set("points", points); data.set("tier", tier);
        if (saveTask == null) saveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> { saveTask = null; flush(); }, 20L);
    }

    public void saveNow() {
        if (saveTask != null) { saveTask.cancel(); saveTask = null; }
        flush();
    }

    private void flush() {
        data.set("points", points); data.set("tier", tier);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            data.save(temp);
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            if (temp.isFile()) temp.delete();
            plugin.getLogger().warning("Could not save community progress: " + e.getMessage());
        }
    }
    public void open(Player p) { long target = nextTarget(Math.max(1L, plugin.getConfig().getLong("community.points-per-tier", 1000L))); p.sendMessage(Text.mm("<aqua><bold>Community Progress</bold></aqua> <gray>Tier " + tier + " · " + points + "/" + target + " points")); p.sendMessage(Text.mm("<gray>Every shared quest, contract, and server activity funds the next milestone.")); }
    @Override public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) { if (s instanceof Player p) open(p); return true; }
    @Override public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, String l, String @NotNull [] a) { return List.of(); }
}
