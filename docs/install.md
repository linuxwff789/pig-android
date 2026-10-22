# Install the backend in Termux

The Android app is a front-end. `pig` and `pig-relay` run inside Termux.

## One command

```bash
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
```

It does three things:

1. installs `pig` with the official `https://pi-in-go.dev/install.sh` when
   `pig` is missing;
2. downloads `pig-relay-android-arm64` from the latest release into
   `$PREFIX/bin/pig-relay`;
3. creates `~/.pig/android-ui/{theme,layout,windows}`.

Then enable Termux's external-command switch:

```bash
# ~/.termux/termux.properties
allow-external-apps = true
```

Open the Termux app once (or run `termux-reload-settings` while an activity is
alive), grant the Pig app `com.termux.permission.RUN_COMMAND`, and press retry.

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
| Stale relay / token after reinstall | `pkill -x pig-relay`, then press retry. The relay exits on a bad token if it never had a client. |
| App shows no model/windows | Check `ps -A | grep pig`; restart the app. |
