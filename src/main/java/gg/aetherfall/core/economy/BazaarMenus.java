package gg.aetherfall.core.economy;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.economy.Bazaar.Node;
import gg.aetherfall.core.economy.Bazaar.Order;
import gg.aetherfall.core.economy.Bazaar.Side;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Hypixel-style Bazaar screens: Category → Group → Sub-group … → Product, each category
 * framed in its own colour, with a toolbar for search, orders and quick selling.
 */
public final class BazaarMenus {
    private final AetherCore plugin;
    private final Bazaar bz;
    /** Where each player was browsing, so "Back" from a product returns there. */
    private final Map<UUID, Node> lastNode = new HashMap<>();

    BazaarMenus(AetherCore plugin, Bazaar bz) {
        this.plugin = plugin;
        this.bz = bz;
    }

    private String name(String product) {
        return plugin.items().displayName(product);
    }

    private ItemStack icon(String product, List<String> extraLore) {
        ItemStack s = plugin.items().stack(product, 1);
        ItemMeta meta = s.getItemMeta();
        meta.displayName(Text.item(name(product), Map.of()));
        List<Component> lore = new ArrayList<>();
        for (String l : extraLore) lore.add(Text.item(l, Map.of()));
        meta.lore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        s.setItemMeta(meta);
        return s;
    }

    private List<String> marketLore(String product) {
        double sell = bz.bestSell(product), buy = bz.bestBuy(product);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Buy Price: " + (sell > 0 ? "<gold>" + Eco.fmt(sell) + " coins" : "<dark_gray>No offers"));
        lore.add("<gray>Sell Price: " + (buy > 0 ? "<gold>" + Eco.fmt(buy) + " coins" : "<dark_gray>No orders"));
        lore.add("");
        lore.add("<dark_gray>" + Eco.fmt(bz.volume(product, Side.SELL)) + " for sale · " + Eco.fmt(bz.volume(product, Side.BUY)) + " wanted");
        return lore;
    }

    private Node node(Node root, List<String> path) {
        Node n = root;
        for (String id : path) {
            Node c = n.children.get(id);
            if (c == null) break;
            n = c;
        }
        return n;
    }

    // ── category / group screens ──────────────────────────────

    public void openHome(Player p) {
        Node last = lastNode.get(p.getUniqueId());
        if (last != null) openNode(p, last);
        else if (!bz.categories().isEmpty()) openNode(p, bz.categories().values().iterator().next());
    }

    public void openNode(Player p, Node node) {
        lastNode.put(p.getUniqueId(), node);
        Node root = node.root();
        // Breadcrumb: "Bazaar ➜ Farming" at the top level, "Parent ➜ Group" deeper down.
        String title = "<dark_gray>" + (node.parent == null ? "Bazaar" : node.parent.name) + " ➜ " + node.name;
        Menu menu = new Menu(6, Text.mm(title));
        tabs(menu, root);

        int i = 0;
        for (Node child : node.children.values()) {
            if (i >= Gui.GRID.length) break;
            int slot = Gui.GRID[i++];
            List<String> all = child.allProducts();
            List<String> lore = new ArrayList<>();
            int shown = 0;
            for (String prod : all) {
                if (shown++ >= 6) { lore.add("<dark_gray>… and " + (all.size() - 6) + " more"); break; }
                double sell = bz.bestSell(prod);
                lore.add("<dark_gray>▪ </dark_gray>" + name(prod) + (sell > 0 ? " <dark_gray>" + Eco.fmt(sell) : ""));
            }
            if (!child.children.isEmpty()) {
                lore.add("");
                lore.add("<gray>" + child.children.size() + " subcategories");
            }
            lore.add("");
            lore.add("<yellow>▶ Click to view");
            ItemStack stack = new ItemBuilder(child.icon).name(child.color + child.name).lore(lore).build();
            menu.set(slot, stack, (pl, c) -> {
                // A group with exactly one product and nothing below it opens the product directly.
                if (child.children.isEmpty() && child.products.size() == 1) openProduct(pl, child.products.getFirst());
                else openNode(pl, child);
            });
        }
        for (String product : node.products) {
            if (i >= Gui.GRID.length) break;
            int slot = Gui.GRID[i++];
            List<String> lore = new ArrayList<>(marketLore(product));
            lore.add("");
            lore.add("<yellow>▶ Click to view details");
            menu.set(slot, icon(product, lore), (pl, c) -> openProduct(pl, product));
        }
        toolbar(menu, p, node);
        Gui.frame(menu, root.pane);
        menu.open(p);
    }

    private void tabs(Menu menu, Node selectedRoot) {
        int t = 0;
        for (Node c : bz.categories().values()) {
            if (t >= Gui.TABS.length) break;
            boolean sel = c == selectedRoot;
            menu.set(Gui.TABS[t++], new ItemBuilder(c.icon).name(c.color + "<bold>" + c.name)
                    .lore(List.of("<gray>" + c.allProducts().size() + " products", "", sel ? "<green>Currently viewing" : "<yellow>▶ Click to view"))
                    .glow(sel).build(), (pl, cl) -> openNode(pl, c));
        }
    }

    private void toolbar(Menu menu, Player p, Node node) {
        menu.set(45, new ItemBuilder(Material.GOLDEN_HORSE_ARMOR).name("<gold><bold>Auction House")
                .lore(List.of("<gray>Gear and unique items are", "<gray>traded in the Auction House.", "", "<yellow>▶ Click to open")).build(),
                (pl, c) -> plugin.auctionMenus().openHub(pl));
        menu.set(46, new ItemBuilder(Material.EMERALD).name("<green><bold>NPC Shop")
                .lore(List.of("<gray>Fixed prices for basics.", "", "<yellow>▶ Click to open")).build(), (pl, c) -> plugin.shop().open(pl));
        menu.set(47, new ItemBuilder(Material.OAK_SIGN).name("<green><bold>Search")
                .lore(List.of("<gray>Find any product by name.", "", "<yellow>▶ Click to search")).build(),
                (pl, c) -> plugin.chatPrompt().ask(pl, "Search the Bazaar for:", q -> openSearch(pl, q)));
        if (node != null && node.parent != null) {
            Node parent = node.parent;
            menu.set(48, new ItemBuilder(Material.ARROW).name("<green>Go Back").lore(List.of("<gray>To " + parent.name)).build(), (pl, c) -> openNode(pl, parent));
        }
        menu.set(49, new ItemBuilder(Material.BARRIER).name("<red>Close").build(), (pl, c) -> pl.closeInventory());
        int mine = bz.ordersOf(p.getUniqueId()).size();
        int claim = 0;
        for (Order o : bz.ordersOf(p.getUniqueId())) claim += o.claimable();
        menu.set(50, new ItemBuilder(Material.BOOK).name("<green><bold>Manage Orders")
                .lore(List.of("<gray>Open orders: <white>" + mine, claim > 0 ? "<green>" + claim + " items ready to claim!" : "<dark_gray>Nothing to claim", "", "<yellow>▶ Click to manage"))
                .glow(claim > 0).build(), (pl, c) -> openOrders(pl));
        menu.set(51, new ItemBuilder(Material.CHEST).name("<green><bold>Sell Inventory Now")
                .lore(List.of("<gray>Instantly sells every Bazaar", "<gray>material in your inventory to", "<gray>the best buy orders.", "", "<yellow>▶ Click to sell")).build(),
                (pl, c) -> { bz.sellInventory(pl); openHome(pl); });
        menu.set(53, new ItemBuilder(Material.GOLD_NUGGET).name("<gold>Purse: " + Eco.fmt(Eco.balance(p)) + " coins")
                .lore(List.of("<gray>Your sales tax: <gold>" + Eco.fmt(bz.taxFor(p) * 100) + "%", "<dark_gray>Higher ranks pay less tax.")).build());
    }

    public void openSearch(Player p, String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<String> hits = new ArrayList<>();
        for (String product : bz.products()) {
            String plain = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(name(product)).toLowerCase(Locale.ROOT);
            if (plain.contains(q) || product.toLowerCase(Locale.ROOT).contains(q)) hits.add(product);
        }
        Menu menu = new Menu(6, Text.mm("<dark_gray>Bazaar ➜ Search: " + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(query)));
        tabs(menu, null);
        int i = 0;
        for (String product : hits) {
            if (i >= Gui.GRID.length) break;
            List<String> lore = new ArrayList<>(marketLore(product));
            lore.add("");
            lore.add("<yellow>▶ Click to view details");
            menu.set(Gui.GRID[i++], icon(product, lore), (pl, c) -> openProduct(pl, product));
        }
        if (hits.isEmpty()) menu.set(22, new ItemBuilder(Material.BARRIER).name("<red>No products match \"" + query.replace("<", "") + "\"").build());
        toolbar(menu, p, null);
        menu.set(48, new ItemBuilder(Material.ARROW).name("<green>Go Back").build(), (pl, c) -> openHome(pl));
        Gui.frame(menu, Material.LIME_STAINED_GLASS_PANE);
        menu.open(p);
    }

    // ── product screen ────────────────────────────────────────

    public void openProduct(Player p, String product) {
        bz.refreshAsync(() -> { if (p.isOnline()) openProductView(p, product); });
    }

    private void openProductView(Player p, String product) {
        Node home = bz.homeOf(product);
        Material pane = home == null ? Material.GRAY_STAINED_GLASS_PANE : home.root().pane;
        String crumb = home == null ? "Bazaar" : home.root().name + (home.parent == null ? "" : " ➜ " + home.name);
        Menu menu = new Menu(4, Text.mm("<dark_gray>" + crumb + " ➜ ").append(Text.mm(name(product))));
        List<String> info = new ArrayList<>(marketLore(product));
        info.add("");
        info.add("<gold>Top Sell Offers:");
        List<Order> sells = bz.top(product, Side.SELL, 5);
        for (Order o : sells) info.add("<dark_gray>▪ <gold>" + Eco.fmt(o.price) + " <gray>each · <white>" + o.remaining() + "<gray>x");
        if (sells.isEmpty()) info.add("<dark_gray>  none");
        info.add("<gold>Top Buy Orders:");
        List<Order> buys = bz.top(product, Side.BUY, 5);
        for (Order o : buys) info.add("<dark_gray>▪ <gold>" + Eco.fmt(o.price) + " <gray>each · <white>" + o.remaining() + "<gray>x");
        if (buys.isEmpty()) info.add("<dark_gray>  none");
        double npc = plugin.shop().sellPrice(product);
        if (npc > 0) { info.add(""); info.add("<dark_gray>NPC sell price: " + Eco.fmt(npc)); }
        menu.set(13, icon(product, info));

        int have = plugin.items().count(p, product);
        double sellAll = bz.quoteSell(product, have) * (1 - bz.taxFor(p));
        double unit = bz.bestSell(product);
        menu.set(10, new ItemBuilder(Material.GOLDEN_HORSE_ARMOR).name("<green><bold>Buy Instantly")
                .lore(List.of("<gray>Price per unit: " + (unit > 0 ? "<gold>" + Eco.fmt(unit) + " coins" : "<dark_gray>no offers"),
                        "<gray>Stack price: " + (unit > 0 ? "<gold>" + Eco.fmt(bz.quoteBuy(product, 64) < 0 ? unit * 64 : bz.quoteBuy(product, 64)) + " coins" : "<dark_gray>—"),
                        "", "<yellow>▶ Click to pick an amount")).build(),
                (pl, cl) -> pickAmount(pl, product, true, amt -> { bz.instantBuy(pl, product, amt); openProduct(pl, product); }));
        menu.set(11, new ItemBuilder(Material.HOPPER).name("<gold><bold>Sell Instantly")
                .lore(List.of("<gray>Inventory: <white>" + have + " items", "<gray>You earn: <gold>" + Eco.fmt(sellAll) + " coins",
                        "<dark_gray>(after tax, limited by demand)", "", have > 0 ? "<yellow>▶ Click to sell all" : "<dark_gray>You have none to sell")).build(),
                (pl, cl) -> { bz.instantSell(pl, product, Integer.MAX_VALUE); openProduct(pl, product); });
        menu.set(15, new ItemBuilder(Material.FILLED_MAP).name("<green><bold>Create Buy Order")
                .lore(List.of("<gray>Pre-pay coins; sellers fill", "<gray>your order over time.", "", "<yellow>▶ Click to set up")).build(),
                (pl, cl) -> pickAmount(pl, product, false, amt -> pickPrice(pl, product, Side.BUY, amt)));
        menu.set(16, new ItemBuilder(Material.MAP).name("<gold><bold>Create Sell Offer")
                .lore(List.of("<gray>List your items at your price.", "<gray>Inventory: <white>" + have + " items", "",
                        "<yellow>Left-click</yellow> <gray>list all", "<yellow>Right-click</yellow> <gray>choose amount")).build(),
                (pl, cl) -> {
                    int n = plugin.items().count(pl, product);
                    if (n <= 0) { Bazaar.fail(pl, "You don't have any of that to sell."); return; }
                    if (cl.isRightClick()) {
                        plugin.chatPrompt().ask(pl, "How many do you want to list? (you have " + n + ")", s -> {
                            double v = ChatPrompt.parseNumber(s);
                            if (Double.isNaN(v)) { Bazaar.fail(pl, "That's not a number."); return; }
                            pickPrice(pl, product, Side.SELL, (int) Math.min(v, n));
                        });
                    } else {
                        pickPrice(pl, product, Side.SELL, n);
                    }
                });
        menu.set(31, new ItemBuilder(Material.ARROW).name("<green>Go Back").lore(List.of("<gray>To " + (home == null ? "Bazaar" : home.name))).build(),
                (pl, cl) -> { if (home != null) openNode(pl, home); else openHome(pl); });
        menu.set(32, new ItemBuilder(Material.BOOK).name("<green>Manage Orders").build(), (pl, cl) -> openOrders(pl));
        Gui.frame(menu, pane);
        menu.open(p);
    }

    private Material paneOf(String product) {
        Node home = bz.homeOf(product);
        return home == null ? Material.GRAY_STAINED_GLASS_PANE : home.root().pane;
    }

    private void pickAmount(Player p, String product, boolean instant, IntConsumer then) {
        Menu menu = new Menu(4, Text.mm("<dark_gray>" + (instant ? "Buy Instantly" : "Buy Order") + " ➜ ").append(Text.mm(name(product))));
        int[] amounts = {1, 16, 64, 256, 1024};
        int[] slots = {11, 12, 13, 14, 15};
        for (int i = 0; i < amounts.length; i++) {
            int amt = amounts[i];
            List<String> lore = new ArrayList<>();
            if (instant) {
                double q = bz.quoteBuy(product, amt);
                lore.add(q < 0 ? "<red>Not enough for sale" : "<gray>Total: <gold>" + Eco.fmt(q) + " coins");
            }
            lore.add("");
            lore.add("<yellow>▶ Click to choose");
            ItemStack s = plugin.items().stack(product, Math.min(amt, 64));
            ItemMeta meta = s.getItemMeta();
            meta.displayName(Text.item("<green><bold>" + amt + "x", Map.of()));
            meta.lore(Text.lore(lore, Map.of()));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            s.setItemMeta(meta);
            menu.set(slots[i], s, (pl, cl) -> then.accept(amt));
        }
        menu.set(22, new ItemBuilder(Material.OAK_SIGN).name("<yellow><bold>Custom Amount").lore(List.of("<gray>Type any amount in chat.")).build(),
                (pl, cl) -> plugin.chatPrompt().ask(pl, "How many?", s -> {
                    double v = ChatPrompt.parseNumber(s);
                    if (Double.isNaN(v)) { Bazaar.fail(pl, "That's not a number."); return; }
                    then.accept((int) Math.min(v, 1_000_000));
                }));
        menu.set(31, new ItemBuilder(Material.ARROW).name("<green>Go Back").build(), (pl, cl) -> openProduct(pl, product));
        Gui.frame(menu, paneOf(product));
        menu.open(p);
    }

    private void pickPrice(Player p, String product, Side side, int amount) {
        Menu menu = new Menu(4, Text.mm("<dark_gray>" + (side == Side.BUY ? "Buy Order" : "Sell Offer") + " ➜ Price"));
        double best = side == Side.BUY ? bz.bestBuy(product) : bz.bestSell(product);
        double npc = plugin.shop().sellPrice(product);
        double fallback = side == Side.BUY ? Math.max(0.1, npc) : Math.max(0.1, npc * 2);
        double same = best > 0 ? best : fallback;
        double better = best > 0 ? (side == Side.BUY ? best + 0.1 : Math.max(0.1, best - 0.1)) : fallback;
        String verb = side == Side.BUY ? "Top Order" : "Best Offer";
        addPrice(menu, 11, Material.GOLD_NUGGET, "Same as " + verb, same, side, product, amount);
        addPrice(menu, 13, Material.GOLD_INGOT, verb + (side == Side.BUY ? " +0.1" : " -0.1"), better, side, product, amount);
        menu.set(15, new ItemBuilder(Material.OAK_SIGN).name("<yellow><bold>Custom Price").lore(List.of("<gray>Type a price per unit in chat.")).build(),
                (pl, cl) -> plugin.chatPrompt().ask(pl, "Price per unit? (e.g. 12.5 or 1.2k)", s -> {
                    double v = ChatPrompt.parseNumber(s);
                    if (Double.isNaN(v)) { Bazaar.fail(pl, "That's not a number."); return; }
                    place(pl, product, side, amount, v);
                }));
        menu.set(31, new ItemBuilder(Material.ARROW).name("<green>Go Back").build(), (pl, cl) -> openProduct(pl, product));
        Gui.frame(menu, paneOf(product));
        menu.open(p);
    }

    private void addPrice(Menu menu, int slot, Material icon, String label, double price, Side side, String product, int amount) {
        double total = price * amount;
        menu.set(slot, new ItemBuilder(icon).name("<green><bold>" + label).lore(List.of(
                "<gray>Price per unit: <gold>" + Eco.fmt(price) + " coins",
                "<gray>Amount: <white>" + amount + "x",
                side == Side.BUY ? "<gray>Total: <gold>" + Eco.fmt(total) + " coins" : "<gray>You'd earn: <gold>" + Eco.fmt(total * (1 - bz.tax())) + " coins",
                "", "<yellow>▶ Click to confirm")).build(), (pl, cl) -> place(pl, product, side, amount, price));
    }

    private void place(Player p, String product, Side side, int amount, double price) {
        boolean ok = side == Side.BUY ? bz.createBuyOrder(p, product, amount, price) : bz.createSellOffer(p, product, amount, price);
        if (ok) openProduct(p, product);
    }

    // ── manage orders ─────────────────────────────────────────

    public void openOrders(Player p) {
        bz.refreshAsync(() -> { if (p.isOnline()) openOrdersView(p); });
    }

    private void openOrdersView(Player p) {
        Menu menu = new Menu(6, Text.mm("<dark_gray>Bazaar ➜ Your Orders"));
        int i = 0;
        for (Order o : bz.ordersOf(p.getUniqueId())) {
            if (i >= Gui.GRID.length) break;
            List<String> lore = new ArrayList<>();
            lore.add(o.side == Side.BUY ? "<green><bold>BUY ORDER" : "<gold><bold>SELL OFFER");
            lore.add("<gray>Price per unit: <gold>" + Eco.fmt(o.price) + " coins");
            lore.add("<gray>Filled: <white>" + o.filled + "<gray>/<white>" + o.amount + (o.filled >= o.amount ? " <green>✔ 100%" : " <gray>(" + (o.filled * 100 / Math.max(1, o.amount)) + "%)"));
            if (o.side == Side.BUY && o.claimable() > 0) lore.add("<green>" + o.claimable() + " items ready to claim!");
            lore.add("");
            if (o.side == Side.BUY) lore.add("<yellow>Left-click</yellow> <gray>to claim");
            lore.add("<yellow>Right-click</yellow> <gray>to cancel " + (o.side == Side.BUY ? "(refunds coins)" : "(returns items)"));
            ItemStack s = icon(o.product, lore);
            if (o.claimable() > 0) {
                ItemMeta m = s.getItemMeta();
                m.setEnchantmentGlintOverride(true);
                s.setItemMeta(m);
            }
            menu.set(Gui.GRID[i++], s, (pl, cl) -> {
                if (cl.isRightClick()) bz.cancel(pl, o);
                else if (o.side == Side.BUY) bz.claim(pl, o);
                openOrders(pl);
            });
        }
        if (i == 0) menu.set(22, new ItemBuilder(Material.PAPER).name("<gray>No open orders")
                .lore(List.of("<gray>Open any product to place", "<gray>a buy order or sell offer.")).build());
        menu.set(48, new ItemBuilder(Material.ARROW).name("<green>Go Back").build(), (pl, cl) -> openHome(pl));
        menu.set(49, new ItemBuilder(Material.BARRIER).name("<red>Close").build(), (pl, cl) -> pl.closeInventory());
        Gui.frame(menu, Material.LIME_STAINED_GLASS_PANE);
        menu.open(p);
    }
}
