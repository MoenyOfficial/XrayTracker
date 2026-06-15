package me.xraid.xraytracker;

import me.xraid.xraytracker.models.MiningRecord;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import me.xraid.xraytracker.models.TriggerReport;
import org.bukkit.Location;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface StorageProvider {
    void init() throws Exception;
    
    DatabaseManager.OreLogResult logOre(
        UUID playerId,
        String playerName,
        Location loc,
        String type,
        int suspicionValue,
        int windowSeconds,
        int alertThreshold,
        int alertCooldownSeconds
    );
    
    void logTrigger(
        UUID playerId,
        String playerName,
        Location location,
        String oreType,
        int score,
        String reason
    );
    
    List<MiningRecord> getMiningRecords(String playerIdentifier);
    
    List<TriggerReport> getTriggerReports(String playerIdentifier);
    
    PlayerStatsSnapshot getPlayerStats(String playerIdentifier, int windowSeconds);
    
    List<PlayerStatsSnapshot> getAllPlayerStats(int windowSeconds);
    
    Set<String> getKnownPlayerNames();
    
    boolean clearPlayerData(String playerIdentifier);
    
    void touchPlayer(UUID playerId, String playerName);
    
    void flush();
    
    void close();
}
