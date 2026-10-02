package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;

/** Rotates helpful tips in chat so new players discover features without reading a wiki. */
public final class Announcer {
    private final AetherCore plugin;
    private BukkitTask task;
    private int index;

    public Announcer(AetherCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) task.cancel();
        long interval = Math.max(30, plugin.getConfig().getLong("announcer.interval-seconds", 360)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::announce, interval, interval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void announce() {
        List<String> messages = plugin.getConfig().getStringList("announcer.messages");
        if (messages.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) return;
        String raw = messages.get(index++ % messages.size());
        Map<String, String> vars = Map.of(
                "discord", plugin.getConfig().getString("discord-url", ""),
                "website", plugin.getConfig().getString("website-url", ""));
        String filled = raw;
        for (var e : vars.entrySet()) filled = filled.replace("{" + e.getKey() + "}", e.getValue());
        Bukkit.broadcast(Text.mm(plugin.getConfig().getString("prefix", "") + filled));
    }
}
