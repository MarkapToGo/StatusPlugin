package de.stylelabor.statusplugin.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

public class StatusAdminCommandTest {

    private CommandSourceStack createMockStack(boolean isAdmin, boolean isReload) {
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if ("hasPermission".equals(method.getName())) {
                        String perm = (String) args[0];
                        if ("statusplugin.admin".equalsIgnoreCase(perm)) return isAdmin;
                        if ("statusplugin.reload".equalsIgnoreCase(perm)) return isReload;
                        return false;
                    }
                    if ("isOp".equals(method.getName())) {
                        return isAdmin;
                    }
                    return null;
                }
        );

        return (CommandSourceStack) Proxy.newProxyInstance(
                CommandSourceStack.class.getClassLoader(),
                new Class<?>[]{CommandSourceStack.class},
                (proxy, method, args) -> {
                    if ("getSender".equals(method.getName())) {
                        return sender;
                    }
                    return null;
                }
        );
    }

    @Test
    public void testSuggestWithEmptyArgsDoesNotThrow() {
        StatusAdminCommand command = new StatusAdminCommand(null, null, null, null, null);

        CommandSourceStack stack = createMockStack(true, false);

        // When args is null
        Collection<String> nullArgs = command.suggest(stack, null);
        assertNotNull(nullArgs);
        assertTrue(nullArgs.contains("set"));

        // When args is empty array (length 0)
        Collection<String> emptyArgs = command.suggest(stack, new String[0]);
        assertNotNull(emptyArgs);
        assertTrue(emptyArgs.contains("set"));
        assertTrue(emptyArgs.contains("reload"));
        assertTrue(emptyArgs.contains("deaths"));
        assertTrue(emptyArgs.contains("requests"));
        assertTrue(emptyArgs.contains("help"));

        // When args is [""] (length 1)
        Collection<String> singleBlank = command.suggest(stack, new String[]{""});
        assertNotNull(singleBlank);
        assertTrue(singleBlank.contains("set"));
        assertTrue(singleBlank.contains("reload"));
        assertTrue(singleBlank.contains("deaths"));
        assertTrue(singleBlank.contains("requests"));
        assertTrue(singleBlank.contains("help"));

        // When prefix filtering
        Collection<String> prefixR = command.suggest(stack, new String[]{"r"});
        assertTrue(prefixR.contains("reload"));
        assertTrue(prefixR.contains("requests"));
        assertFalse(prefixR.contains("set"));

        Collection<String> prefixS = command.suggest(stack, new String[]{"s"});
        assertTrue(prefixS.contains("set"));
        assertFalse(prefixS.contains("reload"));
    }

    @Test
    public void testSuggestReloadOnlyPermission() {
        StatusAdminCommand command = new StatusAdminCommand(null, null, null, null, null);

        CommandSourceStack stack = createMockStack(false, true);

        Collection<String> suggestions = command.suggest(stack, new String[0]);
        assertEquals(1, suggestions.size());
        assertTrue(suggestions.contains("reload"));
    }

    @Test
    public void testSuggestNoPermission() {
        StatusAdminCommand command = new StatusAdminCommand(null, null, null, null, null);

        CommandSourceStack stack = createMockStack(false, false);

        Collection<String> suggestions = command.suggest(stack, new String[0]);
        assertTrue(suggestions.isEmpty());
    }

    @Test
    public void testCanUse() {
        StatusAdminCommand command = new StatusAdminCommand(null, null, null, null, null);

        CommandSourceStack adminStack = createMockStack(true, false);
        CommandSourceStack reloadStack = createMockStack(false, true);
        CommandSourceStack userStack = createMockStack(false, false);

        assertTrue(command.canUse(adminStack.getSender()));
        assertTrue(command.canUse(reloadStack.getSender()));
        assertFalse(command.canUse(userStack.getSender()));
    }
}
