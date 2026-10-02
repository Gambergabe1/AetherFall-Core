package gg.aetherfall.core.economy;

import gg.aetherfall.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "Type a number in chat" prompts used by the Bazaar for custom prices and amounts. */
public final class ChatPrompt implements Listener {
    private record Pending(Consumer<String> handler, long expires) {}

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String question, Consumer<String> handler) {
        player.closeInventory();
        player.sendMessage(Text.mm("<gold>» </gold>" + question + " <dark_gray>(type <gray>cancel</gray> to abort)"));
        pending.put(player.getUniqueId(), new Pending(handler, System.currentTimeMillis() + 60_000));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        if (System.currentTimeMillis() > p.expires) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.equalsIgnoreCase("cancel")) {
            event.getPlayer().sendMessage(Text.mm("<gray>Cancelled."));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) p.handler.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    /** Parses "1.5k", "2m", "1,000", "64" etc. Returns NaN if invalid. */
    public static double parseNumber(String s) {
        String t = s.toLowerCase().replace(",", "").replace("coins", "").trim();
        double mult = 1;
        if (t.endsWith("k")) { mult = 1_000; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m")) { mult = 1_000_000; t = t.substring(0, t.length() - 1); }
        try {
            double v = Double.parseDouble(t) * mult;
            return Double.isFinite(v) && v > 0 ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
