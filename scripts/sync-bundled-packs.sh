#!/bin/sh
# Copy the packs that ship inside the APK from examples/ into app assets.
# Run this after editing examples/ui-pack-* and before building a release.
set -e
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
for pack in ui-pack-compact; do
    src="$root/examples/$pack"
    name=${pack#ui-pack-}
    dst="$root/app/src/main/assets/ui-packs/$name"
    [ -d "$src" ] || { echo "missing $src" >&2; exit 1; }
    rm -rf "$dst"
    mkdir -p "$(dirname "$dst")"
    cp -r "$src" "$dst"
    echo "bundled: $pack -> assets/ui-packs/$name"
done
