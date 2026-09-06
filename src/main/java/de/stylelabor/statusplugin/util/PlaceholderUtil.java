package de.stylelabor.statusplugin.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;

/**
 * Utility class for handling PlaceholderAPI parsing.
 */
public final class PlaceholderUtil {

    private static StatusPlugin plugin = null;
    private static boolean enabled = false;
    private static boolean initialized = false;

    private PlaceholderUtil() {
        // Utility class
    }

    /**
     * Check if PlaceholderAPI is enabled and available
     */
    public static void init(@NotNull StatusPlugin pluginInstance, @NotNull ConfigManager configManager) {
        plugin = pluginInstance;
        boolean pluginPresent = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean configEnabled = configManager.getConfig().getBoolean("integrations.placeholderapi.enabled", true);

        enabled = pluginPresent && configEnabled;
        initialized = true;
    }

    public static void init(@NotNull ConfigManager configManager) {
        boolean pluginPresent = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean configEnabled = configManager.getConfig().getBoolean("integrations.placeholderapi.enabled", true);

        enabled = pluginPresent && configEnabled;
        initialized = true;
    }

    /**
     * Parse placeholders in text safely on the main thread
     * 
     * @param player The player to parse placeholders for (can be null for
     *               non-player placeholders)
     * @param text   The text to parse
     * @return The parsed text
     */
    @NotNull
    public static String parse(@Nullable OfflinePlayer player, @NotNull String text) {
        if (!enabled || text.indexOf('%') == -1) {
            return text;
        }

        if (Bukkit.isPrimaryThread()) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable e) {
                return text;
            }
        }

        // If called asynchronously, resolve safely on main thread if plugin is available
        if (plugin != null && plugin.isEnabled()) {
            try {
                return Bukkit.getScheduler().callSyncMethod(plugin, () ->
                        me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text)
                ).get(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable e) {
                try {
                    return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
                } catch (Throwable t) {
                    return text;
                }
            }
        }

        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable e) {
            return text;
        }
    }
}
