package de.stylelabor.statusplugin.integration;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.manager.StatusManager;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.tablist.HeaderFooterManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;

/**
 * Integration with the TAB plugin.
 * When TAB is present, we use its API instead of our built-in tab list
 * management.
 */
public class TabPluginIntegration {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final StatusPlugin plugin;
    private final StatusManager statusManager;
    private final ConfigManager configManager;

    @Nullable
    private TabAPI tabApi;
    @Nullable
    private TabListFormatManager tabListFormatManager;
    @Nullable
    private HeaderFooterManager headerFooterManager;

    public TabPluginIntegration(@NotNull StatusPlugin plugin,
            @NotNull StatusManager statusManager,
            @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.statusManager = statusManager;
        this.configManager = configManager;

        try {
            this.tabApi = TabAPI.getInstance();
            this.tabListFormatManager = tabApi.getTabListFormatManager();
            this.headerFooterManager = tabApi.getHeaderFooterManager();
            plugin.debug("TAB API initialized successfully");
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Failed to initialize TAB API: " + e.getMessage());
            this.tabApi = null;
        }
    }

    /**
     * Check if TAB integration is available
     */
    public boolean isAvailable() {
        return tabApi != null;
    }

    /**
     * Update a player's tab list name through TAB API
     */
    public void updatePlayerTabName(@NotNull Player player) {
        if (tabListFormatManager == null || tabApi == null)
            return;

        TabPlayer tabPlayer = tabApi.getPlayer(player.getUniqueId());
        if (tabPlayer == null)
            return;

        String statusFormat = statusManager.getStatusFormat(player.getUniqueId());
        String playerFormat = configManager.getConfig().getString("tablist.player-format",
                "<status> <gray><player></gray>");

        // Replace placeholders
        String formatted = playerFormat
                .replace("<status>", statusFormat)
                .replace("<player>", player.getName());

        // Convert MiniMessage to legacy for TAB API compatibility
        Component component = plugin.parseMessage(formatted);
        String legacyText = LEGACY_SERIALIZER.serialize(component);

        try {
            // TAB API uses legacy formatting
            tabListFormatManager.setPrefix(tabPlayer, legacyText);
            plugin.debug("Updated TAB prefix for " + player.getName());
        } catch (Exception e) {
            plugin.debug("Failed to set TAB prefix for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Set custom header and footer through TAB API
     */
    public void setHeaderFooter(@NotNull Player player, @NotNull String header, @NotNull String footer) {
        if (headerFooterManager == null || tabApi == null)
            return;

        TabPlayer tabPlayer = tabApi.getPlayer(player.getUniqueId());
        if (tabPlayer == null)
            return;

        // Convert MiniMessage to legacy
        Component headerComponent = plugin.parseMessage(header);
        Component footerComponent = plugin.parseMessage(footer);
        String legacyHeader = LEGACY_SERIALIZER.serialize(headerComponent);
        String legacyFooter = LEGACY_SERIALIZER.serialize(footerComponent);

        try {
            headerFooterManager.setHeader(tabPlayer, legacyHeader);
            headerFooterManager.setFooter(tabPlayer, legacyFooter);
            plugin.debug("Updated TAB header/footer for " + player.getName());
        } catch (Exception e) {
            plugin.debug("Failed to set TAB header/footer for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Reset TAB customizations for a player
     */
    public void resetPlayer(@NotNull Player player) {
        if (tabApi == null)
            return;

        TabPlayer tabPlayer = tabApi.getPlayer(player.getUniqueId());
        if (tabPlayer == null)
            return;

        try {
            if (tabListFormatManager != null) {
                tabListFormatManager.setPrefix(tabPlayer, null);
                tabListFormatManager.setSuffix(tabPlayer, null);
            }
            if (headerFooterManager != null) {
                headerFooterManager.setHeader(tabPlayer, null);
                headerFooterManager.setFooter(tabPlayer, null);
            }
            plugin.debug("Reset TAB customizations for " + player.getName());
        } catch (Exception e) {
            plugin.debug("Failed to reset TAB customizations for " + player.getName() + ": " + e.getMessage());
        }
    }
}
