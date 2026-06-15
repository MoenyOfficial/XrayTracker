package me.xraid.xraytracker;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class DiscordWebhook {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    public static void sendAlert(
        String webhookUrl,
        String playerName,
        UUID playerUuid,
        String oreType,
        int score,
        String reason,
        String world,
        int x,
        int y,
        int z
    ) {
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("http")) {
            return;
        }

        // Build Payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", "XrayTracker Alerts");
        payload.put("avatar_url", "https://crafatar.com/avatars/069a79f4-44e9-4726-a5be-fca90e38aaf5?size=128");

        List<Map<String, Object>> embeds = new ArrayList<>();
        Map<String, Object> embed = new HashMap<>();
        embed.put("title", "🚨 Xray Alert: " + playerName);
        embed.put("color", 16711680); // Red color

        StringBuilder desc = new StringBuilder();
        desc.append("**Player:** ").append(playerName).append("\n");
        desc.append("**UUID:** `").append(playerUuid.toString()).append("`\n");
        desc.append("**Ore Block:** `").append(oreType).append("`\n");
        desc.append("**Suspicion Score:** `").append(score).append("`\n");
        desc.append("**Trigger Reason:** *").append(reason).append("*\n");
        desc.append("**Coordinates:** `").append(world).append(" @ ").append(x).append(", ").append(y).append(", ").append(z).append("`\n");
        embed.put("description", desc.toString());

        Map<String, String> thumbnail = new HashMap<>();
        thumbnail.put("url", "https://crafatar.com/avatars/" + playerUuid + "?size=64&overlay");
        embed.put("thumbnail", thumbnail);

        embed.put("timestamp", java.time.Instant.now().toString());

        Map<String, Object> footer = new HashMap<>();
        footer.put("text", "XrayTracker Security System");
        embed.put("footer", footer);

        embeds.add(embed);
        payload.put("embeds", embeds);

        String jsonPayload = GSON.toJson(payload);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() >= 400) {
                    Bukkit.getLogger().log(Level.WARNING, "[XrayTracker] Discord webhook failed with status: " + response.statusCode() + " and body: " + response.body());
                }
            })
            .exceptionally(ex -> {
                Bukkit.getLogger().log(Level.SEVERE, "[XrayTracker] Error sending Discord webhook: " + ex.getMessage());
                return null;
            });
    }
}
