package net.samsside.craftedannouncements;

import net.samsside.craftedannouncements.announcement.AnnouncementScheduler;
import net.samsside.craftedannouncements.announcement.AnnouncementService;
import net.samsside.craftedannouncements.announcement.AnnouncementStore;
import net.samsside.craftedannouncements.announcement.LoopScheduler;
import net.samsside.craftedannouncements.announcement.LoopStateStore;
import net.samsside.craftedannouncements.command.BroadcastCommand;
import net.samsside.craftedannouncements.command.CraftedAnnouncementsCommand;
import net.samsside.craftedannouncements.config.PluginConfig;
import net.samsside.craftedannouncements.message.Messages;
import net.samsside.craftedannouncements.placeholder.PapiHook;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * CraftedAnnouncements - broadcasts and scheduled preset announcements.
 *
 * <p>{@code /broadcast} (aka {@code /announce}) sends ad-hoc text to everyone.
 * Preset announcements are defined in announcements.yml with optional schedules,
 * sounds and {@code {n}} arguments, and are fired by
 * {@code /craftedannouncements trigger <id> [args...]} (usable from console, e.g.
 * by a store webhook) or automatically on their own timers.
 *
 * <p>Loops cycle a list of presets on one shared interval, sending a single message
 * at a time so scheduled announcements never overlap in chat.
 */
public final class CraftedAnnouncements extends JavaPlugin {

    private PluginConfig pluginConfig;
    private Messages messages;
    private AnnouncementStore store;
    private AnnouncementScheduler scheduler;
    private LoopStateStore loopStateStore;
    private LoopScheduler loopScheduler;

    @Override
    public void onEnable() {
        this.pluginConfig = new PluginConfig(this);
        this.messages = new Messages(this);
        this.store = new AnnouncementStore(this);

        loadConfiguration();

        // Read once, on enable only: from here on the in-memory state is the authority
        // and the file just trails it, so a reload can never rewind a loop.
        this.loopStateStore = new LoopStateStore(this);
        this.loopStateStore.load();

        PapiHook papi = new PapiHook(this);
        AnnouncementService service = new AnnouncementService(this, pluginConfig, messages, papi);
        this.scheduler = new AnnouncementScheduler(this, store, service);
        this.scheduler.rebuild();
        this.loopScheduler = new LoopScheduler(this, store, service, loopStateStore);
        this.loopScheduler.rebuild();

        registerCommand(CraftedAnnouncementsCommand.COMMAND_NAME,
                new CraftedAnnouncementsCommand(this, messages, store, service, loopScheduler));
        registerCommand(BroadcastCommand.COMMAND_NAME,
                new BroadcastCommand(messages, service));

        if (papi.available()) {
            getLogger().info("PlaceholderAPI found; %..% placeholders in announcements will be resolved.");
        } else {
            getLogger().info("PlaceholderAPI not found; announcements are sent without placeholder resolution.");
        }

        getLogger().info("CraftedAnnouncements " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (loopScheduler != null) {
            loopScheduler.cancelAll();
        }
        if (loopStateStore != null) {
            // Synchronous: the scheduler is gone by now, so an async write would never run.
            loopStateStore.saveNow();
        }
        getLogger().info("CraftedAnnouncements disabled.");
    }

    /**
     * Reload config.yml, messages.yml and announcements.yml (re-running auto-merge),
     * then rebuild all scheduled and loop timers. loop-state.yml is deliberately not
     * re-read: each loop keeps the position and paused state it already had.
     * Propagates on failure so the command can report it.
     */
    public void reloadAll() {
        loadConfiguration();
        scheduler.rebuild();
        loopScheduler.rebuild();
        getLogger().info("CraftedAnnouncements configuration reloaded.");
    }

    private void loadConfiguration() {
        pluginConfig.load();
        messages.load();
        store.load();
    }

    private <T extends CommandExecutor & TabCompleter> void registerCommand(String name, T handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is not defined in plugin.yml; it will not work.");
            return;
        }
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }
}
