package me.xraid.xraytracker.gui;

import me.xraid.xraytracker.XrayTracker;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PlayerListGui {
    private static final int ENTRIES_PER_PAGE = 45;

    public static void open(Player p) {
        openPage(p, 0);
    }

    public static void openPage(Player p, int page) {
        List<PlayerStatsSnapshot> players = XrayTracker.getInstance()
            .getDbManager()
            .getAllPlayerStats(XrayTracker.getInstance().getConfig().getInt("suspicion-window-seconds", 60));

        // Apply search filter
        String filter = XrayTracker.getInstance().getSearchFilter(p);
        if (filter != null) {
            players.removeIf(stats -> !stats.playerName().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)));
        }

        // Apply sort mode
        XrayTracker.SortMode sortMode = XrayTracker.getInstance().getSortMode(p);
        switch (sortMode) {
            case SUSPICION -> players.sort(Comparator.comparingInt(PlayerStatsSnapshot::recentSuspicionScore).reversed());
            case NAME -> players.sort(Comparator.comparing(PlayerStatsSnapshot::playerName, String.CASE_INSENSITIVE_ORDER));
            case LAST_ACTIVE -> players.sort(Comparator.comparingLong(PlayerStatsSnapshot::lastSeenTimestamp).reversed());
            case ALERTS -> players.sort(Comparator.comparingInt(PlayerStatsSnapshot::alertCount).reversed());
        }

        int totalPages = Math.max(1, (players.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54, "§8Players §7(" + (safePage + 1) + "/" + totalPages + ")");

        // Fill contents
        int start = safePage * ENTRIES_PER_PAGE;
        int end = Math.min(start + ENTRIES_PER_PAGE, players.size());
        for (int i = start; i < end; i++) {
            PlayerStatsSnapshot stats = players.get(i);
            inv.setItem(i - start, createPlayerHeadItem(stats));
        }

        // Add empty info message if list is empty
        if (players.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta meta = empty.getItemMeta();
            meta.setDisplayName(filter != null ? "§cNo players match your search" : "§cNo tracked players yet");
            if (filter != null) {
                meta.setLore(List.of("§7Filter: §f" + filter, "§7Click search icon to clear filter."));
            }
            empty.setItemMeta(meta);
            inv.setItem(22, empty);
        }

        // Add border pane fillers
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }

        // Pagination buttons
        inv.setItem(45, createNavButton("prev", safePage > 0, safePage - 1));
        inv.setItem(53, createNavButton("next", safePage < totalPages - 1, safePage + 1));

        // Sort toggle button
        inv.setItem(47, createSortButton(sortMode));

        // Search button
        inv.setItem(49, createSearchButton(filter));

        p.openInventory(inv);
    }

    public static boolean handleClick(Player admin, ItemStack clicked, int slot) {
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(key("action"), PersistentDataType.STRING);
        if (action == null) {
            return false;
        }

        switch (action) {
            case "prev", "next" -> {
                Integer targetPage = meta.getPersistentDataContainer().get(key("page"), PersistentDataType.INTEGER);
                if (targetPage != null) {
                    admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                    openPage(admin, targetPage);
                }
            }
            case "sort" -> {
                XrayTracker.getInstance().toggleSortMode(admin);
                admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                openPage(admin, 0);
            }
            case "search" -> {
                admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                String currentFilter = XrayTracker.getInstance().getSearchFilter(admin);
                if (currentFilter != null) {
                    // Left click to clear filter
                    XrayTracker.getInstance().setSearchFilter(admin, null);
                    admin.sendMessage("§6[XrayTracker] §7Search filter cleared.");
                    openPage(admin, 0);
                } else {
                    admin.closeInventory();
                    XrayTracker.getInstance().setSearching(admin, true);
                    admin.sendMessage(" ");
                    admin.sendMessage("§6§l[XrayTracker] §eType player name to search in chat...");
                    admin.sendMessage("§7Type §c'cancel'§7 to close search.");
                    admin.sendMessage(" ");
                }
            }
            case "inspect" -> {
                String targetName = meta.getPersistentDataContainer().get(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING);
                String targetId = meta.getPersistentDataContainer().get(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING);
                if (targetName != null && targetId != null) {
                    admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                    PlayerInspectGui.open(admin, targetName, targetId);
                }
            }
        }
        return true;
    }

    private static ItemStack createPlayerHeadItem(PlayerStatsSnapshot stats) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(stats.playerId()));
            sm.setOwningPlayer(offlinePlayer);
        } catch (IllegalArgumentException ignored) {}

        sm.setDisplayName("§6§l" + stats.playerName());
        
        List<String> lore = new ArrayList<>();
        lore.add("§8" + stats.playerId());
        lore.add("");
        lore.add("§7Suspicion Level:");
        lore.add(getSuspicionBar(stats.recentSuspicionScore()));
        lore.add("");
        lore.add("§7Recent suspicion: §e" + stats.recentSuspicionScore());
        lore.add("§7Tracked ore breaks: §e" + stats.trackedOreBreaks());
        lore.add("§7Total Mining Records: §f" + stats.totalMiningRecords());
        lore.add("§7Alerts Triggered: §c" + stats.alertCount());
        lore.add("");
        lore.add("§7Last seen: §f" + formatLastSeen(stats.lastSeenTimestamp()));
        lore.add("");
        lore.add("§a▶ Click to Inspect player");
        sm.setLore(lore);

        sm.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerNameKey(), PersistentDataType.STRING, stats.playerName());
        sm.getPersistentDataContainer().set(XrayTracker.getInstance().getPlayerIdKey(), PersistentDataType.STRING, stats.playerId());
        
        // Tag this as a player selection item
        sm.getPersistentDataContainer().set(key("action"), PersistentDataType.STRING, "inspect");
        
        head.setItemMeta(sm);
        return head;
    }

    private static String getSuspicionBar(int score) {
        int bars = Math.min(10, score / 3);
        StringBuilder sb = new StringBuilder("§8[");
        String color = "§a";
        if (score >= 16) color = "§c";
        else if (score >= 8) color = "§6";
        
        sb.append(color);
        for (int i = 0; i < 10; i++) {
            if (i < bars) {
                sb.append("█");
            } else {
                sb.append("§8█" + color);
            }
        }
        sb.append("§8]");
        return sb.toString();
    }

    private static ItemStack createNavButton(String action, boolean enabled, int targetPage) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(enabled ? (action.equals("prev") ? "§a◀ Previous Page" : "§aNext Page ▶") : "§7No page");
        meta.getPersistentDataContainer().set(key("action"), PersistentDataType.STRING, action);
        meta.getPersistentDataContainer().set(key("page"), PersistentDataType.INTEGER, targetPage);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createSortButton(XrayTracker.SortMode mode) {
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6§lSort Mode");
        meta.setLore(List.of(
            "§7Current: §e" + mode.getDisplayName(),
            "",
            "§7Click to cycle:",
            " §7• Highest Suspicion",
            " §7• Alphabetical",
            " §7• Last Active",
            " §7• Most Alerts"
        ));
        meta.getPersistentDataContainer().set(key("action"), PersistentDataType.STRING, "sort");
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createSearchButton(String currentFilter) {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e§lSearch Players");
        if (currentFilter != null) {
            meta.setLore(List.of(
                "§7Active filter: §b" + currentFilter,
                "",
                "§aLeft-Click to clear filter",
                "§eRight-Click to search again"
            ));
        } else {
            meta.setLore(List.of(
                "§7Filter players by name.",
                "",
                "§aClick to start search in chat"
            ));
        }
        meta.getPersistentDataContainer().set(key("action"), PersistentDataType.STRING, "search");
        item.setItemMeta(meta);
        return item;
    }

    private static String formatLastSeen(long timestamp) {
        if (timestamp <= 0L) {
            return "never";
        }
        Duration age = Duration.ofMillis(System.currentTimeMillis() - timestamp);
        long minutes = age.toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = age.toHours();
        if (hours < 24) return hours + "h ago";
        return age.toDays() + "d ago";
    }

    private static NamespacedKey key(String suffix) {
        return new NamespacedKey(XrayTracker.getInstance(), "playerlist_" + suffix);
    }
}
