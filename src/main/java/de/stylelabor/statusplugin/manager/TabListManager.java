package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages tab list formatting including header, footer, player list names, and
 * sorting.
 */
public final class TabListManager {

    private static final DecimalFormat TPS_FORMAT = new DecimalFormat("#0.00");
    private static final DecimalFormat MSPT_FORMAT = new DecimalFormat("#0.0");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SORT_TEAM_PREFIX = "sp_sort_";

    private final StatusPlugin plugin;
    private final ConfigManager configManager;
    private final StatusManager statusManager;
    private final DeathTracker deathTracker;
    private final CountryManager countryManager;
    private final MiniMessage miniMessage;
    private Scoreboard scoreboard;

    private BukkitTask updateTask;
    private final AtomicInteger rotatingIndex = new AtomicInteger(0);

    private String playerFormat;
    private int refreshInterval;
    private boolean sortingEnabled;

    // Track teams we've created for sorting
    private final Set<String> createdSortTeams = new HashSet<>();

    private boolean nameColorsEnabled;
    private final Map<String, String> nameColors = new HashMap<>();

    public TabListManager(@NotNull StatusPlugin plugin,
            @NotNull ConfigManager configManager,
            @NotNull StatusManager statusManager,
            @NotNull DeathTracker deathTracker,
            @NotNull CountryManager countryManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.statusManager = statusManager;
        this.deathTracker = deathTracker;
        this.countryManager = countryManager;
        this.miniMessage = plugin.getMiniMessage();
        this.scoreboard = Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard();
        loadConfig();
    }

    /**
     * Load configuration
     */
    private void loadConfig() {
        var config = configManager.getConfig();
        String rawFormat = config.getString("tablist.player-format", "<status> <gray><player></gray>");
        playerFormat = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(rawFormat);

        refreshInterval = config.getInt("tablist.refresh-interval", 5);
        sortingEnabled = config.getBoolean("tablist.sorting.enabled", true);

        nameColorsEnabled = config.getBoolean("chat.name-colors.enabled", false);
        nameColors.clear();
        ConfigurationSection nameSection = config.getConfigurationSection("chat.name-colors.colors");
        if (nameSection != null) {
            for (String key : nameSection.getKeys(false)) {
                String color = nameSection.getString(key);
                if (color != null) {
                    nameColors.put(key.toUpperCase(),
                            de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(color));
                }
            }
        }
    }

    /**
     * Check if tab list formatting is enabled
     */
    public final boolean isEnabled() {
        return configManager.getConfig().getBoolean("tablist.enabled", true);
    }

    /**
     * Start the periodic update task
     */
    public void startUpdateTask() {
        if (!isEnabled())
            return;

        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateAllPlayers();
            rotatingIndex.incrementAndGet();
        }, 20L, refreshInterval * 20L);
    }

    /**
     * Update all online players' tab list
     */
    public void updateAllPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    /**
     * Update a specific player's tab list
     */
    public void updatePlayer(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> updatePlayer(player));
            return;
        }

        if (!isEnabled())
            return;

        var tabIntegration = plugin.getTabPluginIntegration();
        if (tabIntegration != null && tabIntegration.isAvailable()) {
            tabIntegration.updatePlayerTabName(player);
            removeFromSortTeams(player);
            return;
        }

        // Update player list name
        updatePlayerListName(player);

        // Update sorting & team if sorting is enabled or nametag is enabled
        boolean nametagEnabled = plugin.getNametagManager() != null && plugin.getNametagManager().isEnabled();
        if (sortingEnabled || nametagEnabled) {
            updatePlayerSorting(player);
        } else {
            removeFromSortTeams(player);
        }

        // Update header and footer
        updateHeaderFooter(player);
    }

    /**
     * Update player's tab list sorting and nametag using a single coordinated scoreboard team
     */
    public void updatePlayerSorting(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> updatePlayerSorting(player));
            return;
        }

        if (!player.isOnline())
            return;

        var tabIntegration = plugin.getTabPluginIntegration();
        if (tabIntegration != null && tabIntegration.isAvailable()) {
            removeFromSortTeams(player);
            return;
        }

        boolean nametagEnabled = plugin.getNametagManager() != null && plugin.getNametagManager().isEnabled();
        if (!sortingEnabled && !nametagEnabled) {
            removeFromSortTeams(player);
            return;
        }

        String status = statusManager.getStatus(player);
        String safeStatus = status != null ? status : "";
        int priority = statusManager.getStatusPriority(safeStatus);

        // Sanitize status to ensure robust alphabetical sorting
        String sortKey = safeStatus.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

        // Team name includes priority for sorting (000-999), sortKey, and player name
        String teamName = SORT_TEAM_PREFIX + String.format("%03d", priority) + "_" +
                (sortKey.isEmpty() ? "" : sortKey + "_") +
                player.getName().substring(0, Math.min(player.getName().length(), 8));

        // Remove player from any other sort teams
        for (String existingTeamName : new ArrayList<>(createdSortTeams)) {
            if (existingTeamName.equals(teamName)) continue;
            Team t = scoreboard.getTeam(existingTeamName);
            if (t != null) {
                if (t.hasEntry(player.getName())) {
                    t.removeEntry(player.getName());
                }
                if (t.getSize() == 0) {
                    t.unregister();
                    createdSortTeams.remove(existingTeamName);
                }
            } else {
                createdSortTeams.remove(existingTeamName);
            }
        }

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            createdSortTeams.add(teamName);
        }

        // Set team prefix (status) if nametags are enabled
        if (nametagEnabled && status != null && !status.isEmpty()) {
            String statusFormat = statusManager.getStatusFormatByKey(status);
            if (!statusFormat.isEmpty()) {
                team.prefix(plugin.parseMessage(statusFormat + " "));
            } else {
                team.prefix(Component.empty());
            }
        } else {
            team.prefix(Component.empty());
        }

        // Add player to team
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        plugin.debug("Updated team for " + player.getName() + " with team " + teamName);
    }

    /**
     * Remove a player from sorting teams
     */
    private void removeFromSortTeams(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> removeFromSortTeams(player));
            return;
        }

        for (String teamName : new ArrayList<>(createdSortTeams)) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                if (team.hasEntry(player.getName())) {
                    team.removeEntry(player.getName());
                }
                // Clean up empty teams
                if (team.getSize() == 0) {
                    team.unregister();
                    createdSortTeams.remove(teamName);
                }
            } else {
                createdSortTeams.remove(teamName);
            }
        }
    }

    /**
     * Remove a player from all sorting (called on quit)
     */
    public void removePlayer(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> removePlayer(player));
            return;
        }

        var tabIntegration = plugin.getTabPluginIntegration();
        if (tabIntegration != null && tabIntegration.isAvailable()) {
            tabIntegration.resetPlayer(player);
        }
        removeFromSortTeams(player);
    }

    /**
     * Update a player's list name
     */
    public void updatePlayerListName(@NotNull Player player) {
        String statusFormat = statusManager.getStatusFormat(player.getUniqueId());
        int deaths = deathTracker.getDeaths(player.getUniqueId());
        String country = countryManager.getCountry(player.getUniqueId()).orElse("");
        String countryCode = countryManager.getCountryCode(player.getUniqueId()).orElse("");

        TagResolver.Builder resolvers = TagResolver.builder();

        if (!statusFormat.isEmpty()) {
            resolvers.resolver(Placeholder.component("status", miniMessage.deserialize(statusFormat)));
        } else {
            resolvers.resolver(Placeholder.component("status", Component.empty()));
        }

        Component playerName = Component.text(player.getName());
        if (nameColorsEnabled) {
            String rawStatus = statusManager.getStatus(player);
            if (rawStatus != null) {
                String color = nameColors.get(rawStatus.toUpperCase());
                if (color != null) {
                    playerName = miniMessage.deserialize(color).append(playerName);
                }
            }
        }
        resolvers.resolver(Placeholder.component("player", playerName));
        resolvers.resolver(Placeholder.unparsed("deaths", String.valueOf(deaths)));
        // Formatted deaths: [☠ N]
        resolvers.resolver(Placeholder.component("deaths_formatted",
                miniMessage.deserialize(
                        "<dark_gray>[</dark_gray><red>☠</red> <red>" + deaths + "</red><dark_gray>]</dark_gray>")));
        resolvers.resolver(Placeholder.unparsed("country", country));
        resolvers.resolver(Placeholder.unparsed("countrycode", countryCode));

        String formatWithPapi = de.stylelabor.statusplugin.util.PlaceholderUtil.parse(player, playerFormat);
        formatWithPapi = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(formatWithPapi);

        Component listName = miniMessage.deserialize(formatWithPapi, resolvers.build());

        if (Bukkit.isPrimaryThread()) {
            if (player.isOnline()) {
                player.playerListName(listName);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.playerListName(listName);
                }
            });
        }
    }

    /**
     * Update header and footer for a player
     */
    private void updateHeaderFooter(@NotNull Player player) {
        var tablistConfig = configManager.getTablist();

        // Build header
        List<String> headerLines = tablistConfig.getStringList("header.lines");
        headerLines.replaceAll(de.stylelabor.statusplugin.util.ColorUtil::convertLegacyToMiniMessage);
        Component header = buildMultilineComponent(headerLines, player);

        // Rotating messages are now handled via <rotating> placeholder
        // which can be placed anywhere in header.lines or footer.lines

        // Build footer
        List<String> footerLines = tablistConfig.getStringList("footer.lines");
        footerLines.replaceAll(de.stylelabor.statusplugin.util.ColorUtil::convertLegacyToMiniMessage);
        Component footer = buildMultilineComponent(footerLines, player);

        // Apply header and footer
        Component finalHeader = header;
        Component finalFooter = footer;
        if (Bukkit.isPrimaryThread()) {
            if (player.isOnline()) {
                player.sendPlayerListHeaderAndFooter(finalHeader, finalFooter);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendPlayerListHeaderAndFooter(finalHeader, finalFooter);
                }
            });
        }
    }

    /**
     * Build a multi-line component from a list of lines
     */
    @NotNull
    private Component buildMultilineComponent(@NotNull List<String> lines, @NotNull Player player) {
        if (lines.isEmpty()) {
            return Component.empty();
        }

        Component result = parseWithPlaceholders(lines.get(0), player);
        for (int i = 1; i < lines.size(); i++) {
            result = result.appendNewline().append(parseWithPlaceholders(lines.get(i), player));
        }
        return result;
    }

    /**
     * Parse a string with all available placeholders
     */
    @NotNull
    private Component parseWithPlaceholders(@NotNull String text, @NotNull Player player) {
        return parseWithPlaceholders(text, player, false);
    }

    /**
     * Parse a string with all available placeholders
     * 
     * @param isNested set to true if parsing the rotating message itself to prevent
     *                 infinite recursion
     */
    @NotNull
    private Component parseWithPlaceholders(@NotNull String text, @NotNull Player player, boolean isNested) {
        // Parse PAPI placeholders first
        String textWithPapi = de.stylelabor.statusplugin.util.PlaceholderUtil.parse(player, text);
        // Convert any legacy colors key PAPI might have returned
        textWithPapi = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(textWithPapi);

        String statusFormat = statusManager.getStatusFormat(player.getUniqueId());
        int deaths = deathTracker.getDeaths(player.getUniqueId());
        String country = countryManager.getCountry(player.getUniqueId()).orElse("");
        String countryCode = countryManager.getCountryCode(player.getUniqueId()).orElse("");

        TagResolver.Builder resolvers = TagResolver.builder();

        // Player-specific placeholders
        if (!statusFormat.isEmpty()) {
            resolvers.resolver(Placeholder.component("status", miniMessage.deserialize(statusFormat)));
        } else {
            resolvers.resolver(Placeholder.component("status", Component.empty()));
        }
        resolvers.resolver(Placeholder.unparsed("player", player.getName()));
        resolvers.resolver(Placeholder.unparsed("deaths", String.valueOf(deaths)));
        // Formatted deaths: [☠ N]
        resolvers.resolver(Placeholder.component("deaths_formatted",
                miniMessage.deserialize(
                        "<dark_gray>[</dark_gray><red>☠</red> <red>" + deaths + "</red><dark_gray>]</dark_gray>")));
        resolvers.resolver(Placeholder.unparsed("country", country));
        resolvers.resolver(Placeholder.unparsed("countrycode", countryCode));

        // Rotating message placeholder
        // Only resolve if we are not already inside a rotating message
        if (!isNested) {
            if (configManager.getTablist().getBoolean("rotating.enabled", true)) {
                List<String> rotatingMessages = configManager.getTablist().getStringList("rotating.messages");
                if (!rotatingMessages.isEmpty()) {
                    int index = (rotatingIndex.get() & Integer.MAX_VALUE) % rotatingMessages.size();
                    String rotatingLine = rotatingMessages.get(index);
                    // Convert legacy color codes in the rotating message
                    rotatingLine = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(rotatingLine);
                    // Parse placeholders inside the rotating message itself
                    resolvers.resolver(
                            Placeholder.component("rotating", parseWithPlaceholders(rotatingLine, player, true)));
                } else {
                    resolvers.resolver(Placeholder.component("rotating", Component.empty()));
                }
            } else {
                resolvers.resolver(Placeholder.component("rotating", Component.empty()));
            }
        } else {
            // Prevent recursion
            resolvers.resolver(Placeholder.component("rotating", Component.empty()));
        }

        // Server placeholders
        long onlineCount = Bukkit.getOnlinePlayers().stream().filter(p -> !isVanished(p)).count();
        resolvers.resolver(Placeholder.unparsed("online", String.valueOf(onlineCount)));
        resolvers.resolver(Placeholder.unparsed("max", String.valueOf(Bukkit.getMaxPlayers())));

        // TPS and performance
        double[] tps = Bukkit.getTPS();
        resolvers.resolver(Placeholder.unparsed("tps", TPS_FORMAT.format(tps[0])));
        resolvers.resolver(Placeholder.unparsed("tps_5m", TPS_FORMAT.format(tps[1])));
        resolvers.resolver(Placeholder.unparsed("tps_15m", TPS_FORMAT.format(tps[2])));
        resolvers.resolver(Placeholder.component("performance", getPerformanceIndicator(tps[0])));

        // MSPT
        double mspt = Bukkit.getAverageTickTime();
        resolvers.resolver(Placeholder.unparsed("mspt", MSPT_FORMAT.format(mspt)));

        // Time
        resolvers.resolver(Placeholder.unparsed("time", LocalTime.now().format(TIME_FORMAT)));

        // World player counts
        resolvers.resolver(
                Placeholder.unparsed("overworld", String.valueOf(getPlayersInEnvironment(World.Environment.NORMAL))));
        resolvers.resolver(
                Placeholder.unparsed("nether", String.valueOf(getPlayersInEnvironment(World.Environment.NETHER))));
        resolvers.resolver(
                Placeholder.unparsed("end", String.valueOf(getPlayersInEnvironment(World.Environment.THE_END))));

        // Total deaths
        long totalDeaths = deathTracker.getTotalDeaths();
        resolvers.resolver(Placeholder.unparsed("total_deaths", formatNumber(totalDeaths)));

        return miniMessage.deserialize(textWithPapi, resolvers.build());
    }

    /**
     * Get a colored TPS indicator
     */
    @NotNull
    private Component getPerformanceIndicator(double tps) {
        String color;
        if (tps >= 19.5) {
            color = "<green>";
        } else if (tps >= 18.0) {
            color = "<yellow>";
        } else if (tps >= 15.0) {
            color = "<gold>";
        } else {
            color = "<red>";
        }
        return miniMessage.deserialize(color + TPS_FORMAT.format(Math.min(tps, 20.0)) + " TPS");
    }

    /**
     * Get the number of players in a specific world environment
     */
    private int getPlayersInEnvironment(@NotNull World.Environment environment) {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isVanished(player) && player.getWorld().getEnvironment() == environment) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if a player is vanished (SuperVanish/PremiumVanish support)
     */
    private boolean isVanished(@NotNull Player player) {
        return player.hasMetadata("vanished");
    }

    /**
     * Format a large number with K/M suffixes
     */
    @NotNull
    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    /**
     * Reload configuration and restart task
     */
    public void reload() {
        shutdown();
        loadConfig();
        startUpdateTask();
    }

    /**
     * Shutdown the manager
     */
    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }

        // Clean up all plugin teams from scoreboard
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("sp_") || team.getName().startsWith(SORT_TEAM_PREFIX)) {
                team.unregister();
            }
        }
        createdSortTeams.clear();
    }

    /**
     * Check if sorting is enabled
     */
    public final boolean isSortingEnabled() {
        return sortingEnabled;
    }
}
