package de.stylelabor.statusplugin.command;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.manager.StatusManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Command for setting player status: /status <option>
 * Running without args shows current status
 */
@SuppressWarnings("UnstableApiUsage")
public class StatusCommand implements BasicCommand {

    private final StatusPlugin plugin;
    private final StatusManager statusManager;
    private final ConfigManager configManager;

    public StatusCommand(@NotNull StatusPlugin plugin,
            @NotNull StatusManager statusManager,
            @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.statusManager = statusManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (!(stack.getSender() instanceof Player player)) {
            stack.getSender().sendMessage(plugin.parseMessage(configManager.getMessage("error-player-only")));
            return;
        }

        // If no args, show current status
        if (args.length == 0) {
            showCurrentStatus(player);
            return;
        }

        String statusKey = args[0].toUpperCase();

        // Check if status exists
        if (!statusManager.statusExists(statusKey)) {
            player.sendMessage(plugin.parseMessage(configManager.getMessage("status-invalid")));
            return;
        }

        // Check permission
        if (!statusManager.hasPermission(player, statusKey)) {
            player.sendMessage(plugin.parseMessage(configManager.getMessage("status-no-permission")));
            return;
        }

        // Set status
        statusManager.setStatus(player, statusKey);

        // Update tab list and nametag
        plugin.getTabListManager().updatePlayer(player);
        plugin.getNametagManager().updatePlayer(player);

        // Send confirmation
        Component statusDisplay = statusManager.getStatusDisplay(player);
        String message = configManager.getMessage("status-set");
        Component formatted = plugin.getMiniMessage().deserialize(message,
                Placeholder.component("status", statusDisplay));
        player.sendMessage(formatted);
    }

    /**
     * Show the player's current status
     */
    private void showCurrentStatus(@NotNull Player player) {
        String currentStatus = statusManager.getStatus(player);
        
        if (currentStatus == null || currentStatus.isEmpty()) {
            player.sendMessage(plugin.parseMessage(configManager.getMessage("status-current-none")));
        } else {
            Component statusDisplay = statusManager.getStatusDisplay(player);
            String message = configManager.getMessage("status-current");
            Component formatted = plugin.getMiniMessage().deserialize(message,
                    Placeholder.component("status", statusDisplay),
                    Placeholder.unparsed("status_key", currentStatus));
            player.sendMessage(formatted);
        }
    }

    @Override
    @NotNull
    public Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (!(stack.getSender() instanceof Player player)) {
            return Collections.emptyList();
        }

        // Show all statuses when no args or typing first arg
        if (args.length <= 1) {
            String input = args.length == 1 ? args[0].toUpperCase() : "";
            return statusManager.getAvailableStatuses(player).stream()
                    .filter(status -> status.toUpperCase().startsWith(input))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
