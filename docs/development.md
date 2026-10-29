**English** · [简体中文](development.zh-CN.md)

# Development

Build the relay and the app, run the client by hand and drive it from a shell.
None of this is needed to *use* the app — see [install.md](install.md) for that.

## Build the relay

```bash
cd relay
go build -trimpath -ldflags="-s -w -X main.buildVersion=dev" -o "$PREFIX/bin/pig-relay" .
```

`main.buildVersion` is what `pig-relay --version` reports next to the protocol
(`relay/1 1.1.27`); the release workflow stamps the tag into it, and the Termux
installer replaces a relay whose version differs from the app's.

The relay carries the extensions it ships with: `relay/embedded/` holds copies
of `extensions/` that the binary embeds and extracts on first start.

- `extensions/plan` is written to `~/.pig/agent/extensions/plan/` and
  registered with `pig install`. A plan extension the user configured (a
  repository checkout, another package) always wins over the embedded copy.
- `extensions/pigkit` (the module behind the app's own UI: `/provider-ui` for
  the provider window, custom endpoints, model speed, memory, questionnaire,
  codegraph) is written to `~/.pig/agent/pigkit/` and each package is appended
  to the `packages` list in `settings.json` as `pigkit/<name>`. A package
  directory without the relay's `.embedded-hash` marker is the user's: it is
  left untouched and never registered twice, and an entry that already names
  the same package from somewhere else wins.
- `examples/ui-pack-compact` (the only pack in the project) is written into
  `~/.pig/android-ui` as `relay/embedded/ui`. The APK ships no pack of its own,
  so the relay's copy is the single look. Only missing files are added, so a
  hand-edited theme or layout stays, and `windows/` is never shipped: a window is
  rendered by the extension that owns it (`provider-ui` writes
  `windows/providers.json` from the user's `custom-endpoints.json`).
  `scripts/check-ui-packs.sh` (CI) rejects a committed provider window —
  committing a live `~/.pig/android-ui` once leaked a real provider URL into the
  APK.

`relay/embedded/` is generated — after editing an extension run
`sh scripts/sync-embedded-extensions.sh` and commit the result; CI fails when it
is stale.

## Fresh-device simulation

`tools/fresh-device-sim/sim.sh` runs the Termux half on a real userspace: the
official `termux-packages` bootstrap is extracted under `~/.cache/...` and run
through `proot`, so the install script sees an empty `$PREFIX`, no `pig`, no Go
and a fresh `$HOME`. The app half is `tools/fresh-device-sim/app-sim.py`, which
sends the frames the APK sends and checks the UI pack, the pigkit commands, the
provider window and `/providerui.save` (reachable and unreachable endpoints).

```sh
tools/fresh-device-sim/sim.sh all      # wipe, install, then replay the app
tools/fresh-device-sim/sim.sh destroy  # ~1 GB of bootstrap + Go toolchain
```

It needs network (bootstrap, Termux packages, the official pig installer) and
about five minutes on a phone; it is not part of CI. See the README there for
per-step commands and for what is a proot artifact rather than a bug.

### Relay and pig logs

The app cannot read logcat or Termux's scrollback, so the relay streams what it
has: every `logf` line and every pig stderr line goes over the socket as a
`relay_log` frame (`source`: `relay`/`pig`, `level`: `info`/`error`), the last
400 are buffered and replayed as `relay_logs` right after the handshake, and a
crash carries the pig stderr tail in `relay_exit`. The app shows failures as a
toast, keeps everything in the diagnostic log, and Settings → 诊断日志 → 查看
tails it live.

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
GitHub Release with the APK attached (plus `pig-relay-android-arm64` and
`install-termux.sh`). Every tag is published as a **pre-release** by default; a
release is only promoted to a normal one when explicitly asked for.

Version numbers have four parts, `X.Y.Z.W`:

- `X.Y` — product line (stable for a long while)
- `Z` — one batch of changes ("round"); it only moves when the next round starts
- `W` — the build inside that round: `1.1.68.1`, `1.1.68.2`, …

So previews never consume a "release number". Promoting a build to a normal
release keeps its number (uncheck *Pre-release* on GitHub, or run
`PIG_GITHUB_TOKEN=... scripts/promote-release.sh v1.1.68.1`); `latest` then
points at it, and the next preview continues the `W` counter of a new `Z`.
All comparators (app update check, relay-vs-app nag, `install-termux.sh`) treat
missing parts as zero, so old three-part versions still order correctly.

A release can carry its own summary: `release-notes/<tag>.md`
is prepended to the generated "What's Changed" list, see
[release-notes/README.md](../release-notes/README.md). The release key differs from the debug
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
