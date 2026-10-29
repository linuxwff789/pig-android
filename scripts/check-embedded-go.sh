#!/bin/sh
# relay/embedded/*.go.txt is not compiled by the relay module, so a syntax
# error in a shipped extension reaches the phone and only fails when pig tries
# to build it. gofmt -e parses each file (no SDK needed).
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=${TMPDIR:-/tmp}/pig-embedded-go.$$
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp"
status=0
files=$(find "$root/relay/embedded" -name '*.go.txt' | sort)
for file in $files; do
    rel=${file#"$root/"}
    dest="$tmp/$(printf '%s' "$rel" | tr '/' '_')"
    dest=${dest%.txt}
    cp "$file" "$dest"
    if ! gofmt -e "$dest" >/dev/null 2>"$tmp/error"; then
        echo "$rel: $(cat "$tmp/error")" >&2
        status=1
    fi
done
if [ "$status" -ne 0 ]; then
    echo "embedded extension sources have syntax errors" >&2
fi
exit "$status"
