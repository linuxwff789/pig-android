**English** · [简体中文](protocol.zh-CN.md)

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
{"type":"relay_hello","protocol":"relay/1","version":"1.1.34","features":["backup","restore","providers"],"cwd":"/data/data/com.termux/files/home","uiDir":"/data/data/com.termux/files/home/.pig/android-ui","pigPid":12345}
```

`features` lists optional control frames the relay implements. Clients must gate
on it instead of discovering a missing frame through `unknown relay frame`;
relays before 1.1.34 omit both fields.

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
| `relay_restart` | | kills and respawns pig from the same session file (`relay_restarted`); used after a context-window pin |
| `relay_backup` | `id`, optional `sessions`/`ui` booleans (default true) | `relay_backup_start`, then `relay_backup_chunk`…, then `relay_backup_done` or `relay_backup_error` |
| `relay_backup_cancel` | `id` | the running export stops with `relay_backup_error` (`cancelled: true`) |
| `relay_restore_begin` | `id`, `name`, `size` | `relay_restore_ready` (`max` archive size) or `relay_restore_error` |
| `relay_restore_chunk` | `id`, `data` (base64) | none; an error aborts with `relay_restore_error` |
| `relay_restore_end` | `id`, `sha256` of the uploaded bytes | `relay_restore_done` (`entries`, `bytes`, `backup`) or `relay_restore_error` |
| `relay_stop` | | closes the connection |

## History paging

`get_messages` may carry `limit: N`. pig has no paging of its own, so the relay
returns the newest N messages itself — from the disk cache when no pig is
attached for that session, or by trimming pig's reply — and adds two fields to
`data`:

| Field | Meaning |
| --- | --- |
| `historyStart` | index of the first returned message in the whole transcript; `0` means nothing older is left |
| `historyTotal` | how many messages the relay had for that session |

The window is moved back to the `user` message that opened its first turn (at
most 24 messages) so a page never begins with a `toolResult` whose `toolCall`
lives on the previous page. A `get_messages` without `limit` is answered whole,
so older clients are unaffected; the disk fallback still caps at 2000 messages.

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

The app keeps relay-level failures as notices in the transcript: they are
appended to `files/notices/<session>.jsonl` and replayed with the history, so
they survive a reconnect. Failures pig itself records (an assistant message with
`errorMessage`) are replayed from pig's history instead.

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

PiG reads `models.json` when it starts and does not re-read it on `set_model`,
so the new window applies only to agents spawned afterwards — the running agent
keeps its old value. This is why the app follows a successful override with
`relay_restart` (see above): the relay stops the agent and reopens the same
session file, so the transcript survives and the pin becomes live.
`get_session_stats` reports `contextUsage.contextWindow`, which is what the app
compares a pinned window against (and what drives the `⏳` marker on the
`context` binding).

These values are read by pig when a process starts, so a change applies to
newly spawned sessions; the running pig keeps its loaded values.

## Provider management

The app's native 模型供应商 screen reads and writes
`~/.pig/agent/custom-endpoints.json` through these frames. The API key is
write-only: `relay_providers` reports `hasKey`, never the key itself, and a save
that omits `apiKey` keeps the stored one.

| Frame | Fields | Response |
| --- | --- | --- |
| `relay_providers` | `id` | `relay_providers` (`providers`, `modelsProviders`, `path`, `offline`) |
| `relay_provider_save` | `id`, `entry` | `relay_provider_saved` (`provider`, `path`, optional `warning`) |
| `relay_provider_delete` | `id`, `entry.id` | `relay_provider_deleted` (`provider` = the id) |
| `relay_provider_models` | `id`, `entry` (draft: `id`, `baseUrl`, `api`, optional `apiKey`/`modelsUrl`) | `relay_provider_models` (`provider`, `models`, `fetchedAt`) |

Any failure answers `relay_provider_error` with `op` (`list` / `save` /
`delete` / `refresh`) and a `message`.

`entry` for a save:

```json
{"id": "gproxy", "name": "g", "baseUrl": "https://…/v1",
 "api": "openai-completions", "apiKey": "sk-…", "clearKey": false,
 "auto": true, "modelsUrl": "",
 "models": [{"id": "m1", "name": "m1", "contextWindow": 128000, "maxTokens": 8192}],
 "cacheModels": [ … ]}
```

- `auto: true` writes `models: "auto"`; `cacheModels` becomes the provider's entry
  in `custom-endpoints.cache.json` so the session can register models without a
  fetch. `auto: false` writes the explicit `models` array (an empty one is
  rejected) and keeps `cacheModels` in the cache for the next picker.
- Unknown provider keys (`headers`, `oauth`, `defaults`, refresh options) are
  preserved. Writes are atomic and keep
  `custom-endpoints.json.bak-<timestamp>-pre-app`.
- `relay_provider_models` only fetches: it does not touch the file. The relay
  applies the same header rules as the session (`Authorization: Bearer`,
  `x-api-key` + `anthropic-version` for Anthropic, `?key=` for Google, explicit
  `headers`/`modelsHeaders`), expands `$ENV`/`${ENV}`/`!command` in Termux, and
  refuses when `PIG_OFFLINE`/`PI_OFFLINE` is set.
- The provider is not registered into the running session by the relay: the app
  follows a successful save/delete with `relay_restart`, and the
  `custom-endpoints` extension reads the file when the new pig starts.

## Backup and restore

`~/.pig` lives in Termux's private data directory, so the app cannot read or
write it directly; the relay is the file server. A backup is a **gzipped tar**
whose entry names are relative to the Termux home (`$HOME/.pig/agent/...`,
`.pig/state/...`, `.pig/android-ui/...`), streamed as base64 chunks over the
same connection:

```
→ {"type":"relay_backup","id":"b1","sessions":true,"ui":true}
← {"type":"relay_backup_start","id":"b1","name":"pig-backup-20261027-120000.tar.gz","entries":312,"bytes":68123456}
← {"type":"relay_backup_chunk","id":"b1","seq":0,"data":"H4sI…"}
← {"type":"relay_backup_chunk","id":"b1","seq":1,"data":"…"}
← {"type":"relay_backup_done","id":"b1","name":"…","entries":312,"bytes":9123456,"sha256":"…"}
```

- `bytes` in `start` is the uncompressed size (for a progress bar); `bytes` in
  `done` and the `sha256` cover the compressed stream that was sent.
- `sessions: false` leaves out `agent/sessions` (the bulk of the archive);
  `ui: false` leaves out `android-ui`. Keys (`agent/auth.json`,
  `agent/models.json`, `agent/custom-endpoints.json`) are always included, so a
  backup is sensitive.
- Caches and noise are not archived: `crash-output/`, `backups/`, `.log`
  files, `node_modules/`, `__pycache__/`, `.git/` and symlinks.
- Chunks are at most 48 KiB of raw bytes; the archive is capped at 1 GiB and
  only one transfer runs at a time.

Restore is the same bytes in reverse. The relay writes them to a staging file,
enforces the announced `size` and verifies the SHA-256 before touching anything:

```
→ {"type":"relay_restore_begin","id":"r1","name":"pig-backup.tar.gz","size":9123456}
← {"type":"relay_restore_ready","id":"r1","max":1073741824}
→ {"type":"relay_restore_chunk","id":"r1","data":"H4sI…"}        (repeated)
→ {"type":"relay_restore_end","id":"r1","sha256":"…"}
← {"type":"relay_restore_done","id":"r1","entries":312,"bytes":9123456,"backup":"/…/.pig/backups/pig-auto-before-restore-20261027-120500.tar.gz"}
```

Before extracting, the relay writes an automatic full backup to
`~/.pig/backups/`. On success it also sanitizes `settings.json`: package entries
that point at another copy of an extension the relay installs itself (today
`plan`) are removed, and `extensions/plan` is kept. Without that, a backup from a
development checkout makes pig exit with `Flag "--plan" conflicts`, which the app
only sees as a reconnect/error loop. `relay_restore_done` reports the removed
entries as `settingsFixed` and any non-fatal cleanup problem as `warning`. Extraction only accepts entries under `.pig/`, refuses
absolute paths and `..`, and never creates symlinks, hardlinks or devices;
files already in `~/.pig` but missing from the archive are left alone (merge,
not wipe). After a successful restore every pig process is stopped — they cache
settings, models and auth at startup — so the next RPC starts a fresh one with
the restored files. Sessions that are still running are reaped, not written.

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
  `extension_ui_request`. The replay carries text only, so image blocks
  (`{"type":"image",…}`) arrive with `get_messages`/`message_end` instead;
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
