package me.xraid.xraytracker.models;

public record TriggerReport(
    long timestamp,
    String reason,
    String oreType,
    int score,
    String world,
    int x,
    int y,
    int z
) {}
