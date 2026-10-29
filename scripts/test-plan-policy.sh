#!/bin/sh
# extensions/plan imports the PiG extension SDK, which is not vendored here, so
# `go test ./extensions/plan` cannot resolve go.sum in CI. When the SDK is in
# pig's local cache run the full package against it; otherwise run the pure
# policy/steps tests in a throwaway module (no network).
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=${TMPDIR:-/tmp}/pig-plan-policy.$$
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp"

sdk=""
for candidate in "$HOME"/.pig/state/pigsdk/sdk-*; do
    [ -f "$candidate/go.mod" ] && sdk=$candidate
done
if [ -n "$sdk" ]; then
    cp "$root"/extensions/plan/*.go "$root/extensions/plan/go.mod" "$tmp"/
    {
        printf '\nreplace github.com/MichaelKinsy/PiG/extensions/sdk => %s\n' "$sdk"
    } >> "$tmp/go.mod"
    (cd "$tmp" && GOFLAGS=-mod=mod go test ./...)
    exit 0
fi

cp "$root/extensions/plan/policy.go" \
   "$root/extensions/plan/steps.go" \
   "$root/extensions/plan/policy_test.go" \
   "$tmp"/
cat > "$tmp/go.mod" <<'MOD'
module plantest

go 1.21
MOD
(cd "$tmp" && go test ./...)
