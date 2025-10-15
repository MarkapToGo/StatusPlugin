package de.stylelabor.statusplugin;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class StatusPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final HashMap<UUID, String> playerStatusMap = new HashMap<>();
    private final HashMap<UUID, Integer> playerDeathMap = new HashMap<>();
    private String commandName;
    private String tabListFormat;
    private final HashMap<String, String> statusOptions = new HashMap<>();
    private FileConfiguration languageConfig;
    private FileConfiguration playerStatusConfig;
    private FileConfiguration playerDeathsConfig;
    private boolean isTabPluginPresent;
    private boolean useOnlyOneLanguage;
    private String defaultLanguage;
    private boolean isDiscordSrvPresent;
    private CountryLocationManager countryLocationManager;
    private static final ThreadLocal<Boolean> relayingToDiscord = ThreadLocal.withInitial(() -> false);

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig(); // Load player status during plugin startup
        loadPlayerDeathsConfig(); // Load player deaths during plugin startup
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
            playerDeathMap.put(UUID.fromString(uuid), playerDeathsConfig.getInt(uuid, 0));
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        savePlayerStatusConfig(); // Save player status during plugin shutdown
        savePlayerDeathsConfig(); // Save player deaths during plugin shutdown
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
                String status = statusOptions.get(args[0].toUpperCase());
                if (status != null) {
                    playerStatusMap.put(player.getUniqueId(), status);
                    String message = getLanguageText(player, "status_set", "&aYour status has been set to: &r%s");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', String.format(message, status)));
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
                        updatePlayerTabListName(refreshedOnlineTarget);
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

            playerStatusMap.put(targetPlayer.getUniqueId(), status);
            String message = getLanguageText(targetPlayer, "status_set", "&aYour status has been set to: &r%s");
            targetPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', String.format(message, status)));
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
                        Bukkit.getScheduler().runTask(this, this::updatePlayerTabList);
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
        if (getConfig().getBoolean("tab-styling-enabled", true)) {
            updatePlayerTabListName(player);
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
        
        chatFormat = chatFormat.replace("$$PLAYER$$", player.getName());
        chatFormat = ChatColor.translateAlternateColorCodes('&', chatFormat);

        // Create a TextComponent for the formatted message
        BaseComponent[] statusComponent = TextComponent.fromLegacyText(chatFormat);
        BaseComponent[] messageComponent = TextComponent.fromLegacyText(event.getMessage());

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
                String prefix = args[0].toUpperCase();
                for (String option : statusOptions.keySet()) {
                    if (option.startsWith(prefix)) {
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

        // Load status options from status-options.yml
        loadStatusOptions();

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

    private void updatePlayerTabList() {
        if (!getConfig().getBoolean("tab-styling-enabled", true)) {
            return; // Skip tab styling if disabled
        }

        // Get a list of all online players
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());

        // Update the tab list for each player in the sorted order
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            String invisiblePrefix = ChatColor.COLOR_CHAR + "" + (char)('a' + i);
            player.setDisplayName(invisiblePrefix + player.getName());
            updatePlayerTabListName(player);
        }
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



    private void updatePlayerTabListName(Player player) {
        String status = playerStatusMap.getOrDefault(player.getUniqueId(), "");
        String playerName = player.getName();
        String tabListName;

        if (status.isEmpty()) {
            // Use a format for no status that still includes country code
            String noStatusFormat = getConfig().getString("tab-list-format-no-status", "&7[&e%countrycode%&7] &r$$PLAYER$$");
            tabListName = noStatusFormat.replace("$$PLAYER$$", playerName);
        } else {
            tabListName = tabListFormat.replace("%status%", status).replace("$$PLAYER$$", playerName);
        }
        
        // Replace country placeholders for both cases (only if country location is enabled)
        if (getConfig().getBoolean("country-location-enabled", false) && 
            countryLocationManager != null) {
            CountryLocationManager.CountryData countryData = countryLocationManager.getPlayerCountry(player.getUniqueId());
            if (countryData != null) {
                tabListName = tabListName.replace("%country%", countryData.getCountry());
                tabListName = tabListName.replace("%countrycode%", countryData.getCountryCode());
            } else {
                tabListName = tabListName.replace("%country%", "");
                tabListName = tabListName.replace("%countrycode%", "");
            }
        } else {
            tabListName = tabListName.replace("%country%", "");
            tabListName = tabListName.replace("%countrycode%", "");
        }

        tabListName = tabListName.replace("%deaths%", String.valueOf(getPlayerDeaths(player.getUniqueId())));
        
        tabListName = ChatColor.translateAlternateColorCodes('&', tabListName);

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
    }




    private void reloadPlugin() {
        reloadConfig();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig();
        loadPlayerDeathsConfig();
        loadPlayerStatuses();
        loadPlayerDeaths();
        updatePlayerTabList();
    }
}
