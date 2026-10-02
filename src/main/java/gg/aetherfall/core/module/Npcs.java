package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/** Village NPCs: right-click opens the matching menu. They can't be hurt, traded with or converted. */
public final class Npcs implements Listener {
    private final AetherCore plugin;
    private final NamespacedKey key;
    private final NamespacedKey displayKey;
    private final NamespacedKey buildKey;

    public Npcs(AetherCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "npc");
        this.displayKey = new NamespacedKey(plugin, "spawn_display");
        this.buildKey = new NamespacedKey(plugin, "build");
    }

    private String action(Entity e) {
        return e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEntityEvent event) {
        String action = action(event.getRightClicked());
        if (action == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.6f, 1.1f);
        switch (action) {
            case "bazaar" -> plugin.bazaar().open(p);
            case "ah" -> plugin.auctionMenus().openHub(p);
            case "shop" -> plugin.shop().open(p);
            case "forge" -> plugin.forge().open(p);
            case "quests" -> plugin.quests().open(p);
            case "daily" -> plugin.daily().open(p);
            default -> plugin.menus().openMain(p);
        }
    }

    /** Region flags like mob-spawning=deny must never block our own NPCs. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM && action(event.getEntity()) != null) event.setCancelled(false);
    }

    /** Removes NPCs/signs left over from an older village build when their chunk loads. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        long current = plugin.getConfig().getLong("village-build-id", 0);
        if (current == 0) return;
        for (Entity e : event.getEntities()) {
            var pdc = e.getPersistentDataContainer();
            if (!pdc.has(key) && !pdc.has(displayKey)) continue;
            Long build = pdc.get(buildKey, PersistentDataType.LONG);
            if (build == null || build != current) e.remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (action(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (action(event.getEntity()) != null) event.setCancelled(true);
    }
}
