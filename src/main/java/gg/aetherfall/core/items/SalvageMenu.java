package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.ItemBuilder;
import gg.aetherfall.core.util.Menu;
import gg.aetherfall.core.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Salvage Station GUI: allows players to recycle unwanted custom items, mob drops,
 * and gear into coins and Aether Shards.
 */
public final class SalvageMenu {
    private final AetherCore plugin;

    public SalvageMenu(AetherCore plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Menu menu = new Menu(3, Text.mm("<dark_gray>Aetherfall · Salvage Altar"));

        // Slot 13 is the input slot (empty)
        // Slot 15 is the Salvage action button

        menu.set(4, new ItemBuilder(Material.BLAST_FURNACE).name("<gold><bold>Salvage Altar")
                .lore(List.of(
                        "<gray>Place custom items, mob drops or equipment",
                        "<gray>into your inventory or the altar to break",
                        "<gray>them down into <gold>coins</gold> and <aqua>Aether Shards</aqua>."
                )).build());

        // Button to salvage all salvageable items in inventory
        menu.set(11, new ItemBuilder(Material.HOPPER).name("<aqua><bold>Salvage Inventory Resources")
                .lore(List.of(
                        "<gray>Scans your inventory for mob drops and",
                        "<gray>crafting resources, converting them to coins",
                        "<gray>and bonus shards.",
                        "",
                        "<yellow>▶ Click to salvage resources"
                )).build(), (p, c) -> salvageInventory(p));

        // Button to salvage held item
        menu.set(15, new ItemBuilder(Material.ANVIL).name("<yellow><bold>Salvage Held Item")
                .lore(List.of(
                        "<gray>Breaks down the custom item in your main hand",
                        "<gray>into coins, materials and shards.",
                        "",
                        "<yellow>▶ Click to salvage held item"
                )).build(), (p, c) -> salvageHeld(p));

        menu.set(22, new ItemBuilder(Material.BARRIER).name("<red>Close").build(), (p, c) -> p.closeInventory());
        menu.fill(Material.BLACK_STAINED_GLASS_PANE).open(player);
    }

    private void salvageHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        CustomItem custom = plugin.items().customOf(held);
        if (custom == null) {
            player.sendMessage(Text.mm("<red>Hold a valid custom item to salvage it."));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f);
            return;
        }

        int count = held.getAmount();
        long coins = Math.max(5, (long) (custom.sell() * 0.75 * count));
        int shards = custom.rarity().ordinal(); // Common=0, Uncommon=1, Rare=2, Epic=3, Legendary=4, Mythic=5

        player.getInventory().setItemInMainHand(null);
        gg.aetherfall.core.economy.Eco.deposit(player, coins);
        if (shards > 0 && plugin.items().isValidKey("aether_shard")) {
            plugin.items().give(player, "aether_shard", shards);
            player.sendMessage(Text.mm("<green>✔ Salvaged <white>" + count + "x " + custom.coloredName()
                    + "<green> for <gold>" + Text.number(coins) + " coins</gold> and <aqua>" + shards + "x Aether Shard(s)</aqua>!"));
        } else {
            player.sendMessage(Text.mm("<green>✔ Salvaged <white>" + count + "x " + custom.coloredName()
                    + "<green> for <gold>" + Text.number(coins) + " coins</gold>!"));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
    }

    private void salvageInventory(Player player) {
        long totalCoins = 0;
        int totalItems = 0;
        int totalShards = 0;

        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) continue;
            CustomItem custom = plugin.items().customOf(stack);
            if (custom == null) continue;

            // Only salvage resources, mob drops, and enchanted materials via bulk salvage (never active gear)
            if (custom.type() == CustomItem.Type.RESOURCE || custom.type() == CustomItem.Type.ENCHANTED) {
                int count = stack.getAmount();
                totalCoins += (long) (custom.sell() * 0.75 * count);
                totalItems += count;
                totalShards += (custom.rarity().ordinal() > 1 ? 1 : 0);
                contents[i] = null;
            }
        }

        if (totalItems == 0) {
            player.sendMessage(Text.mm("<gray>No salvageable materials found in your inventory."));
            return;
        }

        player.getInventory().setStorageContents(contents);
        gg.aetherfall.core.economy.Eco.deposit(player, totalCoins);
        if (totalShards > 0 && plugin.items().isValidKey("aether_shard")) {
            plugin.items().give(player, "aether_shard", totalShards);
            player.sendMessage(Text.mm("<green>✔ Salvaged <white>" + totalItems + " items</white> for <gold>"
                    + Text.number(totalCoins) + " coins</gold> and <aqua>" + totalShards + "x Aether Shards</aqua>!"));
        } else {
            player.sendMessage(Text.mm("<green>✔ Salvaged <white>" + totalItems + " items</white> for <gold>"
                    + Text.number(totalCoins) + " coins</gold>!"));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
    }
}
