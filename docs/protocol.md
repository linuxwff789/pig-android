# Relay protocol (`relay/1`)

Loopback TCP, one JSON object per line (LF). The framing is the same as
`pig --mode rpc`. Pig's own frames are forwarded byte-for-byte; relay control
frames are the ones whose `type` starts with `relay_`.

## Handshake

The client must send this within 5 seconds:

```json
{"type":"relay_auth","token":"<shared token>"}
```

On success the relay spawns `pig --mode rpc` and sends:

```json
{"type":"relay_hello","protocol":"relay/1","cwd":"/data/data/com.termux/files/home","uiDir":"/data/data/com.termux/files/home/.pig/android-ui","pigPid":12345}
```

On failure it sends `relay_error` and closes the connection. Tokens are compared
in constant time.

## Client → relay

Any frame whose `type` does **not** start with `relay_` is written to pig's
stdin unchanged. Examples: `prompt`, `abort`, `get_state`, `new_session`,
`extension_ui_response`.

Control frames:

| Frame | Fields | Response |
| --- | --- | --- |
| `relay_ping` | `id` | `relay_pong` |
| `relay_projects` | `id`, optional `root` | `relay_projects` with `{name, path}` list |
| `relay_ui` | | `relay_ui_pack` snapshot |
| `relay_restart` | | kills and respawns pig (`relay_restarted`) |
| `relay_stop` | | closes the connection |

## Relay → client

| Frame | Meaning |
| --- | --- |
| `relay_hello` | auth accepted, pig is running |
| `relay_log` | a line from pig's stderr |
| `relay_exit` | pig exited; `code` is the exit status |
| `relay_error` | relay-level error (not an agent error) |
| `relay_projects` | project list |
| `relay_ui_pack` | UI pack snapshot |
| `relay_restarted` | pig was respawned |

Everything else is a pig RPC frame (`response`, `message_update`,
`extension_ui_request`, ...).

## UI packs

The relay scans `--ui-dir` every 800 ms. Hidden files, symlinks, binary files and
files larger than 512 KiB are skipped. When the content hash changes it pushes:

```json
{"type":"relay_ui_pack","hash":"<sha256>","files":{"manifest.json":"…","theme/colors.json":"…"},"assets":{"assets/logo.png":"<base64>"},"assetHashes":{"assets/logo.png":"<sha256>"}}
```

`files` maps slash-separated relative paths to text. The hash covers sorted
`path\0content\0` pairs, so adding, removing or editing any file triggers a
push. A missing directory is treated as an empty pack.

M1 defines the pack schema (`manifest.json`, `theme/`, `layout/`, `panels/`,
`renderers/`); see `examples/ui-pack/`.

## Lifecycle

One relay process serves one client at a time. The relay spawns a fresh
`pig --mode rpc` per connection and terminates it when the client disconnects.
`--no-session` is not passed, so sessions persist under `~/.pig/agent/sessions`.
