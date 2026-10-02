package gg.aetherfall.core.module;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Optional GriefPrevention bridge for minions, which place an entity instead of a block. */
final class MinionProtectionHook {
    private MinionProtectionHook() { }

    static boolean canPlace(Player player, Location location) {
        if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return true;
        GriefPrevention gp = GriefPrevention.instance;
        if (gp == null || gp.dataStore == null) return true;
        Claim claim = gp.dataStore.getClaimAt(location, true, null);
        return claim == null || claim.allowBuild(player, Material.ARMOR_STAND) == null;
    }
}
