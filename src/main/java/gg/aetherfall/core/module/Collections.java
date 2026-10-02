package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.items.CustomItem;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Long-term discovery goals for custom items, armor, relics, and talismans. */
public final class Collections implements Listener, TabExecutor {
    private final AetherCore plugin;
    public Collections(AetherCore plugin) { this.plugin = plugin; }

    public int totalCount() { return plugin.items().all().size(); }

    public long discoveredCount(PlayerData data) {
        return plugin.items().all().stream().filter(item -> data.collections.contains(item.id())).count();
    }

    /** Syncs custom items currently held so profile and menu summaries stay current. */
    public void sync(Player p) {
        PlayerData d = plugin.data().get(p);
        if (d == null) return;
        int before = d.collections.size();
        scan(p, d);
        if (d.collections.size() != before) plugin.data().saveAsync(d);
    }

    private void discover(Player p, ItemStack stack) {
        String id = plugin.items().idOf(stack);
        if (id == null) return;
        PlayerData d = plugin.data().get(p);
        if (d == null || !d.collections.add(id)) return;
        CustomItem item = plugin.items().get(id);
        plugin.data().recordFunnel(p, "first_custom_item");
        plugin.data().recordFunnel(p, "first_collection");
        plugin.onboarding().complete(p, Onboarding.Step.ITEM);
        plugin.onboarding().complete(p, Onboarding.Step.COLLECTION);
        p.sendMessage(Text.mm("<gold>✦ Collection discovered:</gold> <white>" + (item == null ? id : item.name()) + "</white> <gray>(" + d.collections.size() + "/" + plugin.items().all().size() + ")"));
        if (d.collections.size() % 10 == 0) {
            plugin.giveCoins(p, 500);
            p.sendMessage(Text.mm("<aqua>Collection milestone!</aqua> <gray>+500 coins and a cosmetic title unlocked."));
        }
        plugin.data().saveAsync(d);
        if (plugin.achievements() != null) plugin.achievements().check(p);
    }

    @EventHandler public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p) discover(p, e.getItem().getItemStack()); }
    @EventHandler public void craft(CraftItemEvent e) { if (e.getWhoClicked() instanceof Player p) discover(p, e.getRecipe().getResult()); }
    @EventHandler public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(e.getPlayer()), 20L);
    }

    private void scan(Player p, PlayerData d) {
        for (ItemStack stack : p.getInventory().getContents()) {
            String id = plugin.items().idOf(stack);
            if (id != null) d.collections.add(id);
        }
    }

    private static final int[] ITEM_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    /** SkyBlock-style collection book: categories first, then paged collection entries. */
    public void open(Player p) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        scan(p, d); plugin.data().saveAsync(d);
        List<CustomItem> all = items();
        int found = d.collections.size(), total = all.size();
        Menu menu = new Menu(6, Text.mm("<dark_gray>Collections · " + found + "/" + total));
        menu.set(4, new ItemBuilder(Material.NETHER_STAR).name("<gold><bold>Collection Book")
                .lore(List.of("<gray>Discover custom items to unlock", "<gray>milestones and permanent rewards.", "", "<aqua>Discovered: <white>" + found + "/" + total,
                        "<gray>Next milestone: <gold>" + nextMilestone(found) + " discoveries")).glow(found > 0).build());

        List<String> categories = all.stream().map(CustomItem::category).distinct().sorted().toList();
        int[] categorySlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < categories.size() && i < categorySlots.length; i++) {
            String category = categories.get(i);
            long categoryFound = all.stream().filter(item -> category.equals(item.category()) && d.collections.contains(item.id())).count();
            long categoryTotal = all.stream().filter(item -> category.equals(item.category())).count();
            menu.set(categorySlots[i], new ItemBuilder(categoryIcon(category)).name(categoryColor(category) + "<bold>" + categoryName(category))
                    .lore(List.of("<gray>Collections: <white>" + categoryFound + "/" + categoryTotal, "", "<yellow>▶ Click to browse"))
                    .glow(categoryFound > 0 && categoryFound == categoryTotal).build(), (pl, c) -> openCategory(pl, category, 0));
        }
        menu.set(40, new ItemBuilder(Material.COMPASS).name("<aqua><bold>All Collections")
                .lore(List.of("<gray>Browse every custom item", "<gray>in one paged list.", "", "<yellow>▶ Click to browse")).build(), (pl, c) -> openCategory(pl, "ALL", 0));
        menu.set(44, new ItemBuilder(Material.NETHER_STAR).name("<gold><bold>Milestone Rewards")
                .lore(List.of("<gray>Every 10 discoveries:", "<gold>+500 coins", "<gray>and a cosmetic title.", "", "<aqua>Next: " + nextMilestone(found) + " discoveries")).build());
        menu.set(49, new ItemBuilder(Material.ARROW).name("<gray>← Back to Main Menu").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openCategory(Player p, String category, int page) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        List<CustomItem> list = items().stream().filter(item -> "ALL".equals(category) || category.equals(item.category())).toList();
        int pages = Math.max(1, (list.size() + ITEM_SLOTS.length - 1) / ITEM_SLOTS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        final int currentPage = page;
        String title = "ALL".equals(category) ? "All Collections" : categoryName(category);
        Menu menu = new Menu(6, Text.mm("<dark_gray>Collections · " + title + " · " + (page + 1) + "/" + pages));
        int start = page * ITEM_SLOTS.length;
        for (int i = 0; i < ITEM_SLOTS.length && start + i < list.size(); i++) {
            CustomItem item = list.get(start + i);
            boolean discovered = d.collections.contains(item.id());
            List<String> lore = new ArrayList<>();
            lore.add(discovered ? "<green>✔ Discovered" : "<dark_gray>✖ Not discovered");
            lore.add("<gray>Type: <white>" + item.type().label());
            lore.add("<gray>Rarity: " + item.rarity().color + item.rarity().name());
            lore.add("");
            lore.add(discovered ? "<yellow>▶ Click for details" : "<gray>Find or craft this item");
            menu.set(ITEM_SLOTS[i], new ItemBuilder(discovered ? item.material() : Material.GRAY_DYE)
                    .name((discovered ? item.coloredName() : "<dark_gray>???") + (discovered ? "" : " <dark_gray>(Undiscovered)"))
                    .lore(lore).glow(discovered).build(), (pl, c) -> openDetail(pl, item, category, currentPage));
        }
        if (currentPage > 0) menu.set(48, new ItemBuilder(Material.ARROW).name("<yellow>← Previous Page").build(), (pl, c) -> openCategory(pl, category, currentPage - 1));
        menu.set(49, new ItemBuilder(Material.BARRIER).name("<gray>← Collection Categories").build(), (pl, c) -> open(pl));
        if (currentPage + 1 < pages) menu.set(50, new ItemBuilder(Material.ARROW).name("<yellow>Next Page →").build(), (pl, c) -> openCategory(pl, category, currentPage + 1));
        menu.set(53, new ItemBuilder(Material.OAK_DOOR).name("<gray>← Main Menu").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private void openDetail(Player p, CustomItem item, String category, int page) {
        PlayerData d = plugin.data().get(p); if (d == null) return;
        boolean discovered = d.collections.contains(item.id());
        Menu menu = new Menu(3, Text.mm("<dark_gray>Collection · " + (discovered ? item.name() : "???")));
        List<String> lore = new ArrayList<>();
        lore.add(discovered ? "<green>✔ Discovered" : "<dark_gray>✖ Not discovered");
        lore.add("<gray>Category: <white>" + categoryName(item.category()));
        lore.add("<gray>Type: <white>" + item.type().label());
        lore.add("<gray>Rarity: " + item.rarity().color + item.rarity().name());
        if (item.lore() != null) { lore.add(""); lore.addAll(item.lore()); }
        menu.set(13, new ItemBuilder(discovered ? item.material() : Material.GRAY_DYE).name(discovered ? item.coloredName() : "<dark_gray>Undiscovered Item").lore(lore).glow(discovered).build());
        menu.set(18, new ItemBuilder(Material.ARROW).name("<gray>← Back to " + categoryName(category)).build(), (pl, c) -> openCategory(pl, category, page));
        menu.set(22, new ItemBuilder(Material.BARRIER).name("<gray>← Collection Categories").build(), (pl, c) -> open(pl));
        menu.set(26, new ItemBuilder(Material.OAK_DOOR).name("<gray>← Main Menu").build(), (pl, c) -> plugin.menus().openMain(pl));
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    private List<CustomItem> items() {
        List<CustomItem> all = new ArrayList<>(plugin.items().all());
        all.sort(java.util.Comparator.comparing(CustomItem::category).thenComparing(CustomItem::id));
        return all;
    }

    private static int nextMilestone(int found) {
        return ((found / 10) + 1) * 10;
    }

    private static String categoryName(String category) {
        String lower = category.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1).replace('_', ' ');
    }

    private static String categoryColor(String category) {
        return switch (category.toUpperCase(java.util.Locale.ROOT)) {
            case "RESOURCES" -> "<green>";
            case "ENCHANTED" -> "<aqua>";
            case "WEAPONS" -> "<red>";
            case "ARMOR" -> "<blue>";
            case "TOOLS" -> "<gold>";
            case "TALISMANS" -> "<light_purple>";
            default -> "<yellow>";
        };
    }

    private static Material categoryIcon(String category) {
        return switch (category.toUpperCase(java.util.Locale.ROOT)) {
            case "RESOURCES" -> Material.EMERALD;
            case "ENCHANTED" -> Material.ENCHANTED_BOOK;
            case "WEAPONS" -> Material.DIAMOND_SWORD;
            case "ARMOR" -> Material.DIAMOND_CHESTPLATE;
            case "TOOLS" -> Material.DIAMOND_PICKAXE;
            case "TALISMANS" -> Material.NETHER_STAR;
            case "CONSUMABLES" -> Material.POTION;
            default -> Material.CHEST;
        };
    }

    @Override public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) { if (s instanceof Player p) open(p); return true; }
    @Override public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) { return List.of(); }
}
