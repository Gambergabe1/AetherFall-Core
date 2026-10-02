package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Administrative catalog GUI to browse and give custom items. */
public final class AdminItemMenu {
    private final AetherCore plugin;

    public AdminItemMenu(AetherCore plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, 0, "ALL");
    }

    public void open(Player player, int page, String category) {
        List<CustomItem> all = new ArrayList<>(plugin.items().all());
        List<CustomItem> filtered = new ArrayList<>();
        for (CustomItem item : all) {
            if (matchesCategory(item, category)) {
                filtered.add(item);
            }
        }

        int pageSize = 45;
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / pageSize));
        int curPage = Math.max(0, Math.min(page, totalPages - 1));

        Menu menu = new Menu(6, Text.mm("<dark_gray>Custom Items · " + category + " (" + (curPage + 1) + "/" + totalPages + ")"));

        int startIndex = curPage * pageSize;
        int endIndex = Math.min(startIndex + pageSize, filtered.size());

        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            CustomItem item = filtered.get(i);
            ItemStack stack = plugin.items().stack(item.id(), 1);
            menu.set(slot++, stack, (p, click) -> {
                int amount = click.isShiftClick() ? 64 : click.isRightClick() ? 16 : 1;
                plugin.items().give(p, item.id(), amount);
                plugin.getLogger().info("[admin-give] actor=" + p.getName() + " target=" + p.getUniqueId()
                        + " item=" + item.id() + " amount=" + amount + " source=gui");
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.4f);
                p.sendActionBar(Text.mm("<green>+ Given <white>" + amount + "x </white>" + item.coloredName()));
            });
        }

        // Category filter toggle button
        List<String> categories = List.of("ALL", "WEAPON", "TOOL", "TALISMAN", "CONSUMABLE", "RESOURCE", "ENCHANTED");
        menu.set(45, new ItemBuilder(Material.HOPPER).name("<yellow><bold>Category: <white>" + category)
                .lore(List.of("<gray>Click to cycle category filter.", "<gray>Current: <aqua>" + category)).build(), (p, c) -> {
            int nextIdx = (categories.indexOf(category) + 1) % categories.size();
            open(p, 0, categories.get(nextIdx));
        });

        // Instructions book
        menu.set(49, new ItemBuilder(Material.BOOK).name("<gold><bold>Admin Item Spawner")
                .lore(List.of(
                        "<gray>Total items: <white>" + all.size() + "</white> (<aqua>" + filtered.size() + "</aqua> shown)",
                        "",
                        "<yellow>• Left-Click: <white>Give 1x",
                        "<yellow>• Right-Click: <white>Give 16x",
                        "<yellow>• Shift-Click: <white>Give 64x",
                        "",
                        "<gray>Command: <yellow>/aegive [player] <item> [amount]"
                )).build());

        // Previous page
        if (curPage > 0) {
            menu.set(48, new ItemBuilder(Material.ARROW).name("<gray>← Previous Page").build(),
                    (p, c) -> open(p, curPage - 1, category));
        }

        // Next page
        if (curPage < totalPages - 1) {
            menu.set(50, new ItemBuilder(Material.ARROW).name("<gray>Next Page →").build(),
                    (p, c) -> open(p, curPage + 1, category));
        }

        menu.set(53, new ItemBuilder(Material.BARRIER).name("<red>Close").build(), (p, c) -> p.closeInventory());

        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    private boolean matchesCategory(CustomItem item, String category) {
        if (category == null || category.equalsIgnoreCase("ALL")) return true;
        CustomItem.Type t = item.type();
        return switch (category.toUpperCase(Locale.ROOT)) {
            case "WEAPON" -> t == CustomItem.Type.WEAPON || t == CustomItem.Type.BOW;
            case "TOOL" -> t == CustomItem.Type.TOOL;
            case "TALISMAN" -> t == CustomItem.Type.TALISMAN;
            case "CONSUMABLE" -> t == CustomItem.Type.CONSUMABLE;
            case "RESOURCE" -> t == CustomItem.Type.RESOURCE;
            case "ENCHANTED" -> t == CustomItem.Type.ENCHANTED;
            case "ARMOR" -> t == CustomItem.Type.HELMET || t == CustomItem.Type.CHESTPLATE
                    || t == CustomItem.Type.LEGGINGS || t == CustomItem.Type.BOOTS;
            default -> true;
        };
    }
}
