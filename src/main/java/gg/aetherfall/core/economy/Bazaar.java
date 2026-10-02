package gg.aetherfall.core.economy;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player-driven order book. All matching happens on the main thread against in-memory books;
 * every change is persisted asynchronously. Coins for buy orders are escrowed up front and
 * items for sell offers are taken on listing, so trades can never bounce.
 */
public final class Bazaar {
    public enum Side { BUY, SELL }

    public static final class Order {
        public final long id;
        public final String product;
        public final Side side;
        public final UUID owner;
        public final String ownerName;
        public final double price;
        public final int amount;
        public int filled;
        public int claimed;   // BUY: items handed to the owner so far
        public double taxRate = -1; // SELL: owner's rank tax at listing time (-1 = server default)
        public final long created;

        Order(long id, String product, Side side, UUID owner, String ownerName, double price, int amount, int filled, int claimed, long created) {
            this.id = id; this.product = product; this.side = side; this.owner = owner; this.ownerName = ownerName;
            this.price = price; this.amount = amount; this.filled = filled; this.claimed = claimed; this.created = created;
        }

        public int remaining() { return amount - filled; }
        public int claimable() { return side == Side.BUY ? filled - claimed : 0; }
        public boolean done() { return side == Side.SELL ? filled >= amount : filled >= amount && claimed >= filled; }
    }

    /**
     * A category tree node. Top-level nodes are the main categories (with a colour + frame pane);
     * any node may hold products and/or child groups, nested to any depth (sub-sub-categories).
     */
    public static final class Node {
        public final String id;
        public final String name;
        public final Material icon;
        public final String color;
        public final Material pane;
        public final Node parent;
        public final List<String> products = new ArrayList<>();
        public final Map<String, Node> children = new LinkedHashMap<>();

        Node(String id, String name, Material icon, String color, Material pane, Node parent) {
            this.id = id; this.name = name; this.icon = icon; this.color = color; this.pane = pane; this.parent = parent;
        }

        public Node root() {
            Node n = this;
            while (n.parent != null) n = n.parent;
            return n;
        }

        public List<String> allProducts() {
            List<String> out = new ArrayList<>(products);
            for (Node c : children.values()) out.addAll(c.allProducts());
            return out;
        }

        /** Path of child ids from the root category to this node. */
        public List<String> path() {
            java.util.LinkedList<String> out = new java.util.LinkedList<>();
            for (Node n = this; n.parent != null; n = n.parent) out.addFirst(n.id);
            return out;
        }
    }

    private final AetherCore plugin;
    private final Map<String, Node> categories = new LinkedHashMap<>();
    private final Map<String, Node> productHome = new LinkedHashMap<>();
    private final BazaarMenus menus;
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final Map<String, List<Order>> sells = new HashMap<>();
    private final Map<String, List<Order>> buys = new HashMap<>();
    private final List<Runnable> refreshWaiters = new ArrayList<>();
    private boolean refreshInFlight;
    /** Incremented whenever this process mutates the in-memory book. */
    private long bookVersion;
    private long nextId = 1;
    private double tax;
    private int maxOrders;
    private int maxAmount;
    private double minPrice;
    private double maxPrice;

    public Bazaar(AetherCore plugin) {
        this.plugin = plugin;
        this.menus = new BazaarMenus(plugin, this);
        reload();
        load();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "bazaar.yml");
        if (!file.exists()) plugin.saveResource("bazaar.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        tax = yml.getDouble("tax", 0.0125);
        maxOrders = yml.getInt("max-orders", 21);
        maxAmount = yml.getInt("max-amount", 10240);
        minPrice = yml.getDouble("min-price", 0.1);
        maxPrice = yml.getDouble("max-price", 1e8);
        categories.clear();
        productHome.clear();
        ConfigurationSection cats = yml.getConfigurationSection("categories");
        if (cats == null) return;
        for (String id : cats.getKeys(false)) {
            ConfigurationSection c = cats.getConfigurationSection(id);
            Material pane = gg.aetherfall.core.util.ItemBuilder.material(c.getString("pane"), Material.GRAY_STAINED_GLASS_PANE);
            categories.put(id, parseNode(id, c, null, c.getString("color", "<white>"), pane));
        }
    }

    private Node parseNode(String id, ConfigurationSection s, Node parent, String color, Material pane) {
        Node n = new Node(id, s.getString("name", id), gg.aetherfall.core.util.ItemBuilder.material(s.getString("icon"), Material.CHEST),
                s.getString("color", color), pane, parent);
        for (String p : s.getStringList("products")) {
            if (!plugin.items().isCommodity(p)) {
                plugin.getLogger().warning("bazaar.yml: '" + p + "' is unknown or is gear (gear belongs in the Auction House)");
            } else if (productHome.containsKey(p)) {
                plugin.getLogger().warning("bazaar.yml: '" + p + "' is listed twice; keeping the first");
            } else {
                n.products.add(p);
                productHome.put(p, n);
            }
        }
        ConfigurationSection groups = s.getConfigurationSection("groups");
        if (groups != null) for (String g : groups.getKeys(false)) {
            n.children.put(g, parseNode(g, groups.getConfigurationSection(g), n, n.color, pane));
        }
        return n;
    }

    private void load() {
        plugin.data().sync(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bazaar_orders (
                      id INTEGER PRIMARY KEY, product TEXT NOT NULL, side TEXT NOT NULL, owner TEXT NOT NULL,
                      owner_name TEXT, price REAL NOT NULL, amount INTEGER NOT NULL, filled INTEGER NOT NULL DEFAULT 0,
                      claimed INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)""");
                try { st.executeUpdate("ALTER TABLE bazaar_orders ADD COLUMN tax REAL DEFAULT -1"); } catch (java.sql.SQLException ignored) { }
                try (ResultSet rs = st.executeQuery("SELECT * FROM bazaar_orders")) {
                    while (rs.next()) {
                        Order o = new Order(rs.getLong("id"), rs.getString("product"), Side.valueOf(rs.getString("side")),
                                UUID.fromString(rs.getString("owner")), rs.getString("owner_name"), rs.getDouble("price"),
                                rs.getInt("amount"), rs.getInt("filled"), rs.getInt("claimed"), rs.getLong("created"));
                        o.taxRate = rs.getDouble("tax");
                        index(o);
                        nextId = Math.max(nextId, o.id + 1);
                    }
                }
            }
            return null;
        });
        plugin.getLogger().info("Bazaar loaded " + orders.size() + " open orders.");
    }

    /**
     * Reloads the shared order book from SQLite before a player opens the Bazaar.
     * This keeps the menu correct when another server process or proxy-connected
     * instance created an order after this plugin was enabled.
     */
    public void refreshAsync(Runnable after) {
        refreshWaiters.add(after == null ? () -> {} : after);
        if (refreshInFlight) return;
        refreshInFlight = true;
        long requestedVersion = bookVersion;
        plugin.data().async(c -> {
            List<Order> loaded = new ArrayList<>();
            long loadedNextId = 1;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, product, side, owner, owner_name, price, amount, filled, claimed, created, tax FROM bazaar_orders")) {
                while (rs.next()) {
                    try {
                        String product = rs.getString("product");
                        Side side = Side.valueOf(rs.getString("side"));
                        int amount = rs.getInt("amount");
                        int filled = Math.max(0, Math.min(amount, rs.getInt("filled")));
                        int claimed = Math.max(0, Math.min(filled, rs.getInt("claimed")));
                        double price = rs.getDouble("price");
                        if (!isProduct(product) || !plugin.items().isCommodity(product) || amount <= 0 || !Double.isFinite(price) || price <= 0) continue;
                        Order o = new Order(rs.getLong("id"), product, side, UUID.fromString(rs.getString("owner")), rs.getString("owner_name"), price, amount, filled, claimed, rs.getLong("created"));
                        double loadedTax = rs.getDouble("tax");
                        o.taxRate = Double.isFinite(loadedTax) ? loadedTax : -1;
                        if (!o.done()) loaded.add(o);
                        loadedNextId = Math.max(loadedNextId, o.id + 1);
                    } catch (RuntimeException ignored) { }
                }
                final long next = loadedNextId;
                Bukkit.getScheduler().runTask(plugin, () -> finishRefresh(loaded, next, requestedVersion));
                return null;
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("Could not refresh shared Bazaar orders: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> finishRefresh(null, nextId, requestedVersion));
                return null;
            }
        });
    }

    private void finishRefresh(List<Order> loaded, long loadedNextId, long requestedVersion) {
        // A local mutation committed while the SELECT was running. Re-read instead of
        // replacing the live book with the older snapshot and hiding the new order.
        if (loaded != null && requestedVersion != bookVersion) {
            refreshInFlight = false;
            refreshAsync(null);
            return;
        }
        if (loaded != null) {
            orders.clear(); sells.clear(); buys.clear(); nextId = Math.max(1L, loadedNextId);
            for (Order o : loaded) index(o);
        }
        refreshInFlight = false;
        List<Runnable> callbacks = new ArrayList<>(refreshWaiters); refreshWaiters.clear();
        for (Runnable callback : callbacks) callback.run();
    }

    private void index(Order o) {
        orders.put(o.id, o);
        if (o.remaining() > 0) book(o.product, o.side).add(o);
        sortBook(o.product);
    }

    private List<Order> book(String product, Side side) {
        return (side == Side.SELL ? sells : buys).computeIfAbsent(product, k -> new ArrayList<>());
    }

    private void sortBook(String product) {
        book(product, Side.SELL).sort(Comparator.comparingDouble((Order o) -> o.price).thenComparingLong(o -> o.created));
        book(product, Side.BUY).sort(Comparator.comparingDouble((Order o) -> -o.price).thenComparingLong(o -> o.created));
    }

    /**
     * Commit order mutations before returning to the player. Bazaar is shared across
     * server processes, so an async write can let a friend refresh during the commit
     * window and see an empty book. The operation is still serialized on DataManager's
     * SQLite worker, but the caller does not report success until SQLite has committed.
     */
    private void persist(Order o) {
        bookVersion++;
        long id = o.id; String product = o.product; String side = o.side.name(); String owner = o.owner.toString();
        String name = o.ownerName; double price = o.price; int amount = o.amount, filled = o.filled, claimed = o.claimed; long created = o.created;
        boolean done = o.done();
        double taxRate = o.taxRate;
        try {
            plugin.data().sync(c -> {
            if (done) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM bazaar_orders WHERE id = ?")) {
                    ps.setLong(1, id);
                    return ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO bazaar_orders (id, product, side, owner, owner_name, price, amount, filled, claimed, created, tax)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET filled = excluded.filled, claimed = excluded.claimed""")) {
                ps.setLong(1, id); ps.setString(2, product); ps.setString(3, side); ps.setString(4, owner); ps.setString(5, name);
                ps.setDouble(6, price); ps.setInt(7, amount); ps.setInt(8, filled); ps.setInt(9, claimed); ps.setLong(10, created); ps.setDouble(11, taxRate);
                return ps.executeUpdate();
            }
            });
        } catch (IllegalStateException ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not durably persist Bazaar order " + id, ex);
        }
    }

    /** Random IDs avoid collisions when multiple server processes create orders concurrently. */
    private long allocateId() {
        long id;
        do { id = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE); }
        while (orders.containsKey(id));
        return id;
    }

    private void retire(Order o) {
        if (o.remaining() <= 0) book(o.product, o.side).remove(o);
        if (o.done()) orders.remove(o.id);
        persist(o);
    }

    // ── queries ───────────────────────────────────────────────

    public Map<String, Node> categories() { return categories; }

    /** The group a product lives in (for navigation). */
    public Node homeOf(String product) { return productHome.get(product); }

    public java.util.Set<String> products() { return productHome.keySet(); }
    public double tax() { return tax; }

    /** Lowest sell offer = what instant buyers pay. */
    public double bestSell(String product) {
        List<Order> b = book(product, Side.SELL);
        return b.isEmpty() ? 0 : b.getFirst().price;
    }

    /** Highest buy order = what instant sellers receive. */
    public double bestBuy(String product) {
        List<Order> b = book(product, Side.BUY);
        return b.isEmpty() ? 0 : b.getFirst().price;
    }

    public int volume(String product, Side side) {
        int n = 0;
        for (Order o : book(product, side)) n += o.remaining();
        return n;
    }

    public List<Order> top(String product, Side side, int n) {
        List<Order> b = book(product, side);
        return b.subList(0, Math.min(n, b.size()));
    }

    public List<Order> ordersOf(UUID owner) {
        List<Order> out = new ArrayList<>();
        for (Order o : orders.values()) if (o.owner.equals(owner)) out.add(o);
        return out;
    }

    /** Total coins for instantly buying `amount` (or -1 if not enough supply). */
    public double quoteBuy(String product, int amount) {
        double total = 0;
        int left = amount;
        for (Order o : book(product, Side.SELL)) {
            int q = Math.min(left, o.remaining());
            total += q * o.price;
            left -= q;
            if (left == 0) return total;
        }
        return -1;
    }

    /** Coins (before tax) for instantly selling `amount`, limited by demand. */
    public double quoteSell(String product, int amount) {
        double total = 0;
        int left = amount;
        for (Order o : book(product, Side.BUY)) {
            int q = Math.min(left, o.remaining());
            total += q * o.price;
            left -= q;
            if (left == 0) break;
        }
        return total;
    }

    // ── trading ───────────────────────────────────────────────

    public void instantBuy(Player p, String product, int amount) {
        amount = Math.min(amount, Math.min(volume(product, Side.SELL), plugin.items().space(p, product)));
        if (amount <= 0) {
            fail(p, volume(product, Side.SELL) == 0 ? "Nobody is selling that right now — place a buy order!" : "Your inventory is full.");
            return;
        }
        double cost = quoteBuy(product, amount);
        if (cost < 0 || !Eco.withdraw(p, cost)) {
            fail(p, "You need " + Eco.fmt(cost) + " coins for that.");
            return;
        }
        int left = amount;
        for (Order o : new ArrayList<>(book(product, Side.SELL))) {
            if (left == 0) break;
            int q = Math.min(left, o.remaining());
            o.filled += q;
            left -= q;
            payout(o, q * o.price);
            notifyOwner(o, q);
            retire(o);
        }
        plugin.items().give(p, product, amount);
        p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Bought <white>" + amount + "x</white> ").append(Text.mm(plugin.items().displayName(product)))
                .append(Text.mm(" <gray>for <gold>" + Eco.fmt(cost) + " coins<gray>.")));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
    }

    public void instantSell(Player p, String product, int amount) {
        if (!isProduct(product) || !plugin.items().isCommodity(product)) { fail(p, "That can't be sold on the Bazaar."); return; }
        int have = plugin.items().count(p, product);
        amount = Math.min(amount, Math.min(have, volume(product, Side.BUY)));
        if (amount <= 0) {
            fail(p, have == 0 ? "You don't have any of that." : "Nobody is buying that right now — create a sell offer!");
            return;
        }
        amount = plugin.items().remove(p, product, amount);
        int left = amount;
        double earned = 0;
        for (Order o : new ArrayList<>(book(product, Side.BUY))) {
            if (left == 0) break;
            int q = Math.min(left, o.remaining());
            o.filled += q;
            left -= q;
            earned += q * o.price;
            notifyOwner(o, q);
            retire(o);
        }
        double net = earned * (1 - taxFor(p));
        Eco.deposit(p, net);
        plugin.onboarding().complete(p, gg.aetherfall.core.module.Onboarding.Step.TRADE);
        p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Sold <white>" + amount + "x</white> ").append(Text.mm(plugin.items().displayName(product)))
                .append(Text.mm(" <gray>for <green>" + Eco.fmt(net) + " coins <dark_gray>(" + Eco.fmt(earned - net) + " tax)")));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.2f);
    }

    public boolean createBuyOrder(Player p, String product, int amount, double price) {
        price = Eco.round(price);
        if (!validate(p, amount, price)) return false;
        double cost = price * amount;
        if (!Eco.withdraw(p, cost)) {
            fail(p, "You need " + Eco.fmt(cost) + " coins to place that order.");
            return false;
        }
        Order o = new Order(allocateId(), product, Side.BUY, p.getUniqueId(), p.getName(), price, amount, 0, 0, System.currentTimeMillis());
        index(o);
        persist(o);
        p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Buy order placed: <white>" + amount + "x</white> ").append(Text.mm(plugin.items().displayName(product)))
                .append(Text.mm(" <gray>at <gold>" + Eco.fmt(price) + "</gold> each (<gold>" + Eco.fmt(cost) + "</gold> escrowed).")));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
        return true;
    }

    public boolean isProduct(String key) {
        return productHome.containsKey(key);
    }

    public boolean createSellOffer(Player p, String product, int amount, double price) {
        price = Eco.round(price);
        if (!isProduct(product) || !plugin.items().isCommodity(product)) { fail(p, "That can't be sold on the Bazaar — try the Auction House."); return false; }
        if (!validate(p, amount, price)) return false;
        if (plugin.items().count(p, product) < amount) {
            fail(p, "You don't have " + amount + " of that.");
            return false;
        }
        plugin.items().remove(p, product, amount);
        Order o = new Order(allocateId(), product, Side.SELL, p.getUniqueId(), p.getName(), price, amount, 0, 0, System.currentTimeMillis());
        o.taxRate = taxFor(p);
        index(o);
        persist(o);
        plugin.onboarding().complete(p, gg.aetherfall.core.module.Onboarding.Step.TRADE);
        p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Sell offer listed: <white>" + amount + "x</white> ").append(Text.mm(plugin.items().displayName(product)))
                .append(Text.mm(" <gray>at <gold>" + Eco.fmt(price) + "</gold> each.")));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
        return true;
    }

    private boolean validate(Player p, int amount, double price) {
        if (amount <= 0 || amount > maxAmount) { fail(p, "Amount must be between 1 and " + maxAmount + "."); return false; }
        if (price < minPrice || price > maxPrice) { fail(p, "Price must be between " + Eco.fmt(minPrice) + " and " + Eco.fmt(maxPrice) + "."); return false; }
        int limit = Math.max(maxOrders, plugin.perks().of(p).bazaarOrders());
        if (ordersOf(p.getUniqueId()).size() >= limit) { fail(p, "You already have " + limit + " orders. Claim or cancel some first (higher ranks get more)."); return false; }
        return true;
    }

    /** Collect filled items from a buy order. */
    public void claim(Player p, Order o) {
        if (o.side != Side.BUY || o.claimable() <= 0) return;
        int give = Math.min(o.claimable(), plugin.items().space(p, o.product));
        if (give <= 0) { fail(p, "Your inventory is full."); return; }
        o.claimed += give;
        plugin.items().give(p, o.product, give);
        retire(o);
        p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Claimed <white>" + give + "x</white> ").append(Text.mm(plugin.items().displayName(o.product))));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1f);
    }

    /** Cancels an order, refunding coins (buy) or items (sell). */
    public void cancel(Player p, Order o) {
        if (!orders.containsKey(o.id)) return;
        int rem = o.remaining();
        if (o.side == Side.BUY) {
            if (o.claimable() > 0) {
                plugin.items().give(p, o.product, o.claimable());
                o.claimed = o.filled;
            }
            Eco.deposit(p, rem * o.price);
            p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Order cancelled — refunded <gold>" + Eco.fmt(rem * o.price) + " coins<gray>."));
        } else {
            plugin.items().give(p, o.product, rem);
            p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Offer cancelled — returned <white>" + rem + "x</white> ").append(Text.mm(plugin.items().displayName(o.product))));
        }
        // Mark as fully consumed so it's removed everywhere.
        book(o.product, o.side).remove(o);
        o.filled = o.amount;
        o.claimed = o.filled;
        orders.remove(o.id);
        persist(o);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.8f);
    }

    /** Instantly sells every bazaar product in the inventory to the best buy orders. */
    public void sellInventory(Player p) {
        int kinds = 0;
        for (String product : new ArrayList<>(productHome.keySet())) {
            if (plugin.items().count(p, product) > 0 && volume(product, Side.BUY) > 0) {
                instantSell(p, product, Integer.MAX_VALUE);
                kinds++;
            }
        }
        if (kinds == 0) fail(p, "Nothing in your inventory has buy orders right now.");
    }

    /** Seller's tax rate: rank perk, never above the server default. */
    public double taxFor(Player p) {
        return Math.min(tax, plugin.perks().of(p).bazaarTax());
    }

    private double rateOf(Order o) {
        return o.taxRate >= 0 ? Math.min(tax, o.taxRate) : tax;
    }

    private void payout(Order o, double gross) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(o.owner);
        Eco.deposit(op, gross * (1 - rateOf(o)));
    }

    private void notifyOwner(Order o, int qty) {
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner == null) return;
        String verb = o.side == Side.SELL ? "sold" : "bought";
        owner.sendMessage(Text.mm("<gold>[Bazaar]</gold> <gray>Your " + (o.side == Side.SELL ? "offer" : "order") + " " + verb + " <white>" + qty + "x</white> ")
                .append(Text.mm(plugin.items().displayName(o.product)))
                .append(Text.mm(o.side == Side.SELL ? " <gray>for <green>" + Eco.fmt(qty * o.price * (1 - rateOf(o))) + " coins<gray>."
                        : " <gray>— claim it in <yellow>/bazaar</yellow> → Manage Orders.")));
        owner.playSound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.5f);
    }

    static void fail(Player p, String msg) {
        p.sendMessage(Text.mm("<gold>[Bazaar]</gold> <red>" + msg));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }

    public BazaarMenus menus() {
        return menus;
    }

    public void open(Player p) {
        p.sendActionBar(Text.mm("<gray>Loading shared Bazaar order book…"));
        refreshAsync(() -> { if (p.isOnline()) menus.openHome(p); });
    }
}
