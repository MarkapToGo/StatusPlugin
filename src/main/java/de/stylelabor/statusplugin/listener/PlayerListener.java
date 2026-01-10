package de.stylelabor.statusplugin.listener;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.manager.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Handles player join, quit, and death events.
 */
public class PlayerListener implements Listener {

    private final StatusPlugin plugin;
    private final StatusManager statusManager;
    private final TabListManager tabListManager;
    private final NametagManager nametagManager;
    private final DeathTracker deathTracker;
    private final CountryManager countryManager;
    private final ConfigManager configManager;

    public PlayerListener(@NotNull StatusPlugin plugin,
            @NotNull StatusManager statusManager,
            @NotNull TabListManager tabListManager,
            @NotNull NametagManager nametagManager,
            @NotNull DeathTracker deathTracker,
            @NotNull CountryManager countryManager,
            @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.statusManager = statusManager;
        this.tabListManager = tabListManager;
        this.nametagManager = nametagManager;
        this.deathTracker = deathTracker;
        this.countryManager = countryManager;
        this.configManager = configManager;
    }

    /**
     * Handle player join
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Assign default status if configured
        statusManager.assignDefaultStatus(player);

        // Sync deaths with vanilla statistic
        deathTracker.syncWithVanilla(player);

        // Fetch country data asynchronously
        if (countryManager.isEnabled()) {
            countryManager.fetchCountry(player).thenAccept(data -> {
                if (data != null) {
                    // Update tab list after country data is fetched
                    tabListManager.updatePlayer(player);
                }
            });
        }

        // Update tab list for this player
        tabListManager.updatePlayer(player);

        // Update nametag
        nametagManager.updatePlayer(player);

        // Update tab list for all other players (so they see this player correctly)
        tabListManager.updateAllPlayers();

        plugin.debug(player.getName() + " joined - status: " +
                statusManager.getStatus(player) + ", deaths: " + deathTracker.getDeaths(player));

        // Notify admins about updates
        if ((player.isOp() || player.hasPermission("statusplugin.admin")) &&
                de.stylelabor.statusplugin.util.VersionChecker.isUpdateAvailable()) {

            String latest = de.stylelabor.statusplugin.util.VersionChecker.getLatestVersion();
            String downloadUrl = de.stylelabor.statusplugin.util.VersionChecker.getDownloadUrl();

            player.sendMessage(plugin.parseMessage(
                    "<gray>[<gradient:gold:yellow>StatusPlugin</gradient>] <gray>A new update is available: <gold>"
                            + latest));
            player.sendMessage(plugin.parseMessage(
                    "<gray>Download: <click:open_url:'" + downloadUrl + "'><aqua><u>Modrinth Page</u></aqua></click>"));
        }
    }

    /**
     * Handle player quit
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Remove from nametag teams
        nametagManager.removePlayer(player);

        // Remove from tab list sorting teams
        tabListManager.removePlayer(player);

        // Save data (handled by individual managers with batched saves)
        plugin.debug(player.getName() + " quit");
    }

    /**
     * Handle player death
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        Player player = event.getEntity();

        // Record death
        deathTracker.recordDeath(player);

        // Update tab list to show new death count
        tabListManager.updatePlayer(player);

        plugin.debug(player.getName() + " died - new total: " + deathTracker.getDeaths(player));
    }
}
