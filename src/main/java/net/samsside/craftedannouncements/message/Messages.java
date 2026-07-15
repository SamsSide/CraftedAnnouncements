package net.samsside.craftedannouncements.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.samsside.craftedannouncements.config.YamlFiles;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Loads messages.yml and renders every user-facing string. No player-facing text
 * is hard-coded in Java; a missing key falls back to the key name so the gap is
 * obvious rather than silent. A message value may be a single string or a YAML
 * list of lines (rendered as a multi-line component).
 */
public final class Messages {

    private final JavaPlugin plugin;
    private volatile YamlConfiguration config;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)load messages.yml, running auto-merge. */
    public void load() {
        this.config = YamlFiles.loadWithDefaults(plugin, "messages.yml");
        plugin.getLogger().info("Loaded messages.yml (config-version "
                + this.config.getInt("config-version", 1) + ").");
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

    /** Build an unparsed placeholder resolver ({@code <name>} -> literal value). */
    public static TagResolver placeholder(String name, String value) {
        return Text.placeholder(name, value);
    }

    /** Build a placeholder resolver that inserts an already-rendered Component, colours intact. */
    public static TagResolver placeholder(String name, Component value) {
        return Text.placeholder(name, value);
    }
}
