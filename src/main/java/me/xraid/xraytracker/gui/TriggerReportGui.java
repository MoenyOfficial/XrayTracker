package me.xraid.xraytracker.gui;

import me.xraid.xraytracker.XrayTracker;
import me.xraid.xraytracker.models.TriggerReport;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TriggerReportGui {
    private static final int ENTRIES_PER_PAGE = 45;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static void open(Player admin, String targetIdentifier, String displayName) {
        openPage(admin, targetIdentifier, displayName, 0);
    }

    private static void openPage(Player admin, String targetIdentifier, String displayName, int page) {
        List<TriggerReport> reports = XrayTracker.getInstance().getDbManager().getTriggerReports(targetIdentifier);
        int totalPages = Math.max(1, (reports.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54, "§8Triggers: §0" + displayName + " §7(" + (safePage + 1) + "/" + totalPages + ")");
        int start = safePage * ENTRIES_PER_PAGE;
        int end = Math.min(start + ENTRIES_PER_PAGE, reports.size());

        for (int i = start; i < end; i++) {
            inv.setItem(i - start, createTriggerItem(reports.get(i), targetIdentifier, displayName));
        }

        // Decorative glass pane fillers
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        for (int i = 46; i <= 48; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 50; i <= 52; i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(45, createNavButton("previous", safePage > 0, targetIdentifier, displayName, safePage - 1));
        inv.setItem(49, createBackButton(targetIdentifier, displayName));
        inv.setItem(53, createNavButton("next", safePage < totalPages - 1, targetIdentifier, displayName, safePage + 1));
        admin.openInventory(inv);
    }

    public static boolean handleClick(Player admin, ItemStack clicked) {
        if (clicked == null || !clicked.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = clicked.getItemMeta();

        // Handle back button
        String action = meta.getPersistentDataContainer().get(key("action"), PersistentDataType.STRING);
        if ("back".equals(action)) {
            String playerId = meta.getPersistentDataContainer().get(key("player"), PersistentDataType.STRING);
            String displayName = meta.getPersistentDataContainer().get(key("name"), PersistentDataType.STRING);
            admin.playSound(admin.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
            PlayerInspectGui.open(admin, displayName, playerId);
            return true;
        }

        String nav = meta.getPersistentDataContainer().get(key("nav"), PersistentDataType.STRING);
        if (nav != null) {
            String playerId = meta.getPersistentDataContainer().get(key("player"), PersistentDataType.STRING);
            String displayName = meta.getPersistentDataContainer().get(key("name"), PersistentDataType.STRING);
            Integer page = meta.getPersistentDataContainer().get(key("page"), PersistentDataType.INTEGER);
            if (playerId == null || displayName == null || page == null) {
                return false;
            }
            admin.playSound(admin.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
            openPage(admin, playerId, displayName, page);
            return true;
        }

        String worldName = meta.getPersistentDataContainer().get(key("world"), PersistentDataType.STRING);
        Integer x = meta.getPersistentDataContainer().get(key("x"), PersistentDataType.INTEGER);
        Integer y = meta.getPersistentDataContainer().get(key("y"), PersistentDataType.INTEGER);
        Integer z = meta.getPersistentDataContainer().get(key("z"), PersistentDataType.INTEGER);
        String playerId = meta.getPersistentDataContainer().get(key("player"), PersistentDataType.STRING);
        if (worldName == null || x == null || y == null || z == null || playerId == null) {
            return false;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            admin.sendMessage("§c[XrayTracker] Trigger world is not loaded.");
            return true;
        }

        admin.closeInventory();
        Location target = new Location(world, x + 0.5, y + 1.0, z + 0.5);
        admin.teleport(target);
        admin.sendMessage("§6[XrayTracker] §7Teleported to trigger location.");
        admin.playSound(admin.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
        XrayTracker.getInstance().getHighlighter().highlight(admin, XrayTracker.getInstance().getDbManager().getMiningRecords(playerId));
        return true;
    }

    private static ItemStack createTriggerItem(TriggerReport report, String playerIdentifier, String displayName) {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§cTrigger: §f" + report.oreType() + " §8(Score " + report.score() + ")");
        meta.setLore(List.of(
            "§7Reason: §f" + report.reason(),
            "§7World: §f" + report.world(),
            "§7Pos: §f" + report.x() + ", " + report.y() + ", " + report.z(),
            "§8" + TIME_FORMAT.format(Instant.ofEpochMilli(report.timestamp())),
            "§aClick to teleport + show path"
        ));
        meta.getPersistentDataContainer().set(key("player"), PersistentDataType.STRING, playerIdentifier);
        meta.getPersistentDataContainer().set(key("name"), PersistentDataType.STRING, displayName);
        meta.getPersistentDataContainer().set(key("world"), PersistentDataType.STRING, report.world());
        meta.getPersistentDataContainer().set(key("x"), PersistentDataType.INTEGER, report.x());
        meta.getPersistentDataContainer().set(key("y"), PersistentDataType.INTEGER, report.y());
        meta.getPersistentDataContainer().set(key("z"), PersistentDataType.INTEGER, report.z());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createBackButton(String playerId, String displayName) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§7◀ Back to Inspector");
        meta.getPersistentDataContainer().set(key("player"), PersistentDataType.STRING, playerId);
        meta.getPersistentDataContainer().set(key("name"), PersistentDataType.STRING, displayName);
        meta.getPersistentDataContainer().set(key("action"), PersistentDataType.STRING, "back");
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createNavButton(String nav, boolean enabled, String playerId, String displayName, int page) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(enabled ? ("previous".equals(nav) ? "§a◀ Previous Page" : "§aNext Page ▶") : "§7No page");
        meta.getPersistentDataContainer().set(key("player"), PersistentDataType.STRING, playerId);
        meta.getPersistentDataContainer().set(key("name"), PersistentDataType.STRING, displayName);
        meta.getPersistentDataContainer().set(key("page"), PersistentDataType.INTEGER, page);
        meta.getPersistentDataContainer().set(key("nav"), PersistentDataType.STRING, nav);
        item.setItemMeta(meta);
        return item;
    }

    private static NamespacedKey key(String suffix) {
        return new NamespacedKey(XrayTracker.getInstance(), "triggergui_" + suffix);
    }
}
