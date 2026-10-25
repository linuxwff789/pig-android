#!/bin/sh
# Copy the extensions the relay ships into relay/embedded/, where the relay
# binary embeds them and extracts them on first start (no network, no extra
# download). Go sources get a .txt suffix so the relay module does not compile
# them: they belong to pig, not to the relay.
#
#   extensions/plan     -> relay/embedded/plan/      (one extension)
#   extensions/pigkit   -> relay/embedded/pigkit/    (the pigkit module: the
#                          provider window the app shows, custom endpoints,
#                          model speed, memory, ...)
#   examples/ui-pack-compact
#                       -> relay/embedded/ui/        (default UI pack; the relay
#                          fills a fresh ~/.pig/android-ui with it, no windows/
#                          because those are rendered at runtime)
#
# Run this after editing either tree and commit the result.
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

src="$root/extensions/pigkit"
dst="$root/relay/embedded/pigkit"

[ -d "$src" ] || { echo "missing $src" >&2; exit 1; }
rm -rf "$dst"
mkdir -p "$dst"
# Every Go source and the module file; the directory layout (one package per
# subdirectory) has to survive, pig builds each package against the module root.
(
    cd "$src" &&
    find . -type f \( -name '*.go' -o -name 'go.mod' \) | sed 's|^\./||' | sort
) | while IFS= read -r rel; do
    mkdir -p "$dst/$(dirname "$rel")"
    cp "$src/$rel" "$dst/$rel.txt"
done
echo "embedded: extensions/pigkit -> relay/embedded/pigkit"

# The pack the app ships as its default. `windows/` is excluded: the relay only
# seeds files that are missing, and a window is rendered by whichever extension
# owns it (provider-ui writes windows/providers.json from the live config).
src="$root/examples/ui-pack-compact"
dst="$root/relay/embedded/ui"

[ -d "$src" ] || { echo "missing $src" >&2; exit 1; }
rm -rf "$dst"
mkdir -p "$dst"
(
    cd "$src" &&
    find . -type f ! -path './windows/*' | sed 's|^\./||' | sort
) | while IFS= read -r rel; do
    mkdir -p "$dst/$(dirname "$rel")"
    cp "$src/$rel" "$dst/$rel"
done
echo "embedded: examples/ui-pack-compact -> relay/embedded/ui"
