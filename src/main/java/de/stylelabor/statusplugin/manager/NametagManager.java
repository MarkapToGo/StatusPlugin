package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Manages nametags above player heads using scoreboard teams.
 * Coordinated with TabListManager so a single scoreboard team per player
 * handles both sorting and nametag display without collision.
 */
public final class NametagManager {

    private final StatusPlugin plugin;
    private final ConfigManager configManager;
    private final StatusManager statusManager;
    private final Scoreboard scoreboard;

    private boolean enabled;
    private boolean cleanJoinMessages;
    private boolean cleanDeathMessages;

    public NametagManager(@NotNull StatusPlugin plugin,
            @NotNull ConfigManager configManager,
            @NotNull StatusManager statusManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.statusManager = statusManager;
        this.scoreboard = Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard();
        loadConfig();
    }

    /**
     * Load configuration
     */
    private void loadConfig() {
        var config = configManager.getConfig();
        enabled = config.getBoolean("nametag.enabled", false);
        cleanJoinMessages = config.getBoolean("nametag.clean-join-messages", true);
        cleanDeathMessages = config.getBoolean("nametag.clean-death-messages", true);
    }

    /**
     * Check if nametag system is enabled
     */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if clean join messages is enabled
     */
    public final boolean isCleanJoinMessages() {
        return cleanJoinMessages;
    }

    /**
     * Check if clean death messages is enabled
     */
    public final boolean isCleanDeathMessages() {
        return cleanDeathMessages;
    }

    /**
     * Update a player's nametag (delegated to unified scoreboard team handling in TabListManager)
     */
    public void updatePlayer(@NotNull Player player) {
        if (!enabled)
            return;

        plugin.getTabListManager().updatePlayerSorting(player);
    }

    /**
     * Remove a player from all plugin teams
     */
    public void removePlayer(@NotNull Player player) {
        plugin.getTabListManager().removePlayer(player);
    }

    /**
     * Update all online players
     */
    public void updateAllPlayers() {
        if (!enabled)
            return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    /**
     * Reload configuration
     */
    public void reload() {
        loadConfig();
        if (enabled) {
            updateAllPlayers();
        } else {
            // Remove nametag prefixes by refreshing teams
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.getTabListManager().updatePlayerSorting(player);
            }
        }
    }

    /**
     * Clean up all plugin teams on shutdown
     */
    public void cleanup() {
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("sp_") || team.getName().startsWith("sp_sort_")) {
                team.unregister();
            }
        }
    }

    /**
     * Clean status formatting from join or death message if enabled
     */
    @NotNull
    public Component cleanMessage(@NotNull Player player, @NotNull Component message) {
        String status = statusManager.getStatus(player);
        if (status == null || status.isEmpty()) {
            return message;
        }
        String statusFormat = statusManager.getStatusFormatByKey(status);
        String stripped = de.stylelabor.statusplugin.util.ColorUtil.stripFormatting(statusFormat).trim();
        if (stripped.isEmpty()) {
            return message;
        }
        return message.replaceText(TextReplacementConfig.builder()
                .match(Pattern.compile(Pattern.quote(stripped) + "\\s*"))
                .replacement("")
                .build());
    }
}
