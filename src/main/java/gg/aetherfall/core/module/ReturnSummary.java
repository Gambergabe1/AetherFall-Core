package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;

/** Concise return-player dashboard shown after the normal join flow settles. */
public final class ReturnSummary implements Listener {
    private final AetherCore plugin;
    public ReturnSummary(AetherCore plugin) { this.plugin = plugin; }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            PlayerData d = plugin.data().get(p); if (d == null) return;
            if (plugin.achievements() != null) plugin.achievements().check(p);
            var rank = plugin.playtime().current(d); var next = plugin.playtime().next(d);
            String rankName = rank == null ? "Newcomer" : rank.name();
            String nextLine = next == null ? "<gray>Maximum rank reached" : "<gray>Next rank: <yellow>" + next.name() + "</yellow> in <yellow>" + Text.duration(Math.max(0, next.hours() * 3600 - d.playtime)) + "</yellow>";
            boolean daily = plugin.daily().canClaim(d);
            int weeklyDone = plugin.weekly().completed(d);
            long worldBossSeconds = plugin.bosses().secondsUntilWorldBoss();
            p.sendMessage(Text.mm("<dark_gray>━━━━━━━━ <aqua><bold>Welcome back to Aetherfall</bold></aqua> <dark_gray>━━━━━━━━"));
            p.sendMessage(Text.mm("<gray>Rank: <gold>" + rankName + "</gold>  ·  " + nextLine));
            p.sendMessage(Text.mm(daily ? "<gold>✦ Daily reward ready: <yellow>/daily" : "<gray>Daily reward claimed · Next reset in <yellow>" + Text.duration(plugin.secondsUntilReset())));
            p.sendMessage(Text.mm("<aqua>Weekly contracts: <white>" + weeklyDone + "/" + d.weeklyContracts.size() + "</white> <gray>· <yellow>/contracts"));
            p.sendMessage(Text.mm(worldBossSeconds > 0
                    ? "<red>World Boss: <gray>next spawn in <yellow>" + Text.duration(worldBossSeconds)
                    : "<red>World Boss: <gray>check <yellow>/community</yellow> for the next event"));
            p.sendMessage(Text.mm("<light_purple>Community: <gray>shared milestones are advancing · <yellow>/community"));
            p.sendMessage(Text.mm("<gray>Rank kits and claimable rewards: <yellow>/kits</yellow>  ·  Discord: <click:open_url:'" + plugin.getConfig().getString("discord-url", "") + "'><aqua><u>join us</u></aqua></click>"));
            p.sendMessage(Text.mm("<dark_gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        }, 80L);
    }
}
