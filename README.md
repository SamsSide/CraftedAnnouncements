# CraftedAnnouncements

A broadcast and announcement plugin for Paper Minecraft servers (MC 1.21.11, Java 21).

- **`/broadcast`** — send an ad-hoc message to everyone, right now.
- **Preset announcements** (`announcements.yml`) — reusable messages that can fire
  on their own schedule, be triggered by command (including from console, e.g. a
  store webhook), carry `{0}/{1}` arguments, and play a sound.

Every message supports **legacy colour codes** (`&a`, `&#RRGGBB`, `§a`) **and
MiniMessage** (`<green>`, `<gradient:...>`, `<bold>`) — mix them freely. When
[PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) is
installed, `%..%` placeholders inside announcements are resolved per recipient.

---

## Commands

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/broadcast <message>` | `/announce`, `/bc`, `/ac` | Send `<message>` to every online player, with the broadcast prefix. | `craftedannouncements.broadcast` |
| `/craftedannouncements reload` | `/ca reload` | Reload `config.yml`, `messages.yml`, `announcements.yml`, and rebuild all schedules. | `craftedannouncements.reload` |
| `/craftedannouncements list` | `/ca list` | List every preset: its id and whether it is scheduled or manual. | `craftedannouncements.list` |
| `/craftedannouncements trigger <id> [args...]` | `/ca trigger ...` | Fire preset `<id>` (message + sound) to everyone, filling any `{n}` arguments. Works from console. | `craftedannouncements.trigger` |

All permissions default to **OP**. Tab-completion only suggests sub-commands and
preset ids the sender is permitted to use.

### Permission list

- `craftedannouncements.broadcast`
- `craftedannouncements.reload`
- `craftedannouncements.list`
- `craftedannouncements.trigger`

---

## `config.yml`

```yaml
# Schema version - drives auto-merge on update; do not edit by hand.
config-version: 1

# When true, every announcement (ad-hoc broadcast, manual trigger, or scheduled
# preset) is also echoed to the server console as plain text.
log-announcements-to-console: false
```

On startup and reload the bundled defaults are **auto-merged** into your file: new
keys are added, and your existing values and comments are preserved.

---

## `announcements.yml`

Each entry under `announcements:` is keyed by a unique id.

```yaml
config-version: 1

announcements:

  # Scheduled, multi-line, no arguments - fires every 2 hours 30 minutes.
  discord:
    message:
      - "<gold><bold>=== Community ===</bold>"
      - "<aqua>Join our Discord: <underlined>discord.gg/yourserver"
      - "<gray>See you there!"
    interval: 2h30m

  # Scheduled, single line.
  vote:
    message: "<green>Remember to vote at <yellow>/vote<green>!"
    interval: 45m

  # Manual, two arguments, with a sound.
  thanks:
    message: "<green>Thank you <yellow>{0}</yellow> for purchasing <gold>{1}</gold>!"
    sound:
      name: entity.villager.no
      volume: 1.0
      pitch: 1.0
```

### Fields

- **`message`** *(required)* — a single line, or a YAML list of lines (each entry
  is one broadcast line).
- **`interval`** *(optional)* — how often the preset fires automatically, using the
  units `d`, `h`, `m`, `s` in any combination: `2h30m`, `45m`, `1d`, `90s`,
  `1d2h30m15s`. The first fire happens one interval after (re)load.
  - A preset that uses `{0}/{1}` arguments **cannot be scheduled** (there is nothing
    to fill the arguments with). If you give one an `interval` it is ignored, with a
    console warning; you can still trigger it manually.
  - An invalid interval is ignored with a console warning; the preset stays
    manually triggerable.
- **`sound`** *(optional)* — played to every recipient whenever the preset fires
  (manual **and** scheduled). Remove the block for silence.
  - `name` — a vanilla sound key (`entity.villager.no`) or the enum form
    (`ENTITY_VILLAGER_NO`); custom resource-pack keys work too.
  - `volume` (default `1.0`), `pitch` (default `1.0`).
  - An invalid sound name is ignored with a console warning; the message still
    sends.

### Arguments

Put `{0}`, `{1}`, `{2}`… anywhere in a preset's message. When you trigger it you
supply exactly that many arguments. Wrap any argument that contains spaces in
`"double quotes"`:

```
/craftedannouncements trigger thanks Steve "VIP Rank"
```

→ `{0}` = `Steve`, `{1}` = `VIP Rank`. Supplying too few or too many arguments is
rejected with a clear message. Argument values are inserted as literal text, so a
player name or package can never inject formatting.

**Store (Tebex) example** — configure your store command to run:

```
craftedannouncements trigger thanks %player% "%package%"
```

The user this file is fired by is the console, which always has permission.

---

## `messages.yml`

Every player-facing string lives here — nothing is hard-coded. Values may be a
single string or a list of lines. Same auto-merge and format support as above (both
legacy codes and MiniMessage). Reloaded by `/craftedannouncements reload`.

Key strings include the two prefixes (`prefix` for command feedback, and
`broadcast-prefix` prepended to ad-hoc broadcasts only — preset bodies are sent
exactly as written), `no-permission`, the usage/reload/list/trigger messages, and
the argument-count error.

---

## Building

```
mvn clean package
```

The jar is written to `target/CraftedAnnouncements-1.0.0.jar`. Requires Java 21.
