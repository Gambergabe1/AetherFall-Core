package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs configured weekly server events once at their scheduled local time. */
public final class WeeklyEvents {
    private final AetherCore plugin;
    private BukkitTask task;
    private final Set<String> fired = new HashSet<>();

    public WeeklyEvents(AetherCore plugin) { this.plugin = plugin; }

    public void start() {
        stop();
        fired.clear();
        if (!plugin.getConfig().getBoolean("events.enabled", true)) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("events.enabled", true)) return;
        ZoneId zone;
        try { zone = ZoneId.of(plugin.getConfig().getString("timezone", "UTC")); }
        catch (Exception ex) { zone = ZoneId.of("UTC"); }
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<Map<?, ?>> events = plugin.getConfig().getMapList("events.weekly");
        for (Map<?, ?> event : events) {
            String id = String.valueOf(event.containsKey("id") ? event.get("id") : "event");
            DayOfWeek day;
            LocalTime time;
            try {
                day = DayOfWeek.valueOf(String.valueOf(event.containsKey("day") ? event.get("day") : "SATURDAY").toUpperCase(Locale.ROOT));
                time = LocalTime.parse(String.valueOf(event.containsKey("time") ? event.get("time") : "20:00"));
            } catch (Exception ex) {
                plugin.getLogger().warning("Invalid weekly event '" + id + "': " + ex.getMessage());
                continue;
            }
            if (now.getDayOfWeek() != day || now.toLocalTime().isBefore(time)) continue;
            String key = id + "@" + now.toLocalDate();
            if (!fired.add(key)) continue;
            String action = String.valueOf(event.containsKey("action") ? event.get("action") : "BROADCAST").toUpperCase(Locale.ROOT);
            String message = String.valueOf(event.containsKey("message") ? event.get("message") : "<gold>Weekly event: " + id + "</gold>");
            if ("WORLD_BOSS".equals(action)) plugin.bosses().startWorldBoss();
            Bukkit.broadcast(Text.mm(message));
        }
    }
}
