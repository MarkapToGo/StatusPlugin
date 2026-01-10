package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks player deaths with batched saves and vanilla statistic sync.
 */
public class DeathTracker {

    private final StatusPlugin plugin;
    private final ConfigManager configManager;

    // Cache of player deaths (UUID -> death count)
    private final Map<UUID, Integer> playerDeaths = new ConcurrentHashMap<>();

    // Total server deaths
    private final AtomicLong totalDeaths = new AtomicLong(0);

    // Dirty flag for batched saves
    private volatile boolean dirty = false;

    // Save task
    private BukkitTask saveTask;

    private boolean syncWithVanilla;
    private int saveDelay;

    public DeathTracker(@NotNull StatusPlugin plugin, @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        loadConfig();
        loadData();
        startSaveTask();
    }

    /**
     * Load configuration
     */
    private void loadConfig() {
        var config = configManager.getConfig();
        syncWithVanilla = config.getBoolean("deaths.sync-with-vanilla", true);
        saveDelay = config.getInt("deaths.save-delay", 30);
    }

    /**
     * Check if death tracking is enabled
     */
    public boolean isEnabled() {
        return configManager.getConfig().getBoolean("deaths.enabled", true);
    }

    /**
     * Load death data from file
     */
    private void loadData() {
        playerDeaths.clear();

        var deathsConfig = configManager.getPlayerDeaths();
        for (String uuidString : deathsConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                int deaths = deathsConfig.getInt(uuidString);
                playerDeaths.put(uuid, deaths);
            } catch (IllegalArgumentException e) {
                plugin.debug("Invalid UUID in player-deaths.yml: " + uuidString);
            }
        }

        // Load total deaths
        totalDeaths.set(configManager.getServerStats().getLong("total-deaths", 0));

        plugin.debug("Loaded deaths for " + playerDeaths.size() + " players, total: " + totalDeaths.get());
    }

    /**
     * Start the periodic save task
     */
    private void startSaveTask() {
        if (saveDelay <= 0)
            return;

        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (dirty) {
                saveData();
                dirty = false;
            }
        }, saveDelay * 20L, saveDelay * 20L);
    }

    /**
     * Save death data
     */
    public void saveData() {
        var deathsConfig = configManager.getPlayerDeaths();

        // Clear existing data
        for (String key : deathsConfig.getKeys(false)) {
            deathsConfig.set(key, null);
        }

        // Save current data
        for (Map.Entry<UUID, Integer> entry : playerDeaths.entrySet()) {
            deathsConfig.set(entry.getKey().toString(), entry.getValue());
        }

        configManager.savePlayerDeaths();

        // Save total deaths
        var serverStats = configManager.getServerStats();
        serverStats.set("total-deaths", totalDeaths.get());
        configManager.saveServerStats();

        plugin.debug("Saved deaths for " + playerDeaths.size() + " players");
    }

    /**
     * Get a player's death count
     */
    public int getDeaths(@NotNull UUID uuid) {
        return playerDeaths.getOrDefault(uuid, 0);
    }

    /**
     * Get a player's death count
     */
    public int getDeaths(@NotNull Player player) {
        return getDeaths(player.getUniqueId());
    }

    /**
     * Set a player's death count
     */
    public void setDeaths(@NotNull UUID uuid, int count) {
        playerDeaths.put(uuid, Math.max(0, count));
        dirty = true;

        // Sync with vanilla if enabled and player is online
        if (syncWithVanilla) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.setStatistic(Statistic.DEATHS, count);
                    }
                });
            }
        }
    }

    /**
     * Set a player's death count
     */
    public void setDeaths(@NotNull Player player, int count) {
        setDeaths(player.getUniqueId(), count);
    }

    /**
     * Add deaths to a player
     */
    public void addDeaths(@NotNull UUID uuid, int amount) {
        int current = getDeaths(uuid);
        setDeaths(uuid, current + amount);
    }

    /**
     * Add deaths to a player
     */
    public void addDeaths(@NotNull Player player, int amount) {
        addDeaths(player.getUniqueId(), amount);
    }

    /**
     * Remove deaths from a player
     */
    public void removeDeaths(@NotNull UUID uuid, int amount) {
        int current = getDeaths(uuid);
        setDeaths(uuid, current - amount);
    }

    /**
     * Remove deaths from a player
     */
    public void removeDeaths(@NotNull Player player, int amount) {
        removeDeaths(player.getUniqueId(), amount);
    }

    /**
     * Reset a player's deaths to 0
     */
    public void resetDeaths(@NotNull UUID uuid) {
        setDeaths(uuid, 0);
    }

    /**
     * Reset a player's deaths to 0
     */
    public void resetDeaths(@NotNull Player player) {
        resetDeaths(player.getUniqueId());
    }

    /**
     * Record a death (called when player dies)
     */
    public void recordDeath(@NotNull Player player) {
        if (!isEnabled())
            return;

        addDeaths(player, 1);
        totalDeaths.incrementAndGet();
        dirty = true;

        plugin.debug(player.getName() + " died. Total deaths: " + getDeaths(player));
    }

    /**
     * Sync a player's deaths with their vanilla statistic
     */
    public void syncWithVanilla(@NotNull Player player) {
        if (!syncWithVanilla)
            return;

        int vanillaDeaths = player.getStatistic(Statistic.DEATHS);
        int trackedDeaths = getDeaths(player);

        if (vanillaDeaths != trackedDeaths) {
            // Use vanilla as source of truth if we have no data
            if (trackedDeaths == 0 && vanillaDeaths > 0) {
                setDeaths(player, vanillaDeaths);
                plugin.debug("Synced " + player.getName() + "'s deaths from vanilla: " + vanillaDeaths);
            }
        }
    }

    /**
     * Get total server deaths
     */
    public long getTotalDeaths() {
        return totalDeaths.get();
    }

    /**
     * Reload configuration
     */
    public void reload() {
        if (saveTask != null) {
            saveTask.cancel();
        }
        loadConfig();
        loadData();
        startSaveTask();
    }

    /**
     * Shutdown and save
     */
    public void shutdown() {
        if (saveTask != null) {
            saveTask.cancel();
        }
        saveData();
    }
}
