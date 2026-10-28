**English** · [简体中文](features.zh-CN.md)

# Features in depth

The tour is in the [README](../README.md); this file is the reference for what
each part of the app does and why it behaves that way.

## Reconnect and session restore

The app is designed to survive screen locks, Doze and process death:

- `MainActivity.onResume` reconnects (and relaunches `pig-relay` through Termux)
  whenever the socket is down. While disconnected, `startAutoReconnect` keeps
  retrying with backoff as long as the app is in the foreground.
- Every 4 s the client sends `relay_ping`; if no `relay_pong` arrives for 12 s
  the socket is considered dead (frozen process, killed relay) and is dropped,
  then the reconnect loop takes over. A short `onPause` does not tear down a
  live connection.
- The active session file is stored in the app's `pig` SharedPreferences
  (`lastSessionFile`). The relay always starts a fresh `pig`, so after the
  handshake the app sends `switch_session` for that file and reloads its
  transcript. If the session can no longer be opened the app falls back to a
  new session.
- **History is requested only after `get_state` settles the session.** A relay
  that has no session attached yet answers `get_messages` with an empty list;
  treating that as "the session is empty" wiped the visible transcript right
  after a relay restart. The app now ignores an empty history for an
  unqualified (or current) session, and if `get_state` reports `messageCount`
  while the transcript is empty it re-requests the history once.
- `relay_exit` (pig crashed or was killed) also triggers the reconnect loop.
- **Lossless mid-turn reattach**: the relay owns the pig and no longer kills it
  when the app disconnects. A reconnect attaches to the same `pig --mode rpc`
  process, replays the assistant text streamed so far (`relay_resume`), running
  tool progress (`relay_tool`) and unanswered dialogs, then continues live
  deltas. If `get_state` says the pig is streaming on the saved session, the app
  skips `switch_session` and binds the partial assistant bubble instead.
- **Cards survive a reconnect**: `setWidget`, `setStatus` and `setTitle` are
  pushed once and have no query RPC, so the relay keeps the last frame per key
  for each agent (including its clear form) and replays them on attach, switch
  and parked-session promotion. The app also stores the cards of a session in
  its own prefs and draws them when the session is opened (`widgetSnapshots`,
  capped at 20 sessions, ignored after 7 days); live frames and the relay's
  replay always win over that snapshot. Together they cover an app restart with
  the relay alive, a relay restart, and both going away.

## Sessions and settings

- The drawer lists recent sessions (`~/.pig/agent/sessions`), switches with
  `switch_session` and reloads the transcript with `get_messages` (text and tool
  cards). Switching to a file modified in the last minute asks for confirmation,
  because another pig process may be writing it. The search box filters by name,
  preview, id or cwd; each row's menu renames (RPC `set_session_name` for the
  live session, a relay-appended `session_info` entry otherwise) or deletes the
  session file. The active session cannot be deleted.
- Tapping 新会话 opens the empty conversation and a 正在新建对话 banner right
  away; pig builds the session in the background and whatever you type meanwhile
  is queued in the app and sent as soon as it reports the new session. No spare
  process is prewarmed (memory first): an idle conversation is reused when you
  create a new one, and a session with no messages yet is parked instead of
  being stopped.
- A session with no messages yet is not on disk, so the relay lists it from the
  live process (badge 未发送) and parks that process when you switch away —
  switching back resumes the same conversation. The composer keeps a draft per
  session (also persisted), so typing, switching away and coming back never
  loses text, and text typed while a new session is still being created moves
  onto its file once pig reports one.
- `nav.json` entries: an extension can add a button (e.g. 模型供应商) that
  restores its window and runs a slash command to refresh it. `placement` is
  `drawer` (default) or `settings`; the latter appears in the settings dialog, so
  moving extension entries is a data-only edit (no app rebuild).
- Messages have a copy button (assistant header / user bubble). The transcript
  stops auto-scrolling when you scroll up and shows a 回到底部 pill; long-press a
  tool card's 收起/摘要/展开 chip to apply that mode to every tool card.
- The model picker's star pins a **default model** for new sessions, and each
  model remembers the thinking level you last picked for it.
- Settings is a resizable dialog whose sizes come from the UI pack
  (`layout/chat.json` → `settings`, presets + `defaultSize`); a pack can also pin
  one fixed size. It also has a custom auto-compaction threshold (presets
  50–80%) and an About section with the app version plus a GitHub release update
  check.

## Backup, restore and uninstall

Settings → **备份与维护** moves the whole Termux-side install around and removes
it again. The app never reads `~/.pig` itself: the relay streams the data over
the same loopback socket (protocol: `docs/protocol.md`).

- **导出备份** writes a gzipped tar to `Download/Pig/pig-backup-<time>.tar.gz`
  on Android 10+ (MediaStore, no permission); older releases use the system save
  dialog. Two switches choose what goes in: **会话记录** (the bulk - tens of MB)
  and **UI pack**; API keys, `models.json`, `settings.json`,
  `custom-endpoints.json`, extensions and `~/.pig/state` are always included.
  The file therefore contains **plaintext secrets** - the dialog says so before
  the first export. Binaries, `~/.pig/cache` (compiled Go extension runners),
  crash output, logs and symlinks are skipped. So are copies the relay itself
  wrote and recreates at start (`.embedded-hash` directories such as
  `agent/pigkit/<pkg>` and `agent/extensions/plan`); user packages without that
  marker stay in the archive.
- **导入备份** picks a `.tar.gz` and asks once. The relay verifies the checksum,
  writes an automatic full backup to `~/.pig/backups/` first, then merges the
  archive over `~/.pig` (restored files overwrite same-named ones; sessions not
  in the archive are kept). Only `.pig/` entries are accepted, and traversal,
  symlinks and devices are ignored. Restored `settings.json` is sanitized:
  package entries that point at a second copy of an extension the relay ships
  (e.g. a development checkout of `plan`) are dropped in favour of the embedded
  copy, so a backup cannot leave pig in an exit/retry loop. Afterwards the relay stops every pig process
  so the restored settings, models and keys are actually loaded, and the app
  reconnects and reloads the pack and session list. The Go build cache is not
  part of a backup, so the first start after a restore recompiles the embedded
  extensions (seconds to about a minute on a phone) - the same work a fresh
  install does.
- **卸载 pig 与 pig-relay** stops both processes and deletes
  `$PREFIX/bin/pig`, `pig-relay` and the older `pig-relay.*` copies, plus the
  install receipt. An optional switch **同时删除 ~/.pig 数据** (off by default)
  also removes config, keys, models, sessions and UI packs. When it is on, the
  app first writes `~/pig-before-uninstall-<time>.tar.gz` (without `cache/` and
  `docs/`) and only deletes after that succeeds; a failed backup aborts the
  uninstall and keeps the data. Without the switch, running the install command
  later brings everything back as it was. Note that the installer itself never
  deletes `~/.pig` - only this switch does.

Both backup and restore need relay **1.1.34+**: the handshake advertises
`features: ["backup","restore"]` and the buttons stay disabled with a hint when
an older relay is connected. Progress (bytes, and percent while exporting) is
shown in the same section; dismissing the dialog does not cancel the transfer.

## Relay version check

`relay_hello` carries the relay's `version` (1.1.34+). Release builds compare it
with their own version name and show the result in **Settings → 连接**:

- relay older (or a pre-1.1.27 relay with no version) → red row plus **更新
  relay**: it reruns the installer (pinned to the app's tag, extensions compiled
  first) and restarts the relay when Termux returns; without `RUN_COMMAND`
  permission it copies the command and opens Termux instead. A one-time toast
  points at the row on connect.
- relay newer → an info line suggesting an app update; the relay is never
  downgraded.
- equal → no warning. Debug/CI builds (`0.1.0`/dev versions) skip the check so
  development is not nagged.

## UI pack

`~/.pig/android-ui/` drives colors, typography, layout switches, component
visibility/sizes, icons, nav entries, windows, assets and **all app-owned
strings** — including setup, dialogs, tool metrics, notification text and
launcher errors. Add `skins.json` for named theme variants and the drawer /
settings skin switcher (`layout/chat.json` → `skinSwitcher`). A **UI pack
manager** in the drawer/settings imports packs from ZIP or a folder into the
app's private storage and switches between them and the relay pack
(`layout/chat.json` → `packManager`). See `ui-pack.md` for the key groups
and placeholders.

## Model picker

The picker shows models with a `思考` badge when they declare reasoning support
(the badge is informational only — some providers omit the flag, so there is no
reasoning filter). Thinking levels are model-specific; a non-reasoning model
only reports `off`. After a switch the picker stays open, shows `切换中…`, then
refreshes the available thinking levels so they can be changed immediately.

`/v1/models` proxies rarely expose capability metadata, so every model of an
auto-discovered provider arrives with `reasoning: false` and only reports
`off`. Add `reasoning: true` plus a `thinkingLevelMap` under
`providers.<id>.modelOverrides.<model>` in `~/.pig/agent/models.json` to opt it
in (`~/bin/pig-eu-reasoning-sync` does this for the `eu` provider). Map only the
levels the upstream accepts — mapping a level to `null` **removes** it, including
`off`. The change is picked up when a pig process starts.

Tap the `ctx` line on any row to pin the context window. Providers often
report a wrong or missing `contextWindow`; the override accepts plain tokens or
`128K` / `1M`, is stored per `provider/model`, and immediately recomputes the
header percentage (`used / override`). `恢复自动` removes it and falls back to
the value reported by the provider.

## Provider manager

Settings → **模型供应商** (the UI pack's `nav.json` entry; `window: native:providers`) opens a
native screen, not a pack-generated window:

- Lists every provider in `~/.pig/agent/custom-endpoints.json` with its base URL,
  API type, model count, whether a key is stored, and whether models are fetched
  automatically. Providers defined in `models.json` are shown read-only.
- **新建 / 编辑** writes the same file the `custom-endpoints` extension reads,
  preserving keys the form does not edit (`headers`, `oauth`, `defaults`, refresh
  options). The API key is write-only: the app only learns `hasKey`, so leaving
  the field empty keeps the stored key; a checkbox clears it.
- **刷新模型列表** asks the relay to fetch `{baseUrl}/models` (or `modelsUrl`) with
  the same auth rules as the session (`Authorization: Bearer`, `x-api-key` for
  Anthropic, `?key=` for Google, custom `headers`/`modelsHeaders`) and then opens
  a **native selection window**: searchable, checkable, 全选/清空, showing id and
  context size. The picked subset is written as an explicit `models` array and
  the full fetched list stays in `custom-endpoints.cache.json`, so reopening the
  picker needs no network call. If a draft refresh fails (timeout, 5xx, or a key
  typed after the click), saving in auto mode fetches once more and caches the
  list, so the models are ready as soon as the session restarts; a failed save
  fetch still saves and warns that pig will retry at startup.
- **自动获取全部模型** keeps `models: "auto"`: every fetched model is registered at
  the next session start, with the cached list used when the endpoint is down.
- Save/delete restart pig (same session file) so the extension re-reads the file;
  the app then refreshes `get_available_models`. `$ENV` and `!command` keys are
  expanded in Termux, and `PIG_OFFLINE=1` disables fetching.

The `provider-ui` extension still exists for CLI users (`/provider-ui`,
`/providerui.save`, `/providerui.refresh`, `/providerui.remove`), but it no
longer writes `windows/providers.json`.

## First-run provider setup

A fresh `~/.pig` has no providers, so `get_available_models` is empty. The app
treats that as setup, not an error: a banner above the composer offers
**添加供应商** (the native provider manager) and **备份 / 设置** (export or import
a backup), the model picker shows the same button when its list is empty, and
sending without a model gives a hint instead of a red pig error. Settings →
连接 also has a permanent **模型供应商** row, independent of the UI pack's
`nav.json`, and the relay answers an empty model list even before it has started
a pig, so the banner appears on a completely fresh backend. Dismissing the
banner hides it for the process; it comes back if models disappear again.
Built-in providers are configured in Termux with `pig login` / `pig auth`;
custom endpoints use the provider manager.

## Images

Images travel inline in pig's message content as
`{"type":"image","data":"<base64>","mimeType":"image/png"}` blocks, and the
relay forwards them untouched. The app renders them in both directions:

- **Agent → app.** Tool output keeps its images: `read` on a picture,
  screenshot tools, MCP servers and image models all return an `image` block
  next to their text. It shows inside the tool card (every mode, including
  summary), and an assistant message can carry image blocks too. Tap any
  picture — transcript block or Markdown `![…](…)` — for a full-screen viewer
  (pinch to zoom, drag to pan, double-tap to reset). The viewer's save button
  writes it to `Pictures/Pig` through MediaStore (no storage permission on
  Android 10+; older releases use the app's external Pictures directory).
- **App → agent.** The composer's image button opens the system photo picker
  (no storage permission). Picked pictures are downscaled to a 1568 px long
  edge and recompressed (JPEG, or PNG when the source has alpha) before they
  become RPC `images`, so prompts and session files stay small. They are sent
  on the next `prompt` / `steer` / `followUp`. Images are allowed without text,
  and the app warns when the current model does not declare image input.
- **Markdown.** `![alt](https://…)`, `data:image/…;base64,…` and app-readable
  `file:` links in assistant Markdown are fetched in the background and drawn
  by the renderer (12-entry memory cache, 8 MiB / 1600 px caps).

History rebuilds decode images off the main thread (1024 px long edge, ~24 MB
pixel budget per session) so a 2000-message transcript with screenshots stays
navigable. Oversized blocks — over ~6 MiB of image bytes, more than eight per
message, or beyond the budget — are skipped and shown as `N 张图片未显示`.
Switching sessions discards decoded bitmaps with the transcript.

## Context and cache

The header shows the current context percentage/tokens and the cumulative
prompt-cache hit rate (`缓存 78%`). **设置 → 数据 → 使用统计** opens the full
session counters from `get_session_stats` — input/output/cache tokens, cache
hit share, cost, message/tool counts — covering the whole session file, not just
the loaded history. Packs can bind the same numbers through `stats.*`
(see [ui-pack.md](ui-pack.md)). After `/compact`, PiG returns a null context
usage until the next model call, so the header shows `已压缩` instead of a
misleading `0%`; the real number reappears after the next turn.

## Steering and follow-up queue

While a turn is running the send button has two actions:

- **tap** — `steer`, injected into the running turn after the current tool
  batch (插队);
- **long press** — `follow_up`, queued until the turn settles (排队).

PiG reports both queues with `queue_update`, so the banner above the composer
names what is actually waiting (`已插队 1 · 已排队 2`) instead of calling every
queued message a steer. Tapping the banner opens 待发送消息:

- tapping a row takes that message out of the queue and puts it back in the
  composer, so a queued (including steered) message can still be edited before
  it runs; a message pig already consumed is part of the session and cannot be
  changed;
- each row shows its kind and text and can be removed with the delete icon;
- **全部清空** drops both queues (`clear_queue`);
- pig has no "drop one" RPC, so removing a row clears both queues and re-queues
  the survivors in order with `steer` / `follow_up`; they are not echoed into
  the transcript;
- after a reconnect `get_state` only carries `pendingMessageCount`, so the
  dialog shows the count (and the clear action) until the next queue change.

设置 → 会话行为 → 打断消息 / 追问消息 still chooses `one-at-a-time` vs `all`
for each queue, exactly as before.

## Thinking

Reasoning blocks (`content: [{"type":"thinking", …}]`) are part of the
transcript, not a notification:

- They stream in live (`thinking_delta` / `thinking_end`) while the model is
  thinking and are replayed from history on reload.
- Each block renders **above** the answer as a collapsed `思考 · N 字` section;
  it auto-expands while streaming and collapses when the answer starts. Tap the
  header to toggle, per message.
- A step that only reasons and then calls tools does not get its own bubble:
  that reasoning is folded into the tool card group (also collapsed), so a long
  run of same-name calls stays one group instead of being split by thinking
  bubbles.
- `hideThinkingBlock` in `settings.json` hides them; **设置 → 会话行为 → 显示思考过程**
  flips it through `relay_settings` and applies immediately.
- The block is pack-configurable (`chat.thinking` in `layout/chat.json`): title,
  font size/italic, colours, radius, padding, max height, default collapse and
  the streaming auto-expand. See [ui-pack.md](ui-pack.md).
- A provider that redacts reasoning sends an empty block with `redacted: true`;
  the app shows `（提供方已隐藏思考内容）` instead of a blank section.
- `thinking_level_changed` updates the header, so a level changed by pig or an
  extension does not leave a stale value.

## Slash commands

The composer recognises `/name args`:

- `/compact` (also `/compress` as an alias) sends the RPC `compact` command.
- `/new`, `/name <name>`, `/thinking <level>`, `/model`, `/stop` map to app/RPC actions.
- Registered extension commands are listed by the RPC `get_commands` call and are
  sent as a `prompt`; PiG runs them without calling the model.
- Typing `/` opens a command palette with the built-in and extension commands;
  tapping a row fills `/name `. Unknown commands are rejected with a toast
  instead of being sent to the model.
- The command list is refreshed on connect, when entering `/` mode, after every
  turn and 1.5 s after running an extension command. Installing an extension in
  Termux still requires the running pig to load it: run `/reload-runtime` (or
  restart the agent), then reopen the palette.

### Plan mode (optional)

`extensions/plan/` in this repository is an optional Go extension: `/plan`
switches to a read-only planning mode, the model answers with a `Plan:` list,
and `/plan go` (or the dialog) starts executing it while `[DONE:n]` markers tick
the steps off. Install it with:

```bash
pig install ./extensions/plan        # after cloning this repo
```

Then restart pig (**设置 → 会话行为 → 重启 pig**). `/reload-runtime` rebuilds
the extensions it already knew about at startup; a package added to
`settings.json` afterwards needs the restart. The app does not depend on this
extension — any extension that emits the [plan widget contract](plan-widget.md)
gets the same card.

`compact` is also available from **设置 → 数据 → 压缩上下文**.

## Session behaviour and tuning

**设置 → 会话行为** drives the runtime switches PiG exposes over RPC:

- **自动压缩上下文** → `set_auto_compaction` (`compaction.enabled`, persisted by
  PiG). Triggered when the context exceeds `contextWindow − reserveTokens`;
  turning it off leaves `/compact` as the only way to compact.
- **自动重试** → `set_auto_retry` (session-scoped, not reported back by
  `get_state`, so the app tracks it locally and resets it on session switch).
- **打断消息 / 追问消息** → `set_steering_mode` / `set_follow_up_mode`
  (`one-at-a-time` or `all`).

The context length picked in the model picker is not display-only any more: the
app sends `relay_model_override`, which writes
`providers.<provider>.modelOverrides.<model>.contextWindow` in
`~/.pig/agent/models.json` (atomic write, `.bak-*-pre-app` backup). Pig reads
that file once, at process start, and does not re-read it on `set_model`, so
the app then sends `relay_restart`: the relay stops the agent and reopens the
same session file with its history, and the pinned window is live within a
second or two (status `恢复中`). If a turn is running the restart waits for
`agent_end`. While a pin is still waiting the header appends `⏳` to the ctx
line (e.g. `ctx 57% / 1M ⏳`); it disappears as soon as pig reports the new
window. Clearing the pin removes the key and restarts pig the same way.

Extension status lines (`ctx.ui.setStatus(key, text)`) keep their key: the app
shows them next to its own activity label (`处理中 · ⚡ 38.2 tok/s`), and an empty
`statusText` clears only that key. Extension lines outlive the turn that wrote
them, so they are shown as a plain label — the spinner belongs to the app's own
activity (thinking/working/compacting) and stops with it. Widgets
(`ctx.ui.setWidget`) render above or below the composer as before, and are
restored on reconnect (see above); extensions should still re-render stateful
cards on `session_start` so they also come back after a relay restart. A widget
whose key is `plan` / `plan-todos` / `todos` and whose lines follow the plan
contract becomes a checklist card (title, progress bar, `☑/☐` steps) — any
extension can opt in, so users can pick our optional plan extension or a
third-party one. The card collapses to a single line (remembered per key) and
can be dismissed until its content changes. See
[plan-widget.md](plan-widget.md).

Failures are part of the transcript instead of a toast: pig's own failed turns
are stored in the session file (`errorMessage`) and are replayed on load, while
relay/transport failures the app sees are kept per session in
`files/notices/<session>.jsonl` and merged into the history. Both render as a
notice row between the messages, so scrolling back or restarting the app still
shows what went wrong.

**设置 → 压缩与重试** also offers **自动压缩阈值** as a share of the window
(60/70/80/88/90 %). PiG only knows the absolute headroom, so the app converts
the picked value with `reserveTokens = window × (1 − threshold)` and writes it
through `relay_settings`. The window used is the one pig reports (it is the one
compaction compares against), so a pin that has not been applied yet cannot
produce a reserve larger than the real window; the hint says so and shows the
resulting numbers. The absolute `压缩预留 tokens` entry below stays available
for exact values.

The section edits values that only live in
`~/.pig/agent/settings.json` (`compaction.reserveTokens`,
`compaction.keepRecentTokens`, `retry.maxRetries`, `cacheWarming`). The app has
no RPC for these, so it asks the relay — `relay_settings` reads the file and
merges an allow-listed patch, keeping a `settings.json.bak-*-pre-app` backup.
Older relays reply `relay_settings_error`; the section then shows a hint instead
of pretending to save. Pig reads these values at process start, so they apply to
newly spawned sessions.

## WebDAV backup

Settings → 备份与维护 → **WebDAV 备份** uploads the same archive as 导出备份
(sessions, settings, keys, extensions and UI pack) to any WebDAV server that
speaks Basic auth — Nextcloud, Alist, Synology, rclone serve.

- **Server / user / password / folder**: the folder is created on first upload
  (`MKCOL`); the URL may already point at a user's home, e.g.
  `https://dav.example.com/remote.php/dav/files/me`.
- **保留 N 份**: after each upload the oldest `pig-backup-*.tar.gz` files beyond
  N are deleted from that folder.
- **定时备份**: off / every 6 h / 12 h / daily / every 3 days / weekly. It is a
  WorkManager job with a network constraint; it survives reboots and app
  restarts. The worker opens its own relay connection (starting the relay if
  Termux is not running one), so while it runs a foreground app briefly
  reconnects.
- **测试连接** does a `PROPFIND` (and `MKCOL` when the folder is missing);
  **立即备份** uploads once; **远端备份** lists the archives, each with 恢复.
- Restore downloads the archive to the app cache and goes through the normal
  restore path: the relay backs up the current `~/.pig` first, then extracts,
  then the app reconnects.

Security: the password sits in the app's private settings (not encrypted at
rest), and the archive itself contains plain-text API keys — use HTTPS and
restrict access to the folder.

## Notifications

While the app is in the background, session state changes raise a notification.
Two channels keep this controllable from system settings:

| Channel | Importance | Used for |
| --- | --- | --- |
| 会话进展 (`pig_updates`) | default | turn finished, extension info messages, compaction (silent) |
| 需要处理 (`pig_alerts`) | high | extension waiting for a confirm/select/input/editor, request or connection errors |

Which states notify:

- **回复完成** — `agent_settled` with `aborted: false` (an aborted turn stays
  quiet), text is the first line of the last answer.
- **需要你的操作** — an extension opened a dialog and is waiting.
- **出错了** — a pig `error` event or a relay error (protocol-compat noise such
  as `unknown relay frame` is excluded).
- **上下文已压缩** — `compaction_end`, silent and off by default.
- Extension `notify` calls map by level: `info` → 会话进展, `warning` →
  需要处理, `error` → 需要处理.

Notification sound follows Android's channel model, so the app offers the
practical choices in **设置 → 会话行为 → 通知提示音**: 系统默认 / 静音 / 闹钟 /
铃声. Picking one creates the channels for that sound and deletes the previous
variants (Android binds the sound to the channel). **打开系统通知设置…** opens the
channel page for a custom sound, vibration and importance — a channel the user
customized in the system stays customized until the in-app choice changes.

All four are toggles in **设置 → 会话行为** (完成 / 需要操作 / 出错 / 压缩).
The 完成 switch also covers plain extension `info` messages, since both land on
the same channel.
Tap opens the app; returning to the foreground cancels the notifications the
app raised. Android 13+ asks for the notification permission on first launch.

## Keep-alive

- `KeepAliveService` is a `specialUse` foreground service started by
  `MainActivity`. It keeps the process (and the loopback socket) alive while the
  screen is off; Android 14+/15 no longer caches the app aggressively. The
  persistent notification opens the app when tapped.
- Like `termux-wake-lock`, the service holds a `PARTIAL_WAKE_LOCK`
  (`Pig::RelayConnection`) by default so the CPU is not suspended during long
  turns. Both this and the foreground service are controlled from
  **设置** (drawer): `keepalive` starts/stops the service, `wakelock` controls
  the CPU lock; both choices are stored in the `pig` SharedPreferences.
- `MainActivity` is `singleTask`: repeated launches (icon, notification,
  `am start`) reuse the existing instance instead of creating a second
  ViewModel/RelayClient and a duplicate relay connection.
- On the first relay launch each process the app runs `termux-wake-lock` via
  `RUN_COMMAND`, so Termux does not get frozen during long turns.
- **设置** also has the battery row, which requests
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the app. If the dialog is blocked,
  allow the app manually under Settings → Battery. Allowing Termux as well is
  recommended on aggressive OEM ROMs (MIUI, etc.).
- `POST_NOTIFICATIONS` is requested on Android 13+; the service still runs when
  it is denied, only the notification is hidden.

