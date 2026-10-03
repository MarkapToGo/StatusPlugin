package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.util.ColorUtil;
import de.stylelabor.statusplugin.util.PlaceholderUtil;
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
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Manages tab list formatting including header, footer, player list names, and
 * sorting.
 */
public final class TabListManager {

    private static final DecimalFormat TPS_FORMAT = new DecimalFormat("#0.00");
    private static final DecimalFormat MSPT_FORMAT = new DecimalFormat("#0.0");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SORT_TEAM_PREFIX = "sp_sort_";
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]");

    // Tags that render differently for each player. Header/footer lines without them
    // (and without PAPI placeholders) are parsed once per refresh and shared.
    private static final List<String> PLAYER_TAGS = List.of("<status", "<player", "<deaths", "<country");

    private final StatusPlugin plugin;
    private final ConfigManager configManager;
    private final StatusManager statusManager;
    private final DeathTracker deathTracker;
    private final CountryManager countryManager;
    private final MiniMessage miniMessage;
    private Scoreboard scoreboard;

    private BukkitTask updateTask;
    private final AtomicInteger rotatingIndex = new AtomicInteger(0);

    private boolean enabled;
    private String playerFormat;
    private int refreshInterval;
    private boolean sortingEnabled;

    private boolean nameColorsEnabled;
    private final Map<String, Component> nameColors = new HashMap<>();

    // Header/footer/rotating lines, converted from legacy colors once on load
    private final List<TemplateLine> headerLines = new ArrayList<>();
    private final List<TemplateLine> footerLines = new ArrayList<>();
    private final List<TemplateLine> rotatingMessages = new ArrayList<>();
    private boolean rotatingEnabled;

    // Sort team of each player, so a status change only touches the old and new team
    private final Map<UUID, String> playerTeams = new HashMap<>();
    // Parsed nametag prefix per status format
    private final Map<String, Component> teamPrefixes = new HashMap<>();

    private boolean headerFooterRefreshQueued;

    private record TemplateLine(@NotNull String text, boolean perPlayer, boolean usesRotating) {
    }

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
        enabled = config.getBoolean("tablist.enabled", true);

        String rawFormat = config.getString("tablist.player-format", "<status> <gray><player></gray>");
        playerFormat = ColorUtil.convertLegacyToMiniMessage(rawFormat);

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
                            miniMessage.deserialize(ColorUtil.convertLegacyToMiniMessage(color)));
                }
            }
        }

        var tablistConfig = configManager.getTablist();
        loadLines(headerLines, tablistConfig.getStringList("header.lines"));
        loadLines(footerLines, tablistConfig.getStringList("footer.lines"));
        loadLines(rotatingMessages, tablistConfig.getStringList("rotating.messages"));
        rotatingEnabled = tablistConfig.getBoolean("rotating.enabled", true);

        teamPrefixes.clear();
    }

    private static void loadLines(@NotNull List<TemplateLine> target, @NotNull List<String> rawLines) {
        target.clear();
        for (String raw : rawLines) {
            String text = ColorUtil.convertLegacyToMiniMessage(raw);
            String lower = text.toLowerCase(Locale.ROOT);
            boolean perPlayer = text.indexOf('%') != -1 || PLAYER_TAGS.stream().anyMatch(lower::contains);
            target.add(new TemplateLine(text, perPlayer, lower.contains("<rotating")));
        }
    }

    /**
     * Check if tab list formatting is enabled
     */
    public final boolean isEnabled() {
        return enabled;
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
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::updateAllPlayers);
            return;
        }

        if (!isEnabled())
            return;

        RefreshContext context = new RefreshContext();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player, context);
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

        updatePlayer(player, new RefreshContext());
    }

    private void updatePlayer(@NotNull Player player, @NotNull RefreshContext context) {
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
        updateHeaderFooter(player, context);
    }

    /**
     * Refresh the header and footer of all players on the next tick, e.g. after a
     * join or quit changed the online count. Several requests in the same tick
     * share one refresh.
     */
    public void requestHeaderFooterRefresh() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::requestHeaderFooterRefresh);
            return;
        }

        if (!isEnabled() || headerFooterRefreshQueued)
            return;

        headerFooterRefreshQueued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            headerFooterRefreshQueued = false;
            var tabIntegration = plugin.getTabPluginIntegration();
            if (tabIntegration != null && tabIntegration.isAvailable())
                return;

            RefreshContext context = new RefreshContext();
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateHeaderFooter(player, context);
            }
        });
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
        String sortKey = NON_ALPHANUMERIC.matcher(safeStatus).replaceAll("").toUpperCase();

        // Team name includes priority for sorting (000-999), sortKey, and player name
        String playerName = player.getName();
        String teamName = SORT_TEAM_PREFIX + String.format("%03d", priority) + "_" +
                (sortKey.isEmpty() ? "" : sortKey + "_") +
                playerName.substring(0, Math.min(playerName.length(), 8));

        // Leave the previous team if the status (and therefore the team) changed
        String previousTeam = playerTeams.put(player.getUniqueId(), teamName);
        if (previousTeam != null && !previousTeam.equals(teamName)) {
            leaveTeam(playerName, previousTeam);
        }

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            plugin.debug("Created team " + teamName + " for " + playerName);
        }

        // Set team prefix (status) if nametags are enabled
        Component prefix = Component.empty();
        if (nametagEnabled && status != null && !status.isEmpty()) {
            String statusFormat = statusManager.getStatusFormatByKey(status);
            if (!statusFormat.isEmpty()) {
                prefix = teamPrefixes.computeIfAbsent(statusFormat, format -> plugin.parseMessage(format + " "));
            }
        }
        // Every prefix change is broadcast to all players, so only send real changes
        if (!prefix.equals(team.prefix())) {
            team.prefix(prefix);
        }

        // Add player to team
        if (!team.hasEntry(playerName)) {
            team.addEntry(playerName);
        }
    }

    /**
     * Remove a player from their sorting team
     */
    private void removeFromSortTeams(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> removeFromSortTeams(player));
            return;
        }

        String teamName = playerTeams.remove(player.getUniqueId());
        if (teamName != null) {
            leaveTeam(player.getName(), teamName);
        }
    }

    /**
     * Remove an entry from a sort team and unregister the team once it is empty
     */
    private void leaveTeam(@NotNull String playerName, @NotNull String teamName) {
        Team team = scoreboard.getTeam(teamName);
        if (team == null)
            return;

        if (team.hasEntry(playerName)) {
            team.removeEntry(playerName);
        }
        // Clean up empty teams
        if (team.getSize() == 0) {
            team.unregister();
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
        TagResolver.Builder resolvers = playerResolvers(player);

        Component playerName = Component.text(player.getName());
        if (nameColorsEnabled) {
            String rawStatus = statusManager.getStatus(player);
            if (rawStatus != null) {
                Component color = nameColors.get(rawStatus.toUpperCase());
                if (color != null) {
                    playerName = color.append(playerName);
                }
            }
        }
        resolvers.resolver(Placeholder.component("player", playerName));

        String formatWithPapi = PlaceholderUtil.parse(player, playerFormat);
        formatWithPapi = ColorUtil.convertLegacyToMiniMessage(formatWithPapi);

        Component listName = miniMessage.deserialize(formatWithPapi, resolvers.build());

        if (Bukkit.isPrimaryThread()) {
            applyListName(player, listName);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> applyListName(player, listName));
        }
    }

    /**
     * Set the list name unless the player already has it; every change is
     * broadcast to all players.
     */
    private void applyListName(@NotNull Player player, @NotNull Component listName) {
        if (player.isOnline() && !listName.equals(player.playerListName())) {
            player.playerListName(listName);
        }
    }

    /**
     * Update header and footer for a player
     */
    private void updateHeaderFooter(@NotNull Player player, @NotNull RefreshContext context) {
        Component header = buildMultilineComponent(headerLines, player, context);
        Component footer = buildMultilineComponent(footerLines, player, context);

        if (player.isOnline()
                && !(header.equals(player.playerListHeader()) && footer.equals(player.playerListFooter()))) {
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    /**
     * Build a multi-line component from a list of lines
     */
    @NotNull
    private Component buildMultilineComponent(@NotNull List<TemplateLine> lines, @NotNull Player player,
            @NotNull RefreshContext context) {
        if (lines.isEmpty()) {
            return Component.empty();
        }

        Component result = context.line(lines.get(0), player);
        for (int i = 1; i < lines.size(); i++) {
            result = result.appendNewline().append(context.line(lines.get(i), player));
        }
        return result;
    }

    /**
     * Placeholders that depend on the player and are shared by the list name and
     * header/footer (<player> differs between the two and is added by the caller).
     */
    @NotNull
    private TagResolver.Builder playerResolvers(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        int deaths = deathTracker.getDeaths(uuid);
        return TagResolver.builder()
                .resolver(Placeholder.component("status", statusManager.getStatusDisplay(uuid)))
                .resolver(Placeholder.unparsed("deaths", String.valueOf(deaths)))
                // Formatted deaths: [☠ N]
                .resolver(Placeholder.component("deaths_formatted", deathTracker.formatDeaths(deaths)))
                .resolver(Placeholder.unparsed("country", countryManager.getCountry(uuid).orElse("")))
                .resolver(Placeholder.unparsed("countrycode", countryManager.getCountryCode(uuid).orElse("")));
    }

    /**
     * Values shared by every player during one refresh. Server placeholders are
     * computed once instead of once per line and player, and lines without
     * player-specific placeholders are parsed once.
     */
    private final class RefreshContext {

        private final TagResolver serverResolvers;
        private final @Nullable TemplateLine rotatingLine;
        private final Map<TemplateLine, Component> sharedLines = new IdentityHashMap<>();
        private @Nullable Component sharedRotating;

        // Per-player values, reused for all lines of the player currently being built
        private @Nullable Player currentPlayer;
        private @Nullable TagResolver currentResolvers;

        RefreshContext() {
            int online = 0;
            int overworld = 0;
            int nether = 0;
            int end = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isVanished(player))
                    continue;
                online++;
                World.Environment environment = player.getWorld().getEnvironment();
                if (environment == World.Environment.NORMAL) {
                    overworld++;
                } else if (environment == World.Environment.NETHER) {
                    nether++;
                } else if (environment == World.Environment.THE_END) {
                    end++;
                }
            }

            double[] tps = Bukkit.getTPS();
            serverResolvers = TagResolver.resolver(
                    Placeholder.unparsed("online", String.valueOf(online)),
                    Placeholder.unparsed("max", String.valueOf(Bukkit.getMaxPlayers())),
                    // TPS and performance
                    Placeholder.unparsed("tps", TPS_FORMAT.format(tps[0])),
                    Placeholder.unparsed("tps_5m", TPS_FORMAT.format(tps[1])),
                    Placeholder.unparsed("tps_15m", TPS_FORMAT.format(tps[2])),
                    Placeholder.component("performance", getPerformanceIndicator(tps[0])),
                    Placeholder.unparsed("mspt", MSPT_FORMAT.format(Bukkit.getAverageTickTime())),
                    Placeholder.unparsed("time", LocalTime.now().format(TIME_FORMAT)),
                    // World player counts
                    Placeholder.unparsed("overworld", String.valueOf(overworld)),
                    Placeholder.unparsed("nether", String.valueOf(nether)),
                    Placeholder.unparsed("end", String.valueOf(end)),
                    Placeholder.unparsed("total_deaths", formatNumber(deathTracker.getTotalDeaths())));

            if (rotatingEnabled && !rotatingMessages.isEmpty()) {
                int index = (rotatingIndex.get() & Integer.MAX_VALUE) % rotatingMessages.size();
                rotatingLine = rotatingMessages.get(index);
            } else {
                rotatingLine = null;
            }
        }

        /**
         * Parse a header/footer line for a player
         */
        @NotNull
        Component line(@NotNull TemplateLine line, @NotNull Player player) {
            boolean perPlayer = line.perPlayer()
                    || (line.usesRotating() && rotatingLine != null && rotatingLine.perPlayer());
            Component rotating = line.usesRotating() ? rotating(player) : Component.empty();
            if (!perPlayer) {
                Component shared = sharedLines.get(line);
                if (shared == null) {
                    shared = parse(line.text(), player,
                            TagResolver.resolver(serverResolvers, Placeholder.component("rotating", rotating)));
                    sharedLines.put(line, shared);
                }
                return shared;
            }
            return parse(line.text(), player,
                    TagResolver.resolver(resolversFor(player), Placeholder.component("rotating", rotating)));
        }

        /**
         * The current rotating message; placeholders inside it are resolved, but a
         * nested <rotating> is not
         */
        @NotNull
        private Component rotating(@NotNull Player player) {
            if (rotatingLine == null) {
                return Component.empty();
            }
            if (!rotatingLine.perPlayer()) {
                if (sharedRotating == null) {
                    sharedRotating = parse(rotatingLine.text(), player,
                            TagResolver.resolver(serverResolvers, Placeholder.component("rotating", Component.empty())));
                }
                return sharedRotating;
            }
            return parse(rotatingLine.text(), player,
                    TagResolver.resolver(resolversFor(player), Placeholder.component("rotating", Component.empty())));
        }

        @NotNull
        private TagResolver resolversFor(@NotNull Player player) {
            if (currentPlayer != player || currentResolvers == null) {
                currentPlayer = player;
                currentResolvers = playerResolvers(player)
                        .resolver(Placeholder.unparsed("player", player.getName()))
                        .resolver(serverResolvers)
                        .build();
            }
            return currentResolvers;
        }

        @NotNull
        private Component parse(@NotNull String text, @NotNull Player player, @NotNull TagResolver resolvers) {
            // Parse PAPI placeholders first
            String textWithPapi = PlaceholderUtil.parse(player, text);
            // Convert any legacy colors key PAPI might have returned
            textWithPapi = ColorUtil.convertLegacyToMiniMessage(textWithPapi);
            return miniMessage.deserialize(textWithPapi, resolvers);
        }
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
        playerTeams.clear();
    }

    /**
     * Check if sorting is enabled
     */
    public final boolean isSortingEnabled() {
        return sortingEnabled;
    }
}
