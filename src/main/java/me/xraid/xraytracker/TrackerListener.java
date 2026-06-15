package me.xraid.xraytracker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TrackerListener implements Listener {
    private final XrayTracker plugin;
    private final Map<UUID, MiningMemory> memory = new HashMap<>();

    public TrackerListener(XrayTracker plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player player = e.getPlayer();
        if (player.hasPermission("xraytracker.bypass")) {
            return;
        }

        Material blockType = e.getBlock().getType();
        String typeName = blockType.name();

        boolean trackedOre = plugin.getTrackedOres().contains(typeName);
        boolean trackPath = e.getBlock().getY() <= plugin.getMiningYLevel();
        
        if (!trackedOre && !trackPath) {
            return;
        }

        Location location = e.getBlock().getLocation();
        ScoreResult score = null;
        int suspicionValue = 0;
        if (trackedOre) {
            score = buildSuspicionScore(player, location, typeName);
            suspicionValue = score.score();
        }

        DatabaseManager.OreLogResult stored = plugin.getDbManager().logOre(
            player.getUniqueId(),
            player.getName(),
            location,
            typeName,
            suspicionValue,
            plugin.getConfig().getInt("suspicion-window-seconds", 60),
            plugin.getConfig().getInt("alert-threshold", 12),
            plugin.getConfig().getInt("alert-cooldown-seconds", 120),
            plugin.getDbManager().getMaxMiningRecords()
        );

        if (!trackedOre) {
            return;
        }

        boolean highRiskAlert = false;
        if (score != null && score.highRisk()) {
            MiningMemory state = memory.computeIfAbsent(player.getUniqueId(), id -> new MiningMemory());
            long cooldownMs = Math.max(1, plugin.getConfig().getInt("high-risk-alert-cooldown-seconds", 45)) * 1000L;
            long now = System.currentTimeMillis();
            if ((now - state.lastHighRiskAlertAt) >= cooldownMs) {
                highRiskAlert = true;
                state.lastHighRiskAlertAt = now;
            }
        }

        boolean shouldAlert = stored.alerted() || highRiskAlert;
        if (!shouldAlert) {
            return;
        }

        String reason = highRiskAlert && score != null
            ? score.reasonSummary()
            : "window-score=" + stored.windowScore();
        int shownScore = score != null ? score.score() : stored.windowScore();

        plugin.getDbManager().logTrigger(
            player.getUniqueId(),
            player.getName(),
            location,
            typeName,
            shownScore,
            reason
        );

        // Interactive chat components
        net.kyori.adventure.text.Component chatMessage = net.kyori.adventure.text.Component.text("§c§l[!] XrayAlert: ")
            .append(net.kyori.adventure.text.Component.text("§f" + player.getName())
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§eClick to open inspector GUI")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/xt stats " + player.getName())))
            .append(net.kyori.adventure.text.Component.text(" §7ore: §e" + typeName))
            .append(net.kyori.adventure.text.Component.text(" §7score: §c" + shownScore))
            .append(net.kyori.adventure.text.Component.text(" §8(" + reason + ") "))
            .append(net.kyori.adventure.text.Component.text("§7[§a§lTeleport§7]")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§aClick to teleport to coordinates")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/tp " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ())));

        Bukkit.getOnlinePlayers().stream()
            .filter(admin -> admin.hasPermission("xraytracker.admin") && !plugin.isMuted(admin))
            .forEach(admin -> {
                admin.sendMessage(chatMessage);
                admin.playSound(admin.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 0.5f);
            });

        // Send Discord Webhook
        String webhookUrl = plugin.getConfig().getString("discord-webhook-url", "");
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            DiscordWebhook.sendAlert(
                webhookUrl,
                player.getName(),
                player.getUniqueId(),
                typeName,
                shownScore,
                reason,
                location.getWorld() != null ? location.getWorld().getName() : "world",
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
            );
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        plugin.getDbManager().touchPlayer(player.getUniqueId(), player.getName());
        PathHighlighter.hideReplayEntitiesFrom(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        memory.remove(e.getPlayer().getUniqueId());
        plugin.getHighlighter().stopHighlight(e.getPlayer(), false);
    }

    @EventHandler
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        if (plugin.isSearching(player)) {
            e.setCancelled(true);
            plugin.setSearching(player, false);
            
            String text = e.getMessage().trim();
            if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("close")) {
                player.sendMessage("§6[XrayTracker] §7Search cancelled.");
            } else {
                plugin.setSearchFilter(player, text);
                player.sendMessage("§6[XrayTracker] §7Filtering players by: §e" + text);
            }
            
            // Re-open GUI on main thread
            Bukkit.getScheduler().runTask(plugin, () -> me.xraid.xraytracker.gui.PlayerListGui.open(player));
        }
    }

    private ScoreResult buildSuspicionScore(Player player, Location location, String oreType) {
        MiningMemory state = memory.computeIfAbsent(player.getUniqueId(), key -> new MiningMemory());
        long now = System.currentTimeMillis();
        int score = plugin.getConfig().getInt("ore-score." + oreType, plugin.getConfig().getInt("default-ore-score", 2));

        long hitWindowMs = Math.max(1, plugin.getConfig().getInt("streak-window-seconds", 45)) * 1000L;
        if (oreType.equals(state.lastOreType) && now - state.lastOreTimestamp <= hitWindowMs) {
            state.streak++;
        } else {
            state.streak = 1;
        }
        score += Math.min(plugin.getConfig().getInt("max-streak-bonus", 8), Math.max(0, state.streak - 1) * plugin.getConfig().getInt("streak-bonus-per-hit", 2));

        if (state.lastOreLocation != null && state.lastOreLocation.getWorld() != null && state.lastOreLocation.getWorld().equals(location.getWorld())) {
            double distance = state.lastOreLocation.distance(location);
            if (distance <= plugin.getConfig().getDouble("proximity-bonus-distance", 6.0D)) {
                score += plugin.getConfig().getInt("proximity-bonus", 2);
            }
            if (Math.abs(state.lastOreLocation.getY() - location.getY()) <= plugin.getConfig().getInt("layer-bonus-y-delta", 2)) {
                score += plugin.getConfig().getInt("layer-bonus", 1);
            }
            if (state.lastOreLocation.getChunk().equals(location.getChunk())) {
                score += plugin.getConfig().getInt("chunk-bonus", 2);
            }
        }

        if (location.getY() <= plugin.getConfig().getInt("depth-bonus.y-max", 20) && plugin.getConfig().getBoolean("depth-bonus.enabled", true)) {
            score += plugin.getConfig().getInt("depth-bonus.score", 2);
        }

        state.recentHits.addLast(now);
        state.recentLocations.addLast(location.clone());
        trimHistory(state.recentHits, plugin.getConfig().getInt("burst-window-size", 10));
        trimHistory(state.recentLocations, plugin.getConfig().getInt("burst-window-size", 10));

        int burstCount = countRecentHits(state.recentHits, plugin.getConfig().getInt("burst-window-seconds", 30));
        if (burstCount >= plugin.getConfig().getInt("burst-count-threshold", 4)) {
            score += plugin.getConfig().getInt("burst-bonus", 3);
        }
        if (burstCount >= plugin.getConfig().getInt("burst-hard-threshold", 7)) {
            score += plugin.getConfig().getInt("burst-hard-bonus", 5);
        }

        int clusterCount = countRecentLocationsInChunk(state.recentLocations, location);
        if (clusterCount >= plugin.getBottomClusterThreshold()) {
            score += plugin.getConfig().getInt("cluster-bonus", 4);
        }

        state.lastOreType = oreType;
        state.lastOreTimestamp = now;
        state.lastOreLocation = location.clone();

        List<String> reasons = new ArrayList<>();
        int singleTriggerThreshold = plugin.getConfig().getInt("single-trigger-score-threshold", 16);
        int streakAlertThreshold = plugin.getConfig().getInt("streak-alert-threshold", 4);
        int clusterAlertThreshold = plugin.getConfig().getInt("cluster-alert-threshold", 4);
        int burstHardThreshold = plugin.getConfig().getInt("burst-hard-threshold", 7);

        if (score >= singleTriggerThreshold) {
            reasons.add("single-score=" + score);
        }
        if (state.streak >= streakAlertThreshold) {
            reasons.add("streak=" + state.streak);
        }
        if (burstCount >= burstHardThreshold) {
            reasons.add("burst=" + burstCount);
        }
        if (clusterCount >= clusterAlertThreshold) {
            reasons.add("cluster=" + clusterCount);
        }

        boolean highRisk = !reasons.isEmpty();
        String summary = highRisk ? String.join(", ", reasons) : "window-score";
        return new ScoreResult(Math.max(0, score), highRisk, summary);
    }

    private int countRecentHits(ArrayDeque<Long> hits, int windowSeconds) {
        long cutoff = System.currentTimeMillis() - (Math.max(1, windowSeconds) * 1000L);
        int count = 0;
        for (Long hit : hits) {
            if (hit >= cutoff) {
                count++;
            }
        }
        return count;
    }

    private int countRecentLocationsInChunk(ArrayDeque<Location> locations, Location current) {
        if (current == null || current.getWorld() == null) {
            return 0;
        }

        int count = 0;
        for (Location loc : locations) {
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(current.getWorld()) && loc.getChunk().equals(current.getChunk())) {
                count++;
            }
        }
        return count;
    }

    private <T> void trimHistory(ArrayDeque<T> deque, int maxSize) {
        while (deque.size() > Math.max(1, maxSize)) {
            deque.removeFirst();
        }
    }

    private static final class MiningMemory {
        private String lastOreType;
        private long lastOreTimestamp;
        private Location lastOreLocation;
        private int streak;
        private long lastHighRiskAlertAt;
        private final ArrayDeque<Long> recentHits = new ArrayDeque<>();
        private final ArrayDeque<Location> recentLocations = new ArrayDeque<>();
    }

    private record ScoreResult(int score, boolean highRisk, String reasonSummary) {}
}
