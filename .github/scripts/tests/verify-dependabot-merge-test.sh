#!/usr/bin/env bash
#
# Tests for verify-dependabot-merge.sh and for the workflow that calls it.
#
# Everything here is local. `gh` is replaced by a stub that replays fixture JSON
# and records every invocation, so each case declares the GitHub state it is
# testing against and the assertions can then ask what the script actually tried
# to do. No network, no GitHub, no Dependabot pull request, no workflow run.
#
# The second half parses .github/workflows/dependabot-auto-merge.yml as YAML and
# asserts the structural properties that the shell gate cannot enforce on its
# own: least-privilege permissions, and that no privileged job puts pull-request
# code on disk. Those are parsed rather than grepped, because a grep for
# a checkout of the pull request head under pull_request_target would.
#
#   .github/scripts/tests/verify-dependabot-merge-test.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERIFIER="$SCRIPT_DIR/verify-dependabot-merge.sh"
WORKFLOW="$(cd "$SCRIPT_DIR/../workflows" && pwd)/dependabot-auto-merge.yml"

PASS=0
FAIL=0
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

REPO="Manara-Education/example-repo"
PR_NUMBER=42
HEAD_A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
HEAD_B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

export STUB_LOG="$WORK/gh-calls.log"
export STUB_COUNTER="$WORK/head-calls"
export DEFAULT_HEAD="$HEAD_A"

# ------------------------------------------------------------------ fixtures

# mkcheck <name> <status> <conclusion|""> <app-slug> <id>
mkcheck() {
    jq -cn --arg n "$1" --arg s "$2" --arg c "$3" --arg a "$4" --argjson i "$5" \
        '{id: $i, name: $n, status: $s,
          conclusion: (if $c == "" then null else $c end),
          app: {slug: $a}}'
}

# mkset <check-json>... -> a JSON array
mkset() {
    if [[ $# -eq 0 ]]; then echo '[]'; return; fi
    printf '%s\n' "$@" | jq -s -c '.'
}

BE_GREEN="$(mkset \
    "$(mkcheck 'Build and test' completed success github-actions 101)" \
    "$(mkcheck 'Build the container image' completed success github-actions 102)")"

# Stub `gh`. Records the full argument list of every call, then answers from the
# environment the case set up.
write_gh_stub() {
    cat > "$WORK/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$STUB_LOG"

if [[ "$1" == "pr" && "$2" == "merge" ]]; then
    if [[ " $* " == *" --disable-auto "* ]]; then
        # Non-zero is the ordinary case: there was no auto-merge to revoke.
        exit "${STUB_DISABLE_AUTO_RC:-1}"
    fi
    exit "${STUB_MERGE_RC:-0}"
fi

if [[ "$1" == "api" ]]; then
    endpoint=""
    for arg in "$@"; do
        case "$arg" in repos/*) endpoint="$arg" ;; esac
    done
    case "$endpoint" in
        */pulls/*)
            n=$(( $(cat "$STUB_COUNTER" 2>/dev/null || echo 0) + 1 ))
            printf '%s' "$n" > "$STUB_COUNTER"
            if [[ -n "${STUB_HEAD_SWITCH_AFTER:-}" ]] && (( n > STUB_HEAD_SWITCH_AFTER )); then
                printf '%s\n' "${STUB_HEAD_LATE:?}"
            else
                printf '%s\n' "${STUB_HEAD:-$DEFAULT_HEAD}"
            fi
            exit 0 ;;
        */check-runs*)
            # The real call is `gh api --paginate ... --jq '.check_runs[]'`,
            # which emits one object per line; the script slurps them back into
            # an array. The stub speaks the same shape.
            printf '%s' "${STUB_CHECKS:-[]}" | jq -c '.[]'
            exit 0 ;;
    esac
fi
exit 0
STUB
    chmod +x "$WORK/gh"
}

# ------------------------------------------------------------------- runner

LAST_OUT=""
LAST_RC=0

ok()   { PASS=$((PASS + 1)); printf '  ok    %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n        %s\n' "$1" "$2"; }

# expect <name> <pass|fail> <expected-substring> -- <verifier args...>
expect() {
    local name="$1" want="$2" needle="$3"; shift 4
    : > "$STUB_LOG"
    : > "$STUB_COUNTER"

    LAST_OUT="$(DEPENDABOT_MERGE_GH="$WORK/gh" DEPENDABOT_MERGE_POLL_SECONDS=0 \
                GITHUB_OUTPUT="$WORK/github_output" \
                bash "$VERIFIER" "$@" 2>&1)"
    LAST_RC=$?

    local why=""
    if [[ "$want" == pass && $LAST_RC -ne 0 ]]; then why="expected success, got rc=$LAST_RC"; fi
    if [[ "$want" == fail && $LAST_RC -eq 0 ]]; then why="expected a refusal, got rc=0"; fi
    if [[ -z "$why" && -n "$needle" ]] \
        && ! printf '%s' "$LAST_OUT" | grep -qF "$needle"; then
        why="output did not contain \"$needle\""
    fi

    if [[ -z "$why" ]]; then
        ok "$name"
    else
        bad "$name" "$why; got: $(printf '%s' "$LAST_OUT" | tail -2 | tr '\n' ' ')"
    fi
}

# The merge must have been attempted, and bound to exactly this SHA.
assert_merged_bound_to() {
    local name="$1" sha="$2"
    if grep -q -- "--match-head-commit $sha" "$STUB_LOG"; then
        ok "$name"
    else
        bad "$name" "no merge bound to $sha was attempted; gh calls were: $(tr '\n' '|' < "$STUB_LOG")"
    fi
}

# Nothing may have been merged. `--disable-auto` is not a merge.
assert_not_merged() {
    local name="$1"
    if grep -q -- "--match-head-commit" "$STUB_LOG"; then
        bad "$name" "a merge was attempted: $(grep -- '--match-head-commit' "$STUB_LOG")"
    else
        ok "$name"
    fi
}

assert_logged() {
    local name="$1" needle="$2"
    if grep -qF -- "$needle" "$STUB_LOG"; then
        ok "$name"
    else
        bad "$name" "no gh call contained \"$needle\"; calls were: $(tr '\n' '|' < "$STUB_LOG")"
    fi
}

write_gh_stub
BASE_ARGS=(--repo "$REPO" --pr "$PR_NUMBER" --expected-head "$HEAD_A" --timeout-seconds 0)

echo "verify-dependabot-merge.sh"

# ------------------------------------------------------------ the happy path
STUB_CHECKS="$BE_GREEN" \
  expect "every required check green on the current head" pass "auto-merge enabled" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --check "Build the container image"
assert_merged_bound_to "  ...and the merge names that exact commit" "$HEAD_A"
assert_logged "  ...and the check-runs read is paginated" "--paginate"
assert_logged "  ...and any stale auto-merge is revoked first" "--disable-auto"

# ----------------------------------------------------------- missing checks
STUB_CHECKS='[]' \
  expect "no check runs at all" fail "did not all succeed" \
  -- "${BASE_ARGS[@]}" --check "Build and test"
assert_not_merged "  ...and nothing was merged"

STUB_CHECKS="$BE_GREEN" \
  expect "one required check absent from an otherwise green set" fail "absent: 'Build the container image'" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --check "Build the container image" \
     --producer some-other-app
assert_not_merged "  ...and nothing was merged (wrong producer for both)"

STUB_CHECKS="$(mkset "$(mkcheck 'Build and test' completed success github-actions 101)")" \
  expect "a required check that never ran" fail "absent: 'Build the container image'" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --check "Build the container image"
assert_not_merged "  ...and nothing was merged (a required check never ran)"

# ----------------------------------------------------- non-success conclusions
for c in failure cancelled timed_out action_required stale neutral skipped; do
    STUB_CHECKS="$(mkset "$(mkcheck 'Build and test' completed "$c" github-actions 101)")" \
      expect "a required check concluding '$c'" fail "concluded '$c', not success" \
      -- "${BASE_ARGS[@]}" --check "Build and test"
    assert_not_merged "  ...and '$c' merged nothing"
done

# A check that is still going is not a pass either, and the refusal names it.
STUB_CHECKS="$(mkset "$(mkcheck 'Build and test' in_progress '' github-actions 101)")" \
  expect "a required check still running" fail "running: 'Build and test' (in_progress)" \
  -- "${BASE_ARGS[@]}" --check "Build and test"
assert_not_merged "  ...and an unfinished check merged nothing"

STUB_CHECKS="$(mkset "$(mkcheck 'Build and test' queued '' github-actions 101)")" \
  expect "a required check still queued" fail "running: 'Build and test' (queued)" \
  -- "${BASE_ARGS[@]}" --check "Build and test"

# --------------------------------------------------------------- the producer
STUB_CHECKS="$(mkset "$(mkcheck 'Build and test' completed success some-other-app 101)")" \
  expect "green, but produced by an untrusted app" fail "absent: 'Build and test' (no run from 'github-actions')" \
  -- "${BASE_ARGS[@]}" --check "Build and test"
assert_not_merged "  ...and an untrusted green tick merged nothing"

# The interesting version of the same case: the trusted producer says it failed
# and an untrusted one says it passed. The trusted verdict must be the one read.
STUB_CHECKS="$(mkset \
    "$(mkcheck 'Build and test' completed failure github-actions 101)" \
    "$(mkcheck 'Build and test' completed success impostor-app 102)")" \
  expect "an untrusted green cannot overrule the trusted failure of the same name" \
  fail "concluded 'failure', not success" \
  -- "${BASE_ARGS[@]}" --check "Build and test"
assert_not_merged "  ...and the impostor merged nothing"

# ------------------------------------------------------------------ re-runs
# Attempts are ordered by check-run id, not by completed_at: an in-progress
# re-run has no completed_at, so ordering on it would rank the superseded green
# attempt last and let it answer for a commit that is being re-tested.
STUB_CHECKS="$(mkset \
    "$(mkcheck 'Build and test' completed success github-actions 101)" \
    "$(mkcheck 'Build and test' in_progress '' github-actions 202)")" \
  expect "a green attempt superseded by a re-run that is still going" fail "running: 'Build and test'" \
  -- "${BASE_ARGS[@]}" --check "Build and test"
assert_not_merged "  ...and the superseded green merged nothing"

STUB_CHECKS="$(mkset \
    "$(mkcheck 'Build and test' completed success github-actions 101)" \
    "$(mkcheck 'Build and test' completed failure github-actions 202)")" \
  expect "a green attempt superseded by a failed re-run" fail "concluded 'failure', not success" \
  -- "${BASE_ARGS[@]}" --check "Build and test"

STUB_CHECKS="$(mkset \
    "$(mkcheck 'Build and test' completed failure github-actions 101)" \
    "$(mkcheck 'Build and test' completed success github-actions 202)")" \
  expect "a failed attempt superseded by a green re-run" pass "auto-merge enabled" \
  -- "${BASE_ARGS[@]}" --check "Build and test"

# ------------------------------------------------------------ head-SHA binding
# The head moved before verification even started.
STUB_CHECKS="$BE_GREEN" STUB_HEAD="$HEAD_B" \
  expect "the head moved before verification" fail "not the $HEAD_A this run verified" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --check "Build the container image"
assert_not_merged "  ...and a moved head merged nothing"

# The crux. The head is correct for every read during verification and changes
# only for the final read taken immediately before the merge.
STUB_CHECKS="$BE_GREEN" STUB_HEAD_SWITCH_AFTER=1 STUB_HEAD_LATE="$HEAD_B" \
  expect "the head changed AFTER the checks were verified" fail "immediately before merging" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --check "Build the container image"
assert_not_merged "  ...and the commit verified a moment earlier was NOT merged"

# ----------------------------------------------------- a comma in a check name
# This organisation's frontend CI job really is called
# "Install, type-check and build". Any splitting of a delimited list turns that
# one name into "Install" plus " type-check and build" and then refuses every
# frontend dependency update for want of a check named "Install".
FE_GREEN="$(mkset \
    "$(mkcheck 'Install, type-check and build' completed success github-actions 101)" \
    "$(mkcheck 'Build the container image' completed success github-actions 102)")"

STUB_CHECKS="$FE_GREEN" \
  expect "a required check whose NAME CONTAINS A COMMA" pass "auto-merge enabled" \
  -- "${BASE_ARGS[@]}" --check "Install, type-check and build" --check "Build the container image"
assert_merged_bound_to "  ...and it merged, bound to the verified commit" "$HEAD_A"

STUB_CHECKS="$FE_GREEN" \
  expect "half of a comma-containing name is not a match" fail "absent: 'Install'" \
  -- "${BASE_ARGS[@]}" --check "Install"

STUB_CHECKS="$FE_GREEN" \
  expect "the other half is not a match either" fail "absent: ' type-check and build'" \
  -- "${BASE_ARGS[@]}" --check " type-check and build"

# --------------------------------------------------------- argument handling
expect "no --check at all" fail "At least one --check is required" \
  -- --repo "$REPO" --pr "$PR_NUMBER" --expected-head "$HEAD_A"

expect "an empty --check" fail "An empty --check was given" \
  -- "${BASE_ARGS[@]}" --check ""

expect "an abbreviated head SHA" fail "full 40-character commit SHA" \
  -- --repo "$REPO" --pr "$PR_NUMBER" --expected-head "aaaaaaa" --check "Build and test"

expect "no head SHA" fail "full 40-character commit SHA" \
  -- --repo "$REPO" --pr "$PR_NUMBER" --check "Build and test"

expect "a branch name where a SHA belongs" fail "full 40-character commit SHA" \
  -- --repo "$REPO" --pr "$PR_NUMBER" --expected-head "develop" --check "Build and test"

expect "a malformed --repo" fail "must be owner/name" \
  -- --repo "not a repo; touch /tmp/manara-sec-011-pwned" --pr "$PR_NUMBER" \
     --expected-head "$HEAD_A" --check "Build and test"

expect "a non-numeric --pr" fail "must be a pull request number" \
  -- --repo "$REPO" --pr '42; touch /tmp/manara-sec-011-pwned' \
     --expected-head "$HEAD_A" --check "Build and test"

expect "an unknown argument" fail "Unknown argument" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --merge-anyway

# --dry-run proves eligibility without touching the pull request at all.
STUB_CHECKS="$BE_GREEN" \
  expect "--dry-run reports eligibility" pass "merge-eligible; not merging" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --check "Build the container image" --dry-run
assert_not_merged "  ...and --dry-run merged nothing"

# ------------------------------------------------------------- larger sets
# 150 unrelated check runs plus the two that matter, to confirm selection is by
# name and producer rather than by position in the response.
BIG="$(jq -cn --argjson green "$BE_GREEN" \
    '[range(1;151) | {id: (1000 + .), name: ("noise-\(.)"), status: "completed",
                      conclusion: "failure", app: {slug: "github-actions"}}] + $green')"
STUB_CHECKS="$BIG" \
  expect "the required checks are found in a 152-run set (unrelated failures ignored)" \
  pass "auto-merge enabled" \
  -- "${BASE_ARGS[@]}" --check "Build and test" --check "Build the container image"

echo
echo "dependabot-auto-merge.yml (parsed, not grepped)"

if ! command -v python3 >/dev/null 2>&1; then
    bad "workflow structure" "python3 is required to parse the workflow; the structural assertions did not run"
else
    WF_OUT="$(WORKFLOW="$WORKFLOW" python3 - <<'PY'
import os, re, sys

try:
    import yaml
except ImportError:
    print("FAIL workflow parses as YAML :: PyYAML is not installed")
    sys.exit(0)

path = os.environ["WORKFLOW"]
results = []

def check(name, condition, detail=""):
    results.append(("ok" if condition else "FAIL", name, detail))

try:
    wf = yaml.safe_load(open(path))
except Exception as exc:            # noqa: BLE001
    print(f"FAIL workflow parses as YAML :: {exc}")
    sys.exit(0)

check("the workflow parses as YAML", isinstance(wf, dict))

# PyYAML resolves the bare key `on:` to the boolean True.
triggers = wf.get("on", wf.get(True))
trigger_names = set(triggers) if isinstance(triggers, dict) else {triggers}

# pull_request_target is what lets this workflow hold a write token on a Dependabot
# pull request at all -- a pull_request run gets a read-only one. It is safe only for
# as long as nothing here checks out the pull request own code, so that is what is
# asserted, rather than the trigger name. The per-step checkout assertions below carry
# the other half.
untrusted_refs = ("head.sha", "head.ref", "merge", "pull_request.head")
checkout_refs = [
    str(step.get("with", {}).get("ref", ""))
    for job in wf["jobs"].values()
    for step in job.get("steps", [])
    if "checkout" in str(step.get("uses", ""))
]
check("a pull_request_target run never checks out the pull request own code",
      "pull_request_target" not in trigger_names
      or all(ref and not any(u in ref for u in untrusted_refs) for ref in checkout_refs),
      f"triggers: {sorted(map(str, trigger_names))}; checkout refs: {checkout_refs}")

check("no workflow-wide token scopes are granted",
      wf.get("permissions") == {},
      f"top-level permissions: {wf.get('permissions')!r}")

ALLOWED = {"contents": "write", "pull-requests": "write", "checks": "read"}
CHECKOUT = re.compile(r"^actions/checkout@")
# Anything that reads the head of the pull request rather than its base.
HEAD_REF = re.compile(r"head_ref|pull_request\.head|github\.sha|refs/pull/", re.I)
# Anything that would execute the checked-out tree.
EXECUTES = re.compile(r"\b(mvn|mvnw|npm|pnpm|yarn|gradle|docker\s+build|make|"
                      r"setup-java|setup-node|build-push-action)\b")

for job_id, job in (wf.get("jobs") or {}).items():
    perms = job.get("permissions")
    check(f"job '{job_id}' declares its own permissions", isinstance(perms, dict),
          f"permissions: {perms!r}")
    if isinstance(perms, dict):
        extra = {k: v for k, v in perms.items()
                 if k not in ALLOWED or ALLOWED[k] != v}
        check(f"job '{job_id}' asks for nothing beyond contents/pull-requests write "
              f"and checks read", not extra, f"unexpected: {extra!r}")

    guard = str(job.get("if", ""))
    check(f"job '{job_id}' still runs only for Dependabot",
          "dependabot[bot]" in guard and "github.actor" in guard
          and "pull_request.user.login" in guard,
          f"if: {guard!r}")

    for step in job.get("steps") or []:
        uses = str(step.get("uses", ""))
        run = str(step.get("run", ""))
        with_ = step.get("with") or {}
        label = f"job '{job_id}' step '{step.get('name', uses or 'run')}'"

        if CHECKOUT.match(uses):
            ref = str(with_.get("ref", ""))
            check(f"{label} checks out an explicit ref", ref != "",
                  "a checkout with no ref takes the pull request merge ref, "
                  "which contains the pull request own code")
            check(f"{label} checks out the base, never the pull request head",
                  ref != "" and not HEAD_REF.search(ref), f"ref: {ref!r}")
            check(f"{label} does not persist the token in the working copy",
                  with_.get("persist-credentials") is False,
                  f"persist-credentials: {with_.get('persist-credentials')!r}")

        check(f"{label} does not build, install or test anything",
              not EXECUTES.search(uses) and not EXECUTES.search(run),
              "a privileged job must not execute project code")

for status, name, detail in results:
    print(f"{status} {name}" + (f" :: {detail}" if status == "FAIL" and detail else ""))
PY
)"
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        case "$line" in
            "ok "*)   ok "${line#ok }" ;;
            "FAIL "*) rest="${line#FAIL }"; bad "${rest%% :: *}" "${rest#* :: }" ;;
            *)        bad "workflow assertions" "unparsed output: $line" ;;
        esac
    done <<< "$WF_OUT"
fi

echo
echo "  $PASS passed, $FAIL failed"
if [[ -e /tmp/manara-sec-011-pwned ]]; then
    echo "  INJECTION ARTIFACT CREATED"
    FAIL=$((FAIL + 1))
fi
exit $(( FAIL > 0 ))
