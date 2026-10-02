package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 7-day login streak rewards with limited missed-day protection. */
public final class DailyRewards {
    private final AetherCore plugin;

    public DailyRewards(AetherCore plugin) {
        this.plugin = plugin;
    }

    public boolean canClaim(PlayerData d) {
        return d.lastClaimDay < plugin.today();
    }

    /** Streak the player would be on if they claimed right now. */
    public int effectiveStreak(PlayerData d) {
        return d.lastClaimDay >= plugin.today() - 1 ? d.streak : 0;
    }

    private int cycleLength() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("daily.rewards");
        return s == null ? 7 : Math.max(1, s.getKeys(false).size());
    }

    public void remind(Player player, PlayerData d) {
        if (!canClaim(d)) return;
        if (d.lastClaimDay >= 0 && d.lastClaimDay < plugin.today() - 1 && d.streak > 1 && d.streakSaves <= 0) {
            plugin.send(player, "daily-streak-lost");
        } else if (d.lastClaimDay >= 0 && d.lastClaimDay < plugin.today() - 1 && d.streak > 1 && d.streakSaves > 0) {
            player.sendMessage(Text.mm("<gray>Your current streak can be protected today. <yellow>Claim /daily</yellow> to use one save."));
        }
        plugin.send(player, "daily-available");
    }

    public void claim(Player player) {
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        if (!canClaim(d)) {
            plugin.send(player, "daily-already", Map.of("time", Text.duration(plugin.secondsUntilReset())));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        boolean missed = d.lastClaimDay >= 0 && d.lastClaimDay < plugin.today() - 1 && d.streak > 1;
        boolean protectedGap = missed && d.streakSaves > 0;
        if (protectedGap) {
            d.streakSaves--;
            plugin.send(player, "daily-streak-protected", Map.of("saves", d.streakSaves));
        }
        d.streak = (protectedGap ? d.streak : effectiveStreak(d)) + 1;
        d.bestStreak = Math.max(d.bestStreak, d.streak);
        d.lastClaimDay = plugin.today();

        int day = ((d.streak - 1) % cycleLength()) + 1;
        ConfigurationSection r = plugin.getConfig().getConfigurationSection("daily.rewards." + day);
        long coins = r == null ? 0 : r.getLong("coins");
        int weeks = (d.streak - 1) / cycleLength();
        coins += (long) weeks * plugin.getConfig().getLong("daily.streak-bonus-per-week", 0);
        coins = Math.round(coins * (1 + plugin.perks().of(d).coinBonus())); // rank bonus
        plugin.giveCoins(player, coins);
        if (r != null) plugin.runCommands(r.getStringList("commands"), player);

        int grantEvery = Math.max(1, plugin.getConfig().getInt("daily.streak-protection.grant-every-cycles", 1));
        int maxSaves = Math.max(0, plugin.getConfig().getInt("daily.streak-protection.max-saves", 2));
        if (d.streak % (cycleLength() * grantEvery) == 0 && d.streakSaves < maxSaves) {
            d.streakSaves++;
            plugin.send(player, "daily-streak-save-earned", Map.of("saves", d.streakSaves));
        }

        plugin.send(player, "daily-claimed", Map.of("day", day, "coins", Text.number(coins), "streak", d.streak));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        plugin.data().saveAsync(d);
        plugin.onboarding().complete(player, Onboarding.Step.DAILY);
    }

    public void open(Player player) {
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        int cycle = cycleLength();
        boolean claimable = canClaim(d);
        int streak = effectiveStreak(d);
        // Position within the current cycle, counting today's claim if it has been made.
        int claimedInCycle = streak == 0 ? 0 : ((streak - 1) % cycle) + 1;
        if (claimable && claimedInCycle == cycle) claimedInCycle = 0; // cycle done, a new one starts today
        int todayIndex = claimable ? claimedInCycle + 1 : claimedInCycle;

        Menu menu = new Menu(3, Text.mm("<dark_gray>Daily Rewards · Streak " + streak));
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 1; i <= Math.min(cycle, 7); i++) {
            ConfigurationSection r = plugin.getConfig().getConfigurationSection("daily.rewards." + i);
            String display = r == null ? "" : r.getString("display", "");
            List<String> lore = new ArrayList<>();
            lore.add(display);
            lore.add("");
            Material icon;
            boolean glow = false;
            if (i < todayIndex || (!claimable && i == todayIndex)) {
                icon = Material.LIME_STAINED_GLASS_PANE;
                lore.add("<green>✔ Claimed");
            } else if (claimable && i == todayIndex) {
                icon = ItemBuilder.material(r == null ? null : r.getString("icon"), Material.CHEST);
                glow = true;
                lore.add("<yellow>▶ Click to claim!");
            } else {
                icon = Material.GRAY_STAINED_GLASS_PANE;
                lore.add("<gray>Come back on day " + i);
            }
            var item = new ItemBuilder(icon).name((claimable && i == todayIndex ? "<gold><bold>" : "<white>") + "Day " + i)
                    .lore(lore).glow(glow).build();
            if (claimable && i == todayIndex) {
                menu.set(slots[i - 1], item, (p, click) -> { claim(p); open(p); });
            } else {
                menu.set(slots[i - 1], item);
            }
        }
        List<String> info = List.of(
                "<gray>Current streak: <gold>" + streak + " days",
                "<gray>Best streak: <gold>" + d.bestStreak + " days",
                "<gray>Streak saves: <gold>" + d.streakSaves,
                "",
                claimable ? "<yellow>Today's reward is waiting!" : "<gray>Next reward in <yellow>" + Text.duration(plugin.secondsUntilReset()),
                "<dark_gray>Each full week adds bonus coins.");
        menu.set(22, new ItemBuilder(Material.CLOCK).name("<gradient:#FDE68A:#F59E0B>Your Streak").lore(info).build());
        menu.set(18, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, c) -> plugin.menus().openMain(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }
}
