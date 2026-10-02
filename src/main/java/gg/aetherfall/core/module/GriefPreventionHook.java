package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Isolated so AetherCore still loads without GriefPrevention. Feeds the tutorial's "claim land" step. */
public final class GriefPreventionHook implements Listener {
    private final AetherCore plugin;

    public GriefPreventionHook(AetherCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaim(ClaimCreatedEvent event) {
        if (event.getCreator() instanceof Player p) plugin.onboarding().complete(p, Onboarding.Step.CLAIM);
    }
}
