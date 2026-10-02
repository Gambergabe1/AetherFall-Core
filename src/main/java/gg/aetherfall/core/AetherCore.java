package gg.aetherfall.core;

import gg.aetherfall.core.command.Commands;
import gg.aetherfall.core.data.DataManager;
import gg.aetherfall.core.data.ConfigMigrations;
import gg.aetherfall.core.module.Announcer;
import gg.aetherfall.core.module.ChatGames;
import gg.aetherfall.core.module.DailyRewards;
import gg.aetherfall.core.module.Harvester;
import gg.aetherfall.core.module.Menus;
import gg.aetherfall.core.module.PlacedBlocks;
import gg.aetherfall.core.module.PlaytimeTracker;
import gg.aetherfall.core.module.QuestManager;
import gg.aetherfall.core.module.Sessions;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class AetherCore extends JavaPlugin {
    private DataManager data;
    private PlacedBlocks placedBlocks;
    private DailyRewards daily;
    private QuestManager quests;
    private gg.aetherfall.core.module.WeeklyContracts weekly;
    private gg.aetherfall.core.module.WeeklyEvents weeklyEvents;
    private gg.aetherfall.core.module.Collections collections;
    private gg.aetherfall.core.module.CommunityProgress community;
    private gg.aetherfall.core.module.Bestiary bestiary;
    private gg.aetherfall.core.module.Achievements achievements;
    private gg.aetherfall.core.module.Minions minions;
    private gg.aetherfall.core.module.Guilds guilds;
    private gg.aetherfall.core.module.SlayerContracts slayer;
    private gg.aetherfall.core.module.FishingEncounters fishingEncounters;
    private gg.aetherfall.core.module.CustomEnchantBooks customEnchantBooks;
    private gg.aetherfall.core.module.CropMutations cropMutations;
    private PlaytimeTracker playtime;
    private gg.aetherfall.core.module.RankPerks perks;
    private gg.aetherfall.core.module.ChatFormat chatFormat;
    private gg.aetherfall.core.module.KitMenus kits;
    private Harvester harvester;
    private gg.aetherfall.core.module.FarmingFramework farmingFramework;
    private Announcer announcer;
    private ChatGames chatGames;
    private Menus menus;
    private gg.aetherfall.core.module.VoteRewards votes;
    private gg.aetherfall.core.module.Restarts restarts;
    private gg.aetherfall.core.items.ItemRegistry items;
    private gg.aetherfall.core.items.OreManager ores;
    private gg.aetherfall.core.items.DropManager drops;
    private gg.aetherfall.core.items.Abilities abilities;
    private gg.aetherfall.core.items.Forge forge;
    private gg.aetherfall.core.economy.Shop shop;
    private gg.aetherfall.core.economy.Bazaar bazaar;
    private gg.aetherfall.core.economy.ChatPrompt chatPrompt;
    private gg.aetherfall.core.economy.AuctionHouse auctionHouse;
    private gg.aetherfall.core.economy.AuctionMenus auctionMenus;
    private gg.aetherfall.core.mobs.MobManager mobs;
    private gg.aetherfall.core.mobs.BossManager bosses;
    private gg.aetherfall.core.module.Onboarding onboarding;
    private gg.aetherfall.core.module.Social social;
    private gg.aetherfall.core.module.Staff staff;
    private gg.aetherfall.core.module.BedrockMenus bedrock;
    private gg.aetherfall.core.module.PackServer packServer;
    private gg.aetherfall.core.module.CropBooster cropBooster;
    private gg.aetherfall.core.items.AdminItemMenu adminItems;
    private gg.aetherfall.core.items.SalvageMenu salvage;
    private gg.aetherfall.core.module.SkillManager skills;
    private ZoneId zone = ZoneId.of("UTC");
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            ConfigMigrations.migrate(this);
            reloadConfig();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not migrate config.yml safely; disabling AetherCore", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        readZone();

        data = new DataManager(this);
        try {
            data.open();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open the database; disabling AetherCore", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        placedBlocks = new PlacedBlocks();
        items = new gg.aetherfall.core.items.ItemRegistry(this);
        ores = new gg.aetherfall.core.items.OreManager(this);
        mobs = new gg.aetherfall.core.mobs.MobManager(this);
        bosses = new gg.aetherfall.core.mobs.BossManager(this);
        abilities = new gg.aetherfall.core.items.Abilities(this);
        drops = new gg.aetherfall.core.items.DropManager(this);
        forge = new gg.aetherfall.core.items.Forge(this);
        shop = new gg.aetherfall.core.economy.Shop(this);
        bazaar = new gg.aetherfall.core.economy.Bazaar(this);
        chatPrompt = new gg.aetherfall.core.economy.ChatPrompt(this);
        onboarding = new gg.aetherfall.core.module.Onboarding(this);
        social = new gg.aetherfall.core.module.Social(this);
        staff = new gg.aetherfall.core.module.Staff(this);
        auctionHouse = new gg.aetherfall.core.economy.AuctionHouse(this);
        auctionMenus = new gg.aetherfall.core.economy.AuctionMenus(this, auctionHouse);
        daily = new DailyRewards(this);
        quests = new QuestManager(this);
        weekly = new gg.aetherfall.core.module.WeeklyContracts(this);
        playtime = new PlaytimeTracker(this);
        perks = new gg.aetherfall.core.module.RankPerks(this);
        chatFormat = new gg.aetherfall.core.module.ChatFormat(this);
        harvester = new Harvester(this);
        farmingFramework = new gg.aetherfall.core.module.FarmingFramework(this);
        announcer = new Announcer(this);
        chatGames = new ChatGames(this);
        menus = new Menus(this);
        votes = new gg.aetherfall.core.module.VoteRewards(this);
        restarts = new gg.aetherfall.core.module.Restarts(this);
        weeklyEvents = new gg.aetherfall.core.module.WeeklyEvents(this);
        collections = new gg.aetherfall.core.module.Collections(this);
        community = new gg.aetherfall.core.module.CommunityProgress(this);
        bestiary = new gg.aetherfall.core.module.Bestiary(this);
        achievements = new gg.aetherfall.core.module.Achievements(this);
        minions = new gg.aetherfall.core.module.Minions(this);
        guilds = new gg.aetherfall.core.module.Guilds(this);
        slayer = new gg.aetherfall.core.module.SlayerContracts(this);
        fishingEncounters = new gg.aetherfall.core.module.FishingEncounters(this);
        customEnchantBooks = new gg.aetherfall.core.module.CustomEnchantBooks(this);
        cropMutations = new gg.aetherfall.core.module.CropMutations(this);
        adminItems = new gg.aetherfall.core.items.AdminItemMenu(this);
        salvage = new gg.aetherfall.core.items.SalvageMenu(this);
        skills = new gg.aetherfall.core.module.SkillManager(this);
        packServer = new gg.aetherfall.core.module.PackServer(this);
        cropBooster = new gg.aetherfall.core.module.CropBooster(this);

        var pm = getServer().getPluginManager();
        // AetherCore is the authoritative RPG/skills system. Disable AuraSkills so it cannot
        // award a second stream of XP or apply competing stats and mana values.
        var auraSkills = pm.getPlugin("AuraSkills");
        if (auraSkills != null && auraSkills.isEnabled()) {
            pm.disablePlugin(auraSkills);
            getLogger().info("AuraSkills disabled; AetherCore Skills now own XP, stats and mana.");
        }
        // Native kits remain available when EssentialsX is absent; EssentialsX is only an optional adapter.
        kits = new gg.aetherfall.core.module.KitMenus(this);
        bind("kits", kits);
        bind("perks", perks);
        bind("trail", perks);
        pm.registerEvents(new Menu.MenuListener(), this);
        pm.registerEvents(new Sessions(this), this);
        pm.registerEvents(cropBooster, this);
        pm.registerEvents(placedBlocks, this);
        pm.registerEvents(quests, this);
        pm.registerEvents(playtime, this);
        pm.registerEvents(harvester, this);
        pm.registerEvents(farmingFramework, this);
        pm.registerEvents(chatGames, this);
        pm.registerEvents(ores, this);
        pm.registerEvents(drops, this);
        pm.registerEvents(abilities, this);
        pm.registerEvents(new gg.aetherfall.core.items.ItemGuard(this), this);
        pm.registerEvents(chatPrompt, this);
        pm.registerEvents(auctionHouse, this);
        pm.registerEvents(mobs, this);
        pm.registerEvents(bosses, this);
        pm.registerEvents(new gg.aetherfall.core.module.Npcs(this), this);
        pm.registerEvents(onboarding, this);
        pm.registerEvents(social, this);
        pm.registerEvents(staff, this);
        pm.registerEvents(chatFormat, this);
        pm.registerEvents(collections, this);
        pm.registerEvents(slayer, this); pm.registerEvents(fishingEncounters, this); pm.registerEvents(customEnchantBooks, this); pm.registerEvents(cropMutations, this);
        pm.registerEvents(new gg.aetherfall.core.module.ReturnSummary(this), this);
        pm.registerEvents(bestiary, this);
        pm.registerEvents(skills, this);
        pm.registerEvents(minions, this);
        bind("skills", skills);
        bind("equipment", skills);
        bind("enchants", customEnchantBooks);
        bind("enchant", customEnchantBooks);
        if (pm.isPluginEnabled("floodgate")) {
            bedrock = new gg.aetherfall.core.module.BedrockMenus(this);
            getLogger().info("Native Bedrock menus enabled (Floodgate)");
        }
        if (pm.isPluginEnabled("GriefPrevention")) pm.registerEvents(new gg.aetherfall.core.module.GriefPreventionHook(this), this);
        bind("tutorial", onboarding);
        for (String c : List.of("party", "partychat", "friend")) bind(c, social);
        for (String c : List.of("report", "reports", "staffchat")) bind(c, staff);
        gg.aetherfall.core.module.Frames frames = new gg.aetherfall.core.module.Frames(this);
        pm.registerEvents(frames, this);
        PluginCommand frameCmd = getCommand("frame");
        if (frameCmd != null) { frameCmd.setExecutor(frames); frameCmd.setTabCompleter(frames); }

        Commands commands = new Commands(this);
        for (String name : List.of("menu", "daily", "quests", "contracts", "collections", "community", "bestiary", "achievements", "title", "minions", "guild", "slayer", "playtime", "top", "profile", "timber", "veinminer", "aether", "claimhelp", "map", "discord", "vote", "forge", "shop", "bazaar", "sellall", "auctionhouse", "aegive", "salvage", "magnet")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }

        applyGamerules();
        ores.populateLoaded();
        playtime.start();
        announcer.start();
        chatGames.start();
        restarts.start();
        weeklyEvents.start();
        minions.start();
        // Autosave every 5 minutes; also drops stale entries left by denied logins.
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            data.online().removeIf(d -> Bukkit.getPlayer(d.uuid) == null && !isLoggingIn(d.uuid));
            data.saveAllAsync();
        }, 6000L, 6000L);

        // Supports /reload and plugin managers: adopt players who are already online.
        for (Player p : Bukkit.getOnlinePlayers()) data.ensureLoaded(p);

        if (pm.isPluginEnabled("Votifier")) {
            pm.registerEvents(new gg.aetherfall.core.module.VoteListener(this, votes), this);
            getLogger().info("Listening for votes via NuVotifier");
        }
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            new gg.aetherfall.core.module.Placeholders(this).register();
            getLogger().info("Registered PlaceholderAPI placeholders (%aether_...%)");
        }
        getLogger().info("AetherCore enabled — " + data.uniquePlayers() + " unique players on record.");
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (playtime != null) playtime.stop();
        if (announcer != null) announcer.stop();
        if (chatGames != null) chatGames.stop();
        if (restarts != null) restarts.stop();
        if (perks != null) perks.stop();
        if (abilities != null) abilities.stop();
        if (mobs != null) mobs.stop();
        if (auctionHouse != null) auctionHouse.stop();
        if (skills != null) skills.stop();
        if (packServer != null) packServer.stop();
        if (cropBooster != null) cropBooster.stop();
        if (weeklyEvents != null) weeklyEvents.stop();
        if (minions != null) minions.stop();
        if (bosses != null) bosses.shutdown();
        if (fishingEncounters != null) fishingEncounters.stop();
        if (community != null) community.saveNow();
        if (data != null) data.shutdown();
    }

    private void bind(String name, org.bukkit.command.TabExecutor exec) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) { cmd.setExecutor(exec); cmd.setTabCompleter(exec); }
    }

    public void reloadAll() {
        reloadConfig();
        if (packServer != null) packServer.reload();
        if (cropBooster != null) cropBooster.reload();
        staff.reload();
        readZone();
        quests.reload();
        weekly.reload();
        items.reload();
        ores.reload();
        drops.reload();
        if (customEnchantBooks != null) customEnchantBooks.reload();
        shop.reload();
        bazaar.reload();
        mobs.reload();
        if (fishingEncounters != null) fishingEncounters.reload();
        playtime.reload();
        perks.reload();
        chatFormat.reload();
        announcer.start();
        chatGames.start();
        restarts.start();
        weeklyEvents.start();
        applyGamerules();
    }

    private boolean isLoggingIn(java.util.UUID uuid) {
        return Sessions.isPending(uuid);
    }

    private void readZone() {
        try {
            zone = ZoneId.of(getConfig().getString("timezone", "UTC"));
        } catch (Exception e) {
            getLogger().warning("Invalid timezone in config.yml; using UTC");
            zone = ZoneId.of("UTC");
        }
    }

    private void applyGamerules() {
        int pct = getConfig().getInt("gamerules.players-sleeping-percentage", 100);
        for (World world : Bukkit.getWorlds()) world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, pct);
    }

    // ── shared helpers ────────────────────────────────────────

    public long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    /** Seconds until the next daily reset. */
    public long secondsUntilReset() {
        var now = java.time.ZonedDateTime.now(zone);
        var next = now.toLocalDate().plusDays(1).atStartOfDay(zone);
        return java.time.Duration.between(now, next).getSeconds();
    }

    public String raw(String path) {
        return getConfig().getString("messages." + path, "<red>Missing message: " + path);
    }

    public Component msg(String path, Map<String, ?> vars) {
        return Text.mm(getConfig().getString("prefix", "") + Text.fill(raw(path), vars));
    }

    public void send(CommandSender to, String path, Map<String, ?> vars) {
        to.sendMessage(msg(path, vars));
    }

    public void send(CommandSender to, String path) {
        send(to, path, Map.of());
    }

    public void broadcast(String path, Map<String, ?> vars) {
        Bukkit.broadcast(msg(path, vars));
    }

    /** Pays coins through the configured economy command. */
    public void giveCoins(Player player, long amount) {
        if (amount <= 0) return;
        runCommand(getConfig().getString("coin-command", "eco give {player} {amount}"),
                player, Map.of("amount", amount));
    }

    public void runCommands(List<String> commands, Player player) {
        for (String c : commands) runCommand(c, player, Map.of());
    }

    private void runCommand(String template, Player player, Map<String, ?> extra) {
        String cmd = template.replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString());
        for (var e : extra.entrySet()) cmd = cmd.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Reward command failed: " + cmd, e);
        }
    }

    public DataManager data() { return data; }
    public gg.aetherfall.core.module.SkillManager skills() { return skills; }
    public PlacedBlocks placedBlocks() { return placedBlocks; }
    public DailyRewards daily() { return daily; }
    public QuestManager quests() { return quests; }
    public gg.aetherfall.core.module.WeeklyContracts weekly() { return weekly; }
    public gg.aetherfall.core.module.WeeklyEvents weeklyEvents() { return weeklyEvents; }
    public gg.aetherfall.core.module.Collections collections() { return collections; }
    public gg.aetherfall.core.module.CommunityProgress community() { return community; }
    public gg.aetherfall.core.module.Bestiary bestiary() { return bestiary; }
    public gg.aetherfall.core.module.Achievements achievements() { return achievements; }
    public gg.aetherfall.core.module.Minions minions() { return minions; }
    public gg.aetherfall.core.module.Guilds guilds() { return guilds; }
    public gg.aetherfall.core.module.SlayerContracts slayer() { return slayer; }
    public PlaytimeTracker playtime() { return playtime; }
    public gg.aetherfall.core.module.RankPerks perks() { return perks; }
    public gg.aetherfall.core.module.ChatFormat chatFormat() { return chatFormat; }
    public gg.aetherfall.core.module.KitMenus kits() { return kits; }
    public Harvester harvester() { return harvester; }
    public Menus menus() { return menus; }
    public ChatGames chatGames() { return chatGames; }
    public gg.aetherfall.core.module.VoteRewards votes() { return votes; }
    public gg.aetherfall.core.module.Restarts restarts() { return restarts; }
    public gg.aetherfall.core.items.ItemRegistry items() { return items; }
    public gg.aetherfall.core.items.DropManager drops() { return drops; }
    public gg.aetherfall.core.items.OreManager ores() { return ores; }
    public gg.aetherfall.core.items.Abilities abilities() { return abilities; }
    public gg.aetherfall.core.items.Forge forge() { return forge; }
    public gg.aetherfall.core.economy.Shop shop() { return shop; }
    public gg.aetherfall.core.economy.Bazaar bazaar() { return bazaar; }
    public gg.aetherfall.core.economy.ChatPrompt chatPrompt() { return chatPrompt; }
    public gg.aetherfall.core.economy.AuctionHouse auctionHouse() { return auctionHouse; }
    public gg.aetherfall.core.economy.AuctionMenus auctionMenus() { return auctionMenus; }
    public gg.aetherfall.core.mobs.MobManager mobs() { return mobs; }
    public gg.aetherfall.core.mobs.BossManager bosses() { return bosses; }
    public gg.aetherfall.core.module.Onboarding onboarding() { return onboarding; }
    public gg.aetherfall.core.module.Social social() { return social; }
    public gg.aetherfall.core.module.Staff staff() { return staff; }
    public gg.aetherfall.core.module.BedrockMenus bedrock() { return bedrock; }
    public gg.aetherfall.core.module.PackServer packServer() { return packServer; }
    public gg.aetherfall.core.module.CropBooster cropBooster() { return cropBooster; }
    public gg.aetherfall.core.items.AdminItemMenu adminItems() { return adminItems; }
    public gg.aetherfall.core.items.SalvageMenu salvage() { return salvage; }
    public gg.aetherfall.core.module.CustomEnchantBooks customEnchantBooks() { return customEnchantBooks; }
}
