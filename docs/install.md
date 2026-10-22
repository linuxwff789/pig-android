# Install the backend in Termux

The Android app is a front-end. `pig` and `pig-relay` run inside Termux.

## One command

Install Termux from F-Droid (the Play Store build is obsolete), open it once so
`pkg` initializes, then:

```bash
pkg install -y curl
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
```

`pkg install -y curl` matters: a very fresh Termux may not have `curl` yet, and
both this installer and the official PiG installer need it.

It does three things:

1. installs `pig` with the official `https://pi-in-go.dev/install.sh` when
   `pig` is missing;
2. downloads `pig-relay-android-arm64` from the latest release into
   `$PREFIX/bin/pig-relay`;
3. creates `~/.pig/android-ui/{theme,layout,windows}`.

It also enables Termux's external-command switch in
`~/.termux/termux.properties` (keeping a `.bak-pig` backup) and runs
`termux-reload-settings`. **Open the Termux app once** so the property takes
effect, grant the Pig app `com.termux.permission.RUN_COMMAND`, and press retry.

### Why not just the in-app button?

`RUN_COMMAND` itself is blocked while `allow-external-apps=false`. On a clean
Termux the app cannot run the installer until the user enables that switch, so
the setup dialog gives you the two lines to paste into Termux. Once the switch
is on and Termux has been opened once, 一键安装 works.

## Manual

```bash
curl -fsSL https://pi-in-go.dev/install.sh | sh
curl -fsSL -o "$PREFIX/bin/pig-relay" \
  https://github.com/linuxwff789/pig-android/releases/latest/download/pig-relay-android-arm64
chmod 755 "$PREFIX/bin/pig-relay"
mkdir -p ~/.pig/android-ui/{theme,layout,windows}

pig --version
pig-relay --version
```

## What the app starts

```
App ── RUN_COMMAND ──▶ Termux RunCommandService
                         └─ pig-relay --port 8791 --token … --cwd ~
App ── TCP 127.0.0.1:8791 ──▶ pig-relay ── stdio ──▶ pig --mode rpc
```

If the app cannot connect, its setup dialog shows the one-liner and a 一键安装
button that runs the same script through Termux.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `pig-relay: not found` | Run the installer again; it must be in `$PREFIX/bin`. |
| Connection refused after 12 s | Relay not running, or Termux rejected RUN_COMMAND. Check `allow-external-apps` and open Termux once. |
| `cannot start pig` | `pig` missing or not executable: `command -v pig`, then `pig --version`. |
| `pkg: command not found` | You are not in Termux, or Termux is very old. Reinstall from F-Droid. |
| `curl: not found` | `pkg install -y curl`. |
| Stale relay / token after reinstall | `pkill -x pig-relay`, then press retry. The relay exits on a bad token if it never had a client. |
| App shows no model/windows | Check `ps -A | grep pig`; restart the app. |
