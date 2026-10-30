#!/bin/sh
# Coverage including the subprocess tests for main(): `go test -coverprofile`
# only sees the parent test process, so the CLI/serve branches always showed
# 0%. Build the test binary with coverage, let children write counters into the
# same GOCOVERDIR, then merge.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=${1:-$root/relay/coverage.out}
tmp=${TMPDIR:-/tmp}/pig-relay-cover.$$
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/cover"
(
    cd "$root/relay"
    go test -c -cover -covermode=atomic -o "$tmp/relay.test" .
)
PIG_GOCOVERDIR="$tmp/cover" "$tmp/relay.test" \
    -test.timeout=300s -test.gocoverdir="$tmp/cover" >/dev/null
go tool covdata textfmt -i="$tmp/cover" -o="$out"
(cd "$root/relay" && go tool cover -func="$out") | tail -1
