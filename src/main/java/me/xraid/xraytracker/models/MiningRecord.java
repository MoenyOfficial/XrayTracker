package me.xraid.xraytracker.models;

public record MiningRecord(long timestamp, String world, int x, int y, int z, String blockType, int suspicionValue) {}
