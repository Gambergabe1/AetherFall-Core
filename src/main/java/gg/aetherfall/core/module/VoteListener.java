package gg.aetherfall.core.module;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Isolated so AetherCore still loads when NuVotifier isn't installed. */
public final class VoteListener implements Listener {
    private final Plugin plugin;
    private final VoteRewards rewards;

    public VoteListener(Plugin plugin, VoteRewards rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
    }

    @EventHandler
    public void onVote(VotifierEvent event) {
        Vote vote = event.getVote();
        String user = vote.getUsername();
        String service = vote.getServiceName();
        if (Bukkit.isPrimaryThread()) rewards.onVote(user, service);
        else Bukkit.getScheduler().runTask(plugin, () -> rewards.onVote(user, service));
    }
}
