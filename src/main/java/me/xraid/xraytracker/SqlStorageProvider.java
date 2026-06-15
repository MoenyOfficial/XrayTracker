package me.xraid.xraytracker;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.xraid.xraytracker.models.MiningRecord;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import me.xraid.xraytracker.models.TriggerReport;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.logging.Level;

public class SqlStorageProvider implements StorageProvider {
    private final XrayTracker plugin;
    private final boolean isMySql;
    private HikariDataSource dataSource;
    
    private int maxMiningRecords;
    private int maxTriggerReports;

    public SqlStorageProvider(XrayTracker plugin, boolean isMySql) {
        this.plugin = plugin;
        this.isMySql = isMySql;
    }

    @Override
    public void init() throws Exception {
        maxMiningRecords = plugin.getConfig().getInt("max-mining-records-per-player", 2000);
        maxTriggerReports = plugin.getConfig().getInt("max-trigger-reports-per-player", 500);

        HikariConfig config = new HikariConfig();
        
        if (isMySql) {
            String host = plugin.getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String db = plugin.getConfig().getString("database.mysql.database", "xraytracker");
            String user = plugin.getConfig().getString("database.mysql.username", "root");
            String pass = plugin.getConfig().getString("database.mysql.password", "");
            
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&characterEncoding=utf8");
            config.setUsername(user);
            config.setPassword(pass);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else {
            File dbFile = new File(plugin.getDataFolder(), "data.db");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setConnectionTestQuery("SELECT 1");
        }

        // Pool configuration
        config.setMaximumPoolSize(Math.max(2, plugin.getConfig().getInt("database.pool-size", 10)));
        config.setMinimumIdle(2);
        config.setIdleTimeout(30000);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(5000);
        
        dataSource = new HikariDataSource(config);
        
        createTables();
    }

    private void createTables() throws SQLException {
        String autoIncrement = isMySql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // Players table
            stmt.execute("CREATE TABLE IF NOT EXISTS xt_players (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "username VARCHAR(16) NOT NULL, " +
                "alert_count INT DEFAULT 0, " +
                "last_alert_time BIGINT DEFAULT 0, " +
                "last_seen BIGINT DEFAULT 0)");

            // Mining Records table
            stmt.execute("CREATE TABLE IF NOT EXISTS xt_mining_records (" +
                "id " + autoIncrement + ", " +
                "uuid VARCHAR(36), " +
                "timestamp BIGINT, " +
                "world VARCHAR(64), " +
                "x INT, " +
                "y INT, " +
                "z INT, " +
                "block_type VARCHAR(64), " +
                "suspicion_value INT)");
            
            // Create index for mining records lookup speed
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mining_uuid ON xt_mining_records(uuid)");

            // Trigger Reports table
            stmt.execute("CREATE TABLE IF NOT EXISTS xt_trigger_reports (" +
                "id " + autoIncrement + ", " +
                "uuid VARCHAR(36), " +
                "timestamp BIGINT, " +
                "reason VARCHAR(256), " +
                "ore_type VARCHAR(64), " +
                "score INT, " +
                "world VARCHAR(64), " +
                "x INT, " +
                "y INT, " +
                "z INT)");
            
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trigger_uuid ON xt_trigger_reports(uuid)");

            // Ore Counts table
            stmt.execute("CREATE TABLE IF NOT EXISTS xt_ore_counts (" +
                "uuid VARCHAR(36), " +
                "ore_type VARCHAR(64), " +
                "count INT, " +
                "PRIMARY KEY (uuid, ore_type))");
        }
    }

    @Override
    public DatabaseManager.OreLogResult logOre(
        UUID playerId,
        String playerName,
        Location loc,
        String type,
        int suspicionValue,
        int windowSeconds,
        int alertThreshold,
        int alertCooldownSeconds
    ) {
        String uuidStr = playerId.toString();
        long now = System.currentTimeMillis();
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "UNKNOWN";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Ensure player exists/touch player
                touchPlayerInternal(conn, playerId, playerName, now);

                // Insert record
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO xt_mining_records (uuid, timestamp, world, x, y, z, block_type, suspicion_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    ps.setString(1, uuidStr);
                    ps.setLong(2, now);
                    ps.setString(3, world);
                    ps.setInt(4, loc.getBlockX());
                    ps.setInt(5, loc.getBlockY());
                    ps.setInt(6, loc.getBlockZ());
                    ps.setString(7, type);
                    ps.setInt(8, Math.max(0, suspicionValue));
                    ps.executeUpdate();
                }

                // Increment ore counts if tracked ore with suspicion
                if (suspicionValue > 0) {
                    incrementOreCount(conn, uuidStr, type);
                }

                // Trim records
                trimMiningRecords(conn, uuidStr);

                // Calculate recent suspicion score
                int recentScore = 0;
                long windowStart = now - (Math.max(1, windowSeconds) * 1000L);
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT SUM(suspicion_value) FROM xt_mining_records WHERE uuid = ? AND timestamp >= ?"
                )) {
                    ps.setString(1, uuidStr);
                    ps.setLong(2, windowStart);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            recentScore = rs.getInt(1);
                        }
                    }
                }

                // Alert logic
                boolean alerted = false;
                long lastAlert = 0L;
                int currentAlertCount = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT alert_count, last_alert_time FROM xt_players WHERE uuid = ?"
                )) {
                    ps.setString(1, uuidStr);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            currentAlertCount = rs.getInt(1);
                            lastAlert = rs.getLong(2);
                        }
                    }
                }

                long cooldownMs = Math.max(1, alertCooldownSeconds) * 1000L;
                if (recentScore >= Math.max(1, alertThreshold) && (now - lastAlert) >= cooldownMs) {
                    alerted = true;
                    try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE xt_players SET alert_count = alert_count + 1, last_alert_time = ? WHERE uuid = ?"
                    )) {
                        ps.setLong(1, now);
                        ps.setString(2, uuidStr);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
                return new DatabaseManager.OreLogResult(recentScore, alerted);

            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error logging ore block", ex);
            return new DatabaseManager.OreLogResult(0, false);
        }
    }

    private void incrementOreCount(Connection conn, String uuid, String type) throws SQLException {
        boolean exists = false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT count FROM xt_ore_counts WHERE uuid = ? AND ore_type = ?")) {
            ps.setString(1, uuid);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        }
        
        if (exists) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE xt_ore_counts SET count = count + 1 WHERE uuid = ? AND ore_type = ?")) {
                ps.setString(1, uuid);
                ps.setString(2, type);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO xt_ore_counts (uuid, ore_type, count) VALUES (?, ?, 1)")) {
                ps.setString(1, uuid);
                ps.setString(2, type);
                ps.executeUpdate();
            }
        }
    }

    private void trimMiningRecords(Connection conn, String uuid) throws SQLException {
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM xt_mining_records WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        }

        if (count > maxMiningRecords) {
            int toDelete = count - maxMiningRecords;
            // SQLite does not support JOIN/subquery LIMIT deletion well in old versions, so we find the ID boundary
            long deleteThresholdTime = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT timestamp FROM xt_mining_records WHERE uuid = ? ORDER BY timestamp ASC LIMIT 1 OFFSET ?"
            )) {
                ps.setString(1, uuid);
                ps.setInt(2, toDelete - 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        deleteThresholdTime = rs.getLong(1);
                    }
                }
            }

            if (deleteThresholdTime > 0) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM xt_mining_records WHERE uuid = ? AND timestamp <= ?")) {
                    ps.setString(1, uuid);
                    ps.setLong(2, deleteThresholdTime);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public List<MiningRecord> getMiningRecords(String playerIdentifier) {
        String uuid = resolvePlayerKey(playerIdentifier);
        if (uuid == null) return new ArrayList<>();

        List<MiningRecord> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
            "SELECT timestamp, world, x, y, z, block_type, suspicion_value FROM xt_mining_records WHERE uuid = ? ORDER BY timestamp ASC"
        )) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new MiningRecord(
                        rs.getLong("timestamp"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("block_type"),
                        rs.getInt("suspicion_value")
                    ));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error fetching mining records", ex);
        }
        return list;
    }

    @Override
    public List<TriggerReport> getTriggerReports(String playerIdentifier) {
        String uuid = resolvePlayerKey(playerIdentifier);
        if (uuid == null) return new ArrayList<>();

        List<TriggerReport> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
            "SELECT timestamp, reason, ore_type, score, world, x, y, z FROM xt_trigger_reports WHERE uuid = ? ORDER BY timestamp DESC"
        )) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TriggerReport(
                        rs.getLong("timestamp"),
                        rs.getString("reason"),
                        rs.getString("ore_type"),
                        rs.getInt("score"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z")
                    ));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error fetching trigger reports", ex);
        }
        return list;
    }

    @Override
    public void logTrigger(
        UUID playerId,
        String playerName,
        Location location,
        String oreType,
        int score,
        String reason
    ) {
        String uuidStr = playerId.toString();
        long now = System.currentTimeMillis();
        String world = location.getWorld() != null ? location.getWorld().getName() : "UNKNOWN";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                touchPlayerInternal(conn, playerId, playerName, now);

                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO xt_trigger_reports (uuid, timestamp, reason, ore_type, score, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    ps.setString(1, uuidStr);
                    ps.setLong(2, now);
                    ps.setString(3, reason);
                    ps.setString(4, oreType);
                    ps.setInt(5, score);
                    ps.setString(6, world);
                    ps.setInt(7, location.getBlockX());
                    ps.setInt(8, location.getBlockY());
                    ps.setInt(9, location.getBlockZ());
                    ps.executeUpdate();
                }

                // Trim triggers
                trimTriggerReports(conn, uuidStr);
                
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error logging trigger report", ex);
        }
    }

    private void trimTriggerReports(Connection conn, String uuid) throws SQLException {
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM xt_trigger_reports WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        }

        if (count > maxTriggerReports) {
            int toDelete = count - maxTriggerReports;
            long deleteThresholdTime = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT timestamp FROM xt_trigger_reports WHERE uuid = ? ORDER BY timestamp ASC LIMIT 1 OFFSET ?"
            )) {
                ps.setString(1, uuid);
                ps.setInt(2, toDelete - 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        deleteThresholdTime = rs.getLong(1);
                    }
                }
            }

            if (deleteThresholdTime > 0) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM xt_trigger_reports WHERE uuid = ? AND timestamp <= ?")) {
                    ps.setString(1, uuid);
                    ps.setLong(2, deleteThresholdTime);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public PlayerStatsSnapshot getPlayerStats(String playerIdentifier, int windowSeconds) {
        String uuid = resolvePlayerKey(playerIdentifier);
        if (uuid == null) return null;

        long now = System.currentTimeMillis();
        long windowStart = now - (Math.max(1, windowSeconds) * 1000L);

        try (Connection conn = dataSource.getConnection()) {
            String name = "Unknown";
            int alertCount = 0;
            long lastSeen = 0L;
            
            try (PreparedStatement ps = conn.prepareStatement("SELECT username, alert_count, last_seen FROM xt_players WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        name = rs.getString("username");
                        alertCount = rs.getInt("alert_count");
                        lastSeen = rs.getLong("last_seen");
                    } else {
                        return null;
                    }
                }
            }

            int totalMiningRecords = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM xt_mining_records WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalMiningRecords = rs.getInt(1);
                    }
                }
            }

            int trackedOreBreaks = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT SUM(count) FROM xt_ore_counts WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        trackedOreBreaks = rs.getInt(1);
                    }
                }
            }

            int recentScore = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT SUM(suspicion_value) FROM xt_mining_records WHERE uuid = ? AND timestamp >= ?"
            )) {
                ps.setString(1, uuid);
                ps.setLong(2, windowStart);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        recentScore = rs.getInt(1);
                    }
                }
            }

            return new PlayerStatsSnapshot(
                uuid,
                name,
                totalMiningRecords,
                trackedOreBreaks,
                recentScore,
                alertCount,
                lastSeen
            );

        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error fetching player stats", ex);
            return null;
        }
    }

    @Override
    public List<PlayerStatsSnapshot> getAllPlayerStats(int windowSeconds) {
        List<PlayerStatsSnapshot> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        long windowStart = now - (Math.max(1, windowSeconds) * 1000L);

        try (Connection conn = dataSource.getConnection()) {
            // Load all player profiles
            Map<String, PlayerStatsBuilder> builders = new LinkedHashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                "SELECT uuid, username, alert_count, last_seen FROM xt_players"
            )) {
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    PlayerStatsBuilder builder = new PlayerStatsBuilder(
                        uuid,
                        rs.getString("username"),
                        rs.getInt("alert_count"),
                        rs.getLong("last_seen")
                    );
                    builders.put(uuid, builder);
                }
            }

            // Sum recent scores in single query
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, SUM(suspicion_value) FROM xt_mining_records WHERE timestamp >= ? GROUP BY uuid"
            )) {
                ps.setLong(1, windowStart);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PlayerStatsBuilder builder = builders.get(rs.getString("uuid"));
                        if (builder != null) {
                            builder.recentSuspicionScore = rs.getInt(2);
                        }
                    }
                }
            }

            // Count total mining records in single query
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                "SELECT uuid, COUNT(*) FROM xt_mining_records GROUP BY uuid"
            )) {
                while (rs.next()) {
                    PlayerStatsBuilder builder = builders.get(rs.getString("uuid"));
                    if (builder != null) {
                        builder.totalMiningRecords = rs.getInt(2);
                    }
                }
            }

            // Sum tracked ore breaks in single query
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                "SELECT uuid, SUM(count) FROM xt_ore_counts GROUP BY uuid"
            )) {
                while (rs.next()) {
                    PlayerStatsBuilder builder = builders.get(rs.getString("uuid"));
                    if (builder != null) {
                        builder.trackedOreBreaks = rs.getInt(2);
                    }
                }
            }

            // Build snapshots
            for (PlayerStatsBuilder builder : builders.values()) {
                list.add(builder.build());
            }

        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error fetching all player stats", ex);
        }

        // Sort snapshots standard
        list.sort(
            Comparator.comparingInt(PlayerStatsSnapshot::recentSuspicionScore).reversed()
                .thenComparing(Comparator.comparingInt(PlayerStatsSnapshot::trackedOreBreaks).reversed())
                .thenComparing(Comparator.comparingLong(PlayerStatsSnapshot::lastSeenTimestamp).reversed())
        );
        return list;
    }

    @Override
    public Set<String> getKnownPlayerNames() {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
            "SELECT username FROM xt_players"
        )) {
            while (rs.next()) {
                set.add(rs.getString("username"));
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error getting known player names", ex);
        }
        return set;
    }

    @Override
    public boolean clearPlayerData(String playerIdentifier) {
        String uuid = resolvePlayerKey(playerIdentifier);
        if (uuid == null) return false;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM xt_mining_records WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM xt_trigger_reports WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM xt_ore_counts WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    ps.executeUpdate();
                }
                int deletedPlayers = 0;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM xt_players WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    deletedPlayers = ps.executeUpdate();
                }
                
                conn.commit();
                return deletedPlayers > 0;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error clearing player data", ex);
            return false;
        }
    }

    @Override
    public void touchPlayer(UUID playerId, String playerName) {
        try (Connection conn = dataSource.getConnection()) {
            touchPlayerInternal(conn, playerId, playerName, System.currentTimeMillis());
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error touching player", ex);
        }
    }

    private void touchPlayerInternal(Connection conn, UUID playerId, String playerName, long time) throws SQLException {
        String uuidStr = playerId.toString();
        boolean exists = false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM xt_players WHERE uuid = ?")) {
            ps.setString(1, uuidStr);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        }

        if (exists) {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE xt_players SET username = ?, last_seen = ? WHERE uuid = ?"
            )) {
                ps.setString(1, playerName);
                ps.setLong(2, time);
                ps.setString(3, uuidStr);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO xt_players (uuid, username, last_seen) VALUES (?, ?, ?)"
            )) {
                ps.setString(1, uuidStr);
                ps.setString(2, playerName);
                ps.setLong(3, time);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void flush() {
        // SQL is direct, flush is no-op
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private String resolvePlayerKey(String playerIdentifier) {
        if (playerIdentifier == null || playerIdentifier.isBlank()) {
            return null;
        }
        
        String input = playerIdentifier.trim();
        // Check if input is a valid UUID
        try {
            UUID.fromString(input);
            return input;
        } catch (IllegalArgumentException ignored) {}

        // Resolve by username
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
            "SELECT uuid FROM xt_players WHERE LOWER(username) = ?"
        )) {
            ps.setString(1, input.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("uuid");
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQL error resolving player key", ex);
        }
        
        return null;
    }

    private static class PlayerStatsBuilder {
        private final String uuid;
        private final String username;
        private final int alertCount;
        private final long lastSeen;
        private int totalMiningRecords = 0;
        private int trackedOreBreaks = 0;
        private int recentSuspicionScore = 0;

        public PlayerStatsBuilder(String uuid, String username, int alertCount, long lastSeen) {
            this.uuid = uuid;
            this.username = username;
            this.alertCount = alertCount;
            this.lastSeen = lastSeen;
        }

        public PlayerStatsSnapshot build() {
            return new PlayerStatsSnapshot(
                uuid,
                username,
                totalMiningRecords,
                trackedOreBreaks,
                recentSuspicionScore,
                alertCount,
                lastSeen
            );
        }
    }
}
