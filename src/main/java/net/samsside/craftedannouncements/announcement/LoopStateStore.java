package net.samsside.craftedannouncements.announcement;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Remembers where each loop is up to, in {@code loop-state.yml}, so position and
 * paused state survive a reload and a restart.
 *
 * <p>A handful of integers and booleans written at most once per loop interval and
 * never queried at scale: a small YAML file is the right storage here, and a
 * database would be plumbing with no benefit. The file is plugin-owned - it is not
 * a bundled resource and is never auto-merged.
 *
 * <p>Threading: the in-memory map is read and mutated on the <b>main thread only</b>.
 * Saving takes a deep-copy snapshot on the main thread and writes it off-thread, so
 * a write never blocks the server and never observes a half-updated map. Each
 * snapshot carries a generation number and the writer skips any snapshot older than
 * one already on disk, so out-of-order async writes cannot leave stale data behind.
 */
public final class LoopStateStore {

    private static final String FILE = "loop-state.yml";
    private static final String TMP_FILE = "loop-state.yml.tmp";
    private static final String ROOT = "loops";

    private static final List<String> HEADER = List.of(
            "Managed by CraftedAnnouncements - remembers where each loop is up to.",
            "Safe to delete (every loop then starts from its first message, unpaused).",
            "Do not edit while the server is running.",
            "next-index: 0 = the first message in the loop's list.");

    private final JavaPlugin plugin;

    /** Keyed by lowercase loop id. Main-thread access only. */
    private final Map<String, LoopState> states = new LinkedHashMap<>();

    private final AtomicLong generation = new AtomicLong();
    private final Object writeLock = new Object();
    private long lastWrittenGeneration; // guarded by writeLock

    public LoopStateStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Read the file. Called <b>once, on enable</b> - never on reload, because after
     * enable the in-memory map is the authority and the file only ever trails it.
     */
    public void load() {
        Logger log = plugin.getLogger();
        states.clear();

        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            log.info("No " + FILE + " yet; all loops start from their first message.");
            return;
        }

        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(file);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to parse " + FILE + "; every loop will start from its "
                    + "first message. The unreadable file is being kept for you to inspect.", e);
            quarantine(file, log);
            return;
        }

        ConfigurationSection root = cfg.getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            String key = id.toLowerCase(Locale.ROOT);
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                log.warning("Loop state for '" + id + "' is not a configuration section; "
                        + "that loop will start from its first message.");
                continue;
            }

            int nextIndex = 0;
            if (section.isSet("next-index")) {
                if (section.isInt("next-index")) {
                    nextIndex = section.getInt("next-index");
                } else {
                    log.warning("Loop state for '" + id + "' has a non-numeric 'next-index'; "
                            + "starting that loop from its first message.");
                }
            }

            boolean paused = false;
            if (section.isSet("paused")) {
                if (section.isBoolean("paused")) {
                    paused = section.getBoolean("paused");
                } else {
                    log.warning("Loop state for '" + id + "' has a non-boolean 'paused'; "
                            + "treating that loop as running.");
                }
            }

            List<String> entries = new ArrayList<>();
            if (section.isSet("entries")) {
                if (section.isList("entries")) {
                    for (String entry : section.getStringList("entries")) {
                        entries.add(entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT));
                    }
                } else {
                    log.warning("Loop state for '" + id + "' has a non-list 'entries'; "
                            + "that loop will restart from its first message.");
                }
            }

            states.put(key, new LoopState(nextIndex, paused, entries));
        }

        log.info("Loaded saved state for " + states.size() + " loop(s) from " + FILE + ".");
    }

    /** Rename an unreadable file out of the way so the plugin can start clean. */
    private void quarantine(File file, Logger log) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        File broken = new File(plugin.getDataFolder(), FILE + ".broken-" + stamp);
        try {
            Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.warning("Renamed the unreadable " + FILE + " to " + broken.getName() + ".");
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not rename the unreadable " + FILE + ".", e);
        }
    }

    /** The state for a loop, creating a fresh one (position 0, running) if it has none. */
    LoopState getOrCreate(String key) {
        return states.computeIfAbsent(key, k -> new LoopState(0, false, List.of()));
    }

    /**
     * The state for a loop, or {@code null} if it has none. Never creates an entry,
     * so read-only callers (list, info, tab-completion) cannot mutate the map -
     * keeping "only the main thread writes" an enforced invariant, not a lucky one.
     */
    LoopState peek(String key) {
        return states.get(key);
    }

    /**
     * Write the current state off the main thread. The snapshot is taken here, on
     * the caller's (main) thread, so only the file write itself is async.
     */
    public void saveAsync() {
        Snapshot snapshot = snapshot();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(snapshot));
    }

    /** Write the current state on the calling thread. Used on disable, when async is too late. */
    public void saveNow() {
        write(snapshot());
    }

    /** Deep copy on the main thread, so the async writer never sees a mutating map. */
    private Snapshot snapshot() {
        Map<String, LoopState> copy = new LinkedHashMap<>(states.size());
        for (Map.Entry<String, LoopState> entry : states.entrySet()) {
            LoopState state = entry.getValue();
            copy.put(entry.getKey(), new LoopState(state.nextIndex, state.paused, List.copyOf(state.entries)));
        }
        return new Snapshot(generation.incrementAndGet(), copy);
    }

    private void write(Snapshot snapshot) {
        synchronized (writeLock) {
            if (snapshot.generation() <= lastWrittenGeneration) {
                // A newer snapshot already reached disk; this one would undo it.
                return;
            }
            lastWrittenGeneration = snapshot.generation();

            YamlConfiguration cfg = new YamlConfiguration();
            cfg.options().setHeader(HEADER);
            for (Map.Entry<String, LoopState> entry : snapshot.states().entrySet()) {
                LoopState state = entry.getValue();
                String base = ROOT + "." + entry.getKey();
                cfg.set(base + ".next-index", state.nextIndex);
                cfg.set(base + ".paused", state.paused);
                cfg.set(base + ".entries", new ArrayList<>(state.entries));
            }

            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                plugin.getLogger().warning("Could not create " + folder + "; " + FILE + " was not saved.");
                return;
            }

            Path tmp = new File(folder, TMP_FILE).toPath();
            Path target = new File(folder, FILE).toPath();
            try {
                cfg.save(tmp.toFile());
                move(tmp, target);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not save " + FILE + "; loop positions may be out of date after a restart.", e);
            }
        }
    }

    /** Replace the live file in one step where the filesystem allows it. */
    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Mutable, main-thread-only state for one loop. */
    static final class LoopState {
        int nextIndex;
        boolean paused;
        List<String> entries;

        LoopState(int nextIndex, boolean paused, List<String> entries) {
            this.nextIndex = nextIndex;
            this.paused = paused;
            this.entries = entries;
        }
    }

    private record Snapshot(long generation, Map<String, LoopState> states) {
    }
}
