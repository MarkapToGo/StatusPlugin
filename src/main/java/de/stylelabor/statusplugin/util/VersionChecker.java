package de.stylelabor.statusplugin.util;

import de.stylelabor.statusplugin.StatusPlugin;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Checks for updates on Modrinth.
 */
public final class VersionChecker {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/statusplugin/version";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static String latestVersion = null;
    private static String downloadUrl = null;
    private static boolean updateAvailable = false;

    private VersionChecker() {
        // Utility class
    }

    /**
     * Check for updates asynchronously
     * 
     * @param plugin   The plugin instance
     * @param callback Callback with (latestVersion, downloadUrl)
     */
    public static void checkForUpdates(@NotNull StatusPlugin plugin,
            @NotNull BiConsumer<String, String> callback) {
        CompletableFuture.runAsync(() -> {
            try {
                Request request = new Request.Builder()
                        .url(MODRINTH_API)
                        // Use getPluginMeta() for modern Paper plugins
                        .header("User-Agent", "StatusPlugin/" + plugin.getPluginMeta().getVersion())
                        .get()
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        plugin.debug("Failed to check for updates: HTTP " + response.code());
                        return;
                    }

                    String body = response.body().string();
                    JSONArray versions = new JSONArray(body);

                    if (versions.isEmpty()) {
                        plugin.debug("No versions found on Modrinth");
                        return;
                    }

                    // Get the first (latest) version
                    JSONObject latest = versions.getJSONObject(0);
                    String version = latest.getString("version_number");
                    String currentVersion = plugin.getPluginMeta().getVersion();

                    // Get download URL
                    String url = "https://modrinth.com/plugin/statusplugin";
                    JSONArray files = latest.optJSONArray("files");
                    if (files != null && !files.isEmpty()) {
                        downloadUrl = files.getJSONObject(0).optString("url", downloadUrl);
                    }

                    if (downloadUrl == null) {
                        downloadUrl = url;
                    }

                    latestVersion = version;
                    updateAvailable = isNewer(currentVersion, version);

                    callback.accept(version, url);
                }
            } catch (IOException e) {
                plugin.debug("Failed to check for updates: " + e.getMessage());
            } catch (Exception e) {
                plugin.log(Level.WARNING, "Error parsing update response: " + e.getMessage());
            }
        });
    }

    /**
     * Compare two version strings
     * 
     * @return true if latestVersion is newer than currentVersion
     */
    public static boolean isNewer(@NotNull String currentVersion, @NotNull String latestVersion) {
        // Remove any prefix like 'v'
        currentVersion = currentVersion.replaceFirst("^[vV]", "");
        latestVersion = latestVersion.replaceFirst("^[vV]", "");

        String[] currentParts = currentVersion.split("\\.");
        String[] latestParts = latestVersion.split("\\.");

        int length = Math.max(currentParts.length, latestParts.length);

        for (int i = 0; i < length; i++) {
            int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
            int latestPart = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;

            if (latestPart > currentPart) {
                return true;
            } else if (latestPart < currentPart) {
                return false;
            }
        }

        return false; // Versions are equal
    }

    /**
     * Parse a version part, handling non-numeric suffixes like "1-SNAPSHOT"
     */
    private static int parseVersionPart(@NotNull String part) {
        try {
            // Remove any non-numeric suffix
            String numericPart = part.replaceAll("[^0-9].*", "");
            return numericPart.isEmpty() ? 0 : Integer.parseInt(numericPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Check if an update is available
     */
    public static boolean isUpdateAvailable() {
        return updateAvailable;
    }

    /**
     * Get the latest version string
     */
    public static String getLatestVersion() {
        return latestVersion;
    }

    /**
     * Get the download URL
     */
    public static String getDownloadUrl() {
        return downloadUrl;
    }
}
