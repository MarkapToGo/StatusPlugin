package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.util.BatchedSaver;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player statuses and their persistence.
 */
public final class StatusManager {

    // How often changed statuses are written to player-status.yml in the background
    private static final long SAVE_INTERVAL_TICKS = 5 * 20L;

    private final StatusPlugin plugin;
    private final ConfigManager configManager;
    private final BatchedSaver saver;

    // These maps are also read by chat and PlaceholderAPI threads, so (re)loading
    // builds new maps and swaps them in instead of clearing and refilling them.

    // Cache of player statuses (UUID -> status key)
    private volatile Map<UUID, String> playerStatuses = new ConcurrentHashMap<>();

    // Cache of parsed status components
    private volatile Map<String, Component> statusDisplayCache = Map.of();

    // Status options (key -> MiniMessage format), in config order
    private volatile Map<String, String> statusOptions = Map.of();

    // Status restrictions (status key -> permission)
    private volatile Map<String, String> statusRestrictions = Map.of();

    // Tab list sorting order from config.yml
    private volatile List<String> sortingPriority = List.of();

    public StatusManager(@NotNull StatusPlugin plugin, @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.saver = new BatchedSaver(plugin, this::writeData);
        loadStatusOptions();
        loadPlayerStatuses();
    }

    /**
     * Load status options from config
     */
    private void loadStatusOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, Component> displays = new HashMap<>();
        Map<String, String> restrictions = new HashMap<>();

        ConfigurationSection statuses = configManager.getStatusOptions().getConfigurationSection("statuses");
        if (statuses != null) {
            for (String key : statuses.getKeys(false)) {
                String format = statuses.getString(key);
                if (format != null) {
                    // Convert legacy codes to MiniMessage format immediately
                    // This ensures correct parsing everywhere else
                    String convertedFormat = de.stylelabor.statusplugin.util.ColorUtil
                            .convertLegacyToMiniMessage(format);

                    options.put(key.toUpperCase(), convertedFormat);
                    // Pre-cache the parsed component
                    displays.put(key.toUpperCase(), plugin.parseMessage(convertedFormat));
                }
            }
        }

        // Load restrictions
        ConfigurationSection restrictionSection = configManager.getStatusOptions().getConfigurationSection("restrictions");
        if (restrictionSection != null) {
            for (String key : restrictionSection.getKeys(false)) {
                String permission = restrictionSection.getString(key);
                if (permission != null) {
                    restrictions.put(key.toUpperCase(), permission);
                }
            }
        }

        statusOptions = Collections.unmodifiableMap(options);
        statusDisplayCache = Map.copyOf(displays);
        statusRestrictions = Map.copyOf(restrictions);
        sortingPriority = List.copyOf(configManager.getConfig().getStringList("tablist.sorting.priority"));

        plugin.debug("Loaded " + options.size() + " status options");
    }

    /**
     * Load player statuses from file
     */
    private void loadPlayerStatuses() {
        Map<UUID, String> statuses = new ConcurrentHashMap<>();

        var playerStatusConfig = configManager.getPlayerStatus();
        for (String uuidString : playerStatusConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                String status = playerStatusConfig.getString(uuidString);
                if (status != null) {
                    statuses.put(uuid, status.trim().toUpperCase());
                }
            } catch (IllegalArgumentException e) {
                plugin.debug("Invalid UUID in player-status.yml: " + uuidString);
            }
        }

        playerStatuses = statuses;
        plugin.debug("Loaded statuses for " + statuses.size() + " players");
    }

    /**
     * Reload status options and player data
     */
    public void reload() {
        loadStatusOptions();
        loadPlayerStatuses();
    }

    /**
     * Reload only the status options, e.g. after a new status was added.
     * Player statuses stay as they are in memory.
     */
    public void reloadStatusOptions() {
        loadStatusOptions();
    }

    /**
     * Start writing changed statuses to disk in the background
     */
    public void startSaveTask() {
        saver.start(SAVE_INTERVAL_TICKS);
    }

    /**
     * Save player status data now
     */
    public void saveData() {
        saver.saveNow();
    }

    /**
     * Stop background saving and save pending changes
     */
    public void shutdown() {
        saver.shutdown();
    }

    /**
     * Write a snapshot of all player statuses to player-status.yml (runs on the
     * background save thread or during reload/shutdown)
     */
    private void writeData() {
        Map<UUID, String> statuses = playerStatuses;
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : statuses.entrySet()) {
            data.set(entry.getKey().toString(), entry.getValue());
        }

        configManager.savePlayerStatus(data);
        plugin.debug("Saved statuses for " + statuses.size() + " players");
    }

    /**
     * Get a player's status key
     */
    @Nullable
    public String getStatus(@NotNull UUID uuid) {
        String status = playerStatuses.get(uuid);
        return (status == null || status.isEmpty()) ? null : status;
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
        saver.markDirty();
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
        playerStatuses.put(uuid, "");
        saver.markDirty();
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
     * Handles specific order: Configured -> _OTHER_ (Undefined) ->
     * Configured(Bottom) -> No Status
     */
    public int getStatusPriority(@NotNull String statusKey) {
        if (statusKey == null || statusKey.isEmpty()) {
            return 999; // "No Status" is always last
        }

        List<String> priority = sortingPriority;
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
