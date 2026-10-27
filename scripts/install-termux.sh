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

# The app cannot see this script's stdout until Termux returns, so progress is
# also published as a small file. relay 1.1.36+ watches it, holds agent RPCs
# while state=running and forwards the stages to the app (relay_install).
TOTAL_STEPS=9
STATUS_FILE="$HOME/.pig/install-status.json"
STATUS_WRITTEN=0

status() { # step state message
    _st_step=$1
    _st_state=$2
    _st_message=$3
    mkdir -p "$HOME/.pig" 2>/dev/null || true
    _st_tmp="$STATUS_FILE.tmp.$$"
    if printf '{"state":"%s","step":%s,"total":%s,"message":"%s","updated":%s}\n' \
        "$_st_state" "$_st_step" "$TOTAL_STEPS" "$_st_message" "$(date +%s)" >"$_st_tmp" 2>/dev/null; then
        mv -f "$_st_tmp" "$STATUS_FILE" 2>/dev/null || rm -f "$_st_tmp"
    fi
}

step() { # step message
    status "$1" running "$2"
    say "[$1/$TOTAL_STEPS] $2"
}

fail_install() {
    status 9 error "$1"
    STATUS_WRITTEN=1
    printf '\n!! %s\n' "$1" >&2
    exit 1
}

# A crash in a `set -e` command must not leave state=running behind: the relay
# would keep agent requests on hold until the status goes stale.
trap 'code=$?; if [ "$code" -ne 0 ] && [ "$STATUS_WRITTEN" = 0 ]; then status 9 error "安装脚本异常退出（exit $code）"; fi; exit $code' EXIT

# Downloads are the fragile part on a phone: TLS resets, captive portals and
# HTTP/2 stacks behind proxies all surface as odd curl exit codes (53 is
# CURLE_SSL_ENGINE_NOTFOUND, and a bare `curl: (35) TLS connect error:
# error:00000000:lib(0)::reason(0)` is a handshake a middlebox killed).
# Retry every failure (a TLS handshake that never completes is not retried by
# plain `--retry`), and switch to TLS 1.2 + HTTP/1.1 from the second attempt.
# flaky proxies and middleboxes fail on TLS 1.3/HTTP2 with
# "error:00000000:lib(0)::reason(0)". Never leave a partial file behind for the
# next step to execute.
curl_get() {  # url dest [extra curl args...]
    # POSIX sh has no locals, so every name here is prefixed: a caller's $tmp
    # and $dest must survive the call (the relay step checks $tmp afterwards).
    _cg_url=$1
    _cg_dest=$2
    shift 2
    _cg_tmp="$_cg_dest.part.$$"
    _cg_attempt=1
    while [ "$_cg_attempt" -le 3 ]; do
        _cg_extra=""
        [ "$_cg_attempt" -ge 2 ] && _cg_extra="--tls-max 1.2 --http1.1"
        if curl -fsSL $_cg_extra --connect-timeout 20 --retry 1 --retry-all-errors \
            --retry-delay 2 "$@" -o "$_cg_tmp" "$_cg_url"; then
            if [ -s "$_cg_tmp" ]; then
                mv -f "$_cg_tmp" "$_cg_dest"
                return 0
            fi
            say "downloaded 0 bytes: $_cg_url"
        else
            _cg_code=$?
            say "download failed (curl exit $_cg_code): $_cg_url"
            [ -n "$_cg_extra" ] || say "retrying with --tls-max 1.2 --http1.1"
        fi
        rm -f "$_cg_tmp"
        _cg_attempt=$((_cg_attempt + 1))
        [ "$_cg_attempt" -le 3 ] && sleep 2
    done
    rm -f "$_cg_tmp"
    return 1
}

fetch() {
    curl_get "$1" "$2"
}

# github.com can be unreachable while the API host is fine (same repository,
# same asset): the asset endpoint answers with a redirect to
# objects.githubusercontent.com, a different host again. Used when the plain
# download link fails.
fetch_relay_api() {
    _api_dest=$1
    _api_base="https://api.github.com/repos/linuxwff789/pig-android/releases"
    if [ -n "$version" ]; then
        _api_release="$_api_base/tags/v$version"
    else
        _api_release="$_api_base/latest"
    fi
    _api_json="$PREFIX/tmp/.pig-release.$$.json"
    if ! curl_get "$_api_release" "$_api_json"; then
        rm -f "$_api_json"
        return 1
    fi
    _api_id=$(awk '/"id":/ { id=$0 } /"name": "pig-relay-android-arm64"/ { print id; exit }' "$_api_json" |
        sed 's/[^0-9]//g')
    rm -f "$_api_json"
    if [ -z "$_api_id" ]; then
        say "api.github.com: $_api_release does not list pig-relay-android-arm64"
        return 1
    fi
    say "trying the release asset API: api.github.com/.../assets/$_api_id"
    curl_get "$_api_base/assets/$_api_id" "$_api_dest" -H 'Accept: application/octet-stream'
}

# A fresh Termux points its main repo at packages.termux.dev, which is slow or
# unreachable in China, and the Go toolchain below is the first big download.
# termux-tools (>= 1.45) picks a mirror itself and rewrites sources.list from
# chosen_mirrors, so editing sources.list only holds until the first `pkg` call:
# point chosen_mirrors at a mainland mirror (what `termux-change-repo` writes)
# and leave a choice the user already made alone. Older termux-tools has no
# chosen_mirrors, hence the sources.list branch below.
step 1 "准备 Termux 环境（镜像与 apt）"
SOURCES="$PREFIX/etc/apt/sources.list"
MIRROR_DIR="$PREFIX/etc/termux/mirrors"
CHOSEN="$PREFIX/etc/termux/chosen_mirrors"
MIRROR_FILE="$MIRROR_DIR/chinese_mainland/mirrors.tuna.tsinghua.edu.cn"
NEED_PKG=0
command -v curl >/dev/null 2>&1 || NEED_PKG=1
command -v go >/dev/null 2>&1 || NEED_PKG=1
[ "${PIG_FORCE_MIRROR:-0}" = "1" ] && NEED_PKG=1
MIRRORED=0
if [ "$NEED_PKG" = 1 ] && [ "${PIG_NO_MIRROR:-0}" != "1" ] && [ ! -e "$CHOSEN" ] &&
    [ -f "$MIRROR_FILE" ]; then
    cp -f "$SOURCES" "$SOURCES.bak-pig" 2>/dev/null || true
    ln -sfn "$MIRROR_FILE" "$CHOSEN"
    main=$(. "$MIRROR_FILE" >/dev/null 2>&1; printf '%s' "${MAIN:-}") || true
    if [ -n "$main" ]; then
        mkdir -p "$PREFIX/etc/apt"
        printf 'deb %s stable main\n' "$main" > "$SOURCES"
    fi
    MIRRORED=1
    say "Termux mirror -> ${main:-$MIRROR} (chosen_mirrors; backup: $SOURCES.bak-pig)"
    apt-get update -y >/dev/null 2>&1 || say "apt update failed; keeping the mirror for the next try"
elif [ "$NEED_PKG" = 1 ] && [ "${PIG_NO_MIRROR:-0}" != "1" ] && [ -f "$SOURCES" ] &&
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

step 2 "检查 curl"
if ! command -v curl >/dev/null 2>&1; then
    say "installing curl"
    pkg install -y curl
fi

# The app's RUN_COMMAND path is blocked until this is enabled. Keep a backup.
step 3 "开启 allow-external-apps"
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

step 4 "安装 pig"
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

if ! command -v pig >/dev/null 2>&1; then
    fail_install "pig 没有安装成功；请在 Termux 里查看上面的输出"
fi

# pig-relay carries the plan extension as Go sources and builds them with the
# system Go toolchain. Without Go the app works, only /plan stays missing, and
# the relay retries on every start.
step 5 "安装 Go"
if ! command -v go >/dev/null 2>&1; then
    say "installing Go (needed to build the bundled plan extension)"
    if ! pkg install -y golang; then
        if [ "$MIRRORED" = 1 ] && [ -f "$SOURCES.bak-pig" ]; then
            say "install failed; restoring the official repo and retrying once"
            cp "$SOURCES.bak-pig" "$SOURCES"
            apt-get update -y >/dev/null 2>&1 || true
            TERMUX_PKG_NO_MIRROR_SELECT=1 pkg install -y golang ||
                say "Go install failed; run 'pkg install golang' to enable /plan"
        else
            pkg install -y golang || say "Go install failed; run 'pkg install golang' to enable /plan"
        fi
    fi
else
    say "Go already installed: $(command -v go)"
fi

# Install the relay of the requested release, or replace one of another
# version. `--version` prints "<protocol> <version>", so an older relay (which
# printed only the protocol) counts as a mismatch.
step 6 "安装 pig-relay"
RELAY="$PREFIX/bin/pig-relay"
relay_version() {
    [ -x "$RELAY" ] || return 0
    "$RELAY" --version 2>/dev/null | awk 'NR == 1 { print $2 }'
}
# version_gt A B: true when A is a newer dotted version than B. A pre-1.1.27
# relay prints only `relay/1`, which yields an empty version; a local `dev-...`
# build parses as 0, so a tagged release can replace it.
version_gt() {
    awk -v a="$1" -v b="$2" 'BEGIN {
        na = split(a, x, "."); nb = split(b, y, ".");
        n = na > nb ? na : nb;
        for (i = 1; i <= n; i++) {
            ai = (i <= na ? x[i] + 0 : 0);
            bi = (i <= nb ? y[i] + 0 : 0);
            if (ai > bi) exit 0;
            if (ai < bi) exit 1;
        }
        exit 1;
    }'
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
elif [ -n "$version" ] && [ -n "$have" ] && version_gt "$have" "$version"; then
    # Reinstalling an older app must not downgrade a relay that was already
    # updated: this script is pinned to the app's own tag.
    say "pig-relay $have is newer than this app's $version; keeping it"
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
    if [ "$ok" = 0 ] && fetch_relay_api "$tmp"; then
        ok=1
    fi
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

if [ ! -x "$RELAY" ]; then
    fail_install "pig-relay 没有安装成功；下载可能失败了"
fi
have=$(relay_version)
if [ -n "$version" ] && [ "$have" != "$version" ] && ! version_gt "$have" "$version"; then
    fail_install "pig-relay 版本不对（当前 ${have:-unknown}，需要 $version）"
fi

step 7 "准备 UI 目录"
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

step 8 "编译并检查扩展"
say "preparing the embedded extensions"
if command -v timeout >/dev/null 2>&1; then
    timeout 300 "$RELAY" --prepare || fail_install "relay 准备内置扩展失败"
else
    "$RELAY" --prepare || fail_install "relay 准备内置扩展失败"
fi

# `success:true` alone is not enough: a fresh pig replies with the built-ins
# even when /plan and the pigkit commands are missing. Check the paths that
# matter, then probe again after a repair attempt.
probe_ok() {
    case "$probe" in *'"success":true'*) ;; *) return 1 ;; esac
    case "$probe" in *'/plan"'*) ;; *) return 1 ;; esac
    case "$probe" in *'provider-ui"'*) ;; *) return 1 ;; esac
    case "$probe" in *'custom-endpoints"'*) ;; *) return 1 ;; esac
    return 0
}

say "checking that pig loads its extensions"
probe=$(probe_extensions)
if probe_ok; then
    say "extensions load"
else
    printf '!! pig is missing the bundled extensions. Until this is\n'
    printf '!! fixed the app has no /provider-ui, /endpoints or /plan.\n'
    printf '%s\n' "$probe" | tail -6
    say "rebuilding them: pig update --extensions"
    pig update --extensions --no-approve >/dev/null 2>&1 ||
        say "pig update --extensions failed; probing once more anyway"
    probe=$(probe_extensions)
    if probe_ok; then
        say "extensions load after pig update --extensions"
    else
        printf '%s\n' "$probe" | tail -8
        fail_install "扩展没有编译成功，App 会缺少 /plan、/provider-ui 等功能"
    fi
fi

step 9 "校验安装结果"
pig_status=$(pig status 2>&1 | head -1)
case "$pig_status" in
    *healthy*) say "$pig_status" ;;
    *) say "warning: $pig_status" ;;
esac
status 9 done "安装完成"
STATUS_WRITTEN=1

printf '\npig:   %s\nrelay: %s (%s)\n' \
    "$(command -v pig || echo "$PREFIX/bin/pig")" "$RELAY" "$(relay_version)"
printf 'Open Termux once so allow-external-apps takes effect, then return to Pig and press retry.\n'
