package gg.aetherfall.core.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;

/** Everything AetherCore remembers about a player. Mutated on the main thread only. */
public final class PlayerData {
    public final UUID uuid;
    public String name;
    public boolean isNew;

    public long firstJoin;
    public long lastSeen;
    public long playtime;          // seconds of active (non-AFK) play
    public int streak;             // current daily-reward streak
    public int bestStreak;
    public int streakSaves = 1;
    public long lastClaimDay = -1; // epoch day of last /daily claim
    public int questsDone;         // lifetime completed quests
    public int votes;              // lifetime server-list votes
    public int pendingVotes;       // votes received while offline, rewarded on next join
    public int tutorial;           // bitmask of completed tutorial steps (bit 30 = finished/skipped)
    public boolean timber = true;
    public boolean vein = true;
    public final Set<String> milestones = new HashSet<>();
    public final Set<String> collections = new HashSet<>();
    public final java.util.Map<String, Integer> bestiary = new LinkedHashMap<>();
    /** Stable reward claims prevent repeated kill events or reconnects from duplicating milestones. */
    public final Set<String> bestiaryRewardsClaimed = new HashSet<>();
    public String slayerType = "";
    public int slayerTier;
    public int slayerKills;
    public final Set<String> achievements = new HashSet<>();
    public String selectedTitle = "";
    public final java.util.Map<String, MinionState> minions = new LinkedHashMap<>();
    /** Skill progression used by the RPG stat framework. Keys are lowercase skill ids. */
    public final java.util.Map<String, Long> skillXp = new LinkedHashMap<>();
    public final java.util.Map<String, Integer> skillLevels = new LinkedHashMap<>();
    public final Set<String> skillRewardsClaimed = new HashSet<>();
    /** Fields whose raw database value was quarantined instead of silently discarded. */
    public final Set<String> quarantinedFields = new HashSet<>();

    public long questDay = -1;
    public final List<QuestState> quests = new ArrayList<>();
    public boolean questBonusClaimed;
    public long weeklyPeriod = -1;
    public final List<WeeklyState> weeklyContracts = new ArrayList<>();
    public boolean weeklyBonusClaimed;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public static final class QuestState {
        public final String id;
        public int progress;
        public boolean done;

        public QuestState(String id, int progress, boolean done) {
            this.id = id;
            this.progress = progress;
            this.done = done;
        }
    }

    public static final class WeeklyState {
        public final String id;
        public int progress;
        public boolean done;
        public WeeklyState(String id, int progress, boolean done) {
            this.id = id;
            this.progress = progress;
            this.done = done;
        }
    }

    public static final class MinionState {
        /** Stable identity so a player can own multiple minions of one type. */
        public final String id;
        public final String type;
        /** Persisted placement identity; null world means legacy virtual minion. */
        public String world;
        public int x;
        public int y;
        public int z;
        public long collectedAt;
        public int stored;
        public int level;
        public int storageLevel;
        public long fuelUntil;
        public MinionState(String type, long collectedAt, int stored) { this(java.util.UUID.randomUUID().toString(), type, collectedAt, stored, 1, 0, 0, null, 0, 0, 0); }
        public MinionState(String type, long collectedAt, int stored, int level) { this(java.util.UUID.randomUUID().toString(), type, collectedAt, stored, level, 0, 0, null, 0, 0, 0); }
        public MinionState(String type, long collectedAt, int stored, int level, long fuelUntil) { this(java.util.UUID.randomUUID().toString(), type, collectedAt, stored, level, fuelUntil, 0, null, 0, 0, 0); }
        public MinionState(String type, long collectedAt, int stored, int level, long fuelUntil, int storageLevel) { this(java.util.UUID.randomUUID().toString(), type, collectedAt, stored, level, fuelUntil, storageLevel, null, 0, 0, 0); }
        public MinionState(String type, long collectedAt, int stored, int level, long fuelUntil, int storageLevel, String world, int x, int y, int z) {
            this(java.util.UUID.randomUUID().toString(), type, collectedAt, stored, level, fuelUntil, storageLevel, world, x, y, z);
        }
        public MinionState(String id, String type, long collectedAt, int stored, int level, long fuelUntil, int storageLevel, String world, int x, int y, int z) {
            this.id = id == null || id.isBlank() ? java.util.UUID.randomUUID().toString() : id;
            this.type = type; this.collectedAt = collectedAt; this.stored = Math.max(0, stored); this.level = Math.max(1, level);
            this.fuelUntil = Math.max(0, fuelUntil); this.storageLevel = Math.max(0, storageLevel);
            this.world = world; this.x = x; this.y = y; this.z = z;
        }

        public boolean placed() { return world != null && !world.isBlank(); }
        public void place(String world, int x, int y, int z) { this.world = world; this.x = x; this.y = y; this.z = z; }
        public void unplace() { this.world = null; this.x = this.y = this.z = 0; }
    }

    String encodeWeekly() {
        StringBuilder sb = new StringBuilder();
        for (WeeklyState q : weeklyContracts) {
            if (!sb.isEmpty()) sb.append('|');
            sb.append(q.id).append(':').append(q.progress).append(':').append(q.done ? 1 : 0);
        }
        return sb.toString();
    }

    void decodeWeekly(String raw) {
        weeklyContracts.clear();
        if (raw == null || raw.isBlank()) return;
        for (String part : raw.split("\\|")) {
            String[] f = part.split(":", -1);
            if (f.length != 3 || f[0].isBlank()) continue;
            try {
                int progress = Math.max(0, Integer.parseInt(f[1]));
                if (!f[2].equals("0") && !f[2].equals("1")) continue;
                weeklyContracts.add(new WeeklyState(f[0], progress, "1".equals(f[2])));
            } catch (NumberFormatException ignored) { }
        }
    }

    String encodeQuests() {
        StringBuilder sb = new StringBuilder();
        for (QuestState q : quests) {
            if (!sb.isEmpty()) sb.append('|');
            sb.append(q.id).append(':').append(q.progress).append(':').append(q.done ? 1 : 0);
        }
        return sb.toString();
    }

    void decodeQuests(String raw) {
        quests.clear();
        if (raw == null || raw.isBlank()) return;
        for (String part : raw.split("\\|")) {
            String[] f = part.split(":", -1);
            if (f.length != 3 || f[0].isBlank()) continue;
            try {
                int progress = Math.max(0, Integer.parseInt(f[1]));
                if (!f[2].equals("0") && !f[2].equals("1")) continue;
                quests.add(new QuestState(f[0], progress, "1".equals(f[2])));
            } catch (NumberFormatException ignored) { }
        }
    }

    /** Copy used for async saves so the DB thread never reads live, mutating state. */
    PlayerData snapshot() {
        PlayerData c = new PlayerData(uuid, name);
        c.firstJoin = firstJoin; c.lastSeen = lastSeen; c.playtime = playtime;
        c.streak = streak; c.bestStreak = bestStreak; c.lastClaimDay = lastClaimDay;
        c.streakSaves = streakSaves;
        c.questsDone = questsDone; c.timber = timber; c.vein = vein;
        c.votes = votes; c.pendingVotes = pendingVotes; c.tutorial = tutorial;
        c.milestones.addAll(milestones);
        c.collections.addAll(collections);
        c.bestiary.putAll(bestiary);
        c.bestiaryRewardsClaimed.addAll(bestiaryRewardsClaimed);
        c.slayerType = slayerType; c.slayerTier = slayerTier; c.slayerKills = slayerKills;
        c.achievements.addAll(achievements); c.selectedTitle = selectedTitle;
        for (MinionState m : minions.values()) c.minions.put(m.id, new MinionState(m.id, m.type, m.collectedAt, m.stored, m.level, m.fuelUntil, m.storageLevel, m.world, m.x, m.y, m.z));
        c.skillXp.putAll(skillXp); c.skillLevels.putAll(skillLevels);
        c.skillRewardsClaimed.addAll(skillRewardsClaimed);
        c.quarantinedFields.addAll(quarantinedFields);
        c.questDay = questDay; c.questBonusClaimed = questBonusClaimed;
        c.weeklyPeriod = weeklyPeriod; c.weeklyBonusClaimed = weeklyBonusClaimed;
        for (QuestState q : quests) c.quests.add(new QuestState(q.id, q.progress, q.done));
        for (WeeklyState q : weeklyContracts) c.weeklyContracts.add(new WeeklyState(q.id, q.progress, q.done));
        return c;
    }
}
