package de.stylelabor.statusplugin.util;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BatchedSaverTest {

    private static final Plugin PLUGIN = (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> null);

    @Test
    public void testMarkDirtyDoesNotWriteImmediately() {
        AtomicInteger writes = new AtomicInteger();
        BatchedSaver saver = new BatchedSaver(PLUGIN, writes::incrementAndGet);

        saver.markDirty();
        saver.markDirty();

        assertEquals(0, writes.get());
    }

    @Test
    public void testSaveNowWrites() {
        AtomicInteger writes = new AtomicInteger();
        BatchedSaver saver = new BatchedSaver(PLUGIN, writes::incrementAndGet);

        saver.markDirty();
        saver.saveNow();

        assertEquals(1, writes.get());
    }

    @Test
    public void testShutdownWritesPendingChanges() {
        AtomicInteger writes = new AtomicInteger();
        BatchedSaver saver = new BatchedSaver(PLUGIN, writes::incrementAndGet);

        saver.markDirty();
        saver.shutdown();

        assertEquals(1, writes.get());
    }
}
