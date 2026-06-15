package me.xraid.xraytracker;

import me.xraid.xraytracker.gui.HistoryLogGui;
import me.xraid.xraytracker.gui.PlayerInspectGui;
import me.xraid.xraytracker.gui.PlayerListGui;
import me.xraid.xraytracker.gui.TriggerReportGui;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class XrayTracker extends JavaPlugin implements Listener {
    private static XrayTracker instance;
    private DatabaseManager dbManager;
    private PathHighlighter highlighter;
    private NamespacedKey playerNameKey;
    private NamespacedKey playerIdKey;
    
    private final Set<String> trackedOres = new HashSet<>();
    private int miningYLevel;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadConfigValues();

        dbManager = new DatabaseManager(this);
        highlighter = new PathHighlighter();
        playerNameKey = new NamespacedKey(this, "target_player_name");
        playerIdKey = new NamespacedKey(this, "target_player_id");

        PluginCommand xt = getCommand("xt");
        if (xt != null) {
            CommandHandler commandHandler = new CommandHandler(this);
            xt.setExecutor(commandHandler);
            xt.setTabCompleter(commandHandler);
        } else {
            getLogger().severe("Command 'xt' not found in plugin.yml");
        }

        getServer().getPluginManager().registerEvents(new TrackerListener(this), this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("XrayTracker enabled.");
    }

    @Override
    public void onDisable() {
        if (dbManager != null) {
            dbManager.flush();
        }
        PathHighlighter.cleanUpAllStands();
    }

    public void loadConfigValues() {
        reloadConfig();
        trackedOres.clear();
        trackedOres.addAll(getConfig().getStringList("tracked-ores"));
        miningYLevel = getConfig().getInt("mining-y-level", 64);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (e.getClickedInventory() == null) {
            return;
        }

        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType() == Material.AIR || !cur.hasItemMeta()) {
            return;
        }

        Player admin = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();
        
        if (title.startsWith("§8Log: §0")) {
            e.setCancelled(true);
            HistoryLogGui.handleLogClick(admin, cur);
            return;
        }
        if (title.startsWith("§8Triggers: §0")) {
            e.setCancelled(true);
            TriggerReportGui.handleClick(admin, cur);
            return;
        }
        if (title.startsWith("§8Players §7(")) {
            e.setCancelled(true);
            PlayerListGui.handleClick(admin, cur, e.getRawSlot());
            return;
        }
        if (title.startsWith("Inspect: ")) {
            e.setCancelled(true);
        } else {
            // Not our GUI
            return;
        }

        String targetName = cur.getItemMeta().getPersistentDataContainer().get(playerNameKey, PersistentDataType.STRING);
        String targetId = cur.getItemMeta().getPersistentDataContainer().get(playerIdKey, PersistentDataType.STRING);
        String targetIdentifier = targetId != null ? targetId : targetName;
        if (targetIdentifier == null) {
            return;
        }

        String shownName = targetName != null ? targetName : targetIdentifier;

        if (cur.getType() == Material.PLAYER_HEAD) {
            PlayerInspectGui.open(admin, shownName, targetIdentifier);
        } else if (cur.getType() == Material.BOOK) {
            HistoryLogGui.open(admin, targetIdentifier, shownName);
        } else if (cur.getType() == Material.GOLDEN_PICKAXE) {
            admin.closeInventory();
            highlighter.highlight(admin, dbManager.getMiningRecords(targetIdentifier));
        } else if (cur.getType() == Material.DIAMOND_PICKAXE) {
            admin.closeInventory();
            highlighter.startPlayback(admin, dbManager.getMiningRecords(targetIdentifier), targetIdentifier, shownName);
        } else if (cur.getType() == Material.PAPER) {
            sendStats(admin, targetIdentifier, shownName);
        } else if (cur.getType() == Material.REDSTONE_TORCH) {
            TriggerReportGui.open(admin, targetIdentifier, shownName);
        } else if (cur.getType() == Material.ARROW) {
            admin.playSound(admin.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
            PlayerListGui.open(admin);
        } else if (cur.getType() == Material.ENDER_PEARL) {
            Player target = Bukkit.getPlayer(shownName);
            if (target != null && target.isOnline()) {
                admin.closeInventory();
                admin.teleport(target.getLocation());
                admin.sendMessage("§6[XrayTracker] §7Teleported to §e" + target.getName() + "§7.");
                admin.playSound(admin.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
            } else {
                admin.sendMessage("§c[XrayTracker] Player §f" + shownName + "§c is not online.");
                admin.playSound(admin.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.6f, 0.8f);
            }
        } else if (cur.getType() == Material.BARRIER) {
            boolean removed = dbManager.clearPlayerData(targetIdentifier);
            admin.sendMessage(removed
                ? "§a[XrayTracker] Removed stored data for §f" + shownName + "§a."
                : "§c[XrayTracker] No stored data found for §f" + shownName + "§c.");
            admin.closeInventory();
        }
    }

    public void sendStats(CommandSender sender, String playerIdentifier, String fallbackName) {
        int windowSeconds = getConfig().getInt("suspicion-window-seconds", 60);
        PlayerStatsSnapshot stats = dbManager.getPlayerStats(playerIdentifier, windowSeconds);
        if (stats == null) {
            sender.sendMessage("§c[XrayTracker] No stored data for §f" + fallbackName + "§c.");
            return;
        }

        sender.sendMessage("§6§l[XrayTracker] §fStats for §e" + stats.playerName());
        sender.sendMessage("§7Recent suspicion score (" + windowSeconds + "s): §e" + stats.recentSuspicionScore());
        sender.sendMessage("§7Tracked ore breaks: §e" + stats.trackedOreBreaks() + " §8| §7Total mining records: §e" + stats.totalMiningRecords());
        sender.sendMessage("§7Alerts: §e" + stats.alertCount() + " §8| §7Player ID: §8" + stats.playerId());
        
        // HI Engine Analytics Output
        java.util.List<me.xraid.xraytracker.models.MiningRecord> records = dbManager.getMiningRecords(playerIdentifier);
        XrayAnalytics.AnalyticsResult analysis = XrayAnalytics.analyze(records);
        String probColor = analysis.xrayProbability() >= 75.0 ? "§c" : (analysis.xrayProbability() >= 45.0 ? "§6" : "§a");
        sender.sendMessage("§d§l[HI Engine Analytics]");
        sender.sendMessage(" §7Xray Confidence: " + probColor + String.format("%.1f%%", analysis.xrayProbability()) + " §8(" + analysis.suspicionLevel() + ")");
        sender.sendMessage(" §7Target Ore Ratio: §e" + String.format("%.1f%%", analysis.oreRatio()) + " §8| §7Path Complexity: §e" + String.format("%.1f%%", analysis.pathComplexity()));
        sender.sendMessage(" §7Sharp Dig Turns: §e" + analysis.sharpTurns());
    }

    public static XrayTracker getInstance() {
        return instance;
    }

    public DatabaseManager getDbManager() {
        return dbManager;
    }

    public PathHighlighter getHighlighter() {
        return highlighter;
    }

    public NamespacedKey getPlayerKey() {
        return playerNameKey;
    }

    public NamespacedKey getPlayerNameKey() {
        return playerNameKey;
    }

    public NamespacedKey getPlayerIdKey() {
        return playerIdKey;
    }

    public Set<String> getTrackedOres() {
        return trackedOres;
    }

    public int getMiningYLevel() {
        return miningYLevel;
    }

    public int getBottomClusterThreshold() {
        return getConfig().getInt("cluster-count-threshold", 4);
    }

    private final Set<UUID> mutedStaff = new HashSet<>();

    public boolean toggleAlerts(Player p) {
        if (mutedStaff.contains(p.getUniqueId())) {
            mutedStaff.remove(p.getUniqueId());
            return false; // alerts are now enabled
        } else {
            mutedStaff.add(p.getUniqueId());
            return true; // alerts are now muted
        }
    }

    public boolean isMuted(Player p) {
        return mutedStaff.contains(p.getUniqueId());
    }

    // Advanced GUI search and sorting states
    public enum SortMode {
        SUSPICION("Highest Suspicion"),
        NAME("Alphabetical"),
        LAST_ACTIVE("Last Active"),
        ALERTS("Most Alerts");

        private final String displayName;
        SortMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private final Map<UUID, SortMode> sortModes = new HashMap<>();
    private final Map<UUID, String> searchFilters = new HashMap<>();
    private final Set<UUID> searchingStaff = new HashSet<>();

    public SortMode getSortMode(Player p) {
        return sortModes.getOrDefault(p.getUniqueId(), SortMode.SUSPICION);
    }

    public void toggleSortMode(Player p) {
        SortMode current = getSortMode(p);
        SortMode next = SortMode.values()[(current.ordinal() + 1) % SortMode.values().length];
        sortModes.put(p.getUniqueId(), next);
    }

    public String getSearchFilter(Player p) {
        return searchFilters.get(p.getUniqueId());
    }

    public void setSearchFilter(Player p, String filter) {
        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("clear")) {
            searchFilters.remove(p.getUniqueId());
        } else {
            searchFilters.put(p.getUniqueId(), filter.trim());
        }
    }

    public boolean isSearching(Player p) {
        return searchingStaff.contains(p.getUniqueId());
    }

    public void setSearching(Player p, boolean searching) {
        if (searching) {
            searchingStaff.add(p.getUniqueId());
        } else {
            searchingStaff.remove(p.getUniqueId());
        }
    }
}
