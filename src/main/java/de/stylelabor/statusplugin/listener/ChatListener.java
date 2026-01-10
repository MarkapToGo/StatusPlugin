package de.stylelabor.statusplugin.listener;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.manager.ChatManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Listens to chat events and applies custom formatting using Paper's
 * ChatRenderer.
 * 
 * IMPORTANT: This uses AsyncChatEvent with a custom ChatRenderer.
 * We NEVER cancel the event, allowing other plugins (DiscordSRV, LibertyBans)
 * to process it.
 */
public class ChatListener implements Listener {

    private final StatusPlugin plugin;
    private final ChatManager chatManager;
    private final ConfigManager configManager;

    public ChatListener(@NotNull StatusPlugin plugin,
            @NotNull ChatManager chatManager,
            @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.chatManager = chatManager;
        this.configManager = configManager;
    }

    /**
     * Handle async chat event with custom renderer.
     * 
     * Uses NORMAL priority so other plugins can process the event before and after
     * us.
     * We set the renderer but NEVER cancel the event.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAsyncChat(@NotNull AsyncChatEvent event) {
        if (!chatManager.isEnabled()) {
            return;
        }

        // Set our custom renderer
        // This allows us to format the message without cancelling the event
        event.renderer(chatManager.createRenderer(event.getPlayer()));

        plugin.debug("Applied chat renderer for " + event.getPlayer().getName());
    }
}
