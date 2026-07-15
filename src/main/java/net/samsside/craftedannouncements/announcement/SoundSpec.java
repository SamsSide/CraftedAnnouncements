package net.samsside.craftedannouncements.announcement;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * A parsed, ready-to-play sound for a preset announcement. Accepts either a
 * vanilla sound key ({@code entity.villager.no}) or the Bukkit enum form
 * ({@code ENTITY_VILLAGER_NO}); custom (resource-pack) keys work too. An invalid
 * name yields {@link Optional#empty()} plus a warning, and the announcement then
 * simply fires without a sound.
 */
public final class SoundSpec {

    private static final float DEFAULT_VOLUME = 1.0f;
    private static final float DEFAULT_PITCH = 1.0f;

    private final Sound sound;

    private SoundSpec(Sound sound) {
        this.sound = sound;
    }

    /** The Adventure sound to play to each recipient. */
    public Sound sound() {
        return sound;
    }

    /**
     * Build a SoundSpec from a {@code sound:} section. Returns empty if the section
     * is null, has no {@code name}, or the name cannot be turned into a valid key.
     */
    public static Optional<SoundSpec> fromSection(ConfigurationSection section, String announcementId, Logger logger) {
        if (section == null) {
            return Optional.empty();
        }
        String name = section.getString("name");
        if (name == null || name.isBlank()) {
            logger.warning("Announcement '" + announcementId + "' has a sound block with no 'name'; ignoring it.");
            return Optional.empty();
        }
        Optional<Key> key = toKey(name);
        if (key.isEmpty()) {
            logger.warning("Announcement '" + announcementId + "' has an invalid sound name '" + name
                    + "'; the announcement will fire without a sound.");
            return Optional.empty();
        }
        float volume = (float) section.getDouble("volume", DEFAULT_VOLUME);
        float pitch = (float) section.getDouble("pitch", DEFAULT_PITCH);
        Sound sound = Sound.sound(key.get(), Sound.Source.MASTER, volume, pitch);
        return Optional.of(new SoundSpec(sound));
    }

    /**
     * Turn a configured sound name into an Adventure Key. Enum-style names
     * ({@code ENTITY_VILLAGER_NO}) map to their vanilla key ({@code entity.villager.no}).
     */
    private static Optional<Key> toKey(String name) {
        String raw = name.trim();
        String candidate = raw.matches("[A-Z0-9_]+")
                ? raw.toLowerCase(Locale.ROOT).replace('_', '.')
                : raw.toLowerCase(Locale.ROOT);
        try {
            return Optional.of(Key.key(candidate));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }
}
