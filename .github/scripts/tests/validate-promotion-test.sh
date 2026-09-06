#!/usr/bin/env bash
#
# Tests for validate-promotion.sh.
#
# Everything here is local. A throwaway git repository is built per case so the
# ancestry and tag-resolution rules are exercised against real git behaviour
# rather than a mock of it, and `gh` is replaced by a stub that replays fixture
# JSON. No network, no GitHub, no registry, no workflow run.
#
#   .github/scripts/tests/validate-promotion-test.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATOR="$SCRIPT_DIR/validate-promotion.sh"

PASS=0
FAIL=0
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ------------------------------------------------------------------ fixtures

# A repository with `main`, a feature branch off it, and whichever tags the case
# asks for. `origin/main` is a real remote-tracking ref, so merge-base behaves
# exactly as it will in CI.
build_repo() {
    local dir="$1" tag_style="${2:-lightweight}" tag_target="${3:-main}"
    rm -rf "$dir" && mkdir -p "$dir/origin"

    git init -q --bare "$dir/origin/repo.git"
    git init -q "$dir/repo"
    cd "$dir/repo"
    git config user.email t@example.test && git config user.name Test
    git config commit.gpgsign false

    echo one > f && git add f && git commit -qm one
    git branch -M main
    echo two > f && git commit -qam two
    MAIN_SHA="$(git rev-parse HEAD)"

    git checkout -q -b feature
    echo three > f && git commit -qam three
    FEATURE_SHA="$(git rev-parse HEAD)"
    git checkout -q main

    git remote add origin "$dir/origin/repo.git"
    git push -q origin main
    git fetch -q origin

    local target_sha="$MAIN_SHA"
    [[ "$tag_target" == "feature" ]] && target_sha="$FEATURE_SHA"

    case "$tag_style" in
        lightweight) git tag v1.4.0 "$target_sha" ;;
        annotated)   git tag -a v1.4.0 -m "release" "$target_sha" ;;
        branch)      git branch v1.4.0 "$FEATURE_SHA" ;;  # a BRANCH, no tag
        none)        : ;;
    esac

    export EXPECTED_SHA="$target_sha"
}

# Stub `gh`. Behaviour is driven by environment variables the case sets, so each
# case declares the GitHub state it is testing against rather than editing files.
write_gh_stub() {
    cat > "$WORK/gh" <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "release view")
      [[ "${STUB_RELEASE:-missing}" == "missing" ]] && exit 1
      printf '{"tagName":"v1.4.0","isDraft":%s,"targetCommitish":"main"}' \
             "${STUB_RELEASE_DRAFT:-false}"
      exit 0 ;;
esac
if [[ "$1" == "api" ]]; then
  case "$2" in
    *git/refs/tags/*) printf '%s' "${STUB_TAG_SHA:-$EXPECTED_SHA}"; exit 0 ;;
    *check-runs*)     printf '%s' "${STUB_CHECKS:-[]}"; exit 0 ;;
  esac
fi
exit 0
STUB
    chmod +x "$WORK/gh"
}

green_checks() {
    cat <<JSON
[{"name":"Build and test","status":"completed","conclusion":"success","completed_at":"2026-01-01T00:00:00Z","app":{"slug":"github-actions"}},
 {"name":"Build the container image","status":"completed","conclusion":"success","completed_at":"2026-01-01T00:00:00Z","app":{"slug":"github-actions"}}]
JSON
}

# ------------------------------------------------------------------- runner

# expect <name> <pass|fail> <expected-substring-or-eq-EXPECTED_SHA> -- <args...>
expect() {
    local name="$1" want="$2" needle="$3"; shift 4
    local out rc
    out="$(GITHUB_OUTPUT="$WORK/out" PROMOTION_GH="$WORK/gh" PROMOTION_POLL_SECONDS=0 \
           bash "$VALIDATOR" "$@" 2>&1)"; rc=$?

    local ok=true
    if [[ "$want" == pass && $rc -ne 0 ]]; then ok=false; fi
    if [[ "$want" == fail && $rc -eq 0 ]]; then ok=false; fi
    if [[ -n "$needle" ]] && ! printf '%s' "$out" | grep -qF "$needle"; then ok=false; fi

    if $ok; then
        PASS=$((PASS + 1)); printf '  ok    %s\n' "$name"
    else
        FAIL=$((FAIL + 1))
        printf '  FAIL  %s\n        wanted %s containing "%s", rc=%s\n        got: %s\n' \
               "$name" "$want" "$needle" "$rc" "$(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
    fi
}

write_gh_stub
export PATH="$WORK:$PATH"

echo "validate-promotion.sh"

# --------------------------------------------------------- tag resolution
build_repo "$WORK/c1" lightweight main
expect "lightweight tag on main resolves" pass "$EXPECTED_SHA" -- --tag v1.4.0

build_repo "$WORK/c2" annotated main
# The crux: an annotated tag must resolve to the COMMIT, not to the tag object.
TAG_OBJECT="$(git rev-parse v1.4.0)"
expect "annotated tag resolves to the commit, not the tag object" pass "$EXPECTED_SHA" -- --tag v1.4.0
if [[ "$TAG_OBJECT" == "$EXPECTED_SHA" ]]; then
    echo "  warn  annotated tag object equalled the commit; case is weaker than intended"
fi

build_repo "$WORK/c3" branch main
expect "a BRANCH named v1.4.0 is not a tag" fail "does not exist as a tag" -- --tag v1.4.0

build_repo "$WORK/c4" none main
expect "a tag that does not exist" fail "does not exist as a tag" -- --tag v1.4.0

# ------------------------------------------------------------------ syntax
expect "non-semver tag"        fail "is not vMAJOR.MINOR.PATCH" -- --tag v1.4
expect "prerelease suffix"     fail "is not vMAJOR.MINOR.PATCH" -- --tag v1.4.0-rc1
expect "injection in tag"      fail "is not vMAJOR.MINOR.PATCH" -- --tag 'v1.4.0;touch /tmp/pwned'

# ---------------------------------------------------------------- ancestry
build_repo "$WORK/c5" lightweight feature
expect "tag on a feature branch is refused" fail "is not an ancestor of main" -- --tag v1.4.0

# ----------------------------------------------------------------- release
build_repo "$WORK/c6" lightweight main
STUB_RELEASE=missing \
  expect "no release, when one is required" fail "No GitHub Release" -- --tag v1.4.0 --require-release

STUB_RELEASE=present STUB_RELEASE_DRAFT=true \
  expect "draft release is refused" fail "is a draft" -- --tag v1.4.0 --require-release

STUB_RELEASE=present STUB_TAG_SHA=0000000000000000000000000000000000000000 \
  expect "tag moved since the release was cut" fail "The tag has moved" -- --tag v1.4.0 --require-release

STUB_RELEASE=present \
  expect "published release still on the same commit" pass "approved for promotion" -- --tag v1.4.0 --require-release

# ------------------------------------------------------------------ checks
build_repo "$WORK/c7" lightweight main
STUB_CHECKS='[]' \
  expect "no check runs at all" fail "No check run named" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --timeout-seconds 1

STUB_CHECKS='[{"name":"Build and test","status":"completed","conclusion":"failure","completed_at":"2026-01-01T00:00:00Z","app":{"slug":"github-actions"}}]' \
  expect "a failed check" fail "concluded 'failure'" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --timeout-seconds 1

STUB_CHECKS='[{"name":"Build and test","status":"completed","conclusion":"cancelled","completed_at":"2026-01-01T00:00:00Z","app":{"slug":"github-actions"}}]' \
  expect "a cancelled check" fail "concluded 'cancelled'" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --timeout-seconds 1

STUB_CHECKS='[{"name":"Build and test","status":"completed","conclusion":"skipped","completed_at":"2026-01-01T00:00:00Z","app":{"slug":"github-actions"}}]' \
  expect "a skipped check is not a pass" fail "concluded 'skipped'" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --timeout-seconds 1

STUB_CHECKS='[{"name":"Build and test","status":"completed","conclusion":"success","completed_at":"2026-01-01T00:00:00Z","app":{"slug":"some-other-app"}}]' \
  expect "green, but from the wrong producer" fail "No check run named" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --timeout-seconds 1

STUB_CHECKS="$(green_checks)" \
  expect "one required check missing from an otherwise green set" fail "No check run named" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --check "Nonexistent check" --timeout-seconds 1

STUB_CHECKS="$(green_checks)" \
  expect "both required checks green" pass "approved for promotion" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --check "Build the container image" --timeout-seconds 1

# A check-run name is free text, and this organisation's frontend CI job really
# is called "Install, type-check and build". An earlier version of this script
# took one comma-separated list and split on it, which turned that single name
# into "Install" plus " type-check and build" and refused every frontend release.
COMMA_CHECK='[{"name":"Install, type-check and build","status":"completed","conclusion":"success","completed_at":"2026-01-01T00:00:00Z","app":{"slug":"github-actions"}}]'
STUB_CHECKS="$COMMA_CHECK" \
  expect "a check whose NAME CONTAINS A COMMA" pass "approved for promotion" \
  -- --tag v1.4.0 --require-checks --check "Install, type-check and build" --timeout-seconds 1

STUB_CHECKS="$COMMA_CHECK" \
  expect "half of a comma-containing name is not a match" fail "No check run named" \
  -- --tag v1.4.0 --require-checks --check "Install" --timeout-seconds 1

expect "--require-checks with no --check" fail "at least one --check" \
  -- --tag v1.4.0 --require-checks --timeout-seconds 1

# ------------------------------------------------ the release.yml circularity
STUB_CHECKS="$(green_checks)" \
  expect "checks required WITHOUT a release (release.yml's own case)" pass "approved for promotion" \
  -- --tag v1.4.0 --require-checks --check "Build and test" --timeout-seconds 1

# --------------------------------------------------------------- everything
build_repo "$WORK/c8" annotated main
STUB_RELEASE=present STUB_CHECKS="$(green_checks)" \
  expect "annotated tag, on main, released, all checks green" pass "approved for promotion" \
  -- --tag v1.4.0 --require-release --require-checks \
     --check "Build and test" --check "Build the container image" --timeout-seconds 1

echo
echo "  $PASS passed, $FAIL failed"
[[ -e /tmp/pwned ]] && { echo "  INJECTION ARTIFACT CREATED"; FAIL=$((FAIL + 1)); }
exit $(( FAIL > 0 ))
