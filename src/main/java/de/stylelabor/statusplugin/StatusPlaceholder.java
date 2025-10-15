package de.stylelabor.statusplugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class StatusPlaceholder extends PlaceholderExpansion {

    private final StatusPlugin plugin;

    public StatusPlaceholder(StatusPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "statusplugin";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) {
            return "";
        }

        if (identifier.equals("status")) {
            String status = plugin.getPlayerStatus(player.getUniqueId());
            return ChatColor.translateAlternateColorCodes('&', status);
        } else if (identifier.equals("country")) {
            if (!plugin.getConfig().getBoolean("country-location-enabled", false)) {
                return "";
            }
            CountryLocationManager countryManager = plugin.getCountryLocationManager();
            if (countryManager == null) {
                return "";
            }
            CountryLocationManager.CountryData countryData = countryManager.getPlayerCountry(player.getUniqueId());
            return countryData != null ? countryData.getCountry() : "";
        } else if (identifier.equals("countrycode")) {
            if (!plugin.getConfig().getBoolean("country-location-enabled", false)) {
                return "";
            }
            CountryLocationManager countryManager = plugin.getCountryLocationManager();
            if (countryManager == null) {
                return "";
            }
            CountryLocationManager.CountryData countryData = countryManager.getPlayerCountry(player.getUniqueId());
            return countryData != null ? countryData.getCountryCode() : "";
        } else if (identifier.equals("deaths")) {
            return String.valueOf(plugin.getPlayerDeaths(player.getUniqueId()));
        }

        return null;
    }
}
