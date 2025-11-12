package de.stylelabor.statusplugin.nametag;

import de.stylelabor.statusplugin.ColorParser;
import de.stylelabor.statusplugin.StatusPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

public class StatusNametagManager {

    private static final int SCOREBOARD_TEXT_LIMIT = 256;

    private final StatusPlugin plugin;
    private final Map<String, Team> statusTeams = new HashMap<>();

    private Scoreboard sortingScoreboard;
    private boolean nametagEnabled;
    private boolean sortEnabled;

    public StatusNametagManager(StatusPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateSettings(boolean nametagEnabled, boolean sortEnabled) {
        this.nametagEnabled = nametagEnabled;
        this.sortEnabled = sortEnabled;

        if (isActive()) {
            ensureScoreboardInitialized(true);
        } else {
            clearStatusTeams();
            statusTeams.clear();
            sortingScoreboard = null;
        }
    }

    public boolean isActive() {
        return nametagEnabled || sortEnabled;
    }

    public boolean isNametagEnabled() {
        return nametagEnabled;
    }

    public void scheduleInitialization() {
        if (!isActive()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (ensureScoreboardInitialized(false)) {
                updateAllPlayerTeams();
            }
        });
    }

    public void assignPlayer(Player player) {
        if (!isActive() || player == null) {
            return;
        }

        if (!ensureScoreboardInitialized(false)) {
            return;
        }

        try {
            // Remove from old team if necessary
            Team currentTeam = sortingScoreboard.getEntryTeam(player.getName());
            if (currentTeam != null) {
                currentTeam.removeEntry(player.getName());
            }

            String statusValue = plugin.getPlayerStatus(player.getUniqueId());
            String statusKey = plugin.getStatusKeyFromValue(statusValue);
            Team team = getOrCreateStatusTeam(statusKey);
            if (team == null) {
                return;
            }

            team.addEntry(player.getName());
            if (nametagEnabled) {
                String prefix = buildTeamPrefix(statusValue);
                applyTeamFormatting(team, prefix, statusValue);
            } else {
                team.setPrefix("");
                team.setSuffix("");
            }

            if (player.getScoreboard() != sortingScoreboard) {
                player.setScoreboard(sortingScoreboard);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to assign player " + player.getName() + " to status team: " + e.getMessage());
        }
    }

    public void updateAllPlayerTeams() {
        if (!isActive()) {
            return;
        }
        if (!ensureScoreboardInitialized(false)) {
            return;
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            assignPlayer(online);
        }
    }

    public void tearDown() {
        clearStatusTeams();
        statusTeams.clear();
        sortingScoreboard = null;
    }

    private boolean ensureScoreboardInitialized(boolean logNotReady) {
        if (!isActive()) {
            return false;
        }

        if (sortingScoreboard != null) {
            return true;
        }

        if (Bukkit.getScoreboardManager() == null) {
            if (logNotReady) {
                plugin.getLogger().warning("ScoreboardManager is not available yet. Status sorting will be disabled temporarily.");
            }
            return false;
        }

        sortingScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        statusTeams.clear();
        return true;
    }

    private Team getOrCreateStatusTeam(String statusKey) {
        if (sortingScoreboard == null) {
            return null;
        }

        String teamName = getTeamNameForStatus(statusKey);
        Team team = statusTeams.get(teamName);
        if (team != null) {
            return team;
        }

        team = sortingScoreboard.getTeam(teamName);
        if (team == null) {
            try {
                team = sortingScoreboard.registerNewTeam(teamName);
            } catch (IllegalArgumentException ignored) {
                team = sortingScoreboard.getTeam(teamName);
            }
        }

        if (team != null) {
            statusTeams.put(teamName, team);
        }

        return team;
    }

    private String getTeamNameForStatus(String statusKey) {
        if (statusKey == null || statusKey.isEmpty()) {
            return "z_nostatus";
        }

        String upperKey = statusKey.toUpperCase(Locale.ROOT);
        switch (upperKey) {
            case "ADMIN":
                return "0_admin";
            case "MOD":
                return "1_mod";
            case "AFK":
                return "9_afk";
            case "CAM":
                return "9_cam";
            default:
                return "2_" + statusKey.toLowerCase(Locale.ROOT);
        }
    }

    private String buildTeamPrefix(String status) {
        if (status == null || status.isEmpty()) {
            return "";
        }

        String colored = ColorParser.parse(status);
        if (colored == null || colored.isEmpty()) {
            return "";
        }

        String withSpace = colored + ChatColor.RESET + " ";
        return withSpace;
    }

    private void applyTeamFormatting(Team team, String prefix, String originalStatus) {
        String safePrefix = prefix == null ? "" : prefix;
        String decorated = safePrefix;
        NametagParts parts = splitForScoreboard(decorated);
        try {
            team.setPrefix(parts.prefix());
        } catch (IllegalArgumentException ex) {
            String fallback = ColorParser.parse(originalStatus == null ? "" : originalStatus);
            fallback = ChatColor.stripColor(fallback == null ? "" : fallback);
            if (fallback == null) {
                fallback = "";
            }
            fallback = trimPlainText(fallback + " ", 64);
            team.setPrefix(fallback);
            team.setSuffix(ChatColor.RESET.toString());
            return;
        }
        try {
            team.setSuffix(parts.suffix());
        } catch (IllegalArgumentException ex) {
            team.setSuffix(ChatColor.RESET.toString());
        }
    }

    private String trimPlainText(String value, int limit) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private NametagParts splitForScoreboard(String text) {
        if (text == null) {
            return new NametagParts("", ChatColor.RESET.toString());
        }

        if (text.length() <= SCOREBOARD_TEXT_LIMIT) {
            return new NametagParts(text, ChatColor.RESET.toString());
        }

        StringBuilder prefixBuilder = new StringBuilder(SCOREBOARD_TEXT_LIMIT);
        int index = 0;

        while (index < text.length() && prefixBuilder.length() < SCOREBOARD_TEXT_LIMIT) {
            char current = text.charAt(index++);
            prefixBuilder.append(current);
            if (current == ChatColor.COLOR_CHAR && index < text.length()) {
                char next = text.charAt(index++);
                prefixBuilder.append(next);
            }
        }

        // Avoid ending with incomplete color code
        if (prefixBuilder.length() > 0 && prefixBuilder.charAt(prefixBuilder.length() - 1) == ChatColor.COLOR_CHAR) {
            prefixBuilder.deleteCharAt(prefixBuilder.length() - 1);
            index--;
        }

        String prefix = prefixBuilder.toString();
        String remaining = index < text.length() ? text.substring(index) : "";

        String activeColors = ChatColor.getLastColors(prefix);
        String suffix = (activeColors == null ? "" : activeColors) + remaining + ChatColor.RESET;
        return new NametagParts(prefix, suffix);
    }

    private static final class NametagParts {
        private final String prefix;
        private final String suffix;

        private NametagParts(String prefix, String suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
        }

        private String prefix() {
            return prefix;
        }

        private String suffix() {
            return suffix;
        }
    }

    private void clearStatusTeams() {
        if (sortingScoreboard == null || statusTeams.isEmpty()) {
            return;
        }

        for (Team team : new HashSet<>(statusTeams.values())) {
            if (team == null) {
                continue;
            }
            for (String entry : new HashSet<>(team.getEntries())) {
                team.removeEntry(entry);
            }
            team.setPrefix("");
            team.setSuffix("");
        }
    }
}

