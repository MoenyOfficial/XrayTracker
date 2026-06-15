package me.xraid.xraytracker.gui;

import me.xraid.xraytracker.XrayTracker;
import me.xraid.xraytracker.XrayAnalytics;
import me.xraid.xraytracker.models.MiningRecord;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class PlayerInspectGui {
    public static void open(Player admin, String targetName, String targetIdentifier) {
        Inventory inv = Bukkit.createInventory(null, 27, "Inspect: " + targetName);
        
        // Stained glass border
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        // Load stats to display in summary
        PlayerStatsSnapshot stats = XrayTracker.getInstance().getDbManager().getPlayerStats(targetIdentifier, 
            XrayTracker.getInstance().getConfig().getInt("suspicion-window-seconds", 60));

        // Load mining records for smart analytics
        List<MiningRecord> records = XrayTracker.getInstance().getDbManager().getMiningRecords(targetIdentifier);
        XrayAnalytics.AnalyticsResult analysis = XrayAnalytics.analyze(records);

        // 1. Activity History (Book)
        ItemStack logs = new ItemStack(Material.BOOK);
        ItemMeta m1 = logs.getItemMeta();
        m1.setDisplayName("§b§lActivity History");
        m1.setLore(List.of(
            "§7View logs of all mined blocks.",
            "",
            "§aClick to view log list"
        ));
        m1.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        m1.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        logs.setItemMeta(m1);
        
        // 2. Suspicion Stats / Smart Analytics (Paper)
        ItemStack statsItem = new ItemStack(Material.PAPER);
        ItemMeta m3 = statsItem.getItemMeta();
        m3.setDisplayName("§e§lSuspicion Stats & Analytics");
        
        String probColor = "§a";
        if (analysis.xrayProbability() >= 75.0) probColor = "§c";
        else if (analysis.xrayProbability() >= 45.0) probColor = "§6";

        if (stats != null) {
            m3.setLore(List.of(
                "§7Player: §f" + stats.playerName(),
                "§7UUID: §8" + stats.playerId(),
                "",
                "§7Recent Suspicion Score: §c" + stats.recentSuspicionScore(),
                "§7Staff Alerts: §c" + stats.alertCount(),
                "",
                "§d§lHeuristic Analytics (HI Engine):",
                " §7• Xray Confidence: " + probColor + String.format("%.1f%%", analysis.xrayProbability()) + " (" + analysis.suspicionLevel() + ")",
                " §7• Target Ore Ratio: §e" + String.format("%.1f%%", analysis.oreRatio()) + " of blocks",
                " §7• Path Complexity: §e" + String.format("%.1f%%", analysis.pathComplexity()),
                " §7• Sharp Dig Turns: §e" + analysis.sharpTurns() + " changes",
                "",
                "§aClick to print detailed stats to chat"
            ));
        } else {
            m3.setLore(List.of("§cNo active stats found.", "", "§aClick to inspect in chat"));
        }
        m3.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        m3.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        statsItem.setItemMeta(m3);

        // 3. Show Static Mining Path (Golden Pickaxe)
        ItemStack path = new ItemStack(Material.GOLDEN_PICKAXE);
        ItemMeta m2 = path.getItemMeta();
        m2.setDisplayName("§a§lShow Mined Path (Static)");
        m2.setLore(List.of(
            "§7Instantly visualize the full sequence",
            "§7of blocks mined by this player",
            "§7using client-side fake blocks.",
            "",
            "§aClick to visualize path"
        ));
        m2.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        m2.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        path.setItemMeta(m2);

        // 3b. Chronological Path Replay (Diamond Pickaxe)
        ItemStack replay = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta mReplay = replay.getItemMeta();
        mReplay.setDisplayName("§d§lPath Playback Replay (Animated)");
        mReplay.setLore(List.of(
            "§7Watch an animated playback replay",
            "§7of the player's digging route block-by-block",
            "§7with live step trackers.",
            "",
            "§aClick to start animated replay"
        ));
        mReplay.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        mReplay.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        replay.setItemMeta(mReplay);

        // 4. Trigger Reports (Torch)
        ItemStack triggers = new ItemStack(Material.REDSTONE_TORCH);
        ItemMeta m4 = triggers.getItemMeta();
        m4.setDisplayName("§c§lTrigger Reports");
        if (stats != null) {
            m4.setLore(List.of(
                "§7Total Xray flags: §c" + stats.alertCount(),
                "",
                "§aClick to view trigger logs"
            ));
        } else {
            m4.setLore(List.of("§7Click to view trigger logs"));
        }
        m4.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        m4.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        triggers.setItemMeta(m4);

        // 5. Back Button (Arrow)
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§7◀ Back to Player List");
        backMeta.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        backMeta.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        back.setItemMeta(backMeta);

        // 5b. Teleport to Player (Ender Pearl)
        ItemStack tp = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tpMeta = tp.getItemMeta();
        tpMeta.setDisplayName("§d§lTeleport to Player");
        tpMeta.setLore(List.of(
            "§7Teleport directly to the player",
            "§7in real-time to spectate.",
            "",
            "§aClick to teleport"
        ));
        tpMeta.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        tpMeta.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        tp.setItemMeta(tpMeta);

        // 6. Clear Stored Data (Barrier)
        ItemStack clear = new ItemStack(Material.BARRIER);
        ItemMeta m5 = clear.getItemMeta();
        m5.setDisplayName("§4§lClear Player Data");
        m5.setLore(List.of(
            "§cWARNING: This will permanently delete",
            "§call logs and trigger stats stored for",
            "§cthis player from the database.",
            "",
            "§4Click to delete data"
        ));
        m5.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, targetName);
        m5.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, targetIdentifier);
        clear.setItemMeta(m5);
        
        inv.setItem(10, logs);
        inv.setItem(11, statsItem);
        inv.setItem(13, path);
        inv.setItem(14, replay);
        inv.setItem(16, triggers);
        inv.setItem(18, back);
        inv.setItem(20, tp);
        inv.setItem(26, clear);
        
        admin.openInventory(inv);
    }
}
