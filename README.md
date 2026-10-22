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
- `nav.json` drawer entries: extensions can add a button (e.g. 模型供应商) that
  restores its window and runs a slash command to refresh it.
- Model picker: tap the model name in the top bar to search the available
  models (context size, thinking, image support) and switch with `set_model`.
  The same dialog exposes the thinking-level chips when the model supports
  more than `off`.
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
- `relay_exit` (pig crashed or was killed) also triggers the reconnect loop.
- **Lossless mid-turn reattach**: the relay owns the pig and no longer kills it
  when the app disconnects. A reconnect attaches to the same `pig --mode rpc`
  process, replays the assistant text streamed so far (`relay_resume`), running
  tool progress (`relay_tool`) and unanswered dialogs, then continues live
  deltas. If `get_state` says the pig is streaming on the saved session, the app
  skips `switch_session` and binds the partial assistant bubble instead.

## Keep-alive

- `KeepAliveService` is a `specialUse` foreground service started by
  `MainActivity`. It keeps the process (and the loopback socket) alive while the
  screen is off; Android 14+/15 no longer caches the app aggressively. The
  persistent notification opens the app when tapped.
- Like `termux-wake-lock`, the service holds a `PARTIAL_WAKE_LOCK`
  (`Pig::RelayConnection`) by default so the CPU is not suspended during long
  turns. Toggle it in the drawer (**唤醒锁**); the choice is stored in the `pig`
  SharedPreferences (`wakelock`).
- `MainActivity` is `singleTask`: repeated launches (icon, notification,
  `am start`) reuse the existing instance instead of creating a second
  ViewModel/RelayClient and a duplicate relay connection.
- On the first relay launch each process the app runs `termux-wake-lock` via
  `RUN_COMMAND`, so Termux does not get frozen during long turns.
- The drawer has **后台保活 / 电池白名单**, which requests
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
