**English** · [简体中文](development.zh-CN.md)

# Development

Build the relay and the app, run the client by hand and drive it from a shell.
None of this is needed to *use* the app — see [install.md](install.md) for that.

## Build the relay

```bash
cd relay
go build -trimpath -ldflags="-s -w" -o "$PREFIX/bin/pig-relay" .
```

## Release build

```bash
./gradlew :app:assembleRelease -PversionCode=100 -PversionName=1.0.0
```

R8 + resource shrinking are on, so the APK is ~1.5 MB instead of ~17 MB (debug)
and starts in ~240 ms instead of ~880 ms on this device. CI builds it on every
push and uploads `pig-release-apk` next to the debug artifact.

Signing: `PIG_RELEASE_STORE_FILE` / `PIG_RELEASE_STORE_PASSWORD` /
`PIG_RELEASE_KEY_ALIAS` / `PIG_RELEASE_KEY_PASSWORD` switch to the store key.
Without them the build uses the CI key, which is the same key the debug APK
uses, so a release build installs over an existing debug install (`pm install
-r`) and can be performance-tested. Publishing to other people requires the
store key, because the signature must stay stable across updates.

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
