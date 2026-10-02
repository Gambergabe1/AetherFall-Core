package gg.aetherfall.core.command;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.module.PlaytimeTracker.Milestone;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class Commands implements TabExecutor {
    private final AetherCore plugin;

    public Commands(AetherCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "aether" -> { return admin(sender, args); }
            case "aegive", "aethergive", "cgive", "givecustom" -> { return aeGive(sender, args); }
            case "salvage" -> {
                if (!(sender instanceof Player player)) { plugin.send(sender, "players-only"); return true; }
                plugin.salvage().open(player);
                return true;
            }
            case "magnet" -> {
                if (!(sender instanceof Player player)) { plugin.send(sender, "players-only"); return true; }
                boolean active = plugin.abilities().toggleMagnet(player);
                player.sendMessage(Text.mm("<gray>Item magnet is now " + (active ? "<green>enabled" : "<red>disabled") + "<gray>."));
                return true;
            }
            case "top" -> { top(sender, args); return true; }
            case "playtime" -> { playtime(sender, args); return true; }
            case "claimhelp" -> { claimHelp(sender); return true; }
            case "vote" -> { plugin.votes().showSites(sender); return true; }
            default -> { }
        }
        switch (name) {
            case "map", "discord" -> {
                String url = plugin.getConfig().getString(name.equals("map") ? "map-url" : "discord-url", "");
                sender.sendMessage(Text.mm(plugin.getConfig().getString("prefix", "")
                        + (name.equals("map") ? "<gray>Live map: " : "<gray>Discord: ")
                        + "<click:open_url:'" + url + "'><aqua><u>" + url + "</u></aqua></click>"));
                return true;
            }
            default -> { }
        }
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "players-only");
            return true;
        }
        switch (name) {
            case "menu" -> plugin.menus().openMain(player);
            case "daily" -> plugin.daily().open(player);
            case "quests" -> plugin.quests().open(player);
            case "contracts" -> plugin.weekly().open(player);
            case "collections" -> plugin.collections().open(player);
            case "community" -> plugin.community().open(player);
            case "bestiary" -> plugin.bestiary().onCommand(player, null, name, args);
            case "achievements", "title" -> plugin.achievements().onCommand(player, null, name, args);
            case "minions" -> plugin.minions().onCommand(player, null, name, args);
            case "guild" -> plugin.guilds().onCommand(player, null, name, args);
            case "slayer" -> { if (args.length > 1 && args[0].equalsIgnoreCase("start")) plugin.slayer().start(player, args[1]); else plugin.slayer().open(player); }
            case "profile" -> stats(player, args);
            case "timber" -> plugin.menus().toggleTimber(player);
            case "veinminer" -> plugin.menus().toggleVein(player);
            case "forge" -> plugin.forge().open(player);
            case "shop" -> plugin.shop().open(player);
            case "bazaar" -> plugin.bazaar().open(player);
            case "auctionhouse" -> plugin.auctionMenus().openHub(player);
            case "sellall" -> plugin.shop().sellAll(player);
            default -> { return false; }
        }
        return true;
    }

    /** The player behind a sender, including `/execute as <player> run ...` (proxied senders). */
    private static Player asPlayer(CommandSender sender) {
        if (sender instanceof Player p) return p;
        if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied && proxied.getCallee() instanceof Player p) return p;
        return null;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aethercore.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Text.mm("<gray>/aether reload · info · data · retention · funnel · economy · auramigrate · skillsaudit · statdebug · enchantaudit · enchantrepair · addplaytime · resetquests · resetdaily · give <player|self> <custom-item> [amount] · items · boss · elite · worldboss · restart · buildspawn"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                plugin.send(sender, "reloaded");
            }
            case "info" -> sender.sendMessage(Text.mm("<gray>Next restart in: <white>" + (plugin.restarts().secondsUntilRestart() < 0 ? "off" : Text.duration(plugin.restarts().secondsUntilRestart()))
                    + "</white> · Vote party in: <white>" + plugin.votes().partyLeft() + "</white>\n<gray>Unique players: <white>" + Text.number(plugin.data().uniquePlayers())
                    + "</white> · Online: <white>" + Bukkit.getOnlinePlayers().size() + "</white> · Quests in pool: <white>"
                    + plugin.getConfig().getConfigurationSection("quests.pool").getKeys(false).size()));
            case "data" -> plugin.data().diagnostics(r -> {
                if (r == null) { sender.sendMessage(Text.mm("<red>Database diagnostics failed; check the server log.")); return; }
                sender.sendMessage(Text.mm("<gradient:#8B5CF6:#22D3EE><bold>AetherCore · Database Health</bold>"));
                sender.sendMessage(Text.mm("<gray>Schema: <white>" + r.schemaVersion() + "</white> · Players: <white>" + Text.number(r.players())
                        + "</white> · Unrepaired quarantine records: " + (r.quarantined() == 0 ? "<green>0" : "<red>" + r.quarantined())));
                sender.sendMessage(Text.mm("<gray>Open Bazaar orders: <white>" + r.bazaarOrders() + "</white> · Active auctions: <white>" + r.auctions()));
            });
            case "resetquests", "resetdaily" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                PlayerData d = target == null ? null : plugin.data().get(target);
                if (d == null) {
                    plugin.send(sender, "player-not-found");
                    return true;
                }
                if (args[0].equalsIgnoreCase("resetquests")) {
                    d.questDay = -1;
                    plugin.quests().ensureToday(d);
                } else {
                    d.lastClaimDay = plugin.today() - 1; // keep streak, allow another claim
                }
                sender.sendMessage(Text.mm("<green>Done."));
            }
            case "addplaytime" -> {
                Player target = args.length > 2 ? Bukkit.getPlayerExact(args[1]) : null;
                PlayerData d = target == null ? null : plugin.data().get(target);
                long minutes;
                try { minutes = args.length > 2 ? Long.parseLong(args[2]) : 0; } catch (NumberFormatException e) { minutes = 0; }
                if (d == null || minutes == 0) {
                    sender.sendMessage(Text.mm("<red>Usage: /aether addplaytime <online player> <minutes>"));
                    return true;
                }
                d.playtime = Math.max(0, d.playtime + minutes * 60);
                plugin.playtime().checkMilestones(target, d);
                plugin.data().saveAsync(d);
                sender.sendMessage(Text.mm("<green>" + target.getName() + " now has " + Text.duration(d.playtime) + " of playtime."));
            }
            case "fakevote" -> {
                if (args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /aether fakevote <player> [service]")); return true; }
                plugin.votes().onVote(args[1], args.length > 2 ? args[2] : "TestList");
                sender.sendMessage(Text.mm("<green>Simulated a vote for " + args[1] + "."));
            }
            case "voteparty" -> plugin.votes().startParty();
            case "give" -> {
                // Self-give shortcut: /aether give <item> [amount]
                if (args.length >= 2 && plugin.items().isValidKey(args[1]) && asPlayer(sender) != null && (args.length == 2 || args.length == 3)) {
                    int amt = args.length > 2 ? parseAmount(args[2], 1) : 1;
                    Player self = asPlayer(sender);
                    plugin.items().give(self, args[1], amt);
                    auditGive(sender, self, args[1], amt);
                    sender.sendMessage(Text.mm("<green>Gave " + amt + "x " + plugin.items().displayName(args[1]) + " <green>to yourself."));
                    return true;
                }
                if (args.length < 3) { sender.sendMessage(Text.mm("<red>Usage: /aether give [player|self] <item> [amount] · Or: /aegive")); return true; }
                Player target = (args[1].equalsIgnoreCase("self") || args[1].equalsIgnoreCase("@s")) ? asPlayer(sender) : Bukkit.getPlayerExact(args[1]);
                if (target == null || !plugin.items().isValidKey(args[2])) { sender.sendMessage(Text.mm("<red>Unknown player or custom item.")); return true; }
                int amount = args.length > 3 ? parseAmount(args[3], 1) : 1;
                plugin.items().give(target, args[2], amount);
                auditGive(sender, target, args[2], amount);
                sender.sendMessage(Text.mm("<green>Gave " + amount + "x " + plugin.items().displayName(args[2]) + " <green>to " + target.getName() + "."));
            }
            case "count" -> {
                Player target = args.length > 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) { sender.sendMessage(Text.mm("<red>Usage: /aether count <player> <item>")); return true; }
                sender.sendMessage(Text.mm("<gray>" + target.getName() + " has <white>" + plugin.items().count(target, args[2]) + "</white> " + args[2]));
            }
            case "orescan" -> {
                int radius = args.length > 1 ? Integer.parseInt(args[1]) : 128;
                Player who = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : asPlayer(sender);
                org.bukkit.Location at = who != null ? who.getLocation() : Bukkit.getWorlds().getFirst().getSpawnLocation();
                sender.sendMessage(Text.mm("<gray>Custom ores within " + radius + " blocks (loaded chunks): <white>" + plugin.ores().scan(at, radius)));
            }
            case "retention" -> plugin.data().retention(r -> {
                if (r == null) { sender.sendMessage(Text.mm("<red>Could not compute retention.")); return; }
                java.util.function.DoubleFunction<String> pct = v -> v < 0 ? "<dark_gray>n/a" : (v >= 0.4 ? "<green>" : v >= 0.2 ? "<yellow>" : "<red>") + Math.round(v * 100) + "%";
                for (String l : List.of(
                        "<gradient:#8B5CF6:#22D3EE><bold>Aetherfall · Growth & Loyalty",
                        "<gray>Players ever: <white>" + Text.number(r.total()) + "</white> · New today: <white>" + r.new24h() + "</white> · New this week: <white>" + r.new7d(),
                        "<gray>Active — day: <white>" + r.dau() + "</white> · week: <white>" + r.wau() + "</white> · month: <white>" + r.mau(),
                        "<gray>Retention — D1: " + pct.apply(r.d1()) + " <dark_gray>(" + r.cohort1() + ")</dark_gray> <gray>· D7: " + pct.apply(r.d7())
                                + " <dark_gray>(" + r.cohort7() + ")</dark_gray> <gray>· D30: " + pct.apply(r.d30()) + " <dark_gray>(" + r.cohort30() + ")",
                        "<gray>Average playtime per player: <white>" + String.format(java.util.Locale.ROOT, "%.1f", r.avgPlaytimeHours()) + "h",
                        "<dark_gray>Healthy targets: D1 ≥ 40%, D7 ≥ 20%, D30 ≥ 10%. Stickiness (DAU/MAU) ≥ 20%.")) sender.sendMessage(Text.mm(l));
            });
            case "funnel" -> plugin.data().funnelSummary(rows -> {
                sender.sendMessage(Text.mm("<gradient:#8B5CF6:#22D3EE><bold>Aetherfall · Player Funnel</bold>"));
                if (rows.isEmpty()) {
                    sender.sendMessage(Text.mm("<gray>No funnel events recorded yet."));
                    return;
                }
                rows.forEach((event, count) -> sender.sendMessage(Text.mm("<gray>" + event + ": <white>" + Text.number(count))));
            });
            case "economy" -> sender.sendMessage(Text.mm("<gradient:#8B5CF6:#22D3EE><bold>Aetherfall · Economy Controls</bold>\n"
                    + "<gray>Vault available: " + (gg.aetherfall.core.economy.Eco.available() ? "<green>yes" : "<red>no")
                    + "\n<gray>NPC shop entries: <white>" + plugin.shop().listedItems()
                    + "\n<gray>NPC sell multiplier: <gold>" + String.format(Locale.ROOT, "%.2f", plugin.shop().sellMultiplier())
                    + "\n<dark_gray>Change economy.npc-sell-multiplier in config.yml, then /aether reload."));
            case "auramigrate" -> {
                if (args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /aether auramigrate <online player>")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Text.mm("<red>That player must be online for a safe migration.")); return true; }
                boolean migrated = plugin.skills().migrateAuraSkills(target);
                sender.sendMessage(Text.mm(migrated ? "<green>AuraSkills data imported for " + target.getName() + "." : "<yellow>No import performed (already migrated, missing data, or no compatible skills)."));
            }
            case "skillsaudit" -> {
                if (args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /aether skillsaudit <online player>")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Text.mm("<red>That player must be online.")); return true; }
                sender.sendMessage(Text.mm("<gray>" + target.getName() + ": <white>" + plugin.skills().audit(target)));
                sender.sendMessage(Text.mm("<dark_gray>XP provenance: <white>" + plugin.skills().xpAudit()));
            }
            case "statdebug" -> {
                if (args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /aether statdebug <online player>")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Text.mm("<red>That player must be online.")); return true; }
                for (String line : plugin.skills().equipment().debugLines(target)) sender.sendMessage(Text.mm(line));
                var d = plugin.data().get(target);
                if (d != null) {
                    var total = plugin.skills().stats(target, d);
                    sender.sendMessage(Text.mm("<aqua>Final native stats: <white>Health " + Math.round(total.health()) + " · Defense " + Math.round(total.defense())
                            + " · Mana " + Math.round(total.mana()) + " · Strength " + Math.round(total.strength())
                            + " · Crit " + Math.round(total.critChance()) + "% · Crit Damage " + Math.round(total.critDamage()) + "%"));
                }
            }
            case "enchantaudit", "enchantrepair" -> {
                if (args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /aether " + args[0] + " <online player>")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Text.mm("<red>That player must be online.")); return true; }
                if (args[0].equalsIgnoreCase("enchantaudit")) {
                    sender.sendMessage(Text.mm("<gray>" + target.getName() + ": <white>" + plugin.customEnchantBooks().audit(target)));
                } else {
                    int repaired = plugin.customEnchantBooks().repair(target);
                    sender.sendMessage(Text.mm("<green>Repaired " + repaired + " enchanted item(s) for " + target.getName() + "."));
                }
            }
            case "items" -> {
                StringBuilder sb = new StringBuilder("<gray>Custom items (" + plugin.items().all().size() + "): ");
                for (var c : plugin.items().all()) sb.append(c.rarity().color).append(c.id()).append("<dark_gray>, ");
                sender.sendMessage(Text.mm(sb.toString()));
            }
            case "boss" -> {
                Player p = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : asPlayer(sender);
                if (p == null || args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /aether boss <id> [player]  · ids: " + plugin.bosses().ids())); return true; }
                sender.sendMessage(Text.mm(plugin.bosses().spawnBoss(args[1], p.getLocation()) != null ? "<green>Boss spawned." : "<red>Unknown boss."));
            }
            case "elite" -> {
                Player p = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : asPlayer(sender);
                if (p == null || args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /aether elite <id> [player]")); return true; }
                sender.sendMessage(Text.mm(plugin.mobs().spawn(args[1], p.getLocation()) != null ? "<green>Elite spawned." : "<red>Unknown elite."));
            }
            case "respawnnpcs" -> {
                String[] c = plugin.getConfig().getString("village-center", "").split(",");
                if (c.length < 4) { sender.sendMessage(Text.mm("<red>No village yet — run /aether buildspawn first.")); return true; }
                new gg.aetherfall.core.module.VillageBuilder(plugin).build(new org.bukkit.Location(Bukkit.getWorld(c[0]),
                        Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3])), sender, true);
            }
            case "worldboss" -> { plugin.bosses().startWorldBoss(); sender.sendMessage(Text.mm("<green>World boss triggered (needs an arena: /aether setarena).")); }
            case "setarena" -> {
                org.bukkit.Location l = asPlayer(sender) != null ? asPlayer(sender).getLocation() : null;
                if (args.length >= 4) l = new org.bukkit.Location(Bukkit.getWorlds().getFirst(), Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                if (l == null) { sender.sendMessage(Text.mm("<red>Usage: /aether setarena [x y z]")); return true; }
                plugin.getConfig().set("world-boss-arena", l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ());
                plugin.saveConfig();
                sender.sendMessage(Text.mm("<green>World boss arena set."));
            }
            case "restart" -> {
                long secs;
                try { secs = args.length > 1 ? Long.parseLong(args[1]) : 300; } catch (NumberFormatException e) { secs = 300; }
                plugin.restarts().restartIn(Math.max(5, secs));
                sender.sendMessage(Text.mm("<green>Restart scheduled in " + Math.max(5, secs) + " seconds."));
            }
            case "chatgame" -> sender.sendMessage(Text.mm(plugin.chatGames().launch(true)
                    ? "<green>Chat game started." : "<red>A chat game is already running."));
            case "buildspawn" -> {
                // Players build where they stand; console builds at the world spawn.
                org.bukkit.Location center = args.length >= 4
                        ? new org.bukkit.Location(sender instanceof Player p0 ? p0.getWorld() : Bukkit.getWorlds().getFirst(),
                            Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]))
                        : sender instanceof Player p
                        ? p.getLocation().getBlock().getLocation().subtract(0, 1, 0)
                        : gg.aetherfall.core.module.SpawnBuilder.groundAt(Bukkit.getWorlds().getFirst(),
                            Bukkit.getWorlds().getFirst().getSpawnLocation().getBlockX(),
                            Bukkit.getWorlds().getFirst().getSpawnLocation().getBlockZ());
                new gg.aetherfall.core.module.VillageBuilder(plugin).build(center, sender);
            }
            default -> sender.sendMessage(Text.mm("<red>Unknown subcommand."));
        }
        return true;
    }

    private boolean aeGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aethercore.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || (args.length == 1 && (args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("gui")))) {
            if (sender instanceof Player player) {
                plugin.adminItems().open(player);
                return true;
            }
            sender.sendMessage(Text.mm("<red>Console usage: /aegive <player> <item> [amount]"));
            return true;
        }

        // Single arg: /aegive <item> (give to self)
        if (args.length == 1) {
            if (plugin.items().isValidKey(args[0])) {
                Player player = asPlayer(sender);
                if (player == null) {
                    sender.sendMessage(Text.mm("<red>Specify target player from console: /aegive <player> <item> [amount]"));
                    return true;
                }
                plugin.items().give(player, args[0], 1);
                auditGive(sender, player, args[0], 1);
                sender.sendMessage(Text.mm("<green>Gave 1x " + plugin.items().displayName(args[0]) + " <green>to yourself."));
                return true;
            }
            sender.sendMessage(Text.mm("<red>Unknown custom item: " + args[0] + " · Use /aegive gui"));
            return true;
        }

        // Two args: /aegive <item> <amount> (give to self)
        if (args.length == 2 && plugin.items().isValidKey(args[0]) && asPlayer(sender) != null) {
            int amount = parseAmount(args[1], 1);
            Player player = asPlayer(sender);
            plugin.items().give(player, args[0], amount);
            auditGive(sender, player, args[0], amount);
            sender.sendMessage(Text.mm("<green>Gave " + amount + "x " + plugin.items().displayName(args[0]) + " <green>to yourself."));
            return true;
        }

        // Standard: /aegive <player|self> <item> [amount]
        Player target = (args[0].equalsIgnoreCase("self") || args[0].equalsIgnoreCase("@s")) ? asPlayer(sender) : Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.send(sender, "player-not-found");
            return true;
        }
        if (!plugin.items().isValidKey(args[1])) {
            sender.sendMessage(Text.mm("<red>Unknown custom item: " + args[1] + " · Use /aegive gui"));
            return true;
        }
        int amount = args.length > 2 ? parseAmount(args[2], 1) : 1;
        plugin.items().give(target, args[1], amount);
        auditGive(sender, target, args[1], amount);
        sender.sendMessage(Text.mm("<green>Gave " + amount + "x " + plugin.items().displayName(args[1]) + " <green>to " + target.getName() + "."));
        return true;
    }

    private static int parseAmount(String raw, int def) {
        try {
            int val = Integer.parseInt(raw);
            return Math.max(1, Math.min(2304, val));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void auditGive(CommandSender actor, Player target, String item, int amount) {
        plugin.getLogger().info("[admin-give] actor=" + actor.getName() + " target=" + target.getUniqueId()
                + " item=" + item + " amount=" + amount);
    }

    private void top(CommandSender sender, String[] args) {
        String board = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "playtime";
        String column;
        String title;
        Function<Long, String> fmt;
        switch (board) {
            case "quests" -> { column = "quests_done"; title = "Quests"; fmt = v -> Text.number(v); }
            case "streak" -> { column = "best_streak"; title = "Streaks"; fmt = v -> v + " days"; }
            case "votes" -> { column = "votes"; title = "Voters"; fmt = v -> Text.number(v) + " votes"; }
            default -> { column = "playtime"; title = "Playtime"; fmt = Text::duration; }
        }
        plugin.data().top(column, 10, list -> {
            sender.sendMessage(Text.mm(plugin.raw("top-header"), Map.of("board", title)));
            int i = 1;
            for (var e : list) {
                sender.sendMessage(Text.mm(plugin.raw("top-line"),
                        Map.of("rank", i++, "player", e.name(), "value", fmt.apply(e.value()))));
            }
        });
    }

    private void playtime(CommandSender sender, String[] args) {
        if (args.length > 0) {
            Player target = Bukkit.getPlayerExact(args[0]);
            PlayerData d = target == null ? null : plugin.data().get(target);
            if (d == null) {
                plugin.send(sender, "player-not-found");
                return;
            }
            plugin.send(sender, "playtime-other", Map.of("player", target.getName(), "time", Text.duration(d.playtime)));
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "players-only");
            return;
        }
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        Milestone next = plugin.playtime().next(d);
        String nextText = next == null ? plugin.raw("playtime-max")
                : Text.fill(plugin.raw("playtime-next"), Map.of("time", Text.duration(Math.max(0, next.hours() * 3600 - d.playtime))))
                    .replace("{rank}", next.name());
        player.sendMessage(Text.mm(plugin.getConfig().getString("prefix", "")
                + Text.fill(plugin.raw("playtime-self"), Map.of("time", Text.duration(d.playtime))).replace("{next}", nextText)));
    }

    private void stats(Player viewer, String[] args) {
        Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : viewer;
        PlayerData d = target == null ? null : plugin.data().get(target);
        if (d == null) {
            plugin.send(viewer, "player-not-found");
            return;
        }
        Milestone rank = plugin.playtime().current(d);
        List<String> lines = List.of(
                "<gradient:#8B5CF6:#22D3EE><bold>{player}'s Stats",
                "<gray>Rank: " + (rank == null ? "<white>Newcomer" : rank.name()),
                "<gray>Playtime: <yellow>" + Text.duration(d.playtime),
                "<gray>Daily streak: <gold>" + plugin.daily().effectiveStreak(d) + " <dark_gray>(best " + d.bestStreak + ")",
                "<gray>Quests completed: <aqua>" + Text.number(d.questsDone),
                "<gray>Mobs slain: <red>" + Text.number(target.getStatistic(Statistic.MOB_KILLS))
                        + " <dark_gray>·</dark_gray> <gray>Deaths: <dark_red>" + Text.number(target.getStatistic(Statistic.DEATHS)),
                "<gray>First joined: <white>" + java.time.Instant.ofEpochMilli(d.firstJoin).toString().substring(0, 10));
        for (String l : lines) viewer.sendMessage(Text.mm(l, Map.of("player", target.getName())));
    }

    private void claimHelp(CommandSender sender) {
        for (String l : List.of(
                "<gradient:#FDE68A:#F59E0B><bold>Protecting your land",
                "<yellow>1.</yellow> <gray>Get a <yellow>golden shovel</yellow> (you start with one).",
                "<yellow>2.</yellow> <gray>Right-click one corner of your base, then the opposite corner.",
                "<yellow>3.</yellow> <gray>That's it — nobody can break, build or open chests there.",
                "<gray>Trust friends: <yellow>/trust <name></yellow> · Container access only: <yellow>/containertrust <name>",
                "<gray>See claims: hold a <yellow>stick</yellow> and right-click. Remove a claim: <yellow>/abandonclaim",
                "<gray>You earn more claim blocks the longer you play.")) {
            sender.sendMessage(Text.mm(l));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> options = switch (name) {
                case "top" -> List.of("playtime", "quests", "streak", "votes");
                case "bestiary" -> List.of("categories", "all", "overworld", "aquatic", "fishing", "nether", "elites", "bosses");
            case "aether" -> sender.hasPermission("aethercore.admin") ? List.of("reload", "info", "data", "resetquests", "resetdaily", "buildspawn", "chatgame", "addplaytime", "fakevote", "voteparty", "restart", "give", "items", "boss", "elite", "worldboss", "setarena", "count", "orescan", "respawnnpcs", "retention", "funnel", "economy", "auramigrate", "skillsaudit", "statdebug", "enchantaudit", "enchantrepair") : List.of();
                case "playtime", "profile" -> null; // default: online player names
                default -> List.of();
            };
            if (options == null) return null;
            return options.stream().filter(o -> o.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        boolean isAeGive = name.equals("aegive") || name.equals("aethergive") || name.equals("cgive") || name.equals("givecustom");
        if (isAeGive) {
            if (!sender.hasPermission("aethercore.admin")) return List.of();
            if (args.length == 1) {
                List<String> list = new java.util.ArrayList<>();
                list.add("gui");
                list.add("self");
                for (Player p : Bukkit.getOnlinePlayers()) list.add(p.getName());
                for (var item : plugin.items().all()) list.add(item.id());
                return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            }
            if (args.length == 2) {
                if (plugin.items().isValidKey(args[0])) {
                    return List.of("1", "8", "16", "32", "64", "128").stream().filter(s -> s.startsWith(args[1])).toList();
                }
                return plugin.items().all().stream().map(c -> c.id()).filter(i -> i.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
            if (args.length == 3) {
                return List.of("1", "8", "16", "32", "64", "128").stream().filter(s -> s.startsWith(args[2])).toList();
            }
            return List.of();
        }
        if (args.length == 2 && name.equals("aether") && args[0].equalsIgnoreCase("give")) {
            List<String> targets = new java.util.ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            targets.add("self");
            for (var item : plugin.items().all()) targets.add(item.id());
            return targets.stream().filter(i -> i.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && name.equals("aether") && args[0].equalsIgnoreCase("give")) {
            if (plugin.items().isValidKey(args[1])) {
                return List.of("1", "8", "16", "32", "64", "128").stream().filter(s -> s.startsWith(args[2])).toList();
            }
            return plugin.items().all().stream().map(c -> c.id()).filter(i -> i.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 4 && name.equals("aether") && args[0].equalsIgnoreCase("give")) {
            return List.of("1", "8", "16", "32", "64", "128");
        }
        return List.of();
    }
}

