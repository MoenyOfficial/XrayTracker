package me.xraid.xraytracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import me.xraid.xraytracker.models.MiningRecord;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import me.xraid.xraytracker.models.TriggerReport;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class JsonStorageProvider implements StorageProvider {
    private final XrayTracker plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File dataFile;
    
    private StorageRoot storage;
    private boolean needsSave = false;
    private int maxMiningRecords;
    private int maxTriggerReports;

    public JsonStorageProvider(XrayTracker plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.json");
    }

    @Override
    public void init() throws Exception {
        this.storage = new StorageRoot();
        loadConfigLimits();
        loadStorage();
        migrateLegacyFiles(plugin.getDataFolder());
        
        // Start auto-save task
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveStorageIfDirty, 6000L, 6000L); // Every 5 minutes
    }
    
    private void loadConfigLimits() {
        maxMiningRecords = plugin.getConfig().getInt("max-mining-records-per-player", 2000);
        maxTriggerReports = plugin.getConfig().getInt("max-trigger-reports-per-player", 500);
    }

    @Override
    public synchronized DatabaseManager.OreLogResult logOre(
        UUID playerId,
        String playerName,
        Location loc,
        String type,
        int suspicionValue,
        int windowSeconds,
        int alertThreshold,
        int alertCooldownSeconds
    ) {
        PlayerData player = getOrCreatePlayer(playerId, playerName);
        long now = System.currentTimeMillis();
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "UNKNOWN";

        player.miningRecords.add(new MiningRecord(
            now,
            world,
            loc.getBlockX(),
            loc.getBlockY(),
            loc.getBlockZ(),
            type,
            Math.max(0, suspicionValue)
        ));
        trimList(player.miningRecords, maxMiningRecords);

        if (suspicionValue > 0) {
            player.oreCounts.merge(type, 1, Integer::sum);
        }
        player.lastUpdated = now;

        int recentScore = calculateRecentScore(player.miningRecords, now, windowSeconds);
        boolean alerted = false;
        long cooldownMs = Math.max(1, alertCooldownSeconds) * 1000L;
        if (recentScore >= Math.max(1, alertThreshold) && (now - player.lastAlertTimestamp) >= cooldownMs) {
            alerted = true;
            player.lastAlertTimestamp = now;
            player.alertCount++;
        }

        markDirty();
        return new DatabaseManager.OreLogResult(recentScore, alerted);
    }

    @Override
    public synchronized List<MiningRecord> getMiningRecords(String playerIdentifier) {
        PlayerData player = getByIdentifier(playerIdentifier);
        return player == null ? new ArrayList<>() : new ArrayList<>(player.miningRecords);
    }

    @Override
    public synchronized List<TriggerReport> getTriggerReports(String playerIdentifier) {
        PlayerData player = getByIdentifier(playerIdentifier);
        if (player == null) {
            return new ArrayList<>();
        }

        List<TriggerReport> reports = new ArrayList<>(player.triggerReports);
        reports.sort(Comparator.comparingLong(TriggerReport::timestamp).reversed());
        return reports;
    }

    @Override
    public synchronized void logTrigger(
        UUID playerId,
        String playerName,
        Location location,
        String oreType,
        int score,
        String reason
    ) {
        PlayerData player = getOrCreatePlayer(playerId, playerName);
        String world = location.getWorld() != null ? location.getWorld().getName() : "UNKNOWN";
        player.triggerReports.add(new TriggerReport(
            System.currentTimeMillis(),
            reason,
            oreType,
            score,
            world,
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        ));
        trimList(player.triggerReports, maxTriggerReports);
        player.lastUpdated = System.currentTimeMillis();
        markDirty();
    }

    @Override
    public synchronized PlayerStatsSnapshot getPlayerStats(String playerIdentifier, int windowSeconds) {
        String playerKey = resolvePlayerKey(playerIdentifier);
        if (playerKey == null) {
            return null;
        }

        PlayerData player = storage.players.get(playerKey);
        if (player == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        int trackedOreBreaks = player.oreCounts.values().stream().mapToInt(Integer::intValue).sum();
        int recentScore = calculateRecentScore(player.miningRecords, now, windowSeconds);
        long lastMining = player.miningRecords.isEmpty() ? 0L : player.miningRecords.get(player.miningRecords.size() - 1).timestamp();
        long lastSeen = Math.max(player.lastUpdated, lastMining);

        return new PlayerStatsSnapshot(
            playerKey,
            player.lastKnownName,
            player.miningRecords.size(),
            trackedOreBreaks,
            recentScore,
            player.alertCount,
            lastSeen
        );
    }

    @Override
    public synchronized List<PlayerStatsSnapshot> getAllPlayerStats(int windowSeconds) {
        long now = System.currentTimeMillis();
        List<PlayerStatsSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, PlayerData> entry : storage.players.entrySet()) {
            String playerId = entry.getKey();
            PlayerData player = entry.getValue();
            int trackedOreBreaks = player.oreCounts.values().stream().mapToInt(Integer::intValue).sum();
            int recentScore = calculateRecentScore(player.miningRecords, now, windowSeconds);
            long lastMining = player.miningRecords.isEmpty() ? 0L : player.miningRecords.get(player.miningRecords.size() - 1).timestamp();
            long lastSeen = Math.max(player.lastUpdated, lastMining);

            snapshots.add(new PlayerStatsSnapshot(
                playerId,
                player.lastKnownName,
                player.miningRecords.size(),
                trackedOreBreaks,
                recentScore,
                player.alertCount,
                lastSeen
            ));
        }

        snapshots.sort(
            Comparator.comparingInt(PlayerStatsSnapshot::recentSuspicionScore).reversed()
                .thenComparing(Comparator.comparingInt(PlayerStatsSnapshot::trackedOreBreaks).reversed() )
                .thenComparing(Comparator.comparingLong(PlayerStatsSnapshot::lastSeenTimestamp).reversed())
        );
        return snapshots;
    }

    @Override
    public synchronized Set<String> getKnownPlayerNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PlayerData data : storage.players.values()) {
            if (data.lastKnownName != null && !data.lastKnownName.isBlank()) {
                names.add(data.lastKnownName);
            }
        }
        return names;
    }

    @Override
    public synchronized boolean clearPlayerData(String playerIdentifier) {
        String playerKey = resolvePlayerKey(playerIdentifier);
        if (playerKey == null) {
            return false;
        }

        storage.players.remove(playerKey);
        storage.nameIndex.entrySet().removeIf(e -> playerKey.equals(e.getValue()));
        markDirty();
        return true;
    }

    @Override
    public synchronized void touchPlayer(UUID playerId, String playerName) {
        PlayerData player = getOrCreatePlayer(playerId, playerName);
        player.lastUpdated = System.currentTimeMillis();
        markDirty();
    }

    @Override
    public void flush() {
        saveStorageIfDirty();
    }

    @Override
    public void close() {
        flush();
    }

    private synchronized void markDirty() {
        needsSave = true;
    }

    private void saveStorageIfDirty() {
        synchronized (this) {
            if (!needsSave) return;
            needsSave = false;
        }
        
        StorageRoot copy;
        synchronized (this) {
            copy = cloneStorage();
        }
        
        try (Writer writer = Files.newBufferedWriter(dataFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(copy, writer);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to write data.json asynchronously: " + ex.getMessage());
        }
    }
    
    // Deep clone to avoid ConcurrentModificationException while saving asynchronously
    private StorageRoot cloneStorage() {
        StorageRoot clone = new StorageRoot();
        clone.schemaVersion = storage.schemaVersion;
        clone.updatedAt = System.currentTimeMillis();
        clone.players = new HashMap<>();
        
        for (Map.Entry<String, PlayerData> entry : storage.players.entrySet()) {
            PlayerData data = entry.getValue();
            PlayerData dataClone = new PlayerData();
            dataClone.lastKnownName = data.lastKnownName;
            dataClone.miningRecords = new ArrayList<>(data.miningRecords);
            dataClone.triggerReports = new ArrayList<>(data.triggerReports);
            dataClone.oreCounts = new HashMap<>(data.oreCounts);
            dataClone.lastAlertTimestamp = data.lastAlertTimestamp;
            dataClone.alertCount = data.alertCount;
            dataClone.lastUpdated = data.lastUpdated;
            clone.players.put(entry.getKey(), dataClone);
        }
        
        clone.nameIndex = new HashMap<>(storage.nameIndex);
        return clone;
    }

    private PlayerData getOrCreatePlayer(UUID playerId, String playerName) {
        String key = playerId.toString();
        String oldKeyForName = storage.nameIndex.get(playerName.toLowerCase(Locale.ROOT));
        if (oldKeyForName != null && !oldKeyForName.equals(key) && storage.players.containsKey(oldKeyForName)) {
            PlayerData legacy = storage.players.remove(oldKeyForName);
            PlayerData current = storage.players.computeIfAbsent(key, k -> new PlayerData());
            mergePlayerData(current, legacy);
        }

        PlayerData player = storage.players.computeIfAbsent(key, k -> new PlayerData());
        player.lastKnownName = playerName;
        storage.nameIndex.put(playerName.toLowerCase(Locale.ROOT), key);
        return player;
    }

    private PlayerData getByIdentifier(String playerIdentifier) {
        String key = resolvePlayerKey(playerIdentifier);
        return key == null ? null : storage.players.get(key);
    }

    private String resolvePlayerKey(String playerIdentifier) {
        if (playerIdentifier == null || playerIdentifier.isBlank()) {
            return null;
        }

        String normalized = playerIdentifier.trim();
        if (storage.players.containsKey(normalized)) {
            return normalized;
        }

        String byName = storage.nameIndex.get(normalized.toLowerCase(Locale.ROOT));
        if (byName != null && storage.players.containsKey(byName)) {
            return byName;
        }

        for (Map.Entry<String, PlayerData> entry : storage.players.entrySet()) {
            if (normalized.equalsIgnoreCase(entry.getValue().lastKnownName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private int calculateRecentScore(List<MiningRecord> records, long now, int windowSeconds) {
        long from = now - (Math.max(1, windowSeconds) * 1000L);
        int total = 0;
        for (MiningRecord record : records) {
            if (record.timestamp() >= from) {
                total += Math.max(0, record.suspicionValue());
            }
        }
        return total;
    }

    private void trimList(List<?> list, int maxEntries) {
        if (maxEntries <= 0 || list.size() <= maxEntries) {
            return;
        }

        int removeCount = list.size() - maxEntries;
        for (int i = 0; i < removeCount; i++) {
            list.remove(0);
        }
    }

    private void loadStorage() {
        if (!dataFile.exists()) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(dataFile.toPath(), StandardCharsets.UTF_8)) {
            StorageRoot loaded = gson.fromJson(reader, StorageRoot.class);
            storage = loaded != null ? loaded : new StorageRoot();
            ensureIntegrity();
        } catch (IOException | JsonParseException ex) {
            plugin.getLogger().warning("Failed to read data.json. Creating a fresh store and backing up the broken file.");
            backupCorruptedFile();
            storage = new StorageRoot();
        }
    }

    private void ensureIntegrity() {
        if (storage.players == null) {
            storage.players = new HashMap<>();
        }
        if (storage.nameIndex == null) {
            storage.nameIndex = new HashMap<>();
        }

        Map<String, String> rebuiltIndex = new HashMap<>();
        for (Map.Entry<String, PlayerData> entry : storage.players.entrySet()) {
            PlayerData data = entry.getValue();
            if (data == null) {
                data = new PlayerData();
                entry.setValue(data);
            }
            if (data.lastKnownName == null || data.lastKnownName.isBlank()) {
                data.lastKnownName = entry.getKey();
            }
            if (data.miningRecords == null) {
                data.miningRecords = new ArrayList<>();
            }
            if (data.triggerReports == null) {
                data.triggerReports = new ArrayList<>();
            }
            if (data.oreCounts == null) {
                data.oreCounts = new HashMap<>();
            }
            rebuiltIndex.put(data.lastKnownName.toLowerCase(Locale.ROOT), entry.getKey());
        }

        storage.nameIndex = rebuiltIndex;
    }

    private void backupCorruptedFile() {
        File backup = new File(plugin.getDataFolder(), "data.corrupt." + System.currentTimeMillis() + ".json");
        try {
            Files.move(dataFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not back up corrupted data.json: " + ex.getMessage());
        }
    }

    private void migrateLegacyFiles(File folder) {
        File[] files = folder.listFiles((dir, name) ->
            name.endsWith(".json")
                && !name.equalsIgnoreCase("data.json")
                && name.endsWith("_mining.json")
        );
        if (files == null || files.length == 0) {
            return;
        }

        int migratedFiles = 0;
        for (File file : files) {
            String name = file.getName();
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                if (name.endsWith("_mining.json")) {
                    String playerName = name.substring(0, name.length() - "_mining.json".length());
                    String playerKey = storage.nameIndex.getOrDefault(playerName.toLowerCase(Locale.ROOT), "legacy:" + playerName.toLowerCase(Locale.ROOT));
                    PlayerData data = storage.players.computeIfAbsent(playerKey, k -> new PlayerData());
                    data.lastKnownName = playerName;
                    List<MiningRecord> records = gson.fromJson(reader, new TypeToken<List<MiningRecord>>() {}.getType());
                    if (records != null && !records.isEmpty()) {
                        data.miningRecords.addAll(records);
                        for (MiningRecord record : records) {
                            String blockType = record.blockType();
                            if (blockType != null && (blockType.contains("ORE") || blockType.equals("ANCIENT_DEBRIS"))) {
                                data.oreCounts.merge(blockType, 1, Integer::sum);
                            }
                        }
                    }
                    storage.nameIndex.put(playerName.toLowerCase(Locale.ROOT), playerKey);
                    migratedFiles++;
                }

                File migrated = new File(folder, file.getName() + ".migrated");
                Files.move(file.toPath(), migrated.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | JsonParseException ex) {
                plugin.getLogger().warning("Failed to migrate legacy file " + name + ": " + ex.getMessage());
            }
        }

        if (migratedFiles > 0) {
            plugin.getLogger().info("Migrated " + migratedFiles + " legacy JSON files into data.json");
            markDirty();
        }
    }

    private void mergePlayerData(PlayerData target, PlayerData source) {
        if (source == null) {
            return;
        }
        if (target.lastKnownName == null || target.lastKnownName.isBlank()) {
            target.lastKnownName = source.lastKnownName;
        }
        target.miningRecords.addAll(source.miningRecords);
        if (source.triggerReports != null) {
            target.triggerReports.addAll(source.triggerReports);
        }
        for (Map.Entry<String, Integer> entry : source.oreCounts.entrySet()) {
            target.oreCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        target.lastAlertTimestamp = Math.max(target.lastAlertTimestamp, source.lastAlertTimestamp);
        target.alertCount += source.alertCount;
        target.lastUpdated = Math.max(target.lastUpdated, source.lastUpdated);
    }

    private static final class StorageRoot {
        int schemaVersion = 2;
        long updatedAt = System.currentTimeMillis();
        Map<String, PlayerData> players = new HashMap<>();
        Map<String, String> nameIndex = new HashMap<>();
    }

    private static final class PlayerData {
        String lastKnownName = "Unknown";
        List<MiningRecord> miningRecords = new ArrayList<>();
        List<TriggerReport> triggerReports = new ArrayList<>();
        Map<String, Integer> oreCounts = new HashMap<>();
        long lastAlertTimestamp = 0L;
        int alertCount = 0;
        long lastUpdated = System.currentTimeMillis();
    }
}
