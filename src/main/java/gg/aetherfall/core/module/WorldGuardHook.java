package gg.aetherfall.core.module;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.bukkit.Location;

/** Isolated so AetherCore still loads when WorldGuard isn't installed. */
final class WorldGuardHook {
    private WorldGuardHook() {}

    static boolean protectSpawn(Location center, int radius) {
        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(center.getWorld()));
        if (rm == null) return false;
        var world = center.getWorld();
        ProtectedCuboidRegion region = new ProtectedCuboidRegion("spawn",
                BlockVector3.at(center.getBlockX() - radius, world.getMinHeight(), center.getBlockZ() - radius),
                BlockVector3.at(center.getBlockX() + radius, world.getMaxHeight() - 1, center.getBlockZ() + radius));
        region.setPriority(10);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.PVP, StateFlag.State.DENY);
        region.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
        region.setFlag(Flags.MOB_DAMAGE, StateFlag.State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        region.setFlag(Flags.FIRE_SPREAD, StateFlag.State.DENY);
        region.setFlag(Flags.LAVA_FLOW, StateFlag.State.DENY);
        region.setFlag(Flags.LIGHTER, StateFlag.State.DENY);
        region.setFlag(Flags.ENDER_BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.RAVAGER_RAVAGE, StateFlag.State.DENY);
        region.setFlag(Flags.FALL_DAMAGE, StateFlag.State.DENY);
        region.setFlag(Flags.LEAF_DECAY, StateFlag.State.DENY);
        region.setFlag(Flags.USE, StateFlag.State.ALLOW);
        rm.addRegion(region);
        return true;
    }

    /** Boss arena: nobody can build or grief, but mobs and bosses fight normally. */
    static void protectArena(Location center, int radius) {
        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(center.getWorld()));
        if (rm == null) return;
        ProtectedCuboidRegion region = new ProtectedCuboidRegion("arena",
                BlockVector3.at(center.getBlockX() - radius, center.getWorld().getMinHeight(), center.getBlockZ() - radius),
                BlockVector3.at(center.getBlockX() + radius, center.getWorld().getMaxHeight() - 1, center.getBlockZ() + radius));
        region.setPriority(10);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.PVP, StateFlag.State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        region.setFlag(Flags.FIRE_SPREAD, StateFlag.State.DENY);
        region.setFlag(Flags.LAVA_FLOW, StateFlag.State.DENY);
        region.setFlag(Flags.LIGHTER, StateFlag.State.DENY);
        rm.addRegion(region);
    }
}
