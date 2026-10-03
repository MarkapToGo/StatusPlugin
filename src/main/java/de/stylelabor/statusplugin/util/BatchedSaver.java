package de.stylelabor.statusplugin.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saves data off the main thread. Changes only mark the data as dirty; a
 * background task writes it at most once per interval. {@link #saveNow()}
 * writes synchronously, e.g. before a reload or on shutdown.
 * <p>
 * The save action must take its snapshot of the data itself, so a save that
 * runs later always writes data at least as new as an earlier one.
 */
public final class BatchedSaver {

    private final Plugin plugin;
    private final Runnable save;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Object lock = new Object();
    private @Nullable BukkitTask task;

    public BatchedSaver(@NotNull Plugin plugin, @NotNull Runnable save) {
        this.plugin = plugin;
        this.save = save;
    }

    /**
     * Start writing changes in the background every {@code intervalTicks}
     */
    public void start(long intervalTicks) {
        stop();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (dirty.getAndSet(false)) {
                write();
            }
        }, intervalTicks, intervalTicks);
    }

    /**
     * Mark the data as changed so the next background run writes it
     */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * Write the data now on the calling thread
     */
    public void saveNow() {
        dirty.set(false);
        write();
    }

    /**
     * Stop background saving and write the data one last time
     */
    public void shutdown() {
        stop();
        saveNow();
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void write() {
        // One write at a time, so an older snapshot never overwrites a newer one
        synchronized (lock) {
            save.run();
        }
    }
}
