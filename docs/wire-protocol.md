# Wire protocol (plugin ↔ engine)

Transport: WebSocket, binary frames, one protobuf message per frame. The gateway
proxies frames unchanged in both directions.

Upstream (`UpStream`): `Hello` (first frame, within the Hello window —
`ENGINE_HELLO_TIMEOUT`, default 5 s), then any of `Batch`, `PlayerEvent`,
`Heartbeat`. Downstream (`DownStream`): `HelloAck`, `Verdict`, `Control`.

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

## Verdicts

`Verdict{player, action, alert_type, reason, confidence, shadow, vl, alert_id, issued_at_ms}`.
`shadow = true` means log only (organization is in Experimental mode). Actions:
`FLAG` (staff notify only), `WARN`, `KICK`, `BAN`.
