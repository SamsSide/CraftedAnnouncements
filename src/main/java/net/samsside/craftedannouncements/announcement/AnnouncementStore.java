package net.samsside.craftedannouncements.announcement;

import net.samsside.craftedannouncements.config.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and holds the preset announcements from announcements.yml. The parsed map
 * is rebuilt wholesale on {@link #load()} and swapped in atomically, so a trigger
 * fired mid-reload never sees a half-built state.
 */
public final class AnnouncementStore {

    private static final String FILE = "announcements.yml";
    private static final String ROOT = "announcements";
    private static final Pattern ARG_TOKEN = Pattern.compile("\\{(\\d+)\\}");

    private final JavaPlugin plugin;
    private volatile Map<String, Announcement> announcements = new LinkedHashMap<>();

    public AnnouncementStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)load announcements.yml. The {@code announcements} root is excluded from auto-merge. */
    public void load() {
        Logger log = plugin.getLogger();
        YamlConfiguration cfg = YamlFiles.loadWithDefaults(plugin, FILE, ROOT);

        Map<String, Announcement> parsed = new LinkedHashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection(ROOT);
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    log.warning("Skipping announcement '" + id + "': it is not a configuration section.");
                    continue;
                }
                parse(id, section, log).ifPresent(a -> parsed.put(id.toLowerCase(java.util.Locale.ROOT), a));
            }
        }

        this.announcements = parsed;
        long scheduled = parsed.values().stream().filter(Announcement::isScheduled).count();
        log.info("Loaded " + parsed.size() + " announcement(s) from " + FILE + " ("
                + scheduled + " scheduled).");
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
                scheduleTicks, intervalDisplay, sound));
    }

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

    /** Look up a preset by id (case-insensitive). */
    public Optional<Announcement> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(announcements.get(id.toLowerCase(java.util.Locale.ROOT)));
    }

    /** All presets, in file order. */
    public Collection<Announcement> all() {
        return announcements.values();
    }

    /** Ids of all presets, in file order (used for tab-completion). */
    public Collection<String> ids() {
        List<String> ids = new ArrayList<>(announcements.size());
        for (Announcement a : announcements.values()) {
            ids.add(a.id());
        }
        return ids;
    }

    private record Prepared(List<String> lines, int argCount) {
    }
}
