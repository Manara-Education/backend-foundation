# Security CI — backend-foundation

How the **Security Gate** works, what it does and does not cover, how to
reproduce every scan on a laptop, and what to do when it blocks you.

The gate is mandatory: a pull request into `develop` or `main` cannot merge
while it is failing, and a release cannot deploy without a passing verdict on
the exact commit being released.

---

## 1. When it runs

| Trigger | What is assessed |
|---|---|
| `pull_request` → `develop`/`main` (opened, synchronize, reopened, ready_for_review, edited) | the PR's **head commit** |
| `push` → `develop`/`main` (including every PR merge) | the pushed commit |
| `schedule` — `23 3 * * *` UTC | **both** `develop` and `main`, at their current revisions |
| `workflow_dispatch` | whatever ref you dispatch against |

Two things worth knowing about the schedule. GitHub only ever runs scheduled
workflows from the **default branch** (`develop`), so a scan of `main` would not
happen by itself — `security.yml` therefore builds an explicit two-target matrix
on `schedule` and records the head SHA of each branch. And GitHub's scheduler is
best-effort: runs are delayed under load and occasionally dropped, which is why
the cron sits at `:23` rather than on the hour, and why the daily scan is a
safety net rather than the only line of defence.

There are **no workflow-level path filters**. A path filter would stop the
workflow from running on a PR that only touches, say, `README.md` — and a
required check that never reports leaves the PR permanently unmergeable. Every
PR gets a real verdict.

`merge_group` is deliberately absent: this repository has no merge queue
configured (verified against the rulesets API). If one is ever enabled, add
`merge_group: [checks_requested]` and validate the queued merge revision.

## 2. What is scanned

| Report id | Tool | Covers |
|---|---|---|
| `deps-osv` | osv-scanner 2.5.1 | Every Maven dependency, direct and transitive, from the **CycloneDX SBOM** |
| `deps-trivy` | Trivy 0.74.0 | Second opinion on dependencies; picks up vendor advisories OSV lags on |
| `sast-codeql` | CodeQL (bundle v2.26.4) | `java-kotlin` static analysis |
| `secrets-gitleaks` | gitleaks 8.30.1 | Committed history, plus the PR's own commit range |
| `config-trivy` | Trivy | Dockerfile, `docker-compose.yml`, GitHub Actions workflows |
| `image-trivy` | Trivy | The built container image — OS packages **and** the application layers |
| `deps-diff-review` | `dependency-review-action` | The PR's dependency **difference**, against the GitHub Advisory Database |

**Why both a diff review and a full dependency scan.** `dependency-review` only
sees what a PR *changed*. It cannot see a vulnerability disclosed yesterday in a
dependency the PR never touched — which is the single most common way a
"previously green" branch becomes unsafe. It is an addition to full scanning,
never a replacement, and it is the one report the policy marks PR-only.

**Why the SBOM rather than `pom.xml`.** `pom.xml` alone resolves **19** packages.
The CycloneDX SBOM resolves **142** — the whole transitive graph Spring Boot
actually pulls in. Scanning the manifest would have missed the Tomcat CVEs that
first made this gate fail, because `tomcat-embed-core` is never named in
`pom.xml`; it arrives through `spring-boot-starter-web`.

The SBOM is generated with the CycloneDX Maven plugin invoked as a CLI goal, so
`pom.xml` is not modified to accommodate CI:

```
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputFormat=json -DoutputName=sbom.cdx -DincludeTestScope=false
```

The `.cdx.json` suffix is required — osv-scanner rejects SBOMs whose filename it
cannot classify.

## 3. What blocks

From `.github/security/policy.yml`, which is the single source of truth. The
workflow YAML decides nothing; `.github/security/evaluate.py` reads that policy
and makes every judgement.

- **HIGH** and **CRITICAL** block.
- Anything on the **CISA KEV** catalogue blocks **regardless of severity label**.
  Confirmed exploitation in the wild outranks a base score computed in the
  abstract.
- **Any detected secret** blocks. The remedy for a false positive is a narrow,
  approved exception — not a lowered threshold.
- MEDIUM and below are recorded in the register and reported, but do not block.

The gate also fails — separately and explicitly — when it *cannot* reach a
verdict: a scanner errored, was cancelled or was unexpectedly skipped; a required
report is missing, empty or unparseable; mandatory intelligence is unavailable or
older than the freshness window; or an exception is expired or malformed.

Exit codes: **0** passed · **1** blocking findings · **2** could not be
evaluated. Both non-zero codes block; they read differently so you can tell
"we found something" from "we could not look".

### Severity mapping between tools

Normalised scale: `CRITICAL > HIGH > MEDIUM > LOW > INFO`.

| Tool | Source of truth | Notes |
|---|---|---|
| osv-scanner | GHSA severity word | GHSA's `MODERATE` maps to `MEDIUM`; falls back to computing a CVSS v3.1 base score from the vector |
| Trivy | its own five words | identity mapping, written out so a future label change shows up as a diff |
| CodeQL / SARIF | rule `security-severity` number | scored on the CVSS bands; SARIF `level` (error/warning/note) is only a fallback, because it describes loudness, not danger |
| gitleaks | — | ungraded by design; every finding blocks |

An `UNKNOWN` severity maps to **MEDIUM**, never dropped. Dropping it would
silently delete a finding nobody has scored yet; mapping it to HIGH would make
the gate unusable and push people toward suppressions.

## 4. Vulnerability intelligence and freshness

Sources are registered in `.github/security/sources.yml`. Mandatory: **OSV**,
**GitHub Advisory Database**, **Trivy DB**, **CISA KEV**. Advisory: NVD.

Every run refreshes intelligence *before* any scanner runs and records what it
got — retrieval time, upstream revision, entry counts — in an
`intelligence-manifest.json` that ships as an artifact. The maximum age is
**24 hours**. Exceeding it fails the gate.

Each scanner must provably use that recorded data. osv-scanner is pointed at the
pre-downloaded snapshot with `OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY` plus
`--offline-vulnerabilities`, so it physically cannot fetch its own copy. Trivy
ships its own OCI-distributed database and cannot be pointed at that snapshot, so
each Trivy job writes back the database revision it actually used, and the
evaluator holds it to the same 24-hour window.

Trivy's freshness is measured from `UpdatedAt` — when upstream *built* the
database — not from when we downloaded it. Re-downloading a stale database does
not make it fresh.

> **Retrieval freshness is not publication completeness.**
> A fresh download proves the data we reasoned from was current. It cannot prove
> that every vulnerability disclosed in that window has been published upstream,
> ingested by OSV, or matched to our inventory. That gap is real and unclosable
> from inside CI. It is why the daily scan exists: it is the mechanism that
> catches what the pre-merge scan could not have known.

## 5. Reproducing a failure locally

Install the same pinned versions CI uses (`brew install trivy osv-scanner
gitleaks`, or see `.github/security/install-scanners.sh` for the pinned,
checksum-verified download CI performs).

```bash
# Refresh intelligence exactly as CI does, into ./intel
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputFormat=json -DoutputName=sbom.cdx -DincludeTestScope=false
python3 .github/security/refresh-intelligence.py \
  --out intel --ecosystems maven \
  --probe-file target/sbom.cdx.json \
  --repository Manara-Education/backend-foundation

# Dependencies (exit 1 here means FINDINGS, not a tool error)
OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY=intel/osv-db \
  osv-scanner scan source --sbom=target/sbom.cdx.json \
  --offline-vulnerabilities --format=json

trivy fs . --scanners vuln --format json

# Configuration and image
trivy config .
docker build -t backend-foundation:local .
trivy image backend-foundation:local

# Secrets — `git`, NOT `dir`
gitleaks git . --redact
```

**Use `gitleaks git`, never `gitleaks dir`.** `dir` walks the working tree and
reads gitignored files. On this repository that means it reads
`application-local.properties` — a real local credentials file that is correctly
untracked — and reports it as a leak. `git` reads commits, so it sees exactly
what was actually committed.

To run the full gate decision locally, collect reports into `reports/` using the
naming `reports/<id>.<format>.json` plus `reports/status/<id>.status`, then:

```bash
python3 .github/security/evaluate.py \
  --reports-dir reports --manifest intel/intelligence-manifest.json \
  --event push --ref develop --revision "$(git rev-parse HEAD)" \
  --repository Manara-Education/backend-foundation \
  --workspace "$PWD"
```

### Proving the gate itself still works

`.github/security/verify-gate.sh` runs 23 fixture cases against the evaluator —
clean pass, real blocking finding, scanner failure, cancellation, unexpected
skip, missing/empty/unparseable report, stale and unavailable intelligence,
stale scanner database, secrets, KEV escalation, expired/invalid/narrow
exceptions, event applicability, and per-branch resolution. Run it after any
change to the policy or the evaluator:

```bash
bash .github/security/verify-gate.sh intel/intelligence-manifest.json <an-osv-report.json>
```

## 6. Findings, remediation and exceptions

Findings persist in `.github/security/findings.json`. Each carries a stable id
and its aliases (CVE / GHSA / OSV / vendor, deduplicated), severity and how it
was derived, component and version, the fix version that is actually reachable
from the installed version, detection method, first-seen and last-assessed
timestamps, and **which branches it is present on**.

That last point matters: a finding is cleared **per branch**, and only because
the assessed revision no longer contains it. Fixing something on `develop` does
**not** clear it for `main` or for a deployed image. A PR being opened or merged
proves nothing on its own — only an assessed revision does.

### Exceptions

An exception is narrow, owned, approved and time-boxed. Add it to
`.github/security/exceptions.yml` with `id`, `scope` (a specific advisory, plus
component/version/path/branch as applicable), `reason` written as evidence, a
real `owner`, an `approval` link, and an `expires` date at most 90 days out. A
missing field, an unparseable date, or a past expiry **fails the gate** — an
expired exception stops suppressing automatically, with nobody having to
remember it.

There is currently **no** active exception in this repository. The known
blocking findings are being fixed, not excepted.

## 7. Deployment enforcement

`deploy-production.yml` will not deploy a revision that has not passed.

Before anything is deployed it resolves the release tag to its exact commit,
then **polls** for a `Security Gate` check run on that commit — polling rather
than sampling, because the release chain and the branch scan run concurrently
and the gate may still be in flight. A failure stops the deployment immediately;
a missing verdict is treated as a failure, never as a pass. If the passing
verdict is older than the policy's freshness window, a fresh scan is dispatched
and its result is required instead of the stale one.

The image is then scanned **by immutable digest**, so what was scanned is
provably what is released — not merely a tag that pointed at it at the time.

## 8. Dependabot

Dependabot PRs run the same gate as human PRs; there is no exemption. The
auto-merge workflow additionally requires a check run named exactly
`Security Gate` to exist and to have concluded `success` on the head commit
before it enables auto-merge. Previously it accepted `skipped` and `neutral` as
passing and could sample the check list before the gate's check run existed —
both would have let an unscanned update merge itself.

Dependabot runs receive *Dependabot* secrets rather than Actions secrets. Nothing
in `security.yml` depends on a secret, so the scans work unchanged on Dependabot
and fork PRs. No deployment credential is reachable from PR execution.

## 9. Coverage limitations — read this before trusting a green check

A passing gate means: the required assessments completed, against intelligence
inside the freshness window, and nothing matched what the policy blocks on. It
does **not** mean the revision is free of vulnerabilities.

1. **Coverage is bounded by the registered sources.** Something no source has
   published is invisible here.
2. **No private, embargoed or unpublished disclosures.** There is no live
   bug-bounty feed. Public bug-bounty reports are covered only once the vendor
   has acknowledged them *and* they have become an advisory that reaches OSV or
   Trivy.
3. **OWASP is guidance, not a feed.** The Top 10 and ASVS say what to verify;
   they publish no per-package advisories.
4. **No maximum check age at merge time.** GitHub treats a passed required check
   as passing regardless of age. `security-revalidate.yml` re-runs assessments
   on open PRs daily and on demand, which narrows that window to about a day —
   it does not close it. There is no atomic protection against an advisory
   published between the last scan and the merge button.
5. **PRs assess the head commit, not the merge result.** A vulnerability
   introduced by the base branch after the PR forked is caught by the `develop`
   push scan and the daily scan, not by the PR's own check.
6. **A matrix job's `result` is an aggregate.** On a scheduled run assessing two
   branches, one failing leg fails the job — which is the safe direction, but the
   per-branch detail is in the report, not the check name.
7. **SAST finds what its rules describe.** CodeQL has no rule for most business
   logic flaws. An absent rule is not evidence of safety.
8. **Admin bypass has been removed** from the `develop` and `main` rulesets, but
   a repository admin can still edit a ruleset. Enforcement is a control, not a
   guarantee, against someone with admin rights.

## 10. OWASP coverage map

Verified current editions: **OWASP Top 10:2025**, **API Security Top 10:2023**,
**ASVS 5.0**.

| Category | Automated coverage here | Gap |
|---|---|---|
| A01 Broken Access Control / API1 BOLA, API5 BFLA | CodeQL taint rules; the application's own authorization tests | Object-ownership and role logic is **not** provable by a scanner. Needs review and targeted tests. |
| A02 Security Misconfiguration / API8 | `trivy config` over Dockerfile, Compose and workflows; Spring profile assertions in tests | Runtime configuration of the deployed host is out of scope of this repo's CI. |
| A03 Software Supply Chain Failures | osv-scanner + Trivy + dependency-review over the full SBOM; pinned action SHAs; pinned, checksum-verified scanner binaries | Build-time compromise of an upstream artifact is not detected here. |
| A04 Cryptographic Failures | CodeQL crypto rules; dependency advisories for TLS libraries | Key management and at-rest configuration live in infrastructure. |
| A05 Injection | CodeQL SQL/command/path injection; Spring Data parameter binding | Stored XSS in rendered rich text is primarily a frontend concern. |
| A06 Insecure Design | — | Not automatable. Design review. |
| A07 Authentication Failures / API2 | Dependency advisories for Spring Security and the session stack; session and OTP tests | Rate-limit and OTP-abuse behaviour needs its own integration tests. |
| A08 Software or Data Integrity Failures | Immutable-digest image scanning; the deployment gate; secret scanning | — |
| A09 Logging and Alerting Failures | — | No automated check. Manual verification. |
| A10 Mishandling of Exceptional Conditions | CodeQL error-handling rules | Partial. |
| API4 Unrestricted Resource Consumption | — | Rate limiting is verified by application tests, not by this gate. |
| API7 SSRF | CodeQL SSRF rules on outbound request construction | Needs targeted tests where remote URLs are accepted. |

Gaps are listed because they are real. A category with no automated check is
tracked for manual verification, not quietly counted as covered.

## 11. Troubleshooting

**"the scanner job ended 'failure'"** — the scanner itself broke. Open that job's
log. The gate deliberately does not let a broken scanner look like a clean one.

**osv-scanner exited 1** — that means it *found* something, and is not an error.
The workflow distinguishes exit 1 from a genuine crash.

**"no database revision was recorded for this scanner job"** — a Trivy job did
not write its `reports/intel/<id>.trivydb.json` fragment, so the data it used
cannot be shown to be fresh. Usually the step was edited or reordered.

**"Source 'x' is N hours old"** — intelligence went stale, normally an upstream
outage. Re-run; if it persists, the upstream feed is down and the gate is
correctly refusing to certify anything.

**A finding you believe is wrong** — do not lower the threshold and do not add a
blanket suppression. Either fix it, or open a narrow exception with evidence and
an approval. If a tool is genuinely misreporting, record that in the exception's
reason.

**The check is not appearing on a PR** — the PR branch must contain
`security.yml`. A branch that forked before the gate landed needs a rebase.

## 12. Blocking-report email alerts

Every assessment that ends **BLOCKED**, and every assessment that could not be
completed, emails a report. A clean pass sends nothing.

### Who receives it, and how that is configured

Three settings, none of which live in this repository:

| Setting | Kind | Value |
|---|---|---|
| `SECURITY_ALERT_EMAIL_TO` | repository **variable** | `hamedarfat9@gmail.com` |
| `SECURITY_ALERT_EMAIL_FROM` | repository **variable** | a verified sender on `manara-edu.com` |
| `SECURITY_ALERT_RESEND_API_KEY` | repository **secret** | a Resend key restricted to sending |

The recipient is a variable rather than a literal in the workflow so that
changing it is an audited settings change rather than a pull request, and it is
never derived from anything a pull request can write.

```bash
gh variable set SECURITY_ALERT_EMAIL_TO   --body 'hamedarfat9@gmail.com'      -R <owner>/<repo>
gh variable set SECURITY_ALERT_EMAIL_FROM --body 'security@manara-edu.com'    -R <owner>/<repo>
gh secret   set SECURITY_ALERT_RESEND_API_KEY                                 -R <owner>/<repo>
```

`SECURITY_ALERT_RESEND_API_KEY` is deliberately **not** the application's
existing `RESEND_API_KEY`. That one lives in the `Production` environment and
belongs to the running application; a notifier gated behind a deployment
environment would not fire for a pull request, which is most of what it needs to
report on. Use a separate, send-only key.

The sender must be on a domain verified with Resend. An unverified sender is
rejected at the API, which fails the notify job — visibly, and without touching
the security verdict.

### The trust boundary

`security.yml` runs pull-request code: it builds the image, resolves npm and
Maven dependencies, and runs scanners over a tree the pull request controls. On
a fork pull request that is a tree anyone can write. A mail credential in that
workflow would sit one install script away from whoever opened the PR.

So the notifier is a **separate workflow**, `security-notify.yml`, triggered by
`workflow_run`. What that buys, precisely:

* It runs from the **default branch** revision, whichever branch was assessed.
* It never checks out, sources, or executes any part of the assessed code.
* It reads exactly one artifact and treats every byte of it as hostile input:
  schema version enforced, size and entry counts capped, path traversal
  rejected on extraction, everything HTML-escaped, nothing passed through a
  shell.
* Run identity, the pull request number, the base branch and the head SHA are
  resolved **from the GitHub API**, never from the artifact. When the artifact
  disagrees, the API wins and the disagreement is reported in the email as a
  discrepancy rather than silently resolved.
* The artifact is fetched by id from that exact run and attempt — not from "the
  latest run on this branch", which would let a later green run stand in for the
  blocked one being reported.
* Permissions are `contents: read`, `actions: read`, `pull-requests: read`.
  Nothing can write.

**The notifier cannot change the security verdict.** The required check is
`Security Gate`, decided in the other workflow and already reported before this
one starts. If mail fails, mail did not go out and the gate's answer is
untouched. That direction is the only safe one.

### What is sent, and when

| Situation | Result |
|---|---|
| Complete clean assessment | no email |
| One or more blocking findings | one email, every blocker in it, subject `[SECURITY BLOCKED]` |
| MEDIUM/LOW only, not blocking under policy | no email; they stay in the report |
| A scanner failed but another found blockers | the blocking email, **plus** an explicit coverage gap |
| Failed, timed out, or cancelled with no usable verdict | one email, subject `[SECURITY SCAN ERROR / INCOMPLETE]` |

One consolidated email per source run and attempt, covering every matrix leg,
each labelled with its scope — PR candidate, `develop`, `main`, or an image
digest. It is never one email per CVE, and a second leg's blockers are never
dropped because the first leg failed. Pre-existing blockers are included, not
only newly introduced ones.

Subject format:

```
[Manara][SECURITY BLOCKED][<repository>][<scope>] <N> blocking findings
```

### Delivery, retries and duplicates

Transient failures — timeout, 429, 5xx — are retried with bounded exponential
backoff, honouring `Retry-After`. Each retry reuses the same idempotency key and
an identical payload, so a retry cannot become a second email.

A permanent failure (missing key, unverified sender, 401) **fails the notify
job** with an actionable summary. It does not fail quietly, because a silent
notifier is indistinguishable from a clean assessment.

The idempotency key is derived from repository, source run id, source attempt
and the consolidated scope. Two limits worth knowing:

* Resend retains idempotency keys for **24 hours**. Beyond that window the
  provider will not deduplicate, and neither will this — a re-run of the same
  attempt a day later can produce a second email.
* Deduplication is deliberately scoped so that a **new attempt**, a different
  branch, or the next day's scheduled assessment is **not** suppressed, even
  when the findings are identical. Unchanged findings are still news the second
  day.

What is recorded in the job summary: the provider's message id and the outcome.
The wording distinguishes *accepted by the provider* from *delivered* — the
first is all an API response can prove.

### Redaction

Secret **values** never leave the runner. A secret-scanning finding is reported
by rule, file and line — the actionable half — with the matched value replaced
by a redaction marker that says a value was removed. The same rules are applied
to the Markdown attachment as to the body. `.env` contents, tokens and personal
data are excluded.

If the full report exceeds the provider's attachment limit, the email says so,
carries the summary, and links the complete artifact. Findings are never
silently truncated.

### Trying it without waiting for a red build

`security-notify.yml` has a `workflow_dispatch` path:

* **dry-run** (default) renders the whole message and sends nothing.
* **test** actually sends, with `TEST` in the subject and "Synthetic data" in
  the body. It writes no findings register and touches no required check.

Locally, against the mock provider — no network, no mail:

```bash
bash .github/security/verify-notify.sh     # 65 checks
bash .github/security/verify-gate.sh <manifest.json> <osv-report.json>   # 31 checks
```

### Activation, and what is not yet proved

These are three different things and are worth keeping apart:

| Stage | State |
|---|---|
| **Implemented** | yes — workflow, notifier and 65 fixture checks are in this repository |
| **Activated** | yes, as of 2026-09-10, by merging `security-notify.yml` to **`develop`** — see the note below on which branch that is. Both repository variables are set: `SECURITY_ALERT_EMAIL_TO` = `hamedarfat9@gmail.com`, `SECURITY_ALERT_EMAIL_FROM` = `no-reply@manara-edu.com` |
| **Delivery verified** | **no.** `SECURITY_ALERT_RESEND_API_KEY` is still unset, so no real message has been sent to `hamedarfat9@gmail.com` |

What that combination does TODAY, exactly: a completed assessment triggers the
notifier, the notifier resolves the run, downloads the verdict and renders the
report — and then exits 1 with

```
::error title=Security notification::the mail credential is not configured:
environment variable SECURITY_ALERT_RESEND_API_KEY is empty. The report was
built but could not be sent. ... The security verdict is unaffected.
```

So the alert is a red job with the report attached as an artifact rather than
an email. That is the intended failure mode and not a workaround: a notifier
that cannot send must not look like a clean assessment. Blocking findings keep
blocking either way — the gate never consults the notifier.

The one remaining step is the secret. It should be a **send-only** Resend key,
not the application's `RESEND_API_KEY`: that one lives in the `Production`
environment, is scoped to a deployment job, and would give a workflow that
processes untrusted PR output a production credential for no benefit.

#### The branch that activates this is `develop`, not `main`

`workflow_run` dispatches the workflow file from the repository's **default
branch**, and this repository's default branch is `develop`:

```console
$ gh api repos/Manara-Education/<repo> --jq .default_branch
develop
```

`main` is the release branch — a tag push from it is what deploys — so a
notifier sitting only on `main` is inert. That mistake was made here and is
recorded rather than quietly corrected: two pull requests put this workflow on
`main` first, every Security assessment afterwards completed normally, and not
one `Security notification` run appeared. Merging the same file to `develop` is
what registered the workflow and made `workflow_run` fire.

Keeping the copy on `main` is harmless and is left in place, so that a future
change of default branch does not silently switch the alerts off again.
