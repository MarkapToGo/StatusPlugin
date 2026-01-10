package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Manages nametags above player heads using scoreboard teams.
 */
public class NametagManager {

    private static final String TEAM_PREFIX = "sp_";
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final StatusPlugin plugin;
    private final ConfigManager configManager;
    private final StatusManager statusManager;
    private final Scoreboard scoreboard;

    private boolean enabled;
    private boolean cleanJoinMessages;
    private boolean cleanDeathMessages;

    // Track teams we've created
    private final Set<String> createdTeams = new HashSet<>();

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
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if clean join messages is enabled
     */
    public boolean isCleanJoinMessages() {
        return cleanJoinMessages;
    }

    /**
     * Check if clean death messages is enabled
     */
    public boolean isCleanDeathMessages() {
        return cleanDeathMessages;
    }

    /**
     * Update a player's nametag
     */
    public void updatePlayer(@NotNull Player player) {
        if (!enabled)
            return;

        String status = statusManager.getStatus(player);
        int priority = statusManager.getStatusPriority(status != null ? status : "");

        // Team name includes priority for sorting (00-99) and player UUID for
        // uniqueness
        String teamName = TEAM_PREFIX + String.format("%02d", priority) + "_" +
                player.getName().substring(0, Math.min(player.getName().length(), 8));

        // Remove player from any existing teams we created
        removeFromTeams(player);

        // Get or create team
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            createdTeams.add(teamName);
        }

        // Set team prefix (status)
        if (status != null) {
            String statusFormat = statusManager.getStatusFormatByKey(status);
            if (!statusFormat.isEmpty()) {
                Component prefix = plugin.parseMessage(statusFormat + " ");
                team.prefix(prefix);
            }
        } else {
            team.prefix(Component.empty());
        }

        // Add player to team
        team.addPlayer(player);

        plugin.debug("Updated nametag for " + player.getName() + " with team " + teamName);
    }

    /**
     * Remove a player from all plugin teams
     */
    public void removePlayer(@NotNull Player player) {
        removeFromTeams(player);
    }

    /**
     * Remove a player from all teams we've created
     */
    private void removeFromTeams(@NotNull Player player) {
        for (String teamName : new ArrayList<>(createdTeams)) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                if (team.hasPlayer(player)) {
                    team.removePlayer(player);
                }
                // Clean up empty teams
                if (team.getSize() == 0) {
                    team.unregister();
                    createdTeams.remove(teamName);
                }
            } else {
                createdTeams.remove(teamName);
            }
        }
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
            // Remove all players from teams if disabled
            for (Player player : Bukkit.getOnlinePlayers()) {
                removePlayer(player);
            }
        }
    }

    /**
     * Clean up all teams on shutdown
     */
    public void cleanup() {
        for (String teamName : new ArrayList<>(createdTeams)) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.unregister();
            }
        }
        createdTeams.clear();
    }
}
