package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parties (/party, /pc) for playing and boss-hunting together, and a persistent friends list
 * (/friend) with join/leave alerts. Groups of friends are what keep a survival server alive.
 */
public final class Social implements Listener, TabExecutor {
    public static final int MAX_PARTY = 8;

    public static final class Party {
        public UUID leader;
        public final Set<UUID> members = new LinkedHashSet<>();
        final Map<UUID, Long> invites = new HashMap<>();

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }
    }

    private final AetherCore plugin;
    private final Map<UUID, Party> partyOf = new HashMap<>();
    /** friend lists of online players (loaded on join). */
    private final Map<UUID, Map<UUID, String>> friends = new ConcurrentHashMap<>();
    /** pending friend requests: target -> requesters. */
    private final Map<UUID, Set<UUID>> requests = new HashMap<>();

    public Social(AetherCore plugin) {
        this.plugin = plugin;
    }

    private static String prefix(String color, String label) {
        return "<dark_gray>[" + color + label + "<dark_gray>] <gray>";
    }

    private static final String P = prefix("<light_purple>", "Party");
    private static final String F = prefix("<aqua>", "Friends");

    public Party party(UUID player) {
        return partyOf.get(player);
    }

    /** Everyone in the same party (including the player), or just the player. */
    public Set<UUID> group(UUID player) {
        Party p = partyOf.get(player);
        return p == null ? Set.of(player) : p.members;
    }

    // ── commands ──────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) return true;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "party" -> party(p, args);
            case "partychat" -> partyChat(p, String.join(" ", args));
            case "friend" -> friend(p, args);
            default -> { }
        }
        return true;
    }

    private void party(Player p, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        Party party = partyOf.get(p.getUniqueId());
        switch (sub) {
            case "create" -> {
                if (party != null) { p.sendMessage(Text.mm(P + "You're already in a party.")); return; }
                partyOf.put(p.getUniqueId(), new Party(p.getUniqueId()));
                p.sendMessage(Text.mm(P + "Party created! Invite friends with <yellow>/party invite <name>"));
            }
            case "invite" -> {
                if (args.length < 2) { p.sendMessage(Text.mm(P + "Usage: /party invite <player>")); return; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null || t.equals(p)) { p.sendMessage(Text.mm(P + "<red>That player isn't online.")); return; }
                if (party == null) { party = new Party(p.getUniqueId()); partyOf.put(p.getUniqueId(), party); }
                if (!party.leader.equals(p.getUniqueId())) { p.sendMessage(Text.mm(P + "<red>Only the party leader can invite.")); return; }
                if (party.members.size() >= MAX_PARTY) { p.sendMessage(Text.mm(P + "<red>Your party is full (" + MAX_PARTY + ").")); return; }
                if (partyOf.containsKey(t.getUniqueId())) { p.sendMessage(Text.mm(P + "<red>" + t.getName() + " is already in a party.")); return; }
                party.invites.put(t.getUniqueId(), System.currentTimeMillis() + 60_000);
                p.sendMessage(Text.mm(P + "Invited <yellow>{t}</yellow>. The invite expires in 60s.", Map.of("t", t.getName())));
                t.sendMessage(Text.mm(P + "<yellow>{p}</yellow> invited you to their party! <click:run_command:'/party accept {p}'><green><bold>[ACCEPT]</bold></green></click>",
                        Map.of("p", p.getName())));
                t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
            }
            case "accept" -> {
                if (party != null) { p.sendMessage(Text.mm(P + "<red>Leave your current party first.")); return; }
                Party target = null;
                for (Party pt : new LinkedHashSet<>(partyOf.values())) {
                    Long exp = pt.invites.get(p.getUniqueId());
                    if (exp == null || exp < System.currentTimeMillis()) continue;
                    OfflinePlayer leader = Bukkit.getOfflinePlayer(pt.leader);
                    if (args.length < 2 || args[1].equalsIgnoreCase(leader.getName())) { target = pt; break; }
                }
                if (target == null) { p.sendMessage(Text.mm(P + "<red>You have no pending party invite.")); return; }
                if (target.members.size() >= MAX_PARTY) { p.sendMessage(Text.mm(P + "<red>That party is full.")); return; }
                target.invites.remove(p.getUniqueId());
                target.members.add(p.getUniqueId());
                partyOf.put(p.getUniqueId(), target);
                tell(target, P + "<yellow>{p}</yellow> joined the party!", p.getName());
            }
            case "leave" -> {
                if (party == null) { p.sendMessage(Text.mm(P + "You're not in a party.")); return; }
                leave(p.getUniqueId(), party, false);
                p.sendMessage(Text.mm(P + "You left the party."));
            }
            case "kick" -> {
                if (party == null || !party.leader.equals(p.getUniqueId()) || args.length < 2) { p.sendMessage(Text.mm(P + "Usage (leader): /party kick <player>")); return; }
                UUID kicked = null;
                for (UUID m : party.members) if (args[1].equalsIgnoreCase(Bukkit.getOfflinePlayer(m).getName())) kicked = m;
                if (kicked == null || kicked.equals(p.getUniqueId())) { p.sendMessage(Text.mm(P + "<red>That player isn't in your party.")); return; }
                leave(kicked, party, true);
            }
            case "disband" -> {
                if (party == null || !party.leader.equals(p.getUniqueId())) { p.sendMessage(Text.mm(P + "<red>Only the leader can disband the party.")); return; }
                tell(party, P + "<red>The party was disbanded.", p.getName());
                for (UUID m : party.members) partyOf.remove(m);
            }
            case "chat" -> partyChat(p, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            default -> {
                if (party == null) {
                    p.sendMessage(Text.mm(P + "You're not in a party. <yellow>/party invite <name></yellow> to start one. "
                            + "<dark_gray>Party members share boss loot eligibility and have a private chat (/pc)."));
                    return;
                }
                p.sendMessage(Text.mm(P + "<white>Members (" + party.members.size() + "/" + MAX_PARTY + "):"));
                for (UUID m : party.members) {
                    Player online = Bukkit.getPlayer(m);
                    String name = online != null ? online.getName() : Bukkit.getOfflinePlayer(m).getName();
                    p.sendMessage(Text.mm(" " + (online != null ? "<green>●" : "<dark_gray>●") + " <white>{n}</white>" + (m.equals(party.leader) ? " <gold>★ leader" : ""),
                            Map.of("n", name == null ? "?" : name)));
                }
            }
        }
    }

    private void leave(UUID who, Party party, boolean kicked) {
        party.members.remove(who);
        partyOf.remove(who);
        String name = Bukkit.getOfflinePlayer(who).getName();
        tell(party, P + "<yellow>{p}</yellow> " + (kicked ? "was removed from" : "left") + " the party.", name == null ? "?" : name);
        if (party.members.isEmpty()) return;
        if (party.leader.equals(who)) {
            UUID next = party.members.stream().filter(m -> Bukkit.getPlayer(m) != null).findFirst().orElse(party.members.iterator().next());
            party.leader = next;
            String n = Bukkit.getOfflinePlayer(next).getName();
            tell(party, P + "<yellow>{p}</yellow> is now the party leader.", n == null ? "?" : n);
        }
        if (party.members.size() == 1) {
            UUID last = party.members.iterator().next();
            partyOf.remove(last);
            Player lp = Bukkit.getPlayer(last);
            if (lp != null) lp.sendMessage(Text.mm(P + "The party was disbanded (no members left)."));
        }
    }

    private void tell(Party party, String mini, String name) {
        for (UUID m : party.members) {
            Player online = Bukkit.getPlayer(m);
            if (online != null) online.sendMessage(Text.mm(mini, Map.of("p", name, "t", name)));
        }
    }

    private void partyChat(Player p, String msg) {
        Party party = partyOf.get(p.getUniqueId());
        if (party == null) { p.sendMessage(Text.mm(P + "You're not in a party.")); return; }
        if (msg.isBlank()) { p.sendMessage(Text.mm(P + "Usage: /pc <message>")); return; }
        for (UUID m : party.members) {
            Player online = Bukkit.getPlayer(m);
            if (online != null) online.sendMessage(Text.mm("<light_purple>Party <dark_gray>»</dark_gray> <white>{p}<dark_gray>:</dark_gray> <gray>{m}",
                    Map.of("p", p.getName(), "m", plugin.staff().filter(msg))));
        }
    }

    // ── friends ───────────────────────────────────────────────

    private void friend(Player p, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        Map<UUID, String> mine = friends.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        switch (sub) {
            case "add" -> {
                if (args.length < 2) { p.sendMessage(Text.mm(F + "Usage: /friend add <player>")); return; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null || t.equals(p)) { p.sendMessage(Text.mm(F + "<red>That player isn't online.")); return; }
                if (mine.containsKey(t.getUniqueId())) { p.sendMessage(Text.mm(F + "You're already friends.")); return; }
                // If they already asked us, this accepts.
                Set<UUID> incoming = requests.getOrDefault(p.getUniqueId(), Set.of());
                if (incoming.contains(t.getUniqueId())) { accept(p, t); return; }
                requests.computeIfAbsent(t.getUniqueId(), k -> new LinkedHashSet<>()).add(p.getUniqueId());
                p.sendMessage(Text.mm(F + "Friend request sent to <yellow>{t}</yellow>.", Map.of("t", t.getName())));
                t.sendMessage(Text.mm(F + "<yellow>{p}</yellow> wants to be friends! <click:run_command:'/friend accept {p}'><green><bold>[ACCEPT]</bold></green></click>",
                        Map.of("p", p.getName())));
                t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
            }
            case "accept" -> {
                if (args.length < 2) { p.sendMessage(Text.mm(F + "Usage: /friend accept <player>")); return; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null || !requests.getOrDefault(p.getUniqueId(), Set.of()).contains(t.getUniqueId())) {
                    p.sendMessage(Text.mm(F + "<red>No friend request from that player.")); return;
                }
                accept(p, t);
            }
            case "remove" -> {
                if (args.length < 2) { p.sendMessage(Text.mm(F + "Usage: /friend remove <player>")); return; }
                UUID target = null;
                for (var e : mine.entrySet()) if (e.getValue().equalsIgnoreCase(args[1])) target = e.getKey();
                if (target == null) { p.sendMessage(Text.mm(F + "<red>They're not on your friends list.")); return; }
                mine.remove(target);
                Map<UUID, String> theirs = friends.get(target);
                if (theirs != null) theirs.remove(p.getUniqueId());
                UUID a = p.getUniqueId(), b = target;
                plugin.data().async(c -> {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM friends WHERE (owner = ? AND friend = ?) OR (owner = ? AND friend = ?)")) {
                        ps.setString(1, a.toString()); ps.setString(2, b.toString()); ps.setString(3, b.toString()); ps.setString(4, a.toString());
                        return ps.executeUpdate();
                    }
                });
                p.sendMessage(Text.mm(F + "Removed <yellow>{t}</yellow> from your friends.", Map.of("t", args[1])));
            }
            default -> {
                if (mine.isEmpty()) {
                    p.sendMessage(Text.mm(F + "No friends yet — <yellow>/friend add <name></yellow>. You'll be told when friends join."));
                    return;
                }
                p.sendMessage(Text.mm(F + "<white>Friends (" + mine.size() + "):"));
                List<String> online = new ArrayList<>(), offline = new ArrayList<>();
                for (var e : mine.entrySet()) (Bukkit.getPlayer(e.getKey()) != null ? online : offline).add(e.getValue());
                for (String n : online) p.sendMessage(Text.mm(" <green>● <white>{n} <dark_gray>online", Map.of("n", n)));
                for (String n : offline) p.sendMessage(Text.mm(" <dark_gray>● <gray>{n}", Map.of("n", n)));
            }
        }
    }

    private void accept(Player p, Player t) {
        requests.getOrDefault(p.getUniqueId(), new LinkedHashSet<>()).remove(t.getUniqueId());
        friends.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).put(t.getUniqueId(), t.getName());
        friends.computeIfAbsent(t.getUniqueId(), k -> new ConcurrentHashMap<>()).put(p.getUniqueId(), p.getName());
        UUID a = p.getUniqueId(), b = t.getUniqueId();
        String an = p.getName(), bn = t.getName();
        long now = System.currentTimeMillis();
        plugin.data().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO friends (owner, friend, friend_name, since) VALUES (?,?,?,?)")) {
                ps.setString(1, a.toString()); ps.setString(2, b.toString()); ps.setString(3, bn); ps.setLong(4, now); ps.addBatch();
                ps.setString(1, b.toString()); ps.setString(2, a.toString()); ps.setString(3, an); ps.setLong(4, now); ps.addBatch();
                return ps.executeBatch().length;
            }
        });
        p.sendMessage(Text.mm(F + "You and <yellow>{t}</yellow> are now friends!", Map.of("t", t.getName())));
        t.sendMessage(Text.mm(F + "You and <yellow>{t}</yellow> are now friends!", Map.of("t", p.getName())));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        String name = p.getName();
        plugin.data().async(c -> {
            Map<UUID, String> list = new ConcurrentHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT friend, friend_name FROM friends WHERE owner = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
            }
            // Keep friends' view of our name current (players can rename).
            try (PreparedStatement ps = c.prepareStatement("UPDATE friends SET friend_name = ? WHERE friend = ?")) {
                ps.setString(1, name); ps.setString(2, id.toString()); ps.executeUpdate();
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                friends.put(id, list);
                int online = 0;
                for (UUID f : list.keySet()) {
                    Player fp = Bukkit.getPlayer(f);
                    if (fp == null) continue;
                    online++;
                    fp.sendMessage(Text.mm(F + "<green>{p}</green> joined the game.", Map.of("p", name)));
                    fp.playSound(fp.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.8f);
                }
                if (!list.isEmpty() && p.isOnline()) p.sendMessage(Text.mm(F + "<white>" + online + "</white> of your " + list.size() + " friends are online. <dark_gray>(/friend)"));
            });
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Map<UUID, String> list = friends.remove(id);
        if (list != null) for (UUID f : list.keySet()) {
            Player fp = Bukkit.getPlayer(f);
            if (fp != null) fp.sendMessage(Text.mm(F + "<red>{p}</red> left the game.", Map.of("p", event.getPlayer().getName())));
        }
        requests.remove(id);
        Party party = partyOf.get(id);
        if (party != null && party.leader.equals(id)) {
            // Hand leadership to someone still online so invites keep working.
            party.members.stream().filter(m -> !m.equals(id) && Bukkit.getPlayer(m) != null).findFirst().ifPresent(n -> {
                party.leader = n;
                tell(party, P + "<yellow>{p}</yellow> is now the party leader.", Bukkit.getPlayer(n).getName());
            });
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && cmd.equals("party")) return filter(List.of("invite", "accept", "leave", "kick", "disband", "list", "chat", "create"), args[0]);
        if (args.length == 1 && cmd.equals("friend")) return filter(List.of("add", "accept", "remove", "list"), args[0]);
        if (args.length == 2 && (cmd.equals("party") || cmd.equals("friend"))) return null; // online names
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
