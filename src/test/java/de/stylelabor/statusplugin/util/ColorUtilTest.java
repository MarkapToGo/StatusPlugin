package de.stylelabor.statusplugin.util;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ColorUtilTest {

    @Test
    public void testMiniMessageGradientParsing() {
        String input = "<gradient:#65FF64:#65FF64>TEST</gradient>";
        assertTrue(ColorUtil.isValidFormat(input));

        Component component = ColorUtil.parse(input);
        assertNotNull(component);

        String stripped = ColorUtil.stripFormatting(input);
        assertEquals("TEST", stripped);
    }

    @Test
    public void testBukkitLegacyHexParsing() {
        String input = "&x&6&5&F&F&6&4TEST";
        assertTrue(ColorUtil.isValidFormat(input));

        String miniMessage = ColorUtil.convertLegacyToMiniMessage(input);
        assertEquals("<#65FF64>TEST", miniMessage);

        Component component = ColorUtil.parse(input);
        assertNotNull(component);

        String stripped = ColorUtil.stripFormatting(input);
        assertEquals("TEST", stripped);
    }

    @Test
    public void testCornerBrackets() {
        // Brackets around MiniMessage
        assertTrue(ColorUtil.hasCornerBrackets("[<gradient:#65FF64:#65FF64>TEST</gradient>]"));

        // Brackets inside MiniMessage
        assertTrue(ColorUtil.hasCornerBrackets("<gradient:#65FF64:#65FF64>[TEST]</gradient>"));

        // Brackets with Legacy hex
        assertTrue(ColorUtil.hasCornerBrackets("&x&6&5&F&F&6&4[TEST]"));
        assertTrue(ColorUtil.hasCornerBrackets("[&x&6&5&F&F&6&4TEST]"));

        // No brackets
        assertFalse(ColorUtil.hasCornerBrackets("<gradient:#65FF64:#65FF64>TEST</gradient>"));
        assertFalse(ColorUtil.hasCornerBrackets("&x&6&5&F&F&6&4TEST"));
        assertFalse(ColorUtil.hasCornerBrackets("[TEST"));
        assertFalse(ColorUtil.hasCornerBrackets("TEST]"));
        assertFalse(ColorUtil.hasCornerBrackets(""));
    }

    @Test
    public void testInvalidFormats() {
        assertFalse(ColorUtil.isValidFormat(""));
        assertFalse(ColorUtil.isValidFormat("   "));
    }

    @Test
    public void testStatusRequestRecord() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        var req = new de.stylelabor.statusplugin.manager.RequestManager.StatusRequest(
                "REQ-1",
                uuid,
                "TestPlayer",
                "&x&6&5&F&F&6&4[TEST]",
                "<#65FF64>[TEST]",
                System.currentTimeMillis(),
                de.stylelabor.statusplugin.manager.RequestManager.RequestStatus.PENDING,
                null,
                null,
                false
        );

        assertEquals("REQ-1", req.id());
        assertEquals("TestPlayer", req.playerName());
        assertEquals(de.stylelabor.statusplugin.manager.RequestManager.RequestStatus.PENDING, req.status());
        assertNull(req.assignedKey());
        assertNull(req.reason());
        assertFalse(req.notified());

        // Test accepted state
        var accepted = new de.stylelabor.statusplugin.manager.RequestManager.StatusRequest(
                req.id(), req.playerUuid(), req.playerName(), req.rawInput(), req.formattedStatus(),
                req.timestamp(), de.stylelabor.statusplugin.manager.RequestManager.RequestStatus.ACCEPTED,
                "CUSTOM_STATUS", null, true
        );
        assertEquals("CUSTOM_STATUS", accepted.assignedKey());
        assertTrue(accepted.notified());

        // Test denied state with reason
        var denied = new de.stylelabor.statusplugin.manager.RequestManager.StatusRequest(
                req.id(), req.playerUuid(), req.playerName(), req.rawInput(), req.formattedStatus(),
                req.timestamp(), de.stylelabor.statusplugin.manager.RequestManager.RequestStatus.DENIED,
                null, "Violates color rules", false
        );
        assertEquals("Violates color rules", denied.reason());
        assertFalse(denied.notified());
    }

    @Test
    public void testVersionComparison() {
        assertTrue(VersionChecker.isNewer("7.1.1", "7.1.2"));
        assertTrue(VersionChecker.isNewer("7.1.2", "7.2"));
        assertTrue(VersionChecker.isNewer("7.1.2", "8.0.0"));
        assertFalse(VersionChecker.isNewer("7.1.2", "7.1.2"));
        assertFalse(VersionChecker.isNewer("7.1.2", "7.1.1"));
        assertFalse(VersionChecker.isNewer("8.0.0", "7.1.2"));
    }

    @Test
    public void testRotatingIndexOverflow() {
        int negativeIndex = -5;
        int listSize = 6;
        int safeIndex = (negativeIndex & Integer.MAX_VALUE) % listSize;
        assertTrue(safeIndex >= 0 && safeIndex < listSize);

        int maxInt = Integer.MAX_VALUE;
        safeIndex = (maxInt & Integer.MAX_VALUE) % listSize;
        assertTrue(safeIndex >= 0 && safeIndex < listSize);

        int minInt = Integer.MIN_VALUE;
        safeIndex = (minInt & Integer.MAX_VALUE) % listSize;
        assertTrue(safeIndex >= 0 && safeIndex < listSize);
    }

    @Test
    public void testInfoExampleUnparsedMiniMessage() {
        var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        String template = "<gray> • MiniMessage: <white><code_mm></white> → <preview_mm>";
        String code = "<gradient:#65FF64:#65FF64>[TEST]</gradient>";
        Component comp = mm.deserialize(
                template,
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("code_mm", code),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("preview_mm", ColorUtil.parse(code))
        );

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
        assertEquals(" • MiniMessage: <gradient:#65FF64:#65FF64>[TEST]</gradient> → [TEST]", plain);
    }

    @Test
    public void testInfoExampleUnparsedLegacy() {
        var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        String template = "<gray> • Legacy Hex: <white><code_legacy></white> → <preview_legacy>";
        String code = "&x&6&5&F&F&6&4[TEST]";
        Component comp = mm.deserialize(
                template,
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("code_legacy", code),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("preview_legacy", ColorUtil.parse(code))
        );

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
        assertEquals(" • Legacy Hex: &x&6&5&F&F&6&4[TEST] → [TEST]", plain);
    }

    @Test
    public void testHasResetAtEndAndEnsureReset() {
        assertTrue(ColorUtil.hasResetAtEnd("[TEST]<reset>"));
        assertTrue(ColorUtil.hasResetAtEnd("[TEST]<r>"));
        assertTrue(ColorUtil.hasResetAtEnd("[TEST]&r"));
        assertTrue(ColorUtil.hasResetAtEnd("[TEST]§r"));
        assertFalse(ColorUtil.hasResetAtEnd("[TEST]"));
        assertFalse(ColorUtil.hasResetAtEnd("<#65FF64>[TEST]"));

        assertEquals("[TEST]<reset>", ColorUtil.ensureResetAtEnd("[TEST]"));
        assertEquals("[TEST]<reset>", ColorUtil.ensureResetAtEnd("[TEST]<reset>"));
        assertEquals("&x&6&5&F&F&6&4[TEST]<reset>", ColorUtil.ensureResetAtEnd("&x&6&5&F&F&6&4[TEST]"));
        assertEquals("&x&6&5&F&F&6&4[TEST]&r", ColorUtil.ensureResetAtEnd("&x&6&5&F&F&6&4[TEST]&r"));
    }
}
