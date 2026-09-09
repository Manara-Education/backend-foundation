#!/usr/bin/env bash
# Controlled verification of the gate's decision logic.
#
# Every case below is a fixture. Nothing here touches a real branch, a real
# deployment, or a real credential. The point is to prove the failure modes
# actually fail — a gate is only worth having if "the scanner broke" and
# "the data was stale" are as red as "we found a critical CVE".
set -uo pipefail

SHARED="$(cd "$(dirname "$0")" && pwd)"
# Resolve the checkout root rather than hardcoding anyone's home directory,
# so this suite runs identically on a laptop and on a runner.
REPO_ROOT="$(cd "$SHARED/../.." && pwd)"
WORK="$(mktemp -d)"
MANIFEST_OK="${1:?usage: verify-gate.sh <good-manifest.json> <real-osv-report.json>}"
OSV_REAL="${2:?}"
PASS=0; FAIL=0

run_case() {
  local name="$1" expected="$2" dir="$3"; shift 3
  local out; out="$(python3 "$SHARED/evaluate.py" \
      --reports-dir "$dir" --manifest "$dir/manifest.json" \
      --policy "$SHARED/policy.yml" --exceptions "${EXC:-$SHARED/exceptions.yml}" \
      --event "${EVENT:-push}" --ref "${REF:-develop}" --revision "${REV:-abc1234}" \
      --repository Manara-Education/backend-foundation \
      --workspace "$REPO_ROOT" \
      --register-out "$dir/findings.json" --report-out "$dir/report.md" 2>&1)"
  local code=$?
  if [ "$code" = "$expected" ]; then
    printf '  \033[32mPASS\033[0m  %-56s exit=%s\n' "$name" "$code"; PASS=$((PASS+1))
  else
    printf '  \033[31mFAIL\033[0m  %-56s exit=%s (wanted %s)\n' "$name" "$code" "$expected"
    echo "$out" | sed 's/^/          /' | head -6; FAIL=$((FAIL+1))
  fi
}

# Build a report set with all six required reports clean.
mk_clean() {
  local d="$1"; mkdir -p "$d/status" "$d/intel"
  cp "$MANIFEST_OK" "$d/manifest.json"
  echo '{"results":[]}'  > "$d/deps-osv.osv.json"
  echo '{"Results":[]}'  > "$d/deps-trivy.trivy.json"
  echo '{"Results":[]}'  > "$d/config-trivy.trivy.json"
  echo '{"Results":[]}'  > "$d/image-trivy.trivy.json"
  echo '[]'              > "$d/secrets-gitleaks.gitleaks.json"
  echo '{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"CodeQL","rules":[]}},"results":[]}]}' > "$d/sast-codeql.sarif"
  for r in deps-osv deps-trivy config-trivy image-trivy secrets-gitleaks sast-codeql; do
    echo success > "$d/status/$r.status"
  done
  local fresh; fresh="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for r in deps-trivy image-trivy; do
    printf '{"Version":"0.74.0","VulnerabilityDB":{"Version":2,"UpdatedAt":"%s"}}\n' "$fresh" > "$d/intel/$r.trivydb.json"
  done
  # config-trivy gets what `trivy config` ACTUALLY reports: a check bundle and
  # no vulnerability database. A fixture that handed it a vulnerability DB would
  # be testing a situation that cannot occur.
  printf '{"Version":"0.74.0","CheckBundle":{"Digest":"sha256:1583562f8b90ed2a071b99f0e5ffff6b57e4ceb6ca3e4796577b4e6a339eb74c","DownloadedAt":"%s"}}\n' \
    "$fresh" > "$d/intel/config-trivy.trivydb.json"
}

echo "Security Gate decision logic"
echo

# 1 — clean assessment passes
C="$WORK/clean"; mk_clean "$C"
run_case "clean assessment passes" 0 "$C"

# 1b — running twice must not duplicate the register
C2="$WORK/idem"; mk_clean "$C2"
cp "$OSV_REAL" "$C2/deps-osv.osv.json"
python3 "$SHARED/evaluate.py" --reports-dir "$C2" --manifest "$C2/manifest.json" \
  --policy "$SHARED/policy.yml" --exceptions "$SHARED/exceptions.yml" --event push \
  --ref develop --revision abc1234 --repository Manara-Education/backend-foundation \
  --workspace "$REPO_ROOT" \
  --register-out "$C2/findings.json" --report-out "$C2/r1.md" >/dev/null 2>&1
n1=$(python3 -c "import json;print(len(json.load(open('$C2/findings.json'))['findings']))")
python3 "$SHARED/evaluate.py" --reports-dir "$C2" --manifest "$C2/manifest.json" \
  --policy "$SHARED/policy.yml" --exceptions "$SHARED/exceptions.yml" --event push \
  --ref develop --revision abc1234 --repository Manara-Education/backend-foundation \
  --workspace "$REPO_ROOT" \
  --register-out "$C2/findings.json" --report-out "$C2/r2.md" >/dev/null 2>&1
n2=$(python3 -c "import json;print(len(json.load(open('$C2/findings.json'))['findings']))")
first=$(python3 -c "import json;print(json.load(open('$C2/findings.json'))['findings'][0]['first_seen'])")
if [ "$n1" = "3" ] && [ "$n2" = "3" ]; then
  printf '  \033[32mPASS\033[0m  %-56s %s then %s findings\n' "repeat scan preserves findings without duplication" "$n1" "$n2"; PASS=$((PASS+1))
else
  printf '  \033[31mFAIL\033[0m  %-56s %s then %s\n' "repeat scan duplication" "$n1" "$n2"; FAIL=$((FAIL+1))
fi

# 2 — a real blocking finding fails
B="$WORK/blocking"; mk_clean "$B"; cp "$OSV_REAL" "$B/deps-osv.osv.json"
run_case "real CRITICAL dependency finding blocks" 1 "$B"

# 3 — scanner failure cannot yield success
F="$WORK/scanfail"; mk_clean "$F"; echo failure > "$F/status/deps-osv.status"
run_case "scanner job failure blocks" 1 "$F"

Fc="$WORK/cancel"; mk_clean "$Fc"; echo cancelled > "$Fc/status/sast-codeql.status"
run_case "scanner job cancellation blocks" 1 "$Fc"

S="$WORK/skip"; mk_clean "$S"; echo skipped > "$S/status/config-trivy.status"
run_case "unexpected skip of a required scanner blocks" 1 "$S"

M="$WORK/missing"; mk_clean "$M"; rm "$M/image-trivy.trivy.json"
run_case "missing report blocks (not treated as empty)" 1 "$M"

U="$WORK/unparse"; mk_clean "$U"; echo 'not json {{' > "$U/deps-trivy.trivy.json"
run_case "unparseable report blocks" 1 "$U"

E="$WORK/emptyrep"; mk_clean "$E"; : > "$E/config-trivy.trivy.json"
run_case "empty report blocks" 1 "$E"

# 4 — intelligence failures cannot yield success
ST="$WORK/stale"; mk_clean "$ST"
python3 - "$ST/manifest.json" <<'PY'
import json,sys,datetime as dt
p=sys.argv[1]; d=json.load(open(p))
old=(dt.datetime.now(dt.timezone.utc)-dt.timedelta(hours=48)).isoformat()
d["sources"]["osv"]["retrieved_at"]=old
json.dump(d,open(p,"w"))
PY
run_case "stale mandatory intelligence blocks" 2 "$ST"

MI="$WORK/missintel"; mk_clean "$MI"
python3 - "$MI/manifest.json" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p))
d["sources"]["cisa-kev"]={"status":"failed","error":"simulated outage"}
json.dump(d,open(p,"w"))
PY
run_case "unavailable mandatory source blocks (no empty pass)" 2 "$MI"

NM="$WORK/nomanifest"; mk_clean "$NM"; rm "$NM/manifest.json"
run_case "absent intelligence manifest blocks" 2 "$NM"

SD="$WORK/staledb"; mk_clean "$SD"
printf '{"Version":"0.74.0","VulnerabilityDB":{"Version":2,"UpdatedAt":"2026-08-01T00:00:00Z"}}\n' > "$SD/intel/deps-trivy.trivydb.json"
run_case "scanner using a stale DB blocks" 1 "$SD"

ND="$WORK/nodb"; mk_clean "$ND"; rm "$ND/intel/image-trivy.trivydb.json"
run_case "scanner not recording its DB revision blocks" 1 "$ND"

# A config scan is held to its CHECK BUNDLE, not to the vulnerability database.
# `trivy config` never downloads the vulnerability DB, so requiring one from it
# failed a job that was working correctly. These two cases pin that behaviour:
# a bundle is accepted, and the absence of one still blocks.
CB="$WORK/checkbundle"; mk_clean "$CB"
printf '{"Version":"0.74.0","CheckBundle":{"Digest":"sha256:1583562f8b90ed2a071b99f0e5ffff6b57e4ceb6ca3e4796577b4e6a339eb74c","DownloadedAt":"%s"}}\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$CB/intel/config-trivy.trivydb.json"
run_case "config scan accepted on its check bundle alone" 0 "$CB"

NB="$WORK/nobundle"; mk_clean "$NB"
printf '{"Version":"0.74.0"}\n' > "$NB/intel/config-trivy.trivydb.json"
run_case "config scan with neither bundle nor database blocks" 1 "$NB"

# 5 — secrets
SEC="$WORK/secret"; mk_clean "$SEC"
echo '[{"RuleID":"stripe-access-token","File":"src/config.ts","StartLine":12,"Commit":"deadbeefcafe"}]' > "$SEC/secrets-gitleaks.gitleaks.json"
run_case "detected secret blocks" 1 "$SEC"

# 6 — KEV escalation below the severity threshold
KEV="$WORK/kev"; mk_clean "$KEV"
KEVCVE=$(python3 -c "import json;print(json.load(open('$MANIFEST_OK'))['sources']['cisa-kev']['kev_cve_ids'][0])")
python3 - "$KEV/deps-osv.osv.json" "$KEVCVE" <<'PY'
import json,sys
out={"results":[{"source":{"path":"pom.xml"},"packages":[{"package":{"name":"demo","version":"1.0.0"},
 "vulnerabilities":[{"id":sys.argv[2],"aliases":[sys.argv[2]],"summary":"low-labelled but exploited",
 "database_specific":{"severity":"LOW"},"affected":[]}]}]}]}
json.dump(out,open(sys.argv[1],"w"))
PY
run_case "LOW finding on the CISA KEV catalogue still blocks" 1 "$KEV"

# 7 — exceptions
EXPIRED="$WORK/expired"; mk_clean "$EXPIRED"
cat > "$WORK/exc-expired.yml" <<'YML'
exceptions:
  - id: EXC-TEST-001
    scope: {finding: CVE-2026-65905, component: "org.apache.tomcat.embed:tomcat-embed-core"}
    reason: fixture
    owner: Mohamed-Hamza
    approval: https://example.invalid/fixture
    expires: 2020-01-01
YML
cp "$OSV_REAL" "$EXPIRED/deps-osv.osv.json"
EXC="$WORK/exc-expired.yml" run_case "expired exception blocks" 1 "$EXPIRED"

INVALID="$WORK/invalid"; mk_clean "$INVALID"
cat > "$WORK/exc-invalid.yml" <<'YML'
exceptions:
  - id: EXC-TEST-002
    scope: {finding: CVE-2026-65905}
    reason: no owner, no approval, no expiry
YML
EXC="$WORK/exc-invalid.yml" run_case "incomplete exception blocks" 1 "$INVALID"

VALID="$WORK/valid"; mk_clean "$VALID"; cp "$OSV_REAL" "$VALID/deps-osv.osv.json"
FUTURE=$(python3 -c "import datetime as d;print((d.date.today()+d.timedelta(days=30)).isoformat())")
cat > "$WORK/exc-valid.yml" <<YML
exceptions:
  - id: EXC-TEST-003
    scope:
      finding: CVE-2026-65905
      component: "org.apache.tomcat.embed:tomcat-embed-core"
      version: "11.0.24"
    reason: fixture covering exactly one advisory
    owner: Mohamed-Hamza
    approval: https://example.invalid/fixture
    expires: ${FUTURE}
YML
# Still exit 1: the OTHER two criticals are not excepted. That is the point —
# a narrow exception must not clear its neighbours.
EXC="$WORK/exc-valid.yml" run_case "narrow exception clears only its own finding" 1 "$VALID"
n_block=$(python3 -c "import json;print(sum(1 for f in json.load(open('$VALID/findings.json'))['findings'] if f['status']=='blocking'))")
n_exc=$(python3 -c "import json;print(sum(1 for f in json.load(open('$VALID/findings.json'))['findings'] if f['status']=='excepted'))")
if [ "$n_block" = "2" ] && [ "$n_exc" = "1" ]; then
  printf '  \033[32mPASS\033[0m  %-56s 2 blocking, 1 excepted\n' "  ...and the other two still block"; PASS=$((PASS+1))
else
  printf '  \033[31mFAIL\033[0m  %-56s %s blocking, %s excepted\n' "  narrow exception scope" "$n_block" "$n_exc"; FAIL=$((FAIL+1))
fi

# 8 — event applicability: the PR-only report is not required on push
PRONLY="$WORK/pronly"; mk_clean "$PRONLY"
run_case "PR-only dependency-diff not required on push" 0 "$PRONLY"

PRREQ="$WORK/prreq"; mk_clean "$PRREQ"
EVENT=pull_request run_case "same report IS required on pull_request" 1 "$PRREQ"

# 9 — a fix on develop must not clear main
BR="$WORK/branch"; mk_clean "$BR"; cp "$OSV_REAL" "$BR/deps-osv.osv.json"
python3 "$SHARED/evaluate.py" --reports-dir "$BR" --manifest "$BR/manifest.json" \
  --policy "$SHARED/policy.yml" --exceptions "$SHARED/exceptions.yml" --event push \
  --ref main --revision main111 --repository Manara-Education/backend-foundation \
  --workspace /x --register-out "$BR/findings.json" --report-out "$BR/m.md" >/dev/null 2>&1
python3 "$SHARED/evaluate.py" --reports-dir "$BR" --manifest "$BR/manifest.json" \
  --policy "$SHARED/policy.yml" --exceptions "$SHARED/exceptions.yml" --event push \
  --ref develop --revision dev111 --repository Manara-Education/backend-foundation \
  --workspace /x --register-out "$BR/findings.json" --report-out "$BR/d.md" >/dev/null 2>&1
echo '{"results":[]}' > "$BR/deps-osv.osv.json"   # remediated on develop only
python3 "$SHARED/evaluate.py" --reports-dir "$BR" --manifest "$BR/manifest.json" \
  --policy "$SHARED/policy.yml" --exceptions "$SHARED/exceptions.yml" --event push \
  --ref develop --revision dev222 --repository Manara-Education/backend-foundation \
  --workspace /x --register-out "$BR/findings.json" --report-out "$BR/d2.md" >/dev/null 2>&1
still_main=$(python3 -c "
import json;d=json.load(open('$BR/findings.json'))
print(sum(1 for f in d['findings'] if 'main' in (f.get('branches') or {})))")
gone_dev=$(python3 -c "
import json;d=json.load(open('$BR/findings.json'))
print(sum(1 for f in d['findings'] if 'develop' in (f.get('branches') or {})))")
if [ "$still_main" = "3" ] && [ "$gone_dev" = "0" ]; then
  printf '  \033[32mPASS\033[0m  %-56s main=%s develop=%s\n' "fix on develop does not clear main" "$still_main" "$gone_dev"; PASS=$((PASS+1))
else
  printf '  \033[31mFAIL\033[0m  %-56s main=%s develop=%s\n' "fix on develop does not clear main" "$still_main" "$gone_dev"; FAIL=$((FAIL+1))
fi

echo
echo "  $PASS passed, $FAIL failed"
rm -rf "$WORK"
[ "$FAIL" -eq 0 ]
