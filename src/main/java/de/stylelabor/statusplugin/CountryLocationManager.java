package de.stylelabor.statusplugin;

import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CountryLocationManager {
    
    private final StatusPlugin plugin;
    private final OkHttpClient httpClient;
    private final Map<UUID, CountryData> playerCountries;
    private File countriesFile;
    private FileConfiguration countriesConfig;
    
    public CountryLocationManager(StatusPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        this.playerCountries = new HashMap<>();
        
        loadCountriesConfig();
        loadPlayerCountries();
    }
    
    /**
     * Get country data for a player asynchronously
     */
    public CompletableFuture<CountryData> getPlayerCountryAsync(Player player) {
        UUID playerUUID = player.getUniqueId();
        
        // Check if we already have country data for this player
        if (playerCountries.containsKey(playerUUID)) {
            return CompletableFuture.completedFuture(playerCountries.get(playerUUID));
        }
        
        // If not, fetch from API
        return CompletableFuture.supplyAsync(() -> {
            try {
                String playerIP = player.getAddress().getAddress().getHostAddress();
                return fetchCountryFromAPI(playerIP);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to get IP for player " + player.getName() + ": " + e.getMessage());
                return null;
            }
        }).thenApply(countryData -> {
            if (countryData != null) {
                // Save the country data for future use
                playerCountries.put(playerUUID, countryData);
                savePlayerCountry(playerUUID, countryData);
                plugin.getLogger().info("Country data fetched and saved for player " + player.getName() + 
                    ": " + countryData.getCountry() + " (" + countryData.getCountryCode() + ")");
            }
            return countryData;
        });
    }
    
    /**
     * Get country data for a player (synchronous, returns cached data only)
     */
    public CountryData getPlayerCountry(UUID playerUUID) {
        return playerCountries.get(playerUUID);
    }
    
    /**
     * Fetch country data from API with fallback
     */
    private CountryData fetchCountryFromAPI(String ip) {
        // Try primary API first
        CountryData countryData = tryPrimaryAPI(ip);
        if (countryData != null) {
            return countryData;
        }
        
        // Try fallback API
        countryData = tryFallbackAPI(ip);
        if (countryData != null) {
            return countryData;
        }
        
        plugin.getLogger().warning("Failed to fetch country data for IP: " + ip);
        return null;
    }
    
    /**
     * Try primary API (ip-api.com)
     */
    private CountryData tryPrimaryAPI(String ip) {
        try {
            String url = "http://ip-api.com/json/" + ip;
            Request request = new Request.Builder()
                    .url(url)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonResponse = response.body().string();
                    JSONObject json = new JSONObject(jsonResponse);
                    
                    if (json.getString("status").equals("success")) {
                        String country = json.getString("country");
                        String countryCode = json.getString("countryCode");
                        return new CountryData(country, countryCode);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Primary API (ip-api.com) failed: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Try fallback API (api.iplocation.net)
     */
    private CountryData tryFallbackAPI(String ip) {
        try {
            String url = "https://api.iplocation.net/?ip=" + ip;
            Request request = new Request.Builder()
                    .url(url)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonResponse = response.body().string();
                    JSONObject json = new JSONObject(jsonResponse);
                    
                    if (json.getString("response_code").equals("200")) {
                        String country = json.getString("country_name");
                        String countryCode = json.getString("country_code2");
                        return new CountryData(country, countryCode);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Fallback API (api.iplocation.net) failed: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Load countries configuration file
     */
    private void loadCountriesConfig() {
        countriesFile = new File(plugin.getDataFolder(), "player-countries.yml");
        if (!countriesFile.exists()) {
            try {
                if (countriesFile.createNewFile()) {
                    plugin.getLogger().info("player-countries.yml file created.");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create player-countries.yml: " + e.getMessage());
            }
        }
        countriesConfig = YamlConfiguration.loadConfiguration(countriesFile);
    }
    
    /**
     * Load player countries from config
     */
    private void loadPlayerCountries() {
        for (String uuidString : countriesConfig.getKeys(false)) {
            try {
                UUID playerUUID = UUID.fromString(uuidString);
                String country = countriesConfig.getString(uuidString + ".country");
                String countryCode = countriesConfig.getString(uuidString + ".countryCode");
                if (country != null && countryCode != null) {
                    playerCountries.put(playerUUID, new CountryData(country, countryCode));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load country data for UUID " + uuidString + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded country data for " + playerCountries.size() + " players.");
    }
    
    /**
     * Save player country data to config
     */
    private void savePlayerCountry(UUID playerUUID, CountryData countryData) {
        String uuidString = playerUUID.toString();
        countriesConfig.set(uuidString + ".country", countryData.getCountry());
        countriesConfig.set(uuidString + ".countryCode", countryData.getCountryCode());
        
        try {
            countriesConfig.save(countriesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save country data for player " + playerUUID + ": " + e.getMessage());
        }
    }
    
    /**
     * Save all player countries to config (used on plugin shutdown)
     */
    public void saveAllPlayerCountries() {
        for (Map.Entry<UUID, CountryData> entry : playerCountries.entrySet()) {
            savePlayerCountry(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * Country data class
     */
    public static class CountryData {
        private final String country;
        private final String countryCode;
        
        public CountryData(String country, String countryCode) {
            this.country = country;
            this.countryCode = countryCode;
        }
        
        public String getCountry() {
            return country;
        }
        
        public String getCountryCode() {
            return countryCode;
        }
        
        @Override
        public String toString() {
            return country + " (" + countryCode + ")";
        }
    }
}
