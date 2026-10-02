package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Accelerates crop growth in chunks rendered by players by around 350%.
 * Combines native BlockGrowEvent age acceleration with a light sampling tick in loaded chunks around players.
 */
public final class CropBooster implements Listener {
    private final AetherCore plugin;
    private final Random random = new Random();
    private BukkitTask task;
    private boolean enabled = true;
    private double multiplier = 3.5;

    public CropBooster(AetherCore plugin) {
        this.plugin = plugin;
        reload();
        start();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("crop-growth.enabled", true);
        this.multiplier = plugin.getConfig().getDouble("crop-growth.multiplier", 3.5);
    }

    public void start() {
        if (task != null) task.cancel();
        if (!enabled) return;

        // Sample nearby chunks around active players every second (20 ticks)
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickNearbyChunks, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Extra boost when a crop naturally progresses */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        if (!enabled) return;
        BlockData data = event.getNewState().getBlockData();
        if (data instanceof Ageable ageable) {
            int current = ageable.getAge();
            int max = ageable.getMaximumAge();
            if (current < max) {
                // ~350% boost gives a high chance to advance extra stage
                if (random.nextDouble() < 0.65) {
                    ageable.setAge(Math.min(max, current + 1));
                    event.getNewState().setBlockData(ageable);
                }
            }
        }
    }

    private void tickNearbyChunks() {
        if (!enabled) return;
        var players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return;

        Set<Chunk> chunksToSample = new HashSet<>();
        for (Player player : players) {
            Chunk center = player.getLocation().getChunk();
            int cx = center.getX();
            int cz = center.getZ();
            var world = center.getWorld();

            // Sample in a 5x5 chunk area around each player (radius of 2 chunks)
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (world.isChunkLoaded(cx + dx, cz + dz)) {
                        chunksToSample.add(world.getChunkAt(cx + dx, cz + dz));
                    }
                }
            }
        }

        for (Chunk chunk : chunksToSample) {
            // Sample a few random coordinates in each player-rendered chunk
            for (int i = 0; i < 4; i++) {
                int rx = random.nextInt(16);
                int rz = random.nextInt(16);
                int highestY = chunk.getWorld().getHighestBlockYAt(chunk.getX() * 16 + rx, chunk.getZ() * 16 + rz);
                if (highestY <= 0) continue;

                // Inspect the surface block or block right above/below
                for (int y = Math.max(1, highestY - 2); y <= Math.min(highestY + 1, chunk.getWorld().getMaxHeight() - 1); y++) {
                    Block block = chunk.getBlock(rx, y, rz);
                    if (isCrop(block.getType())) {
                        advanceCrop(block);
                        break;
                    }
                }
            }
        }
    }

    private boolean isCrop(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, MELON_STEM, PUMPKIN_STEM,
                 TORCHFLOWER_CROP, PITCHER_CROP, NETHER_WART, COCOA, SWEET_BERRY_BUSH, SUGAR_CANE, CACTUS -> true;
            default -> false;
        };
    }

    private void advanceCrop(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Ageable ageable) {
            int age = ageable.getAge();
            int max = ageable.getMaximumAge();
            if (age < max && block.getLightLevel() >= 8) {
                ageable.setAge(age + 1);
                block.setBlockData(ageable, true);
                if (random.nextInt(10) == 0) {
                    block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                            block.getLocation().add(0.5, 0.4, 0.5), 2, 0.2, 0.2, 0.2, 0);
                }
            }
        }
    }
}
