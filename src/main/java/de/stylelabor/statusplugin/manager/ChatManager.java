package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.util.ColorUtil;
import de.stylelabor.statusplugin.util.PlaceholderUtil;
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
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manages chat formatting using Paper's modern ChatRenderer system.
 * This ensures compatibility with other plugins like DiscordSRV and
 * LibertyBans.
 */
public final class ChatManager {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE);

    private final ConfigManager configManager;
    private final StatusManager statusManager;
    private final DeathTracker deathTracker;
    private final CountryManager countryManager;
    private final MiniMessage miniMessage;

    // Read by the async chat threads, replaced as a whole on reload
    private volatile boolean enabled;
    private volatile String chatFormat;
    private volatile boolean clickableUrls;
    private volatile String urlStyle;
    private volatile @Nullable Component urlHover;
    private volatile TextReplacementConfig urlReplacement;

    private volatile boolean statusColorsEnabled;
    private volatile Map<String, Component> statusColors = Map.of();

    private volatile boolean nameColorsEnabled;
    private volatile Map<String, Component> nameColors = Map.of();

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
        enabled = config.getBoolean("chat.enabled", true);

        String rawChatFormat = config.getString("chat.format",
                "<status> <gray><player></gray> <dark_gray>»</dark_gray> <white><message></white>");
        chatFormat = ColorUtil.convertLegacyToMiniMessage(rawChatFormat);

        clickableUrls = config.getBoolean("chat.clickable-urls", true);

        String rawUrlStyle = config.getString("chat.url-style", "<aqua><u>");
        urlStyle = ColorUtil.convertLegacyToMiniMessage(rawUrlStyle);

        String rawUrlHover = config.getString("chat.url-hover", "<gray>Click to open URL");
        String hover = ColorUtil.convertLegacyToMiniMessage(rawUrlHover);
        urlHover = hover.isEmpty() ? null : miniMessage.deserialize(hover);
        urlReplacement = createUrlReplacement();

        statusColorsEnabled = config.getBoolean("chat.status-colors.enabled", false);
        statusColors = loadColors(config.getConfigurationSection("chat.status-colors.colors"));

        nameColorsEnabled = config.getBoolean("chat.name-colors.enabled", false);
        nameColors = loadColors(config.getConfigurationSection("chat.name-colors.colors"));
    }

    /**
     * Parse a status -> color section once, so chat messages reuse the components
     */
    @NotNull
    private Map<String, Component> loadColors(@Nullable ConfigurationSection section) {
        Map<String, Component> colors = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String color = section.getString(key);
                if (color != null) {
                    colors.put(key.toUpperCase(), miniMessage.deserialize(ColorUtil.convertLegacyToMiniMessage(color)));
                }
            }
        }
        return Map.copyOf(colors);
    }

    /**
     * Check if chat formatting is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Create a ChatRenderer for the given player
     */
    @NotNull
    public ChatRenderer createRenderer(@NotNull Player player) {
        // The output is the same for every viewer, so Paper renders the message once
        // instead of once per online player
        return ChatRenderer.viewerUnaware(new StatusChatRenderer(player));
    }

    /**
     * Custom ChatRenderer implementation that formats chat messages
     */
    private class StatusChatRenderer implements ChatRenderer.ViewerUnaware {

        private final Player player;
        private final Component statusDisplay;
        private final int deaths;
        private final String country;
        private final String countryCode;

        public StatusChatRenderer(@NotNull Player player) {
            this.player = player;
            this.statusDisplay = statusManager.getStatusDisplay(player.getUniqueId());
            this.deaths = deathTracker.getDeaths(player.getUniqueId());
            this.country = countryManager.getCountry(player.getUniqueId()).orElse("");
            this.countryCode = countryManager.getCountryCode(player.getUniqueId()).orElse("");
        }

        @Override
        @NotNull
        public Component render(@NotNull Player source, @NotNull Component sourceDisplayName,
                @NotNull Component message) {
            // Apply clickable URLs to the message
            Component processedMessage = message;
            if (clickableUrls) {
                processedMessage = message.replaceText(urlReplacement);
            }

            // Apply status color if enabled and configured for this status
            if (statusColorsEnabled) {
                String rawStatus = statusManager.getStatus(player);
                if (rawStatus != null) {
                    Component color = statusColors.get(rawStatus.toUpperCase());
                    if (color != null) {
                        processedMessage = color.append(processedMessage);
                    }
                }
            }

            // Build tag resolvers for placeholders
            TagResolver.Builder resolvers = TagResolver.builder();

            // Status placeholder
            resolvers.resolver(Placeholder.component("status", statusDisplay));

            // Player name
            Component playerName = sourceDisplayName;
            if (nameColorsEnabled) {
                String rawStatus = statusManager.getStatus(player);
                if (rawStatus != null) {
                    Component color = nameColors.get(rawStatus.toUpperCase());
                    if (color != null) {
                        playerName = color.append(playerName);
                    }
                }
            }
            resolvers.resolver(Placeholder.component("player", playerName));

            // Message
            resolvers.resolver(Placeholder.component("message", processedMessage));

            // Deaths
            resolvers.resolver(Placeholder.unparsed("deaths", String.valueOf(deaths)));
            // Formatted deaths: [☠ N]
            resolvers.resolver(Placeholder.component("deaths_formatted", deathTracker.formatDeaths(deaths)));

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

            String formatWithPapi = PlaceholderUtil.parse(player, chatFormat);
            formatWithPapi = ColorUtil.convertLegacyToMiniMessage(formatWithPapi);

            return miniMessage.deserialize(formatWithPapi, resolvers.build());
        }
    }

    /**
     * Build the replacement that makes URLs in a component clickable
     */
    @NotNull
    private TextReplacementConfig createUrlReplacement() {
        String style = urlStyle;
        Component hover = urlHover;
        return TextReplacementConfig.builder()
                .match(URL_PATTERN)
                .replacement((result, builder) -> {
                    String url = result.group(1);

                    // Base component with URL text
                    Component urlComponent = Component.text(url);

                    // Apply style from config (MiniMessage) safely without parsing tags in raw url
                    if (!style.isEmpty()) {
                        TagResolver resolver = TagResolver.resolver(Placeholder.component("content", Component.text(url)));
                        urlComponent = miniMessage.deserialize(style + "<content>", resolver);
                    } else {
                        // Default fallback if empty
                        urlComponent = urlComponent.color(NamedTextColor.AQUA);
                    }

                    // Add click event
                    urlComponent = urlComponent.clickEvent(ClickEvent.openUrl(url));

                    // Add hover event if configured
                    if (hover != null) {
                        urlComponent = urlComponent.hoverEvent(hover);
                    }

                    return urlComponent;
                })
                .build();
    }

    /**
     * Reload configuration
     */
    public void reload() {
        loadConfig();
    }
}
