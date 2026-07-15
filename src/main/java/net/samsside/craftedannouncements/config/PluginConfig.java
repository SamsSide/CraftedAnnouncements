package net.samsside.craftedannouncements.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Typed view over config.yml. Re-read on every reload. */
public final class PluginConfig {

    private static final boolean DEFAULT_LOG_TO_CONSOLE = false;

    private final JavaPlugin plugin;

    private volatile boolean logAnnouncementsToConsole = DEFAULT_LOG_TO_CONSOLE;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)load config.yml, running auto-merge. */
    public void load() {
        YamlConfiguration cfg = YamlFiles.loadWithDefaults(plugin, "config.yml");
        this.logAnnouncementsToConsole =
                cfg.getBoolean("log-announcements-to-console", DEFAULT_LOG_TO_CONSOLE);

        plugin.getLogger().info("Loaded config.yml (config-version " + cfg.getInt("config-version", 1)
                + "): log-announcements-to-console=" + logAnnouncementsToConsole + ".");
    }

    /** Whether every announcement is also echoed to the server console. */
    public boolean logAnnouncementsToConsole() {
        return logAnnouncementsToConsole;
    }
}
