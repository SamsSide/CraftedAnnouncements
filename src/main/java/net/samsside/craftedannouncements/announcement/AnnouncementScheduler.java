package net.samsside.craftedannouncements.announcement;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs each scheduled preset on its own independent repeating task. Rebuilt on
 * enable and on every reload: all existing tasks are cancelled first, so intervals
 * never duplicate or leak.
 */
public final class AnnouncementScheduler {

    private final JavaPlugin plugin;
    private final AnnouncementStore store;
    private final AnnouncementService service;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public AnnouncementScheduler(JavaPlugin plugin, AnnouncementStore store, AnnouncementService service) {
        this.plugin = plugin;
        this.store = store;
        this.service = service;
    }

    /** Cancel all running timers and schedule a fresh one per scheduled preset. */
    public synchronized void rebuild() {
        cancelAll();
        int scheduled = 0;
        for (Announcement announcement : store.all()) {
            if (announcement.scheduleTicks().isEmpty()) {
                continue;
            }
            long ticks = announcement.scheduleTicks().get();
            BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    () -> service.fire(announcement, List.of(), "schedule"),
                    ticks, // first run one interval after (re)load
                    ticks);
            tasks.add(task);
            scheduled++;
        }
        plugin.getLogger().info("Scheduled " + scheduled + " repeating announcement task(s).");
    }

    /** Cancel and forget all timers (called on disable and before each rebuild). */
    public synchronized void cancelAll() {
        for (BukkitTask task : tasks) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // A task may already be cancelled during shutdown.
            }
        }
        tasks.clear();
    }
}
