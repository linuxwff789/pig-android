#!/bin/sh
# `windows/providers.json` is *runtime output*: provider-ui renders it from the
# user's ~/.pig/android-ui and custom-endpoints.json, so it lists their provider
# ids and base URLs. Copying a live ~/.pig/android-ui into examples/ therefore
# ships somebody's providers - and examples/ is copied into the APK assets, so
# they end up in every release (this happened once).
#
# The other example windows (deploy, form, panel, image, showcase) are hand
# written demos of the node types and stay. This guard only rejects the provider
# window and files that look like they were generated from real data.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
status=0

flag() {
    echo "!! $1" >&2
    [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/   /' >&2
    status=1
}

for base in "$root/examples" "$root/app/src/main/assets"; do
    [ -d "$base" ] || continue

    # 1. provider-ui owns this window; a committed copy is a stale snapshot.
    found=$(find "$base" -type f -path '*/windows/providers.json' 2>/dev/null || true)
    [ -n "$found" ] && flag "generated provider window committed (provider-ui writes it at runtime)" "$found"

    # 2. A renamed copy still carries provider-ui's actions or its heading.
    hits=$(grep -rIlE 'ext:providerui\.|个自定义供应商' "$base" 2>/dev/null || true)
    [ -n "$hits" ] && flag "pack file contains a rendered provider list" "$hits"
done

if [ "$status" -ne 0 ]; then
    echo "" >&2
    echo "Delete those files: a window is rendered by the extension that owns it," >&2
    echo "never copied into a pack." >&2
    exit 1
fi

echo "ui packs: no generated provider windows committed"
