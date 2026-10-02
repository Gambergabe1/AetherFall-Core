package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.economy.Eco;
import gg.aetherfall.core.util.Text;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chat formatter (config: chat-format) plus the rank/staff look used by the TAB list and nametags
 * (%aether_prefix%, %aether_name_color%, %aether_rank_weight%).
 *
 * Chat line: [STAFF] [Rank] Name » message — the name has a stats hover and click-to-message,
 * "@name" pings that player, "[item]" shows the held item with its tooltip, and higher ranks may use &-colour codes.
 */
public final class ChatFormat implements Listener {
    private record Staff(String id, String permission, String badge, TextColor nameColor, int weight) {}
    private record Rank(String id, int index, String badge, TextColor nameColor, TextColor messageColor) {}

    /** What a player looks like in chat, tab and above their head. */
    public record Look(Staff staff, Rank rank, Component badges, String legacyBadges, TextColor nameColor, TextColor messageColor, int weight) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().hexCharacter('#').build();
    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_]{3,16})");
    private static final Pattern ITEM_TAG = Pattern.compile("(?i)\\[(item|i|hand)]");
    private static final Pattern BAD_CODES = Pattern.compile("(?i)&[k]"); // obfuscated text is never allowed

    private final AetherCore plugin;
    private final List<Staff> staff = new ArrayList<>();
    private final Map<String, Rank> ranks = new HashMap<>();
    private final Map<String, Look> looks = new HashMap<>(); // cache by staff+rank id
    private Rank defaultRank;
    private boolean enabled, mentions, itemTag, rankWithStaff;
    private String format;
    private int colorCodesFrom;
    private final Map<java.util.UUID, Long> lastPing = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatFormat(AetherCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public synchronized void reload() {
        staff.clear();
        ranks.clear();
        looks.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("chat-format");
        if (sec == null) sec = plugin.getConfig().createSection("chat-format");
        enabled = sec.getBoolean("enabled", true);
        mentions = sec.getBoolean("mentions", true);
        itemTag = sec.getBoolean("item-tag", true);
        rankWithStaff = sec.getBoolean("show-rank-with-staff", true);
        format = sec.getString("format", "<staff><rank><name><dark_gray> » </dark_gray><message>");

        ConfigurationSection s = sec.getConfigurationSection("staff");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection e = s.getConfigurationSection(id);
            if (e == null) continue;
            staff.add(new Staff(id, e.getString("permission", "group." + id), e.getString("badge", ""),
                    color(e.getString("name-color"), NamedTextColor.WHITE), e.getInt("weight", 100)));
        }
        List<PlaytimeTracker.Milestone> ms = plugin.playtime().milestones();
        ConfigurationSection r = sec.getConfigurationSection("ranks");
        defaultRank = rank("default", -1, r == null ? null : r.getConfigurationSection("default"), null);
        Rank prev = defaultRank;
        for (int i = 0; i < ms.size(); i++) {
            Rank rk = rank(ms.get(i).id(), i, r == null ? null : r.getConfigurationSection(ms.get(i).id()), prev);
            ranks.put(rk.id, rk);
            prev = rk;
        }
        String from = sec.getString("color-codes-from", "elder");
        colorCodesFrom = ranks.containsKey(from) ? ranks.get(from).index : Integer.MAX_VALUE;
    }

    private static Rank rank(String id, int index, ConfigurationSection e, Rank parent) {
        TextColor name = parent != null ? parent.nameColor : NamedTextColor.GRAY;
        TextColor msg = parent != null ? parent.messageColor : NamedTextColor.GRAY;
        if (e == null) return new Rank(id, index, "", name, msg);
        return new Rank(id, index, e.getString("badge", ""), color(e.getString("name-color"), name), color(e.getString("message-color"), msg));
    }

    private static TextColor color(String raw, TextColor fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        if (raw.startsWith("#")) {
            TextColor c = TextColor.fromHexString(raw);
            return c != null ? c : fallback;
        }
        NamedTextColor n = NamedTextColor.NAMES.value(raw.toLowerCase(Locale.ROOT));
        return n != null ? n : fallback;
    }

    // ── looks (shared with TAB placeholders) ──────────────────

    public Look look(Player p) {
        Staff st = null;
        for (Staff s : staff) {
            if (p.hasPermission(s.permission)) { st = s; break; }
        }
        PlayerData d = plugin.data().get(p);
        PlaytimeTracker.Milestone m = d == null ? null : plugin.playtime().current(d);
        Rank rk = m == null ? defaultRank : ranks.getOrDefault(m.id(), defaultRank);
        String key = (st == null ? "-" : st.id) + "/" + rk.id;
        Look cached;
        synchronized (this) {
            cached = looks.get(key);
            if (cached == null) {
                StringBuilder mini = new StringBuilder();
                if (st != null && !st.badge.isBlank()) mini.append(st.badge).append(' ');
                if ((st == null || rankWithStaff) && !rk.badge.isBlank()) mini.append(rk.badge).append(' ');
                Component badges = MM.deserialize(mini.toString());
                int weight = st != null ? st.weight : (rk.index + 1) * 10;
                cached = new Look(st, rk, badges, LEGACY.serialize(badges), st != null ? st.nameColor : rk.nameColor, rk.messageColor, weight);
                looks.put(key, cached);
            }
        }
        return cached;
    }

    /** "&#RRGGBB" / "&a" code for a player's name colour (TAB list + nametag). */
    public String legacyNameColor(Player p) {
        String s = LEGACY.serialize(Component.text("x", look(p).nameColor));
        return s.substring(0, s.length() - 1);
    }

    // ── chat ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled) return;
        Player p = event.getPlayer();
        Look look = look(p);
        Component msg = event.message();

        // &-colour codes for high ranks and staff.
        boolean canColor = look.staff != null || look.rank.index >= colorCodesFrom || p.hasPermission("aethercore.chat.color");
        String plain = PlainTextComponentSerializer.plainText().serialize(msg);
        if (canColor && plain.indexOf('&') >= 0) msg = LEGACY.deserialize(BAD_CODES.matcher(plain).replaceAll(""));

        if (itemTag) {
            ItemStack held = p.getInventory().getItemInMainHand().clone();
            if (held.getType() != Material.AIR) {
                Component shown = held.displayName().append(held.getAmount() > 1 ? Component.text(" x" + held.getAmount(), NamedTextColor.GRAY) : Component.empty());
                msg = msg.replaceText(TextReplacementConfig.builder().match(ITEM_TAG).once().replacement(shown).build());
            }
        }

        if (mentions) {
            msg = msg.replaceText(TextReplacementConfig.builder().match(MENTION).replacement((match, b) -> {
                Player target = Bukkit.getPlayerExact(match.group(1));
                return target == null ? b : Component.text("@" + target.getName(), NamedTextColor.YELLOW);
            }).build());
        }

        event.message(Component.text().color(look.messageColor).append(msg).build());

        String name = PlainTextComponentSerializer.plainText().serialize(p.displayName());
        Component nameComp = Component.text(name, look.nameColor)
                .hoverEvent(HoverEvent.showText(hover(p, look, name)))
                .clickEvent(ClickEvent.suggestCommand("/msg " + p.getName() + " "));
        Component staffBadge = look.staff == null || look.staff.badge.isBlank() ? Component.empty() : MM.deserialize(look.staff.badge + " ");
        Component rankBadge = (look.staff != null && !rankWithStaff) || look.rank.badge.isBlank() ? Component.empty() : MM.deserialize(look.rank.badge + " ");
        String fmt = format;
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> MM.deserialize(fmt,
                Placeholder.component("staff", staffBadge), Placeholder.component("rank", rankBadge),
                Placeholder.component("name", nameComp), Placeholder.component("message", message))));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatSent(AsyncChatEvent event) {
        if (!enabled || !mentions) return;
        Player p = event.getPlayer();
        var matcher = MENTION.matcher(PlainTextComponentSerializer.plainText().serialize(event.message()));
        Set<Player> pinged = new LinkedHashSet<>();
        while (matcher.find() && pinged.size() < 3) {
            Player target = Bukkit.getPlayerExact(matcher.group(1));
            if (target != null && target != p && event.viewers().contains(target)) pinged.add(target);
        }
        long now = System.currentTimeMillis();
        Long last = lastPing.get(p.getUniqueId());
        if (pinged.isEmpty() || (last != null && now - last < 3000)) return;
        lastPing.put(p.getUniqueId(), now);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player t : pinged) {
                if (!t.isOnline()) continue;
                t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
                t.sendActionBar(Text.mm("<yellow>{player}</yellow> <gray>mentioned you in chat", Map.of("player", p.getName())));
            }
        });
    }

    private Component hover(Player p, Look look, String name) {
        PlayerData d = plugin.data().get(p);
        List<String> lines = new ArrayList<>();
        lines.add("<name>");
        if (look.staff != null) lines.add("<gray>Staff: </gray>" + look.staff.badge);
        PlaytimeTracker.Milestone m = d == null ? null : plugin.playtime().current(d);
        lines.add("<gray>Rank: </gray>" + (m == null ? "<gray>Newcomer" : m.name()));
        if (d != null) {
            lines.add("<gray>Playtime: <yellow>" + Text.duration(d.playtime));
            lines.add("<gray>Daily streak: <gold>" + plugin.daily().effectiveStreak(d) + " days");
            lines.add("<gray>Quests done: <aqua>" + Text.number(d.questsDone));
        }
        if (Eco.available()) lines.add("<gray>Coins: <gold>" + Eco.fmt(Eco.balance(p)));
        lines.add("");
        lines.add("<yellow>Click to message");
        return MM.deserialize(String.join("\n", lines), Placeholder.component("name",
                look.badges.append(Component.text(name, look.nameColor))));
    }
}
