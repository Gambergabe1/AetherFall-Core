package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * %aether_playtime%, %aether_playtime_hours%, %aether_streak%, %aether_best_streak%,
 * %aether_quests_today%, %aether_quests_total%, %aether_rank%, %aether_next_rank%,
 * %aether_next_rank_time%, %aether_daily_ready%, %aether_unique_players%, %aether_balance%,
 * %aether_prefix% (staff + rank badges, &#hex), %aether_name_color%, %aether_rank_weight% (for TAB sorting)
 */
public final class Placeholders extends PlaceholderExpansion {
    private final AetherCore plugin;

    public Placeholders(AetherCore plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "aether"; }
    @Override public @NotNull String getAuthor() { return "Aetherfall"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equals("unique_players")) return Text.number(plugin.data().uniquePlayers());
        if (params.equals("voteparty_left")) return String.valueOf(plugin.votes().partyLeft());
        if (player == null) return "";
        if (params.equals("balance")) return balance(player);
        if (params.equals("prefix") || params.equals("name_color") || params.equals("rank_weight")) {
            org.bukkit.entity.Player online = player.getPlayer();
            if (online == null) return "";
            var look = plugin.chatFormat().look(online);
            return switch (params) {
                case "prefix" -> look.legacyBadges();
                case "name_color" -> plugin.chatFormat().legacyNameColor(online);
                default -> String.valueOf(look.weight());
            };
        }
        PlayerData d = plugin.data().get(player.getUniqueId());
        if (d == null) return "";
        return switch (params) {
            case "playtime" -> Text.duration(d.playtime);
            case "playtime_hours" -> String.valueOf(d.playtime / 3600);
            case "streak" -> String.valueOf(plugin.daily().effectiveStreak(d));
            case "best_streak" -> String.valueOf(d.bestStreak);
            case "quests_today" -> plugin.quests().completedToday(d) + "/" + d.quests.size();
            case "quests_total" -> String.valueOf(d.questsDone);
            case "votes" -> String.valueOf(d.votes);
            case "daily_ready" -> plugin.daily().canClaim(d) ? "yes" : "no";
            case "rank" -> {
                var m = plugin.playtime().current(d);
                yield m == null ? "Newcomer" : plain(m.name());
            }
            case "next_rank" -> {
                var m = plugin.playtime().next(d);
                yield m == null ? "MAX" : plain(m.name());
            }
            case "next_rank_time" -> {
                var m = plugin.playtime().next(d);
                yield m == null ? "-" : Text.duration(Math.max(0, m.hours() * 3600 - d.playtime));
            }
            default -> null;
        };
    }

    /** Compact balance for scoreboards: 950, 12.4k, 3.1M. */
    private String balance(OfflinePlayer player) {
        var rsp = plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp == null) return "0";
        double v = rsp.getProvider().getBalance(player);
        if (v >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", v / 1_000_000);
        if (v >= 10_000) return String.format(java.util.Locale.ROOT, "%.1fk", v / 1_000);
        return Text.number((long) v);
    }

    private static String plain(String mini) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(mini);
    }
}
