package net.samsside.craftedannouncements.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads a YAML file from the plugin data folder, creating it from the bundled
 * default on first run and auto-merging any missing default keys (and their
 * comments) on subsequent loads. Existing user values and comments are preserved.
 *
 * <p>announcements.yml passes its {@code announcements} root as an excluded root:
 * plugin-owned keys such as {@code config-version} still merge in on update, but
 * the user's hand-written presets are never added to, re-created or overwritten.
 * Deleting a preset keeps it deleted.
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    /**
     * Load {@code fileName} from the data folder, writing the bundled default if
     * absent, then adding any default keys the user is missing (e.g. after a
     * plugin update, or if a key was deleted). The user's own values are never
     * overwritten.
     *
     * @param mergeExcludedRoots top-level sections the merge must never touch.
     * @return the merged configuration, ready to read from.
     */
    public static YamlConfiguration loadWithDefaults(JavaPlugin plugin, String fileName, String... mergeExcludedRoots) {
        File file = new File(plugin.getDataFolder(), fileName);

        if (!file.exists()) {
            plugin.saveResource(fileName, false);
            plugin.getLogger().info("Created default " + fileName + ".");
        }

        YamlConfiguration userConfig = new YamlConfiguration();
        userConfig.options().parseComments(true);
        try {
            userConfig.load(file);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to parse " + fileName + " - it may contain invalid YAML. "
                            + "Falling back to bundled defaults for this load; your file was NOT modified.", e);
            return loadBundledDefaults(plugin, fileName);
        }

        YamlConfiguration defaults = loadBundledDefaults(plugin, fileName);
        int added = mergeMissingKeys(defaults, userConfig, Set.of(mergeExcludedRoots));

        if (added > 0) {
            try {
                userConfig.save(file);
                plugin.getLogger().info("Merged " + added + " missing default key(s) into " + fileName + ".");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not save merged " + fileName + "; new keys apply in memory only.", e);
            }
        }

        return userConfig;
    }

    private static YamlConfiguration loadBundledDefaults(JavaPlugin plugin, String fileName) {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) {
                plugin.getLogger().warning("Bundled default " + fileName + " not found in jar.");
                return defaults;
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read bundled default " + fileName + ".", e);
        }
        return defaults;
    }

    /**
     * Copy every key present in {@code defaults} but missing from {@code target}
     * (including its comments) into {@code target}. Existing target values are
     * left untouched, and any path under an excluded root is skipped entirely.
     *
     * @return the number of keys added.
     */
    private static int mergeMissingKeys(YamlConfiguration defaults, YamlConfiguration target, Set<String> excludedRoots) {
        int added = 0;
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path)) {
                // Sections are created implicitly when a leaf underneath is set.
                continue;
            }
            if (excludedRoots.contains(rootOf(path))) {
                continue;
            }
            if (!target.isSet(path)) {
                target.set(path, defaults.get(path));
                List<String> comments = defaults.getComments(path);
                if (!comments.isEmpty()) {
                    target.setComments(path, comments);
                }
                added++;
            }
        }
        return added;
    }

    private static String rootOf(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }
}
