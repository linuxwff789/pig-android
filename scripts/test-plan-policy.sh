#!/bin/sh
# extensions/plan imports the PiG extension SDK, which is not vendored here, so
# `go test ./extensions/plan` cannot resolve go.sum in CI. The policy and step
# extraction are pure Go; run them in a throwaway module instead of letting the
# safety test rot as dead code.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=${TMPDIR:-/tmp}/pig-plan-policy.$$
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp"
cp "$root/extensions/plan/policy.go" \
   "$root/extensions/plan/steps.go" \
   "$root/extensions/plan/policy_test.go" \
   "$tmp"/
cat > "$tmp/go.mod" <<'MOD'
module plantest

go 1.21
MOD
(cd "$tmp" && go test ./...)
