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
 * Command for previewing a status in chat: /status-preview <status>
 * Shows how a status would look in chat without setting it
 */
@SuppressWarnings("UnstableApiUsage")
public class StatusPreviewCommand implements BasicCommand {

    private final StatusPlugin plugin;
    private final StatusManager statusManager;
    private final ConfigManager configManager;

    public StatusPreviewCommand(@NotNull StatusPlugin plugin,
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

        if (args.length == 0) {
            player.sendMessage(plugin.parseMessage("<red>Usage: /status-preview <status></red>"));
            return;
        }

        String statusKey = args[0].toUpperCase();

        // Check if status exists
        if (!statusManager.statusExists(statusKey)) {
            player.sendMessage(plugin.parseMessage(configManager.getMessage("status-invalid")));
            return;
        }

        // Get status format and display it
        String statusFormat = statusManager.getStatusFormatByKey(statusKey);
        Component statusDisplay = plugin.parseMessage(statusFormat);

        // Get chat format from config
        String chatFormat = configManager.getConfig().getString("chat.format",
                "<status> <gray><player></gray>: <white><message></white>");

        // Build a preview message
        String previewMessage = chatFormat
                .replace("<player>", player.getName())
                .replace("<message>", "This is a preview message!");

        // Replace status placeholder
        Component preview = plugin.getMiniMessage().deserialize(previewMessage,
                Placeholder.component("status", statusDisplay),
                Placeholder.unparsed("deaths", "0"),
                Placeholder.unparsed("country", ""),
                Placeholder.unparsed("countrycode", ""));

        // Send preview header
        player.sendMessage(plugin.parseMessage("<gray>─────────────────────────────────</gray>"));
        player.sendMessage(plugin.parseMessage("<yellow>Preview of status:</yellow> " + statusFormat));
        player.sendMessage(plugin.parseMessage("<gray>─────────────────────────────────</gray>"));
        player.sendMessage(preview);
        player.sendMessage(plugin.parseMessage("<gray>─────────────────────────────────</gray>"));
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
