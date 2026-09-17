# Thorium Minecraft plugin

Single jar for Bukkit/Spigot/Paper/Folia, Minecraft 1.8 → latest, Java 8+.
No detection runs on the server: the plugin streams telemetry to the Thorium engine
and applies the verdicts it gets back.

## Install

1. Drop `thorium-minecraft-<version>.jar` into `plugins/`, start once, stop.
2. Edit `plugins/Thorium/config.yml`: set `server-token` (dashboard → Servers → your server → Token).
3. Start the server. Console shows `Thorium: connected to engine (server …)`.

## Config

| Key | Default | Meaning |
|---|---|---|
| `gateway-url` | `https://gateway.thorium.ac` | Thorium gateway base URL. Must be `https://`: the plugin refuses to start on any other scheme, because `server-token` would cross the network in cleartext. The one exception is a local engine reached with `dev.session-token`, which never sends the server token. |
| `server-token` | — | server token from the dashboard |
| `flush-interval-ms` | `75` | telemetry batch interval (engine may override; clamped 25–1000) |
| `enforce` | `true` | `false` → verdicts are logged and shown to staff, never applied |
| `ban-command` | `""` | console command for BAN verdicts (`%player%`, `%reason%`, `%check%`); empty = Bukkit ban list |
| `send-ip` | `false` | include player IP in join events |
| `alert-format`, `warn-format`, `kick-format` | see file | `&` colours; `%player% %check% %vl% %confidence% %reason% %action%` |
| `dev.session-token` | `""` | **dev only**: skip gateway auth and connect straight to an engine (`STATIC_SESSIONS`) |

## Commands and permissions

| Command | Permission | Effect |
|---|---|---|
| `/thorium status` | `thorium.admin` | version, server, connection state, telemetry queue, enforce flag |
| `/thorium alerts` | `thorium.alerts` | toggle in-game alerts for yourself |
| `/thorium reconnect` | `thorium.admin` | reload `config.yml` and rebuild the connection |

Staff with `thorium.alerts` see every non-shadow verdict as an in-game line.

## Verdict handling

| Engine action | `enforce: true` | `enforce: false` | Experimental (shadow) |
|---|---|---|---|
| FLAG | staff alert | staff alert | console log |
| WARN | staff alert + message to player | staff alert | console log |
| KICK | staff alert + kick | staff alert | console log |
| BAN | staff alert + Bukkit ban (or `ban-command`) + kick | staff alert | console log |

## Connection lifecycle

auth (`GET /api/session/auth`) → WebSocket (`/api/anticheat/ws`) → `Hello` → `HelloAck` → streaming.
Any drop reconnects with 2 s → 60 s exponential backoff and a fresh session token.
Gateway close code `4422` (plugin too old) holds reconnects for one hour and logs the download URL;
`/thorium reconnect` breaks the hold.

## Building

```bash
make plugin        # JDK 17+ runs Gradle (21 locally); emits plugin/build/libs/thorium-minecraft-<version>.jar (Java 8 bytecode)
make plugin-test
```

Protobuf classes are generated from `../proto` at build time. packetevents, Java-WebSocket,
protobuf-java, slf4j and adventure are shaded under `ac.thorium.mc.libs`.

## Local end-to-end

```bash
make run-engine                      # terminal 1: engine with STATIC_SESSIONS token "dev"
# plugins/Thorium/config.yml: gateway-url: "http://localhost:3100", dev: { session-token: "dev" }
#   ^ development only. Plain http is accepted here *only* because dev.session-token is set,
#     which skips gateway auth entirely and so never puts server-token on the wire. Without
#     that key the plugin logs SEVERE and stays idle rather than connecting over cleartext.
java -jar paper.jar --nogui          # terminal 2
```

Verified 2026-09-11 on Paper 1.21.11 (Java 21): loads, connects, HelloAck, no plugin exceptions.

## Threading

packetevents listeners run on netty threads and only read an immutable per-player
snapshot refreshed each tick on the player's thread (Folia region / main thread).
Bukkit reads for combat targets and blocks hop to the player's thread first, keeping
capture-time stamps. Verdicts are applied on the player's thread. All plugin code is
wrapped so an exception is logged (rate-limited) and never reaches the server.

## Manual test matrix before enabling kicks in Live mode

- Paper 1.21.x: legit play (sprint-jump, ladders, water, elytra, boats), then a known cheat client → alerts.
- Spigot/Paper 1.8.8 (Java 8): same. Not run yet (no Java 8 runtime on the dev machine).
- Folia: join/quit, alerts, kick.
