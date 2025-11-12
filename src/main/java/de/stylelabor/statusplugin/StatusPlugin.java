package de.stylelabor.statusplugin;

import de.stylelabor.statusplugin.nametag.StatusNametagManager;
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
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;
import java.util.logging.Level;

import java.net.InetAddress;
import java.util.concurrent.CompletionStage;

public final class StatusPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final HashMap<UUID, String> playerStatusMap = new HashMap<>();
    private final HashMap<UUID, Integer> playerDeathMap = new HashMap<>();
    private String commandName;
    private String tabListFormat;
    private boolean customTabListEnabled;
    private boolean nametagStatusEnabled;
    private boolean nametagCleanJoinMessage;
    private boolean nametagCleanDeathMessage;
    private StatusNametagManager nametagManager;
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
    private boolean isLibertyBansPresent;
    private LibertyBansIntegration libertyBansIntegration;
    private CountryLocationManager countryLocationManager;
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
    private String cachedMspt = PLACEHOLDER_NOT_AVAILABLE;
    private static final ThreadLocal<Boolean> relayingToDiscord = ThreadLocal.withInitial(() -> false);
    private static final DateTimeFormatter TABLIST_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());
    private static final String PLACEHOLDER_NOT_AVAILABLE = "N/A";
    private static final int STATS_AUTOSAVE_INTERVAL_TICKS = 6000;
    private static final long STATS_SAVE_DELAY_TICKS = 200L;
    private BukkitTask delayedStatsSaveTask;
    @Override
    public void onEnable() {
        // Plugin startup logic
        nametagManager = new StatusNametagManager(this);
        saveDefaultConfig();
        updateConfigIfNeeded();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig(); // Load player status during plugin startup
        loadPlayerDeathsConfig(); // Load player deaths during plugin startup
        loadServerStatsConfig(); // Load persistent server-wide stats
        loadPlayerStatuses(); // Load the statuses of all players from player-status.yml
        loadPlayerDeaths(); // Load the death counts of all players from player-deaths.yml
        getServer().getPluginManager().registerEvents(this, this);
        registerPaperAsyncChatListeners();
        Objects.requireNonNull(getCommand(commandName)).setTabCompleter(this);
        int pluginId = 20901;
        //noinspection unused
        new Metrics(this, pluginId);

        isTabPluginPresent = Bukkit.getPluginManager().getPlugin("TAB") != null;
        isDiscordSrvPresent = Bukkit.getPluginManager().getPlugin("DiscordSRV") != null;

        if (isDiscordSrvPresent) {
            getLogger().info("[StatusPlugin] DiscordSRV detected. Enabling Discord relay features.");
        }

        // Delay LibertyBans initialization to ensure it's fully loaded
        // Schedule this to run after server startup is complete
        Bukkit.getScheduler().runTaskLater(this, () -> {
            initializeLibertyBans();
        }, 20L); // Wait 1 second (20 ticks) after plugin enable

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
        
        // Detect and log which TPS fetching method is available
        detectTpsFetchingMethod();
        
        // Detect and log MiniMessage support
        detectMiniMessageSupport();
        
        startTabRefreshSchedulers();
        
        if (nametagManager != null) {
            nametagManager.scheduleInitialization();
            nametagManager.updateAllPlayerTeams();
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
        for (String uuid : playerDeathsConfig.getKeys(false)) {
            int deaths = playerDeathsConfig.getInt(uuid, 0);
            playerDeathMap.put(UUID.fromString(uuid), deaths);
        }
        recalculateTotalTrackedDeathsFromMap();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        stopTabRefreshSchedulers();
        savePlayerStatusConfig(); // Save player status during plugin shutdown
        savePlayerDeathsConfig(); // Save player deaths during plugin shutdown
        persistServerStats(true);
        if (nametagManager != null) {
            nametagManager.tearDown();
        }
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
                player.sendMessage(ColorParser.parse(message));
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
                    player.sendMessage(ColorParser.parse(String.format(message, status)));
                    player.sendMessage(ChatColor.GRAY + "Your status will show in chat when you send a message.");
                    
                    // Debug: Show what's in the map
                    getLogger().info("Set status for " + player.getName() + ": " + status);
                    
                    // Assign player to team for sorting
                    if (nametagManager != null) {
                        nametagManager.assignPlayer(player);
                    }
                    updatePlayerTabList();

                    // Save the player status to player-status.yml
                    playerStatusConfig.set(player.getUniqueId().toString(), status);
                    savePlayerStatusConfig();
                } else {
                    String message = getLanguageText(player, "invalid_status", "&cInvalid status option. Use /status <option>");
                    player.sendMessage(ColorParser.parse(message));
                }
            } else {
                playerStatusMap.remove(player.getUniqueId());
                String message = getLanguageText(player, "status_cleared", "&aYour status has been cleared.");
                player.sendMessage(ColorParser.parse(message));
                
                // Assign player to team for sorting
                if (nametagManager != null) {
                    nametagManager.assignPlayer(player);
                }
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
            player.sendMessage(ColorParser.parse(message));
            
            // Assign player to team for sorting
            if (nametagManager != null) {
                nametagManager.assignPlayer(player);
            }
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
                adjustTotalTrackedDeaths((long) newDeaths - currentDeaths);
                if (getConfig().getBoolean("tab-styling-enabled", true)) {
                    updatePlayerTabList();
                }

                Player refreshedOnlineTarget = Bukkit.getPlayer(targetUuid);
                if (refreshedOnlineTarget != null) {
                    try {
                        refreshedOnlineTarget.setStatistic(Statistic.DEATHS, newDeaths);
                    } catch (IllegalArgumentException ex) {
                        getLogger().log(Level.FINE, "Failed to set vanilla death statistic for " + refreshedOnlineTarget.getName(), ex);
                    }
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
            targetPlayer.sendMessage(ColorParser.parse(String.format(message, status)));
            
            // Assign player to team for sorting
            if (nametagManager != null) {
                nametagManager.assignPlayer(targetPlayer);
            }
            updatePlayerTabList();

            playerStatusConfig.set(targetPlayer.getUniqueId().toString(), status);
            savePlayerStatusConfig();
            sender.sendMessage(ChatColor.GREEN + "Status of " + targetPlayer.getName() + " has been set to: " + ColorParser.parse(status));
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

        syncPlayerDeathsFromStatistic(player);

        // Send hardcoded admin join message if the player has the admin permission and the message is enabled
        if (player.hasPermission("statusplugin.admin") && getConfig().getBoolean("admin-join-message-enabled", false)) {
            String adminJoinMessage = "&aThank you for using this status plugin. When you want to support me, please download my plugin from Modrinth! https://modrinth.com/plugin/statusplugin-like-in-craftattack";
            player.sendMessage(ColorParser.parse(adminJoinMessage));
        }

        // Check for the latest version and send message to admins or ops
        if (player.isOp() || player.hasPermission("statusplugin.admin")) {
            ModrinthVersionChecker.checkVersion();
        }

        // Update tab list if tab styling is enabled
        if (getConfig().getBoolean("tab-styling-enabled", true)) {
            // Assign player to team for sorting
            if (nametagManager != null) {
                nametagManager.assignPlayer(player);
            }
            updatePlayerTabList();
        }

        if (nametagCleanJoinMessage) {
            String joinMessage = event.getJoinMessage();
            if (joinMessage != null && !joinMessage.isEmpty()) {
                joinMessage = sanitizeDisplayNameOccurrences(joinMessage, player);
                event.setJoinMessage(joinMessage);
            }
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
        adjustTotalTrackedDeaths(1);
        if (getConfig().getBoolean("tab-styling-enabled", true)) {
            updatePlayerTabList();
        }
        Bukkit.getScheduler().runTask(this, () -> syncPlayerDeathsFromStatistic(player));

        if (nametagCleanDeathMessage) {
            String deathMessage = event.getDeathMessage();
            if (deathMessage != null && !deathMessage.isEmpty()) {
                deathMessage = sanitizeDisplayNameOccurrences(deathMessage, player);
                Player killer = player.getKiller();
                if (killer != null) {
                    deathMessage = sanitizeDisplayNameOccurrences(deathMessage, killer);
                }
                event.setDeathMessage(deathMessage);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChatMuteCheck(AsyncPlayerChatEvent event) {
        cancelIfMuted(event.getPlayer(), event);
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        handleChatMessage(event.getPlayer(), event.getMessage(), event);
    }

    private void handleChatMessage(Player player, String originalMessage, Cancellable cancellable) {
        if (Boolean.TRUE.equals(relayingToDiscord.get())) {
            return; // Skip formatting/broadcast when relaying to avoid duplicates
        }

        if (cancellable.isCancelled()) {
            return; // Respect prior cancellations (e.g., mute checks)
        }

        if (!getConfig().getBoolean("chat-styling-enabled", true)) {
            return; // Skip chat styling if disabled
        }

        String status = playerStatusMap.getOrDefault(player.getUniqueId(), "");

        // Debug logging
        if (!status.isEmpty()) {
            getLogger().info("Chat from " + player.getName() + " with status: " + status);
        }

        String adminStatusFormat = statusOptions.get("ADMIN");
        boolean usingAdminStatus = adminStatusFormat != null && adminStatusFormat.equals(status);

        // Get the configured chat format from the config
        String chatFormat;
        if (status.isEmpty()) {
            chatFormat = getConfig().getString("chat-format-no-status", "<$$PLAYER$$> "); // Use a different format when no status
        } else {
            chatFormat = getConfig().getString("chat-format", "%status% <$$PLAYER$$> ");
            chatFormat = chatFormat.replace("%status%", status);
            // Debug: Show the format being used
            getLogger().info("Using chat format: " + chatFormat);
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
        chatFormat = ColorParser.parse(chatFormat);

        // Create a TextComponent for the formatted message
        BaseComponent[] statusComponent = TextComponent.fromLegacyText(chatFormat);
        String messageText = originalMessage;
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
        cancellable.setCancelled(true);

        // Relay to DiscordSRV by firing a synthetic chat event on the main thread with no recipients
        relayChatToDiscord(player, originalMessage);
    }

    private boolean cancelIfMuted(Player player, Cancellable event) {
        if (event.isCancelled()) {
            return true;
        }

        if (isLibertyBansPresent && libertyBansIntegration != null) {
            Optional<MuteInfo> muteInfo = libertyBansIntegration.getActiveMute(player);
            if (muteInfo.isPresent()) {
                event.setCancelled(true);

                // Send mute notification to the player
                sendMuteNotification(player, muteInfo.get());
                return true;
            }
        }

        return false;
    }

    private void relayChatToDiscord(Player player, String message) {
        if (!isDiscordSrvPresent || !getConfig().getBoolean("discordsrv-relay-enabled", true) || Boolean.TRUE.equals(relayingToDiscord.get())) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            try {
                relayingToDiscord.set(true);
                AsyncPlayerChatEvent forward = new AsyncPlayerChatEvent(false, player, message, new java.util.HashSet<>());
                Bukkit.getPluginManager().callEvent(forward);
            } finally {
                relayingToDiscord.set(false);
            }
        });
    }

    private String convertAdventureComponentToLegacy(Object adventureComponent) {
        if (adventureComponent == null) {
            return "";
        }

        try {
            ClassLoader loader = adventureComponent.getClass().getClassLoader();
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component", true, loader);
            if (componentClass.isInstance(adventureComponent)) {
                Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer", true, loader);
                Method plainTextMethod = serializerClass.getMethod("plainText");
                Object serializer = plainTextMethod.invoke(null);
                Method serializeMethod = serializer.getClass().getMethod("serialize", componentClass);
                Object plain = serializeMethod.invoke(serializer, adventureComponent);
                if (plain instanceof String) {
                    return (String) plain;
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Plain serializer not available on this platform
        } catch (IllegalAccessException | InvocationTargetException ex) {
            getLogger().log(Level.FINE, "Failed to convert Adventure component using plain serializer", ex);
        }

        try {
            Method contentMethod = adventureComponent.getClass().getMethod("content");
            Object content = contentMethod.invoke(adventureComponent);
            if (content instanceof String) {
                return (String) content;
            }
        } catch (NoSuchMethodException ignored) {
            // Not a TextComponentImpl - ignore
        } catch (IllegalAccessException | InvocationTargetException ex) {
            getLogger().log(Level.FINE, "Failed to access Adventure component content", ex);
        }

        String raw = adventureComponent.toString();
        int contentIndex = raw.indexOf("content=\"");
        if (contentIndex >= 0) {
            int start = contentIndex + "content=\"".length();
            int end = raw.indexOf('"', start);
            if (end > start) {
                return raw.substring(start, end);
            }
        }

        return raw;
    }

    @SuppressWarnings("unchecked")
    private void registerPaperAsyncChatListeners() {
        try {
            final Class<?> asyncChatEventClass = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            final Method playerMethod = asyncChatEventClass.getMethod("getPlayer");
            final Method messageMethod = asyncChatEventClass.getMethod("message");

            EventExecutor muteExecutor = (listener, event) -> {
                if (!asyncChatEventClass.isInstance(event)) {
                    return;
                }
                if (!(event instanceof Cancellable)) {
                    return;
                }
                try {
                    Player player = (Player) playerMethod.invoke(event);
                    cancelIfMuted(player, (Cancellable) event);
                } catch (IllegalAccessException | InvocationTargetException reflectionException) {
                    getLogger().log(Level.SEVERE, "Failed to handle Paper AsyncChatEvent mute check", reflectionException);
                }
            };

            EventExecutor chatExecutor = (listener, event) -> {
                if (!asyncChatEventClass.isInstance(event)) {
                    return;
                }
                if (!(event instanceof Cancellable)) {
                    return;
                }
                try {
                    Player player = (Player) playerMethod.invoke(event);
                    Object component = messageMethod.invoke(event);
                    String message = convertAdventureComponentToLegacy(component);
                    handleChatMessage(player, message, (Cancellable) event);
                } catch (IllegalAccessException | InvocationTargetException reflectionException) {
                    getLogger().log(Level.SEVERE, "Failed to handle Paper AsyncChatEvent formatting", reflectionException);
                }
            };

            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) asyncChatEventClass, this, EventPriority.LOWEST, muteExecutor, this, false);
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) asyncChatEventClass, this, EventPriority.HIGH, chatExecutor, this, false);
            getLogger().info("Paper AsyncChatEvent detected. Modern chat pipeline support enabled.");
        } catch (ClassNotFoundException ignored) {
            // Server is not running Paper or does not expose the modern async chat event
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to register Paper AsyncChatEvent listeners", ex);
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

    /**
     * Update config.yml if it's from an older version
     * Adds missing keys from the default config while preserving existing values
     * ONLY adds new keys - NEVER overwrites existing settings
     */
    private void updateConfigIfNeeded() {
        FileConfiguration config = getConfig();
        
        // Get current config version (defaults to 1 if not present)
        int currentConfigVersion = config.getInt("config-version", 1);
        
        // Expected config version for this plugin version
        int expectedConfigVersion = 8;
        
        // Check if update is needed
        if (currentConfigVersion >= expectedConfigVersion) {
            // Config is up to date
            return;
        }
        
        getLogger().info("========================================");
        getLogger().info("[StatusPlugin] Detected config from older version!");
        getLogger().info("[StatusPlugin] Current config version: " + currentConfigVersion);
        getLogger().info("[StatusPlugin] Expected config version: " + expectedConfigVersion);
        getLogger().info("[StatusPlugin] Updating config with new options...");
        getLogger().info("[StatusPlugin] IMPORTANT: Only adding NEW keys, preserving ALL existing settings!");
        
        // Create backup of old config
        File configFile = new File(getDataFolder(), "config.yml");
        File backupFile = new File(getDataFolder(), "config.yml.backup-v" + currentConfigVersion);
        try {
            if (configFile.exists()) {
                java.nio.file.Files.copy(
                    configFile.toPath(),
                    backupFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
                getLogger().info("[StatusPlugin] Created backup: " + backupFile.getName());
            }
        } catch (IOException e) {
            getLogger().warning("[StatusPlugin] Failed to create config backup: " + e.getMessage());
        }
        
        // Load default config from resources
        FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
            new java.io.InputStreamReader(
                getResource("config.yml"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        );
        
        // Track what's been added
        int addedKeys = 0;
        int skippedKeys = 0;
        
        // Add missing keys from default config (ONLY if they don't exist)
        for (String key : defaultConfig.getKeys(true)) {
            // Skip if this is a ConfigurationSection (parent node)
            Object value = defaultConfig.get(key);
            if (value instanceof ConfigurationSection) {
                continue; // Skip section nodes, only process leaf values
            }
            
            if (!config.contains(key)) {
                // Key doesn't exist - ADD IT
                config.set(key, defaultConfig.get(key));
                addedKeys++;
                getLogger().info("[StatusPlugin] + Added: " + key);
            } else {
                // Key exists - SKIP IT (preserve user's setting)
                skippedKeys++;
            }
        }
        
        // Update version numbers (these are always safe to update)
        config.set("config-version", expectedConfigVersion);
        config.set("plugin-version", getDescription().getVersion());
        
        // Save updated config
        saveConfig();
        
        getLogger().info("[StatusPlugin] ✓ Config update complete!");
        getLogger().info("[StatusPlugin] ✓ Added " + addedKeys + " NEW config options");
        getLogger().info("[StatusPlugin] ✓ Preserved " + skippedKeys + " EXISTING settings (not touched)");
        getLogger().info("[StatusPlugin] ✓ Your tab list formats, statuses, and all settings are safe!");
        getLogger().info("[StatusPlugin] Backup saved as: " + backupFile.getName());
        getLogger().info("========================================");
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
        boolean sortByStatus = config.getBoolean("tab-list-sort-by-status", true);
        nametagStatusEnabled = config.getBoolean("nametag-status-enabled", false);
        nametagCleanJoinMessage = config.getBoolean("nametag-clean-join-message", true);
        nametagCleanDeathMessage = config.getBoolean("nametag-clean-death-message", true);

        // Load status options from status-options.yml
        loadStatusOptions();
        loadTabListConfig();

        // Load the default status options
        boolean defaultStatusEnabled = config.getBoolean("default_status_enabled", true);
        String defaultStatus = config.getString("default_status", "DEFAULT");
        if (defaultStatusEnabled) {
            statusOptions.put("DEFAULT", defaultStatus);
        }

        if (nametagManager != null) {
            nametagManager.updateSettings(nametagStatusEnabled, sortByStatus);
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

        if (force) {
            cancelDelayedStatsSave();
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

    private void markServerStatsDirty() {
        if (serverStatsConfig == null) {
            return;
        }
        serverStatsDirty = true;
        scheduleStatsSave();
    }

    private void scheduleStatsSave() {
        if (delayedStatsSaveTask != null) {
            return;
        }
        delayedStatsSaveTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            delayedStatsSaveTask = null;
            persistServerStats(true);
        }, STATS_SAVE_DELAY_TICKS);
    }

    private void cancelDelayedStatsSave() {
        if (delayedStatsSaveTask != null) {
            delayedStatsSaveTask.cancel();
            delayedStatsSaveTask = null;
        }
    }

    private void adjustTotalTrackedDeaths(long delta) {
        if (delta == 0L) {
            return;
        }
        long recalculated = totalTrackedDeaths + delta;
        if (recalculated < 0L) {
            recalculateTotalTrackedDeathsFromMap();
            return;
        }
        totalTrackedDeaths = recalculated;
        if (serverStatsConfig != null) {
            serverStatsConfig.set("total-deaths", totalTrackedDeaths);
            markServerStatsDirty();
        }
    }

    private void recalculateTotalTrackedDeathsFromMap() {
        long total = 0L;
        for (int deaths : playerDeathMap.values()) {
            total += deaths;
        }
        totalTrackedDeaths = Math.max(0L, total);
        if (serverStatsConfig != null) {
            serverStatsConfig.set("total-deaths", totalTrackedDeaths);
            markServerStatsDirty();
        }
    }

    private void syncPlayerDeathsFromStatistic(Player player) {
        if (player == null) {
            return;
        }
        try {
            int vanillaDeaths = player.getStatistic(Statistic.DEATHS);
            UUID uuid = player.getUniqueId();
            int storedDeaths = playerDeathMap.getOrDefault(uuid, 0);
            if (vanillaDeaths == storedDeaths) {
                return;
            }

            boolean statisticUpdated = false;
            try {
                player.setStatistic(Statistic.DEATHS, storedDeaths);
                statisticUpdated = true;
            } catch (IllegalArgumentException ignored) {
                // Some server versions may not allow setting this statistic
            }

            if (!statisticUpdated) {
                playerDeathMap.put(uuid, vanillaDeaths);
                if (playerDeathsConfig != null) {
                    playerDeathsConfig.set(uuid.toString(), vanillaDeaths);
                    savePlayerDeathsConfig();
                }
                adjustTotalTrackedDeaths((long) vanillaDeaths - storedDeaths);
            }
        } catch (NoSuchFieldError ignored) {
            // Statistic not available on this server version
        }
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
    private void updatePlayerTabList() {
        updatePlayerTabList(TabEnvironmentSnapshot.capture(this));
    }

    /**
     * Get the status key from the status value (reverse lookup)
     */
    public String getStatusKeyFromValue(String statusValue) {
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

        // Update team assignments for all players (handles sorting / nametag prefixes)
        if (nametagManager != null && nametagManager.isActive()) {
            nametagManager.updateAllPlayerTeams();
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
        cancelDelayedStatsSave();
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
        cachedMspt = fetchMspt();

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
    
    public String getMspt() {
        if (fastTabRefreshTask == null) {
            cachedMspt = fetchMspt();
        }
        return cachedMspt;
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
            tabListName = ColorParser.parse(tabListName);
            
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

        header = ColorParser.parse(header);
        footer = ColorParser.parse(footer);

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
    
    private String sanitizeDisplayNameOccurrences(String message, Player player) {
        if (message == null || player == null) {
            return message;
        }

        String displayName = player.getDisplayName();
        String realName = player.getName();

        if (displayName != null && !displayName.equals(realName)) {
            message = message.replace(displayName, realName);
        }

        String strippedDisplay = displayName != null ? ChatColor.stripColor(displayName) : null;
        if (strippedDisplay != null && !strippedDisplay.equals(realName)) {
            message = message.replace(strippedDisplay, realName);
        }

        return message;
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
                .replace("%mspt%", snapshot.mspt)
                .replace("%performance%", snapshot.performanceLabel)
                .replace("%performance_label%", snapshot.performanceLabel)
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

    /**
     * Detect and log MiniMessage support for status formatting
     */
    private void detectMiniMessageSupport() {
        getLogger().info("========================================");
        getLogger().info("[StatusPlugin] MiniMessage support DISABLED.");
        getLogger().info("[StatusPlugin] Using legacy & color codes only for all formats.");
        getLogger().info("[StatusPlugin] Update your status-options.yml to remove MiniMessage tags if present.");
        getLogger().info("========================================");
    }
    
    /**
     * Detect and log which TPS/MSPT fetching methods are available
     */
    private void detectTpsFetchingMethod() {
        boolean hasPaperTps = false;
        boolean hasPaperMspt = false;
        
        // Try Paper API first
        try {
            Method getTpsMethod = Bukkit.class.getMethod("getTPS");
            double[] tps = (double[]) getTpsMethod.invoke(null);
            if (tps != null && tps.length >= 3) {
                hasPaperTps = true;
            }
        } catch (NoSuchMethodException e) {
            // Not Paper
        } catch (Exception e) {
            getLogger().warning("Paper getTPS() available but failed: " + e.getMessage());
        }
        
        // Check for MSPT support (Paper only)
        try {
            Method getAverageMsptMethod = Bukkit.class.getMethod("getAverageTickTime");
            Double mspt = (Double) getAverageMsptMethod.invoke(null);
            if (mspt != null) {
                hasPaperMspt = true;
            }
        } catch (NoSuchMethodException e) {
            // Not available
        } catch (Exception e) {
            getLogger().warning("Paper getAverageTickTime() available but failed: " + e.getMessage());
        }
        
        if (hasPaperTps || hasPaperMspt) {
            getLogger().info("========================================");
            getLogger().info("[StatusPlugin] Paper API DETECTED!");
            if (hasPaperTps) {
                getLogger().info("[StatusPlugin] ✓ TPS: Using Paper's native getTPS() method");
            }
            if (hasPaperMspt) {
                getLogger().info("[StatusPlugin] ✓ MSPT: Using Paper's native getAverageTickTime() method");
            }
            getLogger().info("[StatusPlugin] All performance metrics fully supported");
            getLogger().info("========================================");
            return;
        }
        
        // Try Spigot reflection method for TPS
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getServer");
            Object dedicatedServer = method.invoke(Bukkit.getServer());
            Field recentTpsField = dedicatedServer.getClass().getField("recentTps");
            recentTpsField.setAccessible(true);
            double[] values = (double[]) recentTpsField.get(dedicatedServer);
            if (values != null) {
                getLogger().info("========================================");
                getLogger().info("[StatusPlugin] Spigot TPS detection active");
                getLogger().info("[StatusPlugin] ✓ TPS: Using reflection method");
                getLogger().info("[StatusPlugin] ⚠ MSPT: Not available (Paper-only feature)");
                getLogger().info("[StatusPlugin] TPS metrics available, MSPT will show N/A");
                getLogger().info("========================================");
                return;
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            // Failed
        }
        
        getLogger().warning("========================================");
        getLogger().warning("[StatusPlugin] WARNING: Could not detect TPS!");
        getLogger().warning("[StatusPlugin] TPS, MSPT and performance metrics will show as UNKNOWN");
        getLogger().warning("[StatusPlugin] This may indicate an incompatible server version");
        getLogger().warning("========================================");
    }

    private double[] fetchRecentTps() {
        // Try Paper API first (proper way to get TPS on Paper)
        try {
            Method getTpsMethod = Bukkit.class.getMethod("getTPS");
            double[] tps = (double[]) getTpsMethod.invoke(null);
            if (tps != null && tps.length >= 3) {
                return tps.clone();
            }
        } catch (NoSuchMethodException e) {
            // Not Paper, try Spigot reflection method below
        } catch (Exception e) {
            // Silent failure, already logged during detection
        }
        
        // Fallback to Spigot reflection method for pure Spigot servers
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getServer");
            Object dedicatedServer = method.invoke(Bukkit.getServer());
            Field recentTpsField = dedicatedServer.getClass().getField("recentTps");
            recentTpsField.setAccessible(true);
            double[] values = (double[]) recentTpsField.get(dedicatedServer);
            return values != null ? values.clone() : null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            // Silent failure, already logged during detection
            return null;
        }
    }
    
    /**
     * Fetch MSPT (milliseconds per tick) - Paper only feature
     * @return Formatted MSPT string or N/A if not available
     */
    private String fetchMspt() {
        try {
            Method getAverageMsptMethod = Bukkit.class.getMethod("getAverageTickTime");
            Double mspt = (Double) getAverageMsptMethod.invoke(null);
            if (mspt != null && mspt >= 0) {
                return String.format(Locale.US, "%.2f", mspt);
            }
        } catch (NoSuchMethodException e) {
            // Not Paper - MSPT not available
        } catch (Exception e) {
            // Silent failure
        }
        return PLACEHOLDER_NOT_AVAILABLE;
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
        private final String mspt;
        private final String performanceLabel;
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
                                       String mspt,
                                       String performanceLabel,
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
            this.mspt = mspt;
            this.performanceLabel = performanceLabel;
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
                    plugin.cachedMspt,
                    plugin.cachedPerformanceLabel,
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
    
    /**
     * Initialize LibertyBans integration after all plugins have loaded
     */
    private void initializeLibertyBans() {
        org.bukkit.plugin.Plugin libertyBansPlugin = Bukkit.getPluginManager().getPlugin("LibertyBans");
        libertyBansIntegration = null;

        if (libertyBansPlugin == null || !libertyBansPlugin.isEnabled()) {
            isLibertyBansPresent = false;
            return;
        }

        try {
            libertyBansIntegration = LibertyBansIntegration.tryCreate(this);
            if (libertyBansIntegration != null) {
                isLibertyBansPresent = true;
                getLogger().info("========================================");
                getLogger().info("[StatusPlugin] LibertyBans DETECTED!");
                getLogger().info("[StatusPlugin] Mute checking is now ACTIVE");
                getLogger().info("[StatusPlugin] Muted players will be blocked from chat");
                getLogger().info("========================================");
            } else {
                isLibertyBansPresent = false;
                getLogger().warning("[StatusPlugin] LibertyBans is installed but API could not be initialized.");
            }
        } catch (Exception e) {
            isLibertyBansPresent = false;
            getLogger().log(Level.SEVERE, "[StatusPlugin] Failed to initialize LibertyBans API: " + e.getMessage(), e);
        }

        if (!isLibertyBansPresent) {
            libertyBansIntegration = null;
        }
    }
    
    /**
     * Send a mute notification to the player with reason and duration
     * @param player The muted player
     * @param punishment The mute punishment details
     */
    private void sendMuteNotification(Player player, MuteInfo muteInfo) {
        try {
            // Get messages from language config
            String muteTitle = getLanguageText(player, "mute_title", "&c&lYOU ARE MUTED!");
            String muteWithReason = getLanguageText(player, "mute_with_reason", "&7Reason: &f%reason%");
            String muteDurationTemporary = getLanguageText(player, "mute_duration_temporary", "&7Duration: &e%duration%");
            String muteDurationPermanent = getLanguageText(player, "mute_duration_permanent", "&7Duration: &4&lPERMANENT");
            String muteDurationExpiring = getLanguageText(player, "mute_duration_expiring", "&7Duration: &eExpiring soon...");
            
            StringBuilder message = new StringBuilder();
            message.append(ColorParser.parse(muteTitle)).append("\n");
            
            // Add reason
            String reason = muteInfo.getReason();
            if (reason != null && !reason.isEmpty()) {
                String reasonLine = muteWithReason.replace("%reason%", reason);
                message.append(ColorParser.parse(reasonLine)).append("\n");
            }
            
            // Add duration - check for permanent mute (null end date or > 5 years)
            Instant end = muteInfo.getEnd();
            if (end == null) {
                // Permanent mute - null end date means permanent
                message.append(ColorParser.parse(muteDurationPermanent));
            } else {
                // Temporary mute - show remaining time
                Duration remaining = Duration.between(Instant.now(), end);
                
                // If mute is longer than 5 years, treat it as permanent
                long fiveYearsInDays = 365L * 5L;
                if (remaining.toDays() > fiveYearsInDays) {
                    message.append(ColorParser.parse(muteDurationPermanent));
                } else if (remaining.isNegative() || remaining.isZero()) {
                    message.append(ColorParser.parse(muteDurationExpiring));
                } else {
                    String durationLine = muteDurationTemporary.replace("%duration%", formatDuration(remaining));
                    message.append(ColorParser.parse(durationLine));
                }
            }
            
            player.sendMessage(message.toString());
        } catch (Exception e) {
            // Fallback message if something goes wrong
            String fallback = getLanguageText(player, "mute_fallback", "&cYou are currently muted and cannot send messages.");
            player.sendMessage(ColorParser.parse(fallback));
            getLogger().warning("[StatusPlugin] Error sending mute notification: " + e.getMessage());
        }
    }
    
    /**
     * Format a duration into a human-readable string
     * @param duration The duration to format
     * @return Formatted string (e.g., "2d 5h 30m")
     */
    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        
        StringBuilder result = new StringBuilder();
        if (days > 0) result.append(days).append("d ");
        if (hours > 0) result.append(hours).append("h ");
        if (minutes > 0) result.append(minutes).append("m ");
        if (days == 0 && hours == 0 && minutes == 0) result.append(seconds).append("s");
        
        return result.toString().trim();
    }
    
    private static final class MuteInfo {
        private final String reason;
        private final Instant end;

        private MuteInfo(String reason, Instant end) {
            this.reason = reason;
            this.end = end;
        }

        private String getReason() {
            return reason;
        }

        private Instant getEnd() {
            return end;
        }
    }

    private static final class LibertyBansIntegration {
        private final StatusPlugin plugin;
        private final Object libertyBansApi;
        private final Object mutePunishmentType;
        private final Method getSelectorMethod;
        private final Method getReasonMethod;
        private final Method getEndDateMethod;
        private final Class<?> punishmentTypeClass;
        private boolean loggedError;

        private LibertyBansIntegration(StatusPlugin plugin,
                                       Object libertyBansApi,
                                       Object mutePunishmentType,
                                       Method getSelectorMethod,
                                       Method getReasonMethod,
                                       Method getEndDateMethod,
                                       Class<?> punishmentTypeClass) {
            this.plugin = plugin;
            this.libertyBansApi = libertyBansApi;
            this.mutePunishmentType = mutePunishmentType;
            this.getSelectorMethod = getSelectorMethod;
            this.getReasonMethod = getReasonMethod;
            this.getEndDateMethod = getEndDateMethod;
            this.punishmentTypeClass = punishmentTypeClass;
        }

        private static LibertyBansIntegration tryCreate(StatusPlugin plugin) throws Exception {
            Class<?> omnibusProviderClass = Class.forName("space.arim.omnibus.OmnibusProvider");
            Method getOmnibusMethod = omnibusProviderClass.getMethod("getOmnibus");
            Object omnibus = getOmnibusMethod.invoke(null);
            if (omnibus == null) {
                return null;
            }

            Method getRegistryMethod = omnibus.getClass().getMethod("getRegistry");
            Object registry = getRegistryMethod.invoke(omnibus);
            if (registry == null) {
                return null;
            }

            Class<?> libertyBansClass = Class.forName("space.arim.libertybans.api.LibertyBans");
            Method getProviderMethod = registry.getClass().getMethod("getProvider", Class.class);
            Optional<?> libertyBansOptional = (Optional<?>) getProviderMethod.invoke(registry, libertyBansClass);
            Object libertyBansApi = libertyBansOptional.orElse(null);
            if (libertyBansApi == null) {
                return null;
            }

            Method getSelectorMethod = libertyBansClass.getMethod("getSelector");
            Class<?> punishmentTypeClass = Class.forName("space.arim.libertybans.api.PunishmentType");
            Method valueOfMethod = punishmentTypeClass.getMethod("valueOf", String.class);
            Object muteType = valueOfMethod.invoke(null, "MUTE");
            Class<?> punishmentClass = Class.forName("space.arim.libertybans.api.punish.Punishment");
            Method getReasonMethod = punishmentClass.getMethod("getReason");
            Method getEndDateMethod = punishmentClass.getMethod("getEndDate");

            return new LibertyBansIntegration(
                    plugin,
                    libertyBansApi,
                    muteType,
                    getSelectorMethod,
                    getReasonMethod,
                    getEndDateMethod,
                    punishmentTypeClass
            );
        }

        private Optional<MuteInfo> getActiveMute(Player player) {
            if (player == null) {
                return Optional.empty();
            }
            try {
                Object selector = getSelectorMethod.invoke(libertyBansApi);
                if (selector == null) {
                    return Optional.empty();
                }

                InetAddress inetAddress = player.getAddress() != null ? player.getAddress().getAddress() : null;
                if (inetAddress == null) {
                    return Optional.empty();
                }

                Method builderMethod = selector.getClass().getMethod("selectionByApplicabilityBuilder", UUID.class, InetAddress.class);
                Object builder = builderMethod.invoke(selector, player.getUniqueId(), inetAddress);
                if (builder == null) {
                    return Optional.empty();
                }

                Method typeMethod = builder.getClass().getMethod("type", punishmentTypeClass);
                typeMethod.invoke(builder, mutePunishmentType);

                Method buildMethod = builder.getClass().getMethod("build");
                Object selection = buildMethod.invoke(builder);
                if (selection == null) {
                    return Optional.empty();
                }

                Method firstSpecificMethod = selection.getClass().getMethod("getFirstSpecificPunishment");
                Object stageObj = firstSpecificMethod.invoke(selection);
                if (!(stageObj instanceof CompletionStage)) {
                    return Optional.empty();
                }

                @SuppressWarnings("unchecked")
                CompletionStage<Optional<?>> stage = (CompletionStage<Optional<?>>) stageObj;
                Optional<?> optional = stage.toCompletableFuture().join();
                if (optional == null || !optional.isPresent()) {
                    return Optional.empty();
                }

                Object punishment = optional.get();
                String reason = null;
                try {
                    Object reasonObj = getReasonMethod.invoke(punishment);
                    if (reasonObj instanceof String) {
                        reason = (String) reasonObj;
                    }
                } catch (Exception ignored) {
                    // Ignored - reason remains null
                }

                Instant end = null;
                try {
                    Object endObj = getEndDateMethod.invoke(punishment);
                    if (endObj instanceof Instant) {
                        end = (Instant) endObj;
                    }
                } catch (Exception ignored) {
                    // Ignored - end remains null
                }

                return Optional.of(new MuteInfo(reason, end));
            } catch (Exception ex) {
                if (!loggedError) {
                    loggedError = true;
                    plugin.getLogger().log(Level.WARNING, "[StatusPlugin] Error checking LibertyBans mute status: " + ex.getMessage(), ex);
                }
                return Optional.empty();
            }
        }
    }

    private void reloadPlugin() {
        persistServerStats(true);
        stopTabRefreshSchedulers();
        reloadConfig();
        updateConfigIfNeeded();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig();
        loadPlayerDeathsConfig();
        loadServerStatsConfig();
        loadPlayerStatuses();
        loadPlayerDeaths();
        refreshDimensionCache();
        startTabRefreshSchedulers();
        
        if (nametagManager != null) {
            boolean sortEnabled = getConfig().getBoolean("tab-list-sort-by-status", true);
            nametagManager.updateSettings(nametagStatusEnabled, sortEnabled);
            nametagManager.scheduleInitialization();
            nametagManager.updateAllPlayerTeams();
        }
        
        updatePlayerTabList();
    }
}
