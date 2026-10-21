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
- Streams assistant text, renders tool cards, answers `confirm` / `select` /
  `input` / `editor` extension dialogs, and shows status updates.
- Relay authenticates with a per-install token, forwards pig's RPC frames
  unchanged, lists projects, and pushes a hot-reloadable UI pack.
- GitHub Actions builds a debug APK on every push.

M1 adds the declarative UI pack schema (theme/layout/panels/renderers) and
applies it at runtime. M2 adds `app-ui/1` actions and `ext:` routing.

## Prerequisites

- Termux with `pig` working (`pig --mode rpc` must start).
- `allow-external-apps = true` in `~/.termux/termux.properties`
  (already set on this device; run `termux-reload-settings` after changing it).
- `pig-relay` installed at
  `/data/data/com.termux/files/usr/bin/pig-relay`.
- The app must be granted `com.termux.permission.RUN_COMMAND`.

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

The workflow is `.github/workflows/android.yml`: JDK 17, Gradle wrapper 8.9,
`assembleDebug`, artifact upload. Debug builds use the committed
`keystore/ci-debug.p12` (alias `androiddebugkey`, password `android`) so
successive CI builds can be installed over each other. It is a debug key,
not a release key.

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
examples/ui-pack/        sample declarative UI pack
```

## Security notes

- The relay binds to `127.0.0.1` only and requires the token in the first frame.
- `allow-external-apps=true` lets any app holding `RUN_COMMAND` execute commands
  in Termux. That is the trade-off for the pairing-free launch path. If you do
  not want it, start the relay from a Termux service instead.
- API keys stay in Termux (`~/.pig/agent/auth.json`); the app never sees them.
