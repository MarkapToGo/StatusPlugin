package de.stylelabor.statusplugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for color/text parsing.
 * Handles MiniMessage format and legacy color code conversion.
 */
public final class ColorUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    // Pattern to detect legacy color codes
    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);

    // Pattern to detect hex color codes (&x&r&r&g&g&b&b or &#RRGGBB)
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern BUKKIT_HEX_PATTERN = Pattern.compile(
            "&x(&[A-Fa-f0-9]){6}", Pattern.CASE_INSENSITIVE);

    private ColorUtil() {
        // Utility class, no instantiation
    }

    /**
     * Parse a string that may contain MiniMessage or legacy color codes
     */
    @NotNull
    public static Component parse(@NotNull String text) {
        // Check if it contains legacy color codes
        if (containsLegacyCodes(text)) {
            text = convertLegacyToMiniMessage(text);
        }

        return MINI_MESSAGE.deserialize(text);
    }

    /**
     * Check if text contains legacy color codes
     */
    public static boolean containsLegacyCodes(@NotNull String text) {
        String normalized = text.replace('§', '&');
        return LEGACY_PATTERN.matcher(normalized).find() ||
                HEX_PATTERN.matcher(normalized).find() ||
                BUKKIT_HEX_PATTERN.matcher(normalized).find();
    }

    /**
     * Convert legacy color codes to MiniMessage format
     */
    @NotNull
    public static String convertLegacyToMiniMessage(@NotNull String text) {
        text = text.replace('§', '&');
        // Convert &#RRGGBB to <#RRGGBB>
        Matcher hexMatcher = HEX_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(result, "<#" + hexMatcher.group(1) + ">");
        }
        hexMatcher.appendTail(result);
        text = result.toString();

        // Convert Bukkit hex format &x&r&r&g&g&b&b
        Matcher bukkitHexMatcher = BUKKIT_HEX_PATTERN.matcher(text);
        result = new StringBuilder();
        while (bukkitHexMatcher.find()) {
            String match = bukkitHexMatcher.group();
            String hex = match.replaceAll("&[xX]|&", "");
            bukkitHexMatcher.appendReplacement(result, "<#" + hex + ">");
        }
        bukkitHexMatcher.appendTail(result);
        text = result.toString();

        // Convert legacy codes to MiniMessage
        text = text.replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>")
                .replace("&A", "<green>")
                .replace("&b", "<aqua>")
                .replace("&B", "<aqua>")
                .replace("&c", "<red>")
                .replace("&C", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&D", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&E", "<yellow>")
                .replace("&f", "<white>")
                .replace("&F", "<white>")
                .replace("&k", "<obfuscated>")
                .replace("&K", "<obfuscated>")
                .replace("&l", "<bold>")
                .replace("&L", "<bold>")
                .replace("&m", "<strikethrough>")
                .replace("&M", "<strikethrough>")
                .replace("&n", "<underlined>")
                .replace("&N", "<underlined>")
                .replace("&o", "<italic>")
                .replace("&O", "<italic>")
                .replace("&r", "<reset>")
                .replace("&R", "<reset>");

        return text;
    }

    /**
     * Convert a Component to a legacy string (for compatibility with older APIs)
     */
    @NotNull
    public static String toLegacy(@NotNull Component component) {
        return LEGACY_SECTION.serialize(component);
    }

    /**
     * Convert a Component to a legacy string with ampersand codes
     */
    @NotNull
    public static String toLegacyAmpersand(@NotNull Component component) {
        return LEGACY_AMPERSAND.serialize(component);
    }

    /**
     * Safely validate whether a text containing MiniMessage or legacy codes can be parsed.
     */
    public static boolean isValidFormat(@NotNull String text) {
        if (text.isBlank()) {
            return false;
        }
        try {
            parse(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if the stripped text starts with '[' and ends with ']'.
     */
    public static boolean hasCornerBrackets(@NotNull String text) {
        try {
            String stripped = stripFormatting(text).trim();
            return stripped.startsWith("[") && stripped.endsWith("]") && stripped.length() >= 2;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Strip all color and formatting from a string
     */
    @NotNull
    public static String stripFormatting(@NotNull String text) {
        try {
            Component component = parse(text);
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(component);
        } catch (Exception e) {
            // Fallback regex strip
            return text.replace('§', '&')
                    .replaceAll("<[^>]*>", "")
                    .replaceAll("&x(&[0-9a-fA-F]){6}", "")
                    .replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        }
    }

    /**
     * Check if text ends with a reset tag or legacy reset code
     */
    public static boolean hasResetAtEnd(@NotNull String text) {
        String trimmed = text.trim();
        return trimmed.endsWith("<reset>")
                || trimmed.endsWith("<r>")
                || trimmed.endsWith("</reset>")
                || trimmed.endsWith("&r")
                || trimmed.endsWith("&R")
                || trimmed.endsWith("§r")
                || trimmed.endsWith("§R");
    }

    /**
     * Ensure text ends with a clear color / reset tag (<reset>)
     */
    @NotNull
    public static String ensureResetAtEnd(@NotNull String text) {
        if (hasResetAtEnd(text)) {
            return text;
        }
        return text + "<reset>";
    }
}

