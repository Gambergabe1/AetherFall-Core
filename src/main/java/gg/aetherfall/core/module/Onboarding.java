package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The first-hour tutorial: eight small goals that walk a new player through every core system,
 * each paying a reward. Steps can be done in any order; a boss bar always shows the next one.
 */
public final class Onboarding implements Listener, TabExecutor {
    public enum Step {
        MENU("Open the main menu", "Type <yellow>/menu</yellow> — everything lives there", 100, null),
        DAILY("Claim your daily reward", "Type <yellow>/daily</yellow> and click today's chest", 100, null),
        CLAIM("Protect your land", "Right-click two corners with your <yellow>golden shovel</yellow>", 250, null),
        EXPLORE("Find a place to settle", "Type <yellow>/rtp</yellow> to explore somewhere new", 100, null),
        ORE("Mine a custom ore", "Find teal <aqua>Mithril</aqua> ore underground (below Y 48)", 300, "mithril"),
        FORGE("Forge your first item", "Open <yellow>/forge</yellow> — try an Enchanted material", 300, null),
        TRADE("Make your first sale", "Sell anything on the <yellow>/bazaar</yellow> or at the <yellow>/shop</yellow>", 200, null),
        QUEST("Complete a daily quest", "Check <yellow>/quests</yellow> for today's three", 400, null),
        // These are appended to preserve the bit positions of the original tutorial steps.
        SKILL("Reach your first skill level", "Mine, fight, farm, fish, or forage until a skill levels up", 250, "EXPERIENCE_BOTTLE"),
        ITEM("Collect your first custom item", "Pick up a custom item and open its collection entry", 250, "aether_shard"),
        COLLECTION("Discover your first collection", "Open <yellow>/collections</yellow> after finding custom loot", 250, "sea_glass");

        public final String title;
        public final String hint;
        public final long coins;
        public final String itemReward;

        Step(String title, String hint, long coins, String itemReward) {
            this.title = title;
            this.hint = hint;
            this.coins = coins;
            this.itemReward = itemReward;
        }
    }

    private static final int DONE_BIT = 1 << 30;
    private final AetherCore plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public Onboarding(AetherCore plugin) {
        this.plugin = plugin;
    }

    private static boolean has(PlayerData d, Step s) {
        return (d.tutorial & (1 << s.ordinal())) != 0;
    }

    private static boolean finished(PlayerData d) {
        return (d.tutorial & DONE_BIT) != 0;
    }

    private static int count(PlayerData d) {
        int n = 0;
        for (Step s : Step.values()) if (has(d, s)) n++;
        return n;
    }

    private static Step next(PlayerData d) {
        for (Step s : Step.values()) if (!has(d, s)) return s;
        return null;
    }

    /** Called by the rest of the plugin whenever a player does something the tutorial cares about. */
    public void complete(Player p, Step step) {
        PlayerData d = plugin.data().get(p);
        if (d == null || finished(d) || has(d, step)) return;
        d.tutorial |= 1 << step.ordinal();
        plugin.data().recordFunnel(p, "tutorial_" + step.name().toLowerCase(Locale.ROOT));
        if (step == Step.TRADE) plugin.data().recordFunnel(p, "first_market_trade");
        plugin.giveCoins(p, step.coins);
        if (step.itemReward != null) plugin.items().give(p, step.itemReward, 8);
        p.sendMessage(Text.mm(plugin.getConfig().getString("prefix", "") + "<green>✔ Tutorial:</green> <white>" + step.title
                + "</white> <gray>(+" + step.coins + " coins" + (step.itemReward != null ? ", 8x " + plugin.items().displayName(step.itemReward) : "") + "<gray>)"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        if (count(d) == Step.values().length) {
            d.tutorial |= DONE_BIT;
            plugin.data().recordFunnel(p, "tutorial_complete");
            plugin.giveCoins(p, 1500);
            plugin.items().give(p, "speed_talisman", 1);
            p.showTitle(Title.title(Text.mm("<gradient:#FDE68A:#F59E0B><bold>Tutorial complete!"), Text.mm("<gray>+1,500 coins and a Speed Talisman"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            Bukkit.broadcast(Text.mm(plugin.getConfig().getString("prefix", "") + "<yellow>{p}</yellow> <gray>finished the Aetherfall tutorial — welcome to the community!",
                    Map.of("p", p.getName())));
        }
        plugin.data().saveAsync(d);
        refresh(p);
    }

    public void refresh(Player p) {
        PlayerData d = plugin.data().get(p);
        BossBar bar = bars.get(p.getUniqueId());
        if (d == null || finished(d)) {
            if (bar != null) { p.hideBossBar(bar); bars.remove(p.getUniqueId()); }
            return;
        }
        Step n = next(d);
        if (n == null) return;
        int done = count(d);
        var name = Text.mm("<gold>Tutorial " + (done + 1) + "/" + Step.values().length + ":</gold> <white>" + n.title + " <dark_gray>— <gray>" + n.hint);
        if (bar == null) {
            bar = BossBar.bossBar(name, (float) done / Step.values().length, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(name);
            bar.progress((float) done / Step.values().length);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) refresh(p); }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bars.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/rtp") || cmd.startsWith("/tpr") || cmd.startsWith("/wild")) complete(event.getPlayer(), Step.EXPLORE);
    }

    // ── /tutorial ─────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) return true;
        PlayerData d = plugin.data().get(p);
        if (d == null) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("skip")) {
            d.tutorial |= DONE_BIT;
            plugin.data().saveAsync(d);
            refresh(p);
            p.sendMessage(Text.mm("<gray>Tutorial hidden. Bring it back any time with <yellow>/tutorial restart</yellow>."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("restart")) {
            d.tutorial &= ~DONE_BIT;
            plugin.data().saveAsync(d);
            refresh(p);
        }
        p.sendMessage(Text.mm("<gradient:#8B5CF6:#22D3EE><bold>Aetherfall Tutorial</bold></gradient> <gray>(" + count(d) + "/" + Step.values().length + ")"));
        for (Step s : Step.values()) {
            boolean ok = has(d, s);
            p.sendMessage(Text.mm((ok ? "<green>✔ " : "<gray>○ ") + "<white>" + s.title + "</white> <dark_gray>— " + (ok ? "<dark_gray>done" : s.hint)
                    + " <dark_gray>(" + s.coins + " coins)"));
        }
        p.sendMessage(Text.mm("<dark_gray>Finish all eight for 1,500 coins and a Speed Talisman. <gray>/tutorial skip</gray> to hide."));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        return args.length == 1 ? List.of("skip", "restart") : List.of();
    }
}
