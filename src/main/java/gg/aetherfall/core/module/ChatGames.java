package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/** Quick chat challenges (unscramble / math / type-race) that reward the fastest player. */
public final class ChatGames implements Listener {
    private record Game(String answer, long startedAt, BukkitTask timeout) {}

    private final AetherCore plugin;
    private final AtomicReference<Game> active = new AtomicReference<>();
    private BukkitTask task;

    public ChatGames(AetherCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) task.cancel();
        long interval = Math.max(60, plugin.getConfig().getLong("chatgames.interval-seconds", 540)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> launch(false), interval, interval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        Game game = active.getAndSet(null);
        if (game != null && game.timeout() != null) game.timeout().cancel();
    }

    /** Starts a game now. Returns false if one is already running (or too few players and not forced). */
    public boolean launch(boolean force) {
        if (active.get() != null) return false;
        if (!force && Bukkit.getOnlinePlayers().size() < plugin.getConfig().getInt("chatgames.min-players", 2)) return false;
        List<String> words = plugin.getConfig().getStringList("chatgames.words");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String kind;
        String puzzle;
        String answer;
        int pick = words.isEmpty() ? 1 : r.nextInt(3);
        if (pick == 0) {
            answer = words.get(r.nextInt(words.size())).toLowerCase(Locale.ROOT);
            puzzle = scramble(answer);
            kind = "chatgame-unscramble";
        } else if (pick == 1) {
            int a = r.nextInt(6, 60), b = r.nextInt(3, 40);
            switch (r.nextInt(3)) {
                case 0 -> { puzzle = a + " + " + b; answer = String.valueOf(a + b); }
                case 1 -> { puzzle = (a + b) + " - " + b; answer = String.valueOf(a); }
                default -> { int x = r.nextInt(3, 13), y = r.nextInt(3, 13); puzzle = x + " × " + y; answer = String.valueOf(x * y); }
            }
            kind = "chatgame-math";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(r.nextInt(31)));
            answer = sb.toString();
            puzzle = answer;
            kind = "chatgame-type";
        }
        long coins = plugin.getConfig().getLong("chatgames.coins", 100);
        long timeoutTicks = plugin.getConfig().getLong("chatgames.timeout-seconds", 60) * 20L;
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Game g = active.getAndSet(null);
            if (g != null) plugin.broadcast("chatgame-timeout", Map.of("answer", g.answer));
        }, timeoutTicks);
        active.set(new Game(answer, System.currentTimeMillis(), timeout));
        plugin.broadcast(kind, Map.of("puzzle", puzzle, "coins", Text.number(coins)));
        return true;
    }

    private static String scramble(String word) {
        List<Character> chars = new ArrayList<>();
        for (char c : word.toCharArray()) chars.add(c);
        String out = word;
        for (int attempt = 0; attempt < 10 && out.equals(word); attempt++) {
            Collections.shuffle(chars);
            StringBuilder sb = new StringBuilder();
            for (char c : chars) sb.append(c);
            out = sb.toString();
        }
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Game game = active.get();
        if (game == null) return;
        String said = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase(Locale.ROOT);
        if (!said.equals(game.answer) || !active.compareAndSet(game, null)) return;
        Player winner = event.getPlayer();
        double seconds = (System.currentTimeMillis() - game.startedAt) / 1000.0;
        Bukkit.getScheduler().runTask(plugin, () -> {
            game.timeout.cancel();
            plugin.broadcast("chatgame-won", Map.of("player", winner.getName(),
                    "seconds", String.format(Locale.ROOT, "%.1f", seconds), "answer", game.answer));
            if (winner.isOnline()) {
                plugin.giveCoins(winner, plugin.getConfig().getLong("chatgames.coins", 100));
                winner.playSound(winner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            }
        });
    }
}
