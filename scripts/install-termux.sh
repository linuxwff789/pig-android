#!/data/data/com.termux/files/usr/bin/sh
# Install the Pig backend into Termux: pig + pig-relay + UI pack dirs.
#
#   curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
#
# Pass a release tag to install the relay released with that app version:
#
#   ... | sh -s -- v1.1.27
#
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
REPO="https://github.com/linuxwff789/pig-android"
MIRROR="${PIG_TERMUX_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main}"

# The version the app passed in: no tag means "whatever is newest".
requested="${1:-${PIG_VERSION:-}}"
case "$requested" in
    "")  version="";                     base="$REPO/releases/latest/download" ;;
    v*)  version="${requested#v}";        base="$REPO/releases/download/$requested" ;;
    *)   version="$requested";            base="$REPO/releases/download/v$requested" ;;
esac
RELAY_URL="${PIG_RELAY_URL:-$base/pig-relay-android-arm64}"

say() { printf '==> %s\n' "$*"; }

# A fresh Termux points its main repo at packages.termux.dev, which is slow or
# unreachable in China, and the Go toolchain below is the first big download.
# Only the official host is rewritten, so a mirror the user picked stays, and
# only when this run needs `pkg install` at all.
SOURCES="$PREFIX/etc/apt/sources.list"
NEED_PKG=0
command -v curl >/dev/null 2>&1 || NEED_PKG=1
command -v go >/dev/null 2>&1 || NEED_PKG=1
[ "${PIG_FORCE_MIRROR:-0}" = "1" ] && NEED_PKG=1
MIRRORED=0
if [ "$NEED_PKG" = 1 ] && [ "${PIG_NO_MIRROR:-0}" != "1" ] && [ -f "$SOURCES" ] &&
    grep -qE '^[[:space:]]*deb[[:space:]]+https?://packages(-cf)?\.termux\.dev/apt/termux-main' "$SOURCES"; then
    cp "$SOURCES" "$SOURCES.bak-pig"
    sed -i -E "s#^[[:space:]]*deb[[:space:]]+https?://packages(-cf)?\.termux\.dev/apt/termux-main.*#deb $MIRROR stable main#" "$SOURCES"
    MIRRORED=1
    say "Termux main repo -> $MIRROR (backup: $SOURCES.bak-pig)"
    apt-get update -y >/dev/null 2>&1 || say "apt update failed; keeping the mirror for the next try"
elif [ "$NEED_PKG" = 1 ] && [ ! -f "$SOURCES" ] && [ "${PIG_NO_MIRROR:-0}" != "1" ]; then
    mkdir -p "$PREFIX/etc/apt"
    printf 'deb %s stable main\n' "$MIRROR" > "$SOURCES"
    MIRRORED=1
    say "wrote $SOURCES -> $MIRROR"
    apt-get update -y >/dev/null 2>&1 || true
fi

if ! command -v curl >/dev/null 2>&1; then
    say "installing curl"
    pkg install -y curl
fi

# The app's RUN_COMMAND path is blocked until this is enabled. Keep a backup.
PROPS="$HOME/.termux/termux.properties"
mkdir -p "$HOME/.termux"
if [ -f "$PROPS" ] && grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$PROPS"; then
    say "allow-external-apps is already enabled"
elif [ -f "$PROPS" ] && grep -qE '^[#[:space:]]*allow-external-apps' "$PROPS"; then
    cp "$PROPS" "$PROPS.bak-pig"
    sed -i 's/^[#[:space:]]*allow-external-apps.*/allow-external-apps = true/' "$PROPS"
    say "enabled allow-external-apps (backup: $PROPS.bak-pig)"
elif [ -f "$PROPS" ]; then
    cp "$PROPS" "$PROPS.bak-pig"
    printf '\nallow-external-apps = true\n' >> "$PROPS"
    say "enabled allow-external-apps (backup: $PROPS.bak-pig)"
else
    printf 'allow-external-apps = true\n' > "$PROPS"
    say "created $PROPS with allow-external-apps = true"
fi
command -v termux-reload-settings >/dev/null 2>&1 && termux-reload-settings || true

if ! command -v pig >/dev/null 2>&1; then
    say "installing pig (official install.sh)"
    curl -fsSL https://pi-in-go.dev/install.sh | sh
else
    say "pig already installed: $(command -v pig)"
fi

# pig-relay carries the plan extension as Go sources and builds them with the
# system Go toolchain. Without Go the app works, only /plan stays missing, and
# the relay retries on every start.
if ! command -v go >/dev/null 2>&1; then
    say "installing Go (needed to build the bundled plan extension)"
    if ! pkg install -y golang; then
        if [ "$MIRRORED" = 1 ] && [ -f "$SOURCES.bak-pig" ]; then
            say "install failed; restoring the official repo and retrying once"
            cp "$SOURCES.bak-pig" "$SOURCES"
            apt-get update -y >/dev/null 2>&1 || true
        fi
        pkg install -y golang || say "Go install failed; run 'pkg install golang' to enable /plan"
    fi
else
    say "Go already installed: $(command -v go)"
fi

# Install the relay of the requested release, or replace one of another
# version. `--version` prints "<protocol> <version>", so an older relay (which
# printed only the protocol) counts as a mismatch.
RELAY="$PREFIX/bin/pig-relay"
relay_version() {
    [ -x "$RELAY" ] || return 0
    "$RELAY" --version 2>/dev/null | awk 'NR == 1 { print $2 }'
}
have=$(relay_version)
if [ -z "$version" ] && [ -n "$have" ]; then
    say "pig-relay already installed: $have"
elif [ -n "$version" ] && [ "$have" = "$version" ]; then
    say "pig-relay already at $version"
else
    say "installing pig-relay ${version:-latest}"
    tmp="$PREFIX/bin/.pig-relay.download"
    curl -fsSL "$RELAY_URL" -o "$tmp"
    chmod 700 "$tmp"
    # Atomic: a running relay keeps the old inode, the next start uses this one.
    mv -f "$tmp" "$RELAY"
    have=$(relay_version)
    if [ -n "$version" ] && [ "$have" != "$version" ]; then
        say "warning: $RELAY reports '${have:-nothing}', expected $version"
    fi
    if command -v pgrep >/dev/null 2>&1 && pgrep -f "pig-rela[y] --port" >/dev/null 2>&1; then
        say "a relay is running; restart it (or reopen the app) to use this binary"
    fi
fi

mkdir -p "$HOME/.pig/android-ui/theme" "$HOME/.pig/android-ui/layout" "$HOME/.pig/android-ui/windows"

printf '\npig:   %s\nrelay: %s (%s)\n' \
    "$(command -v pig || echo "$PREFIX/bin/pig")" "$RELAY" "$(relay_version)"
printf 'Open Termux once so allow-external-apps takes effect, then return to Pig and press retry.\n'
