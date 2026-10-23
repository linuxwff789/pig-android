# Pig Android

A native Android client for PiG that keeps the agent running in Termux.
The app is a thin front-end: it never talks to a model provider and never holds
an API key. All agent work happens in the original `pig` binary.

```
┌──────────────┐   TCP 127.0.0.1:8791   ┌────────────┐   stdio JSONL   ┌──────────────┐
│  Pig app     │ ─────────────────────► │ pig-relay  │ ──────────────► │ pig --mode rpc│
│  (Compose)   │ ◄───────────────────── │   (Go)     │ ◄────────────── │   (Termux)    │
└──────────────┘   one JSON per line    └────────────┘                 └──────────────┘
                                          │
                                          └── watches ~/.pig/android-ui and pushes UI packs
```

## Status (M0)

- App launches `pig-relay` through Termux `RUN_COMMAND` (no pairing screen).
- Streams assistant text, renders Markdown (GFM headings, lists, code fences,
  tables with alignment and inline formatting, links), renders tool cards with
  collapse/summary/expand modes (`write` shows lines/bytes, `edit` shows
  added/removed counts and a colored diff), answers `confirm` / `select` / `input` /
  `editor` extension dialogs, and shows status updates.
- Relay authenticates with a per-install token, forwards pig's RPC frames
  unchanged, lists projects/sessions, and pushes a hot-reloadable UI pack.
- Session drawer lists recent sessions (`~/.pig/agent/sessions`), switches via
  `switch_session` and reloads the transcript with `get_messages` (text + tool
  cards). Switching to a file modified in the last minute asks for confirmation
  because another pig process may be writing it. A search box filters by name,
  preview, id or cwd; each row has a menu to rename (RPC `set_session_name` for
  the live session, a relay-appended `session_info` entry otherwise) and delete
  the session file. The active session cannot be deleted.
- `nav.json` entries: extensions can add a button (e.g. 模型供应商) that restores
  its window and runs a slash command to refresh it. `placement` is `drawer`
  (default) or `settings`; the latter appears in the settings dialog, so moving
  extension entries is a data-only edit (no app rebuild).
- Messages have a copy button (assistant header / user bubble). The transcript
  stops auto-scrolling when you scroll up and shows a 回到底部 pill; long-press a
  tool card's 收起/摘要/展开 chip to apply that mode to every tool card.
- Model picker: tap the model name in the top bar to search the available
  models (context size, thinking, image support) and switch with `set_model`.
  The same dialog exposes the thinking-level chips when the model supports
  more than `off`, and every row's `ctx` value is editable so a wrong provider
  `contextWindow` can be pinned by hand.
- GitHub Actions builds a debug APK on every push.

M1 is in: the app parses the pushed pack and applies `theme/colors.json` and
`layout/chat.json` live (see [docs/ui-pack.md](docs/ui-pack.md)). M2 adds
`app-ui/1` actions, `ext:` routing, and the release pipeline. M3 turns the pack
into arbitrary windows; the overlay/sheet/inline placements, the `windows/` file channel
(hot reload, add/update/remove, dismissal), forms, `ext:` actions and
`setWidget` panels are in. Contract and plan:
[docs/app-ui.md](docs/app-ui.md), [docs/app-ui-plan.md](docs/app-ui-plan.md).

## Fresh Termux install

The APK is only the front-end. Install Termux from F-Droid (the Play version is
no longer maintained), open it once, then run:

```bash
pkg install -y curl
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
```

The script installs `pig` with the official installer, downloads
`pig-relay-android-arm64` from this repo's latest release, and creates
`~/.pig/android-ui`.

Then:

The script enables `allow-external-apps` (with a backup), installs `pig` via the
official installer, downloads `pig-relay`, and creates `~/.pig/android-ui`.
Open the Termux app once so the property reloads, then install/open the Pig app
and grant `com.termux.permission.RUN_COMMAND`.

If the app shows the setup dialog, the same two lines are displayed with a copy
button. 一键安装 only works when `allow-external-apps` is already enabled; if it
does nothing, paste the two lines into Termux manually.

See [docs/install.md](docs/install.md) for the manual route and troubleshooting.

## Build the relay

```bash
cd relay
go build -trimpath -ldflags="-s -w" -o "$PREFIX/bin/pig-relay" .
```

## Build the app

Local builds need a JDK and the Android SDK, which Termux does not have here.
CI builds the APK:

1. Push to `main` (or open a PR / run the workflow manually).
2. Download the `pig-debug-apk` artifact from the Actions run.
3. `adb install -r app-debug.apk` (or open the file on the device).

The debug workflow is `.github/workflows/android.yml`: JDK 17, Gradle wrapper
8.9, `assembleDebug`, artifact upload. Debug builds use the committed
`keystore/ci-debug.p12` (alias `androiddebugkey`, password `android`) so
successive CI builds can be installed over each other.

Release builds use `.github/workflows/release.yml`, triggered by a `v*` tag or
manually. The keystore comes from the `RELEASE_KEYSTORE_BASE64`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`
Actions secrets; the version name is the tag without `v` and the version code
is the commit count. The local backup lives at
`~/.pig-android-release/pig-release.p12` (not in the repo). A tag also creates a
GitHub Release with the APK attached. The release key differs from the debug
key, so installing a release APK over an installed debug build requires
uninstalling the debug app once.

## Run

Normally you just open the app: it starts the relay with its stored token and
connects. For debugging, start the relay yourself with the same token the app
uses (stored in the app's `pig` SharedPreferences):

```bash
pig-relay --port 8791 --token <token> --cwd ~ --ui-dir ~/.pig/android-ui
```

Relay flags:

| Flag | Default | Meaning |
| --- | --- | --- |
| `--listen` | `127.0.0.1` | bind address (keep it on loopback) |
| `--port` | `8791` | TCP port |
| `--token` | generated | shared token |
| `--token-file` | | read the token from a file |
| `--cwd` | `$HOME` | pig working directory |
| `--ui-dir` | `~/.pig/android-ui` | UI pack directory |
| `--pig` | `pig` | pig binary |

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

## UI pack

`~/.pig/android-ui/` drives colors, typography, layout switches, component
visibility/sizes, icons, nav entries, windows, assets and **all app-owned
strings** — including setup, dialogs, tool metrics, notification text and
launcher errors. Add `skins.json` for named theme variants and the drawer /
settings skin switcher (`layout/chat.json` → `skinSwitcher`). A **UI pack
manager** in the drawer/settings imports packs from ZIP or a folder into the
app's private storage and switches between them and the relay pack
(`layout/chat.json` → `packManager`). See `docs/ui-pack.md` for the key groups
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

## Context and cache

The header shows the current context percentage/tokens and the cumulative
prompt-cache hit rate (`缓存 78%`). After `/compact`, PiG returns a null context
usage until the next model call, so the header shows `已压缩` instead of a
misleading `0%`; the real number reappears after the next turn.

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
extension — any extension that emits the [plan widget contract](docs/plan-widget.md)
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
(`ctx.ui.setWidget`) render above or below the composer as before. A widget
whose key is `plan` / `plan-todos` / `todos` and whose lines follow the plan
contract becomes a checklist card (title, progress bar, `☑/☐` steps) — any
extension can opt in, so users can pick our optional plan extension or a
third-party one. The card collapses to a single line (remembered per key) and
can be dismissed until its content changes. See
[docs/plan-widget.md](docs/plan-widget.md).

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

## Layout

```
app/                     Compose client
  src/main/java/com/linuxwff/pigapp/
    MainActivity.kt      permission request + relay launch
    RelayClient.kt       TCP JSONL client with retry
    RelayLauncher.kt     Termux RUN_COMMAND intent
    AppViewModel.kt      RPC event → UI state
    ui/ChatScreen.kt     sidebar, transcript, tool cards, dialogs
relay/main.go            loopback bridge, auth, UI pack watcher
docs/protocol.md         wire protocol
docs/ui-pack.md          declarative theme/layout schema
docs/app-ui.md           app-ui/1 window schema (M3 contract)
docs/app-ui-plan.md      M3 milestone order, acceptance, pitfalls
examples/ui-pack/        sample declarative UI pack
```

## Security notes

- The relay binds to `127.0.0.1` only and requires the token in the first frame.
- `allow-external-apps=true` lets any app holding `RUN_COMMAND` execute commands
  in Termux. That is the trade-off for the pairing-free launch path. If you do
  not want it, start the relay from a Termux service instead.
- API keys stay in Termux (`~/.pig/agent/auth.json`); the app never sees them.

## Drive the app from the shell (debug builds)

Debug builds register `com.linuxwff.pigapp/.DebugReceiver`, so you can exercise
the client without touching the screen. Always pass `-n`; the app may be in the
background, and implicit broadcasts are not delivered to cached apps.

```bash
# current connection / model / messages (logcat tag PigDebug)
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_STATE
logcat -d -s PigDebug

# send a prompt
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_PROMPT --es text "Reply with exactly OK"

# switch to a session file
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_SWITCH --es path /path/to/session.jsonl

# model / thinking
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_MODELS
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_SET_MODEL --es provider deepseek --es model deepseek-flash
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_THINKING --es level high

# reconnect (relaunches the relay if needed)
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_CONNECT

# abort the active turn
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ABORT

# answer a pending confirm/select/input dialog
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ANSWER --es value "Allow"
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ANSWER --ez confirmed true
```

`DEBUG_STATE` also writes
`/storage/emulated/0/Android/data/com.linuxwff.pigapp/files/state.txt` (root can
read it), which avoids relying on logcat.

`DEBUG_PROMPT` / `DEBUG_FORM` act on the **active session**. Switch the app to a
scratch session before sending test prompts; otherwise they land in whatever
conversation is open.

The receiver lives in `app/src/debug/` and is not part of release builds.
