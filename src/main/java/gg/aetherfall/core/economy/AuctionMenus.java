package gg.aetherfall.core.economy;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.economy.AuctionHouse.Auction;
import gg.aetherfall.core.economy.AuctionHouse.Category;
import gg.aetherfall.core.economy.AuctionHouse.Filter;
import gg.aetherfall.core.economy.AuctionHouse.Sort;
import gg.aetherfall.core.economy.AuctionHouse.State;
import gg.aetherfall.core.util.Gui;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Auction House screens: browser, inspect, create, manage and bids. */
public final class AuctionMenus {
    /** Per-player browsing and listing state, kept while they click around. */
    private static final class View {
        Category category = Category.WEAPONS;
        Sort sort = Sort.LOWEST_PRICE;
        Filter filter = Filter.ALL;
        String sub;
        gg.aetherfall.core.items.CustomItem.Rarity rarity;
        String query;
        int page;
        int slot = -1;
        ItemStack item;
        boolean bin = true;
        double price;
        int duration = 3; // index into DURATIONS_H (24h)
    }

    private final AetherCore plugin;
    private final AuctionHouse ah;
    private final Map<UUID, View> views = new HashMap<>();

    public AuctionMenus(AetherCore plugin, AuctionHouse ah) {
        this.plugin = plugin;
        this.ah = ah;
    }

    private View view(Player p) {
        return views.computeIfAbsent(p.getUniqueId(), k -> new View());
    }

    // ── hub ──────────────────────────────────────────────────

    public void openHub(Player p) {
        p.sendActionBar(Text.mm("<gray>Loading shared Auction House listings…"));
        ah.refreshAsync(() -> { if (p.isOnline()) openHubLoaded(p); });
    }

    private void openHubLoaded(Player p) {
        Menu menu = new Menu(3, Text.mm("<dark_gray>Auction House"));
        menu.set(10, new ItemBuilder(Material.GOLD_BLOCK).name("<gold><bold>Browse Auctions")
                .lore(List.of("<gray>Find weapons, armor, tools and", "<gray>rare items listed by players.", "", "<yellow>▶ Click to browse")).build(),
                (pl, c) -> { view(pl).page = 0; openBrowser(pl); });
        int bids = ah.bidderAuctions(p.getUniqueId()).size();
        menu.set(12, new ItemBuilder(Material.GOLDEN_CARROT).name("<yellow><bold>View Bids")
                .lore(List.of("<gray>Auctions you're winning, and", "<gray>items you've won to collect.", "", "<gray>Active: <white>" + bids, "", "<yellow>▶ Click to view"))
                .glow(bids > 0).build(), (pl, c) -> openBids(pl));
        int mine = ah.sellerAuctions(p.getUniqueId()).size();
        menu.set(14, new ItemBuilder(Material.GOLDEN_HORSE_ARMOR).name("<green><bold>Manage Auctions")
                .lore(List.of("<gray>Your listings, cancellations", "<gray>and unsold items.", "", "<gray>Listings: <white>" + mine, "", "<yellow>▶ Click to manage"))
                .build(), (pl, c) -> openManage(pl));
        menu.set(16, new ItemBuilder(Material.GOLDEN_HOE).name("<aqua><bold>Create Auction")
                .lore(List.of("<gray>Sell gear and unique items.", "<gray>Buy-It-Now or bidding auctions.", "",
                        "<dark_gray>Your listing fee " + Eco.fmt(Math.min(AuctionHouse.LISTING_FEE, plugin.perks().of(p).ahFee()) * 100) + "% · Sale tax " + Eco.fmt(AuctionHouse.TAX * 100) + "%",
                        "<dark_gray>Materials go on the Bazaar.", "", "<yellow>▶ Click to create")).build(), (pl, c) -> openCreate(pl));
        menu.set(18, new ItemBuilder(Material.ARROW).name("<gray>← Menu").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.set(26, new ItemBuilder(Material.GOLD_INGOT).name("<gold>Bazaar").lore(List.of("<gray>Trade materials instead.")).build(), (pl, c) -> plugin.bazaar().open(pl));
        Gui.frame(menu, Material.ORANGE_STAINED_GLASS_PANE);
        menu.open(p);
    }

    // ── browser ──────────────────────────────────────────────

    private ItemStack listing(Auction a, List<String> extra) {
        ItemStack s = a.item.clone();
        ItemMeta meta = s.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Text.item("<dark_gray>" + "-".repeat(24), Map.of()));
        lore.add(Text.item("<gray>Seller: <white>{s}", Map.of("s", a.sellerName)));
        if (a.bin) {
            lore.add(Text.item("<gray>Buy it now: <gold>" + Eco.fmt(a.price) + " coins", Map.of()));
        } else if (a.bids == 0) {
            lore.add(Text.item("<gray>Starting bid: <gold>" + Eco.fmt(a.price) + " coins", Map.of()));
        } else {
            lore.add(Text.item("<gray>Top bid: <gold>" + Eco.fmt(a.topBid) + " coins <dark_gray>(" + a.bids + " bids)", Map.of()));
            lore.add(Text.item("<gray>Bidder: <white>{b}", Map.of("b", a.topBidderName == null ? "?" : a.topBidderName)));
        }
        long left = Math.max(0, (a.ends - System.currentTimeMillis()) / 1000);
        lore.add(Text.item(a.state == State.ACTIVE ? "<gray>Ends in: <yellow>" + Text.duration(left) + (left < 60 ? " " + left + "s" : "") : "<red>Ended", Map.of()));
        for (String l : extra) lore.add(Text.item(l, Map.of()));
        meta.lore(lore);
        s.setItemMeta(meta);
        return s;
    }

    public void openBrowser(Player p) {
        View v = view(p);
        List<Auction> list = ah.browse(v.category, v.sub, v.rarity, v.sort, v.filter, v.query);
        int per = Gui.GRID.length;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        v.page = Math.max(0, Math.min(v.page, pages - 1));
        String crumb = v.category.label + (v.sub == null ? "" : " ➜ " + v.sub);
        Menu menu = new Menu(6, Text.mm("<dark_gray>Auctions ➜ " + crumb + (pages > 1 ? " (" + (v.page + 1) + "/" + pages + ")" : "")));
        // Category tabs down the left.
        int t = 0;
        for (Category c : Category.values()) {
            boolean sel = c == v.category;
            menu.set(t++ * 9, new ItemBuilder(c.icon).name(c.color + "<bold>" + c.label)
                    .lore(List.of("<gray>" + String.join(", ", c.subs), "", sel ? "<green>Currently viewing" : "<yellow>▶ Click to view")).glow(sel).build(),
                    (pl, cl) -> { View vv = view(pl); vv.category = c; vv.sub = null; vv.page = 0; openBrowser(pl); });
        }
        // Sub-category chips across the top.
        List<String> chips = new ArrayList<>();
        chips.add(null);
        chips.addAll(v.category.subs);
        for (int i = 0; i < chips.size() && i < 7; i++) {
            String sub = chips.get(i);
            boolean sel = java.util.Objects.equals(sub, v.sub);
            menu.set(2 + i, new ItemBuilder(sel ? Material.LIME_DYE : Material.GRAY_DYE).name((sel ? "<green><bold>" : "<gray>") + (sub == null ? "All " + v.category.label : sub))
                    .lore(List.of(sel ? "<green>Selected" : "<yellow>▶ Click to filter")).build(),
                    (pl, cl) -> { View vv = view(pl); vv.sub = sub; vv.page = 0; openBrowser(pl); });
        }
        for (int i = 0; i < per && v.page * per + i < list.size(); i++) {
            Auction a = list.get(v.page * per + i);
            menu.set(Gui.GRID[i], listing(a, List.of("", "<yellow>▶ Click to inspect")), (pl, cl) -> openInspect(pl, a.id));
        }
        if (list.isEmpty()) menu.set(22, new ItemBuilder(Material.BARRIER).name("<red>No auctions found")
                .lore(List.of("<gray>Try another category or filter,", "<gray>or be the first to list one!")).build());
        menu.set(46, new ItemBuilder(Material.ENDER_EYE).name("<light_purple><bold>Rarity: " + (v.rarity == null ? "Any" : nice(v.rarity.name())))
                .lore(List.of("<gray>Click to cycle rarity", "<gray>Right-click to reset")).build(), (pl, cl) -> {
            View vv = view(pl);
            var values = gg.aetherfall.core.items.CustomItem.Rarity.values();
            if (cl.isRightClick()) vv.rarity = null;
            else vv.rarity = vv.rarity == null ? values[0] : (vv.rarity.ordinal() + 1 < values.length ? values[vv.rarity.ordinal() + 1] : null);
            vv.page = 0;
            openBrowser(pl);
        });
        menu.set(47, new ItemBuilder(Material.HOPPER).name("<yellow><bold>Sort: " + nice(v.sort.name()))
                .lore(List.of("<gray>Click to change")).build(), (pl, cl) -> {
            View vv = view(pl);
            vv.sort = Sort.values()[(vv.sort.ordinal() + 1) % Sort.values().length];
            openBrowser(pl);
        });
        if (v.page > 0) menu.set(48, new ItemBuilder(Material.ARROW).name("<green>Previous Page").build(), (pl, cl) -> { view(pl).page--; openBrowser(pl); });
        menu.set(49, new ItemBuilder(Material.ARROW).name("<green>Go Back").lore(List.of("<gray>To Auction House")).build(), (pl, cl) -> openHub(pl));
        if (v.page < pages - 1) menu.set(50, new ItemBuilder(Material.ARROW).name("<green>Next Page").build(), (pl, cl) -> { view(pl).page++; openBrowser(pl); });
        menu.set(51, new ItemBuilder(Material.OAK_SIGN).name("<green><bold>Search")
                .lore(List.of(v.query == null ? "<gray>No filter" : "<gray>Filter: <white>" + v.query.replace("<", ""), "",
                        "<yellow>Left-click</yellow> <gray>to search", "<yellow>Right-click</yellow> <gray>to clear")).build(), (pl, cl) -> {
            if (cl.isRightClick()) { view(pl).query = null; openBrowser(pl); return; }
            plugin.chatPrompt().ask(pl, "Search auctions for:", q -> { view(pl).query = q; view(pl).page = 0; openBrowser(pl); });
        });
        menu.set(52, new ItemBuilder(Material.GOLD_BLOCK).name("<gold><bold>Show: " + (v.filter == Filter.ALL ? "All" : v.filter == Filter.BIN ? "BIN Only" : "Auctions Only"))
                .lore(List.of("<gray>Click to change")).build(), (pl, cl) -> {
            View vv = view(pl);
            vv.filter = Filter.values()[(vv.filter.ordinal() + 1) % Filter.values().length];
            openBrowser(pl);
        });
        menu.set(53, new ItemBuilder(Material.GOLD_NUGGET).name("<gold>Purse: " + Eco.fmt(Eco.balance(p)) + " coins").build());
        Gui.frame(menu, v.category.pane);
        menu.open(p);
    }

    private static String nice(String enumName) {
        return gg.aetherfall.core.items.ItemRegistry.prettify(enumName);
    }

    // ── inspect ──────────────────────────────────────────────

    public void openInspect(Player p, long id) {
        Auction a = ah.get(id);
        if (a == null || a.state != State.ACTIVE) { AuctionHouse.fail(p, "That auction has ended."); openBrowser(p); return; }
        Menu menu = new Menu(4, Text.mm("<dark_gray>" + (a.bin ? "BIN Auction" : "Auction")));
        menu.set(13, listing(a, List.of()));
        boolean own = a.seller.equals(p.getUniqueId());
        if (own) {
            menu.set(31, new ItemBuilder(a.bids > 0 ? Material.GRAY_DYE : Material.BARRIER).name(a.bids > 0 ? "<gray>Can't cancel (has bids)" : "<red><bold>Cancel Auction")
                    .lore(List.of("<gray>Returns the item to you.")).build(), (pl, cl) -> { ah.cancel(pl, a); openManage(pl); });
        } else if (a.bin) {
            menu.set(31, new ItemBuilder(Material.GOLD_NUGGET).name("<gold><bold>Buy Item Right Now")
                    .lore(List.of("<gray>Price: <gold>" + Eco.fmt(a.price) + " coins", "<gray>Your balance: <gold>" + Eco.fmt(Eco.balance(p)), "", "<yellow>▶ Click to buy")).build(),
                    (pl, cl) -> { ah.buyNow(pl, a); pl.closeInventory(); });
        } else {
            double min = a.minNextBid();
            menu.set(31, new ItemBuilder(Material.GOLD_INGOT).name("<gold><bold>Submit Bid")
                    .lore(List.of("<gray>Minimum bid: <gold>" + Eco.fmt(min) + " coins", "<gray>Your balance: <gold>" + Eco.fmt(Eco.balance(p)), "",
                            "<dark_gray>Coins are held until you're outbid", "<dark_gray>(then refunded instantly).", "",
                            "<yellow>Left-click</yellow> <gray>bid the minimum", "<yellow>Right-click</yellow> <gray>custom amount")).build(), (pl, cl) -> {
                if (cl.isRightClick()) {
                    plugin.chatPrompt().ask(pl, "Your bid (minimum " + Eco.fmt(min) + "):", s -> {
                        double v = ChatPrompt.parseNumber(s);
                        if (Double.isNaN(v)) { AuctionHouse.fail(pl, "That's not a number."); return; }
                        ah.bid(pl, a, v);
                        openInspect(pl, a.id);
                    });
                } else {
                    ah.bid(pl, a, min);
                    openInspect(pl, a.id);
                }
            });
        }
        menu.set(27, new ItemBuilder(Material.ARROW).name("<green>Go Back").build(), (pl, cl) -> openBrowser(pl));
        Gui.frame(menu, ah.categoryOf(a.item).pane);
        menu.open(p);
    }

    // ── create ───────────────────────────────────────────────

    public void openCreate(Player p) {
        View v = view(p);
        Menu menu = new Menu(5, Text.mm("<dark_gray>Create " + (v.bin ? "BIN Auction" : "Auction")));
        if (v.item == null) {
            menu.set(13, new ItemBuilder(Material.STONE_BUTTON).name("<yellow>Click an item in your inventory!")
                    .lore(List.of("<gray>Weapons, armor, tools, talismans,", "<gray>relics and other unique items.", "", "<dark_gray>Materials go on the Bazaar.")).build());
        } else {
            menu.set(13, v.item.clone(), (pl, cl) -> { view(pl).item = null; view(pl).slot = -1; openCreate(pl); });
        }
        menu.set(29, new ItemBuilder(v.bin ? Material.GOLD_NUGGET : Material.GOLD_INGOT).name("<gold><bold>Type: " + (v.bin ? "Buy It Now" : "Bidding Auction"))
                .lore(List.of(v.bin ? "<gray>Sold instantly to the first buyer." : "<gray>Highest bidder wins when time runs out.", "", "<yellow>▶ Click to switch")).build(),
                (pl, cl) -> { view(pl).bin = !view(pl).bin; openCreate(pl); });
        menu.set(31, new ItemBuilder(Material.POISONOUS_POTATO).name("<gold><bold>" + (v.bin ? "Price" : "Starting Bid") + ": "
                        + (v.price > 0 ? Eco.fmt(v.price) + " coins" : "<red>not set"))
                .lore(List.of("<gray>Listing fee: <gold>" + Eco.fmt(Math.max(1, Math.floor(v.price * Math.min(AuctionHouse.LISTING_FEE, plugin.perks().of(p).ahFee())))) + " coins", "", "<yellow>▶ Click to set")).build(),
                (pl, cl) -> plugin.chatPrompt().ask(pl, (view(pl).bin ? "Buy-It-Now price" : "Starting bid") + " in coins? (e.g. 2500 or 1.5k)", s -> {
                    double val = ChatPrompt.parseNumber(s);
                    if (Double.isNaN(val)) AuctionHouse.fail(pl, "That's not a number.");
                    else view(pl).price = Math.floor(val);
                    openCreate(pl);
                }));
        long hours = AuctionHouse.DURATIONS_H[v.duration];
        menu.set(33, new ItemBuilder(Material.CLOCK).name("<gold><bold>Duration: " + hours + "h")
                .lore(List.of("<yellow>▶ Click to change")).build(),
                (pl, cl) -> { View vv = view(pl); vv.duration = (vv.duration + 1) % AuctionHouse.DURATIONS_H.length; openCreate(pl); });
        boolean ready = v.item != null && v.price >= 1;
        menu.set(40, new ItemBuilder(ready ? Material.GREEN_TERRACOTTA : Material.RED_TERRACOTTA).name(ready ? "<green><bold>Create Auction" : "<red>Pick an item and set a price")
                .build(), (pl, cl) -> {
            View vv = view(pl);
            if (vv.item == null || vv.price < 1) return;
            if (ah.create(pl, vv.slot, vv.item, vv.bin, vv.price, AuctionHouse.DURATIONS_H[vv.duration])) {
                vv.item = null; vv.slot = -1; vv.price = 0;
                openManage(pl);
            }
        });
        menu.set(36, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (pl, cl) -> openHub(pl));
        Gui.frame(menu, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        menu.onBottomClick((pl, slot) -> {
            ItemStack clicked = pl.getInventory().getItem(slot);
            String reason = ah.rejectReason(clicked);
            if (reason != null) { AuctionHouse.fail(pl, reason); return; }
            View vv = view(pl);
            vv.item = clicked.clone();
            vv.slot = slot;
            openCreate(pl);
        });
        menu.open(p);
    }

    // ── manage & bids ────────────────────────────────────────

    public void openManage(Player p) {
        Menu menu = new Menu(6, Text.mm("<dark_gray>Your Auctions"));
        int slot = 0;
        for (Auction a : ah.sellerAuctions(p.getUniqueId())) {
            if (slot >= 45) break;
            List<String> extra = new ArrayList<>();
            extra.add("");
            if (a.state == State.EXPIRED) extra.add("<yellow>▶ Click to collect your item");
            else if (a.bids == 0) extra.add("<yellow>▶ Click to view · cancel");
            else extra.add("<yellow>▶ Click to view");
            menu.set(slot++, listing(a, extra), (pl, cl) -> {
                if (a.state == State.EXPIRED) { ah.claim(pl, a); openManage(pl); }
                else openInspect(pl, a.id);
            });
        }
        if (slot == 0) menu.set(22, new ItemBuilder(Material.PAPER).name("<gray>You have no auctions").build());
        menu.set(49, new ItemBuilder(Material.GOLDEN_HOE).name("<aqua><bold>Create Auction").build(), (pl, cl) -> openCreate(pl));
        menu.set(45, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (pl, cl) -> openHub(pl));
        Gui.frame(menu, Material.LIME_STAINED_GLASS_PANE);
        menu.open(p);
    }

    public void openBids(Player p) {
        Menu menu = new Menu(6, Text.mm("<dark_gray>Your Bids"));
        int slot = 0;
        for (Auction a : ah.bidderAuctions(p.getUniqueId())) {
            if (slot >= 45) break;
            boolean won = a.state == State.SOLD;
            menu.set(slot++, listing(a, List.of("", won ? "<green><bold>You won!</bold> <yellow>▶ Click to collect" : "<green>You're the top bidder")), (pl, cl) -> {
                if (a.state == State.SOLD) { ah.claim(pl, a); openBids(pl); }
                else openInspect(pl, a.id);
            });
        }
        if (slot == 0) menu.set(22, new ItemBuilder(Material.PAPER).name("<gray>No active bids").build());
        menu.set(45, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (pl, cl) -> openHub(pl));
        Gui.frame(menu, Material.LIME_STAINED_GLASS_PANE);
        menu.open(p);
    }
}
