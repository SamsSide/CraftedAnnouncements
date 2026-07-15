package net.samsside.craftedannouncements.command;

import net.samsside.craftedannouncements.announcement.AnnouncementService;
import net.samsside.craftedannouncements.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

/**
 * {@code /broadcast} (aliases {@code /announce}, {@code /bc}, {@code /ac}). Sends
 * the typed text to every online player, with the configurable broadcast prefix.
 * Free-text, so there is no tab-completion.
 */
public final class BroadcastCommand implements CommandExecutor, TabCompleter {

    public static final String COMMAND_NAME = "broadcast";
    public static final String PERM = "craftedannouncements.broadcast";

    private final Messages messages;
    private final AnnouncementService service;

    public BroadcastCommand(Messages messages, AnnouncementService service) {
        this.messages = messages;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            messages.send(sender, "no-permission");
            return true;
        }
        String text = String.join(" ", args);
        if (text.isBlank()) {
            messages.send(sender, "broadcast-usage");
            return true;
        }
        service.broadcast(text);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return Collections.emptyList();
    }
}
