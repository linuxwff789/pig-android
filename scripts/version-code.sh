#!/bin/sh
# Android versionCode from a four-part tag. `git rev-list --count HEAD` goes
# backwards on a hotfix branch and collides when one commit carries two tags;
# this scheme is monotonic per release line:
#   v1.1.70.3 -> 101070003
#   v1.1.63   -> 101063000
#   v1.2.0.1  -> 102000001
# Missing segments count as 0. Major is capped at 20 to stay below Android's
# 2100000000 ceiling.
set -eu
raw=${1#v}
major=$(printf '%s' "$raw" | cut -d. -f1)
minor=$(printf '%s' "$raw" | cut -d. -f2)
patch=$(printf '%s' "$raw" | cut -d. -f3)
build=$(printf '%s' "$raw" | cut -d. -f4)
[ -n "$major" ] || major=0
[ -n "$minor" ] || minor=0
[ -n "$patch" ] || patch=0
[ -n "$build" ] || build=0
for value in "$major" "$minor" "$patch" "$build"; do
    case "$value" in ''|*[!0-9]*) echo "version-code: not a numeric version: $1" >&2; exit 2;; esac
done
if [ "$major" -gt 20 ] || [ "$minor" -gt 99 ] || [ "$patch" -gt 999 ] || [ "$build" -gt 999 ]; then
    echo "version-code: segment out of range: $1" >&2
    exit 2
fi
printf '%s\n' $((major * 100000000 + minor * 1000000 + patch * 1000 + build))
