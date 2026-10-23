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
| `relay_session_rename` | `id`, `path`, `name` | `relay_session_renamed` (`path`, `name`) |
| `relay_session_delete` | `id`, `path` | `relay_session_deleted` (`path`) |
| `relay_ui` | | `relay_ui_pack` snapshot |
| `relay_settings` | `id`, optional `set` object | `relay_settings` (`settings`, `editable`) |
| `relay_settings` | `set` only merges allow-listed keys | `relay_settings_error` (`message`) |
| `relay_model_override` | `id`, `provider`, `model`, `contextWindow` | `relay_model_override` (`provider`, `model`, `contextWindow`) |
| `relay_model_override` | `contextWindow` outside 0…10 000 000 or unknown provider | `relay_model_override_error` (`message`) |
| `relay_restart` | | kills and respawns pig (`relay_restarted`) |
| `relay_stop` | | closes the connection |

## Relay → client

| Frame | Meaning |
| --- | --- |
| `relay_hello` | auth accepted; `resumed=true` when an existing pig was reused |
| `relay_log` | a line from pig's stderr |
| `relay_exit` | pig exited; `code` is the exit status |
| `relay_error` | relay-level error (not an agent error) |
| `relay_projects` | project list |
| `relay_sessions` | session list (`path`, `id`, `cwd`, `name`, `preview`, `updated`, `size`) |
| `relay_session_renamed` | rename succeeded |
| `relay_session_deleted` | delete succeeded |
| `relay_session_error` | rename/delete failed (`message`) |
| `relay_ui_pack` | UI pack snapshot |
| `relay_settings` | parsed `~/.pig/agent/settings.json` plus the writable key list |
| `relay_settings_error` | settings read/write failed (`message`) |
| `relay_model_override` | context window written to `models.json` (echo) |
| `relay_model_override_error` | override rejected (`message`) |
| `relay_resume` | in-flight assistant text replayed after a reconnect |
| `relay_tool` | running tool progress replayed after a reconnect |
| `relay_restarted` | pig was respawned |

Everything else is a pig RPC frame (`response`, `message_update`,
`extension_ui_request`, ...).

## Settings

`relay_settings` without `set` returns the raw `~/.pig/agent/settings.json`
object (empty object when the file is missing) together with `editable`, the
sorted list of keys the app may write. With `set`, only allow-listed dotted
paths are merged into the file; unknown keys and invalid types/enums are
silently dropped so a stale app can never corrupt unrelated settings. Writes
are atomic (`tmp` + rename) and keep a
`settings.json.bak-<timestamp>-pre-app` backup.

Writable keys: `compaction.enabled`, `compaction.reserveTokens`,
`compaction.keepRecentTokens`, `cacheWarming`, `hideThinkingBlock`,
`retry.enabled`, `retry.maxRetries`, `retry.baseDelayMs`,
`retry.maxAgentDelayMs`, `defaultThinkingLevel`, `thinkingBudgets.*`.

## Model overrides

`relay_model_override` pins a per-model context window in
`~/.pig/agent/models.json`:

```json
{"type": "relay_model_override", "id": 7, "provider": "eu",
 "model": "wb2api/cn:deepseek-v4.1-flash", "contextWindow": 1000000}
```

It writes `providers.<provider>.modelOverrides.<model>.contextWindow`, keeping
the other override keys (`reasoning`, `thinkingLevelMap`, ...). A
`contextWindow` of `0` removes just that key (and the empty override object).
The provider must already exist in `models.json`; unknown providers and windows
outside `0…10 000 000` are rejected. Same write discipline as the settings
frame: atomic rename plus `models.json.bak-<timestamp>-pre-app`.

PiG reads `models.json` when it starts, so the new window applies to agents
spawned afterwards — the running agent keeps its old value until it is
restarted. `get_session_stats` reports `contextUsage.contextWindow`, which is
what the app compares a pinned window against.

These values are read by pig when a process starts, so a change applies to
newly spawned sessions; the running pig keeps its loaded values.

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

## Lifecycle and reconnects

The relay owns the `pig --mode rpc` process and serves one client at a time.
When a client disconnects the pig **keeps running**, so a turn that is still
streaming is not aborted. A new client attaches to the same pig:

- the relay sends `relay_hello` with `resumed: true` and the same `pigPid`;
- after the handshake it replays `relay_resume` with the assistant text
  accumulated so far, `relay_tool` for running tools, and any unanswered
  `extension_ui_request`;
- the client resyncs its transcript with `get_state`/`get_messages` and must not
  call `switch_session` while `isStreaming` is true.
- a relay that has no session attached yet answers `get_messages` with
  `{"messages": []}` and `sessionPath: ""`. Clients must not clear the
  transcript on that answer: wait for `get_state` (and restore the remembered
  session with `switch_session`) before rendering history. `get_state` carries
  `messageCount`, which lets a client notice that it is missing messages.

If the pig exits, the relay sends `relay_exit`; the next attach starts a fresh
pig. `relay_restart` explicitly stops and replaces it. `--no-session` is not
passed, so sessions persist under `~/.pig/agent/sessions`.
