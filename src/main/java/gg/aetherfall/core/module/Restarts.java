package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daily scheduled restarts with countdown warnings. start.bat/start.sh bring the
 * server back up (and take a backup while it is offline).
 */
public final class Restarts {
    private final AetherCore plugin;
    private BukkitTask task;
    private ZonedDateTime next;
    private final Set<Long> warned = new HashSet<>();

    public Restarts(AetherCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) task.cancel();
        next = null;
        warned.clear();
        if (!plugin.getConfig().getBoolean("restarts.enabled", true)) return;
        next = nextScheduled();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        next = null;
        warned.clear();
    }

    /** Staff-triggered restart in N seconds (overrides the schedule). */
    public void restartIn(long seconds) {
        next = ZonedDateTime.now(zone()).plusSeconds(seconds);
        warned.clear();
        if (task == null || task.isCancelled()) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public long secondsUntilRestart() {
        return next == null ? -1 : Duration.between(ZonedDateTime.now(zone()), next).getSeconds();
    }

    private ZoneId zone() {
        try { return ZoneId.of(plugin.getConfig().getString("timezone", "UTC")); } catch (Exception e) { return ZoneId.of("UTC"); }
    }

    private ZonedDateTime nextScheduled() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        ZonedDateTime best = null;
        for (String t : plugin.getConfig().getStringList("restarts.times")) {
            try {
                LocalTime time = LocalTime.parse(t);
                ZonedDateTime cand = now.toLocalDate().atTime(time).atZone(zone());
                if (!cand.isAfter(now.plusMinutes(1))) cand = cand.plusDays(1);
                if (best == null || cand.isBefore(best)) best = cand;
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid restart time '" + t + "' (use HH:mm)");
            }
        }
        return best;
    }

    private void tick() {
        if (next == null) return;
        long left = secondsUntilRestart();
        List<Long> warnings = plugin.getConfig().getLongList("restarts.warnings");
        if (warnings.isEmpty()) warnings = List.of(900L, 600L, 300L, 60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L);
        for (long w : warnings) {
            if (left <= w && left > w - 1 && warned.add(w)) warn(w);
        }
        if (left <= 0) {
            next = null;
            Bukkit.broadcast(Text.mm(plugin.getConfig().getString("prefix", "") + "<red>Restarting now — see you in a minute!"));
            plugin.data().saveAllAsync();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.kick(Text.mm("<gradient:#8B5CF6:#22D3EE><bold>Aetherfall is restarting</bold></gradient>\n\n<gray>We'll be back in about a minute. Rejoin soon!"));
                }
                Bukkit.shutdown();
            }, 40L);
        }
    }

    private void warn(long seconds) {
        String when = seconds >= 60 ? (seconds / 60) + " minute" + (seconds >= 120 ? "s" : "") : seconds + " second" + (seconds > 1 ? "s" : "");
        Bukkit.broadcast(Text.mm(plugin.getConfig().getString("prefix", "") + "<yellow>Server restart in <gold>" + when + "</gold>."));
        if (seconds <= 10) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Text.mm("<red><bold>" + seconds), Text.mm("<gray>Server restarting"),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            }
        }
    }
}
