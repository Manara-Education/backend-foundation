#!/usr/bin/env bash
# Vendors the Cloudflare security-audit agent skill at a pinned commit.
#
# The skill lives in .claude/skills/security-audit/ so Claude Code picks it up
# automatically. It is copied, not referenced, so an audit never depends on what
# upstream `main` happens to be today — but a copy can drift, so the copy is
# checked: CI runs this script with --dest into a scratch directory and diffs
# the result against the committed tree. Bumping the pin and verifying it are
# the same code path, which is the point.
#
# Usage:
#   .github/skills/sync-security-audit.sh <sha>       bump: vendor <sha>, rewrite the lock
#   .github/skills/sync-security-audit.sh             re-vendor the locked sha in place
#   .github/skills/sync-security-audit.sh --dest DIR  fetch the locked sha into DIR (CI)
#
# Requires git. Fetches only the one commit it was asked for.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK="$ROOT/.github/skills/security-audit.lock"
DEST="$ROOT/.claude/skills/security-audit"
NEW_SHA=""

while [ $# -gt 0 ]; do
  case "$1" in
    --dest) DEST="$2"; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) NEW_SHA="$1"; shift ;;
  esac
done

# shellcheck source=/dev/null
. "$LOCK"
SHA="${NEW_SHA:-$UPSTREAM_SHA}"

# A full 40-character SHA, never a branch or tag: both can be moved upstream.
if ! printf '%s' "$SHA" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "error: '$SHA' is not a full commit SHA" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git -C "$WORK" init -q
git -C "$WORK" fetch -q --depth 1 "$UPSTREAM_REPO" "$SHA"
git -C "$WORK" -c advice.detachedHead=false checkout -q FETCH_HEAD

if [ ! -f "$WORK/$UPSTREAM_PATH/SKILL.md" ]; then
  echo "error: $UPSTREAM_PATH/SKILL.md not found at $SHA" >&2
  exit 1
fi

rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$WORK/$UPSTREAM_PATH/." "$DEST/"
cp "$WORK/LICENSE" "$DEST/LICENSE"

if [ -n "$NEW_SHA" ]; then
  sed -i.bak "s/^UPSTREAM_SHA=.*/UPSTREAM_SHA=$SHA/" "$LOCK" && rm -f "$LOCK.bak"
fi

echo "security-audit skill @ $SHA -> $DEST"
