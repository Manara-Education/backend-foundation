#!/usr/bin/env bash
#
# Decides whether a Dependabot pull request may be merged automatically, and —
# if it may — enables the merge bound to the exact commit it verified.
#
# The gate this replaces asked a much weaker question. It read the check runs on
# the head commit and merged if the set was non-empty and nothing in it was
# still running:
#
#     if [ "$total" -gt 0 ] && [ "$pending" -eq 0 ]; then
#       bad=... select(.conclusion | IN("success","skipped","neutral") | not) ...
#
# Three things follow from that. A check run that never appeared is
# indistinguishable from one that passed, so if "Build and test" failed to start
# the PR merged on the strength of whatever else happened to be there. `skipped`
# and `neutral` counted as passing, so a required job that decided not to run
# established nothing and blocked nothing. And a check run's name is free text
# that any GitHub App with checks:write can produce, so "who says this is green"
# was never asked at all.
#
# The merge was then handed to `gh pr merge --auto` naming only the pull
# request. Auto-merge lands whatever the head is when it fires, which need not
# be the commit whose checks were read — a head pushed after verification
# inherited the earlier head's green ticks.
#
# What this script establishes instead, failing closed at the first thing it
# cannot prove:
#
#   1. The pull request's CURRENT head is the commit this run was triggered for.
#      Re-read from the API before verifying, on every poll, and once more
#      immediately before the merge. A head that moved means this run is stale:
#      it refuses and leaves the decision to the run the new head started.
#   2. Every check named by --check exists on that commit, was produced by the
#      trusted app given to --producer, and concluded `success`. Missing,
#      queued, in progress, failed, cancelled, timed out, action_required,
#      stale, neutral and skipped are all refusals.
#   3. The merge itself names that commit (`--match-head-commit`), so GitHub
#      refuses it server-side if the head moved in the interim, and any
#      auto-merge left enabled by an earlier run against an earlier head is
#      revoked before verification starts.
#
# Usage:
#   verify-dependabot-merge.sh --repo owner/name --pr 42 \
#       --expected-head <40-hex sha> \
#       --check "Build and test" --check "Build the container image" \
#       [--producer github-actions] [--timeout-seconds 1800] [--dry-run]
#
# --check is repeated once per required check rather than taking one delimited
# list. A check run's name is free text chosen by whoever wrote the workflow,
# and the sibling frontend repository's CI job is called
# "Install, type-check and build" — splitting a comma-separated list would turn
# that single name into "Install" plus " type-check and build" and then refuse
# every frontend dependency update for want of a check named "Install".

set -euo pipefail

REPO=""
PR=""
EXPECTED_HEAD=""
PRODUCER="github-actions"
DRY_RUN=false
REQUIRED_CHECKS=()
CHECK_COUNT=0

TIMEOUT_SECONDS="${DEPENDABOT_MERGE_TIMEOUT:-1800}"
POLL_SECONDS="${DEPENDABOT_MERGE_POLL_SECONDS:-30}"

# Every call to the GitHub CLI goes through this, so the tests can substitute a
# stub that replays fixture JSON and records what it was asked to do instead of
# reaching GitHub.
GH="${DEPENDABOT_MERGE_GH:-gh}"

die() { echo "::error::$*" >&2; exit 1; }
note() { echo "$*"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)            REPO="${2:-}"; shift 2 ;;
        --pr)              PR="${2:-}"; shift 2 ;;
        --expected-head)   EXPECTED_HEAD="${2:-}"; shift 2 ;;
        --check)           REQUIRED_CHECKS[CHECK_COUNT]="${2:-}"
                           CHECK_COUNT=$(( CHECK_COUNT + 1 )); shift 2 ;;
        --producer)        PRODUCER="${2:-}"; shift 2 ;;
        --timeout-seconds) TIMEOUT_SECONDS="${2:-}"; shift 2 ;;
        --dry-run)         DRY_RUN=true; shift ;;
        *) die "Unknown argument '$1'." ;;
    esac
done

# ------------------------------------------------------------------ arguments

[[ -n "$REPO" ]] || die "--repo is required."
printf '%s' "$REPO" | grep -Eq '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$' \
    || die "--repo must be owner/name, not '$REPO'."

printf '%s' "$PR" | grep -Eq '^[0-9]+$' \
    || die "--pr must be a pull request number, not '$PR'."

# Insisting on a full 40-hex SHA is not cosmetic. Everything below compares this
# value against what the API reports, and an abbreviated or empty SHA would make
# that comparison quietly meaningless.
printf '%s' "$EXPECTED_HEAD" | grep -Eq '^[0-9a-f]{40}$' \
    || die "--expected-head must be a full 40-character commit SHA, not '$EXPECTED_HEAD'."

(( CHECK_COUNT > 0 )) || die "At least one --check is required; refusing to merge on no evidence."

[[ -n "$PRODUCER" ]] || die "--producer may not be empty."

for i in $(seq 0 $(( CHECK_COUNT - 1 ))); do
    [[ -n "${REQUIRED_CHECKS[$i]}" ]] || die "An empty --check was given."
done

printf '%s' "$TIMEOUT_SECONDS" | grep -Eq '^[0-9]+$' \
    || die "--timeout-seconds must be a whole number of seconds."

# -------------------------------------------------------------------- helpers

current_head() {
    "$GH" api "repos/${REPO}/pulls/${PR}" --jq '.head.sha' 2>/dev/null || true
}

# `--paginate` matters: the check-runs endpoint is paginated, and reading only
# the first page of a large set would let a required check that lives on page
# two read as "not there yet" forever, or — under the old any-non-empty-set
# rule — as "nothing pending". `--jq '.check_runs[]'` emits one object per line
# per page and `jq -s` reassembles the pages into a single array, which works on
# every gh version rather than depending on `--slurp`.
check_runs_for() {
    local sha="$1"
    "$GH" api --paginate "repos/${REPO}/commits/${sha}/check-runs?per_page=100" \
        --jq '.check_runs[]' 2>/dev/null | jq -s '.' || echo '[]'
}

# The newest attempt for a name, decided by check-run id. Ids increase, and
# every check run has one; `completed_at` is null while a run is in progress, so
# sorting on it would rank a re-run that is still going BELOW the earlier
# attempt it supersedes and let a stale green tick answer for it.
latest_run_for() {
    local runs="$1" name="$2"
    printf '%s' "$runs" | jq -c --arg n "$name" --arg a "$PRODUCER" \
        '[.[] | select(.name == $n and .app.slug == $a)] | sort_by(.id) | last // empty'
}

require_head_unchanged() {
    local where="$1" head_now
    head_now="$(current_head)"
    [[ -n "$head_now" ]] || die "Could not read the current head of $REPO#$PR."
    [[ "$head_now" == "$EXPECTED_HEAD" ]] || die \
        "$REPO#$PR now points at $head_now, not the $EXPECTED_HEAD this run verified ($where). Refusing to merge; the run triggered by the new head decides for it."
}

# --------------------------------------------- 0. revoke any stale auto-merge
#
# An earlier run may have enabled auto-merge against an earlier head. That
# enablement is bound to the commit it named, but it is not this run's evidence,
# and leaving it in place would let a decision made about a commit nobody is
# looking at any more resolve itself while this run is still deciding. Clearing
# it first means every merge on this pull request traces back to a verification
# that finished. Nothing to clear is the normal case, and not an error.

if [[ "$DRY_RUN" != true ]]; then
    if "$GH" pr merge --disable-auto --repo "$REPO" "$PR" >/dev/null 2>&1; then
        note "cleared an auto-merge left enabled on $REPO#$PR by an earlier run"
    fi
fi

# ------------------------------------------------- 1 & 2. green, named, trusted

note "verifying $REPO#$PR at $EXPECTED_HEAD"
deadline=$(( SECONDS + TIMEOUT_SECONDS ))

while :; do
    require_head_unchanged "before reading check runs"

    runs_json="$(check_runs_for "$EXPECTED_HEAD")"

    unfinished=0
    unfinished_names=""

    for i in $(seq 0 $(( CHECK_COUNT - 1 ))); do
        name="${REQUIRED_CHECKS[$i]}"
        entry="$(latest_run_for "$runs_json" "$name")"

        # Absent is not the same as pending, but it is treated the same way
        # while there is still time: CI may not have created the check run yet.
        # Once the deadline passes, absent is a refusal like any other, which is
        # the whole point — the old gate could not tell "never ran" from "passed".
        if [[ -z "$entry" ]]; then
            unfinished=$(( unfinished + 1 ))
            unfinished_names="${unfinished_names}
  absent: '${name}' (no run from '${PRODUCER}')"
            continue
        fi

        status="$(printf '%s' "$entry" | jq -r '.status')"
        conclusion="$(printf '%s' "$entry" | jq -r '.conclusion // "none"')"

        if [[ "$status" != "completed" ]]; then
            unfinished=$(( unfinished + 1 ))
            unfinished_names="${unfinished_names}
  running: '${name}' ($status)"
            continue
        fi

        # Only `success`. `skipped` and `neutral` are refusals rather than
        # passes: a required check that declined to run has established nothing
        # about this commit, and a dependency bump is exactly the situation in
        # which a path filter or an early exit could make it decline.
        [[ "$conclusion" == "success" ]] \
            || die "Check '$name' on $EXPECTED_HEAD concluded '$conclusion', not success. Refusing to merge."
    done

    if (( unfinished == 0 )); then
        break
    fi

    if (( SECONDS >= deadline )); then
        die "Required checks on $EXPECTED_HEAD did not all succeed within ${TIMEOUT_SECONDS}s:${unfinished_names}"
    fi

    note "waiting on ${unfinished} required check(s) on $EXPECTED_HEAD:${unfinished_names}"
    sleep "$POLL_SECONDS"
done

for i in $(seq 0 $(( CHECK_COUNT - 1 ))); do
    note "  ok  '${REQUIRED_CHECKS[$i]}' succeeded on $EXPECTED_HEAD (produced by $PRODUCER)"
done

# ------------------------------------------------------- 3. bind and merge

# Read the head once more, as late as possible, so the gap between the evidence
# and the decision is as small as it can be made from here. The gap cannot be
# closed entirely in a client, which is why the merge below also names the SHA
# and lets GitHub refuse it server-side.
require_head_unchanged "immediately before merging"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "verified-sha=$EXPECTED_HEAD" >> "$GITHUB_OUTPUT"
fi

if [[ "$DRY_RUN" == true ]]; then
    note "--dry-run: $EXPECTED_HEAD is merge-eligible; not merging."
    exit 0
fi

# `--match-head-commit` is the binding. It travels to GitHub as the expected
# head OID, so if the head moved between the read above and this call the server
# rejects the request rather than merging a commit nothing verified.
#
# `--auto` is kept: it hands the merge to GitHub's own auto-merge, which
# respects — and cannot bypass — required reviews, required status checks,
# linear history and merge queues. This script is an extra gate in front of
# those, never a way around them.
"$GH" pr merge --auto --squash --match-head-commit "$EXPECTED_HEAD" --repo "$REPO" "$PR"
note "auto-merge enabled on $REPO#$PR, bound to $EXPECTED_HEAD"
