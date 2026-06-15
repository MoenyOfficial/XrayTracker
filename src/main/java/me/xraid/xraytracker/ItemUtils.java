package me.xraid.xraytracker;

import org.bukkit.inventory.ItemStack;

public class ItemUtils {
    public static String getItemName(ItemStack item) {
        if (item == null) return "AIR";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item.getType().name();
    }
}
