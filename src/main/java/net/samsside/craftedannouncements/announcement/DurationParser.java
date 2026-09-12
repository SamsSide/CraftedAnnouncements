package net.samsside.craftedannouncements.announcement;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses interval strings such as {@code 2h30m}, {@code 45m}, {@code 1d},
 * {@code 90s} or {@code 1d2h30m15s} into a positive number of server ticks
 * (20 ticks = 1 second). Units may appear in any order and repeat; anything the
 * pattern cannot fully consume, or a total of zero, is rejected.
 */
public final class DurationParser {

    private static final long TICKS_PER_SECOND = 20L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;
    private static final long SECONDS_PER_DAY = 86_400L;

    private static final Pattern UNIT = Pattern.compile("(\\d+)([dhms])");

    private DurationParser() {
    }

    /**
     * Parse an interval into repeating ticks.
     *
     * @return the tick count, or empty if the string is null, blank, malformed,
     * contains stray characters, or totals zero ticks.
     */
    public static Optional<Long> parseTicks(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String s = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }

        Matcher m = UNIT.matcher(s);
        long totalSeconds = 0L;
        int matchedChars = 0;
        while (m.find()) {
            if (m.start() != matchedChars) {
                // A gap means an unparseable character sits between units.
                return Optional.empty();
            }
            long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (NumberFormatException overflow) {
                return Optional.empty();
            }
            // An absurd interval must be reported as invalid, never silently wrapped
            // round into a small (or negative) one.
            try {
                totalSeconds = Math.addExact(totalSeconds, switch (m.group(2)) {
                    case "d" -> Math.multiplyExact(value, SECONDS_PER_DAY);
                    case "h" -> Math.multiplyExact(value, SECONDS_PER_HOUR);
                    case "m" -> Math.multiplyExact(value, SECONDS_PER_MINUTE);
                    default -> value; // "s"
                });
            } catch (ArithmeticException tooLarge) {
                return Optional.empty();
            }
            matchedChars = m.end();
        }

        if (matchedChars != s.length() || totalSeconds <= 0L) {
            return Optional.empty();
        }
        try {
            return Optional.of(Math.multiplyExact(totalSeconds, TICKS_PER_SECOND));
        } catch (ArithmeticException tooLarge) {
            return Optional.empty();
        }
    }
}
