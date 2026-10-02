package gg.aetherfall.core.module;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers recently player-placed blocks so quests can't be farmed by placing and
 * re-breaking the same block. Bounded LRU so memory stays flat on busy servers.
 */
public final class PlacedBlocks implements Listener {
    private static final int MAX = 300_000;

    private record Key(UUID world, int x, int y, int z) {
        static Key of(Block b) {
            return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final Map<Key, Boolean> placed = new LinkedHashMap<>(16_384, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Boolean> eldest) {
            return size() > MAX;
        }
    };

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        placed.put(Key.of(event.getBlockPlaced()), Boolean.TRUE);
    }

    /** True if the block was placed by a player; also forgets it (it's being broken). */
    public boolean consume(Block block) {
        return placed.remove(Key.of(block)) != null;
    }

    public boolean isPlaced(Block block) {
        return placed.containsKey(Key.of(block));
    }
}
