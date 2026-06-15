package me.xraid.xraytracker;

import me.xraid.xraytracker.gui.TriggerReportGui;
import me.xraid.xraytracker.gui.PlayerListGui;
import me.xraid.xraytracker.models.PlayerStatsSnapshot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CommandHandler implements CommandExecutor, TabCompleter {
    private final XrayTracker plugin;

    public CommandHandler(XrayTracker plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("xraytracker.admin")) {
            s.sendMessage("§cYou do not have permission.");
            return true;
        }

        if (a.length == 0 || a[0].equalsIgnoreCase("open")) {
            if (s instanceof Player p) {
                PlayerListGui.open(p);
            } else {
                sendUsage(s);
            }
            return true;
        }

        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.loadConfigValues();
                s.sendMessage("§a[XrayTracker] Configuration reloaded.");
            }
            case "stats" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUsage: /xt stats <player>");
                    return true;
                }
                plugin.sendStats(s, a[1], a[1]);
            }
            case "clear" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUsage: /xt clear <player>");
                    return true;
                }
                boolean removed = plugin.getDbManager().clearPlayerData(a[1]);
                s.sendMessage(removed
                    ? "§a[XrayTracker] Cleared data for §f" + a[1] + "§a."
                    : "§c[XrayTracker] No stored data found for §f" + a[1] + "§c.");
            }
            case "triggers" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUsage: /xt triggers <player>");
                    return true;
                }
                if (!(s instanceof Player p)) {
                    s.sendMessage("§cThis command can only be used by players.");
                    return true;
                }
                TriggerReportGui.open(p, a[1], a[1]);
            }
            case "stop" -> {
                if (s instanceof Player p) {
                    plugin.getHighlighter().stopHighlight(p, true);
                } else {
                    s.sendMessage("§cThis command can only be used by players.");
                }
            }
            case "replay" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUsage: /xt replay <player>");
                    return true;
                }
                if (!(s instanceof Player p)) {
                    s.sendMessage("§cThis command can only be used by players.");
                    return true;
                }
                plugin.getHighlighter().startPlayback(p, plugin.getDbManager().getMiningRecords(a[1]), a[1], a[1]);
            }
            case "replaycontrol", "rc" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUsage: /xt replaycontrol <action>");
                    return true;
                }
                if (!(s instanceof Player p)) {
                    s.sendMessage("§cThis command can only be used by players.");
                    return true;
                }
                String action = a[1].toLowerCase(Locale.ROOT);
                switch (action) {
                    case "toggle" -> plugin.getHighlighter().togglePause(p);
                    case "speed" -> plugin.getHighlighter().adjustSpeed(p);
                    case "skip" -> plugin.getHighlighter().skipForward(p);
                    case "rewind" -> plugin.getHighlighter().skipBackward(p);
                    case "restart" -> plugin.getHighlighter().restartReplay(p);
                    case "stop" -> plugin.getHighlighter().stopHighlight(p, true);
                    default -> p.sendMessage("§cUnknown replay control action: " + action);
                }
            }
            case "toggle" -> {
                if (s instanceof Player p) {
                    boolean muted = plugin.toggleAlerts(p);
                    s.sendMessage(muted
                        ? "§a[XrayTracker] Alerts muted. You will no longer receive staff alerts."
                        : "§a[XrayTracker] Alerts enabled. You will now receive staff alerts.");
                } else {
                    s.sendMessage("§cThis command can only be used by players.");
                }
            }
            case "top" -> {
                int limit = 5;
                if (a.length >= 2) {
                    try {
                        limit = Math.max(1, Integer.parseInt(a[1]));
                    } catch (NumberFormatException ex) {
                        s.sendMessage("§cInvalid number. Usage: /xt top [amount]");
                        return true;
                    }
                }

                List<PlayerStatsSnapshot> players = plugin.getDbManager().getAllPlayerStats(plugin.getConfig().getInt("suspicion-window-seconds", 60));
                if (players.isEmpty()) {
                    s.sendMessage("§e[XrayTracker] No tracked players yet.");
                    return true;
                }

                s.sendMessage("§6§l[XrayTracker] §fTop suspicious players:");
                int shown = Math.min(limit, players.size());
                for (int i = 0; i < shown; i++) {
                    PlayerStatsSnapshot stats = players.get(i);
                    s.sendMessage("§e" + (i + 1) + ". §f" + stats.playerName() + " §8- §7score: §c" + stats.recentSuspicionScore()
                        + " §8| §7ores: §e" + stats.trackedOreBreaks() + " §8| §7alerts: §6" + stats.alertCount());
                }
            }
            default -> sendUsage(s);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6§l[XrayTracker] §fCommands:");
        sender.sendMessage("§e/xt §7- Open player inspector GUI");
        sender.sendMessage("§e/xt stats <player> §7- Show stored anti-xray stats");
        sender.sendMessage("§e/xt top [amount] §7- Show most suspicious tracked players");
        sender.sendMessage("§e/xt triggers <player> §7- Open trigger reports GUI");
        sender.sendMessage("§e/xt replay <player> §7- Play animated path replay of player");
        sender.sendMessage("§e/xt clear <player> §7- Delete stored data for a player");
        sender.sendMessage("§e/xt toggle §7- Toggle alert messages on/off");
        sender.sendMessage("§e/xt stop §7- Clear active path highlighting");
        sender.sendMessage("§e/xt reload §7- Reload plugin config");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("xraytracker.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filterCompletions(args[0], List.of("open", "stats", "top", "triggers", "replay", "clear", "toggle", "stop", "reload"));
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("stats") || args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("triggers") || args[0].equalsIgnoreCase("replay"))) {
            return filterCompletions(args[1], new ArrayList<>(plugin.getDbManager().getKnownPlayerNames()));
        }

        return List.of();
    }

    private List<String> filterCompletions(String input, List<String> source) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return source.stream()
            .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }
}
