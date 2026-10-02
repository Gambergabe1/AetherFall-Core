package gg.aetherfall.core.items;

import gg.aetherfall.core.AetherCore;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps custom items from being lost or abused through vanilla mechanics:
 * no placing, eating, throwing, smelting, burning or crafting them into vanilla recipes.
 */
public final class ItemGuard implements Listener {
    private final AetherCore plugin;

    public ItemGuard(AetherCore plugin) {
        this.plugin = plugin;
    }

    private boolean custom(ItemStack s) {
        return plugin.items().idOf(s) != null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (custom(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        CustomItem c = plugin.items().customOf(event.getItem());
        if (c != null && c.inert()) {
            event.setUseItemInHand(Event.Result.DENY);
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) event.setUseInteractedBlock(Event.Result.ALLOW);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        CustomItem c = plugin.items().customOf(event.getItem());
        if (c != null && c.inert()) event.setCancelled(true);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack s : event.getInventory().getMatrix()) {
            if (custom(s)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onSmithing(PrepareSmithingEvent event) {
        for (ItemStack s : event.getInventory().getContents()) {
            if (custom(s)) {
                event.setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        if (custom(event.getSource())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(FurnaceBurnEvent event) {
        if (custom(event.getFuel())) event.setCancelled(true);
    }
}
