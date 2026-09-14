# Wire protocol (plugin ↔ engine)

Transport: WebSocket, binary frames, one protobuf message per frame. The gateway
proxies frames unchanged in both directions.

Upstream (`UpStream`): `Hello` (first frame, within the Hello window —
`ENGINE_HELLO_TIMEOUT`, default 5 s), then any of `Batch`, `PlayerEvent`,
`Heartbeat`, `WorldChunk`, `WorldDelta`. Downstream (`DownStream`): `HelloAck`,
`Verdict`, `Control`.

## Discriminating gateway control frames

The gateway injects its own binary frames on the same socket: `0x01` heartbeat,
`0x02` mod update, `0x03` reconnect, `0x04` message (opcode byte, optional payload).
A protobuf message never begins with a byte below `0x08` (that would be field 0), so:

```
first byte < 0x08  →  gateway control frame
otherwise          →  DownStream protobuf
```

Text frames (e.g. `{"type":"resync"}`) come from the gateway; `resync` means send a
fresh `Hello` with the current roster.

## Handshake

1. `GET /api/session/auth` with `X-SERVER-TOKEN` (gateway) → `{ "sessionToken": "..." }` (valid 60 s).
2. WebSocket upgrade `GET /api/anticheat/ws` with headers `X-SESSION-TOKEN`,
   `X-THORIUM-VERSION` (plugin version), `X-THORIUM-GAME: minecraft`.
3. Send `Hello{protocol: 1, plugin_version, mc_version, software, roster}`.
4. Receive `HelloAck{accepted, server_id, organization_id, policy}`. Honour
   `policy.flush_interval_ms`.

Gateway close code `4422` = plugin too old; the plugin holds reconnects for 1 h.
With a dev session token the plugin skips step 1 and sends the token as `X-SESSION-TOKEN` directly.

## Samples

See `proto/thorium/mc/v1/packets.proto`. Every `Sample` carries `ServerMeta`;
checks lose their false-positive suppression without `gamemode`, `ping_ms`,
`tps`, `server_moved` and the block-context flags.

## World streaming

Off unless `HelloAck.policy.world.enabled` (engine side: `WORLD_STREAM_ENABLED`).
When on, the plugin mirrors the sections around every player so the engine can run
real collision instead of inferring geometry from `ServerMeta`'s block flags.

- `WorldChunk{sections, full_sync_start, full_sync_done}` — whole 16×16×16 sections.
  `full_sync_start` tells the engine to drop its model first; `full_sync_done` marks
  the mirror complete. The initial sync is spread over many frames against a per-tick
  snapshot budget (`columns_per_tick`) so it never stalls the server's main thread.
- `WorldDelta{sections, dropped}` — individual block changes, and sections the plugin
  stopped tracking. A change to a section the engine does not hold is ignored.

`Section` is palette-encoded: entries in Minecraft's YZX order
(`offset = (y << 8) | (z << 4) | x`), one byte per index up to a 256-entry palette
and two little-endian bytes past it, and no index array at all for a uniform
section. Palette entries are opaque state strings — `minecraft:oak_stairs[facing=north,...]`
on 1.13+, legacy `MATERIAL:data` below that.

The plugin does not listen to `BlockPhysicsEvent` (too hot to be worth it), so
sections are re-read on a slow rotation to correct drift. An unchanged re-read is
dropped by content hash and costs nothing on the wire.

## Verdicts

`Verdict{player, action, alert_type, reason, confidence, shadow, vl, alert_id, issued_at_ms}`.
`shadow = true` means log only (organization is in Experimental mode). Actions:
`FLAG` (staff notify only), `WARN`, `KICK`, `BAN`.
