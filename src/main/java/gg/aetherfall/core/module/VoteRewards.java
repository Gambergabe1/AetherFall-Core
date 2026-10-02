package gg.aetherfall.core.module;

import gg.aetherfall.core.AetherCore;
import gg.aetherfall.core.data.PlayerData;
import gg.aetherfall.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Rewards server-list votes (via NuVotifier), queues votes for offline players,
 * and runs a server-wide vote party every N votes.
 */
public final class VoteRewards {
    private static final String PARTY_KEY = "vote_party_progress";
    private final AetherCore plugin;
    private long partyProgress;

    public VoteRewards(AetherCore plugin) {
        this.plugin = plugin;
        this.partyProgress = plugin.data().getMeta(PARTY_KEY);
    }

    public int partyGoal() {
        return Math.max(1, plugin.getConfig().getInt("voting.party.votes-needed", 50));
    }

    public long partyLeft() {
        return partyGoal() - partyProgress;
    }

    /** Main thread. Called for every vote received from any site. */
    public void onVote(String username, String service) {
        if (username == null || username.isBlank() || !username.matches("[A-Za-z0-9_.]{1,20}")) return;
        Player player = Bukkit.getPlayerExact(username);
        PlayerData loaded = player != null ? plugin.data().get(player) : plugin.data().findLoaded(username);
        if (player != null && loaded != null) {
            loaded.votes++;
            reward(player, service);
            plugin.data().saveAsync(loaded);
        } else if (loaded != null) {
            loaded.votes++;
            loaded.pendingVotes++; // logging in right now; paid when they finish joining
        } else {
            plugin.data().addPendingVote(username, known -> {
                if (!known) plugin.getLogger().info("Vote from unknown player '" + username + "' via " + service + " ignored");
            });
        }
        advanceParty();
    }

    /** Pays out votes that arrived while the player was offline. */
    public void payPending(Player player, PlayerData d) {
        if (d.pendingVotes <= 0) return;
        int n = d.pendingVotes;
        d.pendingVotes = 0;
        for (int i = 0; i < n; i++) reward(player, "while you were away");
        plugin.data().saveAsync(d);
    }

    private void reward(Player player, String service) {
        var cfg = plugin.getConfig();
        long coins = cfg.getLong("voting.coins", 150);
        plugin.giveCoins(player, coins);
        plugin.runCommands(cfg.getStringList("voting.commands"), player);
        plugin.broadcast("vote-broadcast", Map.of("player", player.getName(), "service", service, "coins", Text.number(coins)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
    }

    private void advanceParty() {
        partyProgress++;
        long goal = partyGoal();
        if (partyProgress >= goal) {
            partyProgress = 0;
            startParty();
        } else if (goal - partyProgress <= 5 || partyProgress % 10 == 0) {
            plugin.broadcast("vote-party-progress", Map.of("left", goal - partyProgress));
        }
        plugin.data().setMetaAsync(PARTY_KEY, partyProgress);
    }

    public void startParty() {
        var cfg = plugin.getConfig();
        long coins = cfg.getLong("voting.party.coins", 500);
        plugin.broadcast("vote-party", Map.of("coins", Text.number(coins)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.giveCoins(p, coins);
            plugin.runCommands(cfg.getStringList("voting.party.commands"), p);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    public void showSites(CommandSender sender) {
        var cfg = plugin.getConfig();
        String prefix = cfg.getString("prefix", "");
        sender.sendMessage(Text.mm(plugin.raw("vote-header"), Map.of("coins", Text.number(cfg.getLong("voting.coins", 150)))));
        List<Map<?, ?>> sites = cfg.getMapList("voting.sites");
        int i = 1;
        for (Map<?, ?> site : sites) {
            String name = String.valueOf(site.get("name"));
            String url = String.valueOf(site.get("url"));
            sender.sendMessage(Text.mm("<gray> " + i++ + ". <click:open_url:'" + url + "'><hover:show_text:'<gray>" + url
                    + "'><aqua><u>" + Text.fill("{n}", Map.of("n", name)) + "</u></aqua></hover></click>"));
        }
        if (sender instanceof Player p && plugin.data().get(p) != null) {
            sender.sendMessage(Text.mm(prefix + "<gray>Your votes: <yellow>" + plugin.data().get(p).votes
                    + "</yellow> · Vote party in <light_purple>" + partyLeft() + "</light_purple> votes"));
        }
    }
}
