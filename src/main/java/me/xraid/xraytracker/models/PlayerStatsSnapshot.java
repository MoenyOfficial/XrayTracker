package me.xraid.xraytracker.models;

public record PlayerStatsSnapshot(
    String playerId,
    String playerName,
    int totalMiningRecords,
    int trackedOreBreaks,
    int recentSuspicionScore,
    int alertCount,
    long lastSeenTimestamp
) {}
