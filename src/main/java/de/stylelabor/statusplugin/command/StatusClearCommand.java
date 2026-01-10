package de.stylelabor.statusplugin.command;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.manager.StatusManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Command for clearing player status: /status-clear
 */
@SuppressWarnings("UnstableApiUsage")
public class StatusClearCommand implements BasicCommand {

    private final StatusPlugin plugin;
    private final StatusManager statusManager;
    private final ConfigManager configManager;

    public StatusClearCommand(@NotNull StatusPlugin plugin,
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

        // Clear status
        statusManager.clearStatus(player);

        // Update tab list and nametag
        plugin.getTabListManager().updatePlayer(player);
        plugin.getNametagManager().updatePlayer(player);

        // Send confirmation
        player.sendMessage(plugin.parseMessage(configManager.getMessage("status-cleared")));
    }
}
