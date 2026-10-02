package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.economy.Eco;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Native Bedrock forms for players joining through Geyser/Floodgate (phones, consoles, Windows).
 * Isolated so AetherCore still loads without Floodgate. Deep screens reuse the chest menus, which work over Geyser.
 */
public final class BedrockMenus {
    private record Entry(String label, String icon, Consumer<Player> action) {}

    private final AetherCore plugin;

    public BedrockMenus(AetherCore plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrock(Player p) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    public void openMain(Player p) {
        PlayerData d = plugin.data().get(p);
        List<Entry> entries = new ArrayList<>();
        boolean daily = d != null && plugin.daily().canClaim(d);
        entries.add(new Entry((daily ? "§6★ " : "") + "Daily Rewards" + (daily ? " §6(ready!)" : ""), "textures/blocks/chest_front", pl -> plugin.daily().open(pl)));
        entries.add(new Entry("Daily Quests" + (d != null ? " §8(" + plugin.quests().completedToday(d) + "/" + d.quests.size() + ")" : ""),
                "textures/items/book_writable", pl -> plugin.quests().open(pl)));
        entries.add(new Entry("Weekly Contracts", "textures/items/clock_item", pl -> plugin.weekly().open(pl)));
        entries.add(new Entry("Bazaar §8(materials)", "textures/items/gold_ingot", pl -> plugin.bazaar().open(pl)));
        entries.add(new Entry("Auction House §8(gear)", "textures/items/gold_horse_armor", pl -> plugin.auctionMenus().openHub(pl)));
        entries.add(new Entry("Shop", "textures/items/emerald", pl -> plugin.shop().open(pl)));
        entries.add(new Entry("Aether Forge", "textures/blocks/anvil_top_damaged_0", pl -> plugin.forge().open(pl)));
        entries.add(new Entry("Skills", "textures/items/experience_bottle", pl -> pl.performCommand("skills")));
        entries.add(new Entry("Ranks & Perks", "textures/items/name_tag", pl -> plugin.menus().openRanks(pl)));
        if (plugin.kits() != null) entries.add(new Entry("Rank Kits", "textures/blocks/chest_front", pl -> plugin.kits().open(pl)));
        entries.add(new Entry("Tutorial", "textures/items/map_filled", pl -> pl.performCommand("tutorial")));
        entries.add(new Entry("Party & Friends", "textures/items/cake", this::openSocial));
        entries.add(new Entry("Leaderboards", "textures/blocks/gold_block", pl -> plugin.menus().openTop(pl)));
        entries.add(new Entry("Travel", "textures/items/compass_item", this::openTravel));
        entries.add(new Entry("Vote for rewards", "textures/items/diamond", pl -> plugin.votes().showSites(pl)));
        entries.add(new Entry("§7Classic menu", "textures/blocks/crafting_table_front", pl -> plugin.menus().openMain(pl, true)));

        String content = "§7Welcome, §f" + p.getName() + "§7!\n§7Coins: §6" + Eco.fmt(Eco.balance(p))
                + (d != null ? "\n§7Playtime: §e" + Text.duration(d.playtime) + "  §7Streak: §6" + plugin.daily().effectiveStreak(d) + " days" : "");
        send(p, "§5§lAetherfall", content, entries);
    }

    private void openSocial(Player p) {
        List<Entry> entries = List.of(
                new Entry("My party", "textures/items/cake", pl -> pl.performCommand("party list")),
                new Entry("My friends", "textures/items/name_tag", pl -> pl.performCommand("friend list")),
                new Entry("Leave party", "textures/items/door_wood", pl -> pl.performCommand("party leave")),
                new Entry("§7Back", "textures/items/arrow", this::openMain));
        send(p, "§dParty & Friends", "§7Invite with §e/party invite <name>§7 and §e/friend add <name>§7.\n§7Party chat: §e/pc <message>", entries);
    }

    private void openTravel(Player p) {
        List<Entry> entries = List.of(
                new Entry("Spawn", "textures/items/compass_item", pl -> pl.performCommand("spawn")),
                new Entry("Random wilderness", "textures/items/ender_pearl", pl -> pl.performCommand("rtp")),
                new Entry("Home", "textures/items/bed_red", pl -> pl.performCommand("home")),
                new Entry("Bazaar Hall", "textures/items/gold_ingot", pl -> pl.performCommand("warp bazaar")),
                new Entry("Aether Forge", "textures/blocks/anvil_top_damaged_0", pl -> pl.performCommand("warp forge")),
                new Entry("Boss Arena", "textures/items/netherite_sword", pl -> pl.performCommand("warp arena")),
                new Entry("§7Back", "textures/items/arrow", this::openMain));
        send(p, "§bTravel", "§7Where to?", entries);
    }

    private void send(Player p, String title, String content, List<Entry> entries) {
        SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
        for (Entry e : entries) form.button(e.label, FormImage.Type.PATH, e.icon);
        form.validResultHandler(response -> {
            int id = response.clickedButtonId();
            if (id < 0 || id >= entries.size()) return;
            // Form responses arrive off the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) entries.get(id).action.accept(p); });
        });
        FloodgateApi.getInstance().sendForm(p.getUniqueId(), form);
    }
}
