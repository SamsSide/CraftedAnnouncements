package net.samsside.craftedannouncements.announcement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.samsside.craftedannouncements.config.PluginConfig;
import net.samsside.craftedannouncements.message.Messages;
import net.samsside.craftedannouncements.message.Text;
import net.samsside.craftedannouncements.placeholder.PapiHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Sends announcements. Every outgoing line runs the same pipeline, in order:
 * <ol>
 *   <li>positional {@code {n}} arguments are inserted (injection-safe, unparsed);</li>
 *   <li>PlaceholderAPI resolves {@code %..%} per recipient (if installed);</li>
 *   <li>the result is rendered supporting both legacy codes and MiniMessage;</li>
 *   <li>the component is sent.</li>
 * </ol>
 * All sends and sound plays happen on the main thread (callers are the command
 * handlers and the Bukkit scheduler, both main-thread).
 */
public final class AnnouncementService {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Messages messages;
    private final PapiHook papi;

    public AnnouncementService(JavaPlugin plugin, PluginConfig config, Messages messages, PapiHook papi) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.papi = papi;
    }

    /**
     * Fire a preset announcement to every online player: each message line, then the
     * sound. {@code args} must already match {@link Announcement#argCount()}.
     *
     * @param context "trigger" or "schedule", for the console log line.
     */
    public void fire(Announcement announcement, List<String> args, String context) {
        TagResolver[] resolvers = resolvers(args);

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String line : announcement.preparedLines()) {
                player.sendMessage(renderLine(line, player, resolvers));
            }
            announcement.sound().ifPresent(spec -> player.playSound(spec.sound()));
        }

        if (config.logAnnouncementsToConsole()) {
            for (String line : announcement.preparedLines()) {
                plugin.getLogger().info("[announce:" + context + ":" + announcement.id() + "] "
                        + Text.plain(renderLine(line, null, resolvers)));
            }
        }
    }

    /** Send an ad-hoc /broadcast (or /announce) to every online player, with the broadcast prefix. */
    public void broadcast(String rawText) {
        Component prefix = messages.component("broadcast-prefix");
        TagResolver[] none = new TagResolver[0];

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(prefix.append(renderLine(rawText, player, none)));
        }

        if (config.logAnnouncementsToConsole()) {
            plugin.getLogger().info("[broadcast] " + Text.plain(prefix.append(renderLine(rawText, null, none))));
        }
    }

    private Component renderLine(String line, Player recipient, TagResolver[] resolvers) {
        String resolved = papi.apply(recipient, line);
        return Text.render(resolved, resolvers);
    }

    private TagResolver[] resolvers(List<String> args) {
        TagResolver[] resolvers = new TagResolver[args.size()];
        for (int i = 0; i < args.size(); i++) {
            resolvers[i] = Text.placeholder("arg" + i, args.get(i));
        }
        return resolvers;
    }
}
