package gg.aetherfall.core.data;

import gg.aetherfall.core.AetherCore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;

/** Idempotent, backup-first migration for the main configuration file. */
public final class ConfigMigrations {
    public static final int CURRENT_VERSION = 6;

    private ConfigMigrations() { }

    public static void migrate(AetherCore plugin) throws IOException {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) return;
        YamlConfiguration config = new YamlConfiguration();
        try { config.load(file); }
        catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("Invalid config.yml; original file preserved", e); }
        int version = config.getInt("config-version", 0);
        if (version > CURRENT_VERSION) throw new IOException("config.yml version " + version + " is newer than supported version " + CURRENT_VERSION);
        if (version == CURRENT_VERSION) return;

        File backup = new File(plugin.getDataFolder(), "config.yml.v" + version + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        if (version < 2) {
            putIfMissing(config, "fishing.encounter-cap", 3);
            putIfMissing(config, "fishing.encounter-cooldown-ms", 5000L);
            putIfMissing(config, "fishing.encounter-lifetime-ms", 120000L);
        }
        if (version < 3) {
            // Import only this feature's missing defaults; preserve operator overrides.
            try (var stream = plugin.getResource("config.yml")) {
                if (stream == null) throw new IOException("Bundled config.yml is missing");
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
                var bestiary = defaults.getConfigurationSection("bestiary");
                if (bestiary != null) for (String key : bestiary.getKeys(true)) {
                    if (!bestiary.isConfigurationSection(key)) putIfMissing(config, "bestiary." + key, bestiary.get(key));
                }
            }
        }
        if (version < 4) {
            try (var stream = plugin.getResource("config.yml")) {
                if (stream == null) throw new IOException("Bundled config.yml is missing");
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
                var fishing = defaults.getConfigurationSection("fishing");
                if (fishing != null) for (String key : fishing.getKeys(true)) {
                    if (!fishing.isConfigurationSection(key) && (key.startsWith("encounters.") || key.equals("max-encounter-item-count")))
                        putIfMissing(config, "fishing." + key, fishing.get(key));
                }
            }
        }
        if (version < 5) {
            try (var stream = plugin.getResource("config.yml")) {
                if (stream == null) throw new IOException("Bundled config.yml is missing");
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
                var entries = defaults.getConfigurationSection("bestiary.entries");
                if (entries != null) for (String key : List.of("elite:reef_stalker", "elite:krakenling")) {
                    var entry = entries.getConfigurationSection(key);
                    if (entry == null) continue;
                    for (String child : entry.getKeys(true)) {
                        if (!entry.isConfigurationSection(child)) putIfMissing(config, "bestiary.entries." + key + "." + child, entry.get(child));
                    }
                }
            }
        }
        if (version < 6) {
            putIfMissing(config, "minions.max-per-player", 30);
        }
        config.set("config-version", CURRENT_VERSION);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        config.save(temp);
        // Parse the generated file before replacing the operator's copy. This catches
        // serialization failures and leaves the original available for recovery.
        YamlConfiguration check = new YamlConfiguration();
        try { check.load(temp); }
        catch (org.bukkit.configuration.InvalidConfigurationException e) {
            Files.deleteIfExists(temp.toPath());
            throw new IOException("Generated migrated config.yml is invalid; original file preserved", e);
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        plugin.getLogger().info("Migrated config.yml from version " + version + " to " + CURRENT_VERSION + ". Backup: " + backup.getName());
    }

    private static void putIfMissing(YamlConfiguration config, String path, Object value) {
        if (!config.contains(path)) config.set(path, value);
    }
}
