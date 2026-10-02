package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Counts active playtime (AFK excluded) and grants rank milestones automatically. */
public final class PlaytimeTracker implements Listener {
    public record Milestone(String id, long hours, String name, List<String> commands, long coins) {}

    private final AetherCore plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final List<Milestone> milestones = new ArrayList<>();
    private BukkitTask task;
    private long lastTick;

    public PlaytimeTracker(AetherCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        milestones.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("playtime.milestones")) {
            Object cmds = m.get("commands");
            List<String> commands = new ArrayList<>();
            if (cmds instanceof List<?> l) for (Object o : l) commands.add(String.valueOf(o));
            milestones.add(new Milestone(String.valueOf(m.get("id")), toLong(m.get("hours")),
                    String.valueOf(m.get("name")), commands, toLong(m.get("coins"))));
        }
        milestones.sort(Comparator.comparingLong(Milestone::hours));
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    public void start() {
        if (task != null) task.cancel();
        lastTick = System.currentTimeMillis();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1200L, 1200L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        lastActive.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, Math.min(120, (now - lastTick) / 1000)); // cap in case of lag spikes
        lastTick = now;
        long afkMillis = plugin.getConfig().getLong("playtime.afk-seconds", 300) * 1000;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().get(player);
            if (d == null) continue;
            if (now - lastActive.getOrDefault(player.getUniqueId(), 0L) > afkMillis) continue;
            d.playtime += elapsed;
            checkMilestones(player, d);
        }
    }

    public void checkMilestones(Player player, PlayerData d) {
        for (Milestone m : milestones) {
            if (d.milestones.contains(m.id) || d.playtime < m.hours * 3600) continue;
            d.milestones.add(m.id);
            plugin.runCommands(m.commands, player);
            plugin.giveCoins(player, m.coins);
            // Rank names are admin-authored MiniMessage, so insert them unescaped.
            Bukkit.broadcast(Text.mm(plugin.getConfig().getString("prefix", "")
                    + Text.fill(plugin.raw("milestone-reached"), Map.of("player", player.getName(), "hours", m.hours))
                    .replace("{rank}", m.name)));
            player.sendMessage(Text.mm(plugin.getConfig().getString("prefix", "")
                    + Text.fill(plugin.raw("milestone-self"), Map.of("coins", Text.number(m.coins))).replace("{rank}", m.name)));
            for (String line : plugin.perks().ofRank(m.id).lore()) player.sendMessage(Text.mm("  <dark_gray>▸ " + line));
            player.showTitle(Title.title(Text.mm("<gold><bold>RANK UP!"), Text.mm(m.name),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            plugin.data().saveAsync(d);
        }
    }

    /** The next milestone the player hasn't reached, or null at max rank. */
    public Milestone next(PlayerData d) {
        for (Milestone m : milestones) if (!d.milestones.contains(m.id)) return m;
        return null;
    }

    public Milestone current(PlayerData d) {
        Milestone best = null;
        for (Milestone m : milestones) if (d.milestones.contains(m.id)) best = m;
        return best;
    }

    public List<Milestone> milestones() {
        return milestones;
    }

    public void markActive(Player player) {
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void forget(Player player) {
        lastActive.remove(player.getUniqueId());
    }

    public boolean isAfk(Player player) {
        long afkMillis = plugin.getConfig().getLong("playtime.afk-seconds", 300) * 1000;
        return System.currentTimeMillis() - lastActive.getOrDefault(player.getUniqueId(), 0L) > afkMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only head rotation or real walking counts; water currents/pistons alone don't reset the AFK timer.
        var from = event.getFrom();
        var to = event.getTo();
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        markActive(event.getPlayer());
    }
}
