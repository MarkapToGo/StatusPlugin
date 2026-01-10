package de.stylelabor.statusplugin.manager;

import de.stylelabor.statusplugin.StatusPlugin;
import de.stylelabor.statusplugin.config.ConfigManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Manages country lookup via IP geolocation with async fetching and caching.
 */
public class CountryManager {

    private static final String PRIMARY_API = "http://ip-api.com/json/%s?fields=status,country,countryCode";
    private static final String FALLBACK_API = "https://api.iplocation.net/?ip=%s";

    private final StatusPlugin plugin;
    private final ConfigManager configManager;
    private final OkHttpClient httpClient;

    // Cache: UUID -> CountryData
    private final Map<UUID, CountryData> countryCache = new ConcurrentHashMap<>();

    /**
     * Immutable record for country data
     */
    public record CountryData(String country, String countryCode, long timestamp) {
    }

    public CountryManager(@NotNull StatusPlugin plugin, @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        loadCache();
    }

    /**
     * Check if country lookup is enabled
     */
    public boolean isEnabled() {
        return configManager.getConfig().getBoolean("country.enabled", false);
    }

    /**
     * Load cached country data from file
     */
    private void loadCache() {
        if (!isEnabled())
            return;

        var countriesConfig = configManager.getPlayerCountries();
        long cacheDuration = configManager.getConfig().getLong("country.cache-duration", 24) * 3600000L;

        for (String uuidString : countriesConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                var section = countriesConfig.getConfigurationSection(uuidString);
                if (section != null) {
                    String country = section.getString("country", "");
                    String countryCode = section.getString("countryCode", "");
                    long timestamp = section.getLong("timestamp", 0);

                    // Check if cache is still valid
                    if (cacheDuration == 0 || System.currentTimeMillis() - timestamp < cacheDuration) {
                        countryCache.put(uuid, new CountryData(country, countryCode, timestamp));
                    }
                }
            } catch (IllegalArgumentException e) {
                plugin.debug("Invalid UUID in player-countries.yml: " + uuidString);
            }
        }

        plugin.debug("Loaded country cache for " + countryCache.size() + " players");
    }

    /**
     * Save country cache to file
     */
    public void saveData() {
        if (!isEnabled())
            return;

        var countriesConfig = configManager.getPlayerCountries();

        // Clear existing data
        for (String key : countriesConfig.getKeys(false)) {
            countriesConfig.set(key, null);
        }

        // Save current cache
        for (Map.Entry<UUID, CountryData> entry : countryCache.entrySet()) {
            String path = entry.getKey().toString();
            CountryData data = entry.getValue();
            countriesConfig.set(path + ".country", data.country());
            countriesConfig.set(path + ".countryCode", data.countryCode());
            countriesConfig.set(path + ".timestamp", data.timestamp());
        }

        configManager.savePlayerCountries();
        plugin.debug("Saved country cache for " + countryCache.size() + " players");
    }

    /**
     * Get a player's country name
     */
    @NotNull
    public Optional<String> getCountry(@NotNull UUID uuid) {
        CountryData data = countryCache.get(uuid);
        return data != null ? Optional.of(data.country()) : Optional.empty();
    }

    /**
     * Get a player's country name
     */
    @NotNull
    public Optional<String> getCountry(@NotNull Player player) {
        return getCountry(player.getUniqueId());
    }

    /**
     * Get a player's country code
     */
    @NotNull
    public Optional<String> getCountryCode(@NotNull UUID uuid) {
        CountryData data = countryCache.get(uuid);
        return data != null ? Optional.of(data.countryCode()) : Optional.empty();
    }

    /**
     * Get a player's country code
     */
    @NotNull
    public Optional<String> getCountryCode(@NotNull Player player) {
        return getCountryCode(player.getUniqueId());
    }

    /**
     * Fetch country data for a player asynchronously
     */
    public CompletableFuture<CountryData> fetchCountry(@NotNull Player player) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        UUID uuid = player.getUniqueId();

        // Check cache first
        CountryData cached = countryCache.get(uuid);
        if (cached != null) {
            long cacheDuration = configManager.getConfig().getLong("country.cache-duration", 24) * 3600000L;
            if (cacheDuration == 0 || System.currentTimeMillis() - cached.timestamp() < cacheDuration) {
                return CompletableFuture.completedFuture(cached);
            }
        }

        // Get player's IP
        InetAddress address = player.getAddress() != null ? player.getAddress().getAddress() : null;
        if (address == null || address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            plugin.debug("Cannot lookup country for " + player.getName() + " - local/loopback address");
            return CompletableFuture.completedFuture(null);
        }

        String ip = address.getHostAddress();

        return CompletableFuture.supplyAsync(() -> {
            CountryData data = fetchFromPrimaryApi(ip);
            if (data == null) {
                data = fetchFromFallbackApi(ip);
            }

            if (data != null) {
                countryCache.put(uuid, data);
                plugin.debug("Fetched country for " + player.getName() + ": " + data.country());
            }

            return data;
        });
    }

    /**
     * Fetch from primary API (ip-api.com)
     */
    private CountryData fetchFromPrimaryApi(@NotNull String ip) {
        String url = String.format(PRIMARY_API, ip);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                JSONObject json = new JSONObject(body);

                if ("success".equals(json.optString("status"))) {
                    String country = json.optString("country", "");
                    String countryCode = json.optString("countryCode", "");
                    return new CountryData(country, countryCode, System.currentTimeMillis());
                }
            }
        } catch (IOException e) {
            plugin.debug("Primary API failed for " + ip + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Fetch from fallback API (iplocation.net)
     */
    private CountryData fetchFromFallbackApi(@NotNull String ip) {
        String url = String.format(FALLBACK_API, ip);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                JSONObject json = new JSONObject(body);

                String country = json.optString("country_name", "");
                String countryCode = json.optString("country_code2", "");

                if (!country.isEmpty()) {
                    return new CountryData(country, countryCode, System.currentTimeMillis());
                }
            }
        } catch (IOException e) {
            plugin.debug("Fallback API failed for " + ip + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Reload configuration
     */
    public void reload() {
        countryCache.clear();
        loadCache();
    }
}
