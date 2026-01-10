package de.stylelabor.statusplugin.integration;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.manager.CountryManager;
import de.stylelabor.statusplugin.manager.DeathTracker;
import de.stylelabor.statusplugin.manager.StatusManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;

/**
 * PlaceholderAPI expansion for StatusPlugin.
 * 
 * Available placeholders:
 * - %statusplugin_status% - Player's status display
 * - %statusplugin_status_raw% - Player's status key
 * - %statusplugin_deaths% - Player's death count
 * - %statusplugin_country% - Player's country name
 * - %statusplugin_countrycode% - Player's country code
 * - %statusplugin_performance% - Server TPS performance
 * - %statusplugin_mspt% - Server MSPT
 * - %statusplugin_total_deaths% - Total server deaths (formatted)
 * - %statusplugin_total_deaths_raw% - Total server deaths (raw number)
 */
public class PlaceholderAPIExpansion extends PlaceholderExpansion {

    private static final DecimalFormat TPS_FORMAT = new DecimalFormat("#0.00");
    private static final DecimalFormat MSPT_FORMAT = new DecimalFormat("#0.0");

    private final StatusPlugin plugin;
    private final StatusManager statusManager;
    private final DeathTracker deathTracker;
    private final CountryManager countryManager;

    public PlaceholderAPIExpansion(@NotNull StatusPlugin plugin,
            @NotNull StatusManager statusManager,
            @NotNull DeathTracker deathTracker,
            @NotNull CountryManager countryManager) {
        this.plugin = plugin;
        this.statusManager = statusManager;
        this.deathTracker = deathTracker;
        this.countryManager = countryManager;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "statusplugin";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return plugin.getDescription().getAuthors().isEmpty()
                ? "Unknown"
                : plugin.getDescription().getAuthors().get(0);
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Don't unregister on reload
    }

    @Override
    @Nullable
    public String onRequest(@NotNull OfflinePlayer player, @NotNull String params) {
        // Server-wide placeholders (don't require player)
        switch (params.toLowerCase()) {
            case "performance" -> {
                double tps = Bukkit.getTPS()[0];
                return getPerformanceString(tps);
            }
            case "mspt" -> {
                return MSPT_FORMAT.format(Bukkit.getAverageTickTime());
            }
            case "total_deaths" -> {
                return formatNumber(deathTracker.getTotalDeaths());
            }
            case "total_deaths_raw" -> {
                return String.valueOf(deathTracker.getTotalDeaths());
            }
        }

        // Player-specific placeholders
        if (player == null) {
            return "";
        }

        return switch (params.toLowerCase()) {
            case "status" -> {
                String statusFormat = statusManager.getStatusFormat(player.getUniqueId());
                yield statusFormat.isEmpty() ? "" : statusFormat;
            }
            case "status_raw" -> {
                String status = statusManager.getStatus(player.getUniqueId());
                yield status != null ? status : "";
            }
            case "deaths" -> String.valueOf(deathTracker.getDeaths(player.getUniqueId()));
            case "deaths_formatted" -> {
                int deaths = deathTracker.getDeaths(player.getUniqueId());
                yield "<dark_gray>[</dark_gray><red>☠</red> <red>" + deaths + "</red><dark_gray>]</dark_gray>";
            }
            case "country" -> countryManager.getCountry(player.getUniqueId()).orElse("");
            case "countrycode" -> countryManager.getCountryCode(player.getUniqueId()).orElse("");
            default -> null;
        };
    }

    /**
     * Get a colored TPS performance string
     */
    @NotNull
    private String getPerformanceString(double tps) {
        String color;
        if (tps >= 19.5) {
            color = "<green>";
        } else if (tps >= 18.0) {
            color = "<yellow>";
        } else if (tps >= 15.0) {
            color = "<gold>";
        } else {
            color = "<red>";
        }
        return color + TPS_FORMAT.format(Math.min(tps, 20.0)) + " TPS";
    }

    /**
     * Format a large number with K/M suffixes
     */
    @NotNull
    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }
}
