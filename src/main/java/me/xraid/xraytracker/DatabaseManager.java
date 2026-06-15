package me.xraid.xraytracker;

import me.xraid.xraytracker.models.MiningRecord;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import me.xraid.xraytracker.models.TriggerReport;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.util.*;

public class DatabaseManager {
    private final XrayTracker plugin;
    private StorageProvider provider;
    private int maxMiningRecords;
    private int maxTriggerReports;

    public DatabaseManager(XrayTracker plugin) {
        this.plugin = plugin;
        this.maxMiningRecords = plugin.getConfig().getInt("max-mining-records-per-player", 2000);
        this.maxTriggerReports = plugin.getConfig().getInt("max-trigger-reports-per-player", 500);

        String storageType = plugin.getConfig().getString("database.type", "JSON").toUpperCase(Locale.ROOT);
        boolean isMySql = storageType.equals("MYSQL");
        boolean isSqlite = storageType.equals("SQLITE");

        if (isMySql || isSqlite) {
            provider = new SqlStorageProvider(plugin, isMySql);
        } else {
            provider = new JsonStorageProvider(plugin);
        }

        try {
            provider.init();
            plugin.getLogger().info("Database storage provider initialized: " + storageType);
            
            // Check for JSON to SQL migration
            if (isMySql || isSqlite) {
                checkForMigration();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize database provider! Falling back to JSON storage.");
            e.printStackTrace();
            provider = new JsonStorageProvider(plugin);
            try {
                provider.init();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
    
    private void checkForMigration() {
        File jsonFile = new File(plugin.getDataFolder(), "data.json");
        if (!jsonFile.exists()) {
            return;
        }
        
        Set<String> sqlPlayers = provider.getKnownPlayerNames();
        if (!sqlPlayers.isEmpty()) {
            // Already has data in SQL, don't migrate
            return;
        }

        plugin.getLogger().info("Found legacy data.json and empty SQL database. Starting automatic migration...");
        try {
            JsonStorageProvider legacyProvider = new JsonStorageProvider(plugin);
            legacyProvider.init();
            
            int window = plugin.getConfig().getInt("suspicion-window-seconds", 60);
            List<PlayerStatsSnapshot> allStats = legacyProvider.getAllPlayerStats(window);
            
            int migratedCount = 0;
            for (PlayerStatsSnapshot stats : allStats) {
                String playerId = stats.playerId();
                UUID uuid;
                try {
                    uuid = UUID.fromString(playerId);
                } catch (IllegalArgumentException e) {
                    continue; // Skip legacy names
                }
                
                // Touch player in SQL
                provider.touchPlayer(uuid, stats.playerName());
                
                // Copy mining records
                List<MiningRecord> records = legacyProvider.getMiningRecords(playerId);
                for (MiningRecord r : records) {
                    Location loc = new Location(Bukkit.getWorld(r.world()), r.x(), r.y(), r.z());
                    provider.logOre(
                        uuid, 
                        stats.playerName(), 
                        loc, 
                        r.blockType(), 
                        r.suspicionValue(), 
                        window, 
                        9999, 
                        9999
                    );
                }
                
                // Copy trigger reports
                List<TriggerReport> triggers = legacyProvider.getTriggerReports(playerId);
                for (TriggerReport t : triggers) {
                    Location loc = new Location(Bukkit.getWorld(t.world()), t.x(), t.y(), t.z());
                    provider.logTrigger(uuid, stats.playerName(), loc, t.oreType(), t.score(), t.reason());
                }
                migratedCount++;
            }
            
            legacyProvider.close();
            
            // Backup JSON file
            File backup = new File(plugin.getDataFolder(), "data.json.migrated");
            if (jsonFile.renameTo(backup)) {
                plugin.getLogger().info("Successfully migrated " + migratedCount + " player profiles to SQL! data.json renamed to data.json.migrated");
            } else {
                plugin.getLogger().warning("Migrated " + migratedCount + " player profiles, but could not rename data.json. Please delete it manually to avoid duplicate migration.");
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error migrating legacy data.json to SQL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized OreLogResult logOre(
        UUID playerId,
        String playerName,
        Location loc,
        String type,
        int suspicionValue,
        int windowSeconds,
        int alertThreshold,
        int alertCooldownSeconds,
        int limitOverride // ignored, using config limits
    ) {
        return provider.logOre(playerId, playerName, loc, type, suspicionValue, windowSeconds, alertThreshold, alertCooldownSeconds);
    }

    public synchronized List<MiningRecord> getMiningRecords(String playerIdentifier) {
        return provider.getMiningRecords(playerIdentifier);
    }

    public synchronized List<TriggerReport> getTriggerReports(String playerIdentifier) {
        return provider.getTriggerReports(playerIdentifier);
    }

    public synchronized void logTrigger(
        UUID playerId,
        String playerName,
        Location location,
        String oreType,
        int score,
        String reason
    ) {
        provider.logTrigger(playerId, playerName, location, oreType, score, reason);
    }

    public synchronized PlayerStatsSnapshot getPlayerStats(String playerIdentifier, int windowSeconds) {
        return provider.getPlayerStats(playerIdentifier, windowSeconds);
    }

    public synchronized List<PlayerStatsSnapshot> getAllPlayerStats(int windowSeconds) {
        return provider.getAllPlayerStats(windowSeconds);
    }

    public synchronized Set<String> getKnownPlayerNames() {
        return provider.getKnownPlayerNames();
    }

    public synchronized boolean clearPlayerData(String playerIdentifier) {
        return provider.clearPlayerData(playerIdentifier);
    }

    public void flush() {
        provider.flush();
    }

    public synchronized void touchPlayer(UUID playerId, String playerName) {
        provider.touchPlayer(playerId, playerName);
    }

    public int getMaxMiningRecords() { return maxMiningRecords; }
    public int getMaxTriggerReports() { return maxTriggerReports; }

    public record OreLogResult(int windowScore, boolean alerted) {}
}
