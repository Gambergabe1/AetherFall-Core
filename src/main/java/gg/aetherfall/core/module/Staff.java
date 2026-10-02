package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Moderation basics: /report (with a staff queue + click-to-teleport), /reports, staff chat (/sc)
 * and a chat filter (blocked words + caps limiter). Staff = aethercore.staff (give it to the helper group).
 */
public final class Staff implements Listener, TabExecutor {
    private final AetherCore plugin;
    private final Map<UUID, Long> reportCooldown = new HashMap<>();
    private Pattern blocked;

    public Staff(AetherCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        List<String> words = plugin.getConfig().getStringList("chat-filter.words");
        if (words.isEmpty()) {
            blocked = null;
            return;
        }
        StringBuilder sb = new StringBuilder("(?i)\\b(");
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(words.get(i)));
        }
        blocked = Pattern.compile(sb.append(")\\w*").toString());
    }

    /** Filters a plain string (used by party chat and anywhere else we echo player text). */
    public String filter(String s) {
        return blocked == null ? s : blocked.matcher(s).replaceAll(m -> "*".repeat(m.group().length()));
    }

    private static boolean staff(CommandSender s) {
        return s.hasPermission("aethercore.staff");
    }

    private void toStaff(String mini, Map<String, ?> vars) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (staff(p)) {
                p.sendMessage(Text.mm(mini, vars));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.2f);
            }
        }
        Bukkit.getConsoleSender().sendMessage(Text.mm(mini, vars));
    }

    // ── chat filter ───────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat-filter.enabled", true) || event.getPlayer().hasPermission("aethercore.staff")) return;
        if (blocked != null) {
            event.message(event.message().replaceText(TextReplacementConfig.builder().match(blocked)
                    .replacement((m, b) -> b.content("*".repeat(m.group().length()))).build()));
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        long letters = plain.chars().filter(Character::isLetter).count();
        long upper = plain.chars().filter(Character::isUpperCase).count();
        if (letters >= 8 && upper > letters * 0.7) {
            // SHOUTING → normal case, without touching anything else.
            event.message(net.kyori.adventure.text.Component.text(plain.toLowerCase(Locale.ROOT)));
        }
    }

    // ── commands ──────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "report" -> report(sender, args);
            case "reports" -> reports(sender, args);
            case "staffchat" -> {
                if (!staff(sender)) { sender.sendMessage(Text.mm("<red>Staff only.")); return true; }
                if (args.length == 0) { sender.sendMessage(Text.mm("<gray>Usage: /sc <message>")); return true; }
                toStaff("<dark_aqua>[Staff] <aqua>{p}<dark_gray>:</dark_gray> <white>{m}", Map.of("p", sender.getName(), "m", String.join(" ", args)));
            }
            default -> { }
        }
        return true;
    }

    private void report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) return;
        if (args.length < 2) {
            p.sendMessage(Text.mm("<gray>Usage: <yellow>/report <player> <reason></yellow> — staff will be alerted right away."));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = reportCooldown.get(p.getUniqueId());
        if (last != null && now - last < 60_000) {
            p.sendMessage(Text.mm("<red>Please wait a minute between reports."));
            return;
        }
        reportCooldown.put(p.getUniqueId(), now);
        String target = args[0];
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        Player tp = Bukkit.getPlayerExact(target);
        Location at = tp != null ? tp.getLocation() : p.getLocation();
        UUID reporter = p.getUniqueId();
        String reporterName = p.getName();
        plugin.data().async(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO reports (reporter, reporter_name, target_name, reason, world, x, y, z, created) VALUES (?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, reporter.toString()); ps.setString(2, reporterName); ps.setString(3, target); ps.setString(4, reason);
                ps.setString(5, at.getWorld().getName()); ps.setDouble(6, at.getX()); ps.setDouble(7, at.getY()); ps.setDouble(8, at.getZ());
                ps.setLong(9, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { id = rs.next() ? rs.getLong(1) : -1; }
            }
            long fid = id;
            Bukkit.getScheduler().runTask(plugin, () -> {
                toStaff("<red>[Report #" + fid + "]</red> <yellow>{r}</yellow> <gray>reported</gray> <yellow>{t}</yellow><gray>: {why} "
                        + "<click:run_command:'/tp {t}'><aqua>[TP]</aqua></click> <click:run_command:'/reports resolve " + fid + "'><green>[RESOLVE]</green></click>",
                        Map.of("r", reporterName, "t", target, "why", reason));
            });
            return null;
        });
        long onlineStaff = Bukkit.getOnlinePlayers().stream().filter(Staff::staff).count();
        p.sendMessage(Text.mm("<green>Thanks — your report about <yellow>{t}</yellow> was sent to staff" + (onlineStaff > 0 ? " (" + onlineStaff + " online)." : " and will be reviewed soon."),
                Map.of("t", target)));
    }

    private void reports(CommandSender sender, String[] args) {
        if (!staff(sender)) { sender.sendMessage(Text.mm("<red>Staff only.")); return; }
        if (args.length >= 2 && args[0].equalsIgnoreCase("resolve")) {
            long id;
            try { id = Long.parseLong(args[1]); } catch (NumberFormatException e) { sender.sendMessage(Text.mm("<red>Usage: /reports resolve <id>")); return; }
            String by = sender.getName();
            plugin.data().async(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE reports SET resolved = 1, resolved_by = ? WHERE id = ?")) {
                    ps.setString(1, by); ps.setLong(2, id);
                    int n = ps.executeUpdate();
                    Bukkit.getScheduler().runTask(plugin, () -> toStaff(n > 0 ? "<green>Report #" + id + " resolved by {p}." : "<red>No report #" + id + ".", Map.of("p", by)));
                    return n;
                }
            });
            return;
        }
        plugin.data().async(c -> {
            List<String> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, reporter_name, target_name, reason, created FROM reports WHERE resolved = 0 ORDER BY id DESC LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ago = (System.currentTimeMillis() - rs.getLong(5)) / 1000;
                    String t = rs.getString(3);
                    lines.add("<red>#" + rs.getLong(1) + "</red> <yellow>" + esc(t) + "</yellow> <gray>by " + esc(rs.getString(2)) + " · " + Text.duration(ago)
                            + " ago: <white>" + esc(rs.getString(4)) + "</white> <click:run_command:'/tp " + esc(t) + "'><aqua>[TP]</aqua></click> "
                            + "<click:run_command:'/reports resolve " + rs.getLong(1) + "'><green>[RESOLVE]</green></click>");
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(Text.mm("<gold><bold>Open reports</bold></gold> <gray>(" + lines.size() + (lines.size() == 10 ? "+" : "") + ")"));
                if (lines.isEmpty()) sender.sendMessage(Text.mm("<green>No open reports. Nice!"));
                for (String l : lines) sender.sendMessage(Text.mm(l));
            });
            return null;
        });
    }

    private static String esc(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s == null ? "" : s).replace("'", "");
    }

    /** Staff see how many reports are waiting when they log in. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!staff(p)) return;
        plugin.data().async(c -> {
            long open;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM reports WHERE resolved = 0"); ResultSet rs = ps.executeQuery()) {
                open = rs.next() ? rs.getLong(1) : 0;
            }
            if (open > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.sendMessage(Text.mm("<gold>[Staff]</gold> <gray>There are <red>" + open + "</red> open reports. <click:run_command:'/reports'><yellow>[VIEW]</yellow></click>"));
            }, 60L);
            return null;
        });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("report") && args.length == 1) return null;
        if (command.getName().equalsIgnoreCase("reports") && args.length == 1) return List.of("resolve");
        return List.of();
    }
}
