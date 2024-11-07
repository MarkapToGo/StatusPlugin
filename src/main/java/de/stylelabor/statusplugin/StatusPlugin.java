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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class StatusPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final HashMap<UUID, String> playerStatusMap = new HashMap<>();
    private String commandName;
    private String tabListFormat;
    private final HashMap<String, String> statusOptions = new HashMap<>();
    private FileConfiguration languageConfig;
    private FileConfiguration playerStatusConfig;
    private boolean isTabPluginPresent;
    private boolean useOnlyOneLanguage;
    private String defaultLanguage;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig(); // Load player status during plugin startup
        loadPlayerStatuses(); // Load the statuses of all players from player-status.yml
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand(commandName)).setTabCompleter(this);
        int pluginId = 20901;
        //noinspection unused
        Metrics metrics = new Metrics(this, pluginId);

        isTabPluginPresent = Bukkit.getPluginManager().getPlugin("TAB") != null;

        // Register PlaceholderAPI placeholder
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new StatusPlaceholder(this).register();
            getLogger().info("PlaceholderAPI placeholder registered successfully.");
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholder registration skipped.");
        }

        // Check for the latest version
        ModrinthVersionChecker.checkVersion();

    }

    /**
     * Load player statuses from playerStatusConfig and populate playerStatusMap.
     */
    private void loadPlayerStatuses() {
        for (String uuid : playerStatusConfig.getKeys(false)) {
            playerStatusMap.put(UUID.fromString(uuid), playerStatusConfig.getString(uuid));
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        savePlayerStatusConfig(); // Save player status during plugin shutdown
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
            if (args.length == 2) {
                Player targetPlayer = Bukkit.getPlayer(args[0]);
                if (targetPlayer != null) {
                    String status = statusOptions.get(args[1].toUpperCase());
                    if (status != null) {
                        playerStatusMap.put(targetPlayer.getUniqueId(), status);
                        String message = getLanguageText(targetPlayer, "status_set", "&aYour status has been set to: &r%s");
                        targetPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', String.format(message, status)));
                        updatePlayerTabList();

                        // Save the player status to player-status.yml
                        playerStatusConfig.set(targetPlayer.getUniqueId().toString(), status);
                        savePlayerStatusConfig();
                        sender.sendMessage(ChatColor.GREEN + "Status of " + targetPlayer.getName() + " has been set to: " + ChatColor.translateAlternateColorCodes('&', status));
                    } else {
                        sender.sendMessage(ChatColor.RED + "Invalid status option. Use /status-admin <playerName> <status>");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                }
            } else {
                sender.sendMessage(ChatColor.RED + "Usage: /status-admin <playerName> <status>");
            }
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

        // Send hardcoded admin join message if the player has the admin permission and the message is enabled
        if (player.hasPermission("statusplugin.admin") && getConfig().getBoolean("admin-join-message-enabled", false)) {
            String adminJoinMessage = "&aThank you for using this status plugin. When you want to support me, please download my plugin from Modrinth! https://modrinth.com/plugin/statusplugin-like-in-craftattack";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', adminJoinMessage));
        }

        // Check for the latest version and send message to admins
        if (player.hasPermission("statusplugin.admin")) {
            ModrinthVersionChecker.checkVersion();
        }

        // Update tab list if tab styling is enabled
        if (getConfig().getBoolean("tab-styling-enabled", true)) {
            updatePlayerTabList();
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
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
                // Auto-complete player names
                String prefix = args[0].toLowerCase();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(prefix)) {
                        completions.add(player.getName());
                    }
                }
            } else if (args.length == 2) {
                // Auto-complete status options
                String prefix = args[1].toUpperCase();
                for (String option : statusOptions.keySet()) {
                    if (option.startsWith(prefix)) {
                        completions.add(option);
                    }
                }
            }
        }

        return completions;
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        commandName = config.getString("command-name", "status");
        config.getString("chat-format", "&7[&a%status%&7] &r%s");
        tabListFormat = config.getString("tab-list-format", "&7[&a%status%&7] &r%s");
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

    private void savePlayerStatusConfig() {
        File playerStatusFile = new File(getDataFolder(), "player-status.yml");
        try {
            playerStatusConfig.save(playerStatusFile);
        } catch (IOException e) {
            getLogger().info("&c AN ERROR OCCURRED! | savePlayerStatusConfig | IOException e");
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



    private void updatePlayerTabListName(Player player) {
        String status = playerStatusMap.getOrDefault(player.getUniqueId(), "");
        String playerName = player.getName();
        String tabListName;

        if (status.isEmpty()) {
            tabListName = playerName; // No status, so just use the player name
        } else {
            tabListName = tabListFormat.replace("%status%", status).replace("$$PLAYER$$", playerName);
            tabListName = ChatColor.translateAlternateColorCodes('&', tabListName);
        }

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
    }
}