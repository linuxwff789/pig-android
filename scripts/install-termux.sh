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
RELAY_URL="" # resolved below, once the release to install is known

say() { printf '==> %s\n' "$*"; }

# Downloads are the fragile part on a phone: TLS resets, captive portals and
# HTTP/2 stacks behind proxies all surface as odd curl exit codes (53 is
# CURLE_SSL_ENGINE_NOTFOUND, and a bare `curl: (35) TLS connect error:
# error:00000000:lib(0)::reason(0)` is a handshake a middlebox killed).
# Retry every failure (a TLS handshake that never completes is not retried by
# plain `--retry`), and switch to TLS 1.2 + HTTP/1.1 from the second attempt.
# flaky proxies and middleboxes fail on TLS 1.3/HTTP2 with
# "error:00000000:lib(0)::reason(0)". Never leave a partial file behind for the
# next step to execute.
fetch() {
    url=$1
    dest=$2
    tmp="$dest.part.$$"
    attempt=1
    while [ "$attempt" -le 3 ]; do
        extra=""
        [ "$attempt" -ge 2 ] && extra="--tls-max 1.2 --http1.1"
        if curl -fsSL $extra --connect-timeout 20 --retry 1 --retry-all-errors \
            --retry-delay 2 -o "$tmp" "$url"; then
            if [ -s "$tmp" ]; then
                mv -f "$tmp" "$dest"
                return 0
            fi
            say "downloaded 0 bytes: $url"
        else
            code=$?
            say "download failed (curl exit $code): $url"
            [ -n "$extra" ] || say "retrying with --tls-max 1.2 --http1.1"
        fi
        rm -f "$tmp"
        attempt=$((attempt + 1))
        [ "$attempt" -le 3 ] && sleep 2
    done
    rm -f "$tmp"
    return 1
}

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
    if fetch https://pi-in-go.dev/install.sh "$PREFIX/tmp/pig-official-install.sh"; then
        sh "$PREFIX/tmp/pig-official-install.sh" ||
            say "pig installer failed; rerun: sh $PREFIX/tmp/pig-official-install.sh"
    else
        say "could not download https://pi-in-go.dev/install.sh; install pig later with:"
        say "  curl -fsSL https://pi-in-go.dev/install.sh | sh"
    fi
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

# No tag passed: ask GitHub what "latest" currently is. Without this an
# already-installed relay is kept forever, and an old relay carries an old (or
# missing) set of embedded extensions - the app then has no /provider-ui even
# though the install "succeeded".
if [ -z "$version" ]; then
    resolved=$(curl -fsSL --connect-timeout 15 -o /dev/null -w '%{url_effective}' \
        "$REPO/releases/latest" 2>/dev/null || true)
    case "$resolved" in
        */releases/tag/v*)
            version="${resolved##*/releases/tag/v}"
            base="$REPO/releases/download/v$version"
            say "latest release is v$version"
            ;;
    esac
fi
RELAY_URL="${PIG_RELAY_URL:-$base/pig-relay-android-arm64}"

if [ -n "$version" ] && [ "$have" = "$version" ]; then
    say "pig-relay already at $version"
elif [ -z "$version" ] && [ -n "$have" ]; then
    say "pig-relay already installed: $have (could not resolve the latest release)"
else
    say "installing pig-relay ${version:-latest}"
    tmp="$PREFIX/bin/.pig-relay.download"
    # GitHub can be unreachable while the rest of the network works; PIG_RELAY_MIRRORS
    # takes URL prefixes (space separated), e.g. "https://ghfast.top/ ".
    urls="$RELAY_URL"
    for prefix in ${PIG_RELAY_MIRRORS:-}; do
        urls="$urls ${prefix}${RELAY_URL}"
    done
    ok=0
    for url in $urls; do
        [ "$url" = "$RELAY_URL" ] || say "trying mirror: $url"
        fetch "$url" "$tmp" && { ok=1; break; }
    done
    if [ "$ok" = 0 ]; then
        say "could not download $RELAY_URL"
        say "hint: download it another way and place it at $RELAY (chmod 700),"
        say "      or set PIG_RELAY_MIRRORS to a prefix like https://ghfast.top/"
    elif [ "$(head -c 4 "$tmp")" != "$(printf '\177ELF')" ]; then
        # A captive portal or an error page answers 200 with HTML; never
        # replace a working relay with that.
        say "$RELAY_URL is not an executable ($(wc -c < "$tmp") bytes); not installing"
        rm -f "$tmp"
    else
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
fi

mkdir -p "$HOME/.pig/android-ui/theme" "$HOME/.pig/android-ui/layout" "$HOME/.pig/android-ui/windows"

# pig compiles the pigkit extensions from Go sources on its first start, and
# builds one content-addressed cell per package. A cell that failed once (an
# older Go, a half-finished download) is reused until something asks for a
# rebuild, which is why "the app has no /provider-ui" survived a reinstall and
# only `pig update --extensions` fixed it. Probe, then repair automatically and
# say what happened.
probe_extensions() {
    if command -v timeout >/dev/null 2>&1; then
        printf '%s\n' '{"id":1,"type":"get_extensions"}' |
            timeout 300 pig --mode rpc 2>&1 || true
    else
        printf '%s\n' '{"id":1,"type":"get_extensions"}' |
            pig --mode rpc 2>&1 || true
    fi
}

if command -v pig >/dev/null 2>&1; then
    say "checking that pig loads its extensions"
    probe=$(probe_extensions)
    case "$probe" in
        *'"success":true'*) say "extensions load" ;;
        *)
            printf '!! pig could not start with its extensions. Until this is\n'
            printf '!! fixed the app has no /provider-ui, /endpoints or /plan.\n'
            printf '%s\n' "$probe" | tail -6
            say "rebuilding them: pig update --extensions"
            if pig update --extensions --no-approve >/dev/null 2>&1; then
                probe=$(probe_extensions)
                case "$probe" in
                    *'"success":true'*) say "extensions load after pig update --extensions" ;;
                    *)
                        say "still failing. Check that Go is installed:"
                        say "  pkg install golang && pig update --extensions"
                        printf '%s\n' "$probe" | tail -6
                        ;;
                esac
            else
                say "pig update --extensions failed; run it yourself to see why"
            fi
            ;;
    esac
fi

printf '\npig:   %s\nrelay: %s (%s)\n' \
    "$(command -v pig || echo "$PREFIX/bin/pig")" "$RELAY" "$(relay_version)"
printf 'Open Termux once so allow-external-apps takes effect, then return to Pig and press retry.\n'
