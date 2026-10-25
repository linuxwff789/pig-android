**English** · [简体中文](install.zh-CN.md)

# Install the backend in Termux

The Android app is a front-end. `pig` and `pig-relay` run inside Termux.

## One command

Install Termux from F-Droid (the Play Store build is obsolete), open it once so
`pkg` initializes, then:

``bash
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
``

Nothing has to be installed first: Termux's bootstrap already ships `curl` (and
the installer checks for it anyway). The installer also switches the main repo
to a Chinese mirror *before* its first `pkg install`, so the Go toolchain
download already uses the mirror; the app's 一键安装 runs the same command.

Pass the app's release to install the relay published with it (the app does this
for you; this is the manual form):

``bash
curl -fsSL https://github.com/linuxwff789/pig-android/releases/download/v1.1.27/install-termux.sh | sh -s -- v1.1.27
``

It does six things:

1. points the Termux main repo at a Chinese mirror when it still uses
   `packages.termux.dev` and this run has to `pkg install` something (curl or
   Go); TUNA by default, `PIG_TERMUX_MIRROR=…` picks another, `PIG_NO_MIRROR=1`
   skips it, `PIG_FORCE_MIRROR=1` does it anyway, and the old file stays as
   `sources.list.bak-pig`);
2. installs `pig` with the official `https://pi-in-go.dev/install.sh` when
   `pig` is missing;
3. installs the Go toolchain with `pkg install golang` when it is missing — the
   relay builds the plan extension it carries on first start;
4. downloads `pig-relay-android-arm64` into `$PREFIX/bin/pig-relay`, replacing
   an older version atomically (`pig-relay --version` prints the release, so a
   mismatch is upgraded rather than kept). Run without a tag it asks GitHub which
   tag "latest" is, so `sh install-termux.sh` upgrades an old relay too — an old
   relay carries old (or no) embedded extensions;
5. creates `~/.pig/android-ui/{theme,layout,windows}`; on start the relay then
   *fills in* the default UI pack (`manifest.json`, theme, layout, strings,
   nav) — only files that are missing, never ones you edited. Without it the
   pack the app receives has no `manifest.json`, so the app answers
   "UI pack 未应用" and falls back to its bundled theme;
6. ships the `pigkit` extensions inside the relay binary itself (the provider
   window the app opens as 模型供应商, custom endpoints, model speed, memory,
   questionnaire, codegraph): on first start the relay writes them to
   `~/.pig/agent/pigkit/` and registers each one in `settings.json`. Nothing
   else has to be downloaded, and a directory you already own is never
   overwritten.

It also enables Termux's external-command switch in
`~/.termux/termux.properties` (keeping a `.bak-pig` backup) and runs
`termux-reload-settings`. **Open the Termux app once** so the property takes
effect, grant the Pig app `com.termux.permission.RUN_COMMAND`, and press retry.

Finally it starts `pig` once through the RPC mode and asks for its extension
list, so a toolchain that cannot build the extensions is reported here instead
of showing up later as "the app only has built-in commands". When that probe
fails it runs `pig update --extensions` once and probes again — a cached,
failed build cell is only replaced by an explicit rebuild.

Every download retries and falls back to HTTP/1.1 when the connection breaks
(curl exit 35/53/56 and friends). Files are written as `.part` and checked
(non-empty, and ELF magic for the relay) before replacing anything, so a failed
run leaves neither a half script nor a half binary behind.

### Why not just the in-app button?

`RUN_COMMAND` itself is blocked while `allow-external-apps=false`. On a clean
Termux the app cannot run the installer until the user enables that switch, so
the setup dialog gives you the one line to paste into Termux: it has
**复制命令** and **打开 Termux** buttons, and while the install runs it shows a
staged progress line (Termux's output is in its own window; the dialog switches
to connected on its own when the relay answers).

The app now reads the result Termux sends back for every `RUN_COMMAND`, so a
refused command (`allow-external-apps` off) or a missing `pig-relay` shows up
immediately with that hint, instead of looking like a request that did nothing.

When the switch is the blocker, the dialog offers a shorter one-liner that only
flips it — the single step the app cannot do for you:

``bash
mkdir -p ~/.termux; f=~/.termux/termux.properties; sed -i '/allow-external-apps/d' "$f" 2>/dev/null; echo 'allow-external-apps = true' >> "$f" && termux-reload-settings
``

It is plain shell (works offline), drops any old/commented `allow-external-apps`
line before appending, and is safe to repeat. The same thing ships as a script
in every release if you prefer a readable version:

``bash
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/enable-external-apps.sh | sh
``

After that the app's own install button works, with progress and the final exit
code. Once the switch is on and
Termux has been opened once, 一键安装 works.

## Manual

``bash
curl -fsSL https://pi-in-go.dev/install.sh | sh
curl -fsSL -o "$PREFIX/bin/pig-relay" \
  https://github.com/linuxwff789/pig-android/releases/latest/download/pig-relay-android-arm64
chmod 755 "$PREFIX/bin/pig-relay"
mkdir -p ~/.pig/android-ui/{theme,layout,windows}

pig --version
pig-relay --version
``

## What the app starts

``
App ── RUN_COMMAND ──▶ Termux RunCommandService
                         └─ pig-relay --port 8791 --token … --cwd ~
App ── TCP 127.0.0.1:8791 ──▶ pig-relay ── stdio ──▶ pig --mode rpc
``

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
