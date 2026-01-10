package de.stylelabor.statusplugin;

import de.stylelabor.statusplugin.command.StatusAdminCommand;
import de.stylelabor.statusplugin.command.StatusClearCommand;
import de.stylelabor.statusplugin.command.StatusCommand;
import de.stylelabor.statusplugin.command.StatusPreviewCommand;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.integration.LibertyBansIntegration;
import de.stylelabor.statusplugin.integration.PlaceholderAPIExpansion;
import de.stylelabor.statusplugin.integration.TabPluginIntegration;
import de.stylelabor.statusplugin.listener.ChatListener;
import de.stylelabor.statusplugin.listener.PlayerListener;
import de.stylelabor.statusplugin.manager.*;
import de.stylelabor.statusplugin.util.VersionChecker;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;

/**
 * StatusPlugin v7 - Complete Recode
 * Modern Paper plugin for status prefixes, chat formatting, and tab list
 * management.
 */
public class StatusPlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 20901;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private ConfigManager configManager;
    private StatusManager statusManager;
    private ChatManager chatManager;
    private TabListManager tabListManager;
    private NametagManager nametagManager;
    private DeathTracker deathTracker;
    private CountryManager countryManager;

    // Integration instances
    private @Nullable PlaceholderAPIExpansion placeholderExpansion;
    private @Nullable TabPluginIntegration tabPluginIntegration;
    private @Nullable LibertyBansIntegration libertyBansIntegration;

    private boolean debug = false;

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public void onEnable() {
        // Initialize configuration
        configManager = new ConfigManager(this);
        configManager.loadAll();

        debug = configManager.getConfig().getBoolean("general.debug", false);

        // Initialize managers with dependency injection
        statusManager = new StatusManager(this, configManager);
        deathTracker = new DeathTracker(this, configManager);
        countryManager = new CountryManager(this, configManager);
        chatManager = new ChatManager(this, configManager, statusManager, deathTracker, countryManager);
        tabListManager = new TabListManager(this, configManager, statusManager, deathTracker, countryManager);
        nametagManager = new NametagManager(this, configManager, statusManager);

        // Register listeners
        registerListeners();

        // Register commands
        registerCommands();

        // Setup integrations
        setupIntegrations();

        // Initialize bStats metrics
        setupMetrics();

        // Check for updates
        checkForUpdates();

        log(Level.INFO, "StatusPlugin v" + getPluginMeta().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        // Save all data
        if (configManager != null) {
            statusManager.saveData();
            deathTracker.saveData();
            countryManager.saveData();
        }

        // Shutdown managers
        if (tabListManager != null) {
            tabListManager.shutdown();
        }

        // Unregister PlaceholderAPI expansion
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }

        log(Level.INFO, "StatusPlugin disabled!");
    }

    private void registerListeners() {
        var pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new ChatListener(this, chatManager, configManager), this);
        pluginManager.registerEvents(new PlayerListener(this, statusManager, tabListManager,
                nametagManager, deathTracker, countryManager, configManager), this);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void registerCommands() {
        LifecycleEventManager<Plugin> manager = this.getLifecycleManager();

        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            var statusCommand = new StatusCommand(this, statusManager, configManager);
            var statusClearCommand = new StatusClearCommand(this, statusManager, configManager);
            var statusPreviewCommand = new StatusPreviewCommand(this, statusManager, configManager);
            var statusAdminCommand = new StatusAdminCommand(this, statusManager, deathTracker, configManager);

            // Register /status command
            commands.register("status", "View or set your status", statusCommand);

            // Register /status-clear command
            commands.register("status-clear", "Clear your status", statusClearCommand);

            // Register /status-preview command
            commands.register("status-preview", "Preview how a status looks in chat", statusPreviewCommand);

            // Register /status-admin command
            commands.register("status-admin", "Admin commands for StatusPlugin", statusAdminCommand);
        });
    }

    private void setupIntegrations() {
        // PlaceholderAPI integration
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            if (configManager.getConfig().getBoolean("integrations.placeholderapi.enabled", true)) {
                placeholderExpansion = new PlaceholderAPIExpansion(this, statusManager, deathTracker, countryManager);
                placeholderExpansion.register();
                log(Level.INFO, "PlaceholderAPI integration enabled!");
            }
        }

        // TAB plugin integration
        if (Bukkit.getPluginManager().getPlugin("TAB") != null) {
            if (configManager.getConfig().getBoolean("integrations.tab-plugin.enabled", true)) {
                tabPluginIntegration = new TabPluginIntegration(this, statusManager, configManager);
                log(Level.INFO, "TAB plugin integration enabled!");
            }
        }

        // LibertyBans integration
        if (Bukkit.getPluginManager().getPlugin("LibertyBans") != null) {
            if (configManager.getConfig().getBoolean("integrations.libertybans.enabled", true)) {
                libertyBansIntegration = new LibertyBansIntegration(this, configManager);
                log(Level.INFO, "LibertyBans integration enabled!");
            }
        }
    }

    private void setupMetrics() {
        new Metrics(this, BSTATS_PLUGIN_ID);
        debug("bStats metrics initialized");
    }

    @SuppressWarnings("UnstableApiUsage")
    private void checkForUpdates() {
        if (!configManager.getConfig().getBoolean("updates.enabled", true)) {
            return;
        }

        VersionChecker.checkForUpdates(this, (latestVersion, downloadUrl) -> {
            String currentVersion = getPluginMeta().getVersion();
            if (!currentVersion.equals(latestVersion)) {
                log(Level.INFO, "A new version (" + latestVersion + ") is available on Modrinth!");
                log(Level.INFO, "Download: " + downloadUrl);
            }
        });
    }

    /**
     * Reload the plugin configuration
     */
    public void reload() {
        configManager.loadAll();
        statusManager.reload();
        deathTracker.reload();
        tabListManager.reload();
        nametagManager.reload();
        debug = configManager.getConfig().getBoolean("general.debug", false);
        log(Level.INFO, "Configuration reloaded!");
    }

    /**
     * Parse a MiniMessage string into a Component
     */
    @NotNull
    public Component parseMessage(@NotNull String message) {
        return de.stylelabor.statusplugin.util.ColorUtil.parse(message);
    }

    /**
     * Get the MiniMessage instance
     */
    @NotNull
    public MiniMessage getMiniMessage() {
        return MINI_MESSAGE;
    }

    /**
     * Log a message
     */
    public void log(@NotNull Level level, @NotNull String message) {
        getLogger().log(level, message);
    }

    /**
     * Log a debug message (only if debug mode is enabled)
     */
    public void debug(@NotNull String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    /**
     * Check if debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return debug;
    }

    // Getter methods for managers
    @NotNull
    public ConfigManager getConfigManager() {
        return configManager;
    }

    @NotNull
    public StatusManager getStatusManager() {
        return statusManager;
    }

    @NotNull
    public ChatManager getChatManager() {
        return chatManager;
    }

    @NotNull
    public TabListManager getTabListManager() {
        return tabListManager;
    }

    @NotNull
    public NametagManager getNametagManager() {
        return nametagManager;
    }

    @NotNull
    public DeathTracker getDeathTracker() {
        return deathTracker;
    }

    @NotNull
    public CountryManager getCountryManager() {
        return countryManager;
    }

    @Nullable
    public TabPluginIntegration getTabPluginIntegration() {
        return tabPluginIntegration;
    }

    @Nullable
    public LibertyBansIntegration getLibertyBansIntegration() {
        return libertyBansIntegration;
    }
}
