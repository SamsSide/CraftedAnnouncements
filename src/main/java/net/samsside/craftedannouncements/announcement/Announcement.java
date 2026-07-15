package net.samsside.craftedannouncements.announcement;

import java.util.List;
import java.util.Optional;

/**
 * An immutable preset announcement, ready to fire. Message lines are already
 * "prepared": each {@code {n}} placeholder has been rewritten to a MiniMessage
 * {@code <argN>} tag so supplied arguments are inserted injection-safely at render
 * time.
 */
public final class Announcement {

    private final String id;
    private final List<String> preparedLines;
    private final int argCount;
    private final Optional<Long> scheduleTicks;
    private final String intervalDisplay;
    private final Optional<SoundSpec> sound;

    public Announcement(String id, List<String> preparedLines, int argCount,
                        Optional<Long> scheduleTicks, String intervalDisplay, Optional<SoundSpec> sound) {
        this.id = id;
        this.preparedLines = List.copyOf(preparedLines);
        this.argCount = argCount;
        this.scheduleTicks = scheduleTicks;
        this.intervalDisplay = intervalDisplay;
        this.sound = sound;
    }

    public String id() {
        return id;
    }

    /** Message lines with {@code {n}} already rewritten to {@code <argN>} tags. */
    public List<String> preparedLines() {
        return preparedLines;
    }

    /** Exact number of arguments this preset requires. */
    public int argCount() {
        return argCount;
    }

    /** The repeating tick interval, present only when this preset is actually scheduled. */
    public Optional<Long> scheduleTicks() {
        return scheduleTicks;
    }

    /** Whether this preset fires automatically on a timer. */
    public boolean isScheduled() {
        return scheduleTicks.isPresent();
    }

    /** The raw interval string for display in /craftedannouncements list (may be null). */
    public String intervalDisplay() {
        return intervalDisplay;
    }

    /** The optional sound played to every recipient when this preset fires. */
    public Optional<SoundSpec> sound() {
        return sound;
    }
}
