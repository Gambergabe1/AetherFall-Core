package gg.aetherfall.core.data;

import gg.aetherfall.core.AetherCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * SQLite-backed player storage. All SQL runs on one dedicated thread, so the main
 * thread never blocks on disk and there is no connection contention.
 */
public final class DataManager {
    /** Increment only when a new, idempotent database migration is added. */
    private static final int CURRENT_SCHEMA = 2;
    private final AetherCore plugin;
    private final ExecutorService db = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AetherCore-DB");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();
    private Connection connection;
    private volatile long uniquePlayers;
    private volatile boolean closing;

    public record TopEntry(String name, long value) {}

    public DataManager(AetherCore plugin) {
        this.plugin = plugin;
    }

    public void open() throws Exception {
        db.submit(() -> {
            File file = new File(plugin.getDataFolder(), "data.db");
            backupBeforeOpen(file);
            SQLiteConfig cfg = new SQLiteConfig();
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
            cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            SQLiteDataSource ds = new SQLiteDataSource(cfg);
            ds.setUrl("jdbc:sqlite:" + file.getAbsolutePath());
            connection = ds.getConnection();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid TEXT PRIMARY KEY, name TEXT NOT NULL,
                      first_join INTEGER, last_seen INTEGER, playtime INTEGER DEFAULT 0,
                      streak INTEGER DEFAULT 0, best_streak INTEGER DEFAULT 0, last_claim_day INTEGER DEFAULT -1,
                      quests_done INTEGER DEFAULT 0, timber INTEGER DEFAULT 1, vein INTEGER DEFAULT 1,
                      milestones TEXT DEFAULT '', quest_day INTEGER DEFAULT -1, quest_data TEXT DEFAULT '',
                      quest_bonus INTEGER DEFAULT 0, collections TEXT DEFAULT '', bestiary_data TEXT DEFAULT '', bestiary_rewards TEXT DEFAULT '', achievements TEXT DEFAULT '', selected_title TEXT DEFAULT '', minions_data TEXT DEFAULT '', slayer_type TEXT DEFAULT '', slayer_tier INTEGER DEFAULT 0, slayer_kills INTEGER DEFAULT 0, streak_saves INTEGER DEFAULT 1,
                      weekly_period INTEGER DEFAULT -1, weekly_data TEXT DEFAULT '', weekly_bonus INTEGER DEFAULT 0,
                      skills_xp TEXT DEFAULT '', skills_levels TEXT DEFAULT '', skill_rewards TEXT DEFAULT '')""");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS migration_history (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
                migrate(st);
                setSchemaVersion(st, CURRENT_SCHEMA);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_playtime ON players(playtime DESC)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_votes ON players(votes DESC)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_name ON players(name COLLATE NOCASE)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_quests ON players(quests_done DESC)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_streak ON players(best_streak DESC)");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM players")) {
                    uniquePlayers = rs.next() ? rs.getLong(1) : 0;
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            return null;
        }).get(30, TimeUnit.SECONDS);
    }

    /** Keep a recoverable copy before opening a database that may need migration. */
    private void backupBeforeOpen(File file) {
        if (!file.isFile() || file.length() == 0) return;
        try {
            Path dir = plugin.getDataFolder().toPath().resolve("backups");
            Files.createDirectories(dir);
            String stamp = Long.toString(System.currentTimeMillis());
            Files.copy(file.toPath(), dir.resolve("data-" + stamp + ".db"), StandardCopyOption.COPY_ATTRIBUTES);
            File[] backups = dir.toFile().listFiles((d, n) -> n.startsWith("data-") && n.endsWith(".db"));
            if (backups != null && backups.length > 5) {
                java.util.Arrays.sort(backups, java.util.Comparator.comparingLong(File::lastModified).reversed());
                for (int i = 5; i < backups.length; i++) Files.deleteIfExists(backups[i].toPath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not create a database backup before opening data.db", e);
        }
    }

    /** A unit of SQL work run on the database thread. */
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    /** Runs SQL on the DB thread and waits for the result (startup / rare admin paths only). */
    public <T> T sync(SqlWork<T> work) {
        try {
            return db.submit(() -> work.run(connection)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Database call failed", e);
        }
    }

    /** Queues SQL on the DB thread without waiting. */
    public void async(SqlWork<?> work) {
        db.execute(() -> {
            try {
                work.run(connection);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Async database write failed", e);
            }
        });
    }

    /** Adds columns introduced after the first release, so old databases keep working. */
    private void migrate(Statement st) throws SQLException {
        int version = 0;
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = 'schema_version'"); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) version = rs.getInt(1);
        }
        if (version > CURRENT_SCHEMA) throw new SQLException("Database schema " + version + " is newer than this plugin supports (" + CURRENT_SCHEMA + ")");
        java.util.Set<String> cols = new java.util.HashSet<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(players)")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        if (!cols.contains("votes")) st.executeUpdate("ALTER TABLE players ADD COLUMN votes INTEGER DEFAULT 0");
        if (!cols.contains("pending_votes")) st.executeUpdate("ALTER TABLE players ADD COLUMN pending_votes INTEGER DEFAULT 0");
        if (!cols.contains("tutorial")) st.executeUpdate("ALTER TABLE players ADD COLUMN tutorial INTEGER DEFAULT 0");
        if (!cols.contains("collections")) st.executeUpdate("ALTER TABLE players ADD COLUMN collections TEXT DEFAULT ''");
        if (!cols.contains("bestiary_data")) st.executeUpdate("ALTER TABLE players ADD COLUMN bestiary_data TEXT DEFAULT ''");
        if (!cols.contains("bestiary_rewards")) st.executeUpdate("ALTER TABLE players ADD COLUMN bestiary_rewards TEXT DEFAULT ''");
        if (!cols.contains("achievements")) st.executeUpdate("ALTER TABLE players ADD COLUMN achievements TEXT DEFAULT ''");
        if (!cols.contains("selected_title")) st.executeUpdate("ALTER TABLE players ADD COLUMN selected_title TEXT DEFAULT ''");
        if (!cols.contains("minions_data")) st.executeUpdate("ALTER TABLE players ADD COLUMN minions_data TEXT DEFAULT ''");
        if (!cols.contains("slayer_type")) st.executeUpdate("ALTER TABLE players ADD COLUMN slayer_type TEXT DEFAULT ''");
        if (!cols.contains("slayer_tier")) st.executeUpdate("ALTER TABLE players ADD COLUMN slayer_tier INTEGER DEFAULT 0");
        if (!cols.contains("slayer_kills")) st.executeUpdate("ALTER TABLE players ADD COLUMN slayer_kills INTEGER DEFAULT 0");
        if (!cols.contains("streak_saves")) st.executeUpdate("ALTER TABLE players ADD COLUMN streak_saves INTEGER DEFAULT 1");
        if (!cols.contains("weekly_period")) st.executeUpdate("ALTER TABLE players ADD COLUMN weekly_period INTEGER DEFAULT -1");
        if (!cols.contains("weekly_data")) st.executeUpdate("ALTER TABLE players ADD COLUMN weekly_data TEXT DEFAULT ''");
        if (!cols.contains("weekly_bonus")) st.executeUpdate("ALTER TABLE players ADD COLUMN weekly_bonus INTEGER DEFAULT 0");
        if (!cols.contains("skills_xp")) st.executeUpdate("ALTER TABLE players ADD COLUMN skills_xp TEXT DEFAULT ''");
        if (!cols.contains("skills_levels")) st.executeUpdate("ALTER TABLE players ADD COLUMN skills_levels TEXT DEFAULT ''");
        if (!cols.contains("skill_rewards")) st.executeUpdate("ALTER TABLE players ADD COLUMN skill_rewards TEXT DEFAULT ''");
        st.executeUpdate("CREATE TABLE IF NOT EXISTS friends (owner TEXT NOT NULL, friend TEXT NOT NULL, friend_name TEXT, since INTEGER, PRIMARY KEY (owner, friend))");
        st.executeUpdate("CREATE TABLE IF NOT EXISTS funnel_events (uuid TEXT NOT NULL, event TEXT NOT NULL, first_seen INTEGER NOT NULL, hits INTEGER DEFAULT 1, PRIMARY KEY (uuid, event))");
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter TEXT, reporter_name TEXT, target_name TEXT,
              reason TEXT, world TEXT, x REAL, y REAL, z REAL, created INTEGER, resolved_by TEXT, resolved INTEGER DEFAULT 0)""");
        if (version < 1) {
            st.executeUpdate("INSERT OR IGNORE INTO migration_history(version, applied_at) VALUES (1, " + System.currentTimeMillis() + ")");
        }
        if (version < 2) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS data_quarantine (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, field TEXT NOT NULL, raw_value TEXT, detected_at INTEGER NOT NULL, repaired INTEGER DEFAULT 0)");
            st.executeUpdate("INSERT OR IGNORE INTO migration_history(version, applied_at) VALUES (2, " + System.currentTimeMillis() + ")");
        }
    }

    private void setSchemaVersion(Statement st, int version) throws SQLException {
        st.executeUpdate("INSERT INTO meta (key, value) VALUES ('schema_version', " + version + ") ON CONFLICT(key) DO UPDATE SET value = excluded.value");
    }

    /** Queues a vote for a player who is offline. Callback gets true if the name is known. */
    public void addPendingVote(String name, Consumer<Boolean> callback) {
        CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE players SET pending_votes = pending_votes + 1, votes = votes + 1 WHERE name = ? COLLATE NOCASE")) {
                ps.setString(1, name);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not queue vote for " + name, e);
                return false;
            }
        }, db).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> callback.accept(ok)));
    }

    public long getMeta(String key) {
        try {
            return db.submit(() -> {
                try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read meta " + key, e);
            return 0;
        }
    }

    public void setMetaAsync(String key, long value) {
        db.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setLong(2, value);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not write meta " + key, e);
            }
        });
    }

    /** Growth & loyalty numbers for /aether retention. Runs off-thread, result on the main thread. */
    public record Retention(long total, long new24h, long new7d, long dau, long wau, long mau,
                            double d1, double d7, double d30, double avgPlaytimeHours, long cohort1, long cohort7, long cohort30) {}

    public void retention(Consumer<Retention> callback) {
        CompletableFuture.supplyAsync(() -> {
            long now = System.currentTimeMillis(), day = 86_400_000L;
            try (Statement st = connection.createStatement()) {
                long total = scalar(st, "SELECT COUNT(*) FROM players");
                long new24 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join > " + (now - day));
                long new7 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join > " + (now - 7 * day));
                long dau = scalar(st, "SELECT COUNT(*) FROM players WHERE last_seen > " + (now - day));
                long wau = scalar(st, "SELECT COUNT(*) FROM players WHERE last_seen > " + (now - 7 * day));
                long mau = scalar(st, "SELECT COUNT(*) FROM players WHERE last_seen > " + (now - 30 * day));
                // Dn retention: of players who joined at least n days ago, the share seen again n+ days after joining.
                long c1 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join < " + (now - day));
                long r1 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join < " + (now - day) + " AND last_seen - first_join >= " + day);
                long c7 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join < " + (now - 7 * day));
                long r7 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join < " + (now - 7 * day) + " AND last_seen - first_join >= " + (7 * day));
                long c30 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join < " + (now - 30 * day));
                long r30 = scalar(st, "SELECT COUNT(*) FROM players WHERE first_join < " + (now - 30 * day) + " AND last_seen - first_join >= " + (30 * day));
                double avg = 0;
                try (ResultSet rs = st.executeQuery("SELECT AVG(playtime) FROM players")) { if (rs.next()) avg = rs.getDouble(1) / 3600.0; }
                return new Retention(total, new24, new7, dau, wau, mau,
                        c1 == 0 ? -1 : (double) r1 / c1, c7 == 0 ? -1 : (double) r7 / c7, c30 == 0 ? -1 : (double) r30 / c30, avg, c1, c7, c30);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Retention query failed", e);
                return null;
            }
        }, db).thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> callback.accept(r)));
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) { return rs.next() ? rs.getLong(1) : 0; }
    }

    public long uniquePlayers() {
        return uniquePlayers;
    }

    /** Safe, read-only database health snapshot for staff diagnostics. */
    public record Diagnostics(long schemaVersion, long players, long quarantined, long bazaarOrders, long auctions) {}

    public void diagnostics(Consumer<Diagnostics> callback) {
        CompletableFuture.supplyAsync(() -> {
            try (Statement st = connection.createStatement()) {
                long schema = scalar(st, "SELECT value FROM meta WHERE key = 'schema_version'");
                long players = scalar(st, "SELECT COUNT(*) FROM players");
                long quarantined = scalar(st, "SELECT COUNT(*) FROM data_quarantine WHERE repaired = 0");
                long bazaar = scalar(st, "SELECT COUNT(*) FROM bazaar_orders");
                long auctions = scalar(st, "SELECT COUNT(*) FROM auctions WHERE state = 'ACTIVE'");
                return new Diagnostics(schema, players, quarantined, bazaar, auctions);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Database diagnostics query failed", e);
                return null;
            }
        }, db).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result)));
    }

    /** Records a funnel milestone without blocking the server thread. Rows are unique per player/event. */
    public void recordFunnel(UUID uuid, String event) {
        if (uuid == null || event == null || !event.matches("[a-z0-9_]{1,48}")) return;
        db.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO funnel_events (uuid, event, first_seen) VALUES (?, ?, ?) ON CONFLICT(uuid, event) DO UPDATE SET hits = hits + 1")) {
                ps.setString(1, uuid.toString()); ps.setString(2, event); ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Could not record funnel event " + event, e); }
        });
    }

    public void recordFunnel(Player player, String event) {
        if (player != null) recordFunnel(player.getUniqueId(), event);
    }

    /** Returns unique-player counts by funnel event, suitable for the admin dashboard. */
    public void funnelSummary(Consumer<Map<String, Long>> callback) {
        CompletableFuture.supplyAsync(() -> {
            Map<String, Long> out = new LinkedHashMap<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT event, COUNT(*) FROM funnel_events GROUP BY event ORDER BY event")) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Funnel query failed", e); }
            return out;
        }, db).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result)));
    }

    public PlayerData get(Player player) {
        return online.get(player.getUniqueId());
    }

    public PlayerData get(UUID uuid) {
        return online.get(uuid);
    }

    /** Loaded data by name, including players who are mid-login. */
    public PlayerData findLoaded(String name) {
        for (PlayerData d : online.values()) if (d.name.equalsIgnoreCase(name)) return d;
        return null;
    }

    public Collection<PlayerData> online() {
        return online.values();
    }

    /** Called from AsyncPlayerPreLoginEvent: blocks that login thread (not the main thread) until loaded. */
    public void loadBlocking(UUID uuid, String name) throws Exception {
        PlayerData data = db.submit(() -> load(uuid, name)).get(10, TimeUnit.SECONDS);
        online.put(uuid, data);
    }

    /** Ensures data exists for a player who joined without passing pre-login (e.g. /reload). */
    public PlayerData ensureLoaded(Player player) {
        return online.computeIfAbsent(player.getUniqueId(), id -> {
            try {
                return db.submit(() -> load(id, player.getName())).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load " + player.getName() + "; refusing to create an empty profile", e);
                return null;
            }
        });
    }

    public void unload(UUID uuid) {
        PlayerData data = online.remove(uuid);
        if (data != null) saveAsync(data);
    }

    public void saveAsync(PlayerData data) {
        if (data == null || closing) return;
        PlayerData snap = data.snapshot();
        db.execute(() -> save(snap));
    }

    /**
     * Commits a critical player mutation before its command reports success.
     * This is reserved for item/economy operations where an async queue window
     * could otherwise lose the state after a crash or immediate restart.
     */
    public void saveNow(PlayerData data) {
        if (data == null || closing) return;
        PlayerData snap = data.snapshot();
        sync(c -> { save(snap); return null; });
    }

    public void saveAllAsync() {
        for (PlayerData d : online.values()) saveAsync(d);
    }

    public void shutdown() {
        closing = true;
        for (PlayerData d : online.values()) {
            PlayerData snap = d.snapshot();
            db.execute(() -> save(snap));
        }
        // Queue the checkpoint after every player save so the clean backup contains
        // the final committed state and no uncheckpointed WAL pages.
        db.execute(this::checkpointAndBackup);
        db.shutdown();
        try {
            if (!db.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("DB queue did not drain in time; player data may not have been saved");
                db.shutdownNow();
            }
            if (connection != null) connection.close();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error closing database", e);
        }
    }

    private void checkpointAndBackup() {
        try {
            if (connection != null) {
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
            }
            File source = new File(plugin.getDataFolder(), "data.db");
            if (!source.isFile()) return;
            Path dir = plugin.getDataFolder().toPath().resolve("backups");
            Files.createDirectories(dir);
            Files.copy(source.toPath(), dir.resolve("data-clean-" + System.currentTimeMillis() + ".db"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            File[] backups = dir.toFile().listFiles((d, n) -> n.startsWith("data-") && n.endsWith(".db"));
            if (backups != null && backups.length > 5) {
                java.util.Arrays.sort(backups, java.util.Comparator.comparingLong(File::lastModified).reversed());
                for (int i = 5; i < backups.length; i++) Files.deleteIfExists(backups[i].toPath());
            }
            plugin.getLogger().info("Created clean database backup after final save queue drain.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not create clean database backup", e);
        }
    }

    /** Runs a leaderboard query off-thread and hands results back on the main thread. */
    public void top(String column, int limit, Consumer<List<TopEntry>> callback) {
        String col = switch (column) {
            case "playtime", "quests_done", "best_streak", "votes" -> column;
            default -> throw new IllegalArgumentException(column);
        };
        CompletableFuture.supplyAsync(() -> {
            List<TopEntry> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT name, " + col + " FROM players ORDER BY " + col + " DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new TopEntry(rs.getString(1), rs.getLong(2)));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Leaderboard query failed", e);
            }
            return out;
        }, db).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> callback.accept(list)));
    }

    private PlayerData load(UUID uuid, String name) throws SQLException {
        PlayerData d = new PlayerData(uuid, name);
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    d.isNew = true;
                    d.firstJoin = System.currentTimeMillis();
                    d.streakSaves = Math.max(0, plugin.getConfig().getInt("daily.streak-protection.starting-saves", 1));
                    uniquePlayers++;
                    save(d);
                    return d;
                }
                d.firstJoin = rs.getLong("first_join");
                d.lastSeen = rs.getLong("last_seen");
                d.playtime = rs.getLong("playtime");
                d.streak = rs.getInt("streak");
                d.bestStreak = rs.getInt("best_streak");
                d.lastClaimDay = rs.getLong("last_claim_day");
                d.questsDone = rs.getInt("quests_done");
                d.timber = rs.getInt("timber") != 0;
                d.vein = rs.getInt("vein") != 0;
                String ms = rs.getString("milestones");
                if (ms != null && !ms.isBlank()) d.milestones.addAll(List.of(ms.split(",")));
                d.questDay = rs.getLong("quest_day");
                d.decodeQuests(rs.getString("quest_data"));
                d.questBonusClaimed = rs.getInt("quest_bonus") != 0;
                String collectionData = rs.getString("collections");
                addSafeTokens(collectionData, d.collections);
                String bestiaryData = rs.getString("bestiary_data");
                d.bestiary.putAll(BestiaryProgressCodec.decode(bestiaryData));
                String bestiaryRewards = rs.getString("bestiary_rewards");
                addSafeTokens(bestiaryRewards, d.bestiaryRewardsClaimed);
                String achievements = rs.getString("achievements");
                addSafeTokens(achievements, d.achievements);
                d.selectedTitle = rs.getString("selected_title");
                String minionData = rs.getString("minions_data");
                if (minionData != null && !minionData.isBlank()) for (String entry : minionData.split(";")) {
                    String[] f = entry.split(":", -1);
                    if (f.length >= 3) try {
                        // v2 records begin with id:type; legacy records used type as the key.
                        boolean v2 = f.length >= 11 && !isLong(f[1]);
                        int offset = v2 ? 2 : 1;
                        String id = v2 ? f[0] : f[0], type = v2 ? f[1] : f[0];
                        String world = f.length > offset + 5 && !f[offset + 5].isBlank() ? f[offset + 5] : null;
                        int x = f.length > offset + 6 ? Integer.parseInt(f[offset + 6]) : 0, y = f.length > offset + 7 ? Integer.parseInt(f[offset + 7]) : 0, z = f.length > offset + 8 ? Integer.parseInt(f[offset + 8]) : 0;
                        d.minions.put(id, new PlayerData.MinionState(id, type, Long.parseLong(f[offset]), Integer.parseInt(f[offset + 1]), f.length > offset + 2 ? Integer.parseInt(f[offset + 2]) : 1, f.length > offset + 3 ? Long.parseLong(f[offset + 3]) : 0, f.length > offset + 4 ? Integer.parseInt(f[offset + 4]) : 0, world, x, y, z));
                    } catch (NumberFormatException ignored) {
                        d.quarantinedFields.add("minions_data");
                        quarantine(d.uuid, "minions_data", entry);
                        plugin.getLogger().warning("Quarantined malformed minion record for " + d.name);
                    }
                }
                d.slayerType = rs.getString("slayer_type"); d.slayerTier = rs.getInt("slayer_tier"); d.slayerKills = rs.getInt("slayer_kills");
                d.votes = rs.getInt("votes");
                d.pendingVotes = rs.getInt("pending_votes");
                d.tutorial = rs.getInt("tutorial");
                d.streakSaves = rs.getInt("streak_saves");
                d.weeklyPeriod = rs.getLong("weekly_period");
                d.decodeWeekly(rs.getString("weekly_data"));
                d.weeklyBonusClaimed = rs.getInt("weekly_bonus") != 0;
                decodeLongMap(rs.getString("skills_xp"), d.skillXp);
                decodeIntMap(rs.getString("skills_levels"), d.skillLevels);
                String skillRewards = rs.getString("skill_rewards");
                addSafeTokens(skillRewards, d.skillRewardsClaimed);
            }
        }
        return d;
    }

    private void save(PlayerData d) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO players (uuid, name, first_join, last_seen, playtime, streak, best_streak, last_claim_day,
                  quests_done, timber, vein, milestones, quest_day, quest_data, quest_bonus, collections, bestiary_data, bestiary_rewards, achievements, selected_title, minions_data, slayer_type, slayer_tier, slayer_kills, votes, pending_votes, tutorial,
                  streak_saves, weekly_period, weekly_data, weekly_bonus, skills_xp, skills_levels, skill_rewards)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_seen=excluded.last_seen,
                  playtime=excluded.playtime, streak=excluded.streak, best_streak=excluded.best_streak,
                  last_claim_day=excluded.last_claim_day, quests_done=excluded.quests_done, timber=excluded.timber,
                  vein=excluded.vein, milestones=excluded.milestones, quest_day=excluded.quest_day,
                  quest_data=excluded.quest_data, quest_bonus=excluded.quest_bonus, collections=excluded.collections, bestiary_data=excluded.bestiary_data, bestiary_rewards=excluded.bestiary_rewards, achievements=excluded.achievements, selected_title=excluded.selected_title, minions_data=excluded.minions_data, slayer_type=excluded.slayer_type, slayer_tier=excluded.slayer_tier, slayer_kills=excluded.slayer_kills, votes=excluded.votes,
                  pending_votes=excluded.pending_votes, tutorial=excluded.tutorial, streak_saves=excluded.streak_saves,
                  weekly_period=excluded.weekly_period, weekly_data=excluded.weekly_data, weekly_bonus=excluded.weekly_bonus,
                  skills_xp=excluded.skills_xp, skills_levels=excluded.skills_levels, skill_rewards=excluded.skill_rewards""")) {
            ps.setString(1, d.uuid.toString());
            ps.setString(2, d.name);
            ps.setLong(3, d.firstJoin);
            ps.setLong(4, d.lastSeen);
            ps.setLong(5, d.playtime);
            ps.setInt(6, d.streak);
            ps.setInt(7, d.bestStreak);
            ps.setLong(8, d.lastClaimDay);
            ps.setInt(9, d.questsDone);
            ps.setInt(10, d.timber ? 1 : 0);
            ps.setInt(11, d.vein ? 1 : 0);
            ps.setString(12, String.join(",", d.milestones));
            ps.setLong(13, d.questDay);
            ps.setString(14, d.encodeQuests());
            ps.setInt(15, d.questBonusClaimed ? 1 : 0);
            ps.setString(16, String.join(",", d.collections));
            ps.setString(17, d.bestiary.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(java.util.stream.Collectors.joining(",")));
            ps.setString(18, String.join(",", d.bestiaryRewardsClaimed));
            ps.setString(19, String.join(",", d.achievements));
            ps.setString(20, d.selectedTitle == null ? "" : d.selectedTitle);
            String minionEncoded = d.minions.values().stream().map(m -> m.id + ":" + m.type + ":" + m.collectedAt + ":" + m.stored + ":" + m.level + ":" + m.fuelUntil + ":" + m.storageLevel + ":" + (m.world == null ? "" : m.world) + ":" + m.x + ":" + m.y + ":" + m.z).collect(java.util.stream.Collectors.joining(";"));
            if (d.quarantinedFields.contains("minions_data")) minionEncoded = preservedField(d.uuid, "minions_data", minionEncoded);
            ps.setString(21, minionEncoded);
            ps.setString(22, d.slayerType); ps.setInt(23, d.slayerTier); ps.setInt(24, d.slayerKills);
            ps.setInt(25, d.votes); ps.setInt(26, d.pendingVotes); ps.setInt(27, d.tutorial); ps.setInt(28, d.streakSaves); ps.setLong(29, d.weeklyPeriod); ps.setString(30, d.encodeWeekly()); ps.setInt(31, d.weeklyBonusClaimed ? 1 : 0);
            ps.setString(32, encodeMap(d.skillXp)); ps.setString(33, encodeMap(d.skillLevels)); ps.setString(34, String.join(",", d.skillRewardsClaimed));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save " + d.name, e);
        }
    }

    private static String encodeMap(java.util.Map<String, ? extends Number> map) {
        return map.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(java.util.stream.Collectors.joining(","));
    }

    private void quarantine(UUID uuid, String field, String raw) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO data_quarantine (uuid, field, raw_value, detected_at, repaired) VALUES (?, ?, ?, ?, 0)")) {
            ps.setString(1, uuid.toString()); ps.setString(2, field); ps.setString(3, raw); ps.setLong(4, System.currentTimeMillis()); ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not quarantine malformed " + field, e);
        }
    }

    private String preservedField(UUID uuid, String field, String fallback) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT minions_data FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getString(1) != null ? rs.getString(1) : fallback; }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not preserve quarantined " + field, e);
            return fallback;
        }
    }

    private static void decodeLongMap(String raw, java.util.Map<String, Long> out) {
        if (raw == null || raw.isBlank()) return;
        for (String entry : raw.split(",")) {
            if (out.size() >= 64) break;
            String[] pair = entry.split(":", 2);
            if (pair.length != 2 || !safeKey(pair[0])) continue;
            try { out.put(pair[0], Math.max(0L, Math.min(25_000_000L, Long.parseLong(pair[1])))); }
            catch (NumberFormatException ignored) { }
        }
    }

    private static void decodeIntMap(String raw, java.util.Map<String, Integer> out) {
        if (raw == null || raw.isBlank()) return;
        for (String entry : raw.split(",")) {
            if (out.size() >= 64) break;
            String[] pair = entry.split(":", 2);
            if (pair.length != 2 || !safeKey(pair[0])) continue;
            try { out.put(pair[0], Math.max(0, Math.min(50, Integer.parseInt(pair[1])))); }
            catch (NumberFormatException ignored) { }
        }
    }

    private static void addSafeTokens(String raw, java.util.Set<String> out) {
        if (raw == null || raw.isBlank()) return;
        for (String token : raw.split(",")) {
            if (out.size() >= 4096) break;
            if (safeKey(token)) out.add(token);
        }
    }

    private static boolean safeKey(String value) {
        return value != null && value.length() <= 96 && value.matches("[A-Za-z0-9_:-]{1,96}");
    }

    private static boolean isLong(String value) {
        try { Long.parseLong(value); return true; } catch (NumberFormatException ignored) { return false; }
    }
}
