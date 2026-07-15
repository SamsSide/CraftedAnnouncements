package net.samsside.craftedannouncements.announcement;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a raw argument string into positional arguments on spaces, treating any
 * {@code "double-quoted"} span as a single argument so values with spaces (e.g. a
 * store package name like {@code "VIP Rank"}) arrive intact.
 *
 * <p>An unterminated quote is handled gracefully: the remaining text becomes one
 * final argument rather than throwing.
 */
public final class ArgumentParser {

    /** Hard cap on the number of arguments, to reject absurd input cheaply. */
    public static final int MAX_ARGS = 64;
    /** Hard cap on the length of a single argument. */
    public static final int MAX_ARG_LENGTH = 256;

    private ArgumentParser() {
    }

    /** Parse the joined argument string; returns an immutable-enough list of tokens. */
    public static List<String> parse(String raw) {
        List<String> args = new ArrayList<>();
        if (raw == null) {
            return args;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean building = false; // whether current holds a (possibly empty) started token

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                building = true; // a lone "" is still an (empty) argument
            } else if (c == ' ' && !inQuotes) {
                if (building) {
                    args.add(cap(current.toString()));
                    current.setLength(0);
                    building = false;
                    if (args.size() >= MAX_ARGS) {
                        break;
                    }
                }
            } else {
                current.append(c);
                building = true;
            }
        }
        if (building && args.size() < MAX_ARGS) {
            args.add(cap(current.toString()));
        }
        return args;
    }

    private static String cap(String value) {
        return value.length() > MAX_ARG_LENGTH ? value.substring(0, MAX_ARG_LENGTH) : value;
    }
}
