package de.stylelabor.statusplugin.config;

import de.stylelabor.statusplugin.StatusPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Manages all YAML configuration files for the plugin.
 */
public class ConfigManager {

    private final StatusPlugin plugin;

    // Configuration files
    private FileConfiguration config;
    private FileConfiguration statusOptions;
    private FileConfiguration tablist;
    private FileConfiguration language;

    // Data files
    private FileConfiguration playerStatus;
    private FileConfiguration playerDeaths;
    private FileConfiguration playerCountries;
    private FileConfiguration serverStats;

    // File references
    private File statusOptionsFile;
    private File tablistFile;
    private File languageFile;
    private File playerStatusFile;
    private File playerDeathsFile;
    private File playerCountriesFile;
    private File serverStatsFile;

    public ConfigManager(@NotNull StatusPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Load all configuration files
     */
    public void loadAll() {
        // Save default configs
        plugin.saveDefaultConfig();
        saveDefaultResource("status-options.yml");
        saveDefaultResource("tablist.yml");
        saveDefaultResource("language.yml");

        // Load main config
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Load other configs
        statusOptionsFile = new File(plugin.getDataFolder(), "status-options.yml");
        statusOptions = YamlConfiguration.loadConfiguration(statusOptionsFile);
        setDefaults(statusOptions, "status-options.yml");

        tablistFile = new File(plugin.getDataFolder(), "tablist.yml");
        tablist = YamlConfiguration.loadConfiguration(tablistFile);
        setDefaults(tablist, "tablist.yml");

        languageFile = new File(plugin.getDataFolder(), "language.yml");
        language = YamlConfiguration.loadConfiguration(languageFile);
        setDefaults(language, "language.yml");

        // Load data files
        playerStatusFile = new File(plugin.getDataFolder(), "player-status.yml");
        playerStatus = YamlConfiguration.loadConfiguration(playerStatusFile);

        playerDeathsFile = new File(plugin.getDataFolder(), "player-deaths.yml");
        playerDeaths = YamlConfiguration.loadConfiguration(playerDeathsFile);

        playerCountriesFile = new File(plugin.getDataFolder(), "player-countries.yml");
        playerCountries = YamlConfiguration.loadConfiguration(playerCountriesFile);

        serverStatsFile = new File(plugin.getDataFolder(), "server-stats.yml");
        serverStats = YamlConfiguration.loadConfiguration(serverStatsFile);

        plugin.debug("All configuration files loaded");
    }

    /**
     * Save a default resource if it doesn't exist
     */
    private void saveDefaultResource(@NotNull String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
        }
    }

    /**
     * Set defaults from bundled resource
     */
    private void setDefaults(@NotNull FileConfiguration config, @NotNull String resourceName) {
        InputStream defaultStream = plugin.getResource(resourceName);
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }
    }

    /**
     * Save player status data
     */
    public void savePlayerStatus() {
        try {
            playerStatus.save(playerStatusFile);
        } catch (IOException e) {
            plugin.log(Level.SEVERE, "Failed to save player-status.yml: " + e.getMessage());
        }
    }

    /**
     * Save player deaths data
     */
    public void savePlayerDeaths() {
        try {
            playerDeaths.save(playerDeathsFile);
        } catch (IOException e) {
            plugin.log(Level.SEVERE, "Failed to save player-deaths.yml: " + e.getMessage());
        }
    }

    /**
     * Save player countries data
     */
    public void savePlayerCountries() {
        try {
            playerCountries.save(playerCountriesFile);
        } catch (IOException e) {
            plugin.log(Level.SEVERE, "Failed to save player-countries.yml: " + e.getMessage());
        }
    }

    /**
     * Save server stats data
     */
    public void saveServerStats() {
        try {
            serverStats.save(serverStatsFile);
        } catch (IOException e) {
            plugin.log(Level.SEVERE, "Failed to save server-stats.yml: " + e.getMessage());
        }
    }

    // Getters for configuration files
    @NotNull
    public FileConfiguration getConfig() {
        return config;
    }

    @NotNull
    public FileConfiguration getStatusOptions() {
        return statusOptions;
    }

    @NotNull
    public FileConfiguration getTablist() {
        return tablist;
    }

    @NotNull
    public FileConfiguration getLanguage() {
        return language;
    }

    @NotNull
    public FileConfiguration getPlayerStatus() {
        return playerStatus;
    }

    @NotNull
    public FileConfiguration getPlayerDeaths() {
        return playerDeaths;
    }

    @NotNull
    public FileConfiguration getPlayerCountries() {
        return playerCountries;
    }

    @NotNull
    public FileConfiguration getServerStats() {
        return serverStats;
    }

    /**
     * Get a message from language.yml with prefix
     */
    @NotNull
    public String getMessage(@NotNull String key) {
        String prefix = language.getString("prefix", "");
        String message = language.getString("messages." + key, "<red>Missing message: " + key);
        return message.replace("<prefix>", prefix);
    }

    /**
     * Get a raw message from language.yml without prefix processing
     */
    @NotNull
    public String getRawMessage(@NotNull String key) {
        return language.getString(key, "<red>Missing message: " + key);
    }
}
