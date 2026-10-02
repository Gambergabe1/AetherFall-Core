package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Loads data before join, greets players, and saves on quit. */
public final class Sessions implements Listener {
    /** Players that passed pre-login but haven't joined yet (uuid -> time). */
    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();
    private final AetherCore plugin;
    private final NamespacedKey cosmeticKey;

    public Sessions(AetherCore plugin) {
        this.plugin = plugin;
        this.cosmeticKey = new NamespacedKey(plugin, "cosmetic");
    }

    public static boolean isPending(UUID uuid) {
        Long t = PENDING.get(uuid);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 60_000) {
            PENDING.remove(uuid);
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        PENDING.put(event.getUniqueId(), System.currentTimeMillis());
        try {
            plugin.data().loadBlocking(event.getUniqueId(), event.getName());
        } catch (Exception e) {
            PENDING.remove(event.getUniqueId());
            plugin.getLogger().log(Level.SEVERE, "Could not load data for " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Your profile failed to load. Please try again in a moment."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PENDING.remove(player.getUniqueId());
        PlayerData data = plugin.data().ensureLoaded(player);
        if (data == null) {
            plugin.getLogger().severe("Refusing join initialization for " + player.getName() + " because the profile could not be loaded.");
            Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text("Your profile could not be loaded safely. Please reconnect shortly.")));
            return;
        }
        data.name = player.getName();
        long now = System.currentTimeMillis();
        long previousSeen = data.lastSeen;
        boolean firstJoin = data.isNew;
        data.lastSeen = now;
        plugin.data().recordFunnel(player, "session_join");
        if (firstJoin) plugin.data().recordFunnel(player, "first_join");
        else {
            plugin.data().recordFunnel(player, "return_session");
            if (previousSeen > 0 && now - data.firstJoin >= 86_400_000L) plugin.data().recordFunnel(player, "return_24h");
            if (previousSeen > 0 && now - data.firstJoin >= 7L * 86_400_000L) plugin.data().recordFunnel(player, "return_7d");
        }
        plugin.playtime().markActive(player);
        plugin.quests().ensureToday(data);
        offerPack(player);

        // Let other join handlers (spawn teleports, kits) run first.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (data.isNew) greetNew(player);
            else greetReturning(player, data);
            for (String hint : plugin.getConfig().getStringList("welcome.join-hints")) {
                player.sendMessage(Text.mm(plugin.getConfig().getString("prefix", "") + hint));
            }
            plugin.daily().remind(player, data);
            plugin.votes().payPending(player, data);
        }, 20L);
    }

    /** Offers the Aetherfall resource pack (custom item textures). Optional: declining keeps vanilla looks. */
    private void offerPack(Player player) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", true)) return;
        var packServer = plugin.packServer();
        if (packServer == null) return;
        java.io.File file = packServer.getPackFile();
        if (!file.exists()) return;
        String url = packServer.getPackUrl();
        try {
            String sha1 = packServer.getPackHash();
            if (sha1 == null) return;
            var info = net.kyori.adventure.resource.ResourcePackInfo.resourcePackInfo(
                    java.util.UUID.nameUUIDFromBytes(("aetherfall-" + sha1).getBytes()), java.net.URI.create(url), sha1);
            player.sendResourcePacks(net.kyori.adventure.resource.ResourcePackRequest.resourcePackRequest()
                    .packs(info).required(cfg.getBoolean("resource-pack.required", false))
                    .prompt(Text.mm(cfg.getString("resource-pack.prompt", "<gray>Custom textures for Aetherfall's items. Recommended!")))
                    .replace(true).build());
        } catch (Exception e) {
            plugin.getLogger().warning("Could not offer resource pack: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.data().get(player);
        if (data != null) data.lastSeen = System.currentTimeMillis();
        plugin.playtime().forget(player);
        plugin.quests().forget(player.getUniqueId());
        plugin.data().unload(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw && fw.getPersistentDataContainer().has(cosmeticKey)) event.setCancelled(true);
    }

    private void greetNew(Player player) {
        PlayerData data = plugin.data().get(player);
        if (data != null) data.isNew = false;
        var cfg = plugin.getConfig();
        Map<String, Object> vars = Map.of("player", player.getName(), "count", Text.number(plugin.data().uniquePlayers()));
        Bukkit.broadcast(Text.mm(cfg.getString("welcome.first-join-broadcast", ""), vars));
        player.showTitle(Title.title(
                Text.mm(cfg.getString("welcome.first-join-title", ""), vars),
                Text.mm(cfg.getString("welcome.first-join-subtitle", ""), vars),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(1))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        plugin.runCommands(cfg.getStringList("welcome.first-join-commands"), player);
        launchFirework(player);
    }

    private void greetReturning(Player player, PlayerData data) {
        var cfg = plugin.getConfig();
        Map<String, Object> vars = Map.of("player", player.getName(), "streak", data.streak);
        player.showTitle(Title.title(
                Text.mm(cfg.getString("welcome.returning-title", ""), vars),
                Text.mm(cfg.getString("welcome.returning-subtitle", ""), vars),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.4f);
        // Veteran+ get their arrival announced to everyone.
        String announce = plugin.perks().of(data).joinAnnounce();
        if (announce != null && !announce.isBlank()) {
            Bukkit.broadcast(Text.mm(Text.fill(announce, Map.of("player", player.getName()))));
        }
    }

    private void launchFirework(Player player) {
        player.getWorld().spawn(player.getLocation().add(0, 1, 0), Firework.class, fw -> {
            var meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                    .withColor(Color.fromRGB(0x8B5CF6), Color.fromRGB(0x22D3EE)).withFade(Color.WHITE).flicker(true).build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
            // Purely cosmetic: never hurt the new player.
            fw.getPersistentDataContainer().set(cosmeticKey, PersistentDataType.BOOLEAN, true);
        });
    }
}
