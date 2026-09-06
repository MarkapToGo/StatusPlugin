package de.stylelabor.statusplugin.command;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.manager.DeathTracker;
import de.stylelabor.statusplugin.manager.StatusManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin commands for StatusPlugin.
 * 
 * Usage:
 * - /status-admin set <player> <status> - Set another player's status
 * - /status-admin reload - Reload configuration
 * - /status-admin deaths <player> [view|add|remove|set|reset] [amount] - Manage
 * deaths
 */
@SuppressWarnings("UnstableApiUsage")
public final class StatusAdminCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = Arrays.asList("set", "reload", "deaths", "requests", "help");
    private static final List<String> DEATH_ACTIONS = Arrays.asList("view", "add", "remove", "set", "reset");
    private static final List<String> REQUEST_ACTIONS = Arrays.asList("list", "accept", "deny");

    private final StatusPlugin plugin;
    private final StatusManager statusManager;
    private final DeathTracker deathTracker;
    private final ConfigManager configManager;
    private final de.stylelabor.statusplugin.manager.RequestManager requestManager;

    public StatusAdminCommand(@NotNull StatusPlugin plugin,
            @NotNull StatusManager statusManager,
            @NotNull DeathTracker deathTracker,
            @NotNull ConfigManager configManager,
            @NotNull de.stylelabor.statusplugin.manager.RequestManager requestManager) {
        this.plugin = plugin;
        this.statusManager = statusManager;
        this.deathTracker = deathTracker;
        this.configManager = configManager;
        this.requestManager = requestManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();

        if (!sender.hasPermission("statusplugin.admin") && !sender.hasPermission("statusplugin.reload")) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-no-permission")));
            return;
        }

        if (args.length == 0) {
            if (sender.hasPermission("statusplugin.admin")) {
                showUsage(sender);
            } else {
                sender.sendMessage(plugin.parseMessage("<gray>Usage: <white>/status-admin reload</white>"));
            }
            return;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("reload")) {
            handleReload(sender);
            return;
        }

        if (!sender.hasPermission("statusplugin.admin")) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-no-permission")));
            return;
        }

        switch (subCommand) {
            case "set" -> handleSet(sender, args);
            case "deaths" -> handleDeaths(sender, args);
            case "requests" -> handleRequests(sender, args);
            case "help" -> showUsage(sender);
            default -> showUsage(sender);
        }
    }

    /**
     * Handle /status-admin set <player> <status>
     */
    private void handleSet(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-usage")
                    .replace("<usage>", "/status-admin set <player> <status>")));
            return;
        }

        String targetName = args[1];
        String statusKey = args[2].toUpperCase();

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-status-target-offline")
                    .replace("<target>", targetName)));
            return;
        }

        if (!statusManager.statusExists(statusKey)) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("status-invalid")));
            return;
        }

        statusManager.setStatus(target, statusKey);
        plugin.getTabListManager().updatePlayer(target);
        plugin.getNametagManager().updatePlayer(target);

        Component statusDisplay = statusManager.getStatusDisplay(target);
        String message = configManager.getMessage("admin-status-set");
        Component formatted = plugin.getMiniMessage().deserialize(message,
                Placeholder.unparsed("target", target.getName()),
                Placeholder.component("status", statusDisplay));
        sender.sendMessage(formatted);
    }

    /**
     * Handle /status-admin reload
     */
    private void handleReload(@NotNull CommandSender sender) {
        if (!sender.hasPermission("statusplugin.reload")) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-no-permission")));
            return;
        }

        plugin.reload();
        sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-reload")));
    }

    /**
     * Handle /status-admin deaths <player> [view|add|remove|set|reset] [amount]
     */
    private void handleDeaths(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-usage")
                    .replace("<usage>", "/status-admin deaths <player> [view|add|remove|set|reset] [amount]")));
            return;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-player-not-found")));
            return;
        }

        String action = args.length >= 3 ? args[2].toLowerCase() : "view";

        switch (action) {
            case "view" -> handleDeathsView(sender, target);
            case "add" -> handleDeathsAdd(sender, target, args);
            case "remove" -> handleDeathsRemove(sender, target, args);
            case "set" -> handleDeathsSet(sender, target, args);
            case "reset" -> handleDeathsReset(sender, target);
            default -> sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-usage")
                    .replace("<usage>", "/status-admin deaths <player> [view|add|remove|set|reset] [amount]")));
        }
    }

    private void handleDeathsView(@NotNull CommandSender sender, @NotNull Player target) {
        int deaths = deathTracker.getDeaths(target);
        String message = configManager.getMessage("deaths-view")
                .replace("<target>", target.getName())
                .replace("<deaths>", String.valueOf(deaths));
        sender.sendMessage(plugin.parseMessage(message));
    }

    private void handleDeathsAdd(@NotNull CommandSender sender, @NotNull Player target, @NotNull String[] args) {
        int amount = parseAmount(sender, args, 3);
        if (amount < 0)
            return;

        deathTracker.addDeaths(target, amount);
        int newDeaths = deathTracker.getDeaths(target);
        plugin.getTabListManager().updatePlayer(target);

        String message = configManager.getMessage("deaths-add")
                .replace("<target>", target.getName())
                .replace("<amount>", String.valueOf(amount))
                .replace("<deaths>", String.valueOf(newDeaths));
        sender.sendMessage(plugin.parseMessage(message));
    }

    private void handleDeathsRemove(@NotNull CommandSender sender, @NotNull Player target, @NotNull String[] args) {
        int amount = parseAmount(sender, args, 3);
        if (amount < 0)
            return;

        deathTracker.removeDeaths(target, amount);
        int newDeaths = deathTracker.getDeaths(target);
        plugin.getTabListManager().updatePlayer(target);

        String message = configManager.getMessage("deaths-remove")
                .replace("<target>", target.getName())
                .replace("<amount>", String.valueOf(amount))
                .replace("<deaths>", String.valueOf(newDeaths));
        sender.sendMessage(plugin.parseMessage(message));
    }

    private void handleDeathsSet(@NotNull CommandSender sender, @NotNull Player target, @NotNull String[] args) {
        int amount = parseAmount(sender, args, 3);
        if (amount < 0)
            return;

        deathTracker.setDeaths(target, amount);
        plugin.getTabListManager().updatePlayer(target);

        String message = configManager.getMessage("deaths-set")
                .replace("<target>", target.getName())
                .replace("<deaths>", String.valueOf(amount));
        sender.sendMessage(plugin.parseMessage(message));
    }

    private void handleDeathsReset(@NotNull CommandSender sender, @NotNull Player target) {
        deathTracker.resetDeaths(target);
        plugin.getTabListManager().updatePlayer(target);

        String message = configManager.getMessage("deaths-reset")
                .replace("<target>", target.getName());
        sender.sendMessage(plugin.parseMessage(message));
    }

    /**
     * Parse amount from args, returns -1 on error
     */
    private int parseAmount(@NotNull CommandSender sender, @NotNull String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-number")));
            return -1;
        }

        try {
            int amount = Integer.parseInt(args[index]);
            if (amount < 0) {
                sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-number")));
                return -1;
            }
            return amount;
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-number")));
            return -1;
        }
    }

    /**
     * Handle /status-admin requests [list|accept|deny]
     */
    private void handleRequests(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-usage")
                    .replace("<usage>", "/status-admin requests [list|accept|deny]")));
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "list" -> handleRequestsList(sender);
            case "accept" -> handleRequestsAccept(sender, args);
            case "deny" -> handleRequestsDeny(sender, args);
            default -> sender.sendMessage(plugin.parseMessage(configManager.getMessage("error-invalid-usage")
                    .replace("<usage>", "/status-admin requests [list|accept|deny]")));
        }
    }

    private void handleRequestsList(@NotNull CommandSender sender) {
        var pending = requestManager.getPendingRequests();
        if (pending.isEmpty()) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-list-empty")));
            return;
        }

        String header = configManager.getMessage("admin-requests-list-header")
                .replace("<count>", String.valueOf(pending.size()));
        sender.sendMessage(plugin.parseMessage(header));

        String itemFormat = configManager.getMessage("admin-requests-list-item");
        for (var req : pending) {
            Component statusDisplay = plugin.parseMessage(req.formattedStatus());
            Component formattedItem = plugin.getMiniMessage().deserialize(itemFormat,
                    Placeholder.unparsed("id", req.id()),
                    Placeholder.unparsed("player", req.playerName()),
                    Placeholder.component("status_display", statusDisplay),
                    Placeholder.unparsed("raw", req.rawInput()));

            // Interactive Accept and Deny buttons
            Component acceptButton = plugin.getMiniMessage().deserialize(
                    " <click:suggest_command:'/status-admin requests accept " + req.id() + " '>" +
                    "<hover:show_text:'<green>Click to accept (specify status name)</green>'>" +
                    "<green><bold>[✔ Accept]</bold></green></hover></click>"
            );
            Component denyButton = plugin.getMiniMessage().deserialize(
                    " <click:suggest_command:'/status-admin requests deny " + req.id() + " '>" +
                    "<hover:show_text:'<red>Click to deny (specify reason)</red>'>" +
                    "<red><bold>[✖ Deny]</bold></red></hover></click>"
            );

            sender.sendMessage(formattedItem.append(acceptButton).append(denyButton));
        }
    }

    private void handleRequestsAccept(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-name-required")));
            return;
        }

        String id = args[2].toUpperCase();
        if (args.length < 4 || args[3].isBlank()) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-name-required")));
            return;
        }

        String customKey = args[3].trim().toUpperCase();
        if (!customKey.matches("^[A-Za-z0-9_]+$")) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-name-invalid")));
            return;
        }

        if (statusManager.statusExists(customKey)) {
            String existsMsg = configManager.getMessage("admin-requests-name-exists")
                    .replace("<key>", customKey);
            sender.sendMessage(plugin.parseMessage(existsMsg));
            return;
        }

        String assignedKey = requestManager.acceptRequest(id, customKey);
        if (assignedKey == null) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-not-found")
                    .replace("<id>", id)));
            return;
        }

        String message = configManager.getMessage("admin-requests-accepted")
                .replace("<id>", id)
                .replace("<key>", assignedKey);
        sender.sendMessage(plugin.parseMessage(message));
    }

    private void handleRequestsDeny(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-reason-required")));
            return;
        }

        String id = args[2].toUpperCase();
        if (args.length < 4) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-reason-required")));
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (reason.isBlank()) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-reason-required")));
            return;
        }

        boolean denied = requestManager.denyRequest(id, reason);
        if (!denied) {
            sender.sendMessage(plugin.parseMessage(configManager.getMessage("admin-requests-not-found")
                    .replace("<id>", id)));
            return;
        }

        String message = configManager.getMessage("admin-requests-denied")
                .replace("<id>", id);
        sender.sendMessage(plugin.parseMessage(message));
    }

    /**
     * Show usage help
     */
    private void showUsage(@NotNull CommandSender sender) {
        sender.sendMessage(plugin.parseMessage("<gray>StatusPlugin Admin Commands:"));
        sender.sendMessage(plugin
                .parseMessage("<white>/status-admin set <player> <status></white> <gray>- Set player's status</gray>"));
        sender.sendMessage(
                plugin.parseMessage("<white>/status-admin reload</white> <gray>- Reload configuration</gray>"));
        sender.sendMessage(plugin
                .parseMessage("<white>/status-admin deaths <player> [view|add|remove|set|reset] [amount]</white>"));
        sender.sendMessage(plugin
                .parseMessage("<white>/status-admin requests list</white> <gray>- View suggestions with Accept/Deny buttons</gray>"));
        sender.sendMessage(plugin
                .parseMessage("<white>/status-admin requests accept <id> <name></white> <gray>- Accept suggestion with name</gray>"));
        sender.sendMessage(plugin
                .parseMessage("<white>/status-admin requests deny <id> <reason></white> <gray>- Deny suggestion with reason</gray>"));
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return sender.hasPermission("statusplugin.admin") || sender.hasPermission("statusplugin.reload") || sender.isOp();
    }

    @Override
    @NotNull
    public Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();

        boolean isAdmin = sender.hasPermission("statusplugin.admin") || sender.isOp();
        boolean isReload = sender.hasPermission("statusplugin.reload") || sender.isOp();

        if (!isAdmin && !isReload) {
            return Collections.emptyList();
        }

        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0] : "";
            if (!isAdmin) {
                return filterStartsWith(List.of("reload"), prefix);
            }
            return filterStartsWith(SUBCOMMANDS, prefix);
        }

        if (!isAdmin) {
            return Collections.emptyList();
        }

        String subCommand = args[0].toLowerCase();

        if (args.length == 2) {
            if (subCommand.equals("set") || subCommand.equals("deaths")) {
                return filterStartsWith(getOnlinePlayerNames(), args[1]);
            }
            if (subCommand.equals("requests")) {
                return filterStartsWith(REQUEST_ACTIONS, args[1]);
            }
        }

        if (args.length == 3) {
            if (subCommand.equals("set")) {
                return filterStartsWith(new ArrayList<>(statusManager.getAvailableStatuses()), args[2]);
            }
            if (subCommand.equals("deaths")) {
                return filterStartsWith(DEATH_ACTIONS, args[2]);
            }
            if (subCommand.equals("requests") && (args[1].equalsIgnoreCase("accept") || args[1].equalsIgnoreCase("deny"))) {
                List<String> pendingIds = requestManager.getPendingRequests().stream()
                        .map(de.stylelabor.statusplugin.manager.RequestManager.StatusRequest::id)
                        .collect(Collectors.toList());
                return filterStartsWith(pendingIds, args[2]);
            }
        }

        if (args.length == 4) {
            if (subCommand.equals("deaths")) {
                String action = args[2].toLowerCase();
                if (action.equals("add") || action.equals("remove") || action.equals("set")) {
                    return filterStartsWith(List.of("1", "5", "10", "50", "100"), args[3]);
                }
            }
            if (subCommand.equals("requests")) {
                if (args[1].equalsIgnoreCase("deny")) {
                    return filterStartsWith(List.of("Inappropriate", "Format_error", "Color_rules", "Duplicate"), args[3]);
                }
                if (args[1].equalsIgnoreCase("accept")) {
                    var req = requestManager.getRequest(args[2].toUpperCase());
                    if (req != null) {
                        String clean = de.stylelabor.statusplugin.util.ColorUtil.stripFormatting(req.rawInput())
                                .replaceAll("[^A-Za-z0-9_]", "").toUpperCase();
                        if (!clean.isEmpty()) {
                            return filterStartsWith(List.of(clean), args[3]);
                        }
                    }
                }
            }
        }

        return Collections.emptyList();
    }

    @NotNull
    private List<String> getOnlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    @NotNull
    private List<String> filterStartsWith(@NotNull List<String> list, @NotNull String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(lowerPrefix))
                .collect(Collectors.toList());
    }
}
