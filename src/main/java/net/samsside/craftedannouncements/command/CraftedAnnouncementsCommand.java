package net.samsside.craftedannouncements.command;

import net.kyori.adventure.text.Component;
import net.samsside.craftedannouncements.CraftedAnnouncements;
import net.samsside.craftedannouncements.announcement.Announcement;
import net.samsside.craftedannouncements.announcement.AnnouncementService;
import net.samsside.craftedannouncements.announcement.AnnouncementStore;
import net.samsside.craftedannouncements.announcement.ArgumentParser;
import net.samsside.craftedannouncements.announcement.Loop;
import net.samsside.craftedannouncements.announcement.LoopScheduler;
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
 * {@code reload}, {@code list}, {@code trigger <id> [args...]} and
 * {@code loop <next|pause|resume|info> <loop>}. Every sub-command checks its own
 * permission before acting - including before revealing whether a loop id exists -
 * and tab-completion only suggests entries the sender is allowed to use.
 */
public final class CraftedAnnouncementsCommand implements CommandExecutor, TabCompleter {

    public static final String COMMAND_NAME = "craftedannouncements";

    private static final String PERM_RELOAD = "craftedannouncements.reload";
    private static final String PERM_LIST = "craftedannouncements.list";
    private static final String PERM_TRIGGER = "craftedannouncements.trigger";
    private static final String PERM_LOOP_NEXT = "craftedannouncements.loop.next";
    private static final String PERM_LOOP_PAUSE = "craftedannouncements.loop.pause";
    private static final String PERM_LOOP_RESUME = "craftedannouncements.loop.resume";
    private static final String PERM_LOOP_INFO = "craftedannouncements.loop.info";

    private static final int MAX_ID_LENGTH = 64;

    private final CraftedAnnouncements plugin;
    private final Messages messages;
    private final AnnouncementStore store;
    private final AnnouncementService service;
    private final LoopScheduler loopScheduler;

    public CraftedAnnouncementsCommand(CraftedAnnouncements plugin, Messages messages,
                                       AnnouncementStore store, AnnouncementService service,
                                       LoopScheduler loopScheduler) {
        this.plugin = plugin;
        this.messages = messages;
        this.store = store;
        this.service = service;
        this.loopScheduler = loopScheduler;
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
            case "loop" -> handleLoop(sender, args);
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
            messages.sendNoPrefix(sender, "list-entry",
                    Messages.placeholder("id", a.id()),
                    Messages.placeholder("state", presetState(a)));
        }

        if (store.loops().isEmpty()) {
            return;
        }
        messages.send(sender, "list-loops-header",
                Messages.placeholder("count", String.valueOf(store.loops().size())));
        for (Loop loop : store.loops()) {
            messages.sendNoPrefix(sender, "list-loop-entry",
                    Messages.placeholder("id", loop.id()),
                    Messages.placeholder("state", loopState(loop)));
        }
    }

    /** Belonging to a loop wins over having an interval: the loop is what actually sends it. */
    private Component presetState(Announcement a) {
        if (a.inLoop()) {
            return messages.component("list-entry-in-loop",
                    Messages.placeholder("loop", a.loopId().orElse("")));
        }
        if (a.isScheduled()) {
            return messages.component("list-entry-scheduled",
                    Messages.placeholder("interval", a.intervalDisplay()));
        }
        return messages.component("list-entry-manual");
    }

    private Component loopState(Loop loop) {
        String key = loopScheduler.isPaused(loop) ? "list-loop-paused" : "list-loop-running";
        return messages.component(key,
                Messages.placeholder("interval", loop.intervalDisplay()),
                Messages.placeholder("count", String.valueOf(loop.entries().size())),
                Messages.placeholder("next", String.valueOf(loopScheduler.nextIndex(loop) + 1)));
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

        // A manual trigger is independent of any loop: it never moves the loop's
        // position or touches its timer.
        service.fire(announcement, parsed, "trigger");
        messages.send(sender, "trigger-success", Messages.placeholder("id", announcement.id()));
    }

    // ----------------------------------------------------------------- loop

    private void handleLoop(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        String permission = permissionFor(action);

        if (permission == null) {
            // No action, or an unrecognised one. Only hint at the sub-command to
            // someone who may actually use at least one part of it.
            if (!hasAnyLoopPermission(sender)) {
                messages.send(sender, "no-permission");
            } else {
                messages.send(sender, "loop-usage");
            }
            return;
        }
        if (!has(sender, permission)) {
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "loop-usage");
            return;
        }

        String id = args[2];
        Optional<Loop> found = id.length() > MAX_ID_LENGTH ? Optional.empty() : store.loop(id);
        if (found.isEmpty()) {
            messages.send(sender, "loop-unknown-id", Messages.placeholder("id", id));
            return;
        }
        Loop loop = found.get();

        switch (action) {
            case "next" -> handleLoopNext(sender, loop);
            case "pause" -> handleLoopPause(sender, loop);
            case "resume" -> handleLoopResume(sender, loop);
            default -> handleLoopInfo(sender, loop);
        }
    }

    private void handleLoopNext(CommandSender sender, Loop loop) {
        LoopScheduler.NextResult result = loopScheduler.next(loop);
        if (!result.sent()) {
            messages.send(sender, "loop-next-no-players", Messages.placeholder("id", loop.id()));
            return;
        }
        messages.send(sender, "loop-next-success",
                Messages.placeholder("id", loop.id()),
                Messages.placeholder("index", String.valueOf(result.number())),
                Messages.placeholder("announcement", result.announcementId()));
    }

    private void handleLoopPause(CommandSender sender, Loop loop) {
        String key = loopScheduler.pause(loop) ? "loop-pause-success" : "loop-already-paused";
        messages.send(sender, key, Messages.placeholder("id", loop.id()));
    }

    private void handleLoopResume(CommandSender sender, Loop loop) {
        if (!loopScheduler.resume(loop)) {
            messages.send(sender, "loop-not-paused", Messages.placeholder("id", loop.id()));
            return;
        }
        messages.send(sender, "loop-resume-success",
                Messages.placeholder("id", loop.id()),
                Messages.placeholder("interval", loop.intervalDisplay()));
    }

    private void handleLoopInfo(CommandSender sender, Loop loop) {
        boolean paused = loopScheduler.isPaused(loop);

        messages.send(sender, "loop-info-header",
                Messages.placeholder("id", loop.id()),
                Messages.placeholder("status", messages.component(
                        paused ? "loop-info-status-paused" : "loop-info-status-running")));

        if (paused) {
            messages.sendNoPrefix(sender, "loop-info-timing-paused",
                    Messages.placeholder("interval", loop.intervalDisplay()));
        } else {
            long seconds = loopScheduler.secondsUntilNext(loop).orElse(0L);
            messages.sendNoPrefix(sender, "loop-info-timing",
                    Messages.placeholder("interval", loop.intervalDisplay()),
                    Messages.placeholder("countdown", messages.duration(seconds)));
        }

        int next = loopScheduler.nextIndex(loop);
        List<Announcement> entries = loop.entries();
        for (int i = 0; i < entries.size(); i++) {
            messages.sendNoPrefix(sender, i == next ? "loop-info-entry-next" : "loop-info-entry",
                    Messages.placeholder("number", String.valueOf(i + 1)),
                    Messages.placeholder("announcement", entries.get(i).id()));
        }
    }

    // ------------------------------------------------------------- helpers

    private static String permissionFor(String action) {
        return switch (action) {
            case "next" -> PERM_LOOP_NEXT;
            case "pause" -> PERM_LOOP_PAUSE;
            case "resume" -> PERM_LOOP_RESUME;
            case "info" -> PERM_LOOP_INFO;
            default -> null;
        };
    }

    private static boolean hasAnyLoopPermission(CommandSender sender) {
        return sender.hasPermission(PERM_LOOP_NEXT)
                || sender.hasPermission(PERM_LOOP_PAUSE)
                || sender.hasPermission(PERM_LOOP_RESUME)
                || sender.hasPermission(PERM_LOOP_INFO);
    }

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
            List<String> subs = new ArrayList<>(4);
            if (sender.hasPermission(PERM_RELOAD)) {
                subs.add("reload");
            }
            if (sender.hasPermission(PERM_LIST)) {
                subs.add("list");
            }
            if (sender.hasPermission(PERM_TRIGGER)) {
                subs.add("trigger");
            }
            if (hasAnyLoopPermission(sender)) {
                subs.add("loop");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trigger") && sender.hasPermission(PERM_TRIGGER)) {
            return filter(new ArrayList<>(store.ids()), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("loop")) {
            List<String> actions = new ArrayList<>(4);
            for (String action : List.of("next", "pause", "resume", "info")) {
                if (sender.hasPermission(permissionFor(action))) {
                    actions.add(action);
                }
            }
            return filter(actions, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("loop")) {
            return filter(loopSuggestions(sender, args[1].toLowerCase(Locale.ROOT)), args[2]);
        }
        return new ArrayList<>();
    }

    /**
     * Loop ids for the third argument: only loops the action can actually act on,
     * and nothing at all if the sender lacks that action's permission (so tab-
     * completion never leaks which loops exist).
     */
    private List<String> loopSuggestions(CommandSender sender, String action) {
        String permission = permissionFor(action);
        if (permission == null || !sender.hasPermission(permission)) {
            return new ArrayList<>();
        }
        List<String> ids = new ArrayList<>();
        for (Loop loop : store.loops()) {
            boolean paused = loopScheduler.isPaused(loop);
            boolean relevant = switch (action) {
                case "pause" -> !paused;
                case "resume" -> paused;
                default -> true;
            };
            if (relevant) {
                ids.add(loop.id());
            }
        }
        return ids;
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
