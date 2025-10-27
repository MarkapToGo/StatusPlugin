package de.stylelabor.statusplugin;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.tablist.HeaderFooterManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;

public final class StatusPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final HashMap<UUID, String> playerStatusMap = new HashMap<>();
    private final HashMap<UUID, Integer> playerDeathMap = new HashMap<>();
    private String commandName;
    private String tabListFormat;
    private boolean customTabListEnabled;
    private List<String> tabListHeaderLines = Collections.emptyList();
    private List<String> tabListFooterLines = Collections.emptyList();
    private final HashMap<String, String> statusOptions = new HashMap<>();
    private FileConfiguration languageConfig;
    private FileConfiguration playerStatusConfig;
    private FileConfiguration playerDeathsConfig;
    private FileConfiguration serverStatsConfig;
    private boolean isTabPluginPresent;
    private boolean useOnlyOneLanguage;
    private String defaultLanguage;
    private boolean isDiscordSrvPresent;
    private CountryLocationManager countryLocationManager;
    private long totalBlocksPlaced;
    private long totalBlocksBroken;
    private long totalTrackedDeaths;
    private boolean serverStatsDirty;
    private int statsAutosaveCounterTicks;
    private BukkitTask fastTabRefreshTask;
    private BukkitTask slowTabRefreshTask;
    private int tabRefreshIntervalTicks;
    private int tabDimensionRefreshIntervalTicks;
    private int rotatingLineIntervalTicks;
    private int rotatingLineElapsedTicks;
    private int rotatingLineIndex;
    private List<String> tabListRotatingLines = Collections.emptyList();
    private int cachedOverworldPlayers;
    private int cachedNetherPlayers;
    private int cachedEndPlayers;
    private String cachedPerformanceLabel = ChatColor.GREEN + "" + ChatColor.UNDERLINE + "Smooth" + ChatColor.RESET;
    private static final ThreadLocal<Boolean> relayingToDiscord = ThreadLocal.withInitial(() -> false);
    private static final DateTimeFormatter TABLIST_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());
    private static final String PLACEHOLDER_NOT_AVAILABLE = "N/A";
    private static final int STATS_AUTOSAVE_INTERVAL_TICKS = 6000;
    private Scoreboard sortingScoreboard;
    private final HashMap<String, Team> statusTeams = new HashMap<>();

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig(); // Load player status during plugin startup
        loadPlayerDeathsConfig(); // Load player deaths during plugin startup
        loadServerStatsConfig(); // Load persistent server-wide stats
        loadPlayerStatuses(); // Load the statuses of all players from player-status.yml
        loadPlayerDeaths(); // Load the death counts of all players from player-deaths.yml
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand(commandName)).setTabCompleter(this);
        int pluginId = 20901;
        //noinspection unused
        new Metrics(this, pluginId);

        isTabPluginPresent = Bukkit.getPluginManager().getPlugin("TAB") != null;
        isDiscordSrvPresent = Bukkit.getPluginManager().getPlugin("DiscordSRV") != null;

        if (isDiscordSrvPresent) {
            getLogger().info("[StatusPlugin] DiscordSRV detected. Enabling Discord relay features.");
        }

        // Register PlaceholderAPI placeholder
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new StatusPlaceholder(this).register();
            getLogger().info("PlaceholderAPI placeholder registered successfully.");
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholder registration skipped.");
        }

        // Check for the latest version
        ModrinthVersionChecker.checkVersion();

        // Initialize country location manager only if the feature is enabled
        if (getConfig().getBoolean("country-location-enabled", false)) {
            countryLocationManager = new CountryLocationManager(this);
            getLogger().info("Country location feature enabled. IP geolocation will be active.");
        } else {
            getLogger().info("Country location feature disabled. To enable, set country-location-enabled to true in config.yml");
        }

        refreshDimensionCache();
        startTabRefreshSchedulers();
        
        // Initialize scoreboard for tab list sorting (delayed to ensure server is ready)
        if (getConfig().getBoolean("tab-list-sort-by-status", true)) {
            Bukkit.getScheduler().runTask(this, this::initializeSortingScoreboard);
        }

    }

    /**
     * Load player statuses from playerStatusConfig and populate playerStatusMap.
     */
    private void loadPlayerStatuses() {
        playerStatusMap.clear();
        for (String uuid : playerStatusConfig.getKeys(false)) {
            playerStatusMap.put(UUID.fromString(uuid), playerStatusConfig.getString(uuid));
        }
    }

    private void loadPlayerDeaths() {
        if (playerDeathsConfig == null) {
            return;
        }
        playerDeathMap.clear();
        long aggregatedDeaths = 0;
        for (String uuid : playerDeathsConfig.getKeys(false)) {
            int deaths = playerDeathsConfig.getInt(uuid, 0);
            aggregatedDeaths += deaths;
            playerDeathMap.put(UUID.fromString(uuid), deaths);
        }
        totalTrackedDeaths = aggregatedDeaths;
        if (serverStatsConfig != null) {
            serverStatsConfig.set("total-deaths", totalTrackedDeaths);
            serverStatsDirty = true;
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        stopTabRefreshSchedulers();
        savePlayerStatusConfig(); // Save player status during plugin shutdown
        savePlayerDeathsConfig(); // Save player deaths during plugin shutdown
        persistServerStats(true);
        if (countryLocationManager != null) {
            countryLocationManager.saveAllPlayerCountries(); // Save country data during plugin shutdown
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase(commandName) && sender instanceof Player) {
            Player player = (Player) sender;

            if (getConfig().getBoolean("only-admin-change", false) && !player.hasPermission("statusplugin.admin")) {
                String message = getConfig().getString("only-admin-change-message", "&cOnly admins can change statuses.");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                return true;
            }

            if (args.length > 0) {
                String statusKey = args[0].toUpperCase(Locale.ROOT);
                String status = statusOptions.get(statusKey);
                if (status != null) {
                    if ("ADMIN".equals(statusKey) && !player.isOp() && !player.hasPermission("statusplugin.admin")) {
                        player.sendMessage(ChatColor.RED + "Only admins can use the ADMIN status.");
                        return true;
                    }
                    if ("MOD".equals(statusKey) && !player.hasPermission("statusplugin.mod")) {
                        player.sendMessage(ChatColor.RED + "Only moderators can use the MOD status.");
                        return true;
                    }
                    playerStatusMap.put(player.getUniqueId(), status);
                    String message = getLanguageText(player, "status_set", "&aYour status has been set to: &r%s");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', String.format(message, status)));
                    
                    // Assign player to team for sorting
                    assignPlayerToTeam(player);
                    updatePlayerTabList();

                    // Save the player status to player-status.yml
                    playerStatusConfig.set(player.getUniqueId().toString(), status);
                    savePlayerStatusConfig();
                } else {
                    String message = getLanguageText(player, "invalid_status", "&cInvalid status option. Use /status <option>");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
            } else {
                playerStatusMap.remove(player.getUniqueId());
                String message = getLanguageText(player, "status_cleared", "&aYour status has been cleared.");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                
                // Assign player to team for sorting
                assignPlayerToTeam(player);
                updatePlayerTabList();

                // Clear the player status in player-status.yml
                playerStatusConfig.set(player.getUniqueId().toString(), null);
                savePlayerStatusConfig();
            }

            return true;
        } else if (command.getName().equalsIgnoreCase("reloadstatus") && (sender.isOp() || sender.hasPermission("statusplugin.reload"))) {
            reloadPlugin();
            sender.sendMessage(ChatColor.GREEN + "StatusPlugin configuration reloaded!");
            return true;
        } else if (command.getName().equalsIgnoreCase("status-clear") && sender instanceof Player) {
            Player player = (Player) sender;
            playerStatusMap.remove(player.getUniqueId());
            String message = getLanguageText(player, "status_cleared", "&aYour status has been cleared.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            
            // Assign player to team for sorting
            assignPlayerToTeam(player);
            updatePlayerTabList();

            // Clear the player status in player-status.yml
            playerStatusConfig.set(player.getUniqueId().toString(), null);
            savePlayerStatusConfig();

            return true;
        } else if (command.getName().equalsIgnoreCase("status-admin") && sender.hasPermission("statusplugin.admin")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.RED + "Usage: /status-admin <player> <status> | /status-admin deaths <player> [view|add|remove|minus|reset|set] [amount] | /status-admin reload");
                return true;
            }

            String subCommand = args[0].toLowerCase(Locale.ROOT);

            if (subCommand.equals("reload")) {
                reloadPlugin();
                sender.sendMessage(ChatColor.GREEN + "StatusPlugin configuration reloaded!");
                return true;
            }

            if (subCommand.equals("deaths")) {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /status-admin deaths <player> [view|add|remove|minus|reset|set] [amount]");
                    return true;
                }

                Player onlineTarget = Bukkit.getPlayerExact(args[1]);
                OfflinePlayer targetProfile = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(args[1]);
                if (targetProfile == null || (!targetProfile.hasPlayedBefore() && !targetProfile.isOnline())) {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }

                UUID targetUuid = targetProfile.getUniqueId();
                int currentDeaths = getPlayerDeaths(targetUuid);

                if (args.length == 2) {
                    String targetName = targetProfile.getName() != null ? targetProfile.getName() : args[1];
                    sender.sendMessage(ChatColor.YELLOW + targetName + ChatColor.GRAY + " has " + ChatColor.AQUA + currentDeaths + ChatColor.GRAY + " tracked deaths.");
                    return true;
                }

                String action = args[2].toLowerCase(Locale.ROOT);
                int newDeaths = currentDeaths;

                switch (action) {
                    case "reset":
                        newDeaths = 0;
                        break;
                    case "set":
                        if (args.length < 4) {
                            sender.sendMessage(ChatColor.RED + "Usage: /status-admin deaths <player> set <amount>");
                            return true;
                        }
                        try {
                            int amount = Integer.parseInt(args[3]);
                            if (amount < 0) {
                                sender.sendMessage(ChatColor.RED + "Amount must not be negative.");
                                return true;
                            }
                            newDeaths = amount;
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
                            return true;
                        }
                        break;
                    case "add":
                        if (args.length < 4) {
                            sender.sendMessage(ChatColor.RED + "Usage: /status-admin deaths <player> add <amount>");
                            return true;
                        }
                        try {
                            int amount = Integer.parseInt(args[3]);
                            if (amount < 0) {
                                sender.sendMessage(ChatColor.RED + "Amount must not be negative.");
                                return true;
                            }
                            newDeaths = currentDeaths + amount;
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
                            return true;
                        }
                        break;
                    case "remove":
                    case "minus":
                        if (args.length < 4) {
                            sender.sendMessage(ChatColor.RED + "Usage: /status-admin deaths <player> " + action + " <amount>");
                            return true;
                        }
                        try {
                            int amount = Integer.parseInt(args[3]);
                            if (amount < 0) {
                                sender.sendMessage(ChatColor.RED + "Amount must not be negative.");
                                return true;
                            }
                            newDeaths = Math.max(0, currentDeaths - amount);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
                            return true;
                        }
                        break;
                    default:
                        sender.sendMessage(ChatColor.RED + "Usage: /status-admin deaths <player> [view|add|remove|minus|reset|set] [amount]");
                        return true;
                }

                playerDeathMap.put(targetUuid, newDeaths);
                if (playerDeathsConfig != null) {
                    playerDeathsConfig.set(targetUuid.toString(), newDeaths);
                    savePlayerDeathsConfig();
                }

                Player refreshedOnlineTarget = Bukkit.getPlayer(targetUuid);
                if (refreshedOnlineTarget != null) {
                    if (getConfig().getBoolean("tab-styling-enabled", true)) {
                        updatePlayerTabListName(refreshedOnlineTarget, TabEnvironmentSnapshot.capture(this), "");
                    }
                    refreshedOnlineTarget.sendMessage(ChatColor.GOLD + "Your tracked deaths were updated to " + ChatColor.AQUA + newDeaths + ChatColor.GOLD + " by an administrator.");
                }

                String targetName = targetProfile.getName() != null ? targetProfile.getName() : args[1];
                sender.sendMessage(ChatColor.GREEN + "Set " + targetName + "'s tracked deaths to " + newDeaths + ".");
                return true;
            }

            int targetIndex = 0;
            int statusIndex = 1;

            if (subCommand.equals("set")) {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /status-admin set <player> <status>");
                    return true;
                }
                targetIndex = 1;
                statusIndex = 2;
            } else if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /status-admin <player> <status>");
                return true;
            }

            Player targetPlayer = Bukkit.getPlayer(args[targetIndex]);
            if (targetPlayer == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }

            String statusKey = args[statusIndex].toUpperCase(Locale.ROOT);
            String status = statusOptions.get(statusKey);
            if (status == null) {
                sender.sendMessage(ChatColor.RED + "Invalid status option. Use /status-admin " + (subCommand.equals("set") ? "set <player> <status>" : "<player> <status>"));
                return true;
            }
            if ("ADMIN".equals(statusKey) && !targetPlayer.isOp() && !targetPlayer.hasPermission("statusplugin.admin")) {
                sender.sendMessage(ChatColor.RED + "ADMIN status can only be applied to admins or operators.");
                return true;
            }
            if ("MOD".equals(statusKey) && !targetPlayer.hasPermission("statusplugin.mod")) {
                sender.sendMessage(ChatColor.RED + "MOD status can only be applied to moderators.");
                return true;
            }

            playerStatusMap.put(targetPlayer.getUniqueId(), status);
            String message = getLanguageText(targetPlayer, "status_set", "&aYour status has been set to: &r%s");
            targetPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', String.format(message, status)));
            
            // Assign player to team for sorting
            assignPlayerToTeam(targetPlayer);
            updatePlayerTabList();

            playerStatusConfig.set(targetPlayer.getUniqueId().toString(), status);
            savePlayerStatusConfig();
            sender.sendMessage(ChatColor.GREEN + "Status of " + targetPlayer.getName() + " has been set to: " + ChatColor.translateAlternateColorCodes('&', status));
            return true;
        }

        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Apply the default status if enabled
        if (getConfig().getBoolean("default_status_enabled", true)) {
            String defaultStatus = getConfig().getString("default_status", "DEFAULT");
            playerStatusMap.put(player.getUniqueId(), defaultStatus);

            // Save the default status to player-status.yml
            playerStatusConfig.set(player.getUniqueId().toString(), defaultStatus);
            savePlayerStatusConfig();
        }

        // Fetch country data for the player (async)
        if (countryLocationManager != null && getConfig().getBoolean("country-location-enabled", true)) {
            countryLocationManager.getPlayerCountryAsync(player).thenAccept(countryData -> {
                if (countryData != null) {
                    // Update tab list after country data is loaded
                    if (getConfig().getBoolean("tab-styling-enabled", true)) {
                        Bukkit.getScheduler().runTask(this, () -> updatePlayerTabList());
                    }
                }
            }).exceptionally(throwable -> {
                getLogger().warning("Failed to fetch country data for player " + player.getName() + ": " + throwable.getMessage());
                return null;
            });
        }

        // Send hardcoded admin join message if the player has the admin permission and the message is enabled
        if (player.hasPermission("statusplugin.admin") && getConfig().getBoolean("admin-join-message-enabled", false)) {
            String adminJoinMessage = "&aThank you for using this status plugin. When you want to support me, please download my plugin from Modrinth! https://modrinth.com/plugin/statusplugin-like-in-craftattack";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', adminJoinMessage));
        }

        // Check for the latest version and send message to admins or ops
        if (player.isOp() || player.hasPermission("statusplugin.admin")) {
            ModrinthVersionChecker.checkVersion();
        }

        // Update tab list if tab styling is enabled
        if (getConfig().getBoolean("tab-styling-enabled", true)) {
            // Assign player to team for sorting
            assignPlayerToTeam(player);
            updatePlayerTabList();
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        int deaths = playerDeathMap.getOrDefault(uuid, 0) + 1;
        playerDeathMap.put(uuid, deaths);
        if (playerDeathsConfig != null) {
            playerDeathsConfig.set(uuid.toString(), deaths);
            savePlayerDeathsConfig();
        }
        totalTrackedDeaths++;
        if (serverStatsConfig != null) {
            serverStatsConfig.set("total-deaths", totalTrackedDeaths);
            serverStatsDirty = true;
        }
        if (getConfig().getBoolean("tab-styling-enabled", true)) {
            updatePlayerTabListName(player, TabEnvironmentSnapshot.capture(this), "");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }
        totalBlocksPlaced++;
        if (serverStatsConfig != null) {
            serverStatsConfig.set("total-blocks-placed", totalBlocksPlaced);
            serverStatsDirty = true;
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        totalBlocksBroken++;
        if (serverStatsConfig != null) {
            serverStatsConfig.set("total-blocks-broken", totalBlocksBroken);
            serverStatsDirty = true;
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (Boolean.TRUE.equals(relayingToDiscord.get())) {
            return; // Skip formatting/broadcast when relaying to avoid duplicates
        }
        if (!getConfig().getBoolean("chat-styling-enabled", true)) {
            return; // Skip chat styling if disabled
        }

        Player player = event.getPlayer();
        String status = playerStatusMap.getOrDefault(player.getUniqueId(), "");
        String adminStatusFormat = statusOptions.get("ADMIN");
        boolean usingAdminStatus = adminStatusFormat != null && adminStatusFormat.equals(status);

        // Get the configured chat format from the config
        String chatFormat;
        if (status.isEmpty()) {
            chatFormat = getConfig().getString("chat-format-no-status", "<$$PLAYER$$> "); // Use a different format when no status
        } else {
            chatFormat = getConfig().getString("chat-format", "%status% <$$PLAYER$$> ");
            chatFormat = chatFormat.replace("%status%", status);
        }
        
        // Replace country placeholders (only if country location is enabled)
        if (getConfig().getBoolean("country-location-enabled", false) && 
            countryLocationManager != null) {
            CountryLocationManager.CountryData countryData = countryLocationManager.getPlayerCountry(player.getUniqueId());
            if (countryData != null) {
                chatFormat = chatFormat.replace("%country%", countryData.getCountry());
                chatFormat = chatFormat.replace("%countrycode%", countryData.getCountryCode());
            } else {
                chatFormat = chatFormat.replace("%country%", "");
                chatFormat = chatFormat.replace("%countrycode%", "");
            }
        } else {
            chatFormat = chatFormat.replace("%country%", "");
            chatFormat = chatFormat.replace("%countrycode%", "");
        }

        chatFormat = chatFormat.replace("%deaths%", String.valueOf(getPlayerDeaths(player.getUniqueId())));

        String playerName = player.getName();
        if (usingAdminStatus) {
            playerName = ChatColor.RED + playerName + ChatColor.RESET;
        }
        chatFormat = chatFormat.replace("$$PLAYER$$", playerName);
        chatFormat = ChatColor.translateAlternateColorCodes('&', chatFormat);

        // Create a TextComponent for the formatted message
        BaseComponent[] statusComponent = TextComponent.fromLegacyText(chatFormat);
        String messageText = event.getMessage();
        if (usingAdminStatus) {
            messageText = ChatColor.RED + messageText + ChatColor.RESET;
        }
        BaseComponent[] messageComponent = TextComponent.fromLegacyText(messageText);

        // Concatenate components to form the final message
        BaseComponent[] finalComponents = new BaseComponent[statusComponent.length + messageComponent.length];
        System.arraycopy(statusComponent, 0, finalComponents, 0, statusComponent.length);
        System.arraycopy(messageComponent, 0, finalComponents, statusComponent.length, messageComponent.length);

        // Convert URLs in the message to clickable links
        for (BaseComponent component : finalComponents) {
            String text = component.toPlainText();
            if (text.contains("http://") || text.contains("https://")) {
                component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, text));
            }
        }

        // Send the chat message to all players
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            recipient.spigot().sendMessage(finalComponents);
        }

        // Cancel the original chat event
        event.setCancelled(true);

        // Relay to DiscordSRV by firing a synthetic chat event on the main thread with no recipients
        if (isDiscordSrvPresent && getConfig().getBoolean("discordsrv-relay-enabled", true) && !Boolean.TRUE.equals(relayingToDiscord.get())) {
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    relayingToDiscord.set(true);
                    AsyncPlayerChatEvent forward = new AsyncPlayerChatEvent(false, player, event.getMessage(), new java.util.HashSet<>());
                    Bukkit.getPluginManager().callEvent(forward);
                } finally {
                    relayingToDiscord.set(false);
                }
            });
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("status")) {
            if (args.length == 1) {
                // Auto-complete status options
                String prefix = args[0].toUpperCase(Locale.ROOT);
                boolean canUseAdminStatus = true;
                boolean canUseModStatus = true;
                if (sender instanceof Player) {
                    Player playerSender = (Player) sender;
                    canUseAdminStatus = playerSender.isOp() || playerSender.hasPermission("statusplugin.admin");
                    canUseModStatus = playerSender.hasPermission("statusplugin.mod");
                }
                for (String option : statusOptions.keySet()) {
                    if (option.startsWith(prefix)) {
                        if ("ADMIN".equals(option) && !canUseAdminStatus) {
                            continue;
                        }
                        if ("MOD".equals(option) && !canUseModStatus) {
                            continue;
                        }
                        completions.add(option);
                    }
                }
            }
        } else if (command.getName().equalsIgnoreCase("status-admin")) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                if ("reload".startsWith(prefix)) {
                    completions.add("reload");
                }
                if ("deaths".startsWith(prefix)) {
                    completions.add("deaths");
                }
                if ("set".startsWith(prefix)) {
                    completions.add("set");
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        completions.add(player.getName());
                    }
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("deaths") || args[0].equalsIgnoreCase("set")) {
                    String prefix = args[1].toLowerCase(Locale.ROOT);
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            completions.add(player.getName());
                        }
                    }
                } else {
                    String prefix = args[1].toUpperCase(Locale.ROOT);
                    for (String option : statusOptions.keySet()) {
                        if (option.startsWith(prefix)) {
                            completions.add(option);
                        }
                    }
                }
            } else if (args.length == 3) {
                if (args[0].equalsIgnoreCase("deaths")) {
                    String prefix = args[2].toLowerCase(Locale.ROOT);
                    List<String> actions = Arrays.asList("add", "remove", "minus", "reset", "set");
                    for (String action : actions) {
                        if (action.startsWith(prefix)) {
                            completions.add(action);
                        }
                    }
                } else if (args[0].equalsIgnoreCase("set")) {
                    String prefix = args[2].toUpperCase(Locale.ROOT);
                    for (String option : statusOptions.keySet()) {
                        if (option.startsWith(prefix)) {
                            completions.add(option);
                        }
                    }
                }
            }
        }

        return completions;
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        commandName = config.getString("command-name", "status");
        config.getString("chat-format", "%status% &r<$$PLAYER$$> &e%countrycode% &c%deaths%");
        tabListFormat = config.getString("tab-list-format", "&a%status% &r$$PLAYER$$ &e%countrycode% &c%deaths%");
        defaultLanguage = config.getString("default-language", "english");
        useOnlyOneLanguage = config.getBoolean("use-only-one-language", true);
        customTabListEnabled = config.getBoolean("custom-tablist-enabled", false);
        tabRefreshIntervalTicks = Math.max(1, config.getInt("tab-refresh-interval-ticks", 20));
        tabDimensionRefreshIntervalTicks = Math.max(tabRefreshIntervalTicks, config.getInt("tab-dimension-refresh-interval-ticks", 200));

        // Load status options from status-options.yml
        loadStatusOptions();
        loadTabListConfig();

        // Load the default status options
        boolean defaultStatusEnabled = config.getBoolean("default_status_enabled", true);
        String defaultStatus = config.getString("default_status", "DEFAULT");
        if (defaultStatusEnabled) {
            statusOptions.put("DEFAULT", defaultStatus);
        }
    }

    private void loadStatusOptions() {
        File statusOptionsFile = new File(getDataFolder(), "status-options.yml");
        if (!statusOptionsFile.exists()) {
            saveResource("status-options.yml", false);
        }
        FileConfiguration statusOptionsConfig = YamlConfiguration.loadConfiguration(statusOptionsFile);
        if (statusOptionsConfig.isConfigurationSection("status")) {
            Set<String> keys = Objects.requireNonNull(statusOptionsConfig.getConfigurationSection("status")).getKeys(false);
            for (String key : keys) {
                statusOptions.put(key.toUpperCase(), statusOptionsConfig.getString("status." + key));
            }
        }
    }

    private void loadTabListConfig() {
        File tabListFile = new File(getDataFolder(), "tablist.yml");
        if (!tabListFile.exists()) {
            saveResource("tablist.yml", false);
        }

        FileConfiguration tabListConfig = YamlConfiguration.loadConfiguration(tabListFile);

        List<String> headerLines = tabListConfig.getStringList("header");
        if (headerLines.isEmpty() && tabListConfig.isString("header")) {
            String headerLine = tabListConfig.getString("header");
            headerLines = Collections.singletonList(headerLine == null ? "" : headerLine);
        }
        tabListHeaderLines = headerLines.isEmpty() ? Collections.emptyList() : new ArrayList<>(headerLines);

        List<String> footerLines = tabListConfig.getStringList("footer");
        if (footerLines.isEmpty() && tabListConfig.isString("footer")) {
            String footerLine = tabListConfig.getString("footer");
            footerLines = Collections.singletonList(footerLine == null ? "" : footerLine);
        }
        tabListFooterLines = footerLines.isEmpty() ? Collections.emptyList() : new ArrayList<>(footerLines);

        List<String> rotatingLines = Collections.emptyList();
        rotatingLineIntervalTicks = Math.max(tabRefreshIntervalTicks, 100);
        if (tabListConfig.isList("rotating-line")) {
            rotatingLines = tabListConfig.getStringList("rotating-line");
        } else {
            ConfigurationSection rotatingSection = tabListConfig.getConfigurationSection("rotating-line");
            if (rotatingSection != null) {
                rotatingLines = rotatingSection.getStringList("entries");
                rotatingLineIntervalTicks = Math.max(tabRefreshIntervalTicks, rotatingSection.getInt("interval-ticks", rotatingLineIntervalTicks));
            }
        }

        if (rotatingLines.isEmpty()) {
            tabListRotatingLines = Collections.emptyList();
        } else {
            tabListRotatingLines = new ArrayList<>(rotatingLines);
        }
        rotatingLineIndex = 0;
        rotatingLineElapsedTicks = 0;
    }

    private void loadLanguageConfig() {
        String languageFileName = useOnlyOneLanguage ? "language.yml" : defaultLanguage + "_language.yml";
        File languageFile;

        if (useOnlyOneLanguage) {
            languageFile = new File(getDataFolder(), languageFileName);
            if (!languageFile.exists()) {
                saveResource(languageFileName, false);
            }
        } else {
            languageFile = new File(getDataFolder(), defaultLanguage + "_language.yml");
            if (!languageFile.exists()) {
                saveResource(defaultLanguage + "_language.yml", false);
            }
        }

        languageConfig = YamlConfiguration.loadConfiguration(languageFile);
    }

    private void loadPlayerStatusConfig() {
        File playerStatusFile = new File(getDataFolder(), "player-status.yml");
        if (!playerStatusFile.exists()) {
            try {
                if (playerStatusFile.createNewFile()) {
                    getLogger().info("player-status.yml file created.");
                }
            } catch (IOException e) {
                getLogger().info("&c AN ERROR OCCURRED! | loadPlayerStatusConfig | IOException e");
            }
        }

        playerStatusConfig = YamlConfiguration.loadConfiguration(playerStatusFile);
    }

    private void loadPlayerDeathsConfig() {
        File playerDeathsFile = new File(getDataFolder(), "player-deaths.yml");
        if (!playerDeathsFile.exists()) {
            try {
                if (playerDeathsFile.createNewFile()) {
                    getLogger().info("player-deaths.yml file created.");
                }
            } catch (IOException e) {
                getLogger().info("&c AN ERROR OCCURRED! | loadPlayerDeathsConfig | IOException e");
            }
        }

        playerDeathsConfig = YamlConfiguration.loadConfiguration(playerDeathsFile);
    }

    private void loadServerStatsConfig() {
        File serverStatsFile = new File(getDataFolder(), "server-stats.yml");
        if (!serverStatsFile.exists()) {
            try {
                if (serverStatsFile.createNewFile()) {
                    getLogger().info("server-stats.yml file created.");
                }
            } catch (IOException e) {
                getLogger().info("&c AN ERROR OCCURRED! | loadServerStatsConfig | IOException e");
            }
        }

        serverStatsConfig = YamlConfiguration.loadConfiguration(serverStatsFile);
        totalBlocksPlaced = serverStatsConfig.getLong("total-blocks-placed", 0L);
        totalBlocksBroken = serverStatsConfig.getLong("total-blocks-broken", 0L);
        long storedTotalDeaths = serverStatsConfig.getLong("total-deaths", -1L);
        if (storedTotalDeaths >= 0) {
            totalTrackedDeaths = storedTotalDeaths;
        }
        serverStatsDirty = false;
        statsAutosaveCounterTicks = 0;
    }

    private void savePlayerStatusConfig() {
        File playerStatusFile = new File(getDataFolder(), "player-status.yml");
        try {
            playerStatusConfig.save(playerStatusFile);
        } catch (IOException e) {
            getLogger().info("&c AN ERROR OCCURRED! | savePlayerStatusConfig | IOException e");
        }
    }

    private void savePlayerDeathsConfig() {
        File playerDeathsFile = new File(getDataFolder(), "player-deaths.yml");
        try {
            playerDeathsConfig.save(playerDeathsFile);
        } catch (IOException e) {
            getLogger().info("&c AN ERROR OCCURRED! | savePlayerDeathsConfig | IOException e");
        }
    }

    private void saveServerStatsConfig() {
        if (serverStatsConfig == null) {
            return;
        }
        File serverStatsFile = new File(getDataFolder(), "server-stats.yml");
        serverStatsConfig.set("total-blocks-placed", totalBlocksPlaced);
        serverStatsConfig.set("total-blocks-broken", totalBlocksBroken);
        serverStatsConfig.set("total-deaths", totalTrackedDeaths);
        try {
            serverStatsConfig.save(serverStatsFile);
        } catch (IOException e) {
            getLogger().info("&c AN ERROR OCCURRED! | saveServerStatsConfig | IOException e");
        }
    }

    private void persistServerStats(boolean force) {
        if (serverStatsConfig == null) {
            return;
        }

        if (!serverStatsDirty && !force) {
            return;
        }

        if (!force) {
            statsAutosaveCounterTicks += tabRefreshIntervalTicks;
            if (statsAutosaveCounterTicks < STATS_AUTOSAVE_INTERVAL_TICKS) {
                return;
            }
        }

        statsAutosaveCounterTicks = 0;
        serverStatsDirty = false;
        saveServerStatsConfig();
    }

    private String getLanguageText(Player player, String key, String defaultText) {
        if (useOnlyOneLanguage) {
            return languageConfig.getString(key, defaultText);
        } else {
            String playerLanguage = languageConfig.getString(player.getUniqueId() + ".language", defaultLanguage);
            File playerLanguageFile = new File(getDataFolder(), playerLanguage + "_language.yml");
            FileConfiguration playerLanguageConfig = YamlConfiguration.loadConfiguration(playerLanguageFile);
            return playerLanguageConfig.getString(key, defaultText);
        }
    }

    /**
     * Initialize the scoreboard for tab list sorting using teams
     */
    private void initializeSortingScoreboard() {
        try {
            if (Bukkit.getScoreboardManager() == null) {
                getLogger().warning("ScoreboardManager is not available yet. Tab list sorting will be disabled.");
                return;
            }
            
            sortingScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            statusTeams.clear();
            getLogger().info("Initialized tab list sorting scoreboard");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize scoreboard for tab list sorting: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get or create a team for a specific status with the appropriate sorting prefix
     */
    private Team getOrCreateStatusTeam(String statusKey) {
        if (sortingScoreboard == null) {
            return null;
        }
        
        // Create a unique team name with sorting prefix
        String teamName = getTeamNameForStatus(statusKey);
        
        // Check cache first
        Team team = statusTeams.get(teamName);
        if (team != null) {
            return team;
        }
        
        // Check if team already exists on the scoreboard
        team = sortingScoreboard.getTeam(teamName);
        if (team == null) {
            // Team doesn't exist, create it
            try {
                team = sortingScoreboard.registerNewTeam(teamName);
            } catch (IllegalArgumentException e) {
                // Team already exists (race condition), get it
                team = sortingScoreboard.getTeam(teamName);
            }
        }
        
        // Add to cache
        if (team != null) {
            statusTeams.put(teamName, team);
        }
        
        return team;
    }
    
    /**
     * Get the team name with sorting prefix for a status
     * Format: <priority>_<statusname>
     * Priority: 0=ADMIN, 1=MOD, 2=normal, 9=AFK/CAM
     */
    private String getTeamNameForStatus(String statusKey) {
        if (statusKey == null || statusKey.isEmpty()) {
            return "2_nostatus";
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
                // Use status key for alphabetical sorting within normal statuses
                return "2_" + statusKey.toLowerCase(Locale.ROOT);
        }
    }
    
    /**
     * Assign a player to the appropriate team based on their status
     */
    private void assignPlayerToTeam(Player player) {
        if (sortingScoreboard == null || !getConfig().getBoolean("tab-list-sort-by-status", true)) {
            return;
        }
        
        try {
            String status = playerStatusMap.getOrDefault(player.getUniqueId(), "");
            String statusKey = getStatusKey(status);
            
            // Remove player from any existing team in the scoreboard
            Team currentTeam = sortingScoreboard.getEntryTeam(player.getName());
            if (currentTeam != null) {
                currentTeam.removeEntry(player.getName());
            }
            
            // Add player to the appropriate team
            Team team = getOrCreateStatusTeam(statusKey);
            if (team != null) {
                team.addEntry(player.getName());
            }
            
            // Set the scoreboard for the player (only if they don't have one or have different one)
            if (player.getScoreboard() != sortingScoreboard) {
                player.setScoreboard(sortingScoreboard);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to assign player " + player.getName() + " to team: " + e.getMessage());
        }
    }
    
    /**
     * Update all players' team assignments
     */
    private void updateAllPlayerTeams() {
        if (sortingScoreboard == null || !getConfig().getBoolean("tab-list-sort-by-status", true)) {
            return;
        }
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            assignPlayerToTeam(player);
        }
    }

    private void updatePlayerTabList() {
        updatePlayerTabList(TabEnvironmentSnapshot.capture(this));
    }

    /**
     * Get the status key from the status value (reverse lookup)
     */
    private String getStatusKey(String statusValue) {
        if (statusValue == null || statusValue.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String> entry : statusOptions.entrySet()) {
            if (entry.getValue().equals(statusValue)) {
                return entry.getKey();
            }
        }
        return "";
    }

    private void updatePlayerTabList(TabEnvironmentSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        // Update team assignments for all players (this handles the sorting)
        boolean sortByStatus = getConfig().getBoolean("tab-list-sort-by-status", true);
        if (sortByStatus && sortingScoreboard != null) {
            updateAllPlayerTeams();
        }

        // Update the tab list display for each player
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerTabListName(player, snapshot, "");
        }
    }

    private void startTabRefreshSchedulers() {
        stopTabRefreshSchedulers();

        if (!getConfig().getBoolean("tab-styling-enabled", true)) {
            return;
        }

        statsAutosaveCounterTicks = 0;
        refreshDimensionCache();
        runFastTabRefreshTick();

        slowTabRefreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                refreshDimensionCache();
            }
        }.runTaskTimer(this, tabDimensionRefreshIntervalTicks, tabDimensionRefreshIntervalTicks);
        fastTabRefreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                runFastTabRefreshTick();
            }
        }.runTaskTimer(this, tabRefreshIntervalTicks, tabRefreshIntervalTicks);
    }

    private void stopTabRefreshSchedulers() {
        if (fastTabRefreshTask != null) {
            fastTabRefreshTask.cancel();
            fastTabRefreshTask = null;
        }
        if (slowTabRefreshTask != null) {
            slowTabRefreshTask.cancel();
            slowTabRefreshTask = null;
        }
    }

    private void refreshDimensionCache() {
        cachedOverworldPlayers = countPlayersInEnvironment(World.Environment.NORMAL);
        cachedNetherPlayers = countPlayersInEnvironment(World.Environment.NETHER);
        cachedEndPlayers = countPlayersInEnvironment(World.Environment.THE_END);
    }

    private void runFastTabRefreshTick() {
        if (!getConfig().getBoolean("tab-styling-enabled", true)) {
            return;
        }

        double[] tpsValues = fetchRecentTps();
        cachedPerformanceLabel = computePerformanceLabel(tpsValues);

        if (!tabListRotatingLines.isEmpty()) {
            rotatingLineElapsedTicks += tabRefreshIntervalTicks;
            if (rotatingLineElapsedTicks >= Math.max(rotatingLineIntervalTicks, tabRefreshIntervalTicks)) {
                rotatingLineElapsedTicks = 0;
                rotatingLineIndex = (rotatingLineIndex + 1) % tabListRotatingLines.size();
            }
        }

        TabEnvironmentSnapshot snapshot = TabEnvironmentSnapshot.capture(this, tpsValues);
        updatePlayerTabList(snapshot);
        persistServerStats(false);
    }

    private String computePerformanceLabel(double[] tpsValues) {
        double tps = tpsValues != null && tpsValues.length > 0 ? tpsValues[0] : -1;
        if (tps <= 0) {
            return ChatColor.DARK_RED + "" + ChatColor.UNDERLINE + "UNKNOWN" + ChatColor.RESET;
        }
        if (tps >= 19.5D) {
            return ChatColor.GREEN + "" + ChatColor.UNDERLINE + "SMOOTH" + ChatColor.RESET;
        }
        if (tps >= 18.0D) {
            return ChatColor.YELLOW + "" + ChatColor.UNDERLINE + "STABLE" + ChatColor.RESET;
        }
        if (tps >= 15.0D) {
            return ChatColor.GOLD + "" + ChatColor.UNDERLINE + "STRUGGLING" + ChatColor.RESET;
        }
        return ChatColor.RED + "" + ChatColor.UNDERLINE + "CRITICAL" + ChatColor.RESET;
    }

    public String getPlayerStatus(UUID uuid) {
        return playerStatusMap.getOrDefault(uuid, "");
    }

    public CountryLocationManager getCountryLocationManager() {
        return countryLocationManager;
    }

    public int getPlayerDeaths(UUID uuid) {
        return playerDeathMap.getOrDefault(uuid, 0);
    }

    public String getPerformanceLabel() {
        if (fastTabRefreshTask == null) {
            cachedPerformanceLabel = computePerformanceLabel(fetchRecentTps());
        }
        return cachedPerformanceLabel;
    }

    public long getTotalBlocksPlaced() {
        return totalBlocksPlaced;
    }

    public String getFormattedTotalBlocksPlaced() {
        return formatLargeNumber(totalBlocksPlaced);
    }

    public long getTotalBlocksBroken() {
        return totalBlocksBroken;
    }

    public String getFormattedTotalBlocksBroken() {
        return formatLargeNumber(totalBlocksBroken);
    }

    public long getTotalTrackedDeaths() {
        return totalTrackedDeaths;
    }

    public String getFormattedTotalTrackedDeaths() {
        return formatLargeNumber(totalTrackedDeaths);
    }



    private void updatePlayerTabListName(Player player, TabEnvironmentSnapshot snapshot, String invisibleSortPrefix) {
        String status = playerStatusMap.getOrDefault(player.getUniqueId(), "");
        String playerName = player.getName();
        String adminStatusFormat = statusOptions.get("ADMIN");
        boolean usingAdminStatus = adminStatusFormat != null && adminStatusFormat.equals(status);
        String coloredPlayerName = usingAdminStatus ? ChatColor.RED + playerName + ChatColor.RESET : playerName;

        CountryLocationManager.CountryData countryData = null;
        if (snapshot.countryPlaceholdersEnabled) {
            countryData = countryLocationManager.getPlayerCountry(player.getUniqueId());
        }

        if (snapshot.tabStylingEnabled) {
            String template = status.isEmpty()
                    ? getConfig().getString("tab-list-format-no-status", "&7[&e%countrycode%&7] &r$$PLAYER$$")
                    : tabListFormat;
            String tabListName = formatTabListText(template, player, status, coloredPlayerName, countryData, snapshot);
            tabListName = ChatColor.translateAlternateColorCodes('&', tabListName);
            
            // Add invisible sorting prefix to force tab list order
            tabListName = invisibleSortPrefix + tabListName;

            if (isTabPluginPresent) {
                // Update the tab list name using TAB API
                TabPlayer tabPlayer = TabAPI.getInstance().getPlayer(player.getUniqueId());
                TabListFormatManager formatManager = TabAPI.getInstance().getTabListFormatManager();
                if (tabPlayer != null && formatManager != null) {
                    formatManager.setPrefix(tabPlayer, null); // Reset prefix
                    formatManager.setName(tabPlayer, tabListName); // Set custom name
                    formatManager.setSuffix(tabPlayer, null); // Reset suffix
                }
            } else {
                // Fallback to default method
                player.setPlayerListName(tabListName);
            }
        } else {
            resetTabListName(player);
        }

        applyTabListHeaderFooter(player, snapshot, status, coloredPlayerName, countryData);
    }

    private void resetTabListName(Player player) {
        if (isTabPluginPresent) {
            TabPlayer tabPlayer = TabAPI.getInstance().getPlayer(player.getUniqueId());
            TabListFormatManager formatManager = TabAPI.getInstance().getTabListFormatManager();
            if (tabPlayer != null && formatManager != null) {
                formatManager.setPrefix(tabPlayer, null);
                formatManager.setName(tabPlayer, player.getName());
                formatManager.setSuffix(tabPlayer, null);
            }
        } else {
            player.setPlayerListName(player.getName());
        }
    }

    private void applyTabListHeaderFooter(Player player, TabEnvironmentSnapshot snapshot, String status, String coloredPlayerName, CountryLocationManager.CountryData countryData) {
        String header = "";
        String footer = "";

        if (customTabListEnabled) {
            header = formatTabListSection(tabListHeaderLines, player, status, coloredPlayerName, countryData, snapshot);
            footer = formatTabListSection(tabListFooterLines, player, status, coloredPlayerName, countryData, snapshot);
        }

        header = ChatColor.translateAlternateColorCodes('&', header);
        footer = ChatColor.translateAlternateColorCodes('&', footer);

        if (isTabPluginPresent) {
            TabPlayer tabPlayer = TabAPI.getInstance().getPlayer(player.getUniqueId());
            HeaderFooterManager headerFooterManager = TabAPI.getInstance().getHeaderFooterManager();
            if (tabPlayer != null && headerFooterManager != null) {
                headerFooterManager.setHeaderAndFooter(tabPlayer, header, footer);
            }
        } else {
            player.setPlayerListHeaderFooter(header, footer);
        }
    }

    private String formatTabListSection(List<String> lines, Player player, String status, String coloredPlayerName, CountryLocationManager.CountryData countryData, TabEnvironmentSnapshot snapshot) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String processed = formatTabListText(line == null ? "" : line, player, status, coloredPlayerName, countryData, snapshot);
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(processed);
        }

        return builder.toString();
    }

    private String formatTabListText(String template, Player player, String status, String coloredPlayerName, CountryLocationManager.CountryData countryData, TabEnvironmentSnapshot snapshot) {
        return applyTabListPlaceholders(template, player, status, coloredPlayerName, countryData, snapshot, true);
    }

    private String applyTabListPlaceholders(String template,
                                            Player player,
                                            String status,
                                            String coloredPlayerName,
                                            CountryLocationManager.CountryData countryData,
                                            TabEnvironmentSnapshot snapshot,
                                            boolean includeRotatingToken) {
        if (template == null) {
            return "";
        }

        String result = template
                .replace("$$PLAYER$$", coloredPlayerName)
                .replace("%status%", status)
                .replace("%deaths%", String.valueOf(getPlayerDeaths(player.getUniqueId())))
                .replace("%online_players%", String.valueOf(snapshot.onlinePlayers))
                .replace("%max_players%", String.valueOf(snapshot.maxPlayers))
                .replace("%overworld_players%", String.valueOf(snapshot.overworldPlayers))
                .replace("%nether_players%", String.valueOf(snapshot.netherPlayers))
                .replace("%end_players%", String.valueOf(snapshot.endPlayers))
                .replace("%server_time%", snapshot.serverTime)
                .replace("%time%", snapshot.serverTime)
                .replace("%tps%", snapshot.tps1m)
                .replace("%tps_1m%", snapshot.tps1m)
                .replace("%tps_5m%", snapshot.tps5m)
                .replace("%tps_15m%", snapshot.tps15m)
                .replace("%performance%", snapshot.performanceLabel)
                .replace("%performance_label%", snapshot.performanceLabel)
                .replace("%total_blocks%", snapshot.totalBlocksShort)
                .replace("%total_blocks_raw%", String.valueOf(snapshot.totalBlocksRaw))
                .replace("%total_blocks_broken%", snapshot.totalBlocksBrokenShort)
                .replace("%total_blocks_broken_raw%", String.valueOf(snapshot.totalBlocksBrokenRaw))
                .replace("%total_deaths%", snapshot.totalDeathsShort)
                .replace("%total_deaths_raw%", String.valueOf(snapshot.totalDeathsRaw));

        result = replaceCountryPlaceholders(result, countryData);

        if (includeRotatingToken && result.contains("%rotating_line%")) {
            String rotatingLine = "";
            if (!tabListRotatingLines.isEmpty()) {
                String rotatingTemplate = tabListRotatingLines.get(rotatingLineIndex % tabListRotatingLines.size());
                rotatingLine = applyTabListPlaceholders(rotatingTemplate, player, status, coloredPlayerName, countryData, snapshot, false);
            }
            result = result.replace("%rotating_line%", rotatingLine);
        } else if (!includeRotatingToken) {
            result = result.replace("%rotating_line%", "");
        }

        return result;
    }

    private int countPlayersInEnvironment(World.Environment environment) {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                total += world.getPlayers().size();
            }
        }
        return total;
    }

    private double[] fetchRecentTps() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getServer");
            Object dedicatedServer = method.invoke(Bukkit.getServer());
            Field recentTpsField = dedicatedServer.getClass().getField("recentTps");
            recentTpsField.setAccessible(true);
            double[] values = (double[]) recentTpsField.get(dedicatedServer);
            return values != null ? values.clone() : null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private String getCurrentServerTime() {
        return LocalTime.now().format(TABLIST_TIME_FORMAT);
    }

    private static String formatTpsValue(double[] values, int index) {
        if (values != null && index >= 0 && index < values.length) {
            double value = Math.min(values[index], 20.0D);
            return String.format(Locale.US, "%.2f", value);
        }
        return PLACEHOLDER_NOT_AVAILABLE;
    }

    private String formatLargeNumber(long value) {
        long absValue = Math.abs(value);
        if (absValue >= 1_000_000_000L) {
            return formatWithSuffix(value, 1_000_000_000L, "b");
        }
        if (absValue >= 1_000_000L) {
            return formatWithSuffix(value, 1_000_000L, "m");
        }
        if (absValue >= 1_000L) {
            return formatWithSuffix(value, 1_000L, "k");
        }
        return String.valueOf(value);
    }

    private String formatWithSuffix(long value, long divisor, String suffix) {
        double scaled = (double) value / divisor;
        String formatted = Math.abs(scaled) >= 100
                ? String.format(Locale.US, "%.0f", scaled)
                : String.format(Locale.US, "%.1f", scaled);
        if (formatted.endsWith(".0")) {
            formatted = formatted.substring(0, formatted.length() - 2);
        }
        return formatted + suffix;
    }

    private static final class TabEnvironmentSnapshot {
        private final boolean tabStylingEnabled;
        private final boolean countryPlaceholdersEnabled;
        private final String serverTime;
        private final int overworldPlayers;
        private final int netherPlayers;
        private final int endPlayers;
        private final int onlinePlayers;
        private final int maxPlayers;
        private final String tps1m;
        private final String tps5m;
        private final String tps15m;
        private final String performanceLabel;
        private final long totalBlocksRaw;
        private final String totalBlocksShort;
        private final long totalBlocksBrokenRaw;
        private final String totalBlocksBrokenShort;
        private final long totalDeathsRaw;
        private final String totalDeathsShort;

        private TabEnvironmentSnapshot(boolean tabStylingEnabled,
                                       boolean countryPlaceholdersEnabled,
                                       String serverTime,
                                       int overworldPlayers,
                                       int netherPlayers,
                                       int endPlayers,
                                       int onlinePlayers,
                                       int maxPlayers,
                                       String tps1m,
                                       String tps5m,
                                       String tps15m,
                                       String performanceLabel,
                                       long totalBlocksRaw,
                                       String totalBlocksShort,
                                       long totalBlocksBrokenRaw,
                                       String totalBlocksBrokenShort,
                                       long totalDeathsRaw,
                                       String totalDeathsShort) {
            this.tabStylingEnabled = tabStylingEnabled;
            this.countryPlaceholdersEnabled = countryPlaceholdersEnabled;
            this.serverTime = serverTime;
            this.overworldPlayers = overworldPlayers;
            this.netherPlayers = netherPlayers;
            this.endPlayers = endPlayers;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.tps1m = tps1m;
            this.tps5m = tps5m;
            this.tps15m = tps15m;
            this.performanceLabel = performanceLabel;
            this.totalBlocksRaw = totalBlocksRaw;
            this.totalBlocksShort = totalBlocksShort;
            this.totalBlocksBrokenRaw = totalBlocksBrokenRaw;
            this.totalBlocksBrokenShort = totalBlocksBrokenShort;
            this.totalDeathsRaw = totalDeathsRaw;
            this.totalDeathsShort = totalDeathsShort;
        }

        private static TabEnvironmentSnapshot capture(StatusPlugin plugin) {
            return capture(plugin, null);
        }

        private static TabEnvironmentSnapshot capture(StatusPlugin plugin, double[] tpsValues) {
            boolean tabStylingEnabled = plugin.getConfig().getBoolean("tab-styling-enabled", true);
            boolean countryEnabled = plugin.getConfig().getBoolean("country-location-enabled", false)
                    && plugin.countryLocationManager != null;
            double[] resolvedTps = tpsValues != null ? tpsValues : plugin.fetchRecentTps();
            return new TabEnvironmentSnapshot(
                    tabStylingEnabled,
                    countryEnabled,
                    plugin.getCurrentServerTime(),
                    plugin.cachedOverworldPlayers,
                    plugin.cachedNetherPlayers,
                    plugin.cachedEndPlayers,
                    Bukkit.getOnlinePlayers().size(),
                    Bukkit.getMaxPlayers(),
                    formatTpsValue(resolvedTps, 0),
                    formatTpsValue(resolvedTps, 1),
                    formatTpsValue(resolvedTps, 2),
                    plugin.cachedPerformanceLabel,
                    plugin.totalBlocksPlaced,
                    plugin.formatLargeNumber(plugin.totalBlocksPlaced),
                    plugin.totalBlocksBroken,
                    plugin.formatLargeNumber(plugin.totalBlocksBroken),
                    plugin.totalTrackedDeaths,
                    plugin.formatLargeNumber(plugin.totalTrackedDeaths)
            );
        }
    }

    private String replaceCountryPlaceholders(String text, CountryLocationManager.CountryData countryData) {
        if (text == null) {
            return "";
        }

        if (countryData != null) {
            text = text.replace("%country%", countryData.getCountry());
            text = text.replace("%countrycode%", countryData.getCountryCode());
        } else {
            text = text.replace("%country%", "");
            text = text.replace("%countrycode%", "");
        }

        return text;
    }
    private void reloadPlugin() {
        persistServerStats(true);
        stopTabRefreshSchedulers();
        reloadConfig();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig();
        loadPlayerDeathsConfig();
        loadServerStatsConfig();
        loadPlayerStatuses();
        loadPlayerDeaths();
        refreshDimensionCache();
        startTabRefreshSchedulers();
        
        // Reinitialize scoreboard for sorting
        if (getConfig().getBoolean("tab-list-sort-by-status", true)) {
            // Clear existing team cache
            statusTeams.clear();
            if (sortingScoreboard == null) {
                initializeSortingScoreboard();
            }
            updateAllPlayerTeams();
        }
        
        updatePlayerTabList();
    }
}
