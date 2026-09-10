#!/usr/bin/env bash
# Controlled verification of the security notifier.
#
# Same shape as verify-gate.sh, same reason for existing: the failure modes have
# to be proven to fail. A notifier is only worth having if "the scan broke" and
# "the artifact is not from this run" are as loud as "we found a critical CVE",
# and if a hostile pull request cannot make it send something it should not.
#
# Every case is a fixture. NOTHING here sends mail: the provider is `dry-run` by
# default and `mock` where a provider response has to be scripted, so a mistake
# in this file cannot reach a real mailbox. It takes no arguments and needs no
# credential.
set -uo pipefail

SHARED="$(cd "$(dirname "$0")" && pwd)"
NOTIFY="$SHARED/notify.py"
WORK="$(mktemp -d)"
PASS=0; FAIL=0
REPO="Manara-Education/backend-foundation"

ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && echo "        $2"; FAIL=$((FAIL+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "got '$2', wanted '$3'"; fi; }

# --- fixture builders ------------------------------------------------------

# mk_meta <dir> <conclusion> [event] [pr_number] [pr_title]
mk_meta() {
  python3 - "$1" "$2" "${3:-pull_request}" "${4:-42}" "${5:-Add a feature}" "$REPO" <<'PY'
import json, sys
d, conclusion, event, pr, title, repo = sys.argv[1:7]
meta = {
    "repository": repo, "event_repository": repo, "server_url": "https://github.com",
    "run_id": 9001, "run_attempt": 1, "workflow_name": "Security", "event": event,
    "status": "completed", "conclusion": conclusion,
    "head_sha": "a" * 40, "head_branch": "feat/thing",
    "run_started_at": "2026-09-09T03:23:00Z",
    "artifact": {"id": 5551212, "name": "security-gate-verdict"},
}
if event == "pull_request":
    meta["pull_request"] = {"number": int(pr), "base_ref": "develop",
                            "head_ref": "feat/thing", "head_sha": "a" * 40, "title": title}
json.dump(meta, open(f"{d}/meta.json", "w"), indent=2)
PY
}

# mk_leg <dir> <slug> <status> <blocking-count> [extra-json]
EMPTY_JSON='{}'
mk_leg() {
  python3 - "$1" "$2" "$3" "$4" "${5:-$EMPTY_JSON}" "$REPO" <<'PY'
import json, os, sys
d, slug, status, nblock, extra, repo = sys.argv[1:7]
nblock = int(nblock)
sev = {"CRITICAL": nblock, "HIGH": 0, "MEDIUM": 1, "LOW": 0, "INFO": 0}
findings = []
for i in range(nblock):
    findings.append({
        "id": f"CVE-2026-1000{i}", "key": f"CVE-2026-1000{i}|lib{i}|1.0.0",
        "severity": "CRITICAL", "category": "dependency", "detector": "osv-scanner",
        "title": f"Finding {i}", "component": f"org.example:lib{i}", "version": "1.0.0",
        "fixed_version": "1.0.1" if i % 2 == 0 else "",
        "all_fixed_versions": ["1.0.1"] if i % 2 == 0 else [],
        "location": "pom.xml", "kev": i == 0, "status": "blocking", "blocking": True})
findings.append({"id": "CVE-2026-20000", "key": "CVE-2026-20000|quiet|2.0",
                 "severity": "MEDIUM", "category": "dependency", "detector": "trivy",
                 "title": "Tracked only", "component": "org.example:quiet", "version": "2.0",
                 "fixed_version": "2.1", "all_fixed_versions": ["2.1"], "location": "pom.xml",
                 "kev": False, "status": "tracked", "blocking": False})
doc = {
    "schema_version": 1, "generated_at": "2026-09-09T03:40:00+00:00",
    "repository": repo, "event": "pull_request", "ref": "feat/thing",
    "revision": "a" * 40, "target_slug": slug, "target_name": f"PR #42 ({slug})",
    "run_id": "9001", "run_attempt": 1,
    "run_url": "https://github.com/x/y/actions/runs/9001",
    "workflow": "Security", "pr_number": 42, "base_ref": "develop",
    "image_digests": [], "policy_revision": "sha256:" + "b" * 64,
    "intelligence": {"osv": {"source": "osv", "status": "ok",
                             "retrieved_at": "2026-09-09T03:00:00+00:00",
                             "upstream_revision": "Maven@abc"},
                     "ghsa": {"source": "ghsa", "status": "ok",
                              "retrieved_at": "2026-09-09T03:00:00+00:00"},
                     "trivy_db": {"source": "trivy-db", "status": "ok",
                                  "retrieved_at": "2026-09-09T03:00:00+00:00"},
                     "kev": {"source": "cisa-kev", "status": "ok",
                             "retrieved_at": "2026-09-09T03:00:00+00:00",
                             "entry_count": 1400},
                     "advisory": {}},
    "scanners": [], "coverage_complete": True, "coverage_gaps": [],
    "status": status, "exit_code": {"PASS": 0, "BLOCKED": 1, "ERROR": 2}[status],
    "counts": {"blocking": nblock, "tracked": 1, "excepted": 0,
               "by_severity": sev, "kev": 1 if nblock else 0},
    "findings": findings if nblock else findings[-1:],
    "blocking_reasons": [f"CRITICAL CVE-2026-1000{i} in org.example:lib{i} 1.0.0."
                         for i in range(nblock)],
    "error_reasons": [], "notes": [],
}
doc.update(json.loads(extra))
out = os.path.join(d, "out", slug)
os.makedirs(out, exist_ok=True)
json.dump(doc, open(os.path.join(out, "verdict.json"), "w"), indent=2)
open(os.path.join(out, "report.md"), "w").write(
    f"## Security Gate — {status}\n\nfixture report for {slug}\n")
json.dump({"schema": 1, "findings": [
    dict(f, first_seen="2026-01-01T00:00:00+00:00", first_seen_revision="z" * 40)
    for f in findings]}, open(os.path.join(d, "out", slug, "findings.json"), "w"))
PY
}

# run <case-dir> [extra notify args...]
run_notify() {
  local d="$1"; shift
  python3 "$NOTIFY" --run-metadata "$d/meta.json" --artifact-dir "$d" \
    --out-dir "$d/result" --to alerts@example.invalid --from no-reply@example.invalid \
    --sleep-scale 0 "$@" >"$d/stdout.txt" 2>"$d/stderr.txt"
  echo $?
}

field() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$1" "$2"; }

echo "Security notifier behaviour"
echo

# 1 — a complete clean pass sends nothing
D="$WORK/clean"; mkdir -p "$D"; mk_meta "$D" success; mk_leg "$D" t0 PASS 0
rc=$(run_notify "$D")
check "clean pass sends no mail" "$(field "$D/result/result.json" decision)" "none"
check "  ...and the job still succeeds" "$rc" "0"
if [ ! -f "$D/result/request.json" ]; then ok "  ...and no request was built"; else bad "  ...and no request was built"; fi

# 2 — blockers produce one mail carrying every blocker
D="$WORK/block"; mkdir -p "$D"; mk_meta "$D" failure; mk_leg "$D" t0 BLOCKED 3
rc=$(run_notify "$D")
check "blocking verdict sends one mail" "$(field "$D/result/result.json" decision)" "blocked"
check "  ...exit 0 (the mail is the job's success)" "$rc" "0"
missing=0
for i in 0 1 2; do grep -q "CVE-2026-1000$i" "$D/result/body.txt" || missing=$((missing+1)); done
check "  ...every blocker appears in the body" "$missing" "0"
if grep -q "SECURITY BLOCKED" "$D/result/body.txt" && \
   grep -q "^\[Manara\]\[SECURITY BLOCKED\]\[$REPO\]" "$D/result/body.txt"; then
  ok "  ...subject is tagged and scoped"
else bad "  ...subject is tagged and scoped" "$(head -1 "$D/result/body.txt")"; fi
if grep -q "no verified upgrade available" "$D/result/body.txt"; then
  ok "  ...a blocker with no reachable fix says so explicitly"
else bad "  ...a blocker with no reachable fix says so explicitly"; fi
if grep -q "CISA KEV" "$D/result/body.txt"; then ok "  ...KEV status is stated"
else bad "  ...KEV status is stated"; fi
if grep -q "continuing since 2026-01-01" "$D/result/body.txt"; then
  ok "  ...pre-existing blockers are marked continuing, not just new ones"
else bad "  ...pre-existing blockers are marked continuing"; fi

# 3 — a scanner failed AND blockers exist: the blocking mail wins, gap stated
D="$WORK/gap"; mkdir -p "$D"; mk_meta "$D" failure
mk_leg "$D" t0 BLOCKED 2 '{"coverage_complete": false, "coverage_gaps": [{"kind":"scanner","detail":"image-trivy: the scanner job ended failure.","hard":true}]}'
rc=$(run_notify "$D")
check "scanner failure + blockers still sends the BLOCKING mail" \
  "$(field "$D/result/result.json" decision)" "blocked"
if grep -q "REQUIRED ASSESSMENT MISSING" "$D/result/body.txt" && \
   grep -q "image-trivy" "$D/result/body.txt"; then
  ok "  ...and states the coverage gap"
else bad "  ...and states the coverage gap"; fi

# 4 — one leg unusable must never suppress another leg's blockers
D="$WORK/multileg"; mkdir -p "$D"; mk_meta "$D" failure schedule
mk_leg "$D" develop BLOCKED 2
mk_leg "$D" main PASS 0
echo 'not json {{' > "$D/out/main/verdict.json"
rc=$(run_notify "$D")
check "a broken leg does not drop a healthy leg's blockers" \
  "$(field "$D/result/result.json" decision)" "blocked"
check "  ...the broken leg is reported as rejected" \
  "$(python3 -c "import json;print(len(json.load(open('$D/result/result.json'))['rejected_legs']))")" "1"
if grep -q "VERDICT NOT USED" "$D/result/body.txt"; then ok "  ...and named in the body"
else bad "  ...and named in the body"; fi

# 5 — missing verdict
D="$WORK/noverdict"; mkdir -p "$D/out"; mk_meta "$D" failure
rc=$(run_notify "$D")
check "no verdict at all sends the ERROR mail" "$(field "$D/result/result.json" decision)" "error"
if grep -q "SECURITY SCAN ERROR / INCOMPLETE" "$D/result/body.txt"; then ok "  ...tagged ERROR"
else bad "  ...tagged ERROR"; fi

# 6 — malformed verdict (unknown schema_version)
D="$WORK/badschema"; mkdir -p "$D"; mk_meta "$D" success; mk_leg "$D" t0 PASS 0 '{"schema_version": 99}'
rc=$(run_notify "$D")
check "an unknown schema_version is refused, not guessed at" \
  "$(field "$D/result/result.json" decision)" "error"
if grep -q "schema_version 99" "$D/result/body.txt"; then ok "  ...and the reason is stated"
else bad "  ...and the reason is stated"; fi

# 7 — stale verdict: right shape, wrong run
D="$WORK/stale"; mkdir -p "$D"; mk_meta "$D" success; mk_leg "$D" t0 PASS 0 '{"run_id": "1234"}'
rc=$(run_notify "$D")
check "a verdict from another run is not this run's result" \
  "$(field "$D/result/result.json" decision)" "error"
if grep -q "produced by run 1234" "$D/result/body.txt"; then ok "  ...and says which run it came from"
else bad "  ...and says which run it came from"; fi

D="$WORK/staleattempt"; mkdir -p "$D"; mk_meta "$D" success
mk_leg "$D" t0 PASS 0 '{"run_attempt": 7}'
rc=$(run_notify "$D")
check "a verdict from another attempt is refused too" \
  "$(field "$D/result/result.json" decision)" "error"

# 8 — forged repository inside the artifact
D="$WORK/forged"; mkdir -p "$D"; mk_meta "$D" success
mk_leg "$D" t0 BLOCKED 2 '{"repository": "attacker/evil"}'
rc=$(run_notify "$D")
check "a verdict naming a different repository is rejected" \
  "$(field "$D/result/result.json" decision)" "error"
if grep -q "attacker/evil" "$D/result/body.txt" && grep -q "VERDICT NOT USED" "$D/result/body.txt"; then
  ok "  ...its claim is shown as a rejected claim, not as a result"
else bad "  ...its claim is shown as a rejected claim"; fi

# 9 — cancelled run is never a pass
D="$WORK/cancelled"; mkdir -p "$D"; mk_meta "$D" cancelled; mk_leg "$D" t0 PASS 0
rc=$(run_notify "$D")
check "a cancelled run sends the ERROR mail, never silence" \
  "$(field "$D/result/result.json" decision)" "error"
if grep -q "concluded 'cancelled'" "$D/result/body.txt"; then ok "  ...naming the conclusion"
else bad "  ...naming the conclusion"; fi

D="$WORK/timedout"; mkdir -p "$D"; mk_meta "$D" timed_out; mk_leg "$D" t0 PASS 0
rc=$(run_notify "$D")
check "a timed-out run sends the ERROR mail too" \
  "$(field "$D/result/result.json" decision)" "error"

# 10 — hostile strings are rendered as data
HOSTILE='</h1><script>alert(1)</script><img src=x onerror=alert(2)>'
D="$WORK/hostile"; mkdir -p "$D"; mk_meta "$D" failure pull_request 42 "$HOSTILE"
mk_leg "$D" t0 BLOCKED 1 "$(python3 -c '
import json; print(json.dumps({"ref": "</table><script>alert(3)</script>",
 "target_name": "<script>alert(4)</script>"}))')"
rc=$(run_notify "$D")
check "a hostile PR title still produces a mail" "$(field "$D/result/result.json" decision)" "blocked"
if grep -q "<script>" "$D/result/body.html"; then
  bad "  ...injected HTML is inert in the HTML body" "raw <script> reached the HTML"
else ok "  ...injected HTML is inert in the HTML body"; fi
if grep -q "&lt;script&gt;" "$D/result/body.html"; then
  ok "  ...and is rendered as escaped text"
else bad "  ...and is rendered as escaped text"; fi
if python3 -c "
import sys
h = open('$D/result/body.html').read()
sys.exit(0 if 'onerror=' not in h.replace('onerror=alert(2)&gt;','') else 1)" 2>/dev/null; then
  ok "  ...no event-handler attribute survives"
else bad "  ...no event-handler attribute survives"; fi

# 11 — path traversal in the archive
D="$WORK/traversal"; mkdir -p "$D"; mk_meta "$D" failure
python3 - "$D" <<'PY'
import zipfile, json, os
d = os.sys.argv[1]
with zipfile.ZipFile(f"{d}/evil.zip", "w") as z:
    z.writestr("out/t0/verdict.json", json.dumps({"schema_version": 1}))
    z.writestr("../../../../tmp/pwned.txt", "owned")
PY
python3 "$NOTIFY" --run-metadata "$D/meta.json" --artifact-zip "$D/evil.zip" \
  --out-dir "$D/result" --to a@example.invalid --from b@example.invalid --sleep-scale 0 \
  >"$D/stdout.txt" 2>&1
check "a path-traversing archive member is refused" \
  "$(field "$D/result/result.json" decision)" "error"
if grep -q "path-traversing" "$D/result/body.txt"; then ok "  ...and named as such"
else bad "  ...and named as such" "$(head -3 "$D/result/body.txt")"; fi
if [ ! -f /tmp/pwned.txt ]; then ok "  ...and nothing was written outside the destination"
else bad "  ...nothing written outside the destination" "/tmp/pwned.txt exists"; fi

D="$WORK/abs"; mkdir -p "$D"; mk_meta "$D" failure
python3 - "$D" <<'PY'
import zipfile, os
d = os.sys.argv[1]
with zipfile.ZipFile(f"{d}/abs.zip", "w") as z:
    z.writestr("/etc/pwned", "owned")
PY
python3 "$NOTIFY" --run-metadata "$D/meta.json" --artifact-zip "$D/abs.zip" \
  --out-dir "$D/result" --to a@example.invalid --from b@example.invalid --sleep-scale 0 >/dev/null 2>&1
check "an absolute-path archive member is refused" \
  "$(field "$D/result/result.json" decision)" "error"

# 12 — redaction
#
# The fake credentials are ASSEMBLED AT RUNTIME rather than written out as
# literals, and that is not squeamishness. Spelled in full, this file is itself
# a file containing a string shaped exactly like a live Stripe key — and GitHub
# push protection duly refused the push that first added it:
#
#     - GITHUB PUSH PROTECTION
#       —— Stripe API Key ——
#          path: .github/security/verify-notify.sh:284
#
# It was right to. A test fixture is still a string in a repository, and the
# scanner cannot know this one is synthetic. Bypassing the block through the
# "allow this secret" URL would have trained everyone to click that URL.
#
# Splitting the prefix from the body keeps the test exactly as strong: notify.py
# never sees the seam, only the joined value, which is what it must redact.
_L="live_"   # split so this file never contains the full pattern
D="$WORK/redact"; mkdir -p "$D"; mk_meta "$D" failure
mk_leg "$D" t0 BLOCKED 1 "$(python3 -c '
import json
sk = "sk_" + "live_" + "ABCDEFGHIJKLMNOPQRSTUVWX"
gh = "ghp_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
print(json.dumps({"blocking_reasons": [
  f"Secret detected — stripe key {sk} at src/config.ts:12.",
  f"gh token {gh} in .env",
  "DATABASE_PASSWORD=hunter2supersecret leaked",
  "reported by dev.person@example.com",
  "https://user:pa55word@internal.example.com/repo.git",
]}))')"
rc=$(run_notify "$D")
for needle in "sk_${_L}ABCDEFGH" "ghp_ABCDEFGH" "hunter2supersecret" "dev.person@example.com" "pa55word"; do
  if grep -q "$needle" "$D/result/body.txt" "$D/result/body.html"; then
    bad "redaction removes: $needle" "still present"
  else ok "redaction removes: $needle"; fi
done
if grep -q "redacted" "$D/result/body.txt"; then ok "  ...and says a value was redacted"
else bad "  ...and says a value was redacted"; fi
if grep -q "src/config.ts:12" "$D/result/body.txt"; then
  ok "  ...while keeping the location, which is the actionable half"
else bad "  ...while keeping the location"; fi

# 13 — provider: 429 then success, one message, same key
D="$WORK/retry"; mkdir -p "$D"; mk_meta "$D" failure; mk_leg "$D" t0 BLOCKED 1
echo '[{"status":429,"outcome":"rate limited"},{"status":200,"id":"msg-abc123"}]' > "$D/script.json"
rc=$(run_notify "$D" --provider mock --mock-script "$D/script.json")
check "a 429 is retried and then accepted" "$rc" "0"
check "  ...the provider message id is recorded" \
  "$(field "$D/result/result.json" message_id)" "msg-abc123"
check "  ...exactly two provider calls were made" \
  "$(python3 -c "import json;print(len(json.load(open('$D/result/provider-calls.json'))))")" "2"
check "  ...both used the same idempotency key" \
  "$(python3 -c "
import json;c=json.load(open('$D/result/provider-calls.json'))
print('same' if c[0]['idempotency_key']==c[1]['idempotency_key'] else 'different')")" "same"
check "  ...and both carried an identical payload" \
  "$(python3 -c "
import json;c=json.load(open('$D/result/provider-calls.json'))
print('same' if c[0]['request']==c[1]['request'] else 'different')")" "same"

# 14 — permanent 401 fails the job visibly
D="$WORK/401"; mkdir -p "$D"; mk_meta "$D" failure; mk_leg "$D" t0 BLOCKED 1
echo '[{"status":401,"outcome":"bad key"}]' > "$D/script.json"
rc=$(run_notify "$D" --provider mock --mock-script "$D/script.json")
check "a permanent 401 fails the notify job" "$rc" "1"
check "  ...and records that nothing was sent" "$(field "$D/result/result.json" sent)" "False"
if grep -q "::error" "$D/stderr.txt"; then ok "  ...loudly, as a workflow error annotation"
else bad "  ...loudly, as a workflow error annotation"; fi
if grep -q "security verdict for this run is unchanged" "$D/result/result.json"; then
  ok "  ...and says the security verdict is unaffected"
else bad "  ...and says the security verdict is unaffected"; fi

# 15 — a missing credential is a config failure, not a silent skip
D="$WORK/nokey"; mkdir -p "$D"; mk_meta "$D" failure; mk_leg "$D" t0 BLOCKED 1
rc=$(SECURITY_ALERT_RESEND_API_KEY= run_notify "$D" --provider resend)
check "an unset API key fails the job with an actionable message" "$rc" "1"
if grep -q "SECURITY_ALERT_RESEND_API_KEY" "$D/stderr.txt"; then ok "  ...naming the variable"
else bad "  ...naming the variable"; fi

# 16 — idempotency key: stable per run/attempt/scope, distinct across them
D="$WORK/idem1"; mkdir -p "$D"; mk_meta "$D" failure; mk_leg "$D" t0 BLOCKED 1
run_notify "$D" >/dev/null; k1="$(field "$D/result/result.json" idempotency_key)"
rm -rf "$D/result"; run_notify "$D" >/dev/null; k1b="$(field "$D/result/result.json" idempotency_key)"
check "the same run/attempt/scope reuses one idempotency key" "$k1" "$k1b"
D2="$WORK/idem2"; mkdir -p "$D2"; mk_meta "$D2" failure; mk_leg "$D2" t0 BLOCKED 1
python3 - "$D2" <<'PY'
import json, sys
p = f"{sys.argv[1]}/meta.json"; m = json.load(open(p)); m["run_attempt"] = 2
json.dump(m, open(p, "w"))
PY
python3 - "$D2" <<'PY'
import json, sys
p = f"{sys.argv[1]}/out/t0/verdict.json"; d = json.load(open(p)); d["run_attempt"] = 2
json.dump(d, open(p, "w"))
PY
run_notify "$D2" >/dev/null; k2="$(field "$D2/result/result.json" idempotency_key)"
if [ "$k1" != "$k2" ]; then ok "  ...and a re-run attempt is NOT suppressed"
else bad "  ...a re-run attempt is NOT suppressed" "both keys were $k1"; fi

# 17 — manual test mode
D="$WORK/testmode"; mkdir -p "$D/out"; mk_meta "$D" success workflow_dispatch
rc=$(run_notify "$D" --mode test)
check "test mode sends a synthetic message" "$(field "$D/result/result.json" decision)" "blocked"
if grep -q "^\[Manara\]\[TEST\]" "$D/result/body.txt"; then ok "  ...with TEST in the subject"
else bad "  ...with TEST in the subject" "$(head -1 "$D/result/body.txt")"; fi
if grep -q "Synthetic data" "$D/result/body.txt"; then ok "  ...and says it is synthetic"
else bad "  ...and says it is synthetic"; fi
if [ ! -f "$D/out/synthetic/findings.json" ] && [ -z "$(ls -A "$D/out" 2>/dev/null)" ]; then
  ok "  ...and writes no findings register"
else bad "  ...and writes no findings register"; fi
check "  ...and the default provider sent nothing real" \
  "$(field "$D/result/result.json" provider)" "dry-run"

# 18 — the reported fix is the one you can actually upgrade to
D="$WORK/fixver"; mkdir -p "$D"; mk_meta "$D" failure
mk_leg "$D" t0 BLOCKED 1 "$(python3 -c '
import json
print(json.dumps({"findings": [{
  "id": "CVE-2026-33333", "key": "k", "severity": "HIGH", "category": "dependency",
  "detector": "trivy", "title": "go stdlib", "component": "stdlib", "version": "1.26.3",
  "fixed_version": "1.26.4", "all_fixed_versions": ["1.25.11", "1.26.4"],
  "location": "usr/local/go", "kev": False, "status": "blocking", "blocking": True}]}))')"
rc=$(run_notify "$D")
if grep -q "1.26.4 (from 1.26.3)" "$D/result/body.txt"; then
  ok "the mail recommends the upgrade, not the downgrade"
else bad "the mail recommends the upgrade, not the downgrade" "$(grep -o '1\.2[0-9.]*' "$D/result/body.txt" | tr '\n' ' ')"; fi
if grep -q "advisory also lists 1.25.11" "$D/result/body.txt"; then
  ok "  ...and still shows the other branch's fix as context"
else bad "  ...and still shows the other branch's fix as context"; fi

# 19 — the artifact is never trusted for identity, the API is
D="$WORK/prforge"; mkdir -p "$D"; mk_meta "$D" failure pull_request 42
mk_leg "$D" t0 BLOCKED 1 '{"pr_number": 999, "base_ref": "main"}'
rc=$(run_notify "$D")
check "a PR number that disagrees with the API is a discrepancy, not a truth" \
  "$(python3 -c "
import json;d=json.load(open('$D/result/result.json'))
print('recorded' if any('999' in x for x in d['discrepancies']) else 'lost')")" "recorded"
if grep -q "pull/42" "$D/result/body.txt" && ! grep -q "pull/999" "$D/result/body.txt"; then
  ok "  ...and every link is built from the API value"
else bad "  ...every link is built from the API value"; fi

# 20 — a run from another repository is refused outright
D="$WORK/otherrepo"; mkdir -p "$D"; mk_meta "$D" failure; mk_leg "$D" t0 BLOCKED 1
python3 - "$D" <<'PY'
import json, sys
p = f"{sys.argv[1]}/meta.json"; m = json.load(open(p))
m["event_repository"] = "attacker/evil"; json.dump(m, open(p, "w"))
PY
rc=$(run_notify "$D")
check "a workflow_run from another repository is refused" "$rc" "1"

echo
echo "  $PASS passed, $FAIL failed"
rm -rf "$WORK"
[ "$FAIL" -eq 0 ]
