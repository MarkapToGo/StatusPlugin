package de.stylelabor.statusplugin.command;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.manager.RequestManager;
import de.stylelabor.statusplugin.util.ColorUtil;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for players to suggest custom statuses: /status-suggest <status> or /status-suggest info
 */
@SuppressWarnings("UnstableApiUsage")
public class StatusSuggestCommand implements BasicCommand {

    private final StatusPlugin plugin;
    private final RequestManager requestManager;
    private final ConfigManager configManager;

    public StatusSuggestCommand(@NotNull StatusPlugin plugin,
                                @NotNull RequestManager requestManager,
                                @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.requestManager = requestManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-player-only")));
            return;
        }

        boolean enabled = configManager.getConfig().getBoolean("requests.enabled", true);
        if (!enabled) {
            player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-disabled")));
            return;
        }

        if (args.length == 0) {
            player.sendMessage(plugin.parseMessage("<gray>Usage: <yellow>/status-suggest <status></yellow> or <yellow>/status-suggest info</yellow></gray>"));
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            showInfo(player);
            return;
        }

        // Combine arguments to allow spaces
        String rawInput = String.join(" ", args).trim();

        // Check per-player pending limit
        int maxSubmits = configManager.getConfig().getInt("requests.max-submits-per-player", 3);
        int currentPending = requestManager.getPendingCount(player.getUniqueId());
        if (maxSubmits > 0 && currentPending >= maxSubmits) {
            String limitMsg = configManager.getMessage("requests-limit-reached")
                    .replace("<max>", String.valueOf(maxSubmits));
            player.sendMessage(plugin.parseMessage(limitMsg));
            return;
        }

        // Validate format syntax (MiniMessage / Legacy)
        if (!ColorUtil.isValidFormat(rawInput)) {
            player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-invalid-format")));
            return;
        }

        // Check corner brackets requirement if configured
        boolean requireBrackets = configManager.getConfig().getBoolean("requests.require-brackets", true);
        if (requireBrackets && !ColorUtil.hasCornerBrackets(rawInput)) {
            player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-brackets-required")));
            return;
        }

        // Validate length on stripped text
        String stripped = ColorUtil.stripFormatting(rawInput).trim();
        int minLength = configManager.getConfig().getInt("requests.min-length", 3);
        int maxLength = configManager.getConfig().getInt("requests.max-length", 24);
        if (stripped.length() < minLength || stripped.length() > maxLength) {
            String lengthMsg = configManager.getMessage("requests-length-invalid")
                    .replace("<min>", String.valueOf(minLength))
                    .replace("<max>", String.valueOf(maxLength));
            player.sendMessage(plugin.parseMessage(lengthMsg));
            return;
        }

        // Submit the request
        RequestManager.StatusRequest request = requestManager.submitRequest(player, rawInput);
        Component statusDisplay = plugin.parseMessage(request.formattedStatus());

        String message = configManager.getMessage("requests-submitted");
        Component formatted = plugin.getMiniMessage().deserialize(message,
                Placeholder.unparsed("id", request.id()),
                Placeholder.component("status_display", statusDisplay));
        player.sendMessage(formatted);
    }

    private void showInfo(@NotNull Player player) {
        boolean requireBrackets = configManager.getConfig().getBoolean("requests.require-brackets", true);
        int maxSubmits = configManager.getConfig().getInt("requests.max-submits-per-player", 3);
        int currentPending = requestManager.getPendingCount(player.getUniqueId());

        player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-info-header")));
        player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-info-format")));
        player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-info-example-minimessage")));
        player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-info-example-legacy")));

        String bracketsRule = requireBrackets ? "<green>Yes</green>" : "<yellow>No</yellow>";
        String bracketsMsg = configManager.getMessage("requests-info-brackets")
                .replace("<brackets_required>", bracketsRule);
        player.sendMessage(plugin.parseMessage(bracketsMsg));

        String generatorUrl = configManager.getConfig().getString("requests.generator-website", "https://www.birdflop.com/resources/rgb/");
        if (generatorUrl != null && !generatorUrl.trim().isEmpty()) {
            String genMsg = configManager.getMessage("requests-info-generator")
                    .replace("<url>", generatorUrl.trim());
            player.sendMessage(plugin.parseMessage(genMsg));
        }

        String maxStr = maxSubmits > 0 ? String.valueOf(maxSubmits) : "Unlimited";
        String limitsMsg = configManager.getMessage("requests-info-limits")
                .replace("<pending>", String.valueOf(currentPending))
                .replace("<max>", maxStr);
        player.sendMessage(plugin.parseMessage(limitsMsg));

        player.sendMessage(plugin.parseMessage(configManager.getMessage("requests-info-command")));
    }

    @Override
    @NotNull
    public Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args == null || args.length == 0) {
            return List.of("info");
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if ("info".startsWith(prefix)) {
                return List.of("info");
            }
        }
        return Collections.emptyList();
    }
}
