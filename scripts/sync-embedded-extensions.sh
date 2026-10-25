#!/bin/sh
# Copy the extensions the relay ships into relay/embedded/, where the relay
# binary embeds them and extracts them on first start (no network, no extra
# download). Go sources get a .txt suffix so the relay module does not compile
# them: they belong to pig, not to the relay.
# Run this after editing extensions/plan and commit the result.
set -e
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
src="$root/extensions/plan"
dst="$root/relay/embedded/plan"

[ -d "$src" ] || { echo "missing $src" >&2; exit 1; }
rm -rf "$dst"
mkdir -p "$dst"
for name in commands.go events.go extension.go policy.go steps.go go.mod; do
    [ -f "$src/$name" ] || { echo "missing $src/$name" >&2; exit 1; }
    cp "$src/$name" "$dst/$name.txt"
done
[ -f "$src/README.md" ] && cp "$src/README.md" "$dst/README.md"
echo "embedded: extensions/plan -> relay/embedded/plan"
