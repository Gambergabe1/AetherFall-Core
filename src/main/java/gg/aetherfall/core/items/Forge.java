package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /forge: craft custom items straight from your inventory, Hypixel-style. */
public final class Forge {
    private static final Map<String, Material> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("ENCHANTED", Material.ENCHANTED_BOOK);
        CATEGORIES.put("TOOLS", Material.DIAMOND_PICKAXE);
        CATEGORIES.put("WEAPONS", Material.DIAMOND_SWORD);
        CATEGORIES.put("ARMOR", Material.DIAMOND_CHESTPLATE);
        CATEGORIES.put("TALISMANS", Material.RABBIT_FOOT);
        CATEGORIES.put("CONSUMABLES", Material.GOLDEN_APPLE);
        CATEGORIES.put("SUMMONS", Material.WITHER_SKELETON_SKULL);
    }

    private final AetherCore plugin;

    public Forge(AetherCore plugin) {
        this.plugin = plugin;
    }

    private List<CustomItem> recipes(String category) {
        List<CustomItem> out = new ArrayList<>();
        for (CustomItem c : plugin.items().all()) {
            if (!c.recipe().isEmpty() && c.category().equalsIgnoreCase(category)) out.add(c);
        }
        return out;
    }

    public void open(Player player) {
        open(player, "ENCHANTED", 0);
    }

    public void open(Player player, String category, int page) {
        List<CustomItem> list = recipes(category);
        int perPage = 28;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, Text.mm("<dark_gray>Aether Forge · " + ItemRegistry.prettify(category)
                + (pages > 1 ? " (" + (page + 1) + "/" + pages + ")" : "")));
        int slot = 0;
        for (var e : CATEGORIES.entrySet()) {
            String cat = e.getKey();
            boolean sel = cat.equalsIgnoreCase(category);
            menu.set(slot * 9, new ItemBuilder(e.getValue()).name((sel ? "<green><bold>" : "<yellow>") + ItemRegistry.prettify(cat))
                    .lore(List.of("<gray>" + recipes(cat).size() + " recipes", sel ? "<green>Selected" : "<yellow>▶ Click to browse"))
                    .glow(sel).build(), (p, c) -> open(p, cat, 0));
            if (++slot >= 6) break;
        }
        int[] grid = new int[28];
        int gi = 0;
        for (int row = 0; row < 4; row++) for (int col = 2; col <= 8; col++) grid[gi++] = row * 9 + col;
        for (int i = 0; i < perPage && page * perPage + i < list.size(); i++) {
            CustomItem c = list.get(page * perPage + i);
            menu.set(grid[i], display(player, c), (p, click) -> {
                craft(p, c, click.isShiftClick());
                String cat = category;
                int pg = pageOf(cat, c);
                open(p, cat, pg);
            });
        }
        if (page > 0) {
            int prev = page - 1;
            menu.set(48, new ItemBuilder(Material.ARROW).name("<gray>← Previous page").build(), (p, c) -> open(p, category, prev));
        }
        if (page < pages - 1) {
            int next = page + 1;
            menu.set(50, new ItemBuilder(Material.ARROW).name("<gray>Next page →").build(), (p, c) -> open(p, category, next));
        }
        menu.set(49, new ItemBuilder(Material.ANVIL).name("<gold><bold>Aether Forge").lore(List.of(
                "<gray>Ingredients are taken straight", "<gray>from your inventory.", "",
                "<yellow>Click</yellow> <gray>a recipe to forge one,", "<yellow>Shift-click</yellow> <gray>to forge as many as you can.")).build());
        menu.fill(Material.ORANGE_STAINED_GLASS_PANE).open(player);
    }

    private int pageOf(String category, CustomItem c) {
        return Math.max(0, recipes(category).indexOf(c) / 28);
    }

    private ItemStack display(Player player, CustomItem c) {
        ItemStack stack = plugin.items().build(c);
        stack.setAmount(Math.max(1, Math.min(c.recipeAmount(), stack.getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Text.item("<gold>Ingredients:", Map.of()));
        boolean can = true;
        for (var e : c.recipe().entrySet()) {
            int have = plugin.items().count(player, e.getKey());
            boolean ok = have >= e.getValue();
            can &= ok;
            lore.add(Text.item((ok ? "<green>✔ " : "<red>✘ ") + "<white>" + e.getValue() + "x </white>", Map.of())
                    .append(Text.item(plugin.items().displayName(e.getKey()), Map.of()))
                    .append(Text.item(" <dark_gray>(" + have + ")", Map.of())));
        }
        lore.add(Component.empty());
        lore.add(Text.item(can ? "<green>▶ Click to forge!" : "<red>Missing ingredients", Map.of()));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void craft(Player player, CustomItem c, boolean max) {
        int crafts = 0;
        int limit = max ? 64 : 1;
        while (crafts < limit && hasAll(player, c)) {
            for (var e : c.recipe().entrySet()) plugin.items().remove(player, e.getKey(), e.getValue());
            plugin.items().give(player, c.id(), c.recipeAmount());
            crafts++;
        }
        if (crafts == 0) {
            player.sendMessage(Text.mm("<red>You don't have all the ingredients for ").append(Text.mm(c.coloredName())).append(Text.mm("<red>.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        player.sendMessage(Text.mm("<gold>Forged</gold> <white>" + (crafts * c.recipeAmount()) + "x</white> ").append(Text.mm(c.coloredName())));
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.3f);
        plugin.onboarding().complete(player, gg.aetherfall.core.module.Onboarding.Step.FORGE);
        if (c.rarity().ordinal() >= CustomItem.Rarity.EPIC.ordinal()) {
            plugin.getServer().broadcast(Text.mm("<gold>✦</gold> <yellow>{player}</yellow> <gray>forged</gray> ", Map.of("player", player.getName()))
                    .append(Text.mm(c.coloredName())).append(Text.mm("<gray>!")));
        }
    }

    private boolean hasAll(Player player, CustomItem c) {
        for (var e : c.recipe().entrySet()) if (plugin.items().count(player, e.getKey()) < e.getValue()) return false;
        return true;
    }

}
