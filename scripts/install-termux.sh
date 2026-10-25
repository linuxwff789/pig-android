#!/data/data/com.termux/files/usr/bin/sh
# Install the Pig backend into Termux: pig + pig-relay + UI pack dirs.
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
RELAY_URL="${PIG_RELAY_URL:-https://github.com/linuxwff789/pig-android/releases/latest/download/pig-relay-android-arm64}"

say() { printf '==> %s\n' "$*"; }

if ! command -v curl >/dev/null 2>&1; then
    say "curl is missing"
    printf '%s\n' "Run: pkg install -y curl"
    exit 1
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
# system Go toolchain on first start. Without Go the app works, only /plan stays
# missing, and the relay retries on every start.
if ! command -v go >/dev/null 2>&1; then
    say "installing Go (needed to build the bundled plan extension)"
    pkg install -y golang || say "Go install failed; run 'pkg install golang' to enable /plan"
else
    say "Go already installed: $(command -v go)"
fi

if ! command -v pig-relay >/dev/null 2>&1; then
    say "installing pig-relay"
    mkdir -p "$PREFIX/bin"
    curl -fsSL "$RELAY_URL" -o "$PREFIX/bin/pig-relay"
    chmod 755 "$PREFIX/bin/pig-relay"
else
    say "pig-relay already installed: $(command -v pig-relay)"
fi

mkdir -p "$HOME/.pig/android-ui/theme" "$HOME/.pig/android-ui/layout" "$HOME/.pig/android-ui/windows"

printf '\npig:   %s\nrelay: %s\n' "$(command -v pig)" "$(command -v pig-relay)"
printf 'Open Termux once so allow-external-apps takes effect, then return to Pig and press retry.\n'
