package net.samsside.craftedannouncements.command;

import net.samsside.craftedannouncements.CraftedAnnouncements;
import net.samsside.craftedannouncements.announcement.Announcement;
import net.samsside.craftedannouncements.announcement.AnnouncementService;
import net.samsside.craftedannouncements.announcement.AnnouncementStore;
import net.samsside.craftedannouncements.announcement.ArgumentParser;
import net.samsside.craftedannouncements.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;

/**
 * The {@code /craftedannouncements} (alias {@code /ca}) admin root command:
 * {@code reload}, {@code list}, and {@code trigger <id> [args...]}. Every
 * sub-command checks its own permission before acting, and tab-completion only
 * suggests entries the sender is allowed to use.
 */
public final class CraftedAnnouncementsCommand implements CommandExecutor, TabCompleter {

    public static final String COMMAND_NAME = "craftedannouncements";

    private static final String PERM_RELOAD = "craftedannouncements.reload";
    private static final String PERM_LIST = "craftedannouncements.list";
    private static final String PERM_TRIGGER = "craftedannouncements.trigger";

    private static final int MAX_ID_LENGTH = 64;

    private final CraftedAnnouncements plugin;
    private final Messages messages;
    private final AnnouncementStore store;
    private final AnnouncementService service;

    public CraftedAnnouncementsCommand(CraftedAnnouncements plugin, Messages messages,
                                       AnnouncementStore store, AnnouncementService service) {
        this.plugin = plugin;
        this.messages = messages;
        this.store = store;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            messages.sendNoPrefix(sender, "craftedannouncements-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "list" -> handleList(sender);
            case "trigger" -> handleTrigger(sender, args);
            default -> messages.sendNoPrefix(sender, "craftedannouncements-usage");
        }
        return true;
    }

    // --------------------------------------------------------------- reload

    private void handleReload(CommandSender sender) {
        if (!has(sender, PERM_RELOAD)) {
            return;
        }
        try {
            plugin.reloadAll();
            messages.send(sender, "reload-success");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Reload failed.", t);
            messages.send(sender, "reload-failed");
        }
    }

    // ----------------------------------------------------------------- list

    private void handleList(CommandSender sender) {
        if (!has(sender, PERM_LIST)) {
            return;
        }
        if (store.all().isEmpty()) {
            messages.send(sender, "list-empty");
            return;
        }
        messages.send(sender, "list-header",
                Messages.placeholder("count", String.valueOf(store.all().size())));
        for (Announcement a : store.all()) {
            var state = a.isScheduled()
                    ? messages.component("list-entry-scheduled",
                    Messages.placeholder("interval", a.intervalDisplay()))
                    : messages.component("list-entry-manual");
            messages.sendNoPrefix(sender, "list-entry",
                    Messages.placeholder("id", a.id()),
                    Messages.placeholder("state", state));
        }
    }

    // -------------------------------------------------------------- trigger

    private void handleTrigger(CommandSender sender, String[] args) {
        if (!has(sender, PERM_TRIGGER)) {
            return;
        }
        if (args.length < 2) {
            messages.send(sender, "trigger-usage");
            return;
        }
        String id = args[1];
        if (id.length() > MAX_ID_LENGTH) {
            messages.send(sender, "trigger-unknown-id", Messages.placeholder("id", id));
            return;
        }
        Optional<Announcement> found = store.get(id);
        if (found.isEmpty()) {
            messages.send(sender, "trigger-unknown-id", Messages.placeholder("id", id));
            return;
        }
        Announcement announcement = found.get();

        String joined = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "";
        List<String> parsed = ArgumentParser.parse(joined);

        if (parsed.size() != announcement.argCount()) {
            messages.send(sender, "trigger-arg-count",
                    Messages.placeholder("id", announcement.id()),
                    Messages.placeholder("expected", String.valueOf(announcement.argCount())),
                    Messages.placeholder("given", String.valueOf(parsed.size())));
            return;
        }

        service.fire(announcement, parsed, "trigger");
        messages.send(sender, "trigger-success", Messages.placeholder("id", announcement.id()));
    }

    // ------------------------------------------------------------- helpers

    private boolean has(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages.send(sender, "no-permission");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(3);
            if (sender.hasPermission(PERM_RELOAD)) {
                subs.add("reload");
            }
            if (sender.hasPermission(PERM_LIST)) {
                subs.add("list");
            }
            if (sender.hasPermission(PERM_TRIGGER)) {
                subs.add("trigger");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trigger") && sender.hasPermission(PERM_TRIGGER)) {
            return filter(new ArrayList<>(store.ids()), args[1]);
        }
        return new ArrayList<>();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
