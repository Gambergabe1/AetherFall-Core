package gg.aetherfall.core.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Minimal chest GUI: items can't be taken, clicks dispatch to per-slot handlers. */
public class Menu implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer, BiConsumer<Player, ClickType>> handlers = new HashMap<>();
    private BiConsumer<Player, Integer> bottomHandler;

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    public Menu fill(Material pane) {
        ItemStack filler = new ItemBuilder(pane).name(" ").build();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
        return this;
    }

    public Menu set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
        return this;
    }

    public Menu set(int slot, ItemStack item, BiConsumer<Player, ClickType> onClick) {
        inventory.setItem(slot, item);
        handlers.put(slot, onClick);
        return this;
    }

    /** Called with the player-inventory slot when the player clicks their own inventory below the menu. */
    public Menu onBottomClick(BiConsumer<Player, Integer> handler) {
        this.bottomHandler = handler;
        return this;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static final class MenuListener implements org.bukkit.event.Listener {
        @EventHandler(ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder(false) instanceof Menu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getInventory()) {
                if (menu.bottomHandler != null && event.getClickedInventory() != null
                        && event.getClickedInventory().equals(event.getWhoClicked().getInventory())
                        && event.getWhoClicked() instanceof Player player) {
                    menu.bottomHandler.accept(player, event.getSlot());
                    player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.35f, 1.15f);
                }
                return;
            }
            BiConsumer<Player, ClickType> handler = menu.handlers.get(event.getRawSlot());
            if (handler != null && event.getWhoClicked() instanceof Player player) {
                handler.accept(player, event.getClick());
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.35f, 1.15f);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
        }
    }
}
