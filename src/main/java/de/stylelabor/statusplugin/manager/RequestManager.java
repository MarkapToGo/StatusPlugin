package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import de.stylelabor.statusplugin.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Manages player status suggestions / requests and their lifecycle.
 */
public final class RequestManager {

    public enum RequestStatus {
        PENDING,
        ACCEPTED,
        DENIED
    }

    public record StatusRequest(
            @NotNull String id,
            @NotNull UUID playerUuid,
            @NotNull String playerName,
            @NotNull String rawInput,
            @NotNull String formattedStatus,
            long timestamp,
            @NotNull RequestStatus status,
            @Nullable String assignedKey,
            @Nullable String reason,
            boolean notified
    ) {
    }

    private final StatusPlugin plugin;
    private final ConfigManager configManager;
    private final StatusManager statusManager;

    private final Map<String, StatusRequest> requests = new LinkedHashMap<>();
    private int idCounter = 1;

    public RequestManager(@NotNull StatusPlugin plugin,
                          @NotNull ConfigManager configManager,
                          @NotNull StatusManager statusManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.statusManager = statusManager;
        loadRequests();
    }

    /**
     * Load requests from status-requests.yml
     */
    public final synchronized void loadRequests() {
        requests.clear();
        var config = configManager.getStatusRequests();
        idCounter = config.getInt("meta.next-id", 1);

        ConfigurationSection section = config.getConfigurationSection("requests");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection reqSec = section.getConfigurationSection(key);
                if (reqSec != null) {
                    try {
                        String id = key.toUpperCase();
                        UUID uuid = UUID.fromString(reqSec.getString("uuid", ""));
                        String playerName = reqSec.getString("player", "Unknown");
                        String raw = reqSec.getString("raw", "");
                        String format = reqSec.getString("format", "");
                        long timestamp = reqSec.getLong("timestamp", System.currentTimeMillis());
                        RequestStatus status = RequestStatus.valueOf(
                                reqSec.getString("status", "PENDING").toUpperCase());
                        String assignedKey = reqSec.getString("assigned-key", null);
                        String reason = reqSec.getString("reason", null);
                        boolean notified = reqSec.getBoolean("notified", true);

                        requests.put(id, new StatusRequest(
                                id, uuid, playerName, raw, format, timestamp, status, assignedKey, reason, notified));
                    } catch (Exception e) {
                        plugin.debug("Error loading request " + key + ": " + e.getMessage());
                    }
                }
            }
        }

        plugin.debug("Loaded " + requests.size() + " status requests");
    }

    /**
     * Save requests to status-requests.yml
     */
    public synchronized void saveData() {
        var config = configManager.getStatusRequests();

        // Clear existing requests section
        config.set("requests", null);
        config.set("meta.next-id", idCounter);

        for (StatusRequest req : requests.values()) {
            String path = "requests." + req.id();
            config.set(path + ".uuid", req.playerUuid().toString());
            config.set(path + ".player", req.playerName());
            config.set(path + ".raw", req.rawInput());
            config.set(path + ".format", req.formattedStatus());
            config.set(path + ".timestamp", req.timestamp());
            config.set(path + ".status", req.status().name());
            config.set(path + ".assigned-key", req.assignedKey());
            config.set(path + ".reason", req.reason());
            config.set(path + ".notified", req.notified());
        }

        configManager.saveStatusRequests();
        plugin.debug("Saved " + requests.size() + " status requests");
    }

    /**
     * Count pending requests for a player
     */
    public synchronized int getPendingCount(@NotNull UUID playerUuid) {
        int count = 0;
        for (StatusRequest req : requests.values()) {
            if (req.playerUuid().equals(playerUuid) && req.status() == RequestStatus.PENDING) {
                count++;
            }
        }
        return count;
    }

    /**
     * Submit a new status suggestion
     */
    public synchronized StatusRequest submitRequest(@NotNull Player player, @NotNull String rawInput) {
        String id = "REQ-" + idCounter++;
        String formatted = ColorUtil.convertLegacyToMiniMessage(rawInput);

        StatusRequest req = new StatusRequest(
                id,
                player.getUniqueId(),
                player.getName(),
                rawInput,
                formatted,
                System.currentTimeMillis(),
                RequestStatus.PENDING,
                null,
                null,
                false
        );

        requests.put(id, req);
        saveData();
        return req;
    }

    /**
     * Get all pending requests
     */
    @NotNull
    public synchronized List<StatusRequest> getPendingRequests() {
        List<StatusRequest> pending = new ArrayList<>();
        for (StatusRequest req : requests.values()) {
            if (req.status() == RequestStatus.PENDING) {
                pending.add(req);
            }
        }
        return Collections.unmodifiableList(pending);
    }

    /**
     * Find a request by ID
     */
    @Nullable
    public synchronized StatusRequest getRequest(@NotNull String id) {
        return requests.get(id.toUpperCase());
    }

    /**
     * Accept a pending request with a mandatory status name and register it into status-options.yml
     *
     * @return the status key assigned, or null if request not found or not pending
     */
    @Nullable
    public synchronized String acceptRequest(@NotNull String id, @NotNull String customKey) {
        StatusRequest req = getRequest(id);
        if (req == null || req.status() != RequestStatus.PENDING) {
            return null;
        }

        String key = customKey.trim().toUpperCase();

        Player player = Bukkit.getPlayer(req.playerUuid());
        boolean notified = false;
        if (player != null && player.isOnline()) {
            notifyPlayerAccepted(player, req.formattedStatus(), key);
            notified = true;
        }

        // Update request status
        StatusRequest updated = new StatusRequest(
                req.id(),
                req.playerUuid(),
                req.playerName(),
                req.rawInput(),
                req.formattedStatus(),
                req.timestamp(),
                RequestStatus.ACCEPTED,
                key,
                null,
                notified
        );
        requests.put(req.id(), updated);
        saveData();

        // Save into status-options.yml and reload status manager
        configManager.addStatusOption(key, req.formattedStatus());
        statusManager.reload();

        return key;
    }

    /**
     * Deny a pending request with a mandatory reason
     */
    public synchronized boolean denyRequest(@NotNull String id, @NotNull String reason) {
        StatusRequest req = getRequest(id);
        if (req == null || req.status() != RequestStatus.PENDING) {
            return false;
        }

        Player player = Bukkit.getPlayer(req.playerUuid());
        boolean notified = false;
        if (player != null && player.isOnline()) {
            notifyPlayerDenied(player, req.formattedStatus(), reason);
            notified = true;
        }

        StatusRequest updated = new StatusRequest(
                req.id(),
                req.playerUuid(),
                req.playerName(),
                req.rawInput(),
                req.formattedStatus(),
                req.timestamp(),
                RequestStatus.DENIED,
                null,
                reason,
                notified
        );
        requests.put(req.id(), updated);
        saveData();
        return true;
    }

    /**
     * Get unnotified completed requests for a player
     */
    @NotNull
    public synchronized List<StatusRequest> getUnnotifiedRequests(@NotNull UUID playerUuid) {
        List<StatusRequest> unnotified = new ArrayList<>();
        for (StatusRequest req : requests.values()) {
            if (req.playerUuid().equals(playerUuid) && !req.notified() && req.status() != RequestStatus.PENDING) {
                unnotified.add(req);
            }
        }
        return Collections.unmodifiableList(unnotified);
    }

    /**
     * Mark a request as notified
     */
    public synchronized void markNotified(@NotNull String id) {
        StatusRequest req = getRequest(id);
        if (req != null && !req.notified()) {
            StatusRequest updated = new StatusRequest(
                    req.id(),
                    req.playerUuid(),
                    req.playerName(),
                    req.rawInput(),
                    req.formattedStatus(),
                    req.timestamp(),
                    req.status(),
                    req.assignedKey(),
                    req.reason(),
                    true
            );
            requests.put(req.id(), updated);
            saveData();
        }
    }

    /**
     * Notify an online player about an accepted status
     */
    public void notifyPlayerAccepted(@NotNull Player player, @NotNull String formattedStatus, @NotNull String key) {
        Component statusDisplay = plugin.parseMessage(formattedStatus);
        String template = configManager.getMessage("requests-notify-accepted");
        Component formatted = plugin.getMiniMessage().deserialize(template,
                Placeholder.component("status_display", statusDisplay),
                Placeholder.unparsed("key", key));
        player.sendMessage(formatted);
    }

    /**
     * Notify an online player about a denied status with reason
     */
    public void notifyPlayerDenied(@NotNull Player player, @NotNull String formattedStatus, @NotNull String reason) {
        Component statusDisplay = plugin.parseMessage(formattedStatus);
        String template = configManager.getMessage("requests-notify-denied");
        Component formatted = plugin.getMiniMessage().deserialize(template,
                Placeholder.component("status_display", statusDisplay),
                Placeholder.unparsed("reason", reason));
        player.sendMessage(formatted);
    }

    public synchronized void reload() {
        loadRequests();
    }
}
