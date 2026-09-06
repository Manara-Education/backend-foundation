#!/usr/bin/env bash
#
# Decides whether a release tag may be promoted — built into an image, or
# deployed to production.
#
# The promotion workflows used to answer that question in three different places
# and to varying depths, and a manual run skipped it altogether: publish.yml and
# deploy-production.yml both began with
#
#     if: github.event_name == 'workflow_dispatch' || <the real check>
#
# so anyone able to dispatch a workflow could name any semver tag, wherever it
# pointed, and have it built and shipped. Tag syntax was validated; nothing else
# was. This script is the one place that answers instead, and both entry points
# — automatic and manual — go through it.
#
# What it establishes, in order, failing closed at the first thing it cannot
# prove:
#
#   1. The tag is exactly vMAJOR.MINOR.PATCH.
#   2. The tag exists as a TAG, and resolves to a commit. Looked up under
#      refs/tags/ specifically, and dereferenced if it is an annotated tag
#      object. `git rev-parse "$TAG"` would also happily resolve a *branch* of
#      the same name, and would hand back the tag object's own SHA rather than
#      the commit it points at.
#   3. That commit is an ancestor of origin/main. Releases come from main.
#   4. Optionally: a published, non-draft GitHub Release exists for the tag, and
#      still points at the same commit. A tag that has been moved since the
#      release was cut fails here.
#   5. Optionally: every named check has actually succeeded on that exact
#      commit, produced by GitHub Actions itself. Missing, queued, cancelled,
#      skipped, neutral and failed are all refusals — only `success` passes.
#
# Usage:
#   validate-promotion.sh --tag v1.4.0 [--require-release] [--require-checks]
#                         [--check "Build and test"] [--check "Build the image"]
#                         [--timeout-seconds 900]
#
# --check is repeated once per required check rather than taking one delimited
# list. A check run's name is free text chosen by whoever wrote the workflow, and
# this repository's own CI job is called "Install, type-check and build" — a
# comma-separated list would have split that in half and then refused every
# release for want of a check named "Install".
#
# Writes `sha=<commit>` to $GITHUB_OUTPUT when it passes.

set -euo pipefail

TAG=""
REQUIRE_RELEASE=false
REQUIRE_CHECKS=false
REQUIRED_CHECKS=()
TIMEOUT_SECONDS="${PROMOTION_CHECK_TIMEOUT:-900}"
POLL_SECONDS="${PROMOTION_POLL_SECONDS:-15}"

# Every call to the GitHub CLI goes through this, so the tests can substitute a
# stub that replays fixture JSON instead of reaching the network.
GH="${PROMOTION_GH:-gh}"
GIT="${PROMOTION_GIT:-git}"

die() { echo "::error::$*" >&2; exit 1; }
note() { echo "$*"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)             TAG="${2:-}"; shift 2 ;;
        --require-release) REQUIRE_RELEASE=true; shift ;;
        --require-checks)  REQUIRE_CHECKS=true; shift ;;
        --check)           REQUIRED_CHECKS+=("${2:-}"); shift 2 ;;
        --timeout-seconds) TIMEOUT_SECONDS="${2:-}"; shift 2 ;;
        *) die "Unknown argument '$1'." ;;
    esac
done

# ---------------------------------------------------------------- 1. tag syntax

[[ -n "$TAG" ]] || die "--tag is required."

if ! printf '%s' "$TAG" | grep -Eq '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'; then
    die "'$TAG' is not vMAJOR.MINOR.PATCH."
fi

# ------------------------------------------------------- 2. tag -> commit SHA

# ^{commit} peels an annotated tag object through to the commit it points at,
# and is a no-op for a lightweight tag. Both kinds exist across Manara's
# repositories, and not in a tidy pattern — some releases were cut with
# `git tag -a` and some with a plain `git tag`, sometimes alternating between
# consecutive versions. Whoever cuts the next one could produce either, so
# neither form can be assumed.
#
# Anchored at refs/tags/ so that a *branch* called v9.9.9 cannot answer this
# question. That is not hypothetical: creating such a branch is exactly how
# someone would try to get unreviewed code past a check that trusted
# `git rev-parse`.
SHA="$("$GIT" rev-parse --verify --quiet "refs/tags/${TAG}^{commit}" || true)"
[[ -n "$SHA" ]] || die "'$TAG' does not exist as a tag in this repository."
note "$TAG resolves to $SHA"

# ------------------------------------------------------------- 3. on main?

"$GIT" fetch origin main --quiet --depth=0 2>/dev/null \
    || "$GIT" fetch origin main --quiet \
    || die "Could not fetch origin/main to check ancestry."

if ! "$GIT" merge-base --is-ancestor "$SHA" origin/main; then
    die "$TAG ($SHA) is not an ancestor of main. Releases are cut from main only."
fi
note "$SHA is on main"

# ------------------------------------------------------- 4. a real release?

if [[ "$REQUIRE_RELEASE" == true ]]; then
    release_json="$("$GH" release view "$TAG" --json tagName,isDraft,targetCommitish 2>/dev/null || true)"
    [[ -n "$release_json" ]] || die "No GitHub Release exists for $TAG. Publish the release first."

    is_draft="$(printf '%s' "$release_json" | jq -r '.isDraft')"
    [[ "$is_draft" == "false" ]] || die "The release for $TAG is a draft."

    # The release is re-checked against the tag's *current* commit rather than
    # trusted from when it was cut, so a tag moved after the fact is caught.
    released_sha="$("$GH" api "repos/{owner}/{repo}/git/refs/tags/${TAG}" \
                        --jq 'if .object.type == "tag" then "deref" else .object.sha end' 2>/dev/null || true)"
    if [[ "$released_sha" == "deref" ]]; then
        released_sha="$("$GH" api "repos/{owner}/{repo}/git/tags/$(
            "$GH" api "repos/{owner}/{repo}/git/refs/tags/${TAG}" --jq '.object.sha')" \
            --jq '.object.sha' 2>/dev/null || true)"
    fi
    [[ -n "$released_sha" ]] || die "Could not resolve $TAG through the API."
    [[ "$released_sha" == "$SHA" ]] \
        || die "$TAG points at $released_sha on the remote but $SHA locally. The tag has moved."
    note "release $TAG is published and still points at $SHA"
fi

# --------------------------------------------------------- 5. green CI on it?

if [[ "$REQUIRE_CHECKS" == true ]]; then
    (( ${#REQUIRED_CHECKS[@]} > 0 )) || die "--require-checks needs at least one --check."

    deadline=$(( SECONDS + TIMEOUT_SECONDS ))

    while :; do
        runs_json="$("$GH" api "repos/{owner}/{repo}/commits/${SHA}/check-runs?per_page=100" \
                        --jq '.check_runs' 2>/dev/null || echo '[]')"

        pending=0
        for name in "${REQUIRED_CHECKS[@]}"; do
            [[ -n "$name" ]] || die "An empty --check was given."

            # The producer is part of the requirement. A check run can be
            # created by any app with the right permission, so a green tick
            # alone says nothing about who decided it was green.
            entry="$(printf '%s' "$runs_json" \
                     | jq -c --arg n "$name" \
                        '[.[] | select(.name == $n and .app.slug == "github-actions")]
                         | sort_by(.completed_at // "") | last // empty')"

            if [[ -z "$entry" ]]; then
                die "No check run named '$name' from github-actions on $SHA."
            fi

            status="$(printf '%s' "$entry" | jq -r '.status')"
            conclusion="$(printf '%s' "$entry" | jq -r '.conclusion // "none"')"

            if [[ "$status" != "completed" ]]; then
                pending=1
                note "waiting for '$name' ($status) on $SHA ..."
                continue
            fi
            # Only success. skipped and neutral are explicitly refusals: a
            # required check that did not actually run has not established
            # anything about this commit.
            [[ "$conclusion" == "success" ]] \
                || die "Check '$name' on $SHA concluded '$conclusion', not success."
        done

        (( pending )) || break
        (( SECONDS < deadline )) || die "Required checks on $SHA did not complete within ${TIMEOUT_SECONDS}s."
        sleep "$POLL_SECONDS"
    done
    note "all required checks are green on $SHA"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "sha=$SHA" >> "$GITHUB_OUTPUT"
fi
note "$TAG is approved for promotion"
