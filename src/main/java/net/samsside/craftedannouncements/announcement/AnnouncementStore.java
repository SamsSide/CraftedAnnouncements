package net.samsside.craftedannouncements.announcement;

import net.samsside.craftedannouncements.config.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and holds the preset announcements and loops from announcements.yml.
 * Everything is rebuilt wholesale on {@link #load()} and swapped in as a single
 * immutable snapshot, so a trigger or a loop tick that lands mid-reload never sees
 * presets and loops out of sync with each other.
 */
public final class AnnouncementStore {

    private static final String FILE = "announcements.yml";
    private static final String ROOT = "announcements";
    private static final String LOOPS_ROOT = "loops";
    /** The key inside a loop holding its ordered preset ids (same word, different place). */
    private static final String LOOP_ENTRIES = "announcements";
    private static final Pattern ARG_TOKEN = Pattern.compile("\\{(\\d+)\\}");

    private final JavaPlugin plugin;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    public AnnouncementStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)load announcements.yml. Both the {@code announcements} and {@code loops}
     * roots are excluded from auto-merge: a server that updates the plugin must
     * never find the bundled example loop injected into its own file.
     */
    public void load() {
        Logger log = plugin.getLogger();
        YamlConfiguration cfg = YamlFiles.loadWithDefaults(plugin, FILE, ROOT, LOOPS_ROOT);

        Map<String, Announcement> presets = parsePresets(cfg, log);
        List<LoopDef> definitions = parseLoops(cfg, presets, log);

        // A preset may be listed by several loops; the first one in file order owns it.
        Map<String, String> ownerByPreset = new LinkedHashMap<>();
        for (LoopDef definition : definitions) {
            for (String entryKey : definition.entryKeys()) {
                ownerByPreset.putIfAbsent(entryKey, definition.id());
            }
        }
        for (Map.Entry<String, String> owned : ownerByPreset.entrySet()) {
            Announcement preset = presets.get(owned.getKey());
            if (preset.isScheduled()) {
                log.warning("Preset '" + preset.id() + "' has an interval but is part of loop '"
                        + owned.getValue() + "'; its own interval is ignored while it is in a loop.");
            }
            presets.put(owned.getKey(), preset.joinedToLoop(owned.getValue()));
        }

        // Built last, so every loop holds the final (loop-owned, un-scheduled) presets.
        Map<String, Loop> loops = new LinkedHashMap<>();
        for (LoopDef definition : definitions) {
            List<Announcement> entries = new ArrayList<>(definition.entryKeys().size());
            for (String entryKey : definition.entryKeys()) {
                entries.add(presets.get(entryKey));
            }
            loops.put(definition.key(), new Loop(definition.id(), definition.intervalTicks(),
                    definition.intervalDisplay(), entries));
        }

        this.snapshot = new Snapshot(
                Collections.unmodifiableMap(presets),
                Collections.unmodifiableMap(loops));

        long scheduled = presets.values().stream().filter(Announcement::isScheduled).count();
        log.info("Loaded " + presets.size() + " announcement(s) from " + FILE + " ("
                + scheduled + " scheduled) and " + loops.size() + " loop(s).");
    }

    // ------------------------------------------------------------- presets

    private Map<String, Announcement> parsePresets(YamlConfiguration cfg, Logger log) {
        Map<String, Announcement> parsed = new LinkedHashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection(ROOT);
        if (root == null) {
            return parsed;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                log.warning("Skipping announcement '" + id + "': it is not a configuration section.");
                continue;
            }
            parse(id, section, log).ifPresent(a -> parsed.put(id.toLowerCase(Locale.ROOT), a));
        }
        return parsed;
    }

    private Optional<Announcement> parse(String id, ConfigurationSection section, Logger log) {
        List<String> lines = readMessage(section);
        if (lines.isEmpty()) {
            log.warning("Skipping announcement '" + id + "': it has no 'message'.");
            return Optional.empty();
        }

        Prepared prepared = prepare(lines);

        String intervalRaw = section.getString("interval");
        Optional<Long> scheduleTicks = Optional.empty();
        String intervalDisplay = null;
        if (intervalRaw != null && !intervalRaw.isBlank()) {
            intervalDisplay = intervalRaw.trim();
            Optional<Long> ticks = DurationParser.parseTicks(intervalRaw);
            if (ticks.isEmpty()) {
                log.warning("Announcement '" + id + "' has an invalid interval '" + intervalRaw
                        + "'; it will not be scheduled (but can still be triggered manually).");
            } else if (prepared.argCount() > 0) {
                log.warning("Announcement '" + id + "' has an interval but also uses {arguments}; it cannot be "
                        + "scheduled (nothing would fill the arguments). Trigger it manually instead.");
            } else {
                scheduleTicks = ticks;
            }
        }

        Optional<SoundSpec> sound = SoundSpec.fromSection(section.getConfigurationSection("sound"), id, log);

        return Optional.of(new Announcement(id, prepared.lines(), prepared.argCount(),
                scheduleTicks, intervalDisplay, sound, Optional.empty()));
    }

    // --------------------------------------------------------------- loops

    /**
     * Parse the {@code loops:} section into validated definitions. Every problem is
     * a warning that skips one loop or one entry - a bad loop can never stop the
     * rest of the file, or the server, from loading.
     */
    private List<LoopDef> parseLoops(YamlConfiguration cfg, Map<String, Announcement> presets, Logger log) {
        List<LoopDef> definitions = new ArrayList<>();
        ConfigurationSection root = cfg.getConfigurationSection(LOOPS_ROOT);
        if (root == null) {
            return definitions;
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                log.warning("Skipping loop '" + id + "': it is not a configuration section.");
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            if (!seenKeys.add(key)) {
                log.warning("Skipping loop '" + id + "': another loop already uses this id "
                        + "(ids are case-insensitive).");
                continue;
            }

            String intervalRaw = section.getString("interval");
            if (intervalRaw == null || intervalRaw.isBlank()) {
                log.warning("Skipping loop '" + id + "': it has no 'interval'.");
                continue;
            }
            Optional<Long> ticks = DurationParser.parseTicks(intervalRaw);
            if (ticks.isEmpty()) {
                log.warning("Skipping loop '" + id + "': invalid interval '" + intervalRaw + "'.");
                continue;
            }

            if (!section.isList(LOOP_ENTRIES)) {
                log.warning("Skipping loop '" + id + "': it has no 'announcements' list.");
                continue;
            }

            List<String> entryKeys = new ArrayList<>();
            for (String rawEntry : section.getStringList(LOOP_ENTRIES)) {
                String entry = rawEntry == null ? "" : rawEntry.trim().toLowerCase(Locale.ROOT);
                if (entry.isEmpty() || !presets.containsKey(entry)) {
                    log.warning("Loop '" + id + "': unknown preset '" + (rawEntry == null ? "" : rawEntry.trim())
                            + "' - skipped.");
                    continue;
                }
                if (presets.get(entry).argCount() > 0) {
                    log.warning("Loop '" + id + "': preset '" + entry
                            + "' uses {arguments} and cannot be looped - skipped.");
                    continue;
                }
                entryKeys.add(entry);
            }

            if (entryKeys.isEmpty()) {
                log.warning("Skipping loop '" + id + "': it has no usable presets.");
                continue;
            }

            definitions.add(new LoopDef(id, key, ticks.get(), intervalRaw.trim(), List.copyOf(entryKeys)));
        }
        return definitions;
    }

    // ------------------------------------------------------------- reading

    private static List<String> readMessage(ConfigurationSection section) {
        if (section.isList("message")) {
            return new ArrayList<>(section.getStringList("message"));
        }
        if (section.isString("message")) {
            List<String> single = new ArrayList<>(1);
            single.add(section.getString("message"));
            return single;
        }
        return List.of();
    }

    /** Rewrite {@code {n}} tokens to {@code <argN>} tags and find the argument count. */
    private static Prepared prepare(List<String> lines) {
        int maxIndex = -1;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher m = ARG_TOKEN.matcher(line == null ? "" : line);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int idx;
                try {
                    idx = Integer.parseInt(m.group(1));
                } catch (NumberFormatException tooBig) {
                    // An absurd index like {99999999999}: leave it literal.
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                if (idx > maxIndex) {
                    maxIndex = idx;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement("<arg" + idx + ">"));
            }
            m.appendTail(sb);
            out.add(sb.toString());
        }
        return new Prepared(out, maxIndex + 1);
    }

    // ------------------------------------------------------------- lookups

    /** Look up a preset by id (case-insensitive). */
    public Optional<Announcement> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.presets().get(id.toLowerCase(Locale.ROOT)));
    }

    /** All presets, in file order. */
    public Collection<Announcement> all() {
        return snapshot.presets().values();
    }

    /** Ids of all presets, in file order (used for tab-completion). */
    public Collection<String> ids() {
        Map<String, Announcement> presets = snapshot.presets();
        List<String> ids = new ArrayList<>(presets.size());
        for (Announcement a : presets.values()) {
            ids.add(a.id());
        }
        return ids;
    }

    /** Look up a loop by id (case-insensitive). */
    public Optional<Loop> loop(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.loops().get(id.toLowerCase(Locale.ROOT)));
    }

    /** All loaded loops, in file order. */
    public Collection<Loop> loops() {
        return snapshot.loops().values();
    }

    /** Display ids of all loaded loops, in file order (used for tab-completion). */
    public Collection<String> loopIds() {
        Map<String, Loop> loops = snapshot.loops();
        List<String> ids = new ArrayList<>(loops.size());
        for (Loop loop : loops.values()) {
            ids.add(loop.id());
        }
        return ids;
    }

    /** Presets and loops always swap together, never one without the other. */
    private record Snapshot(Map<String, Announcement> presets, Map<String, Loop> loops) {
    }

    /** A validated loop before its presets are resolved to their final objects. */
    private record LoopDef(String id, String key, long intervalTicks, String intervalDisplay,
                           List<String> entryKeys) {
    }

    private record Prepared(List<String> lines, int argCount) {
    }
}
