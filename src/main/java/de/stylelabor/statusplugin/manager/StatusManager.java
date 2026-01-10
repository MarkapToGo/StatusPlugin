package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player statuses and their persistence.
 */
public class StatusManager {

    private final StatusPlugin plugin;
    private final ConfigManager configManager;

    // Cache of player statuses (UUID -> status key)
    private final Map<UUID, String> playerStatuses = new ConcurrentHashMap<>();

    // Cache of parsed status components
    private final Map<String, Component> statusDisplayCache = new ConcurrentHashMap<>();

    // Status options (key -> MiniMessage format)
    private final Map<String, String> statusOptions = new LinkedHashMap<>();

    // Status restrictions (status key -> permission)
    private final Map<String, String> statusRestrictions = new HashMap<>();

    public StatusManager(@NotNull StatusPlugin plugin, @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        loadStatusOptions();
        loadPlayerStatuses();
    }

    /**
     * Load status options from config
     */
    private void loadStatusOptions() {
        statusOptions.clear();
        statusDisplayCache.clear();
        statusRestrictions.clear();

        ConfigurationSection statuses = configManager.getStatusOptions().getConfigurationSection("statuses");
        if (statuses != null) {
            for (String key : statuses.getKeys(false)) {
                String format = statuses.getString(key);
                if (format != null) {
                    // Convert legacy codes to MiniMessage format immediately
                    // This ensures correct parsing everywhere else
                    String convertedFormat = de.stylelabor.statusplugin.util.ColorUtil
                            .convertLegacyToMiniMessage(format);

                    statusOptions.put(key.toUpperCase(), convertedFormat);
                    // Pre-cache the parsed component
                    statusDisplayCache.put(key.toUpperCase(), plugin.parseMessage(convertedFormat));
                }
            }
        }

        // Load restrictions
        ConfigurationSection restrictions = configManager.getStatusOptions().getConfigurationSection("restrictions");
        if (restrictions != null) {
            for (String key : restrictions.getKeys(false)) {
                String permission = restrictions.getString(key);
                if (permission != null) {
                    statusRestrictions.put(key.toUpperCase(), permission);
                }
            }
        }

        plugin.debug("Loaded " + statusOptions.size() + " status options");
    }

    /**
     * Load player statuses from file
     */
    private void loadPlayerStatuses() {
        playerStatuses.clear();

        var playerStatusConfig = configManager.getPlayerStatus();
        for (String uuidString : playerStatusConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                String status = playerStatusConfig.getString(uuidString);
                if (status != null && !status.isEmpty()) {
                    playerStatuses.put(uuid, status.toUpperCase());
                }
            } catch (IllegalArgumentException e) {
                plugin.debug("Invalid UUID in player-status.yml: " + uuidString);
            }
        }

        plugin.debug("Loaded statuses for " + playerStatuses.size() + " players");
    }

    /**
     * Reload status options and player data
     */
    public void reload() {
        loadStatusOptions();
        loadPlayerStatuses();
    }

    /**
     * Save player status data
     */
    public void saveData() {
        var playerStatusConfig = configManager.getPlayerStatus();

        // Clear existing data
        for (String key : playerStatusConfig.getKeys(false)) {
            playerStatusConfig.set(key, null);
        }

        // Save current data
        for (Map.Entry<UUID, String> entry : playerStatuses.entrySet()) {
            playerStatusConfig.set(entry.getKey().toString(), entry.getValue());
        }

        configManager.savePlayerStatus();
        plugin.debug("Saved statuses for " + playerStatuses.size() + " players");
    }

    /**
     * Get a player's status key
     */
    @Nullable
    public String getStatus(@NotNull UUID uuid) {
        return playerStatuses.get(uuid);
    }

    /**
     * Get a player's status key
     */
    @Nullable
    public String getStatus(@NotNull Player player) {
        return getStatus(player.getUniqueId());
    }

    /**
     * Get a player's status as a formatted Component
     */
    @NotNull
    public Component getStatusDisplay(@NotNull UUID uuid) {
        String status = playerStatuses.get(uuid);
        if (status == null || status.isEmpty()) {
            return Component.empty();
        }
        return statusDisplayCache.getOrDefault(status, Component.empty());
    }

    /**
     * Get a player's status as a formatted Component
     */
    @NotNull
    public Component getStatusDisplay(@NotNull Player player) {
        return getStatusDisplay(player.getUniqueId());
    }

    /**
     * Get a player's status as a raw MiniMessage string
     */
    @NotNull
    public String getStatusFormat(@NotNull UUID uuid) {
        String status = playerStatuses.get(uuid);
        if (status == null || status.isEmpty()) {
            return "";
        }
        return statusOptions.getOrDefault(status, "");
    }

    /**
     * Set a player's status
     * 
     * @return true if successful, false if status doesn't exist
     */
    public boolean setStatus(@NotNull UUID uuid, @NotNull String statusKey) {
        String key = statusKey.toUpperCase();
        if (!statusOptions.containsKey(key)) {
            return false;
        }
        playerStatuses.put(uuid, key);
        saveData();
        return true;
    }

    /**
     * Set a player's status
     */
    public boolean setStatus(@NotNull Player player, @NotNull String statusKey) {
        return setStatus(player.getUniqueId(), statusKey);
    }

    /**
     * Clear a player's status
     */
    public void clearStatus(@NotNull UUID uuid) {
        playerStatuses.remove(uuid);
        saveData();
    }

    /**
     * Clear a player's status
     */
    public void clearStatus(@NotNull Player player) {
        clearStatus(player.getUniqueId());
    }

    /**
     * Check if a status exists
     */
    public boolean statusExists(@NotNull String statusKey) {
        return statusOptions.containsKey(statusKey.toUpperCase());
    }

    /**
     * Check if a player has permission to use a status
     */
    public boolean hasPermission(@NotNull Player player, @NotNull String statusKey) {
        String key = statusKey.toUpperCase();
        String permission = statusRestrictions.get(key);
        if (permission == null) {
            return true; // No restriction
        }
        return player.hasPermission(permission);
    }

    /**
     * Get all available status keys
     */
    @NotNull
    public Set<String> getAvailableStatuses() {
        return Collections.unmodifiableSet(statusOptions.keySet());
    }

    /**
     * Get statuses available to a player (respecting permissions)
     */
    @NotNull
    public Set<String> getAvailableStatuses(@NotNull Player player) {
        Set<String> available = new LinkedHashSet<>();
        for (String status : statusOptions.keySet()) {
            if (hasPermission(player, status)) {
                available.add(status);
            }
        }
        return available;
    }

    /**
     * Get the status display format for a status key
     */
    @NotNull
    public String getStatusFormatByKey(@NotNull String statusKey) {
        return statusOptions.getOrDefault(statusKey.toUpperCase(), "");
    }

    /**
     * Get status priority for sorting (lower = higher priority)
     */
    /**
     * Get status priority for sorting (lower = higher priority)
     * Handles specific order: Configured -> _OTHER_ (Undefined) ->
     * Configured(Bottom) -> No Status
     */
    public int getStatusPriority(@NotNull String statusKey) {
        if (statusKey == null || statusKey.isEmpty()) {
            return 999; // "No Status" is always last
        }

        List<String> priority = configManager.getConfig().getStringList("tablist.sorting.priority");
        int index = priority.indexOf(statusKey.toUpperCase());

        if (index >= 0) {
            return index;
        }

        // If not explicit, check for wildcard position
        int otherIndex = priority.indexOf("_OTHER_");
        if (otherIndex >= 0) {
            return otherIndex;
        }

        return priority.size();
    }

    /**
     * Assign default status to a player if configured
     */
    public void assignDefaultStatus(@NotNull Player player) {
        if (playerStatuses.containsKey(player.getUniqueId())) {
            return; // Already has a status
        }

        String defaultStatus = configManager.getConfig().getString("general.default-status", "");
        if (!defaultStatus.isEmpty() && statusOptions.containsKey(defaultStatus.toUpperCase())) {
            if (hasPermission(player, defaultStatus)) {
                setStatus(player, defaultStatus);
                plugin.debug("Assigned default status '" + defaultStatus + "' to " + player.getName());
            }
        }
    }
}
