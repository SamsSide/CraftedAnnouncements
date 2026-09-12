package net.samsside.craftedannouncements.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.samsside.craftedannouncements.config.YamlFiles;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Loads messages.yml and renders every user-facing string. No player-facing text
 * is hard-coded in Java; a missing key falls back to the key name so the gap is
 * obvious rather than silent. A message value may be a single string or a YAML
 * list of lines (rendered as a multi-line component).
 */
public final class Messages {

    private static final String FILE = "messages.yml";
    private static final String USAGE_KEY = "craftedannouncements-usage";
    private static final int CURRENT_VERSION = 2;

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3_600L;
    private static final long SECONDS_PER_DAY = 86_400L;

    /**
     * The config-version 1 default for {@link #USAGE_KEY}. This is a migration
     * fingerprint, not output: it is only ever compared against, so that a server
     * that never edited its usage text gets the new "loop" line, while an edited
     * one is left alone.
     */
    private static final List<String> V1_DEFAULT_USAGE = List.of(
            "<gray><strikethrough>                    </strikethrough>",
            "<aqua>/craftedannouncements reload <gray>- reload all config files",
            "<aqua>/craftedannouncements list <gray>- list preset announcements",
            "<aqua>/craftedannouncements trigger <id> [args...] <gray>- fire a preset",
            "<gray><strikethrough>                    </strikethrough>");

    private final JavaPlugin plugin;
    private volatile YamlConfiguration config;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)load messages.yml, running auto-merge and any schema migration. */
    public void load() {
        YamlConfiguration loaded = YamlFiles.loadWithDefaults(plugin, FILE);
        migrate(loaded);
        this.config = loaded;
        plugin.getLogger().info("Loaded " + FILE + " (config-version "
                + loaded.getInt("config-version", 1) + ").");
    }

    /**
     * Bring a pre-loop messages.yml up to config-version 2. The only value that
     * changes is the root usage block, and only when the server never customised
     * it; everything else the update needs was already added by the auto-merge.
     */
    private void migrate(YamlConfiguration loaded) {
        if (loaded.getInt("config-version", 1) >= CURRENT_VERSION) {
            return;
        }

        boolean usageUpdated = false;
        if (loaded.isList(USAGE_KEY) && V1_DEFAULT_USAGE.equals(loaded.getStringList(USAGE_KEY))) {
            Object bundled = YamlFiles.bundledDefaults(plugin, FILE).get(USAGE_KEY);
            if (bundled != null) {
                loaded.set(USAGE_KEY, bundled);
                usageUpdated = true;
            }
        }
        loaded.set("config-version", CURRENT_VERSION);

        try {
            loaded.save(new File(plugin.getDataFolder(), FILE));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not save the migrated " + FILE + "; the new values apply in memory only.", e);
        }
        plugin.getLogger().info("Migrated " + FILE + " to config-version " + CURRENT_VERSION
                + " (usage text " + (usageUpdated ? "updated" : "left as customised") + ").");
    }

    private String raw(String key) {
        YamlConfiguration cfg = this.config;
        if (cfg == null) {
            return key;
        }
        if (cfg.isList(key)) {
            List<String> lines = cfg.getStringList(key);
            return lines.isEmpty() ? key : String.join("\n", lines);
        }
        String value = cfg.getString(key);
        return value == null ? key : value;
    }

    private Component prefix() {
        return Text.render(raw("prefix"));
    }

    /** Render a message key to a Component (no prefix), resolving the given placeholders. */
    public Component component(String key, TagResolver... resolvers) {
        return Text.render(raw(key), resolvers);
    }

    /** Send a prefixed, formatted message to a sender. */
    public void send(CommandSender target, String key, TagResolver... resolvers) {
        target.sendMessage(prefix().append(component(key, resolvers)));
    }

    /** Send a formatted message with no prefix (e.g. multi-line usage blocks). */
    public void sendNoPrefix(CommandSender target, String key, TagResolver... resolvers) {
        target.sendMessage(component(key, resolvers));
    }

    /**
     * Render a countdown such as {@code 1h 5m 30s} from the {@code duration-*} keys:
     * largest unit first, zero units omitted, single space between. Zero or less
     * renders as {@code duration-seconds} with a value of 0.
     */
    public Component duration(long totalSeconds) {
        if (totalSeconds <= 0L) {
            return component("duration-seconds", placeholder("value", "0"));
        }

        long remaining = totalSeconds;
        long days = remaining / SECONDS_PER_DAY;
        remaining %= SECONDS_PER_DAY;
        long hours = remaining / SECONDS_PER_HOUR;
        remaining %= SECONDS_PER_HOUR;
        long minutes = remaining / SECONDS_PER_MINUTE;
        long seconds = remaining % SECONDS_PER_MINUTE;

        List<Component> parts = new ArrayList<>(4);
        addUnit(parts, "duration-days", days);
        addUnit(parts, "duration-hours", hours);
        addUnit(parts, "duration-minutes", minutes);
        addUnit(parts, "duration-seconds", seconds);

        return Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.text(" ")), parts);
    }

    private void addUnit(List<Component> parts, String key, long value) {
        if (value > 0L) {
            parts.add(component(key, placeholder("value", String.valueOf(value))));
        }
    }

    /** Build an unparsed placeholder resolver ({@code <name>} -> literal value). */
    public static TagResolver placeholder(String name, String value) {
        return Text.placeholder(name, value);
    }

    /** Build a placeholder resolver that inserts an already-rendered Component, colours intact. */
    public static TagResolver placeholder(String name, Component value) {
        return Text.placeholder(name, value);
    }
}
