#!/bin/sh
# Promote a preview build to a normal GitHub release.
#
# Every tag is published as a pre-release (see docs/development.md); this is the
# only way back: the release keeps its version number, and GitHub's `latest`
# immediately points at it. The next preview continues the W counter of a new
# round (1.1.68.1 -> 1.1.69.1), so a promoted number is never reused.
#
# Usage:  PIG_GITHUB_TOKEN=<pat> scripts/promote-release.sh v1.1.68.1
# The token only needs public-repo read access to a repo you own (classic PAT
# with `public_repo` or a fine-grained token with "Contents: write").
set -eu

TAG="${1:-}"
[ -n "$TAG" ] || { echo "usage: $0 vX.Y.Z.W" >&2; exit 2; }
TOKEN="${PIG_GITHUB_TOKEN:-}"
[ -n "$TOKEN" ] || { echo "PIG_GITHUB_TOKEN is not set" >&2; exit 2; }
REPO="linuxwff789/pig-android"

api() {
    curl -fsSL --retry 3 --retry-all-errors \
        -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "$@"
}

echo "==> $TAG: unchecking Pre-release"
api -X PATCH "https://api.github.com/repos/$REPO/releases/tags/$TAG" \
    -d '{"prerelease":false}' >/dev/null

echo "==> latest is now: $(api "https://api.github.com/repos/$REPO/releases/latest" |
    sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1)"
echo "done"
