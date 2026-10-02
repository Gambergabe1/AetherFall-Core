package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.DataManager.TopEntry;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.module.PlaytimeTracker.Milestone;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The /menu hub: one place to reach every feature, so new players never feel lost. */
public final class Menus {
    private final AetherCore plugin;

    public Menus(AetherCore plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player player) {
        openMain(player, false);
    }

    /** Opens the decluttered hub; classic is retained for Bedrock fallback/testing. */
    public void openMain(Player player, boolean classic) {
        if (classic) {
            openMainLegacy(player, true);
            return;
        }
        if (plugin.bedrock() != null && plugin.bedrock().isBedrock(player)) {
            plugin.onboarding().complete(player, Onboarding.Step.MENU);
            plugin.bedrock().openMain(player);
            return;
        }
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        plugin.quests().ensureToday(d);
        plugin.onboarding().complete(player, Onboarding.Step.MENU);
        var cfg = plugin.getConfig();
        Menu menu = new Menu(5, Text.mm("<dark_gray>" + stripTags(cfg.getString("server-name", "Menu")) + " · Menu"));
        menu.set(4, profileHead(player, d));

        tile(menu, 10, Material.ENCHANTED_BOOK, "<aqua><bold>Progression", "Daily play and long-term goals", (p, c) -> openProgression(p));
        tile(menu, 11, Material.GOLD_INGOT, "<gold><bold>Economy", "Shop, trading and crafting", (p, c) -> openEconomy(p));
        tile(menu, 12, Material.PLAYER_HEAD, "<light_purple><bold>Social", "Guilds, community and rankings", (p, c) -> openSocial(p));
        tile(menu, 13, Material.COMPASS, "<green><bold>World", "Travel and land utilities", (p, c) -> openWorld(p));
        tile(menu, 14, Material.NAME_TAG, "<yellow><bold>Rewards", "Ranks, kits and earned perks", (p, c) -> openRewards(p));
        tile(menu, 15, Material.REDSTONE, "<red><bold>Settings", "Toggle gameplay preferences", (p, c) -> openSettings(p));

        menu.set(19, new ItemBuilder(plugin.daily().canClaim(d) ? Material.CHEST : Material.ENDER_CHEST)
                .name("<gold><bold>Daily Reward")
                .lore(List.of(plugin.daily().canClaim(d) ? "<yellow>Ready to claim" : "<gray>Next reset in <yellow>" + Text.duration(plugin.secondsUntilReset()), "", "<yellow>▶ Click to open"))
                .glow(plugin.daily().canClaim(d)).build(), (p, c) -> plugin.daily().open(p));
        int done = plugin.quests().completedToday(d);
        menu.set(20, new ItemBuilder(Material.WRITABLE_BOOK).name("<aqua><bold>Today's Quests")
                .lore(List.of("<gray>Completed: <white>" + done + "/" + d.quests.size(), "", "<yellow>▶ Click to view")).glow(done < d.quests.size()).build(), (p, c) -> plugin.quests().open(p));
        tile(menu, 21, Material.DIAMOND_SWORD, "<green><bold>Skills", "Level skills and inspect your stats", (p, c) -> plugin.skills().open(p));
        tile(menu, 22, Material.MAP, "<yellow><bold>Tutorial", "Track your first-hour objectives", (p, c) -> plugin.onboarding().onCommand(p, null, "tutorial", new String[0]));
        tile(menu, 23, Material.WITHER_SKELETON_SKULL, "<dark_red><bold>World Boss", "See the next boss countdown", (p, c) -> plugin.forge().open(p, "SUMMONS", 0));
        tile(menu, 24, Material.BOOK, "<white><bold>Profile", "View your rank and statistics", (p, c) -> command(p, "profile"));

        menu.set(36, new ItemBuilder(Material.DIAMOND).name("<light_purple><bold>Vote")
                .lore(List.of("<gray>Support the server and earn rewards", "", "<yellow>▶ Click for vote links")).glow(true).build(), (p, c) -> { p.closeInventory(); plugin.votes().showSites(p); });
        menu.set(40, new ItemBuilder(Material.AMETHYST_SHARD).name("<#7289DA><bold>Discord")
                .lore(List.of("<gray>Events, giveaways and support", "", "<yellow>▶ Click for the invite")).build(), (p, c) -> openUrl(p, cfg.getString("discord-url", ""), "Join us: "));
        menu.set(44, new ItemBuilder(Material.PAPER).name("<white><bold>Rules")
                .lore(List.of("<gray>Be kind, no cheats, no griefing", "<gray>Full rules are shown here", "", "<yellow>▶ Click to read")).build(), (p, c) -> openRules(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    /** Original full grid retained as a fallback while the compact hub is used by default. */
    private void openMainLegacy(Player player, boolean classic) {
        if (!classic && plugin.bedrock() != null && plugin.bedrock().isBedrock(player)) {
            plugin.onboarding().complete(player, Onboarding.Step.MENU);
            plugin.bedrock().openMain(player);
            return;
        }
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        plugin.quests().ensureToday(d);
        plugin.onboarding().complete(player, Onboarding.Step.MENU);
        var cfg = plugin.getConfig();
        Menu menu = new Menu(6, Text.mm("<dark_gray>" + stripTags(cfg.getString("server-name", "Menu")) + " · Menu"));

        menu.set(4, profileHead(player, d));
        if (plugin.kits() != null) menu.set(12, new ItemBuilder(Material.CHEST).name("<aqua><bold>Rank Kits")
                .lore(List.of("<gray>Preview supplies and claim earned kits.", "<gray>See unlocks and cooldowns.", "", "<yellow>▶ Click to browse"))
                .build(), (p, c) -> plugin.kits().open(p));

        boolean daily = plugin.daily().canClaim(d);
        menu.set(19, new ItemBuilder(daily ? Material.CHEST : Material.ENDER_CHEST)
                .name("<gradient:#FDE68A:#F59E0B><bold>Daily Rewards")
                .lore(List.of("<gray>Log in every day to grow your streak.", "",
                        "<gray>Streak: <gold>" + plugin.daily().effectiveStreak(d) + " days",
                        daily ? "<yellow>▶ Reward ready — click!" : "<gray>Next in <yellow>" + Text.duration(plugin.secondsUntilReset())))
                .glow(daily).build(), (p, c) -> plugin.daily().open(p));

        int done = plugin.quests().completedToday(d);
        menu.set(20, new ItemBuilder(Material.WRITABLE_BOOK).name("<aqua><bold>Daily Quests")
                .lore(List.of("<gray>Fresh challenges every day.", "",
                        "<gray>Completed: <white>" + done + "/" + d.quests.size(),
                        "<yellow>▶ Click to view"))
                .glow(done < d.quests.size()).build(), (p, c) -> plugin.quests().open(p));
        menu.set(26, new ItemBuilder(Material.CLOCK).name("<gold><bold>Weekly Contracts")
                .lore(List.of("<gray>Longer goals with bigger rewards.", "<gray>Complete all three for a weekly bonus.", "", "<yellow>▶ Click to view"))
                .glow(plugin.weekly().completed(d) < d.weeklyContracts.size()).build(), (p, c) -> plugin.weekly().open(p));

        menu.set(21, new ItemBuilder(Material.EXPERIENCE_BOTTLE).name("<green><bold>Skills")
                .lore(List.of("<gray>Level up Mining, Farming, Fighting", "<gray>and more just by playing.", "", "<yellow>▶ Click to open"))
                .build(), (p, c) -> command(p, "skills"));

        menu.set(22, new ItemBuilder(Material.GOLD_INGOT).name("<gold><bold>Bazaar")
                .lore(List.of("<gray>Trade resources with other players.", "<gray>Place buy orders & sell offers.", "", "<yellow>▶ Click to trade"))
                .glow(true).build(), (p, c) -> plugin.bazaar().open(p));
        menu.set(23, new ItemBuilder(Material.EMERALD).name("<green><bold>Shop")
                .lore(List.of("<gray>Buy basics, sell anything.", "", "<yellow>▶ Click to open")).build(), (p, c) -> plugin.shop().open(p));
        menu.set(24, new ItemBuilder(Material.ANVIL).name("<gold><bold>Aether Forge")
                .lore(List.of("<gray>Craft custom gear, tools,", "<gray>talismans and boss relics.", "", "<yellow>▶ Click to forge")).build(), (p, c) -> plugin.forge().open(p));
        menu.set(13, new ItemBuilder(Material.GOLDEN_HORSE_ARMOR).name("<gold><bold>Auction House")
                .lore(List.of("<gray>Buy and sell weapons, armor,", "<gray>tools and rare items.", "<gray>Buy-It-Now or bidding auctions.", "", "<yellow>▶ Click to open"))
                .glow(true).build(), (p, c) -> plugin.auctionMenus().openHub(p));
        menu.set(34, new ItemBuilder(Material.WITHER_SKELETON_SKULL).name("<dark_red><bold>Boss Battles")
                .lore(List.of("<gray>Forge a relic and right-click the", "<gray>ground (150+ blocks from spawn)", "<gray>to summon a boss. Loot is shared", "<gray>by damage dealt.", "",
                        "<gray>World boss in: <red>" + Text.duration(plugin.bosses().secondsUntilWorldBoss()), "", "<yellow>▶ Click to see relics"))
                .build(), (p, c) -> plugin.forge().open(p, "SUMMONS", 0));
        menu.set(25, new ItemBuilder(Material.GOLD_BLOCK).name("<gold><bold>Leaderboards")
                .lore(List.of("<gray>See the most dedicated players.", "", "<yellow>▶ Click to view"))
                .build(), (p, c) -> openTop(p));

        menu.set(29, new ItemBuilder(Material.COMPASS).name("<white><bold>Spawn")
                .lore(List.of("<gray>Teleport back to spawn.")).build(), (p, c) -> command(p, "spawn"));
        menu.set(30, new ItemBuilder(Material.ENDER_PEARL).name("<light_purple><bold>Warps")
                .lore(List.of("<gray>Travel to public warps.")).build(), (p, c) -> command(p, "warp"));
        menu.set(31, new ItemBuilder(Material.RED_BED).name("<red><bold>Homes")
                .lore(List.of("<gray>Your saved homes.", "<dark_gray>Set one with /sethome <name>")).build(), (p, c) -> command(p, "home"));
        menu.set(32, new ItemBuilder(Material.GOLDEN_SHOVEL).name("<yellow><bold>Land Claims")
                .lore(List.of("<gray>Protect your builds from griefers.", "", "<yellow>▶ Click for a quick guide")).build(),
                (p, c) -> command(p, "claimhelp"));
        menu.set(33, new ItemBuilder(Material.FILLED_MAP).name("<aqua><bold>Live Map")
                .lore(List.of("<gray>Explore the world in your browser.")).build(), (p, c) -> {
            p.closeInventory();
            String url = cfg.getString("map-url", "");
            p.sendMessage(Text.mm(cfg.getString("prefix", "") + "<gray>Live map: <click:open_url:'" + url + "'><aqua><u>" + url + "</u></aqua></click>"));
        });

        menu.set(37, toggleItem(Material.IRON_AXE, "Timber", d.timber, "Chop the bottom log to fell the whole tree."),
                (p, c) -> { toggleTimber(p); openMain(p); });
        menu.set(38, toggleItem(Material.IRON_PICKAXE, "Vein Miner", d.vein, "Sneak while mining ores to mine the whole vein."),
                (p, c) -> { toggleVein(p); openMain(p); });
        menu.set(40, new ItemBuilder(Material.NAME_TAG).name("<gold><bold>Ranks")
                .lore(List.of("<gray>Ranks are earned by playing.", "", "<yellow>▶ Click to see the ladder")).build(), (p, c) -> openRanks(p));
        menu.set(41, new ItemBuilder(Material.DIAMOND).name("<light_purple><bold>Vote")
                .lore(List.of("<gray>Vote daily on server lists for coins.", "<gray>Every vote brings the <light_purple>Vote Party</light_purple> closer!",
                        "", "<gray>Your votes: <yellow>" + d.votes, "<gray>Party in: <light_purple>" + plugin.votes().partyLeft() + " votes",
                        "", "<yellow>▶ Click for links")).glow(true).build(), (p, c) -> { p.closeInventory(); plugin.votes().showSites(p); });
        menu.set(42, new ItemBuilder(Material.AMETHYST_SHARD).name("<#7289DA><bold>Discord")
                .lore(List.of("<gray>Events, giveaways and support.", "", "<yellow>▶ Click for the invite")).build(), (p, c) -> {
            p.closeInventory();
            String url = cfg.getString("discord-url", "");
            p.sendMessage(Text.mm(cfg.getString("prefix", "") + "<gray>Join us: <click:open_url:'" + url + "'><aqua><u>" + url + "</u></aqua></click>"));
        });
        menu.set(43, new ItemBuilder(Material.BOOK).name("<white><bold>Rules")
                .lore(List.of("<gray>1. Be kind — no harassment or hate.", "<gray>2. No cheats, x-ray or exploits.",
                        "<gray>3. No griefing or stealing, even unclaimed.", "<gray>4. No spam or advertising.",
                        "<gray>5. Staff decisions are final — appeal on Discord.")).build());

        menu.set(44, new ItemBuilder(Material.ENCHANTING_TABLE).name("<aqua><bold>Skills & Stats")
                .lore(List.of("<gray>Level your skills and view RPG stats.", "<yellow>Click to open")).build(), (p, c) -> plugin.skills().open(p));
        menu.set(39, new ItemBuilder(Material.ANVIL).name("<gold><bold>Equipment Customization")
                .lore(List.of("<gray>Reforge your held custom gear.", "<yellow>Click to open")).build(), (p, c) -> plugin.skills().equipment().open(p));

        // The bottom navigation row is the first-hour dashboard: every major progression system
        // is reachable without memorising a command.
        menu.set(45, new ItemBuilder(Material.CHEST).name("<gold><bold>Collections")
                .lore(List.of("<gray>Track every custom item you discover.", "<yellow>▶ Open collections")).build(), (p, c) -> plugin.collections().open(p));
        menu.set(46, new ItemBuilder(Material.BONE).name("<dark_red><bold>Bestiary")
                .lore(List.of("<gray>Record mobs, elites, and bosses.", "<yellow>▶ Open Bestiary")).build(), (p, c) -> plugin.bestiary().open(p));
        menu.set(47, new ItemBuilder(Material.BEACON).name("<aqua><bold>Community")
                .lore(List.of("<gray>Help advance shared server goals.", "<yellow>▶ View progress")).build(), (p, c) -> plugin.community().open(p));
        menu.set(48, new ItemBuilder(Material.HOPPER).name("<green><bold>Minions")
                .lore(List.of("<gray>Manage upgraded resource minions.", "<yellow>▶ Open minions")).build(), (p, c) -> plugin.minions().onCommand(p, null, "minions", new String[0]));
        menu.set(49, new ItemBuilder(Material.WHITE_BANNER).name("<light_purple><bold>Guild")
                .lore(List.of("<gray>Play together and grow a guild.", "<yellow>▶ Open guild")).build(), (p, c) -> plugin.guilds().onCommand(p, null, "guild", new String[0]));
        menu.set(50, new ItemBuilder(Material.IRON_SWORD).name("<red><bold>Slayer")
                .lore(List.of("<gray>Take contracts and hunt bosses.", "<yellow>▶ Open Slayer")).build(), (p, c) -> plugin.slayer().open(p));
        menu.set(51, new ItemBuilder(Material.MAP).name("<yellow><bold>Tutorial")
                .lore(List.of("<gray>Follow your first-hour objectives.", "<yellow>▶ View tutorial")).build(), (p, c) -> plugin.onboarding().onCommand(p, null, "tutorial", new String[0]));
        menu.set(52, new ItemBuilder(Material.CLOCK).name("<gold><bold>Events & Contracts")
                .lore(List.of("<gray>See rotating goals and rewards.", "<yellow>▶ Open weekly contracts")).build(), (p, c) -> plugin.weekly().open(p));
        menu.set(53, new ItemBuilder(Material.NAME_TAG).name("<white><bold>Ranks & Kits")
                .lore(List.of("<gray>View playtime perks and claim kits.", "<yellow>▶ Browse rewards")).build(), (p, c) -> openRanks(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    private void openProgression(Player p) {
        Menu menu = new Menu(3, Text.mm("<dark_gray>Menu · Progression"));
        tile(menu, 10, Material.CHEST, "<gold>Daily Rewards", "Claim today's reward", (pl, c) -> plugin.daily().open(pl));
        tile(menu, 11, Material.WRITABLE_BOOK, "<aqua>Daily Quests", "Complete today's objectives", (pl, c) -> plugin.quests().open(pl));
        tile(menu, 12, Material.CLOCK, "<gold>Weekly Contracts", "Longer goals and bonus rewards", (pl, c) -> plugin.weekly().open(pl));
        tile(menu, 13, Material.DIAMOND_SWORD, "<green>Skills & Stats", "Level skills and inspect stats", (pl, c) -> plugin.skills().open(pl));
        tile(menu, 14, Material.CHEST, "<gold>Collections", "Track custom items and milestones", (pl, c) -> plugin.collections().open(pl));
        tile(menu, 15, Material.BONE, "<dark_red>Bestiary", "Record mobs, elites and bosses", (pl, c) -> plugin.bestiary().open(pl));
        tile(menu, 16, Material.IRON_SWORD, "<red>Slayer", "Take contracts and hunt bosses", (pl, c) -> plugin.slayer().open(pl));
        tile(menu, 19, Material.HOPPER, "<green>Minions", "Manage upgraded resource minions", (pl, c) -> plugin.minions().onCommand(pl, null, "minions", new String[0]));
        tile(menu, 20, Material.MAP, "<yellow>Tutorial", "Track your first-hour objectives", (pl, c) -> plugin.onboarding().onCommand(pl, null, "tutorial", new String[0]));
        back(menu, 18);
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openEconomy(Player p) {
        Menu menu = new Menu(3, Text.mm("<dark_gray>Menu · Economy"));
        tile(menu, 10, Material.EMERALD, "<green>Shop", "Buy basics and sell resources", (pl, c) -> plugin.shop().open(pl));
        tile(menu, 11, Material.GOLD_INGOT, "<gold>Bazaar", "Trade resources with players", (pl, c) -> plugin.bazaar().open(pl));
        tile(menu, 12, Material.GOLDEN_HORSE_ARMOR, "<gold>Auction House", "Buy and sell gear and rare items", (pl, c) -> plugin.auctionMenus().openHub(pl));
        tile(menu, 13, Material.ANVIL, "<gold>Aether Forge", "Craft gear, talismans and relics", (pl, c) -> plugin.forge().open(pl));
        tile(menu, 14, Material.LAVA_BUCKET, "<gray>Salvage", "Break down unwanted custom gear", (pl, c) -> plugin.salvage().open(pl));
        back(menu, 18);
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openSocial(Player p) {
        Menu menu = new Menu(3, Text.mm("<dark_gray>Menu · Social"));
        tile(menu, 10, Material.WHITE_BANNER, "<light_purple>Guild", "Play together and grow a guild", (pl, c) -> plugin.guilds().onCommand(pl, null, "guild", new String[0]));
        tile(menu, 11, Material.BEACON, "<aqua>Community", "Help advance shared server goals", (pl, c) -> plugin.community().open(pl));
        tile(menu, 12, Material.GOLD_BLOCK, "<gold>Leaderboards", "See the most dedicated players", (pl, c) -> openTop(pl));
        tile(menu, 13, Material.BOOK, "<white>Profile", "View your rank and statistics", (pl, c) -> command(pl, "profile"));
        tile(menu, 14, Material.DIAMOND, "<light_purple>Vote", "Support the server and earn rewards", (pl, c) -> { pl.closeInventory(); plugin.votes().showSites(pl); });
        tile(menu, 15, Material.AMETHYST_SHARD, "<#7289DA>Discord", "Events, giveaways and support", (pl, c) -> openUrl(pl, plugin.getConfig().getString("discord-url", ""), "Join us: "));
        back(menu, 18);
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openWorld(Player p) {
        Menu menu = new Menu(3, Text.mm("<dark_gray>Menu · World"));
        tile(menu, 10, Material.COMPASS, "<white>Spawn", "Teleport back to spawn", (pl, c) -> command(pl, "spawn"));
        tile(menu, 11, Material.ENDER_PEARL, "<light_purple>Warps", "Travel to public warps", (pl, c) -> command(pl, "warp"));
        tile(menu, 12, Material.RED_BED, "<red>Homes", "Manage your saved homes", (pl, c) -> command(pl, "home"));
        tile(menu, 13, Material.GOLDEN_SHOVEL, "<yellow>Land Claims", "Protect your builds", (pl, c) -> command(pl, "claimhelp"));
        tile(menu, 14, Material.FILLED_MAP, "<aqua>Live Map", "Explore the world in your browser", (pl, c) -> openUrl(pl, plugin.getConfig().getString("map-url", ""), "Live map: "));
        tile(menu, 15, Material.PAPER, "<white>Rules", "Read the server rules", (pl, c) -> openRules(pl));
        back(menu, 18);
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openRewards(Player p) {
        Menu menu = new Menu(3, Text.mm("<dark_gray>Menu · Rewards"));
        tile(menu, 11, Material.NAME_TAG, "<gold>Ranks & Perks", "Earn ranks through active play", (pl, c) -> openRanks(pl));
        if (plugin.kits() != null) tile(menu, 13, Material.CHEST, "<aqua>Rank Kits", "Preview and claim earned kits", (pl, c) -> plugin.kits().open(pl));
        tile(menu, 15, Material.CLOCK, "<yellow>Playtime", "See progress toward your next rank", (pl, c) -> command(pl, "playtime"));
        back(menu, 18);
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openSettings(Player p) {
        PlayerData d = plugin.data().get(p);
        if (d == null) return;
        Menu menu = new Menu(3, Text.mm("<dark_gray>Menu · Settings"));
        menu.set(11, toggleItem(Material.IRON_AXE, "Timber", d.timber, "Chop the bottom log to fell the whole tree."), (pl, c) -> { toggleTimber(pl); openSettings(pl); });
        menu.set(13, toggleItem(Material.IRON_PICKAXE, "Vein Miner", d.vein, "Sneak while mining ores to mine the whole vein."), (pl, c) -> { toggleVein(pl); openSettings(pl); });
        tile(menu, 15, Material.ANVIL, "<gold>Equipment", "Reforge your held custom gear", (pl, c) -> plugin.skills().equipment().open(pl));
        back(menu, 18);
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void tile(Menu menu, int slot, Material icon, String name, String description,
                      java.util.function.BiConsumer<Player, org.bukkit.event.inventory.ClickType> action) {
        menu.set(slot, new ItemBuilder(icon).name(name).lore(List.of("<gray>" + description, "", "<yellow>▶ Click to open")).build(), action);
    }

    private void back(Menu menu, int slot) {
        menu.set(slot, new ItemBuilder(Material.ARROW).name("<gray>← Back to Menu").build(), (p, c) -> openMain(p));
    }

    private void openUrl(Player p, String url, String label) {
        p.closeInventory();
        p.sendMessage(Text.mm(plugin.getConfig().getString("prefix", "") + "<gray>" + label + "<click:open_url:'" + url + "'><aqua><u>" + url + "</u></aqua></click>"));
    }

    private void openRules(Player p) {
        p.closeInventory();
        for (String line : List.of(
                "<gradient:#FDE68A:#F59E0B><bold>Aetherfall Rules",
                "<gray>1. Be kind — no harassment or hate.",
                "<gray>2. No cheats, x-ray or exploits.",
                "<gray>3. No griefing or stealing, even unclaimed.",
                "<gray>4. No spam or advertising.",
                "<gray>5. Staff decisions are final — appeal on Discord.")) p.sendMessage(Text.mm(line));
    }

    private ItemStack profileHead(Player player, PlayerData d) {
        plugin.collections().sync(player);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        meta.displayName(Text.item("<gradient:#8B5CF6:#22D3EE><bold>{player}", Map.of("player", player.getName())));
        Milestone rank = plugin.playtime().current(d);
        Milestone next = plugin.playtime().next(d);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Rank: " + (rank == null ? "<white>Newcomer" : rank.name()));
        lore.add("<gray>Playtime: <yellow>" + Text.duration(d.playtime));
        if (next != null) lore.add("<gray>Next rank " + next.name() + " <gray>in <yellow>" + Text.duration(Math.max(0, next.hours() * 3600 - d.playtime)));
        lore.add("");
        lore.add("<gray>Daily streak: <gold>" + plugin.daily().effectiveStreak(d) + " <dark_gray>(best " + d.bestStreak + ")");
        lore.add("<gray>Skills total level: <aqua>" + plugin.skills().totalLevel(d));
        lore.add("<gray>Quests completed: <aqua>" + Text.number(d.questsDone));
        lore.add("<gray>Bestiary: <dark_red>" + plugin.bestiary().discoveredCount(d) + "/" + plugin.bestiary().entryCount() + " discovered");
        lore.add("<gray>Bestiary mastered: <gold>" + plugin.bestiary().masteredCount(d));
        lore.add("<gray>Collections: <gold>" + plugin.collections().discoveredCount(d) + "/" + plugin.collections().totalCount() + " discovered");
        lore.add("<gray>Mobs slain: <red>" + Text.number(player.getStatistic(Statistic.MOB_KILLS)));
        lore.add("<gray>Deaths: <dark_red>" + Text.number(player.getStatistic(Statistic.DEATHS)));
        meta.lore(Text.lore(lore, Map.of()));
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack toggleItem(Material icon, String name, boolean on, String desc) {
        return new ItemBuilder(icon).name((on ? "<green><bold>" : "<red><bold>") + name)
                .lore(List.of("<gray>" + desc, "", on ? "<green>● Enabled" : "<red>○ Disabled", "<yellow>▶ Click to toggle"))
                .glow(on).build();
    }

    public void toggleTimber(Player p) {
        PlayerData d = plugin.data().get(p);
        if (d == null) return;
        d.timber = !d.timber;
        plugin.send(p, "toggle-timber", Map.of("state", d.timber ? "on" : "off"));
    }

    public void toggleVein(Player p) {
        PlayerData d = plugin.data().get(p);
        if (d == null) return;
        d.vein = !d.vein;
        plugin.send(p, "toggle-vein", Map.of("state", d.vein ? "on" : "off"));
    }

    public void openTop(Player player) {
        plugin.data().top("playtime", 10, time ->
            plugin.data().top("quests_done", 10, quests ->
                plugin.data().top("best_streak", 10, streak ->
                plugin.data().top("votes", 10, votes -> {
                    if (!player.isOnline()) return;
                    Menu menu = new Menu(3, Text.mm("<dark_gray>Leaderboards"));
                    menu.set(10, board(Material.CLOCK, "<yellow><bold>Most Playtime", time, v -> Text.duration(v)));
                    menu.set(12, board(Material.WRITABLE_BOOK, "<aqua><bold>Most Quests", quests, v -> Text.number(v) + " quests"));
                    menu.set(14, board(Material.BLAZE_POWDER, "<gold><bold>Longest Streak", streak, v -> v + " days"));
                    menu.set(16, board(Material.DIAMOND, "<light_purple><bold>Top Voters", votes, v -> Text.number(v) + " votes"));
                    menu.set(18, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, c) -> openMain(p));
                    menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
                }))));
    }

    private ItemStack board(Material icon, String title, List<TopEntry> entries, Function<Long, String> fmt) {
        List<String> lore = new ArrayList<>();
        String[] medals = {"<gold>", "<white>", "<#CD7F32>"};
        for (int i = 0; i < entries.size(); i++) {
            TopEntry e = entries.get(i);
            String color = i < 3 ? medals[i] : "<gray>";
            lore.add(color + "#" + (i + 1) + " " + escape(e.name()) + " <dark_gray>— <yellow>" + fmt.apply(e.value()));
        }
        if (lore.isEmpty()) lore.add("<gray>No entries yet — be the first!");
        return new ItemBuilder(icon).name(title).lore(lore).build();
    }

    public void openRanks(Player player) {
        PlayerData d = plugin.data().get(player);
        if (d == null) return;
        List<Milestone> ms = plugin.playtime().milestones();
        Menu menu = new Menu(3, Text.mm("<dark_gray>Ranks & Perks · earned by playing"));
        int start = 13 - Math.min(ms.size(), 7) / 2;
        for (int i = 0; i < ms.size() && i < 7; i++) {
            Milestone m = ms.get(i);
            boolean has = d.milestones.contains(m.id());
            long left = Math.max(0, m.hours() * 3600 - d.playtime);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Requires <white>" + m.hours() + "h</white> of active play");
            lore.add("<gray>Instant reward: <gold>" + Text.number(m.coins()) + " coins");
            lore.add("");
            lore.add("<gold>Perks:");
            for (String line : plugin.perks().ofRank(m.id()).lore()) lore.add("<dark_gray>▸ " + line);
            lore.add("<dark_gray>▸ <gray>…plus every perk of the ranks below");
            lore.add("");
            lore.add(has ? "<green>✔ Unlocked" : "<yellow>" + Text.duration(left) + " to go");
            menu.set(start + i, new ItemBuilder(has ? Material.LIME_STAINED_GLASS : Material.GRAY_STAINED_GLASS)
                    .name(m.name()).lore(lore).glow(has).build());
        }
        menu.set(18, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, c) -> openMain(p));
        if (plugin.kits() != null) menu.set(22, new ItemBuilder(Material.CHEST).name("<aqua>Browse Rank Kits")
                .build(), (p, c) -> plugin.kits().open(p));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    private static void command(Player p, String cmd) {
        p.closeInventory();
        p.performCommand(cmd);
    }

    private static String escape(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    private static String stripTags(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(s);
    }
}
