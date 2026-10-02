# CraftedAnnouncements

A broadcast and announcement plugin for Paper Minecraft servers (MC 1.21.11, Java 21).

- **`/broadcast`** — send an ad-hoc message to everyone, right now.
- **Preset announcements** (`announcements.yml`) — reusable messages that can fire
  on their own schedule, be triggered by command (including from console, e.g. a
  store webhook), carry `{0}/{1}` arguments, and play a sound.
- **Loops** — cycle through presets one at a time on a single interval, so
  announcements never overlap in chat.

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
| `/craftedannouncements list` | `/ca list` | List every preset — its id and whether it is scheduled, manual, or sent by a loop — then every loop with its interval, message count and next position. | `craftedannouncements.list` |
| `/craftedannouncements trigger <id> [args...]` | `/ca trigger ...` | Fire preset `<id>` (message + sound) to everyone, filling any `{n}` arguments. Works from console. Never affects a loop's position or timer. | `craftedannouncements.trigger` |
| `/craftedannouncements loop next <loop>` | `/ca loop next ...` | Send the loop's next message immediately and move it on by one. On a running loop the timer restarts, so the next automatic send is a full interval away. Works on a paused loop without resuming it. With nobody online nothing is sent and the loop does not move on. Works from console. | `craftedannouncements.loop.next` |
| `/craftedannouncements loop pause <loop>` | `/ca loop pause ...` | Stop the loop's timer, keeping its position. | `craftedannouncements.loop.pause` |
| `/craftedannouncements loop resume <loop>` | `/ca loop resume ...` | Restart a paused loop from where it left off; its next message is one interval from now. | `craftedannouncements.loop.resume` |
| `/craftedannouncements loop info <loop>` | `/ca loop info ...` | Show the loop's status, its interval, a countdown to the next send, and its message order with the next one marked. | `craftedannouncements.loop.info` |

All permissions default to **OP**. Tab-completion only suggests sub-commands,
preset ids and loop ids the sender is permitted to use — `loop pause` only offers
running loops, `loop resume` only paused ones, and a sender without a given loop
permission is shown no loop ids at all.

```
[Announcements] Loop 'main' (running)
  Interval: 30m   Next send in: 12m 30s
  1. discord
  2. vote  « next
  3. store
```

### Permission list

- `craftedannouncements.broadcast`
- `craftedannouncements.reload`
- `craftedannouncements.list`
- `craftedannouncements.trigger`
- `craftedannouncements.loop.next`
- `craftedannouncements.loop.pause`
- `craftedannouncements.loop.resume`
- `craftedannouncements.loop.info`

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

Each entry under `announcements:` is keyed by a unique id. Loops go under a
separate `loops:` root.

```yaml
config-version: 1

announcements:

  # Multi-line, no arguments - sent by the "tips" loop below.
  discord:
    message:
      - "<gold><bold>=== Community ===</bold>"
      - "<aqua>Join our Discord: <underlined>discord.gg/yourserver"
      - "<gray>See you there!"

  # Single line, no arguments - sent by the "tips" loop below.
  vote:
    message: "<green>Remember to vote at <yellow>/vote<green>!"

  # Manual, two arguments, with a sound.
  thanks:
    message: "<green>Thank you <yellow>{0}</yellow> for purchasing <gold>{1}</gold>!"
    sound:
      name: entity.villager.no
      volume: 1.0
      pitch: 1.0

loops:

  # One message every 30 minutes, alternating - never two at once.
  tips:
    interval: 30m
    announcements:
      - discord
      - vote
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
  - A preset that is **part of a loop** has its own `interval` ignored, with a
    console warning — the loop decides when it goes out. It can still be triggered
    manually.
  - An invalid interval is ignored with a console warning; the preset stays
    manually triggerable. An absurdly large one is treated as invalid rather than
    silently wrapping round.
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

### Loops

Give every preset its own `interval` and sooner or later two of them come due in
the same tick and stack up in chat. A **loop** fixes that: it cycles through a list
of presets, sending **one** of them per interval, in order, then starting again
from the top. One timer, one message — a loop can never overlap itself.

With three presets and `interval: 30m`:

```yaml
loops:
  main:
    interval: 30m
    announcements:
      - discord
      - vote
      - store
```

…`discord` is sent 30 minutes after load, `vote` 30 minutes after that, `store` 30
minutes after that, then `discord` again, forever.

**Fields**

- **`interval`** *(required)* — how long between each message, same format as a
  preset interval (`30m`, `1h`, `1h30m`). The first message is sent one interval
  after the plugin loads or reloads.
- **`announcements`** *(required)* — an ordered list of preset ids from above. The
  same id may appear more than once. Each send plays that preset's message and
  sound, exactly like a manual trigger.

**Notes**

- A preset in a loop has its own `interval` ignored (with a console warning) — the
  loop decides when it is sent. It can still be triggered manually, and doing so
  does not move the loop along.
- Presets that use `{0}/{1}` arguments cannot be in a loop (nothing would fill the
  arguments); they are skipped with a console warning.
- Unknown preset ids are skipped with a console warning. A loop with no usable
  presets, or a missing/invalid interval, is not loaded — the rest of the file
  still loads and the server still starts.
- If nobody is online when a loop is due, nothing is sent and the loop does **not**
  move on; it tries again one interval later, so no message is wasted on an empty
  server.
- Each loop remembers its position and whether it is paused across reloads and
  restarts, in `loop-state.yml`. Changing a loop's list of presets makes it start
  again from its first message (a pause is kept).
- Loop ids are case-insensitive and must be unique.
- Control loops in-game with
  `/craftedannouncements loop <next|pause|resume|info> <loop>`.

---

## `loop-state.yml`

Written by the plugin in the plugin folder; you never need to touch it. It holds,
per loop, the position it is up to (`next-index`, where `0` is the first message),
whether it is `paused`, and the list of presets it had when the position was saved.

```yaml
loops:
  tips:
    next-index: 1
    paused: false
    entries:
      - discord
      - vote
```

- **Safe to delete** — every loop then simply starts from its first message,
  unpaused.
- **Do not edit it while the server is running**; the plugin holds the state in
  memory and overwrites the file.
- The saved `entries` list is how a loop notices you changed it: if the effective
  list differs from the one on record, the position resets to message 1 (the paused
  flag is kept).
- State for a loop you have removed or commented out is left in the file, so the
  loop picks up where it was if you put it back unchanged.
- It is read once at startup. `/craftedannouncements reload` deliberately does not
  re-read it, so a reload can never rewind a loop. If the file is unreadable it is
  renamed to `loop-state.yml.broken-<timestamp>`, a SEVERE line explains why, and
  every loop starts fresh — the plugin still enables.

---

## `messages.yml`

Every player-facing string lives here — nothing is hard-coded. Values may be a
single string or a list of lines. Same auto-merge and format support as above (both
legacy codes and MiniMessage). Reloaded by `/craftedannouncements reload`.

Key strings include the two prefixes (`prefix` for command feedback, and
`broadcast-prefix` prepended to ad-hoc broadcasts only — preset bodies are sent
exactly as written), `no-permission`, the usage/reload/list/trigger messages, and
the argument-count error.

Loops add the `loop-*` keys (usage, unknown id, next/pause/resume feedback and the
whole `loop info` display), `list-entry-in-loop` and the `list-loop-*` keys, plus
`duration-days` / `duration-hours` / `duration-minutes` / `duration-seconds`, which
build the `loop info` countdown — each unit is shown only when non-zero, largest
first, space-separated, e.g. `1h 5m 30s`.

**config-version 2.** Loops bump `messages.yml` to `config-version: 2`. The new keys
arrive through the normal auto-merge. The one existing value that changes is the
root usage block, which gains a `loop` line — and only if you never edited it. If
your usage text differs from the old default in any way it is left exactly as you
wrote it, and the console says which happened:

```
Migrated messages.yml to config-version 2 (usage text updated).
Migrated messages.yml to config-version 2 (usage text left as customised).
```

---

## Upgrading an existing server to loops

Your `announcements.yml` is **not changed** by this update: the `announcements:`
and `loops:` roots are both excluded from the auto-merge, so no example loop is
injected and nothing you wrote is touched. To start using loops, add a `loops:`
section by hand at the bottom of the file:

```yaml
loops:
  main:
    interval: 30m
    announcements:
      - discord
      - vote
```

Then remove the `interval` from each preset you moved into the loop — or leave it,
since a looped preset's own interval is ignored anyway (with a console warning).
Run `/craftedannouncements reload` and the loop starts; its first message is one
interval later.

`messages.yml` picks up the new keys automatically and moves to `config-version: 2`
(see above). `loop-state.yml` is created on the first save.

---

## Building

```
mvn clean package
```

The jar is written to `target/CraftedAnnouncements-1.0.0.jar`. Requires Java 21.
