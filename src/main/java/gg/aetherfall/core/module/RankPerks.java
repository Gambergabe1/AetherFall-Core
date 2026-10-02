package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gameplay perks for the playtime ranks (config: rank-perks). A player gets the perks of the
 * highest rank they've reached; any value a rank doesn't set falls back to the rank below.
 */
public final class RankPerks implements TabExecutor {
    public record Perks(String rank, double coinBonus, int extraQuests, double bazaarTax, int bazaarOrders,
                        int ahListings, double ahFee, int veinMax, int timberMax, double luck,
                        String trail, String joinAnnounce, List<String> lore) {}

    private static final Object NO_DATA = new Object();
    private final AetherCore plugin;
    private final NamespacedKey trailKey;
    private final Map<String, Perks> byRank = new HashMap<>();
    private Perks base;
    private BukkitTask trailTask;

    public RankPerks(AetherCore plugin) {
        this.plugin = plugin;
        this.trailKey = new NamespacedKey(plugin, "trail_off");
        reload();
        trailTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trails, 20L, 4L);
    }

    public void stop() {
        if (trailTask != null) { trailTask.cancel(); trailTask = null; }
    }

    public void reload() {
        byRank.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("rank-perks");
        base = parse("default", sec == null ? null : sec.getConfigurationSection("default"), null);
        Perks prev = base;
        // Ranks inherit from the rank below them, in milestone order.
        for (PlaytimeTracker.Milestone m : plugin.playtime().milestones()) {
            Perks p = parse(m.id(), sec == null ? null : sec.getConfigurationSection(m.id()), prev);
            byRank.put(m.id(), p);
            prev = p;
        }
    }

    private static Perks parse(String rank, ConfigurationSection s, Perks parent) {
        Perks d = parent != null ? parent : new Perks("default", 0, 0, 0.0125, 21, 14, 0.01, 48, 160, 1.0, "", "", List.of());
        if (s == null) return new Perks(rank, d.coinBonus, d.extraQuests, d.bazaarTax, d.bazaarOrders, d.ahListings, d.ahFee,
                d.veinMax, d.timberMax, d.luck, d.trail, d.joinAnnounce, List.of());
        return new Perks(rank,
                s.getDouble("coin-bonus", d.coinBonus), s.getInt("extra-quests", d.extraQuests),
                s.getDouble("bazaar-tax", d.bazaarTax), s.getInt("bazaar-orders", d.bazaarOrders),
                s.getInt("ah-listings", d.ahListings), s.getDouble("ah-fee", d.ahFee),
                s.getInt("vein-max", d.veinMax), s.getInt("timber-max", d.timberMax), s.getDouble("luck", d.luck),
                s.getString("trail", d.trail), s.getString("join-announce", d.joinAnnounce), s.getStringList("lore"));
    }

    public Perks of(PlayerData d) {
        if (d == null) return base;
        PlaytimeTracker.Milestone m = plugin.playtime().current(d);
        return m == null ? base : byRank.getOrDefault(m.id(), base);
    }

    public Perks of(Player p) {
        return of(plugin.data().get(p));
    }

    public Perks ofRank(String rankId) {
        return byRank.getOrDefault(rankId, base);
    }

    // ── particle trails (Elder+) ──────────────────────────────

    private void trails() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Perks perks = of(p);
            if (perks.trail.isBlank() || p.isSneaking() || p.isInvisible() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getPersistentDataContainer().has(trailKey)) continue;
            if (p.getVelocity().setY(0).lengthSquared() < 0.003 && p.isOnGround()) continue; // only while moving
            Particle particle;
            try {
                particle = Particle.valueOf(perks.trail.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            // Some particles need extra data (dragon_breath takes a Float); skip any we can't satisfy.
            Class<?> type = particle.getDataType();
            Object data = type == Void.class ? null : type == Float.class ? 1.0f : NO_DATA;
            if (data == NO_DATA) continue;
            p.getWorld().spawnParticle(particle, p.getLocation().add(0, 0.1, 0), 2, 0.15, 0.05, 0.15, 0.0, data);
        }
    }

    // ── /trail and /perks ─────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) return true;
        if (command.getName().equalsIgnoreCase("trail")) {
            if (of(p).trail.isBlank()) {
                p.sendMessage(Text.mm("<gray>Particle trails unlock at <light_purple>Elder</light_purple> rank (72h of play). <dark_gray>/perks"));
                return true;
            }
            var pdc = p.getPersistentDataContainer();
            if (pdc.has(trailKey)) pdc.remove(trailKey);
            else pdc.set(trailKey, PersistentDataType.BYTE, (byte) 1);
            p.sendMessage(Text.mm("<gray>Your particle trail is now " + (pdc.has(trailKey) ? "<red>off" : "<green>on") + "<gray>."));
            return true;
        }
        plugin.menus().openRanks(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        return new ArrayList<>();
    }
}
