package net.samsside.craftedannouncements.placeholder;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Optional PlaceholderAPI bridge. CraftedAnnouncements only <em>consumes</em>
 * placeholders (it exposes none of its own), resolving {@code %..%} inside
 * announcement and broadcast text per recipient when PlaceholderAPI is installed.
 * When it is absent, {@link #apply} returns the text unchanged and the
 * PlaceholderAPI classes are never referenced.
 */
public final class PapiHook {

    private final JavaPlugin plugin;

    public PapiHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Whether PlaceholderAPI is currently enabled on the server. */
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * Resolve placeholders in {@code text} for {@code viewer} (which may be null for
     * a console/global context). Any failure is swallowed to the original text so a
     * bad placeholder never blocks an announcement.
     */
    public String apply(OfflinePlayer viewer, String text) {
        if (text == null || text.isEmpty() || !available()) {
            return text;
        }
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(viewer, text);
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI failed to resolve a placeholder: " + t.getMessage());
            return text;
        }
    }
}
