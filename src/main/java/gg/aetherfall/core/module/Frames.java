package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /frame — invisible "display" item frames for shops and builds.
 * Locked frames can't be emptied, rotated or broken by anyone without aethercore.frames.bypass (ops have it).
 */
public final class Frames implements Listener, TabExecutor {
    private final AetherCore plugin;
    private final NamespacedKey lockKey;

    public Frames(AetherCore plugin) {
        this.plugin = plugin;
        this.lockKey = new NamespacedKey(plugin, "frame_locked");
    }

    private boolean locked(Entity e) {
        return e instanceof ItemFrame && e.getPersistentDataContainer().has(lockKey);
    }

    private static boolean bypass(Entity e) {
        return e instanceof Player p && p.hasPermission("aethercore.frames.bypass");
    }

    // ── command ───────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.mm("<red>Only players can use this."));
            return true;
        }
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "display";
        if (!List.of("display", "invis", "lock", "unlock", "show", "reset").contains(mode)) {
            p.sendMessage(Text.mm("<gray>Look at an item frame and use: <yellow>/frame display</yellow> <dark_gray>(invisible + locked)</dark_gray>, "
                    + "<yellow>/frame invis</yellow>, <yellow>/frame lock</yellow>, <yellow>/frame reset</yellow>. Add a radius to do many: <yellow>/frame display 10"));
            return true;
        }
        List<ItemFrame> targets = new ArrayList<>();
        if (args.length > 1) {
            int radius;
            try {
                radius = Math.max(1, Math.min(32, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                p.sendMessage(Text.mm("<red>Radius must be a number (1-32)."));
                return true;
            }
            for (Entity e : p.getNearbyEntities(radius, radius, radius)) if (e instanceof ItemFrame f) targets.add(f);
        } else {
            RayTraceResult hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 6, 0.3,
                    e -> e instanceof ItemFrame);
            if (hit != null && hit.getHitEntity() instanceof ItemFrame f) targets.add(f);
        }
        if (targets.isEmpty()) {
            p.sendMessage(Text.mm("<red>No item frame found — look directly at one (within 6 blocks) or add a radius."));
            return true;
        }
        for (ItemFrame f : targets) {
            switch (mode) {
                case "display" -> { f.setVisible(false); lock(f, true); }
                case "invis" -> f.setVisible(!f.isVisible());
                case "show" -> f.setVisible(true);
                case "lock" -> lock(f, !locked(f));
                case "unlock" -> lock(f, false);
                case "reset" -> { f.setVisible(true); lock(f, false); }
                default -> { }
            }
        }
        ItemFrame first = targets.getFirst();
        p.sendMessage(Text.mm("<green>Updated " + targets.size() + " item frame" + (targets.size() == 1 ? "" : "s") + "</green> <gray>— "
                + (first.isVisible() ? "visible" : "invisible") + ", " + (locked(first) ? "<gold>locked</gold>" : "unlocked") + "."));
        return true;
    }

    private void lock(ItemFrame f, boolean on) {
        if (on) f.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, (byte) 1);
        else f.getPersistentDataContainer().remove(lockKey);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) return List.of("display", "invis", "lock", "unlock", "show", "reset").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2) return List.of("5", "10", "20");
        return List.of();
    }

    // ── protection ────────────────────────────────────────────

    /** Rotating or putting items in. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (locked(event.getRightClicked()) && !bypass(event.getPlayer())) event.setCancelled(true);
    }

    /** Punching the item out (players, arrows, mobs). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!locked(event.getEntity())) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) damager = shooter;
        if (!bypass(damager)) event.setCancelled(true);
    }

    /** Explosions, fire, etc. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (locked(event.getEntity()) && !(event instanceof EntityDamageByEntityEvent)) event.setCancelled(true);
    }

    /** Breaking the frame itself (players, explosions, block physics). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        if (!locked(event.getEntity())) return;
        if (event instanceof HangingBreakByEntityEvent byEntity) {
            Entity remover = byEntity.getRemover();
            if (remover instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) remover = shooter;
            if (bypass(remover)) return;
        }
        event.setCancelled(true);
    }
}
