package de.stylelabor.statusplugin;

import org.bukkit.ChatColor;

/**
 * Utility class for parsing legacy color codes such as &a, &c, &x&F&F&0&0&0&0.
 */
public class ColorParser {
    
    public static String parse(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // Use traditional & code translation only
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    
    /**
     * Parse text and ensure it's compatible with both chat and tab list
     * This method processes both formats and ensures proper display
     * 
     * @param text The text to parse
     * @return Fully parsed string ready for display
     */
    public static String parseForDisplay(String text) {
        return parse(text);
    }
    
    public static String stripColors(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        String parsed = parse(text);
        return ChatColor.stripColor(parsed);
    }
}

