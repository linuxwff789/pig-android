#!/data/data/com.termux/files/usr/bin/sh
# Install the Pig backend into Termux: pig + pig-relay + UI pack dirs.
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
RELAY_URL="${PIG_RELAY_URL:-https://github.com/linuxwff789/pig-android/releases/latest/download/pig-relay-android-arm64}"

say() { printf '==> %s\n' "$*"; }

if ! command -v pig >/dev/null 2>&1; then
    say "installing pig (official install.sh)"
    curl -fsSL https://pi-in-go.dev/install.sh | sh
else
    say "pig already installed: $(command -v pig)"
fi

if ! command -v pig-relay >/dev/null 2>&1; then
    say "installing pig-relay"
    curl -fsSL "$RELAY_URL" -o "$PREFIX/bin/pig-relay"
    chmod 755 "$PREFIX/bin/pig-relay"
else
    say "pig-relay already installed: $(command -v pig-relay)"
fi

mkdir -p "$HOME/.pig/android-ui/theme" "$HOME/.pig/android-ui/layout" "$HOME/.pig/android-ui/windows"

PROPS="$HOME/.termux/termux.properties"
if [ -f "$PROPS" ] && grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$PROPS"; then
    say "allow-external-apps is enabled"
else
    printf '%s\n' "!! Enable allow-external-apps = true in Termux, then open Termux once"
fi

printf '\npig:   %s\nrelay: %s\n' "$(command -v pig)" "$(command -v pig-relay)"
printf 'Done. Return to the Pig app and press retry.\n'
