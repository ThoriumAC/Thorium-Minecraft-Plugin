# Thorium Minecraft plugin

The client half of [Thorium](https://thorium.ac), a server-side anti-cheat for
Minecraft. This plugin runs on your server, watches the packets the server
already receives, and streams that telemetry to the Thorium engine. The engine
decides; the plugin reports and, if you configure it to, enforces.

This repository is public so you can read exactly what runs on your server
before you install it.

## Where the source lives

This repository is the plugin. It used to be a copy of a directory inside the
private engine repository, kept in step by hand, and that lasted exactly as long
as anyone remembered: the two drifted, both called themselves 0.2.0, and the
published jar was missing fixes the engine had already been told to expect.

There is now one copy, here. The engine repository keeps the wire protocol in
`proto/` and checks on every build that what is here still matches, because the
two halves have to agree about the wire or nothing works.

## What it does, and what it does not

It **does** capture, from packets the server has already accepted:

- movement samples: position, rotation, on-ground and collision flags, sprint
  and sneak state
- combat: attacks, swings, the entity hit and the angle it was hit from
- world interaction: block breaks and places, inventory actions
- transaction markers: ping/pong pairs used to order client acknowledgements
  against server events, which is what makes knockback checks trustworthy
- the blocks immediately around each player, so movement can be judged against
  real geometry rather than guessed

It **does not** touch the player's machine. There is no client-side component,
no file access, no memory inspection, no screenshots, no process enumeration.
Every byte it sends is derived from what the server already knows.

The detection logic is not here. Checks run in the Thorium engine, which is
closed-source. What this repository lets you verify is the boundary: precisely
what leaves your server, and what the plugin does with the verdicts that come
back.

## Install

Drop the jar in `plugins/`, start the server once to generate
`plugins/Thorium/config.yml`, set `server-token` from your dashboard, restart.
Full configuration reference in [docs/plugin.md](docs/plugin.md).

Requires Java 17+ at runtime on modern servers (the jar itself is Java 8
bytecode) and a Paper, Spigot or Folia server. packetevents, Java-WebSocket,
protobuf and slf4j are shaded into the jar under `ac.thorium.mc.libs`, so
nothing conflicts with other plugins.

## Build

```sh
./gradlew shadowJar     # build/libs/thorium-minecraft-<version>.jar
./gradlew test
```

## Wire protocol

`proto/` holds the protobuf schema the plugin and engine share, and
[docs/wire-protocol.md](docs/wire-protocol.md) describes the framing, the
handshake and how verdicts come back. It is a mirror of the schema in the
engine repository; the engine is the source of truth, and a mismatch shows up
as a `4422` close from the gateway telling you to update.

## Contributing

Issues and pull requests are welcome, particularly compatibility reports from
server software and Minecraft versions we do not test against. Changes to
`proto/` have to land in the engine first — a PR that edits the schema alone
cannot be merged.

Security issues go to security@thorium.ac, not to the issue tracker.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
