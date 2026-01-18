package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manages chat formatting using Paper's modern ChatRenderer system.
 * This ensures compatibility with other plugins like DiscordSRV and
 * LibertyBans.
 */
public class ChatManager {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE);

    private final ConfigManager configManager;
    private final StatusManager statusManager;
    private final DeathTracker deathTracker;
    private final CountryManager countryManager;
    private final MiniMessage miniMessage;

    private String chatFormat;
    private boolean clickableUrls;
    private String urlStyle;
    private String urlHover;

    private boolean statusColorsEnabled;
    private final Map<String, String> statusColors = new HashMap<>();

    private boolean nameColorsEnabled;
    private final Map<String, String> nameColors = new HashMap<>();

    public ChatManager(@NotNull StatusPlugin plugin,
            @NotNull ConfigManager configManager,
            @NotNull StatusManager statusManager,
            @NotNull DeathTracker deathTracker,
            @NotNull CountryManager countryManager) {
        this.configManager = configManager;
        this.statusManager = statusManager;
        this.deathTracker = deathTracker;
        this.countryManager = countryManager;
        this.miniMessage = plugin.getMiniMessage();
        loadConfig();
    }

    /**
     * Load configuration
     */
    private void loadConfig() {
        var config = configManager.getConfig();
        String rawChatFormat = config.getString("chat.format",
                "<status> <gray><player></gray> <dark_gray>»</dark_gray> <white><message></white>");
        chatFormat = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(rawChatFormat);

        clickableUrls = config.getBoolean("chat.clickable-urls", true);

        String rawUrlStyle = config.getString("chat.url-style", "<aqua><u>");
        urlStyle = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(rawUrlStyle);

        String rawUrlHover = config.getString("chat.url-hover", "<gray>Click to open URL");
        urlHover = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(rawUrlHover);

        statusColorsEnabled = config.getBoolean("chat.status-colors.enabled", false);
        statusColors.clear();
        ConfigurationSection colors = config.getConfigurationSection("chat.status-colors.colors");
        if (colors != null) {
            for (String key : colors.getKeys(false)) {
                String color = colors.getString(key);
                if (color != null) {
                    statusColors.put(key.toUpperCase(),
                            de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(color));
                }
            }
        }

        nameColorsEnabled = config.getBoolean("chat.name-colors.enabled", false);
        nameColors.clear();
        ConfigurationSection nameSection = config.getConfigurationSection("chat.name-colors.colors");
        if (nameSection != null) {
            for (String key : nameSection.getKeys(false)) {
                String color = nameSection.getString(key);
                if (color != null) {
                    nameColors.put(key.toUpperCase(),
                            de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(color));
                }
            }
        }
    }

    /**
     * Check if chat formatting is enabled
     */
    public boolean isEnabled() {
        return configManager.getConfig().getBoolean("chat.enabled", true);
    }

    /**
     * Create a ChatRenderer for the given player
     */
    @NotNull
    public ChatRenderer createRenderer(@NotNull Player player) {
        return new StatusChatRenderer(player);
    }

    /**
     * Custom ChatRenderer implementation that formats chat messages
     */
    private class StatusChatRenderer implements ChatRenderer {

        private final Player player;
        private final String statusFormat;
        private final int deaths;
        private final String country;
        private final String countryCode;

        public StatusChatRenderer(@NotNull Player player) {
            this.player = player;
            this.statusFormat = statusManager.getStatusFormat(player.getUniqueId());
            this.deaths = deathTracker.getDeaths(player.getUniqueId());
            this.country = countryManager.getCountry(player.getUniqueId()).orElse("");
            this.countryCode = countryManager.getCountryCode(player.getUniqueId()).orElse("");
        }

        @Override
        @NotNull
        public Component render(@NotNull Player source, @NotNull Component sourceDisplayName,
                @NotNull Component message, @NotNull Audience viewer) {
            // Apply clickable URLs to the message
            Component processedMessage = message;
            if (clickableUrls) {
                processedMessage = makeUrlsClickable(message);
            }

            // Apply status color if enabled and configured for this status
            if (statusColorsEnabled) {
                String rawStatus = statusManager.getStatus(player);
                if (rawStatus != null) {
                    String color = statusColors.get(rawStatus.toUpperCase());
                    if (color != null) {
                        processedMessage = miniMessage.deserialize(color).append(processedMessage);
                    }
                }
            }

            // Build tag resolvers for placeholders
            TagResolver.Builder resolvers = TagResolver.builder();

            // Status placeholder - parse as MiniMessage
            if (!statusFormat.isEmpty()) {
                resolvers.resolver(Placeholder.component("status", miniMessage.deserialize(statusFormat)));
            } else {
                resolvers.resolver(Placeholder.component("status", Component.empty()));
            }

            // Player name
            Component playerName = sourceDisplayName;
            if (nameColorsEnabled) {
                String rawStatus = statusManager.getStatus(player);
                if (rawStatus != null) {
                    String color = nameColors.get(rawStatus.toUpperCase());
                    if (color != null) {
                        playerName = miniMessage.deserialize(color).append(playerName);
                    }
                }
            }
            resolvers.resolver(Placeholder.component("player", playerName));

            // Message
            resolvers.resolver(Placeholder.component("message", processedMessage));

            // Deaths
            resolvers.resolver(Placeholder.unparsed("deaths", String.valueOf(deaths)));
            // Formatted deaths: [☠ N]
            resolvers.resolver(Placeholder.component("deaths_formatted",
                    miniMessage.deserialize("<dark_gray>[</dark_gray><red>☠</red> <red>" + deaths
                            + "</red><dark_gray>]</dark_gray>")));

            // Country info
            resolvers.resolver(Placeholder.unparsed("country", country));
            resolvers.resolver(Placeholder.unparsed("countrycode", countryCode));

            // Parse the chat format with placeholders
            // 1. Parse config placeholders (status, death etc) -> MiniMessage
            // 2. Parse PAPI placeholders -> Legacy colors
            // 3. Convert Legacy -> MiniMessage
            // 4. Deserialize all

            // Note: We need to be careful here.
            // The chatFormat contains <status>, <player>, etc.
            // PAPI placeholders are likely in the config string itself, e.g. "<status>
            // %simplenick% ..."

            // First, let's substitute our internal placeholders using MiniMessage as before
            // BUT, if we want PAPI to work on the format string, we should do it first?
            // If PAPI returns a legacy color code &c, MiniMessage won't parse it unless we
            // convert it.

            String formatWithPapi = de.stylelabor.statusplugin.util.PlaceholderUtil.parse(player, chatFormat);
            formatWithPapi = de.stylelabor.statusplugin.util.ColorUtil.convertLegacyToMiniMessage(formatWithPapi);

            return miniMessage.deserialize(formatWithPapi, resolvers.build());
        }
    }

    /**
     * Make URLs in a component clickable
     */
    @NotNull
    private Component makeUrlsClickable(@NotNull Component message) {
        TextReplacementConfig config = TextReplacementConfig.builder()
                .match(URL_PATTERN)
                .replacement((result, builder) -> {
                    String url = result.group(1);

                    // Base component with URL text
                    Component urlComponent = Component.text(url);

                    // Apply style from config (MiniMessage)
                    if (!urlStyle.isEmpty()) {
                        // Apply style by wrapping content
                        urlComponent = miniMessage.deserialize(urlStyle + url);
                    } else {
                        // Default fallback if empty
                        urlComponent = urlComponent.color(NamedTextColor.AQUA);
                    }

                    // Add click event
                    urlComponent = urlComponent.clickEvent(ClickEvent.openUrl(url));

                    // Add hover event if configured
                    if (urlHover != null && !urlHover.isEmpty()) {
                        urlComponent = urlComponent.hoverEvent(miniMessage.deserialize(urlHover));
                    }

                    return urlComponent;
                })
                .build();

        return message.replaceText(config);
    }

    /**
     * Reload configuration
     */
    public void reload() {
        loadConfig();
    }
}
