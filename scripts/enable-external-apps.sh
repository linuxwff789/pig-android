#!/data/data/com.termux/files/usr/bin/sh
# Turn on Termux's external-command switch, the one thing that blocks
# RUN_COMMAND: with `allow-external-apps=false` Termux refuses every command a
# client app sends *before* running it, so an app cannot bootstrap itself.
#
# This is the short one-liner the Pig app asks the user to paste on a fresh
# Termux; after it the app's own install button works (and shows progress).
#
#   curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/enable-external-apps.sh | sh
#
# Idempotent: it only edits the line, keeps a one-time backup, and reloads the
# settings so the switch takes effect without restarting Termux.
set -eu

PROPS="${TERMUX_PROPERTIES:-$HOME/.termux/termux.properties}"

say() { printf '==> %s\n' "$*"; }

mkdir -p "$(dirname "$PROPS")"
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

# Termux reads the property through this helper; without it a restart is needed.
if command -v termux-reload-settings >/dev/null 2>&1; then
    termux-reload-settings || say "termux-reload-settings failed; restart Termux once"
else
    say "termux-reload-settings not found; restart Termux once"
fi

say "done - go back to Pig and press 一键安装 (it shows progress now)"
