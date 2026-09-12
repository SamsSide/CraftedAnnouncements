package net.samsside.craftedannouncements.announcement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An immutable, validated loop: an ordered list of presets sent one at a time,
 * one per interval, wrapping back to the start. A loop can never overlap itself -
 * exactly one of its messages goes out per interval - which is the whole reason
 * loops exist instead of giving every preset its own timer.
 *
 * <p>Duplicates are allowed and kept: listing the same preset twice simply sends
 * it twice per cycle.
 */
public final class Loop {

    private final String id;
    private final String key;
    private final long intervalTicks;
    private final String intervalDisplay;
    private final List<Announcement> entries;
    private final List<String> entryKeys;

    public Loop(String id, long intervalTicks, String intervalDisplay, List<Announcement> entries) {
        this.id = id;
        this.key = id.toLowerCase(Locale.ROOT);
        this.intervalTicks = intervalTicks;
        this.intervalDisplay = intervalDisplay;
        this.entries = List.copyOf(entries);

        List<String> keys = new ArrayList<>(this.entries.size());
        for (Announcement entry : this.entries) {
            keys.add(entry.id().toLowerCase(Locale.ROOT));
        }
        this.entryKeys = List.copyOf(keys);
    }

    /** The loop id exactly as written in announcements.yml (used for display). */
    public String id() {
        return id;
    }

    /** The lowercase id, used for lookups and as the loop-state.yml key. */
    public String key() {
        return key;
    }

    /** How long between messages, in server ticks. */
    public long intervalTicks() {
        return intervalTicks;
    }

    /** The interval exactly as written ("30m"), for display. */
    public String intervalDisplay() {
        return intervalDisplay;
    }

    /** The resolved presets, in order, duplicates kept. */
    public List<Announcement> entries() {
        return entries;
    }

    /** The lowercase preset ids of {@link #entries()}, used to detect list changes. */
    public List<String> entryKeys() {
        return entryKeys;
    }
}
