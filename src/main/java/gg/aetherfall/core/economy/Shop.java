package gg.aetherfall.core.economy;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.items.CustomItem;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /shop: NPC buy/sell prices. The floor of the economy; the Bazaar sits on top. */
public final class Shop {
    public record Entry(String key, double buy, double sell) {}
    public record Category(String id, String name, Material icon, List<Entry> entries) {}

    private final AetherCore plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, Entry> byKey = new LinkedHashMap<>();

    public Shop(AetherCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        categories.clear();
        byKey.clear();
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) plugin.saveResource("shop.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cats = yml.getConfigurationSection("categories");
        if (cats == null) return;
        for (String id : cats.getKeys(false)) {
            ConfigurationSection c = cats.getConfigurationSection(id);
            List<Entry> entries = new ArrayList<>();
            ConfigurationSection items = c.getConfigurationSection("items");
            if (items != null) for (String key : items.getKeys(false)) {
                if (!plugin.items().isCommodity(key)) {
                    plugin.getLogger().warning("shop.yml: '" + key + "' is unknown or is custom gear (gear belongs in the Auction House)");
                    continue;
                }
                Entry e = new Entry(key, items.getDouble(key + ".buy", 0), items.getDouble(key + ".sell", 0));
                entries.add(e);
                byKey.putIfAbsent(key, e);
            }
            categories.put(id, new Category(id, c.getString("name", id), ItemBuilder.material(c.getString("icon"), Material.CHEST), entries));
        }
    }

    /** NPC sell price for any key (shop.yml first, then the custom item's own sell value). */
    public double sellPrice(String key) {
        if (!plugin.items().isCommodity(key)) return 0; // custom gear is never sold to the NPC
        Entry e = byKey.get(key);
        double multiplier = Math.max(0.0, plugin.getConfig().getDouble("economy.npc-sell-multiplier", 1.0));
        if (e != null && e.sell > 0) return e.sell * multiplier;
        CustomItem c = plugin.items().get(key);
        if (c == null) return 0;
        if (c.sell() > 0) return c.sell() * multiplier;
        return enchantedValue(c) * multiplier;
    }

    public int listedItems() { return byKey.size(); }
    public double sellMultiplier() { return Math.max(0.0, plugin.getConfig().getDouble("economy.npc-sell-multiplier", 1.0)); }

    /** Compacted items are worth what went into them. */
    private double enchantedValue(CustomItem c) {
        if (c.type() != CustomItem.Type.ENCHANTED || c.recipe().isEmpty()) return 0;
        double total = 0;
        for (var r : c.recipe().entrySet()) {
            double unit = sellPrice(r.getKey());
            if (unit <= 0) return 0;
            total += unit * r.getValue();
        }
        return total / Math.max(1, c.recipeAmount());
    }

    public void open(Player player) {
        Menu menu = new Menu(4, Text.mm("<dark_gray>Shop"));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (Category c : categories.values()) {
            if (i >= slots.length) break;
            menu.set(slots[i++], new ItemBuilder(c.icon).name("<yellow><bold>" + c.name)
                    .lore(List.of("<gray>" + c.entries.size() + " items", "", "<yellow>▶ Click to browse")).build(), (p, cl) -> openCategory(p, c));
        }
        menu.set(31, sellAllButton(), (p, c) -> { sellAll(p); open(p); });
        menu.set(27, new ItemBuilder(Material.ARROW).name("<gray>← Menu").build(), (p, c) -> plugin.menus().openMain(p));
        menu.set(35, new ItemBuilder(Material.GOLD_BLOCK).name("<gold><bold>Bazaar")
                .lore(List.of("<gray>Better prices? Trade with players", "<gray>on the Bazaar.", "", "<yellow>▶ Click to open")).build(), (p, c) -> plugin.bazaar().open(p));
        menu.fill(Material.GREEN_STAINED_GLASS_PANE).open(player);
    }

    private ItemStack sellAllButton() {
        return new ItemBuilder(Material.HOPPER).name("<green><bold>Sell Inventory")
                .lore(List.of("<gray>Sells every item in your inventory", "<gray>that has an NPC price.", "",
                        "<dark_gray>Tools, armor and weapons are never sold.", "", "<yellow>▶ Click to sell")).build();
    }

    public void openCategory(Player player, Category c) {
        Menu menu = new Menu(6, Text.mm("<dark_gray>Shop · " + c.name));
        int slot = 0;
        for (Entry e : c.entries) {
            if (slot >= 45) break;
            menu.set(slot++, display(e), (p, click) -> { click(p, e, click); openCategory(p, c); });
        }
        menu.set(45, new ItemBuilder(Material.ARROW).name("<gray>← Back").build(), (p, cl) -> open(p));
        menu.set(49, sellAllButton(), (p, cl) -> { sellAll(p); openCategory(p, c); });
        menu.set(53, new ItemBuilder(Material.GOLD_NUGGET).name("<gold>Balance: " + Eco.fmt(Eco.balance(player)) + " coins").build());
        menu.fill(Material.LIME_STAINED_GLASS_PANE).open(player);
    }

    private ItemStack display(Entry e) {
        ItemStack s = plugin.items().stack(e.key, 1);
        ItemMeta meta = s.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        if (e.buy > 0) lore.add(Text.item("<gray>Buy: <gold>" + Eco.fmt(e.buy) + "</gold> each <dark_gray>(" + Eco.fmt(e.buy * s.getMaxStackSize()) + " / stack)", Map.of()));
        if (e.sell > 0) lore.add(Text.item("<gray>Sell: <green>" + Eco.fmt(sellPrice(e.key)) + "</green> each", Map.of()));
        lore.add(Component.empty());
        if (e.buy > 0) lore.add(Text.item("<yellow>Left-click</yellow> <gray>buy 1 · <yellow>Shift-left</yellow> <gray>buy a stack", Map.of()));
        if (e.sell > 0) lore.add(Text.item("<yellow>Right-click</yellow> <gray>sell 1 · <yellow>Shift-right</yellow> <gray>sell all", Map.of()));
        meta.lore(lore);
        s.setItemMeta(meta);
        return s;
    }

    private void click(Player p, Entry e, ClickType click) {
        if (click.isLeftClick() && e.buy > 0) {
            int amount = click.isShiftClick() ? plugin.items().maxStack(e.key) : 1;
            amount = Math.min(amount, plugin.items().space(p, e.key));
            if (amount <= 0) { fail(p, "Your inventory is full."); return; }
            double cost = e.buy * amount;
            if (!Eco.withdraw(p, cost)) { fail(p, "You need " + Eco.fmt(cost) + " coins."); return; }
            plugin.items().give(p, e.key, amount);
            p.sendMessage(Text.mm("<gray>Bought <white>" + amount + "x</white> ").append(Text.mm(plugin.items().displayName(e.key)))
                    .append(Text.mm(" <gray>for <gold>" + Eco.fmt(cost) + " coins")));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        } else if (click.isRightClick() && e.sell > 0) {
            int have = plugin.items().count(p, e.key);
            int amount = click.isShiftClick() ? have : Math.min(1, have);
            if (amount <= 0) { fail(p, "You don't have any to sell."); return; }
            plugin.items().remove(p, e.key, amount);
            double earned = sellPrice(e.key) * amount;
            Eco.deposit(p, earned);
            plugin.onboarding().complete(p, gg.aetherfall.core.module.Onboarding.Step.TRADE);
            p.sendMessage(Text.mm("<gray>Sold <white>" + amount + "x</white> ").append(Text.mm(plugin.items().displayName(e.key)))
                    .append(Text.mm(" <gray>for <green>" + Eco.fmt(earned) + " coins")));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
        }
    }

    /** Sells every NPC-sellable resource in the inventory (never gear). */
    public void sellAll(Player p) {
        double total = 0;
        int count = 0;
        Map<String, Integer> sold = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            String key = plugin.items().keyOf(s);
            if (key == null) continue;
            CustomItem c = plugin.items().get(key);
            if (c != null && !(c.type() == CustomItem.Type.RESOURCE || c.type() == CustomItem.Type.ENCHANTED)) continue;
            if (c == null && s.getMaxStackSize() == 1) continue;
            double price = sellPrice(key);
            if (price <= 0) continue;
            sold.merge(key, s.getAmount(), Integer::sum);
        }
        for (var e : sold.entrySet()) {
            int removed = plugin.items().remove(p, e.getKey(), e.getValue());
            total += removed * sellPrice(e.getKey());
            count += removed;
        }
        if (count == 0) { fail(p, "Nothing in your inventory can be sold to the shop."); return; }
        Eco.deposit(p, total);
        plugin.onboarding().complete(p, gg.aetherfall.core.module.Onboarding.Step.TRADE);
        p.sendMessage(Text.mm("<gray>Sold <white>" + count + "</white> items for <green>" + Eco.fmt(total) + " coins<gray>."));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
    }

    private static void fail(Player p, String msg) {
        p.sendMessage(Text.mm("<red>" + msg));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }
}
