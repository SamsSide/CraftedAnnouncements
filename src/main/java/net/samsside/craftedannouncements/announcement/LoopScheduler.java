package net.samsside.craftedannouncements.announcement;

import net.samsside.craftedannouncements.announcement.LoopStateStore.LoopState;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Runs the loops: one repeating task per running loop, sending exactly one of its
 * presets per interval and then moving on by one. Because a loop has a single
 * timer, its messages can never land on top of each other - which is the point of
 * the whole feature.
 *
 * <p>Everything here runs on the main thread: the Bukkit timer callbacks and the
 * command handlers are the only callers, and only {@link LoopStateStore}'s file
 * write is ever off-thread.
 */
public final class LoopScheduler {

    private final JavaPlugin plugin;
    private final AnnouncementStore store;
    private final AnnouncementService service;
    private final LoopStateStore state;

    /** Running loops only, keyed by lowercase loop id. */
    private final Map<String, BukkitTask> tasks = new HashMap<>();
    /** The tick each running loop is next due to fire on, for the info countdown. */
    private final Map<String, Integer> nextFireTick = new HashMap<>();

    public LoopScheduler(JavaPlugin plugin, AnnouncementStore store, AnnouncementService service,
                         LoopStateStore state) {
        this.plugin = plugin;
        this.store = store;
        this.service = service;
        this.state = state;
    }

    /**
     * Cancel every timer and start a fresh one per running loop. Called on enable
     * and on every reload; positions and paused flags carry over from the in-memory
     * state, so a reload never restarts a loop or double-sends.
     */
    public void rebuild() {
        cancelAll();

        int running = 0;
        int paused = 0;
        for (Loop loop : store.loops()) {
            LoopState loopState = state.getOrCreate(loop.key());

            if (!loopState.entries.equals(loop.entryKeys())) {
                if (!loopState.entries.isEmpty()) {
                    plugin.getLogger().info("Loop '" + loop.id()
                            + "': its list of presets changed; starting again from message 1.");
                }
                loopState.nextIndex = 0;
                loopState.entries = loop.entryKeys();
            }

            if (loopState.nextIndex < 0 || loopState.nextIndex >= loopState.entries.size()) {
                plugin.getLogger().warning("Loop '" + loop.id() + "': saved position "
                        + loopState.nextIndex + " is out of range; starting again from message 1.");
                loopState.nextIndex = 0;
            }

            if (loopState.paused) {
                paused++;
            } else {
                start(loop);
                running++;
            }
        }

        state.saveAsync();
        plugin.getLogger().info("Started " + running + " loop(s) (" + paused + " paused).");
    }

    private void start(Loop loop) {
        long interval = loop.intervalTicks();
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> tick(loop.key()),
                interval, // first message one interval after (re)start
                interval);
        tasks.put(loop.key(), task);
        markNextFire(loop.key(), interval);
    }

    /** Record when this loop is next due, saturating rather than wrapping on a huge interval. */
    private void markNextFire(String key, long interval) {
        long due = (long) Bukkit.getCurrentTick() + interval;
        nextFireTick.put(key, (int) Math.min(due, Integer.MAX_VALUE));
    }

    /** The timer callback: send the due message, unless the server is empty. */
    private void tick(String key) {
        Optional<Loop> found = store.loop(key);
        if (found.isEmpty()) {
            return; // The loop went away mid-tick; the next rebuild will clean the task up.
        }
        Loop loop = found.get();
        markNextFire(key, loop.intervalTicks());

        if (Bukkit.getOnlinePlayers().isEmpty()) {
            // Nobody would see it, so don't burn the message: stay put and try next interval.
            return;
        }

        sendCurrent(loop);
        state.saveAsync();
    }

    /** Send the message the loop is currently on and advance by one, wrapping. */
    private Sent sendCurrent(Loop loop) {
        LoopState loopState = state.getOrCreate(loop.key());
        List<Announcement> entries = loop.entries();
        int index = loopState.nextIndex;
        if (index < 0 || index >= entries.size()) {
            index = 0;
        }

        Announcement announcement = entries.get(index);
        service.fire(announcement, List.of(), "loop:" + loop.id());
        loopState.nextIndex = (index + 1) % entries.size();
        return new Sent(index + 1, announcement.id());
    }

    /**
     * Send the loop's next message right now. A running loop's timer restarts, so
     * the following automatic send is a full interval away and never bunches up
     * behind this one. A paused loop sends and stays paused.
     */
    public NextResult next(Loop loop) {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return NextResult.noPlayers();
        }

        Sent sent = sendCurrent(loop);

        if (!isPaused(loop)) {
            cancel(loop.key());
            start(loop);
        }
        state.saveAsync();
        return NextResult.sent(sent.number(), sent.announcementId());
    }

    /** Stop a loop's timer, keeping its position for whenever it resumes. */
    public boolean pause(Loop loop) {
        LoopState loopState = state.getOrCreate(loop.key());
        if (loopState.paused) {
            return false;
        }
        loopState.paused = true;
        cancel(loop.key());
        state.saveAsync();
        return true;
    }

    /** Restart a paused loop's timer; its first message is one interval from now. */
    public boolean resume(Loop loop) {
        LoopState loopState = state.getOrCreate(loop.key());
        if (!loopState.paused) {
            return false;
        }
        loopState.paused = false;
        start(loop);
        state.saveAsync();
        return true;
    }

    public boolean isPaused(Loop loop) {
        LoopState loopState = state.peek(loop.key());
        return loopState != null && loopState.paused;
    }

    /** The 0-based position of the message this loop will send next. */
    public int nextIndex(Loop loop) {
        LoopState loopState = state.peek(loop.key());
        if (loopState == null) {
            return 0;
        }
        int index = loopState.nextIndex;
        return (index < 0 || index >= loop.entries().size()) ? 0 : index;
    }

    /** Seconds until the next automatic send, or empty while the loop is paused. */
    public OptionalLong secondsUntilNext(Loop loop) {
        if (isPaused(loop)) {
            return OptionalLong.empty();
        }
        Integer due = nextFireTick.get(loop.key());
        if (due == null) {
            return OptionalLong.empty();
        }
        long ticks = Math.max(0L, (long) due - Bukkit.getCurrentTick());
        return OptionalLong.of((long) Math.ceil(ticks / 20.0));
    }

    private void cancel(String key) {
        BukkitTask task = tasks.remove(key);
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // A task may already be cancelled during shutdown.
            }
        }
        nextFireTick.remove(key);
    }

    /** Cancel and forget every timer (called on disable and before each rebuild). */
    public void cancelAll() {
        for (BukkitTask task : tasks.values()) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // A task may already be cancelled during shutdown.
            }
        }
        tasks.clear();
        nextFireTick.clear();
    }

    /** What {@link #next(Loop)} did: either a message went out, or nobody was online. */
    public record NextResult(boolean sent, int number, String announcementId) {

        static NextResult sent(int number, String announcementId) {
            return new NextResult(true, number, announcementId);
        }

        static NextResult noPlayers() {
            return new NextResult(false, 0, "");
        }
    }

    private record Sent(int number, String announcementId) {
    }
}
