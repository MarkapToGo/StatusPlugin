package de.stylelabor.statusplugin.integration;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import space.arim.libertybans.api.LibertyBans;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.punish.Punishment;
import space.arim.libertybans.api.select.PunishmentSelector;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Integration with LibertyBans for custom styled mute notifications.
 * 
 * Note: With proper AsyncChatEvent usage, LibertyBans will automatically block
 * muted players.
 * This integration is for providing prettier mute messages when players try to
 * chat.
 */
public class LibertyBansIntegration {

    private final StatusPlugin plugin;
    private final ConfigManager configManager;

    @Nullable
    private LibertyBans libertyBans;

    public LibertyBansIntegration(@NotNull StatusPlugin plugin, @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;

        try {
            // Try to get LibertyBans instance
            var provider = org.bukkit.Bukkit.getServicesManager()
                    .getRegistration(LibertyBans.class);
            if (provider != null) {
                this.libertyBans = provider.getProvider();
                plugin.debug("LibertyBans API initialized successfully");
            }
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Failed to initialize LibertyBans API: " + e.getMessage());
            this.libertyBans = null;
        }
    }

    /**
     * Check if LibertyBans integration is available
     */
    public boolean isAvailable() {
        return libertyBans != null;
    }

    /**
     * Check if a player is muted and get their active mute punishment
     */
    @NotNull
    public CompletableFuture<Punishment> getActiveMute(@NotNull Player player) {
        if (libertyBans == null) {
            return CompletableFuture.completedFuture(null);
        }

        PunishmentSelector selector = libertyBans.getSelector();
        PlayerVictim victim = PlayerVictim.of(player.getUniqueId());

        return selector.selectionBuilder()
                .type(PunishmentType.MUTE)
                .victim(victim)
                .build()
                .getFirstSpecificPunishment()
                .toCompletableFuture()
                .thenApply(optional -> optional.orElse(null));
    }

    /**
     * Send a styled mute notification to the player
     */
    public void sendMuteNotification(@NotNull Player player, @NotNull Punishment punishment) {
        String reason = punishment.getReason();
        if (reason == null || reason.isEmpty()) {
            reason = "No reason specified";
        }

        String duration = formatDuration(punishment);

        String muteMessage = configManager.getRawMessage("libertybans.mute-notification");
        Component message = plugin.getMiniMessage().deserialize(muteMessage,
                Placeholder.unparsed("reason", reason),
                Placeholder.unparsed("duration", duration));

        player.sendMessage(message);
    }

    /**
     * Format the punishment duration
     */
    @NotNull
    private String formatDuration(@NotNull Punishment punishment) {
        Instant end = punishment.getEndDate();

        if (end.equals(Instant.MAX)) {
            return "Permanent";
        }

        Duration remaining = Duration.between(Instant.now(), end);

        if (remaining.isNegative()) {
            return "Expired";
        }

        long days = remaining.toDays();
        long hours = remaining.toHoursPart();
        long minutes = remaining.toMinutesPart();

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        sb.append(minutes).append("m");

        return sb.toString().trim();
    }
}
