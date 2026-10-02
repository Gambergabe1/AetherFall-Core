package gg.aetherfall.core.economy;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.items.CustomItem;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hypixel-style Auction House for gear and unique items: Buy-It-Now listings and timed
 * bid auctions. Bids are escrowed; outbid players are refunded instantly; sellers are paid
 * automatically; items are delivered on claim (or on join).
 */
public final class AuctionHouse implements Listener {
    public enum State { ACTIVE, SOLD, EXPIRED }

    public enum Category {
        WEAPONS("Weapons", Material.GOLDEN_SWORD, "<red>", Material.RED_STAINED_GLASS_PANE, List.of("Swords", "Bows", "Other Weapons")),
        ARMOR("Armor", Material.DIAMOND_CHESTPLATE, "<aqua>", Material.LIGHT_BLUE_STAINED_GLASS_PANE, List.of("Helmets", "Chestplates", "Leggings", "Boots", "Other Armor")),
        TOOLS("Tools", Material.GOLDEN_PICKAXE, "<green>", Material.LIME_STAINED_GLASS_PANE, List.of("Pickaxes", "Axes", "Shovels", "Hoes", "Fishing Rods", "Other Tools")),
        ACCESSORIES("Talismans", Material.RABBIT_FOOT, "<light_purple>", Material.MAGENTA_STAINED_GLASS_PANE, List.of("Talismans")),
        CONSUMABLES("Consumables & Relics", Material.GOLDEN_APPLE, "<gold>", Material.ORANGE_STAINED_GLASS_PANE, List.of("Boss Relics", "Food & Potions", "Other Consumables")),
        MISC("Miscellaneous", Material.ENCHANTED_BOOK, "<yellow>", Material.YELLOW_STAINED_GLASS_PANE, List.of("Enchanted Books", "Blocks", "Other Items"));

        public final String label;
        public final Material icon;
        public final String color;
        public final Material pane;
        public final List<String> subs;

        Category(String label, Material icon, String color, Material pane, List<String> subs) {
            this.label = label;
            this.icon = icon;
            this.color = color;
            this.pane = pane;
            this.subs = subs;
        }
    }

    public static final class Auction {
        public final long id;
        public final UUID seller;
        public final String sellerName;
        public final ItemStack item;
        public final boolean bin;
        public final double price;          // BIN price or starting bid
        public double topBid;
        public UUID topBidder;
        public String topBidderName;
        public int bids;
        public final long created;
        public long ends;
        public State state = State.ACTIVE;
        public boolean sellerClaimed;
        public boolean buyerClaimed;

        Auction(long id, UUID seller, String sellerName, ItemStack item, boolean bin, double price, long created, long ends) {
            this.id = id; this.seller = seller; this.sellerName = sellerName; this.item = item; this.bin = bin;
            this.price = price; this.created = created; this.ends = ends;
        }

        public double currentPrice() {
            return bin ? price : (bids > 0 ? topBid : price);
        }

        public double minNextBid() {
            if (bids == 0) return price;
            return Math.max(topBid + 1, Math.ceil(topBid * 1.10));
        }
    }

    private final AetherCore plugin;
    private final Map<Long, Auction> auctions = new LinkedHashMap<>();
    private final List<Runnable> refreshWaiters = new ArrayList<>();
    private boolean refreshInFlight;
    private long bookVersion;
    private long nextId = 1;
    public static final double TAX = 0.01;
    public static final double LISTING_FEE = 0.01;
    public static final int MAX_ACTIVE = 14;
    public static final long[] DURATIONS_H = {1, 6, 12, 24, 48};
    private BukkitTask tickTask;

    public AuctionHouse(AetherCore plugin) {
        this.plugin = plugin;
        load();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
    }

    public void stop() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
    }

    // ── persistence ───────────────────────────────────────────

    private void load() {
        plugin.data().sync(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS auctions (
                      id INTEGER PRIMARY KEY, seller TEXT NOT NULL, seller_name TEXT, item BLOB NOT NULL, bin INTEGER NOT NULL,
                      price REAL NOT NULL, top_bid REAL DEFAULT 0, top_bidder TEXT, top_bidder_name TEXT, bids INTEGER DEFAULT 0,
                      created INTEGER NOT NULL, ends INTEGER NOT NULL, state TEXT NOT NULL,
                      seller_claimed INTEGER DEFAULT 0, buyer_claimed INTEGER DEFAULT 0)""");
                try (ResultSet rs = st.executeQuery("SELECT * FROM auctions")) {
                    while (rs.next()) {
                        ItemStack item;
                        try {
                            item = ItemStack.deserializeBytes(rs.getBytes("item"));
                        } catch (Exception e) {
                            plugin.getLogger().warning("Auction " + rs.getLong("id") + " has an unreadable item; skipping");
                            continue;
                        }
                        Auction a = new Auction(rs.getLong("id"), UUID.fromString(rs.getString("seller")), rs.getString("seller_name"), item,
                                rs.getInt("bin") != 0, rs.getDouble("price"), rs.getLong("created"), rs.getLong("ends"));
                        a.topBid = rs.getDouble("top_bid");
                        String tb = rs.getString("top_bidder");
                        a.topBidder = tb == null ? null : UUID.fromString(tb);
                        a.topBidderName = rs.getString("top_bidder_name");
                        a.bids = rs.getInt("bids");
                        a.state = State.valueOf(rs.getString("state"));
                        a.sellerClaimed = rs.getInt("seller_claimed") != 0;
                        a.buyerClaimed = rs.getInt("buyer_claimed") != 0;
                        auctions.put(a.id, a);
                        nextId = Math.max(nextId, a.id + 1);
                    }
                }
            }
            return null;
        });
        plugin.getLogger().info("Auction House loaded " + auctions.size() + " auctions.");
    }

    /** Refreshes listings from the shared database before a player browses. */
    public void refreshAsync(Runnable after) {
        refreshWaiters.add(after == null ? () -> {} : after);
        if (refreshInFlight) return;
        refreshInFlight = true;
        long requestedVersion = bookVersion;
        plugin.data().async(c -> {
            List<Auction> loaded = new ArrayList<>();
            long loadedNextId = 1;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM auctions")) {
                while (rs.next()) {
                    try {
                        ItemStack item = ItemStack.deserializeBytes(rs.getBytes("item"));
                        long id = rs.getLong("id");
                        double price = rs.getDouble("price");
                        long created = rs.getLong("created"), ends = rs.getLong("ends");
                        State state = State.valueOf(rs.getString("state"));
                        if (item == null || item.getType().isAir() || price < 0 || ends <= 0) continue;
                        Auction a = new Auction(id, UUID.fromString(rs.getString("seller")), rs.getString("seller_name"), item,
                                rs.getInt("bin") != 0, price, created, ends);
                        a.topBid = Math.max(0, rs.getDouble("top_bid"));
                        String bidder = rs.getString("top_bidder");
                        a.topBidder = bidder == null ? null : UUID.fromString(bidder);
                        a.topBidderName = rs.getString("top_bidder_name");
                        a.bids = Math.max(0, rs.getInt("bids"));
                        a.state = state;
                        a.sellerClaimed = rs.getInt("seller_claimed") != 0;
                        a.buyerClaimed = rs.getInt("buyer_claimed") != 0;
                        if (!(a.state != State.ACTIVE && a.sellerClaimed && (a.state == State.EXPIRED || a.buyerClaimed))) loaded.add(a);
                        loadedNextId = Math.max(loadedNextId, id + 1);
                    } catch (RuntimeException ignored) { }
                }
                final long next = loadedNextId;
                Bukkit.getScheduler().runTask(plugin, () -> finishRefresh(loaded, next, requestedVersion));
                return null;
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("Could not refresh shared Auction House listings: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> finishRefresh(null, nextId, requestedVersion));
                return null;
            }
        });
    }

    private void finishRefresh(List<Auction> loaded, long loadedNextId, long requestedVersion) {
        if (loaded != null && requestedVersion != bookVersion) {
            refreshInFlight = false;
            refreshAsync(null);
            return;
        }
        if (loaded != null) { auctions.clear(); nextId = Math.max(1L, loadedNextId); for (Auction a : loaded) auctions.put(a.id, a); }
        refreshInFlight = false;
        List<Runnable> callbacks = new ArrayList<>(refreshWaiters); refreshWaiters.clear();
        for (Runnable callback : callbacks) callback.run();
    }

    private void persist(Auction a) {
        bookVersion++;
        boolean finished = a.state != State.ACTIVE && a.sellerClaimed && (a.state == State.EXPIRED || a.buyerClaimed);
        if (finished) {
            auctions.remove(a.id);
            long id = a.id;
            plugin.data().sync(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM auctions WHERE id = ?")) {
                    ps.setLong(1, id);
                    return ps.executeUpdate();
                }
            });
            return;
        }
        byte[] bytes = a.item.serializeAsBytes();
        long id = a.id; String seller = a.seller.toString(); String sellerName = a.sellerName; boolean bin = a.bin; double price = a.price;
        double topBid = a.topBid; String topBidder = a.topBidder == null ? null : a.topBidder.toString(); String topName = a.topBidderName;
        int bids = a.bids; long created = a.created, ends = a.ends; String state = a.state.name(); boolean sc = a.sellerClaimed, bc = a.buyerClaimed;
        plugin.data().sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO auctions (id, seller, seller_name, item, bin, price, top_bid, top_bidder, top_bidder_name, bids, created, ends, state, seller_claimed, buyer_claimed)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET top_bid=excluded.top_bid, top_bidder=excluded.top_bidder,
                    top_bidder_name=excluded.top_bidder_name, bids=excluded.bids, ends=excluded.ends, state=excluded.state,
                    seller_claimed=excluded.seller_claimed, buyer_claimed=excluded.buyer_claimed""")) {
                ps.setLong(1, id); ps.setString(2, seller); ps.setString(3, sellerName); ps.setBytes(4, bytes); ps.setInt(5, bin ? 1 : 0);
                ps.setDouble(6, price); ps.setDouble(7, topBid); ps.setString(8, topBidder); ps.setString(9, topName); ps.setInt(10, bids);
                ps.setLong(11, created); ps.setLong(12, ends); ps.setString(13, state); ps.setInt(14, sc ? 1 : 0); ps.setInt(15, bc ? 1 : 0);
                return ps.executeUpdate();
            }
        });
    }

    private long allocateId() {
        long id;
        do { id = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE); }
        while (auctions.containsKey(id));
        return id;
    }

    // ── queries ───────────────────────────────────────────────

    public Category categoryOf(ItemStack item) {
        CustomItem c = plugin.items().customOf(item);
        if (c != null) {
            return switch (c.type()) {
                case WEAPON, BOW -> Category.WEAPONS;
                case HELMET, CHESTPLATE, LEGGINGS, BOOTS -> Category.ARMOR;
                case TOOL -> Category.TOOLS;
                case TALISMAN -> Category.ACCESSORIES;
                case CONSUMABLE -> Category.CONSUMABLES;
                default -> Category.MISC;
            };
        }
        String n = item.getType().name();
        if (n.endsWith("_SWORD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("MACE") || n.endsWith("_SPEAR")) return Category.WEAPONS;
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.equals("ELYTRA") || n.equals("SHIELD")) return Category.ARMOR;
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("FISHING_ROD") || n.equals("SHEARS")) return Category.TOOLS;
        if (n.contains("POTION") || n.equals("GOLDEN_APPLE") || n.equals("ENCHANTED_GOLDEN_APPLE") || n.equals("TOTEM_OF_UNDYING")) return Category.CONSUMABLES;
        return Category.MISC;
    }

    /** Sub-category label within the item's category (Hypixel-style drill-down). */
    public String subOf(ItemStack item) {
        Category cat = categoryOf(item);
        CustomItem c = plugin.items().customOf(item);
        String n = item.getType().name();
        return switch (cat) {
            case WEAPONS -> n.endsWith("_SWORD") ? "Swords" : (n.equals("BOW") || n.equals("CROSSBOW") || (c != null && c.type() == CustomItem.Type.BOW)) ? "Bows" : "Other Weapons";
            case ARMOR -> n.endsWith("_HELMET") ? "Helmets" : n.endsWith("_CHESTPLATE") ? "Chestplates" : n.endsWith("_LEGGINGS") ? "Leggings"
                    : n.endsWith("_BOOTS") ? "Boots" : "Other Armor";
            case TOOLS -> n.endsWith("_PICKAXE") ? "Pickaxes" : n.endsWith("_AXE") ? "Axes" : n.endsWith("_SHOVEL") ? "Shovels"
                    : n.endsWith("_HOE") ? "Hoes" : n.equals("FISHING_ROD") ? "Fishing Rods" : "Other Tools";
            case ACCESSORIES -> "Talismans";
            case CONSUMABLES -> (c != null && c.ability() != null && c.ability().startsWith("summon:")) ? "Boss Relics"
                    : (item.getType().isEdible() || n.contains("POTION")) ? "Food & Potions" : "Other Consumables";
            case MISC -> n.equals("ENCHANTED_BOOK") ? "Enchanted Books" : item.getType().isBlock() ? "Blocks" : "Other Items";
        };
    }

    /** Custom items use their rarity; vanilla items are Common (Uncommon if enchanted). */
    public CustomItem.Rarity rarityOf(ItemStack item) {
        CustomItem c = plugin.items().customOf(item);
        if (c != null) return c.rarity();
        return item.getEnchantments().isEmpty() && item.getType() != Material.ENCHANTED_BOOK ? CustomItem.Rarity.COMMON : CustomItem.Rarity.UNCOMMON;
    }

    public static String plainName(ItemStack item) {
        Component name = item.getItemMeta() != null && item.getItemMeta().hasDisplayName() ? item.getItemMeta().displayName() : Component.translatable(item.translationKey());
        return PlainTextComponentSerializer.plainText().serialize(name);
    }

    public enum Sort { LOWEST_PRICE, HIGHEST_PRICE, ENDING_SOON, NEWEST }
    public enum Filter { ALL, BIN, AUCTIONS }

    public List<Auction> browse(Category cat, Sort sort, Filter filter, String query) {
        return browse(cat, null, null, sort, filter, query);
    }

    public List<Auction> browse(Category cat, String sub, CustomItem.Rarity rarity, Sort sort, Filter filter, String query) {
        List<Auction> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        String q = query == null ? null : query.toLowerCase(Locale.ROOT);
        for (Auction a : auctions.values()) {
            if (a.state != State.ACTIVE || a.ends <= now) continue;
            if (cat != null && categoryOf(a.item) != cat) continue;
            if (sub != null && !sub.equals(subOf(a.item))) continue;
            if (rarity != null && rarityOf(a.item) != rarity) continue;
            if (filter == Filter.BIN && !a.bin) continue;
            if (filter == Filter.AUCTIONS && a.bin) continue;
            if (q != null && !plainName(a.item).toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(a);
        }
        Comparator<Auction> cmp = switch (sort) {
            case LOWEST_PRICE -> Comparator.comparingDouble(Auction::currentPrice);
            case HIGHEST_PRICE -> Comparator.comparingDouble(Auction::currentPrice).reversed();
            case ENDING_SOON -> Comparator.comparingLong(a -> a.ends);
            case NEWEST -> Comparator.comparingLong((Auction a) -> a.created).reversed();
        };
        out.sort(cmp);
        return out;
    }

    public List<Auction> sellerAuctions(UUID p) {
        List<Auction> out = new ArrayList<>();
        for (Auction a : auctions.values()) if (a.seller.equals(p) && !(a.state != State.ACTIVE && a.sellerClaimed)) out.add(a);
        return out;
    }

    public List<Auction> bidderAuctions(UUID p) {
        List<Auction> out = new ArrayList<>();
        for (Auction a : auctions.values()) {
            if (!p.equals(a.topBidder)) continue;
            if (a.state == State.ACTIVE || (a.state == State.SOLD && !a.buyerClaimed)) out.add(a);
        }
        return out;
    }

    public Auction get(long id) {
        return auctions.get(id);
    }

    private int activeCount(UUID p) {
        int n = 0;
        for (Auction a : auctions.values()) if (a.seller.equals(p) && a.state == State.ACTIVE) n++;
        return n;
    }

    // ── actions ───────────────────────────────────────────────

    /** Why an item can't be auctioned, or null if it can. */
    public String rejectReason(ItemStack item) {
        if (item == null || item.getType().isAir()) return "Pick an item first.";
        String key = plugin.items().keyOf(item);
        if (key != null && plugin.items().isCommodity(key) && plugin.bazaar().isProduct(key)) return "Materials are traded on the Bazaar, not the Auction House.";
        CustomItem c = plugin.items().customOf(item);
        if (c != null && (c.type() == CustomItem.Type.RESOURCE || c.type() == CustomItem.Type.ENCHANTED)) return "Materials are traded on the Bazaar.";
        return null;
    }

    public boolean create(Player p, int slot, ItemStack expected, boolean bin, double price, long hours) {
        price = Math.floor(price);
        ItemStack inSlot = p.getInventory().getItem(slot);
        if (inSlot == null || !inSlot.isSimilar(expected) || inSlot.getAmount() != expected.getAmount()) {
            fail(p, "That item is no longer in your inventory.");
            return false;
        }
        String reason = rejectReason(inSlot);
        if (reason != null) { fail(p, reason); return false; }
        if (price < 1 || price > 1_000_000_000) { fail(p, "Price must be between 1 and 1,000,000,000."); return false; }
        var perks = plugin.perks().of(p);
        int maxActive = Math.max(MAX_ACTIVE, perks.ahListings());
        if (activeCount(p.getUniqueId()) >= maxActive) { fail(p, "You can have at most " + maxActive + " active auctions (higher ranks get more)."); return false; }
        double fee = Math.max(1, Math.floor(price * Math.min(LISTING_FEE, perks.ahFee())));
        if (!Eco.withdraw(p, fee)) { fail(p, "You need " + Eco.fmt(fee) + " coins for the listing fee."); return false; }
        ItemStack item = inSlot.clone();
        p.getInventory().setItem(slot, null);
        long now = System.currentTimeMillis();
        Auction a = new Auction(allocateId(), p.getUniqueId(), p.getName(), item, bin, price, now, now + hours * 3_600_000L);
        auctions.put(a.id, a);
        persist(a);
        p.sendMessage(Text.mm("<gold>[AH]</gold> <gray>Listed ").append(item.effectiveName())
                .append(Text.mm(" <gray>" + (bin ? "for <gold>" + Eco.fmt(price) + " coins</gold> (BIN)" : "with a starting bid of <gold>" + Eco.fmt(price) + "</gold>")
                        + " for " + hours + "h. <dark_gray>(fee " + Eco.fmt(fee) + ")")));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
        plugin.onboarding().complete(p, gg.aetherfall.core.module.Onboarding.Step.TRADE);
        if (price >= 100_000) {
            Bukkit.broadcast(Text.mm("<gold>[AH]</gold> <yellow>{p}</yellow> <gray>listed ", Map.of("p", p.getName())).append(item.effectiveName())
                    .append(Text.mm(" <gray>for <gold>" + Eco.fmt(price) + "</gold>! <dark_gray>(/ah)")));
        }
        return true;
    }

    public void buyNow(Player p, Auction a) {
        if (a.state != State.ACTIVE || !a.bin || a.ends <= System.currentTimeMillis()) { fail(p, "This auction has ended."); return; }
        if (a.seller.equals(p.getUniqueId())) { fail(p, "You can't buy your own auction."); return; }
        if (p.getInventory().firstEmpty() < 0) { fail(p, "Make some room in your inventory first."); return; }
        if (!Eco.withdraw(p, a.price)) { fail(p, "You need " + Eco.fmt(a.price) + " coins."); return; }
        a.state = State.SOLD;
        a.topBid = a.price;
        a.topBidder = p.getUniqueId();
        a.topBidderName = p.getName();
        a.bids = 1;
        paySeller(a);
        deliver(p, a);
        p.sendMessage(Text.mm("<gold>[AH]</gold> <gray>You bought ").append(a.item.effectiveName())
                .append(Text.mm(" <gray>for <gold>" + Eco.fmt(a.price) + " coins<gray>.")));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
    }

    public void bid(Player p, Auction a, double amount) {
        amount = Math.floor(amount);
        if (a.state != State.ACTIVE || a.bin || a.ends <= System.currentTimeMillis()) { fail(p, "This auction has ended."); return; }
        if (a.seller.equals(p.getUniqueId())) { fail(p, "You can't bid on your own auction."); return; }
        if (p.getUniqueId().equals(a.topBidder)) { fail(p, "You already have the top bid."); return; }
        if (amount < a.minNextBid()) { fail(p, "The minimum bid is " + Eco.fmt(a.minNextBid()) + " coins."); return; }
        if (!Eco.withdraw(p, amount)) { fail(p, "You need " + Eco.fmt(amount) + " coins."); return; }
        // Refund the previous top bidder immediately.
        if (a.topBidder != null) {
            Eco.deposit(Bukkit.getOfflinePlayer(a.topBidder), a.topBid);
            Player prev = Bukkit.getPlayer(a.topBidder);
            if (prev != null) {
                prev.sendMessage(Text.mm("<gold>[AH]</gold> <red>You were outbid</red> <gray>on ").append(a.item.effectiveName())
                        .append(Text.mm(" <gray>by <yellow>" + p.getName() + "</yellow> (" + Eco.fmt(amount) + "). Your " + Eco.fmt(a.topBid) + " coins were refunded.")));
                prev.playSound(prev.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            }
        }
        a.topBid = amount;
        a.topBidder = p.getUniqueId();
        a.topBidderName = p.getName();
        a.bids++;
        // Anti-snipe: late bids extend the auction to at least one more minute.
        long now = System.currentTimeMillis();
        if (a.ends - now < 60_000) a.ends = now + 60_000;
        persist(a);
        p.sendMessage(Text.mm("<gold>[AH]</gold> <gray>You bid <gold>" + Eco.fmt(amount) + " coins</gold> on ").append(a.item.effectiveName()));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.4f);
        Player seller = Bukkit.getPlayer(a.seller);
        if (seller != null) seller.sendMessage(Text.mm("<gold>[AH]</gold> <yellow>" + p.getName() + "</yellow> <gray>bid <gold>" + Eco.fmt(amount) + "</gold> on your ").append(a.item.effectiveName()));
    }

    public void cancel(Player p, Auction a) {
        if (!a.seller.equals(p.getUniqueId()) || a.state != State.ACTIVE) return;
        if (a.bids > 0) { fail(p, "You can't cancel an auction that has bids."); return; }
        a.state = State.EXPIRED;
        a.ends = System.currentTimeMillis();
        returnToSeller(p, a);
    }

    /** Claim a won/bought item (buyer) or an unsold item (seller). */
    public void claim(Player p, Auction a) {
        if (a.state == State.SOLD && p.getUniqueId().equals(a.topBidder) && !a.buyerClaimed) {
            if (p.getInventory().firstEmpty() < 0) { fail(p, "Make some room in your inventory first."); return; }
            deliver(p, a);
        } else if (a.state == State.EXPIRED && a.seller.equals(p.getUniqueId()) && !a.sellerClaimed) {
            if (p.getInventory().firstEmpty() < 0) { fail(p, "Make some room in your inventory first."); return; }
            returnToSeller(p, a);
        }
    }

    private void deliver(Player buyer, Auction a) {
        buyer.getInventory().addItem(a.item.clone());
        a.buyerClaimed = true;
        persist(a);
    }

    private void returnToSeller(Player seller, Auction a) {
        seller.getInventory().addItem(a.item.clone());
        a.sellerClaimed = true;
        persist(a);
        seller.sendMessage(Text.mm("<gold>[AH]</gold> <gray>Returned ").append(a.item.effectiveName()).append(Text.mm(" <gray>to your inventory.")));
    }

    private void paySeller(Auction a) {
        double net = a.topBid * (1 - TAX);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(a.seller);
        Eco.deposit(seller, net);
        a.sellerClaimed = true;
        persist(a);
        Player online = seller.getPlayer();
        if (online != null) {
            online.sendMessage(Text.mm("<gold>[AH]</gold> <green>Sold!</green> <gray>Your ").append(a.item.effectiveName())
                    .append(Text.mm(" <gray>sold to <yellow>" + a.topBidderName + "</yellow> for <gold>" + Eco.fmt(a.topBid) + "</gold> <dark_gray>(+" + Eco.fmt(net) + " after tax)")));
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Auction a : new ArrayList<>(auctions.values())) {
            if (a.state != State.ACTIVE || a.ends > now) continue;
            if (a.topBidder != null) {
                a.state = State.SOLD;
                paySeller(a);
                Player winner = Bukkit.getPlayer(a.topBidder);
                if (winner != null) {
                    winner.sendMessage(Text.mm("<gold>[AH]</gold> <green>You won</green> ").append(a.item.effectiveName())
                            .append(Text.mm(" <gray>for <gold>" + Eco.fmt(a.topBid) + "</gold>!")));
                    if (winner.getInventory().firstEmpty() >= 0) deliver(winner, a);
                    else winner.sendMessage(Text.mm("<gray>Claim it in <yellow>/ah</yellow> → View Bids when you have room."));
                }
            } else {
                a.state = State.EXPIRED;
                persist(a);
                Player seller = Bukkit.getPlayer(a.seller);
                if (seller != null) {
                    seller.sendMessage(Text.mm("<gold>[AH]</gold> <gray>Your auction for ").append(a.item.effectiveName())
                            .append(Text.mm(" <gray>expired without bids.")));
                    if (seller.getInventory().firstEmpty() >= 0) returnToSeller(seller, a);
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            int delivered = 0;
            for (Auction a : new ArrayList<>(auctions.values())) {
                if (p.getInventory().firstEmpty() < 0) break;
                if (a.state == State.SOLD && p.getUniqueId().equals(a.topBidder) && !a.buyerClaimed) { deliver(p, a); delivered++; }
                else if (a.state == State.EXPIRED && a.seller.equals(p.getUniqueId()) && !a.sellerClaimed) { returnToSeller(p, a); delivered++; }
            }
            if (delivered > 0) p.sendMessage(Text.mm("<gold>[AH]</gold> <gray>Delivered <white>" + delivered + "</white> auction item(s) to your inventory."));
        }, 60L);
    }

    static void fail(Player p, String msg) {
        p.sendMessage(Text.mm("<gold>[AH]</gold> <red>" + msg));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }
}
