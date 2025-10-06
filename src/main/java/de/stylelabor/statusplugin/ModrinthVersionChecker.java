package de.stylelabor.statusplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ModrinthVersionChecker {

    private static final String API_URL = "https://api.modrinth.com/v2/project/JyJcJ7vv/version";
    private static final Logger LOGGER = Logger.getLogger(ModrinthVersionChecker.class.getName());

    public static void checkVersion() {
        try {
            String jsonResponse = sendGetRequest();
            JSONArray versions = new JSONArray(jsonResponse);

            // Get the newest version
            JSONObject newestVersion = versions.getJSONObject(0);
            for (int i = 1; i < versions.length(); i++) {
                JSONObject version = versions.getJSONObject(i);
                if (version.getString("date_published").compareTo(newestVersion.getString("date_published")) > 0) {
                    newestVersion = version;
                }
            }

            // Load the current version from config.yml
            File configFile = new File(Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("StatusPlugin")).getDataFolder(), "config.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            String currentVersion = config.getString("current_version");

            // Compare versions
            String newestVersionNumber = newestVersion.getString("version_number");
            String message;
            String prefix = ChatColor.DARK_GRAY + "[" + ChatColor.GRAY + "StatusPlugin" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;
            if (newestVersionNumber.equals(currentVersion)) {
                message = prefix + ChatColor.WHITE + "You are using the latest version: " + currentVersion;
            } else if (isVersionHigher(newestVersionNumber, currentVersion)) {
                JSONArray filesArray = newestVersion.getJSONArray("files");
                String downloadLink = filesArray.getJSONObject(0).getString("url");
                message = prefix + ChatColor.RED + "A new version is available: " + newestVersionNumber + "\n" +
                        ChatColor.WHITE + "Current version: " + currentVersion + "\n" +
                        ChatColor.WHITE + "Download it here: " + ChatColor.UNDERLINE + downloadLink;
            } else {
                message = prefix + ChatColor.WHITE + "You are using a higher version: " + currentVersion;
            }

            // Send message to admins
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp() || player.hasPermission("statusplugin.admin")) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to fetch version details", e);
        }
    }

    private static boolean isVersionHigher(String version1, String version2) {
        String[] v1 = version1.split("\\.");
        String[] v2 = version2.split("\\.");
        int length = Math.max(v1.length, v2.length);
        for (int i = 0; i < length; i++) {
            int num1 = i < v1.length ? Integer.parseInt(v1[i]) : 0;
            int num2 = i < v2.length ? Integer.parseInt(v2[i]) : 0;
            if (num1 > num2) {
                return true;
            } else if (num1 < num2) {
                return false;
            }
        }
        return false;
    }

    private static String sendGetRequest() throws Exception {
        URL url = new URL(ModrinthVersionChecker.API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            return content.toString();
        } else {
            throw new Exception("Failed to fetch version details. HTTP response code: " + responseCode);
        }
    }
}