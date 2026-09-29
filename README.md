# Thorium Minecraft plugin

The server-side plugin for [Thorium](https://thorium.ac), a cloud anti-cheat for
Minecraft. It streams the packets your server already handles to the Thorium
engine and applies the verdicts that come back.

This repository is public so you can see exactly what runs on your server and
what leaves it.

## What it sends

- Serverbound packets the server has already received: movement, input,
  interactions, inventory, block actions and similar, with text reduced to its
  length (chat, commands and book contents are never sent).
- Clientbound state that affects the player: entity spawns and moves near them,
  velocity, effects, abilities, inventory contents, block changes, world border.
- Ping/pong transactions used to order the two streams.
- Optionally, when the engine enables it, the block sections around each player.
- Join, quit, teleport, damage and similar server events. Player IPs only when
  the network allows it on the dashboard (or `send-ip: true` before the first
  connection).
- Player reports made with `/report`, when the network has reports enabled.
- With Advanced Analytics on: blocks broken and placed, kills and deaths,
  projectiles, items used, consumed, dropped, picked up, crafted and enchanted.

It has no client-side component and does not touch players' machines.

The engine that evaluates this data is closed source. This repository covers
the boundary: what leaves your server and what the plugin does with the
responses.

## Install

1. Drop `thorium-minecraft-<version>.jar` into `plugins/` and start the server
   once to generate `plugins/Thorium/config.yml`.
2. Set `server-token` from your dashboard (and `server-name` for a network
   token), then restart.

Runs on Spigot, Paper and Folia, Minecraft 1.8 through current, Java 8 or newer.
Dependencies are shaded under `ac.thorium.mc.libs`. See
[docs/plugin.md](docs/plugin.md) for configuration, commands and permissions.

## Build

```sh
./gradlew build    # tests + build/libs/thorium-minecraft-<version>.jar
```

Use the jar without the `-plain` suffix; the plain jar is unshaded.

## Wire protocol

`proto/` holds the protobuf schema shared with the engine, and
[docs/wire-protocol.md](docs/wire-protocol.md) describes the transport. The
engine repository is the source of truth for the schema.

## Contributing

Issues and pull requests are welcome, especially compatibility reports for
server software and versions we do not test. Schema changes in `proto/` have to
land in the engine first, so a PR that only edits the schema cannot be merged.

Report security issues privately; see [SECURITY.md](SECURITY.md).

## License

The source is licensed under the Apache License 2.0; see [LICENSE](LICENSE).

The release jar bundles [packetevents](https://github.com/retrooper/packetevents),
which is GPL-3.0, so the jar as a whole is distributed under the GPL-3.0. Other
bundled libraries are MIT or BSD licensed. See [NOTICE](NOTICE) and
[licenses/](licenses).
