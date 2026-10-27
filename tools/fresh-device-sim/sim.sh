#!/bin/sh
# Simulate a brand-new Android phone for the Termux half of Pig.
#
# A real Termux userspace (the official bootstrap zip, bionic binaries and all)
# is extracted under $SIM/root and run through proot, so the install script sees
# an empty $PREFIX, no pig, no Go, no ~/.pig and a fresh $HOME - exactly what a
# new device looks like. The Pig *app* cannot run without an Android emulator, so
# `app-sim.py` replays the frames it sends and checks what comes back.
#
# Usage:
#   tools/fresh-device-sim/sim.sh all          # bootstrap + install + relay + app
#   tools/fresh-device-sim/sim.sh bootstrap
#   tools/fresh-device-sim/sim.sh install [tag]
#   tools/fresh-device-sim/sim.sh relay        # start, then press Ctrl-C
#   tools/fresh-device-sim/sim.sh app          # replay the app against a running relay
#   tools/fresh-device-sim/sim.sh pack         # bundled assets: manifest present, no leaked window
#   tools/fresh-device-sim/sim.sh reset        # wipe the device, keep the zip
#   tools/fresh-device-sim/sim.sh stop         # stop the simulated relay
#   tools/fresh-device-sim/sim.sh shell        # drop into the simulated device
#   tools/fresh-device-sim/sim.sh destroy
#
# Environment:
#   SIM_ROOT          where the fake device lives (default ~/.cache/pig-fresh-device)
#   BOOTSTRAP_TAG     termux-packages bootstrap release to use
#   RELAY_TAG         release to install (default: the repo's HEAD tag, else latest)
#   PIG_RELAY_URL     install this relay instead of downloading one (file:// works)
#   PORT              relay port (default 18793)
#   TOKEN             relay token (default sim-token)
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$here/../.." && pwd)
SIM=${SIM_ROOT:-$HOME/.cache/pig-fresh-device}
ROOT="$SIM/root"
GUEST_PREFIX=/data/data/com.termux/files/usr
GUEST_HOME=/data/data/com.termux/files/home
PORT=${PORT:-18793}
TOKEN=${TOKEN:-sim-token}
BOOTSTRAP_TAG=${BOOTSTRAP_TAG:-bootstrap-2026.10.25-r1+apt.android-7}
ARCH=aarch64

say() { printf '==> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

# --- the device -------------------------------------------------------------

download() {
    url=$1
    dest=$2
    tmp="$dest.part"
    attempt=1
    while [ "$attempt" -le 3 ]; do
        if curl -fsSL --connect-timeout 20 --retry 2 --retry-delay 2 -o "$tmp" "$url"; then
            mv -f "$tmp" "$dest"
            return 0
        fi
        code=$?
        say "download failed (curl $code): $url"
        case "$code" in
            16|18|35|52|53|55|56|92)
                say "retrying over HTTP/1.1"
                if curl -fsSL --http1.1 --connect-timeout 20 -o "$tmp" "$url"; then
                    mv -f "$tmp" "$dest"
                    return 0
                fi
                ;;
        esac
        rm -f "$tmp"
        attempt=$((attempt + 1))
        [ "$attempt" -le 3 ] && sleep 2
    done
    return 1
}

bootstrap() {
    [ -x "$ROOT$GUEST_PREFIX/bin/bash" ] && { say "bootstrap already extracted"; return 0; }
    mkdir -p "$SIM"
    zip="$SIM/bootstrap-$ARCH.zip"
    if [ ! -s "$zip" ]; then
        # '+' must be escaped in the release URL.
        encoded=$(printf '%s' "$BOOTSTRAP_TAG" | sed 's/+/%2B/g')
        url="https://github.com/termux/termux-packages/releases/download/$encoded/bootstrap-$ARCH.zip"
        say "downloading $BOOTSTRAP_TAG ($ARCH)"
        download "$url" "$zip" || die "could not download $url"
    fi
    say "extracting bootstrap"
    python3 - "$zip" "$ROOT" "$GUEST_PREFIX" <<'PY'
import os, shutil, sys, zipfile
zip_path, root, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.rmtree(root, ignore_errors=True)
target = root + prefix
os.makedirs(target, exist_ok=True)
os.makedirs(root + "/data/data/com.termux/files/home", exist_ok=True)
z = zipfile.ZipFile(zip_path)
for info in z.infolist():
    path = os.path.join(target, info.filename)
    if info.is_dir():
        os.makedirs(path, exist_ok=True)
        continue
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with z.open(info) as src, open(path, "wb") as dst:
        shutil.copyfileobj(src, dst)
    mode = (info.external_attr >> 16) & 0o7777
    if mode:
        os.chmod(path, mode)
PY
    # The zip carries 0600 for most files (the Termux app fixes them up while
    # installing); a plain extraction needs the same fix or nothing executes.
    chmod -R a+rX "$ROOT$GUEST_PREFIX"
    chmod 755 "$ROOT$GUEST_PREFIX"/bin/* "$ROOT$GUEST_PREFIX"/libexec/* 2>/dev/null || true
    chmod 755 "$ROOT$GUEST_PREFIX"/lib/*.so* 2>/dev/null || true
    # A real Termux app creates these; apt refuses to unpack without them.
    mkdir -p "$ROOT/data/data/com.termux/cache/apt/archives/partial" \
        "$ROOT$GUEST_HOME/.cache"
    say "creating symlinks"
    ( cd "$ROOT$GUEST_PREFIX" && python3 - <<'PY'
import os
made = 0
for line in open("SYMLINKS.txt"):
    line = line.rstrip("\n")
    if not line:
        continue
    target, link = line.split("←", 1)
    link = link.lstrip("./")
    if os.path.islink(link) or os.path.exists(link):
        continue
    os.makedirs(os.path.dirname(link) or ".", exist_ok=True)
    os.symlink(target, link)
    made += 1
print("   %d symlinks" % made)
PY
    )
    say "bootstrap ready: $ROOT$GUEST_PREFIX"
}

# proot wrapper: a clean environment, the guest paths, shared network.
# Android's linker and runtime live outside the guest root, so bionic binaries
# from the bootstrap need them mounted in.
device() {
    mkdir -p "$SIM/artifacts"
    # No -0: Termux's `pkg` refuses to run as root, and a real Termux app runs
    # as its own Android uid, which is exactly what proot maps here.
    proot -r "$ROOT" \
        -b /system -b /apex -b /vendor -b /data/dalvik-cache \
        -b /dev -b /proc -b /sys \
        -b "$SIM/artifacts:/artifacts" \
        ${SIM_BIND:+-b "$SIM_BIND"} \
        -w "$GUEST_HOME" \
        "$GUEST_PREFIX/bin/env" -i \
        HOME="$GUEST_HOME" PREFIX="$GUEST_PREFIX" \
        PATH="$GUEST_PREFIX/bin" \
        TMPDIR="$GUEST_PREFIX/tmp" TERM=xterm-256color LANG=en_US.UTF-8 \
        "$@"
}

relay_tag() {
    if [ -n "${RELAY_TAG:-}" ]; then
        echo "$RELAY_TAG"
        return
    fi
    tag=$(git -C "$repo" describe --tags --exact-match 2>/dev/null || true)
    if [ -n "$tag" ]; then
        echo "$tag"
        return
    fi
    curl -fsSL --connect-timeout 15 -o /dev/null -w '%{url_effective}' \
        "https://github.com/linuxwff789/pig-android/releases/latest" 2>/dev/null |
        sed -n 's|.*/releases/tag/||p'
}

install_pig() {
    bootstrap
    [ -x "$ROOT$GUEST_PREFIX/bin/bash" ] || die "no bootstrap"
    tag=${1:-$(relay_tag)}
    say "installing into the simulated device (tag=${tag:-latest})"
    # Bind the working copy read-only: the script under test is the one in this
    # checkout, not whatever the release ships.
    mkdir -p "$SIM/artifacts"
    relay_url=${PIG_RELAY_URL:-}
    case "$relay_url" in
        file://*|/*)
            # A locally built relay is copied in and served through the bind
            # mount, so the guest never needs the host's filesystem layout.
            src=${relay_url#file://}
            [ -f "$src" ] || die "no such relay: $src"
            dest="$SIM/artifacts/pig-relay-android-arm64"
            if [ "$src" != "$dest" ]; then cp -f "$src" "$dest"; fi
            relay_url=file:///artifacts/pig-relay-android-arm64
            say "relay source: $src"
            ;;
        "") ;;
        *) say "relay source: $relay_url" ;;
    esac
    SIM_BIND="$repo:/repo" device /data/data/com.termux/files/usr/bin/bash -c \
        "cd \$HOME && PIG_RELAY_URL='$relay_url' sh /repo/scripts/install-termux.sh ${tag}" \
        2>&1 | sed '/failed to find generated linker configuration/d' | tee "$SIM/install.log"
    say "log: $SIM/install.log"
}

free_port() {
    python3 - "$PORT" <<'PY' >/dev/null 2>&1
import socket, sys
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", int(sys.argv[1])))
s.close()
PY
}

stop_relay() {
    if [ -f "$SIM/relay.pid" ]; then
        kill "$(cat "$SIM/relay.pid")" 2>/dev/null || true
        rm -f "$SIM/relay.pid"
    fi
    # Killing the proot wrapper leaves the traced relay alive (and holding the
    # port); this pattern only matches the relay of *this* port.
    pkill -f "pig-rela[y] --port $PORT" 2>/dev/null || true
    i=0
    while [ "$i" -lt 10 ]; do
        if free_port; then break; fi
        i=$((i + 1))
        sleep 0.5
    done
}

start_relay() {
    [ -x "$ROOT$GUEST_PREFIX/bin/pig-relay" ] || die "pig-relay is not installed; run 'install' first"
    echo $$ > "$SIM/relay.pid"
    say "relay on 127.0.0.1:$PORT (token $TOKEN), Ctrl-C to stop"
    device "$GUEST_PREFIX/bin/pig-relay" --port "$PORT" --token "$TOKEN" \
        --cwd "$GUEST_HOME" --pig "${SIM_PIG:-pig}"
}

app_sim() {
    python3 "$here/app-sim.py" \
        --port "$PORT" --token "$TOKEN" \
        --device-root "$ROOT" \
        --guest-prefix "$GUEST_PREFIX" --guest-home "$GUEST_HOME"
}

pack_check() {
    sh "$repo/scripts/check-ui-packs.sh"
    python3 - "$repo/app/src/main/assets/ui-packs/compact" <<'PY'
import json, os, sys
root = sys.argv[1]
need = ["manifest.json", "theme/colors.json", "layout/chat.json"]
missing = [p for p in need if not os.path.isfile(os.path.join(root, p))]
if missing:
    sys.exit("bundled pack is missing: " + ", ".join(missing))
manifest = json.load(open(os.path.join(root, "manifest.json")))
for key in ("name", "theme", "layout"):
    if not manifest.get(key):
        sys.exit("bundled manifest has no %s" % key)
print("pack: %s v%s, %s" % (manifest.get("name"), manifest.get("version"), ", ".join(need)))
PY
}

reset() {
    rm -rf "$ROOT"
    bootstrap
}

destroy() {
    rm -rf "$SIM"
    say "removed $SIM"
}

case "${1:-all}" in
    bootstrap) bootstrap ;;
    install) shift; install_pig "$@" ;;
    relay) start_relay ;;
    stop) stop_relay ;;
    app) app_sim ;;
    logs)
        # A relay that cannot start pig must say so on the wire, not only in
        # Termux's stderr (that was the "errors only appear after a reconnect"
        # bug).
        say "checking that relay errors reach the app immediately"
        stop_relay
        SIM_PIG=${SIM_PIG:-/nonexistent/pig} PORT=$((PORT + 1)) TOKEN="$TOKEN" \
            sh "$0" relay >"$SIM/relay-error.log" 2>&1 &
        relay_pid=$!
        trap 'kill $relay_pid 2>/dev/null || true; stop_relay' EXIT INT TERM
        python3 "$here/log-check.py" --port $((PORT + 1)) --token "$TOKEN"
        status=$?
        kill $relay_pid 2>/dev/null || true
        pkill -f "pig-rela[y] --port $((PORT + 1))" 2>/dev/null || true
        exit $status
        ;;
    pack) pack_check ;;
    shell) bootstrap; device "$GUEST_PREFIX/bin/bash" ;;
    reset) reset ;;
    destroy) stop_relay; destroy ;;
    all)
        # A brand-new phone every time unless SIM_KEEP=1 is set: an install on
        # top of an existing $PREFIX tests the upgrade path, not this one.
        if [ "${SIM_KEEP:-0}" = "1" ]; then bootstrap; else reset; fi
        install_pig
        pack_check
        say "starting the relay in the background"
        stop_relay
        PORT="$PORT" TOKEN="$TOKEN" sh "$0" relay >"$SIM/relay.log" 2>&1 &
        relay_pid=$!
        trap 'kill $relay_pid 2>/dev/null || true; stop_relay' EXIT INT TERM
        # app-sim retries the connection itself; probing the port without
        # authenticating would make the relay release it again.
        app_sim
        ;;
    *) die "unknown step: $1" ;;
esac
