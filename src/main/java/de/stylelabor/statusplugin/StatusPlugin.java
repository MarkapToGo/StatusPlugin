package de.stylelabor.statusplugin;

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
        int pluginId = 20901; // <-- Replace with the id of your plugin!
        //noinspection unused
        Metrics metrics = new Metrics(this, pluginId);
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
            updatePlayerTabList();

            // Save the default status to player-status.yml
            playerStatusConfig.set(player.getUniqueId().toString(), defaultStatus);
            savePlayerStatusConfig();
        } else {
            updatePlayerTabList();
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String status = playerStatusMap.getOrDefault(player.getUniqueId(), "");

        // Get the configured chat format from the config, replace placeholders, and apply color codes
        String chatFormat = getConfig().getString("chat-format", "%status% &r[<$$PLAYER$$>] ");
        chatFormat = chatFormat.replace("%status%", status);
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

        if (args.length == 1) {
            String prefix = args[0].toUpperCase();
            for (String option : statusOptions.keySet()) {
                if (option.startsWith(prefix)) {
                    completions.add(option);
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

        if (config.isConfigurationSection("status")) {
            Set<String> keys = Objects.requireNonNull(config.getConfigurationSection("status")).getKeys(false);
            for (String key : keys) {
                statusOptions.put(key.toUpperCase(), config.getString("status." + key));
            }
        }

        // Load the default status options
        boolean defaultStatusEnabled = config.getBoolean("default_status_enabled", true);
        String defaultStatus = config.getString("default_status", "DEFAULT");
        if (defaultStatusEnabled) {
            statusOptions.put("DEFAULT", defaultStatus);
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

        player.setPlayerListName(tabListName);

    }

    private void reloadPlugin() {
        reloadConfig();
        loadConfig();
        loadLanguageConfig();
        loadPlayerStatusConfig();
    }
}